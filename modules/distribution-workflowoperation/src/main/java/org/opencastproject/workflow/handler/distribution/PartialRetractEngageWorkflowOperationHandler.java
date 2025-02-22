/*
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package org.opencastproject.workflow.handler.distribution;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.opencastproject.workflow.handler.distribution.EngagePublicationChannel.CHANNEL_ID;

import org.opencastproject.distribution.api.DownloadDistributionService;
import org.opencastproject.distribution.api.StreamingDistributionService;
import org.opencastproject.job.api.Job;
import org.opencastproject.job.api.JobContext;
import org.opencastproject.mediapackage.MediaPackage;
import org.opencastproject.mediapackage.MediaPackageElement;
import org.opencastproject.mediapackage.MediaPackageElementFlavor;
import org.opencastproject.mediapackage.MediaPackageException;
import org.opencastproject.mediapackage.Publication;
import org.opencastproject.mediapackage.selector.SimpleElementSelector;
import org.opencastproject.search.api.SearchException;
import org.opencastproject.search.api.SearchService;
import org.opencastproject.security.api.UnauthorizedException;
import org.opencastproject.serviceregistry.api.ServiceRegistry;
import org.opencastproject.util.NotFoundException;
import org.opencastproject.workflow.api.WorkflowInstance;
import org.opencastproject.workflow.api.WorkflowOperationException;
import org.opencastproject.workflow.api.WorkflowOperationHandler;
import org.opencastproject.workflow.api.WorkflowOperationInstance;
import org.opencastproject.workflow.api.WorkflowOperationResult;

import org.apache.commons.lang3.StringUtils;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Workflow operation for retracting parts of a media package from the engage player.
 */
@Component(
    immediate = true,
    service = WorkflowOperationHandler.class,
    property = {
        "service.description=Engage Partial Retraction Workflow Operation Handler",
        "workflow.operation=retract-partial"
    }
)
public class PartialRetractEngageWorkflowOperationHandler extends RetractEngageWorkflowOperationHandler {

  /** The logging facility */
  private static final Logger logger = LoggerFactory.getLogger(PartialRetractEngageWorkflowOperationHandler.class);

  private static final String RETRACT_FLAVORS = "retract-flavors";
  private static final String RETRACT_TAGS = "retract-tags";

  @Activate
  @Override
  public void activate(ComponentContext cc) {
    super.activate(cc);
  }

