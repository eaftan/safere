// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Verifies that bounded engine selection preserves complete scalar match traces. */
@DisabledForCrosscheck(
    "Compares SafeRE engine configurations, including intentional region semantics")
class RegionEngineEquivalenceTest {
  @Test
  void scalarRegionTracesAgreeWithNfa() {
    String[] patterns = {
      ".*",
      ".*?",
      "(.)+",
      "(a?)*",
      "(a|ab)*b",
      "[\\s\\S]+",
      "\\p{Cs}+",
      "(?U)\\b.",
      "(?U)\\B.",
      "^.*$",
      "(?m)^.*$",
      "(?m:^)a|b$",
      "a$",
      "a\\z",
      "\\Aa",
      "[^x]*x",
      "(?:a|b)+",
      "(?s:.*)$",
      "\\b|\\B"
    };
    String[] inputs = {"a\uD83D\uDC4D", "\uD801\uDC00a", "x\r\na\n", "abax", "a\u0301b", ""};
    EnginePathOptions nfaOnly = EnginePathOptions.builder().bitState(false).build();
    for (String regex : patterns) {
      Pattern actualPattern = Pattern.compile(regex);
      Pattern expectedPattern = Pattern.compile(regex, 0, nfaOnly);
      for (String input : inputs) {
        // Reuse matchers so the engine caches see changing region/boundary contexts.
        for (boolean utf8 : new boolean[] {false, true}) {
          Utf8InputScanner scanner = new Utf8InputScanner(input.getBytes(UTF_8));
          Matcher actual =
              utf8 ? new Matcher(actualPattern, scanner) : actualPattern.matcher(input);
          Matcher expected =
              utf8 ? new Matcher(expectedPattern, scanner) : expectedPattern.matcher(input);
          int length = utf8 ? scanner.length() : input.length();
          for (int start = 0; start <= length; start++) {
            if (utf8 && !scanner.isCodePointBoundary(start)) {
              continue;
            }
            for (int end = start; end <= length; end++) {
              if (utf8 && !scanner.isCodePointBoundary(end)) {
                continue;
              }
              for (boolean transparent : new boolean[] {false, true}) {
                for (boolean anchoring : new boolean[] {false, true}) {
                  for (int operation = 0; operation < 3; operation++) {
                    actual
                        .region(start, end)
                        .useTransparentBounds(transparent)
                        .useAnchoringBounds(anchoring);
                    expected
                        .region(start, end)
                        .useTransparentBounds(transparent)
                        .useAnchoringBounds(anchoring);
                    assertThat(trace(actual, operation))
                        .as(
                            "%s input=%s region=%s:%s transparent=%s anchoring=%s operation=%s",
                            regex, input, start, end, transparent, anchoring, operation)
                        .isEqualTo(trace(expected, operation));
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  @Test
  @Disabled("Existing NFA full-region optional lazy capture bug: #880")
  void optionalLazyGroupRetainsCaptureInFullRegionMatch() {
    Pattern pattern =
        Pattern.compile("(a+?)?", 0, EnginePathOptions.builder().bitState(false).build());
    Matcher matcher = pattern.matcher("\uD801\uDC00a").region(2, 3).useAnchoringBounds(false);
    assertThat(matcher.matches()).isTrue();
    assertThat(matcher.group(1)).isEqualTo("a");
  }

  private static List<String> trace(Matcher matcher, int operation) {
    List<String> result = new ArrayList<>();
    while (true) {
      boolean matched =
          switch (operation) {
            case 0 -> matcher.matches();
            case 1 -> matcher.lookingAt();
            default -> matcher.find();
          };
      result.add(Boolean.toString(matched));
      if (matched) {
        for (int group = 0; group <= matcher.groupCount(); group++) {
          // Both matchers share the input, so equal capture bounds also imply equal contents.
          result.add(matcher.start(group) + ":" + matcher.end(group));
        }
      }
      if (operation != 2 || !matched) {
        return result;
      }
    }
  }
}
