// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** End-first search must preserve exact matching priority and capture boundaries. */
@DisabledForCrosscheck("compares the JDK and forced SafeRE engine paths directly")
class EndAnchoredSearchTest {

  @Test
  void anchoredOnePassTerminalFiltersPreserveLineEndingsAndRegions() {
    for (String regex :
        List.of(
            "^(a)*b$",
            "(?d)^(a)*b$",
            "^(a)*b\\z",
            "(?i)^(a)*B$",
            "^([a-z]+)[0-9]$",
            "^([a-z]+)END\\z")) {
      Pattern pattern = Pattern.compile(regex);
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);
      for (String input :
          List.of(
              "",
              "aaab",
              "aaac",
              "aaab\n",
              "aaab\r\n",
              "aaab\u0085",
              "aaab\u2028",
              "aaab\u2029",
              "aaa5",
              "aaa5\n",
              "aaaEND",
              "aaaEND\n")) {
        Utf8Input utf8 = Utf8Input.validated(input.getBytes(UTF_8));
        assertThat(pattern.matcher(input).matches())
            .as("/%s/ on %s", regex, input)
            .isEqualTo(jdk.matcher(input).matches());
        assertThat(pattern.matcher(utf8).matches()).isEqualTo(jdk.matcher(input).matches());
        assertFindSequence(pattern, jdk, input, utf8);
        assertThat(pattern.matcher(input).replaceAll("<$1>"))
            .isEqualTo(jdk.matcher(input).replaceAll("<$1>"));
        for (boolean anchoring : List.of(false, true)) {
          for (boolean transparent : List.of(false, true)) {
            String surrounded = "x" + input + "y";
            Matcher actual =
                pattern
                    .matcher(surrounded)
                    .region(1, 1 + input.length())
                    .useAnchoringBounds(anchoring)
                    .useTransparentBounds(transparent);
            java.util.regex.Matcher expected =
                jdk.matcher(surrounded)
                    .region(1, 1 + input.length())
                    .useAnchoringBounds(anchoring)
                    .useTransparentBounds(transparent);
            assertThat(actual.matches())
                .as(
                    "matches /%s/ on %s, anchoring=%s, transparent=%s",
                    regex, input, anchoring, transparent)
                .isEqualTo(expected.matches());
            // Reset the search position: initial find after failed matches intentionally diverges.
            actual.region(1, 1 + input.length());
            expected.region(1, 1 + input.length());
            assertThat(actual.find())
                .as(
                    "find /%s/ on %s, anchoring=%s, transparent=%s",
                    regex, input, anchoring, transparent)
                .isEqualTo(expected.find());
          }
        }
      }
    }
  }

  @Test
  void endAnchoredSearchPreservesFindSequenceAndCaptures() {
    for (String regex :
        List.of(
            "abc$",
            "(a)(b)(c)$",
            "(?:abc|bc)$",
            "(?:bc|abc)$",
            "a+?b$",
            "(?:a+?|[^x]*)$",
            "(?s:abc.*?$)",
            "(?s:.*)abc$",
            "(?s:.*)abc\\z",
            "(?:abc\\r?|abc)$",
            "abc\\z",
            "abc\\Z",
            "(?d:abc\\Z)",
            "(?d:abc$)",
            "(?d:abc(?-d:$))",
            "(?-d:abc(?d:$))",
            "(?m:abc$)",
            "(?i:abc)$",
            "(?iu:é)😀$",
            "\\babc$",
            "^abc$",
            "$",
            "\\z",
            "((a)?b)*$",
            "\\X$")) {
      java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);
      for (EnginePathOptions options :
          List.of(
              EnginePathOptions.allEnabled(),
              EnginePathOptions.builder().startAcceleration(false).build(),
              EnginePathOptions.builder().reverseDfa(false).build())) {
        Pattern pattern = Pattern.compile(regex, 0, options);
        for (String prefix : List.of("", "x".repeat(1_024), "é😀".repeat(400))) {
          for (String tail :
              List.of(
                  "",
                  "abc",
                  "abc\n",
                  "abc\r",
                  "abc\r\n",
                  "abc\u0085",
                  "abc\u2028",
                  "abc\u2029",
                  "aaab",
                  "ABC",
                  "é😀",
                  "abca\n",
                  "abcd")) {
            String input = prefix + tail;
            byte[] bytes = input.getBytes(UTF_8);
            byte[] storage = new byte[bytes.length + 7];
            System.arraycopy(bytes, 0, storage, 3, bytes.length);
            Utf8Input utf8 = Utf8Input.trusted(storage, 3, bytes.length);
            assertThat(pattern.find(utf8))
                .as(
                    "existence /%s/ with prefix length %s and tail %s",
                    regex, prefix.length(), tail)
                .isEqualTo(jdkPattern.matcher(input).find());
            assertFindSequence(pattern, jdkPattern, input, utf8);
          }
        }
      }
    }
  }

  @Test
  void unboundedEndAnchoredSearchPreservesResultsAcrossProbeBoundaries() {
    for (String regex :
        List.of(
            "needle.*$",
            "(needle)([a-z]*)\\z",
            "(?:needle.*|x+)$",
            "(?:needle|need).*?$",
            "\\bneedle[a-z]*$",
            "\\Bneedle[a-z]*$",
            "(?m:^needle)[a-z]*\\z",
            "(?s:.*)needle$")) {
      java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);
      for (EnginePathOptions options :
          List.of(
              EnginePathOptions.allEnabled(),
              EnginePathOptions.builder().reverseDfa(false).build(),
              EnginePathOptions.builder().literalFastPaths(false).build())) {
        Pattern pattern = Pattern.compile(regex, 0, options);
        for (int gap : List.of(0, 1, 7, 63, 64, 65, 127, 511, 1_023)) {
          for (String prefix : List.of("x".repeat(1_024), "é😀".repeat(400))) {
            for (String body :
                List.of(
                    "x".repeat(gap),
                    "needle" + "x".repeat(gap),
                    "needle" + "x".repeat(gap) + "needle",
                    "\nneedle" + "x".repeat(gap))) {
              for (String terminator : List.of("", "\n", "\r\n")) {
                String input = prefix + body + terminator;
                byte[] bytes = input.getBytes(UTF_8);
                byte[] storage = new byte[bytes.length + 7];
                System.arraycopy(bytes, 0, storage, 3, bytes.length);
                Utf8Input utf8 = Utf8Input.trusted(storage, 3, bytes.length);
                assertThat(pattern.find(utf8))
                    .as(
                        "existence /%s/ with gap %s and prefix length %s",
                        regex, gap, prefix.length())
                    .isEqualTo(jdkPattern.matcher(input).find());
                assertFindSequence(pattern, jdkPattern, input, utf8);
              }
            }
          }
        }
      }
    }
  }

  private static void assertFindSequence(
      Pattern pattern, java.util.regex.Pattern jdkPattern, String input, Utf8Input utf8) {
    Matcher matcher = pattern.matcher(input);
    Utf8Matcher utf8Matcher = pattern.matcher(utf8);
    java.util.regex.Matcher jdkMatcher = jdkPattern.matcher(input);
    boolean found;
    do {
      found = jdkMatcher.find();
      assertThat(matcher.find()).as("String /%s/", pattern).isEqualTo(found);
      assertThat(utf8Matcher.find()).as("UTF-8 /%s/", pattern).isEqualTo(found);
      if (found) {
        for (int group = 0; group <= matcher.groupCount(); group++) {
          assertThat(matcher.start(group))
              .as("start /%s/ group %s", pattern, group)
              .isEqualTo(jdkMatcher.start(group));
          assertThat(matcher.end(group))
              .as("end /%s/ group %s", pattern, group)
              .isEqualTo(jdkMatcher.end(group));
          assertThat(matcher.group(group))
              .as("capture /%s/ group %s", pattern, group)
              .isEqualTo(jdkMatcher.group(group));
          assertThat(utf8Matcher.start(group))
              .as("UTF-8 start /%s/ group %s", pattern, group)
              .isEqualTo(byteOffset(input, jdkMatcher.start(group)));
          assertThat(utf8Matcher.end(group))
              .as("UTF-8 end /%s/ group %s", pattern, group)
              .isEqualTo(byteOffset(input, jdkMatcher.end(group)));
        }
      }
    } while (found);
  }

  private static int byteOffset(String input, int index) {
    return index < 0 ? -1 : input.substring(0, index).getBytes(UTF_8).length;
  }
}
