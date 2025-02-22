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

package org.opencastproject.elasticsearch.index;

import org.junit.Assert;
import org.junit.Test;

public class QueryPreprocessorTest {

  @Test
  public void testBlankQueries() {
    Assert.assertEquals("", QueryPreprocessor.sanitize(""));
    Assert.assertEquals("", QueryPreprocessor.sanitize("   "));
  }

  @Test
  public void testDoubleQuotes() {
    // No partial matching for quoted strings
    Assert.assertEquals("\"Hello\"", QueryPreprocessor.sanitize("\"Hello\""));
    Assert.assertEquals("*Hello* \"World\"", QueryPreprocessor.sanitize("Hello \"World\""));

    // Auto-completion in case of missing double-quote
    Assert.assertEquals("*Hello* \"World\"", QueryPreprocessor.sanitize("Hello \"World"));
    Assert.assertEquals("*Hello* \"World Again\"", QueryPreprocessor.sanitize("Hello \"World Again"));

    // Escape double quote within tokens, i.e. ensure whitespace separated tokens
    Assert.assertEquals("*He\\\"llo* *Wor\\\"ld*", QueryPreprocessor.sanitize("He\"llo Wor\"ld"));
  }

  @Test
  public void testWildcards() {
    // Don't escape wildcards occuring as individual tokens
    Assert.assertEquals("*", QueryPreprocessor.sanitize("*"));
    Assert.assertEquals("*", QueryPreprocessor.sanitize(" * "));
    Assert.assertEquals("*?*", QueryPreprocessor.sanitize("?"));
    Assert.assertEquals("*?*", QueryPreprocessor.sanitize(" ? "));

    // Don't escape wildcards occuring within tokens
    Assert.assertEquals("*H*llo* *Worl*d*", QueryPreprocessor.sanitize("H*llo Worl*d"));
    Assert.assertEquals("*H?llo* *Worl?d*", QueryPreprocessor.sanitize("H?llo Worl?d"));
  }

  @Test
  public void testCharacterEscaping() {
    // Unsupported special characters are escaped
    Assert.assertEquals("*\\(* *\\)* *\\[* *\\]* *\\{* *\\}* *\\~* *\\^* *\\:* *\\\\*",
        QueryPreprocessor.sanitize(" ( ) [ ] { } ~ ^ : \\"));

    // Unsupported special characters are not escaped in quoted strings
    Assert.assertEquals("\" ( ) [ ] { } ~ ^ : \\\"", QueryPreprocessor.sanitize("\" ( ) [ ] { } ~ ^ : \\\""));

    // Supported special characters are not escape in quoted strings
    Assert.assertEquals("\"|| && + - ! * ?\"", QueryPreprocessor.sanitize("\"|| && + - ! * ?\""));
  }

  @Test
  public void testUnaryOperators() {
    testUnaryOperator("+");
    testUnaryOperator("-");
    testUnaryOperator("!");
  }

  private void testUnaryOperator(String operator) {
    // Escape operator if operand is missing
    Assert.assertEquals("\\" + operator, QueryPreprocessor.sanitize(operator));
    Assert.assertEquals("\\" + operator, QueryPreprocessor.sanitize(" " + operator + " "));

    // Escape operator if occuring within a token
    Assert.assertEquals("*test\\" + operator + "unit*", QueryPreprocessor.sanitize("test" + operator + "unit"));
    Assert.assertEquals("*test\\" + operator + "unit*", QueryPreprocessor.sanitize("*test" + operator + "unit"));
    Assert.assertEquals("*test\\" + operator + "unit*", QueryPreprocessor.sanitize("test" + operator + "unit*"));
    Assert.assertEquals("*test\\" + operator + "unit*", QueryPreprocessor.sanitize("*test" + operator + "unit*"));
    Assert.assertEquals("*test\\" + operator + "unit\\" + operator + "*",
        QueryPreprocessor.sanitize("test" + operator + "unit" + operator));
    Assert.assertEquals(operator + "*\\" + operator + "test\\" + operator + "unit\\" + operator + "\\" + operator + "*",
        QueryPreprocessor.sanitize(operator + operator + "test" + operator + "unit" + operator + operator));

    // Partial matching for operands
    Assert.assertEquals(operator + "*test\\" + operator + "unit*",
        QueryPreprocessor.sanitize(operator + "test" + operator + "unit"));
    Assert.assertEquals(operator + "*test\\" + operator + "unit*",
        QueryPreprocessor.sanitize(operator + "*test" + operator + "unit"));
    Assert.assertEquals(operator + "*test\\" + operator + "unit*",
        QueryPreprocessor.sanitize(operator + "test" + operator + "unit*"));
    Assert.assertEquals(operator + "*test\\" + operator + "unit*",
        QueryPreprocessor.sanitize(operator + "*test" + operator + "unit*"));

    // Binary operators are escaped when appearing as operangs of unary operators
    Assert.assertEquals(operator + "*\\|\\|*", QueryPreprocessor.sanitize(operator + "||"));
    Assert.assertEquals(operator + "*\\&\\&*", QueryPreprocessor.sanitize(operator + "&&"));

    // Double quotes can be used in argument
    Assert.assertEquals(operator + "\"Hello World\"", QueryPreprocessor.sanitize(operator + "\"Hello World\""));
  }

  @Test
  public void testBinaryOperators() {
    testBinaryOperator("&&");
    testBinaryOperator("||");
  }

  private void testBinaryOperator(String operator) {
    // Escape operator if operands are missing
    Assert.assertEquals("\\" + operator, QueryPreprocessor.sanitize(operator));
    Assert.assertEquals("\\" + operator, QueryPreprocessor.sanitize(" " + operator + " "));
    Assert.assertEquals("*Hello* \\" + operator, QueryPreprocessor.sanitize("Hello " + operator));
    Assert.assertEquals("\\" + operator + " *World*", QueryPreprocessor.sanitize(operator + " World"));

    // Don't escape operator if used correctly
    Assert.assertEquals("*Hello* " + operator + " *World*", QueryPreprocessor.sanitize("Hello " + operator + " World"));
  }

  @Test
  public void testPartialMatches() {
    Assert.assertEquals("*Hello*", QueryPreprocessor.sanitize("Hello"));
    Assert.assertEquals("*Hello*", QueryPreprocessor.sanitize("*Hello"));
    Assert.assertEquals("*Hello*", QueryPreprocessor.sanitize("Hello*"));
    Assert.assertEquals("*Hello*", QueryPreprocessor.sanitize("*Hello*"));
    Assert.assertEquals("*Hello* *World*", QueryPreprocessor.sanitize("Hello World"));
    Assert.assertEquals("*Hello* *World*", QueryPreprocessor.sanitize("Hello* World"));
    Assert.assertEquals("*Hello* *World*", QueryPreprocessor.sanitize("Hello *World"));
    Assert.assertEquals("*Hello* *World*", QueryPreprocessor.sanitize("*Hello* *World*"));
  }

}
