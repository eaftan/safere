// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests Unicode grapheme segmentation for unassigned code points and controls. */
class UnassignedGraphemeTest {

  @ParameterizedTest
  @ValueSource(ints = {0x0378, 0x0379, 0xFFFF, 0x8D43F, 0xE1000})
  @DisabledForCrosscheck(
      "JDK classifies unassigned grapheme Other code points as Control except U+0378; see #925")
  void unassignedOtherKeepsExtendersInOneCluster(int codePoint) {
    // GB9 applies to unassigned Other code points just as it does to assigned bases.
    // U+8D43F followed by U+07EF is the reproducer from #925.
    String base = new String(Character.toChars(codePoint));
    for (String suffix : List.of("\u07EF", "\u0301", "\u200D", "\u07EF\u0301\u200D")) {
      String cluster = base + suffix;
      assertThat(Pattern.compile("\\X").matcher(cluster).matches()).as(cluster).isTrue();
      assertThat(Pattern.compile("\\X{2}").matcher(cluster).matches()).as(cluster).isFalse();

      Matcher matcher = Pattern.compile("\\X").matcher(cluster + "a");
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.start()).isZero();
      assertThat(matcher.end()).isEqualTo(cluster.length());
      assertThat(matcher.group()).isEqualTo(cluster);
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.start()).isEqualTo(cluster.length());
      assertThat(matcher.end()).isEqualTo(cluster.length() + 1);
      assertThat(matcher.group()).isEqualTo("a");
      assertThat(matcher.find()).isFalse();

      assertThat(Pattern.compile("\\b{g}").split(cluster + "a")).containsExactly(cluster, "a");
      assertThat(Pattern.compile("\\X").split("a" + cluster + "b", -1))
          .containsExactly("", "", "", "");
      assertThat(
              Pattern.compile("\\X")
                  .matcher(Utf8Input.validated(cluster.getBytes(UTF_8)))
                  .matches())
          .isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        0x0000, 0x000D, 0x000A, 0x2065, 0xFFF0, 0xFFF8, 0xE0000, 0xE0002, 0xE001F, 0xE0080, 0xE00FF,
        0xE01F0, 0xE0FFF
      })
  void controlBreakTakesPrecedenceOverExtenderJoining(int codePoint) {
    // Unassigned default-ignorable ranges have grapheme property Control (UAX #29).
    String control = new String(Character.toChars(codePoint));
    for (String suffix : List.of("\u07EF", "\u0301", "\u200D")) {
      String input = control + suffix;
      assertThat(Pattern.compile("\\X").matcher(input).matches()).isFalse();
      assertThat(Pattern.compile("\\X{2}").matcher(input).matches()).isTrue();
      Matcher matcher = Pattern.compile("\\X").matcher(input);
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.start()).isZero();
      assertThat(matcher.end()).isEqualTo(control.length());
      assertThat(matcher.group()).isEqualTo(control);
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.start()).isEqualTo(control.length());
      assertThat(matcher.end()).isEqualTo(input.length());
      assertThat(matcher.group()).isEqualTo(suffix);
      assertThat(matcher.find()).isFalse();
      assertThat(Pattern.compile("\\b{g}").split(input)).containsExactly(control, suffix);
      // GB5 must also break before a control even when GB9b would otherwise join Prepend.
      assertThat(Pattern.compile("\\X").matcher("\u0600" + control).matches()).isFalse();

      Utf8Matcher utf8 = Pattern.compile("\\X").matcher(Utf8Input.validated(input.getBytes(UTF_8)));
      assertThat(utf8.matches()).isFalse();
      utf8.reset();
      assertThat(utf8.find()).isTrue();
      assertThat(utf8.start()).isZero();
      assertThat(utf8.end()).isEqualTo(control.getBytes(UTF_8).length);
      assertThat(utf8.find()).isTrue();
      assertThat(utf8.start()).isEqualTo(control.getBytes(UTF_8).length);
      assertThat(utf8.end()).isEqualTo(input.getBytes(UTF_8).length);
      assertThat(utf8.find()).isFalse();
    }
  }
}
