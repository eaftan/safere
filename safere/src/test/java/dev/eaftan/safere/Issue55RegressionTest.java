// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for issue #55: zero-width match inside repetition does not break the loop like
 * JDK.
 *
 * <p>JDK breaks {@code *}/{@code +} repetition loops after a zero-width body match (to prevent
 * infinite repetition). Before this fix, SafeRE (following RE2 semantics) continued the loop,
 * allowing a subsequent consuming alternative to match.
 */
class Issue55RegressionTest {

  @Nested
  @DisplayName("Core issue: \\B|a in repetition")
  class WordBoundaryAlternation {

    @Test
    @DisplayName("(?:\\B|a)* on 'aa' should match [0,1) not [0,2)")
    void nonWordBoundaryOrCharStar() {
      Pattern p = Pattern.compile("(?:\\B|a)*");
      Matcher m = p.matcher("aa");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(1);
    }

    @Test
    @DisplayName("(?:\\B|a)+ on 'aa' should match [0,1)")
    void nonWordBoundaryOrCharPlus() {
      Pattern p = Pattern.compile("(?:\\B|a)+");
      Matcher m = p.matcher("aa");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(1);
    }

    @Test
    @DisplayName("(?:\\b|a)* on 'aa' should match [0,0) — \\b at start terminates loop")
    void wordBoundaryOrCharStar() {
      // \\b at position 0 is zero-width and succeeds (word boundary before 'a').
      // The loop body matched zero-width → loop terminates immediately.
      Pattern p = Pattern.compile("(?:\\b|a)*");
      Matcher m = p.matcher("aa");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("Consuming alternative still works")
  class ConsumingAlternative {

    @Test
    @DisplayName("(a|)* on 'aa' should match [0,2) with group(1)=[2,2)")
    void consumingOrEmptyStar() {
      Pattern p = Pattern.compile("(a|)*");
      Matcher m = p.matcher("aa");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(2);
      assertThat(m.start(1)).isEqualTo(2);
      assertThat(m.end(1)).isEqualTo(2);
    }

    @Test
    @DisplayName("(a|)+ on 'aa' should match [0,2)")
    void consumingOrEmptyPlus() {
      Pattern p = Pattern.compile("(a|)+");
      Matcher m = p.matcher("aa");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("Nullable body in bounded repetition")
  class NullableBodyRepetition {

    @Test
    @DisplayName("([a]*)*  on 'aaaaaa' — group(1) captures the zero-width exit match")
    void starOfStar() {
      Pattern p = Pattern.compile("([a]*)*");
      Matcher m = p.matcher("aaaaaa");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(6);
      assertThat(m.start(1)).isEqualTo(6);
      assertThat(m.end(1)).isEqualTo(6);
    }

    @Test
    @DisplayName("X(.?){2,}Y on 'XABCDEFY' — group(1) at [7,7)")
    void boundedRepetitionCapture() {
      Pattern p = Pattern.compile("X(.?){2,}Y");
      Matcher m = p.matcher("XABCDEFY");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(8);
      assertThat(m.start(1)).isEqualTo(7);
      assertThat(m.end(1)).isEqualTo(7);
    }
  }

  @Nested
  @DisplayName("Zero-width assertions in repetition")
  class ZeroWidthAssertionRepetition {

    @Test
    @DisplayName("(?:$)+ on 'a' should not match")
    void dollarPlus() {
      Pattern p = Pattern.compile("(?:$)+");
      Matcher m = p.matcher("a");
      // $ only matches at end of string; + requires at least one match
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(1);
      assertThat(m.end()).isEqualTo(1);
    }

    @Test
    @DisplayName("(?:^)+ at start should match zero-width once")
    void caretPlus() {
      Pattern p = Pattern.compile("(?:^)+");
      Matcher m = p.matcher("a");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(0);
    }

    @Test
    @DisplayName("(?:(?:^)|\\w)* on 'a' — matches() returns true")
    void caretOrWordStar() {
      assertThat(Pattern.matches("(?:(?:^)|\\w)*", "a")).isTrue();
    }
  }

  @Nested
  @DisplayName("Non-greedy repetition with nullable body")
  class NonGreedy {

    @Test
    @DisplayName("(a|)*? on 'aa' should match [0,0)")
    void nonGreedyStarNullable() {
      Pattern p = Pattern.compile("(a|)*?");
      Matcher m = p.matcher("aa");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(0);
    }

    @Test
    @DisplayName("(a|)+? on 'aa' should match [0,1)")
    void nonGreedyPlusNullable() {
      Pattern p = Pattern.compile("(a|)+?");
      Matcher m = p.matcher("aa");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("Dollar-newline in repetition (issue #55 related)")
  class DollarNewline {

    @Test
    @DisplayName("(?:$|\\n)+ on 'a\\n\\n' matches JDK behavior")
    void dollarOrNewlinePlus() {
      Pattern p = Pattern.compile("(?:$|\\n)+");
      Matcher m = p.matcher("a\n\n");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(1);
      assertThat(m.end()).isEqualTo(2);
    }
  }
}
