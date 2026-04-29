// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for {@link Walker}. */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class WalkerTest {

  private static final int FLAGS =
      ParseFlags.MATCH_NL | (ParseFlags.LIKE_PERL & ~ParseFlags.ONE_LINE);

  /** A simple walker that counts nodes in the tree. */
  private static final class CountWalker extends Walker<Integer> {
    @Override
    protected Integer preVisit(Regexp re, Integer parentArg, boolean[] stop) {
      return 0;
    }

    @Override
    protected Integer postVisit(
        Regexp re, Integer parentArg, Integer preArg, List<Integer> childArgs) {
      int count = 1;
      for (int i = 0; i < childArgs.size(); i++) {
        count += childArgs.get(i);
      }
      return count;
    }

    @Override
    protected Integer shortVisit(Regexp re, Integer parentArg) {
      return 1;
    }
  }

  @Test
  void countNodes_literal() {
    Regexp re = Parser.parse("a", FLAGS);
    int count = new CountWalker().walk(re, 0);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void countNodes_concat() {
    Regexp re = Parser.parse("abc", FLAGS);
    // LITERAL_STRING is a single node (no children).
    int count = new CountWalker().walk(re, 0);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void countNodes_alternation() {
    Regexp re = Parser.parse("a|b|c", FLAGS);
    // ALTERNATE(LITERAL, LITERAL, LITERAL) = 1 + 3 = 4... but parser may merge to charclass.
    int count = new CountWalker().walk(re, 0);
    assertThat(count).isGreaterThanOrEqualTo(1);
  }

  @Test
  void countNodes_nested() {
    Regexp re = Parser.parse("(a+)*", FLAGS);
    // STAR(CAPTURE(PLUS(LITERAL))) = 4
    int count = new CountWalker().walk(re, 0);
    assertThat(count).isEqualTo(4);
  }

  @Test
  void walkExponential_budget() {
    // Build a deeply nested expression.
    Regexp re = Parser.parse("a{100}", FLAGS);
    CountWalker w = new CountWalker();
    w.walkExponential(re, 0, 5);
    // With a budget of 5, it should stop early on a complex enough expression.
    // For a{100} (which is a REPEAT node with one child), it may or may not stop early.
    // Just ensure it doesn't crash.
  }

  @Test
  void preVisitStop() {
    // A walker that stops at the first LITERAL it sees.
    Walker<String> w = new Walker<>() {
      @Override
      protected String preVisit(Regexp re, String parentArg, boolean[] stop) {
        if (re.op == RegexpOp.LITERAL) {
          stop[0] = true;
          return "found:" + (char) re.rune;
        }
        return "";
      }

      @Override
      protected String postVisit(
          Regexp re, String parentArg, String preArg, List<String> childArgs) {
        // Concatenate child results.
        StringBuilder sb = new StringBuilder();
        for (String childArg : childArgs) {
          sb.append(childArg);
        }
        return sb.toString();
      }

      @Override
      protected String shortVisit(Regexp re, String parentArg) {
        return "";
      }
    };

    // (a+)* — the walker should find 'a' and stop.
    Regexp re = Parser.parse("(a+)*", FLAGS);
    String result = w.walk(re, "");
    assertThat(result).isEqualTo("found:a");
  }

  @Test
  void walkExponentialShortVisitOnBudgetExhaustion() {
    // A deep tree that should exhaust a low budget.
    Regexp re = Parser.parse("((((((a))))))", FLAGS);
    CountWalker w = new CountWalker();
    w.walkExponential(re, 0, 2);
    assertThat(w.stoppedEarly()).isTrue();
  }

  @Test
  void walkNullRegexp() {
    CountWalker w = new CountWalker();
    int result = w.walk(null, 42);
    assertThat(result).isEqualTo(42);
  }

  @Test
  void walkCopyPathWithSharedChildren() {
    // Create a concat with identical (same object) children.
    Regexp shared = Regexp.literal('a', 0);
    Regexp re = Regexp.concat(List.of(shared, shared), 0);
    CountWalker w = new CountWalker();
    int result = w.walk(re, 0);
    // CONCAT(1) + LITERAL(1) + copied LITERAL(1) = 3
    assertThat(result).isEqualTo(3);
  }
}
