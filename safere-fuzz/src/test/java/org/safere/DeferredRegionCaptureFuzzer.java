// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

/** Exercises deferred NFA captures with independently varied region and match boundaries. */
public final class DeferredRegionCaptureFuzzer {
  @FuzzTest(maxDuration = "30s")
  void deferredRegionCaptures(FuzzedDataProvider data) {
    String atom = pick(data, "a", "b", "\uD801\uDC00", "[ab]");
    String quantifier = pick(data, "+?", "+", "*?", "*", "{1,3}?", "{1,3}");
    String outer = pick(data, "?", "??", "", "{0,1}");
    String regex =
        pick(data, "", "^", "\\b")
            + "("
            + atom
            + quantifier
            + ")"
            + outer
            + pick(data, "", "$", "\\z", "\\b");
    String prefix = pick(data, "x", "", "\uD801\uDC00", "\n");
    String suffix = pick(data, "", "x", "\uD801\uDC00", "\n");
    String body = pick(data, "a", "b", "\uD801\uDC00", "ab").repeat(data.consumeInt(1, 16));
    boolean anchoring = data.consumeBoolean();
    boolean transparent = data.consumeBoolean();
    Pattern pattern =
        Pattern.compile(
            regex, 0, EnginePathOptions.builder().onePass(false).bitState(false).build());
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);
    // A fresh matcher exercises the Pattern's NFA pool; resets vary capture participation and size.
    for (int fresh = 0; fresh < 2; fresh++) {
      Matcher actual = pattern.matcher("");
      java.util.regex.Matcher expected = jdkPattern.matcher("");
      for (String content : new String[] {body, "", "!", body + "!", body}) {
        String input = prefix + content + suffix;
        int start = prefix.length();
        int end = start + content.length();
        for (int operation = 0; operation < 3; operation++) {
          actual
              .reset(input)
              .region(start, end)
              .useAnchoringBounds(anchoring)
              .useTransparentBounds(transparent);
          expected
              .reset(input)
              .region(start, end)
              .useAnchoringBounds(anchoring)
              .useTransparentBounds(transparent);
          String context =
              regex
                  + " input="
                  + input
                  + " region="
                  + start
                  + ":"
                  + end
                  + " anchoring="
                  + anchoring
                  + " transparent="
                  + transparent
                  + " operation="
                  + operation;
          boolean matched;
          do {
            matched =
                switch (operation) {
                  case 0 -> actual.matches();
                  case 1 -> actual.lookingAt();
                  default -> actual.find();
                };
            boolean jdkMatched =
                switch (operation) {
                  case 0 -> expected.matches();
                  case 1 -> expected.lookingAt();
                  default -> expected.find();
                };
            assertThat(matched).as(context).isEqualTo(jdkMatched);
            if (matched) {
              for (int group = 0; group <= expected.groupCount(); group++) {
                assertThat(actual.start(group)).as(context).isEqualTo(expected.start(group));
                assertThat(actual.end(group)).as(context).isEqualTo(expected.end(group));
                assertThat(actual.group(group)).as(context).isEqualTo(expected.group(group));
              }
            }
          } while (operation == 2 && matched);
        }
      }
    }
  }

  private static String pick(FuzzedDataProvider data, String... values) {
    return values[data.consumeInt(0, values.length - 1)];
  }
}
