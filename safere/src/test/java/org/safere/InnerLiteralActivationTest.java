// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("Compares SafeRE and the JDK directly")
class InnerLiteralActivationTest {
  @Test
  void repeatedNarrowingSurvivesCacheGrowthAndBudgetRecovery() {
    String suffix = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    String regex = "record:[^;]*" + suffix;
    Pattern pattern = Pattern.compile(regex);
    java.util.regex.Pattern reference = java.util.regex.Pattern.compile(regex);
    Regexp ast =
        Parser.parse(
            regex,
            ParseFlags.PERL_X
                | ParseFlags.PERL_CLASSES
                | ParseFlags.PERL_B
                | ParseFlags.UNICODE_GROUPS);
    InnerLiteralSearch plan = InnerLiteralSearch.compile(ast, pattern.rejectPrefilter());
    assertThat(plan).isNotNull();
    for (int budget : new int[] {32, 48, 64}) {
      Dfa dfa =
          new Dfa(
              pattern.flatDfaProg(),
              budget,
              Dfa.buildSetup(pattern.flatDfaProg()),
              false,
              pattern.utf8StartAccelerator(),
              pattern.stringStartAccelerator(),
              plan);
      boolean completedMatch = false;
      for (int pass = 0; pass < 4; pass++) {
        for (String input :
            List.of(
                "record:x;".repeat(256) + "record:" + "x".repeat(512) + suffix,
                "record:x;".repeat(256),
                "record:ok" + suffix,
                "record:x;".repeat(256) + "record:ok" + suffix)) {
          for (InputScanner scanner :
              List.of(new StringInputScanner(input), new Utf8InputScanner(input.getBytes(UTF_8)))) {
            Dfa.SearchResult result = dfa.doSearch(scanner, 0, false, false);
            if (result == null) {
              assertThat(dfa.lastSearchBailed()).isTrue();
            } else {
              assertThat(result.matched()).isEqualTo(reference.matcher(input).find());
              completedMatch |= result.matched();
            }
          }
        }
      }
      assertThat(completedMatch).as("state budget=%d", budget).isTrue();
    }
  }

  @Test
  void mixedDenseAndFailedCandidatesPreserveEveryResult() {
    for (String regex :
        List.of(
            "record:[^;\\r\\n]*val=200",
            "record:.*val=200",
            "record:[^;]{0,64}val=200",
            "\\brecord:[^;]*val=200",
            "(?m:^record:[^;\\r\\n]*val=200$)",
            "(record:)([^;]*?)(val=200)",
            "pre:[^;]*;done",
            "line:[^\\r\\n]*done",
            "clé:[^;]*fin",
            "😀[^;]*終")) {
      Pattern pattern = Pattern.compile(regex);
      java.util.regex.Pattern reference = java.util.regex.Pattern.compile(regex);
      for (String body :
          List.of(
              "record:ok_val=200;".repeat(32) + "record:x;".repeat(128) + "record:target_val=200;",
              ("record:" + "x".repeat(128) + ";").repeat(128) + "record:target_val=200",
              "record:x;\n".repeat(128) + "record:target_val=200\n",
              "pre:x;bad;".repeat(128) + "pre:target;done",
              "line:x\n".repeat(128) + "line:target_done",
              "clé:x;".repeat(128) + "clé:é😀fin;clé:fin",
              "😀x;".repeat(128) + "😀é終;😀終",
              "record:" + "x".repeat(1024) + "val=200;record:val=200")) {
        for (int padding : new int[] {0, 1}) {
          String input = padding == 0 ? body : "«" + body + "»";
          Matcher actual = pattern.matcher(input).region(padding, input.length() - padding);
          java.util.regex.Matcher expected =
              reference.matcher(input).region(padding, input.length() - padding);
          Utf8Matcher bytes = pattern.matcher(Utf8Input.validated(body.getBytes(UTF_8)));
          while (expected.find()) {
            assertThat(actual.find()).isTrue();
            assertThat(bytes.find()).isTrue();
            for (int group = 0; group <= expected.groupCount(); group++) {
              assertThat(actual.group(group)).isEqualTo(expected.group(group));
              assertThat(actual.start(group)).isEqualTo(expected.start(group));
              assertThat(actual.end(group)).isEqualTo(expected.end(group));
              int start = expected.start(group) - padding;
              int end = expected.end(group) - padding;
              assertThat(bytes.start(group))
                  .isEqualTo(body.substring(0, start).getBytes(UTF_8).length);
              assertThat(bytes.end(group)).isEqualTo(body.substring(0, end).getBytes(UTF_8).length);
            }
          }
          assertThat(actual.find()).isFalse();
          assertThat(bytes.find()).isFalse();
        }
        assertThat(pattern.matcher(body).replaceAll("X"))
            .isEqualTo(reference.matcher(body).replaceAll("X"));
        assertThat(pattern.split(body, -1)).containsExactly(reference.split(body, -1));
      }
    }
  }
}
