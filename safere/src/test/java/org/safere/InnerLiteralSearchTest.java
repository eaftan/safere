// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Searching through a required inner literal preserves match priority and captures. */
@DisabledForCrosscheck("compares the JDK and forced SafeRE engine paths directly")
class InnerLiteralSearchTest {
  @Test
  void literalGapSearchPreservesFindSequencesAndRegions() {
    for (String regex :
        List.of(
            "record:[^;\\r\\n]*val=200",
            "(record:)([^;\\r\\n]*?)(val=200)",
            "record:[^;]{0,64}val=200",
            "record:[a-z_]*val=200",
            "record:.*val=200",
            "record:.*?val=200",
            "aa[^;]*aba",
            "aa[^;]*?aba",
            "a;[^;]*;b",
            "clé:[^;]*fin",
            "😀[^;]*終",
            "record:[^😀]*val=200",
            "\\brecord:[^;]*val=200",
            "(?i:record:[^;]*val=200)",
            "^record:[^;]*val=200",
            "(?m:^record:[^;\\r\\n]*val=200$)",
            "record:[^;]*val=200|earlier")) {
      java.util.regex.Pattern reference = java.util.regex.Pattern.compile(regex);
      for (EnginePathOptions options :
          List.of(
              EnginePathOptions.allEnabled(),
              EnginePathOptions.builder().startAcceleration(false).build())) {
        Pattern pattern = Pattern.compile(regex, 0, options);
        for (String body :
            List.of(
                "record:x;val=200;record:target_val=200",
                "record:val=200_record:later_val=200",
                "record:val=200;record:later_val=200",
                "record:val=200;record:x;val=200",
                "aaabaaba;aaaba",
                "clé:x;fin;clé:é😀fin",
                "😀x;終;😀é終",
                "a;x;b;a;later;b",
                "record:x😀val=200😀record:target_val=200",
                "RECORD:abcVAL=200;record:val=200",
                "earlier;record:target_val=200",
                "\nrecord:target_val=200\n",
                "record:no_match;val=200")) {
          String input = "noise;".repeat(128) + body;
          assertFindSequence(pattern, reference, input, 0, input.length());
          assertFindSequence(pattern, reference, "«" + input + "»", 1, input.length() + 1);
        }
      }
    }
  }

  @Test
  void bulkOperationsAndBooleanSearchPreserveResults() {
    for (String regex :
        List.of(
            "record:[^;\\r\\n]*val=200",
            "(record:)([^;]*?)(val=200)",
            "aa[^;]*aba",
            "clé:[^;]*fin",
            "record:[^😀]*val=200")) {
      Pattern pattern = Pattern.compile(regex);
      java.util.regex.Pattern expected = java.util.regex.Pattern.compile(regex);
      for (String input :
          List.of(
              "record:x;".repeat(512) + "record:val=200;record:later_val=200",
              "record:" + "x".repeat(1_024) + "val=200",
              "clé:x;".repeat(512) + "clé:é😀fin;clé:fin",
              "noise;".repeat(512) + "aaabaaba;aaaba",
              "record:bad;val=200;record:good_val=200",
              "record:x😀val=200😀record:target_val=200")) {
        assertThat(pattern.find(Utf8Input.validated(input.getBytes(UTF_8))))
            .isEqualTo(expected.matcher(input).find());
        assertThat(pattern.asPredicate().test(input)).isEqualTo(expected.matcher(input).find());
        assertThat(pattern.matcher(input).replaceFirst("X"))
            .isEqualTo(expected.matcher(input).replaceFirst("X"));
        assertThat(pattern.matcher(input).replaceAll("X"))
            .isEqualTo(expected.matcher(input).replaceAll("X"));
        assertThat(pattern.split(input, -1)).containsExactly(expected.split(input, -1));
      }
    }
  }

  private static void assertFindSequence(
      Pattern pattern, java.util.regex.Pattern reference, String input, int start, int end) {
    java.util.regex.Matcher expected = reference.matcher(input).region(start, end);
    Matcher actual = pattern.matcher(input).region(start, end);
    Utf8Matcher bytes =
        pattern
            .matcher(Utf8Input.validated(input.getBytes(UTF_8)))
            .region(byteOffset(input, start), byteOffset(input, end));
    while (true) {
      boolean found = expected.find();
      assertThat(actual.find()).as("String /%s/ in %s", pattern, input).isEqualTo(found);
      assertThat(bytes.find()).as("UTF-8 /%s/ in %s", pattern, input).isEqualTo(found);
      if (!found) {
        return;
      }
      for (int group = 0; group <= expected.groupCount(); group++) {
        assertThat(actual.start(group)).isEqualTo(expected.start(group));
        assertThat(actual.end(group)).isEqualTo(expected.end(group));
        assertThat(actual.group(group)).isEqualTo(expected.group(group));
        assertThat(bytes.start(group)).isEqualTo(byteOffset(input, expected.start(group)));
        assertThat(bytes.end(group)).isEqualTo(byteOffset(input, expected.end(group)));
      }
    }
  }

  private static int byteOffset(String input, int offset) {
    return offset < 0 ? -1 : input.substring(0, offset).getBytes(UTF_8).length;
  }
}
