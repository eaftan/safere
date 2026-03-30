// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for <a href="https://github.com/eaftan/safere/issues/42">issue #42</a>: three
 * pre-existing bugs found by CrossEngineExhaustiveTest.
 */
class Issue42RegressionTest {

  // ---------------------------------------------------------------------------
  // Bug 1: $|\b alternation — SafeRE must find the leftmost \b match
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Bug 1: $|\\b alternation leftmost match")
  class Bug1WordBoundary {

    @Test
    @DisplayName("(?:$|\\b) on \" a\" finds \\b at [1,1)")
    void dollarOrWordBoundary() {
      Matcher m = Pattern.compile("(?:$|\\b)").matcher(" a");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(1);
      assertThat(m.end()).isEqualTo(1);
    }

    @Test
    @DisplayName("(?:\\b|$) on \" a\" finds \\b at [1,1)")
    void wordBoundaryOrDollar() {
      Matcher m = Pattern.compile("(?:\\b|$)").matcher(" a");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(1);
      assertThat(m.end()).isEqualTo(1);
    }

    @Test
    @DisplayName("$|\\b on longer text finds leftmost \\b")
    void dollarOrWordBoundaryLongText() {
      Matcher m = Pattern.compile("(?:$|\\b)").matcher("hello world");
      assertThat(m.find()).isTrue();
      // \b at position 0 (start of "hello") is leftmost
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(0);
    }

    @Test
    @DisplayName("findAll with $|\\b finds all positions")
    void dollarOrWordBoundaryFindAll() {
      Matcher m = Pattern.compile("(?:$|\\b)").matcher(" a");
      List<int[]> matches = new ArrayList<>();
      while (m.find()) {
        matches.add(new int[]{m.start(), m.end()});
      }
      // Expected: [0,0) (non-word boundary? no — space at 0 is non-word, start of text is
      // non-word, so no \b. $ doesn't match either). Actually at pos 0: $ at pos 0? No.
      // \b at pos 0: prev is start-of-text (non-word), next is ' ' (non-word) → no boundary.
      // Position 1: \b matches (space→a). Position 2: $ matches (end of text).
      // Also \b at position 2: prev='a' (word), next=end (non-word) → \b matches.
      // So both \b and $ match at position 2, but that's one match.
      assertThat(matches).hasSize(2);
      assertThat(matches.get(0)).containsExactly(1, 1); // \b at space→a
      assertThat(matches.get(1)).containsExactly(2, 2); // \b and/or $ at end
    }
  }

  // ---------------------------------------------------------------------------
  // Bug 3: Multiline ^|$ findAll must find $ before \n
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Bug 3: Multiline ^|$ findAll")
  class Bug3MultilineDollar {

    @Test
    @DisplayName("(?:^|$) on \"aa\\na\" with MULTILINE finds all four positions")
    void caretOrDollarMultiline() {
      Matcher m = Pattern.compile("(?:^|$)", Pattern.MULTILINE).matcher("aa\na");
      List<int[]> matches = new ArrayList<>();
      while (m.find()) {
        matches.add(new int[]{m.start(), m.end()});
      }
      // ^ at pos 0, $ at pos 2 (before \n), ^ at pos 3 (after \n), $ at pos 4 (end)
      assertThat(matches).hasSize(4);
      assertThat(matches.get(0)).containsExactly(0, 0); // ^ at start
      assertThat(matches.get(1)).containsExactly(2, 2); // $ before \n
      assertThat(matches.get(2)).containsExactly(3, 3); // ^ after \n
      assertThat(matches.get(3)).containsExactly(4, 4); // $ at end
    }

    @Test
    @DisplayName("(?:$|^) on \"aa\\na\" with MULTILINE (reversed order)")
    void dollarOrCaretMultiline() {
      Matcher m = Pattern.compile("(?:$|^)", Pattern.MULTILINE).matcher("aa\na");
      List<int[]> matches = new ArrayList<>();
      while (m.find()) {
        matches.add(new int[]{m.start(), m.end()});
      }
      assertThat(matches).hasSize(4);
      assertThat(matches.get(0)).containsExactly(0, 0);
      assertThat(matches.get(1)).containsExactly(2, 2);
      assertThat(matches.get(2)).containsExactly(3, 3);
      assertThat(matches.get(3)).containsExactly(4, 4);
    }

    @Test
    @DisplayName("(?:^|$) on \"a\\nb\\nc\" with MULTILINE")
    void caretOrDollarMultipleLines() {
      Matcher m = Pattern.compile("(?:^|$)", Pattern.MULTILINE).matcher("a\nb\nc");
      List<int[]> matches = new ArrayList<>();
      while (m.find()) {
        matches.add(new int[]{m.start(), m.end()});
      }
      // ^ at 0, $ at 1 (before \n), ^ at 2 (after \n), $ at 3 (before \n), ^ at 4, $ at 5
      assertThat(matches).hasSize(6);
      assertThat(matches.get(0)).containsExactly(0, 0);
      assertThat(matches.get(1)).containsExactly(1, 1);
      assertThat(matches.get(2)).containsExactly(2, 2);
      assertThat(matches.get(3)).containsExactly(3, 3);
      assertThat(matches.get(4)).containsExactly(4, 4);
      assertThat(matches.get(5)).containsExactly(5, 5);
    }

    @Test
    @DisplayName("First find() still works correctly for multiline ^|$")
    void firstFindMultiline() {
      Matcher m = Pattern.compile("(?:^|$)", Pattern.MULTILINE).matcher("aa\na");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(0);
    }
  }
}