  /**
   * {@inheritDoc}
   *
   * @see org.opencastproject.workflow.api.WorkflowOperationHandler#start(WorkflowInstance, JobContext)
   */
  @Override
  public WorkflowOperationResult start(WorkflowInstance workflowInstance, JobContext context)
          throws WorkflowOperationException {
    WorkflowOperationInstance op = workflowInstance.getCurrentOperation();

    // Check which tags have been configured
    String retractTargetTags = StringUtils.trimToEmpty(op.getConfiguration(RETRACT_TAGS));
    String retractTargetFlavors = StringUtils.trimToEmpty(op.getConfiguration(RETRACT_FLAVORS));

    String[] retractTags = StringUtils.split(retractTargetTags, ",");
    String[] retractFlavors = StringUtils.split(retractTargetFlavors, ",");


    // Configure the download element selector
    SimpleElementSelector retractElementSelector = new SimpleElementSelector();
    for (String flavor : retractFlavors) {
      MediaPackageElementFlavor f = MediaPackageElementFlavor.parseFlavor(flavor);
      logger.debug("Selecting for flavor {}", f);
      retractElementSelector.addFlavor(f);
    }
    for (String tag : retractTags) {
      logger.debug("Selecting for tag {}", tag);
      retractElementSelector.addTag(tag);
    }

    MediaPackage mediaPackage = workflowInstance.getMediaPackage();
    MediaPackage searchMP = null;
    logger.info("Partially retracting {}", mediaPackage.getIdentifier());
    List<Job> jobs;
    try {
      try {
        searchMP = searchService.get(mediaPackage.getIdentifier().toString());
      } catch (NotFoundException e) {
        logger.info("The search service doesn't know media package {}", mediaPackage);
        return createResult(mediaPackage, WorkflowOperationResult.Action.SKIP);
      } catch (UnauthorizedException e) {
        throw new WorkflowOperationException("Not allowed to access media package " + mediaPackage);
      }
      var retractElements = retractElementSelector.select(searchMP, false);
      var retractElementIds = retractElements.stream()
          .map(MediaPackageElement::getIdentifier)
          .collect(Collectors.toSet());
      logger.debug("Found {} matching elements", retractElementIds.size());

      // Pull down the elements themselves
      jobs = retractElements(retractElementIds, searchMP);
      if (jobs.size() < 1) {
        logger.debug("No matching elements found");
        return createResult(mediaPackage, WorkflowOperationResult.Action.CONTINUE);
      }

      for (MediaPackageElement element : retractElements) {
        logger.debug("Removing {} from mediapackage", element.getIdentifier());
        //Remove the element from the workflow mp
        mediaPackage.remove(element);
        searchMP.remove(element);
      }

      // Wait for retraction to finish
      if (!waitForStatus(jobs.toArray(new Job[0])).isSuccess()) {
        throw new WorkflowOperationException("The retract jobs did not complete successfully");
      }
      Job deleteSearchJob = null;
      logger.info("Retracting publication for mediapackage: {}", mediaPackage.getIdentifier().toString());
      deleteSearchJob = searchService.delete(mediaPackage.getIdentifier().toString());
      if (!waitForStatus(deleteSearchJob).isSuccess()) {
        throw new WorkflowOperationException("Mediapackage " + mediaPackage.getIdentifier()
                + " could not be retracted");
      }

      logger.info("Retraction operations complete, republishing updated mediapackage");

      if (!isPublishable(mediaPackage)) {
        throw new WorkflowOperationException("Media package does not meet criteria for publication");
      }

      // Adding media package to the search index
      Job publishJob = null;
      try {
        publishJob = searchService.add(searchMP);
        if (!waitForStatus(publishJob).isSuccess()) {
          throw new WorkflowOperationException("Mediapackage " + mediaPackage.getIdentifier()
                  + " could not be published");
        }
      } catch (SearchException e) {
        throw new WorkflowOperationException("Error publishing media package", e);
      } catch (MediaPackageException e) {
        throw new WorkflowOperationException("Error parsing media package", e);
      }

      logger.info("Publication of {} complete", mediaPackage.getIdentifier());
      return createResult(mediaPackage, WorkflowOperationResult.Action.CONTINUE);
    } catch (Exception e) {
      if (e instanceof WorkflowOperationException) {
        throw (WorkflowOperationException) e;
      } else {
        throw new WorkflowOperationException(e);
      }
    }
  }

  private Publication findPublicationElement(MediaPackage mediaPackage) throws WorkflowOperationException {
    for (Publication element  : mediaPackage.getPublications()) {
      if (CHANNEL_ID.equals(element.getChannel())) {
        logger.debug("Found the publication element");
        return element;
      }
    }
    throw new WorkflowOperationException("Unable to find publication element!");
  }

  /** Media package must meet these criteria in order to be published. */
  //TODO: Move this into some kind of abstract parent class since this is also used in
  //PublishEngageWorkflowOperationHandler
  private boolean isPublishable(MediaPackage mp) {
    boolean hasTitle = !isBlank(mp.getTitle());
    if (!hasTitle) {
      logger.warn("Media package does not meet criteria for publication: There is no title");
    }

    boolean hasTracks = mp.hasTracks();
    if (!hasTracks) {
      logger.warn("Media package does not meet criteria for publication: There are no tracks");
    }

    return hasTitle && hasTracks;
  }

  @Reference(target = "(distribution.channel=download)")
  @Override
  public void setDownloadDistributionService(DownloadDistributionService downloadDistributionService) {
    super.setDownloadDistributionService(downloadDistributionService);
  }

  @Reference
  @Override
  public void setSearchService(SearchService searchService) {
    super.setSearchService(searchService);
  }

  @Reference
  @Override
  public void setServiceRegistry(ServiceRegistry serviceRegistry) {
    super.setServiceRegistry(serviceRegistry);
  }

  @Reference(target = "(distribution.channel=streaming)")
  @Override
  public void setStreamingDistributionService(StreamingDistributionService streamingDistributionService) {
    super.setStreamingDistributionService(streamingDistributionService);
  }

}
