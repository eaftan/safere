// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Regression coverage for the intentional comment/quoting divergence documented in #858. */
final class FuzzSupportCommentQuotingTest {
  @Test
  void skipsCommentQuotesAcrossFlagsLineEndingsAndFollowingSyntax() {
    for (String newline : List.of("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029")) {
      for (String tail : List.of(".", "(", "*", ".\\E", "(a+)", "\\Q(\\E")) {
        String body = "# ignored \\Q" + newline + tail;
        assertSkipped(body, org.safere.Pattern.COMMENTS);
        assertSkipped("(?x)" + body, 0);
        assertSkipped("(?x:" + body + ")", 0);
        assertSkipped("(?x)[a#\\Q" + newline + "-z]", 0);
      }
    }
  }

  @Test
  void recognizesRestoredFlagsNestedClassesAndUnixLineMode() {
    for (String regex :
        List.of(
            "(?x)(?-x:a)#\\Q\n.",
            "(?x)\\c#\\Q\nA",
            "(?x)\\c #\\Q\nA",
            "(?x)[\\c#\\Q\nA]",
            "((?x)a)#\\Q\n.",
            "(?x)[](?-x)]#\\Q\n.",
            "(?x)[[^a]b#\\Q\n]",
            "(?dx)#\r\\Q\n.",
            "(?dx)#\u0085\\Q\n.",
            "(?dx)#\u2028\\Q\n.",
            "(?dx)#\u2029\\Q\n.",
            "(?x)(?d:#\r\\Q\n.)",
            "(?dx)(?-d:a)#\r\\Q\n.",
            "(?x)#\\\\\\Q\n.",
            "(?x)#\\Q\\E\n.")) {
      // A quote wholly inside a comment is conservatively excluded too.
      assertSkipped(regex, regex.startsWith("((") ? org.safere.Pattern.COMMENTS : 0);
    }
  }

  @Test
  void keepsOrdinaryQuotingEscapedHashesAndDisabledComments() {
    for (String regex :
        List.of(
            "#\\Q\n.",
            "(?x)\\#\\Q.\\E",
            "(?x)\\Q#\\E.",
            "(?x)#\\\\Q\n.",
            "(?x)# ordinary\n\\Q.\\E",
            "(?x)(?-x:#\\Q.\\E)",
            "(?x:a)#\\Q.\\E",
            "((?x)a)#\\Q.\\E",
            "(?x)[a](?-x)#\\Q.\\E",
            "(?x)[]](?-x)#\\Q.\\E",
            "(?x)[^^](?-x)#\\Q.\\E",
            "(?x)[\\Q^\\E](?-x)#\\Q.\\E",
            "(?x)#\r\\Q.\\E",
            "(?x)#\u0085\\Q.\\E",
            "(?x)#\u2028\\Q.\\E",
            "(?x)#\u2029\\Q.\\E",
            "(?x)#\0\\Q.\\E")) {
      assertThat(FuzzSupport.compileCompatibleOrSkip(regex, 0)).as(regex).isNotNull();
    }
    assertThat(
            FuzzSupport.compileCompatibleOrSkip(
                "(?x)#\\Q\n.", org.safere.Pattern.LITERAL | org.safere.Pattern.COMMENTS))
        .isNotNull();
  }

  private static void assertSkipped(String regex, int flags) {
    assertThat(CommentQuotingDivergence.contains(regex, flags)).as(regex).isTrue();
    assertThat(FuzzSupport.compileCompatibleOrSkip(regex, flags)).as(regex).isNull();
  }
}
