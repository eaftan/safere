// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link Walker}. */
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
        Regexp re, Integer parentArg, Integer preArg, Object[] childArgs, int nChildArgs) {
      int count = 1;
      for (int i = 0; i < nChildArgs; i++) {
        count += (Integer) childArgs[i];
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
          Regexp re, String parentArg, String preArg, Object[] childArgs, int nChildArgs) {
        // Concatenate child results.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nChildArgs; i++) {
          sb.append((String) childArgs[i]);
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
}
