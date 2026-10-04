// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("tests internal acceleration controls and compares directly with the JDK")
class FixedOffsetClassAccelerationTest {
  private static final EnginePathOptions WITHOUT_ACCELERATION =
      EnginePathOptions.builder().startAcceleration(false).build();

  @Test
  void interiorClassesAccelerateUnselectivePrefixes() {
    for (String regex :
        List.of(" [0-9]{4,}", "  [0-9]+", "( )([0-9]+)", "[a-z]{2}[0-9]+", " {2}[A-F0-9]+")) {
      Pattern pattern = Pattern.compile(regex);
      assertThat(pattern.stringStartAccelerator()).as(regex).isNotNull();
      assertThat(pattern.utf8StartAccelerator()).as(regex).isNotNull();
    }
  }

  @Test
  void exactOffsetsPropagateThroughCapturesAndRequiredRepetitions() {
    for (var entry :
        Map.of(
                " [0-9]{4,}", 1,
                "( )([0-9]+)", 1,
                "(?:[a-z]{2})([0-9]+)", 2,
                "[ \\t\\r\\n]{3}[A-F0-9]+", 3,
                "(?:( )[0-9]+)+", 1)
            .entrySet()) {
      assertThat(Pattern.compile(entry.getKey()).startPlan())
          .as(entry.getKey())
          .isInstanceOfSatisfying(
              MultiAnchorDescriptor.StartPlan.FixedOffsetClass.class,
              plan -> assertThat(plan.offset()).isEqualTo(entry.getValue()));
    }
  }

  @Test
  void variableOrNonAsciiPrefixWidthsDoNotProduceFixedOffsets() {
    for (String regex :
        List.of(" ?[0-9]+", "[a-z]{1,2}[0-9]+", "[é😀][0-9]+", "(?: |é)[0-9]+", " (?U:\\d+)")) {
      assertThat(Pattern.compile(regex).startPlan())
          .as(regex)
          .isNotInstanceOf(MultiAnchorDescriptor.StartPlan.FixedOffsetClass.class);
    }
  }

  @Test
  void variableLeadingExpansionsDoNotWrapFixedClassOffsets() {
    for (String regex : List.of("[0-9 ]+ [0-9]{4,}", "[0-9 ]* [0-9]+")) {
      Pattern pattern = Pattern.compile(regex);
      assertThat(pattern.stringStartAccelerator()).as(regex).isNull();
      assertThat(pattern.utf8StartAccelerator()).as(regex).isNull();
      assertFindSequence(regex, " 1234 5678 ".repeat(200), false);
    }
  }

  @Test
  void unicodeClassesInsideThePrefixPreserveCoordinateWidths() {
    // JDK 26 Pattern specifies concatenation and Unicode character-class membership. A class
    // consuming one code point need not consume one UTF-16 unit or one UTF-8 byte.
    for (String prefix : List.of(" [é😀]", " [aé]", " [a-zé]", " [é😀]{2}", " (?:[aé]{2})")) {
      String regex = prefix + "([0-9]+)";
      for (String input : List.of(" 😀1234 é5678", " aa1234 éé5678", " 😀😀1234 a😀5678")) {
        assertFindSequence(regex, input, false);
        assertFindSequence(regex, "noise é😀".repeat(100) + input, true);
      }
      assertThat(Pattern.compile(regex).startPlan())
          .as(regex)
          .isNotInstanceOf(MultiAnchorDescriptor.StartPlan.FixedOffsetClass.class);
    }
  }

  @Test
  void consumingAtomsInsideThePrefixDoNotAcquireZeroWidthOffsets() {
    for (String prefix : List.of(" .", " .{2}", " \\X")) {
      String regex = prefix + "([0-9]+)";
      for (String input : List.of(" x1234", " 😀1234", " aé1234", " 😀😀1234", " 👩‍🔬1234")) {
        assertFindSequence(regex, input, false);
      }
      assertThat(Pattern.compile(regex).startPlan())
          .as(regex)
          .isNotInstanceOf(MultiAnchorDescriptor.StartPlan.FixedOffsetClass.class);
    }
  }

  @Test
  void findSequencesPreservePriorityCapturesAndUnicodeCoordinates() {
    for (String regex :
        List.of(
            " [0-9]{4,}",
            "e([0-9]+)",
            "(?i:e)([0-9]+)",
            " ([0-9]{4,}?)",
            "( )([0-9]{1,3})",
            " (?:([0-9]+)|([A-F]+))",
            "[a-z]{2}([0-9]+)",
            " {2}([A-F0-9]+)",
            "\\B ([0-9]+)",
            " ([0-9]+)$")) {
      for (String noise :
          List.of("", "words without digits ".repeat(100), "é😀 words ".repeat(100))) {
        for (String suffix :
            List.of(
                "",
                "123456 12 1234 56789",
                " 2026😀 12345",
                " aa42 bb1234",
                "  1A2B  FF",
                " 1 2 3",
                "e12 E345")) {
          String input = noise + suffix;
          for (boolean region : List.of(false, true)) {
            assertFindSequence(regex, input, region);
          }
        }
      }
    }
  }

  @Test
  void denseFalseCandidatesAndFailedSuffixesPreserveTheLeftmostMatch() {
    for (String regex : List.of(" ([0-9]{4,})", "[a-z]{2}([0-9]+)Z")) {
      for (String unit : List.of("1111x", " 1x", "aa1234Y", "é😀1111x")) {
        assertFindSequence(regex, unit.repeat(2_000) + " aa1234Z 56789", false);
      }
    }
  }

  @Test
  void replacementsUseTheSameCapturesAndMatchSequence() {
    String input = "é😀 noise ".repeat(200) + " 12 12345 6789";
    for (String regex : List.of(" ([0-9]{4,})", " ([0-9]{1,3}?)")) {
      String expected = java.util.regex.Pattern.compile(regex).matcher(input).replaceAll("<$1>");
      assertThat(Pattern.compile(regex).matcher(input).replaceAll("<$1>")).isEqualTo(expected);
    }
  }

  @Test
  void explicitFindStartsAndResetPreserveSearchState() {
    String input = "😀 1234x 56789";
    for (int start = 0; start <= input.length(); start++) {
      Matcher safe = Pattern.compile(" ([0-9]{4,})").matcher(input);
      java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(" ([0-9]{4,})").matcher(input);
      boolean matched = jdk.find(start);
      assertThat(safe.find(start)).isEqualTo(matched);
      if (matched) {
        assertThat(safe.start()).isEqualTo(jdk.start());
        assertThat(safe.group(1)).isEqualTo(jdk.group(1));
      }
      while (jdk.find()) {
        assertThat(safe.find()).isTrue();
        assertThat(safe.start()).isEqualTo(jdk.start());
        assertThat(safe.group(1)).isEqualTo(jdk.group(1));
      }
      assertThat(safe.find()).isFalse();
      assertThat(safe.reset().find()).isTrue();
      assertThat(safe.start()).isEqualTo(2);
    }
  }

  @Test
  void interiorClassAnalysisIsStackSafeForDeepCaptures() {
    String regex = " " + "(".repeat(2_000) + "[0-9]{4,}" + ")".repeat(2_000);
    Pattern pattern = Pattern.compile(regex);
    assertThat(pattern.startPlan())
        .isInstanceOf(MultiAnchorDescriptor.StartPlan.FixedOffsetClass.class);
    assertThat(pattern.matcher("noise 1234x").find()).isTrue();
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
      assertThat(safe.find()).as("%s on %s", regex, input).isEqualTo(matched);
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
