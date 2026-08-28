// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class MultiAnchorGapEngineTest {

  @Test
  void multiInfixBasicMatch() {
    String regex = ".*foo.*bar.*baz.*";
    Pattern pattern = Pattern.compile(regex);

    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();

    String text = "prefix foo intermediate bar trailing baz suffix";
    Matcher matcher = pattern.matcher(text);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(0);
    assertThat(matcher.end()).isEqualTo(text.length());
  }

  @Test
  void multiInfixPartialMatchNegativeRejection() {
    String regex = ".*foo.*bar.*baz.*";
    Pattern pattern = Pattern.compile(regex);

    // "foo" and "bar" present, but "baz" is absent -> instant rejection in Phase 1
    String text = "prefix foo intermediate bar trailing qux suffix";
    Matcher matcher = pattern.matcher(text);
    assertThat(matcher.find()).isFalse();
  }

  @Test
  void multiInfixUtf8Input() {
    String regex = ".*foo.*bar.*baz.*";
    Pattern pattern = Pattern.compile(regex);

    byte[] bytes = "prefix foo intermediate bar trailing baz suffix".getBytes(UTF_8);
    Utf8Input input = Utf8Input.validated(bytes);

    Utf8Matcher matcher = pattern.matcher(input);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(0);
    assertThat(matcher.end()).isEqualTo(bytes.length);
  }

  @Test
  void structuredMultiClauseLog() {
    String regex = "error:\\[(\\w+)\\]\\s+code:(\\d+)\\s+msg:([^\n]+)";
    Pattern pattern = Pattern.compile(regex);

    String text = "2026-08-27 12:00:00 error:[CRITICAL] code:500 msg:Internal Server Error\n";
    Matcher matcher = pattern.matcher(text);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(0)).isEqualTo("error:[CRITICAL] code:500 msg:Internal Server Error");
    assertThat(matcher.group(1)).isEqualTo("CRITICAL");
    assertThat(matcher.group(2)).isEqualTo("500");
    assertThat(matcher.group(3)).isEqualTo("Internal Server Error");
  }

  @Test
  void singleLineGapNewlineBoundary() {
    String regex = "START[^\n]*MIDDLE[^\n]*END";
    Pattern pattern = Pattern.compile(regex);

    // Fails when a newline is present between START and MIDDLE
    String multilineFail = "START some text\nMIDDLE some text END";
    Matcher m1 = pattern.matcher(multilineFail);
    assertThat(m1.find()).isFalse();

    // Succeeds when all tokens are on the same line
    String singleLineSuccess = "START some text MIDDLE some text END";
    Matcher m2 = pattern.matcher(singleLineSuccess);
    assertThat(m2.find()).isTrue();
    assertThat(m2.group(0)).isEqualTo("START some text MIDDLE some text END");
  }

  @Test
  void dotallMultiLineSuccess() {
    String regex = "(?s)START.*MIDDLE.*END";
    Pattern pattern = Pattern.compile(regex);

    String text = "START line 1\nMIDDLE line 2\nEND";
    Matcher matcher = pattern.matcher(text);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(0)).isEqualTo(text);
  }

  @Test
  void adversarialDenseNoiseWorkLimitFallback() {
    // Pattern looking for A followed by B with bounded noise
    String regex = "A[0-9]{3}B";
    Pattern pattern = Pattern.compile(regex);

    // Dense stream of 5,000 'A's without digits, ending with valid match
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 5000; i++) {
      sb.append("Axx");
    }
    sb.append("A123B");

    String haystack = sb.toString();
    Matcher matcher = pattern.matcher(haystack);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(0)).isEqualTo("A123B");
    assertThat(matcher.start()).isEqualTo(haystack.length() - 5);
  }

  @Test
  void alternationAnchors() {
    String regex = ".*(GET|POST|PUT)\\s+/api/v1/(users|orders).*";
    Pattern pattern = Pattern.compile(regex);

    String text = "Incoming request: POST /api/v1/orders HTTP/1.1";
    Matcher matcher = pattern.matcher(text);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(0);
    assertThat(matcher.end()).isEqualTo(text.length());
  }

  @Test
  void jdkEquivalenceAcrossOffsets() {
    String regex = "id:([a-z]+)\\s+count:(\\d+)";
    Pattern saferePattern = Pattern.compile(regex);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);

    String text = "noise id:first count:100 intermediate id:second count:200 trailing";
    Matcher safereMatcher = saferePattern.matcher(text);
    java.util.regex.Matcher jdkMatcher = jdkPattern.matcher(text);

    while (jdkMatcher.find()) {
      assertThat(safereMatcher.find()).isTrue();
      assertThat(safereMatcher.start()).isEqualTo(jdkMatcher.start());
      assertThat(safereMatcher.end()).isEqualTo(jdkMatcher.end());
      assertThat(safereMatcher.group(0)).isEqualTo(jdkMatcher.group(0));
      assertThat(safereMatcher.group(1)).isEqualTo(jdkMatcher.group(1));
      assertThat(safereMatcher.group(2)).isEqualTo(jdkMatcher.group(2));
    }
    assertThat(safereMatcher.find()).isFalse();
  }

  @Test
  void fixedOffsetPrefixSingleAnchor() {
    String regex = "[0-9]{4}-target";
    Pattern pattern = Pattern.compile(regex);

    String text = "noise 2026-target intermediate 1999-target trailing";
    Matcher matcher = pattern.matcher(text);

    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(0)).isEqualTo("2026-target");
    assertThat(matcher.start()).isEqualTo(6);
    assertThat(matcher.end()).isEqualTo(17);

    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(0)).isEqualTo("1999-target");
    assertThat(matcher.start()).isEqualTo(31);
    assertThat(matcher.end()).isEqualTo(42);

    assertThat(matcher.find()).isFalse();
  }

  @Test
  void singleAnchorWithTrailingClass() {
    String regex = "foo[0-9]+";
    Pattern pattern = Pattern.compile(regex);

    String text = "bar foo12345 baz foo999 end";
    Matcher matcher = pattern.matcher(text);

    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(0)).isEqualTo("foo12345");
    assertThat(matcher.start()).isEqualTo(4);
    assertThat(matcher.end()).isEqualTo(12);

    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(0)).isEqualTo("foo999");
    assertThat(matcher.start()).isEqualTo(17);
    assertThat(matcher.end()).isEqualTo(23);

    assertThat(matcher.find()).isFalse();
  }

  @Test
  void suffixAnchorSingleAnchor() {
    String regex = ".*\\.json$";
    Pattern pattern = Pattern.compile(regex);

    String valid = "config/settings.json";
    Matcher m1 = pattern.matcher(valid);
    assertThat(m1.find()).isTrue();
    assertThat(m1.group(0)).isEqualTo(valid);

    String invalid = "config/settings.xml";
    Matcher m2 = pattern.matcher(invalid);
    assertThat(m2.find()).isFalse();
  }
}
