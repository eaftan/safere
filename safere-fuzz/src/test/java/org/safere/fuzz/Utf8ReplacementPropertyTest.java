// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pins the whole-input replacement property across its generated behavioral families. */
class Utf8ReplacementPropertyTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void invalidNumberedReferencesHaveConsistentValidation(boolean trusted) {
    for (int groups : new int[] {0, 1, 2, 8}) {
      for (int invalidGroup : new int[] {groups + 1, 9}) {
        for (String prefix : List.of("", "[", "é😀$0-")) {
          for (String suffix : List.of("", "]", "0")) {
            for (boolean hasMatch : new boolean[] {false, true}) {
              RepresentationChecks.assertUtf8NumberedReferenceValidation(
                  groups, invalidGroup, prefix + "$" + invalidGroup + suffix, hasMatch, trusted);
            }
          }
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void wholeInputReplacementsAgreeWithAppendLoops(boolean trusted) {
    String[][] cases = {
      {"", ""},
      {"", "é😀"},
      {"(?<word>[aé😀]+)([0-9]+)?", "aé9 😀 b"},
      {"(a)?(é|😀)", "aé 😀"},
      {"^é", "é😀é"},
      {"^é", "aé😀é"},
      {"é$", "é😀é"},
      {"(?m)^é", "é\né"},
      {"a*?", "aaé😀"},
      {"a*", "aaé😀"},
      {"a+", "éaa😀a"},
      {"[é😀]*", "aé😀éa"},
      {"[^a]+", "aé😀a"},
      {"(?i)é*", "Ééa"},
      {"[aé😀]{0,}", "aé😀!"},
      {"\\X", "á👩‍💻"}
    };
    for (String[] testCase : cases) {
      for (String replacement :
          List.of("", "X", "<$0>", "$0$0", "$1", "${word}-$2", "\\$0\\\\", "$", "$9", "\\")) {
        for (int initialState = 0; initialState <= 2; initialState++) {
          RepresentationChecks.assertUtf8ReplacementMatchesAppendLoop(
              testCase[0], testCase[1], replacement, initialState, trusted);
        }
      }
    }
  }
}
