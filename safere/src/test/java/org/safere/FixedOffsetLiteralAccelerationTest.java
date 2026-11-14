// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("compares internal start acceleration and UTF-8 paths directly with the JDK")
class FixedOffsetLiteralAccelerationTest {
  private static final EnginePathOptions WITHOUT_ACCELERATION =
      EnginePathOptions.builder().startAcceleration(false).build();

  @Test
  void alternatingScansPreserveFindSequencesAndCaptures() {
    for (String regex :
        List.of(
            "(?:requisition|req):(\\S*)",
            "\\b(?:BUG|FIXED)=\\s*(\\d+,?)+",
            "(?:ticket|task)!(\\w*)",
            "(?:alpha|beta)=(\\w*)",
            "(aq|b[a-z]{9})z",
            "https?://(\\S*)",
            "(?:transaction|trans):(\\S*)",
            "(?i)(?:requisition|req):(\\S*)",
            "(?:[a-z]{3}|[a-z]{10}):(\\w*)",
            "(?:a?|bbb):(.*)",
            "[é😀]{2}token")) {
      for (String noise :
          List.of("", ":=! ".repeat(200), "r:r: B= t! a= ".repeat(100), "備考😀:=! ".repeat(100))) {
        for (String tail :
            List.of(
                "",
                " req:x requisition:y",
                " BUG=1,2 FIXED=3",
                " ticket!a task!b alpha=x beta=y",
                " bxxaxzxxxxz http://a https://b",
                " é😀token")) {
          for (boolean region : List.of(false, true)) {
            assertFindSequence(regex, noise + tail, region);
          }
        }
      }
    }
  }

  @Test
  void defeatedLeadingScansPreserveLaterMatches() {
    String input = "a________=_______".repeat(2_000) + " beta=one alpha=two ";
    assertFindSequence("(?:alpha|beta)=(\\w*)", input, false);
    assertFindSequence("(?:alpha|beta)=(\\w*)", input, true);
  }

  @Test
  void alternateLiteralFiltersRequireAnExactCommonPrefix() {
    var exact =
        (MultiAnchorDescriptor.StartPlan.FixedOffset)
            Pattern.compile("(?:requisition|req):(\\S*)").startPlan();
    assertThat(exact.leadingLiteral()).isEqualTo("req");
    assertThat(Pattern.compile("(?i)(?:requisition|req):(\\S*)").startPlan())
        .isNotInstanceOf(MultiAnchorDescriptor.StartPlan.FixedOffset.class);
  }

  @Test
  void replacementAndResetKeepCaptureIdentityAfterSkippingNoise() {
    String regex = "(?:requisition|req):(\\S*)";
    String input = ": ".repeat(1000) + "req:one requisition:two";
    String expected = java.util.regex.Pattern.compile(regex).matcher(input).replaceAll("[$1]");
    Pattern pattern = Pattern.compile(regex);
    Matcher matcher = pattern.matcher(input);
    assertThat(matcher.replaceAll("[$1]")).isEqualTo(expected);
    matcher.reset();
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(1)).isEqualTo("one");
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    pattern
        .matcher(Utf8Input.trusted(input.getBytes(UTF_8)))
        .replaceAll(output::write, Utf8Input.trusted("[$1]".getBytes(UTF_8)));
    assertThat(output.toString(UTF_8)).isEqualTo(expected);
  }

  private static void assertFindSequence(String regex, String input, boolean region) {
    Pattern pattern = Pattern.compile(regex);
    Matcher safe = pattern.matcher(input);
    Matcher unaccelerated = Pattern.compile(regex, 0, WITHOUT_ACCELERATION).matcher(input);
    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regex).matcher(input);
    byte[] padded = ("pad" + input + "tail").getBytes(UTF_8);
    Utf8Input bytes = Utf8Input.trusted(padded, 3, input.getBytes(UTF_8).length);
    Utf8Matcher utf8 = pattern.matcher(bytes);
    if (region && !input.isEmpty()) {
      int begin = input.offsetByCodePoints(0, 1);
      int end = input.offsetByCodePoints(input.length(), -1);
      if (begin > end) {
        return;
      }
      safe.region(begin, end);
      unaccelerated.region(begin, end);
      jdk.region(begin, end);
      utf8.region(byteOffset(input, begin), byteOffset(input, end));
    }
    while (true) {
      boolean matched = jdk.find();
      assertThat(safe.find()).as("%s", regex).isEqualTo(matched);
      assertThat(unaccelerated.find()).isEqualTo(matched);
      assertThat(utf8.find()).isEqualTo(matched);
      if (!matched) {
        break;
      }
      for (int group = 0; group <= jdk.groupCount(); group++) {
        assertThat(safe.start(group)).isEqualTo(jdk.start(group));
        assertThat(safe.end(group)).isEqualTo(jdk.end(group));
        assertThat(safe.group(group)).isEqualTo(jdk.group(group));
        assertThat(unaccelerated.start(group)).isEqualTo(jdk.start(group));
        assertThat(unaccelerated.end(group)).isEqualTo(jdk.end(group));
        assertThat(utf8.start(group)).isEqualTo(byteOffset(input, jdk.start(group)));
        assertThat(utf8.end(group)).isEqualTo(byteOffset(input, jdk.end(group)));
      }
    }
    if (!region) {
      assertThat(pattern.find(bytes))
          .isEqualTo(java.util.regex.Pattern.compile(regex).matcher(input).find());
    }
  }

  private static int byteOffset(String input, int offset) {
    return offset < 0 ? -1 : input.substring(0, offset).getBytes(UTF_8).length;
  }
}
