// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link Compiler}. */
class CompilerTest {

  private static final int DEFAULT_FLAGS =
      ParseFlags.PERL_X | ParseFlags.PERL_CLASSES | ParseFlags.PERL_B
          | ParseFlags.UNICODE_GROUPS;

  private static Prog compile(String pattern) {
    return compile(pattern, DEFAULT_FLAGS);
  }

  private static Prog compile(String pattern, int flags) {
    Regexp re = Parser.parse(pattern, flags);
    return Compiler.compile(re);
  }

  // ---------------------------------------------------------------------------
  // Literals
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Literals")
  class Literals {

    @Test
    void compileSingleLiteral() {
      Prog prog = compile("a");
      assertThat(prog).isNotNull();
      assertThat(prog.size()).isGreaterThan(1);
      assertHasInstOp(prog, InstOp.CHAR_RANGE);
      assertHasInstOp(prog, InstOp.MATCH);
    }

    @Test
    void compileTwoLiterals() {
      Prog prog = compile("ab");
      assertThat(prog).isNotNull();
      int charRangeCount = countInstOp(prog, InstOp.CHAR_RANGE);
      assertThat(charRangeCount).isGreaterThanOrEqualTo(2);
      assertHasInstOp(prog, InstOp.MATCH);
    }

    @Test
    void compileThreeLiterals() {
      Prog prog = compile("abc");
      assertThat(prog).isNotNull();
      int charRangeCount = countInstOp(prog, InstOp.CHAR_RANGE);
      assertThat(charRangeCount).isGreaterThanOrEqualTo(3);
    }

    @Test
    void compileEmptyPattern() {
      Prog prog = compile("");
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.MATCH);
    }
  }

  // ---------------------------------------------------------------------------
  // Alternation
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Alternation")
  class Alternation {

    @Test
    void compileAlternation() {
      Prog prog = compile("a|b");
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.ALT);
      assertHasInstOp(prog, InstOp.MATCH);
    }

    @Test
    void compileMultiAlternation() {
      Prog prog = compile("a|b|c");
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.ALT);
    }
  }

  // ---------------------------------------------------------------------------
  // Quantifiers
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Quantifiers")
  class Quantifiers {

    @Test
    void compileStar() {
      Prog prog = compile("a*");
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.ALT);
      assertHasInstOp(prog, InstOp.CHAR_RANGE);
    }

    @Test
    void compileStarNonGreedy() {
      Prog prog = compile("a*?");
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.ALT);
      assertHasInstOp(prog, InstOp.CHAR_RANGE);
    }

    @Test
    void compilePlus() {
      Prog prog = compile("a+");
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.ALT);
      assertHasInstOp(prog, InstOp.CHAR_RANGE);
    }

    @Test
    void compilePlusNonGreedy() {
      Prog prog = compile("a+?");
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.ALT);
      assertHasInstOp(prog, InstOp.CHAR_RANGE);
    }

    @Test
    void compileQuest() {
      Prog prog = compile("a?");
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.ALT);
      assertHasInstOp(prog, InstOp.CHAR_RANGE);
    }

    @Test
    void compileQuestNonGreedy() {
      Prog prog = compile("a??");
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.ALT);
      assertHasInstOp(prog, InstOp.CHAR_RANGE);
    }

    @Test
    void compileFixedRepeat() {
      Prog prog = compile("a{3}");
      assertThat(prog).isNotNull();
      int charRangeCount = countInstOp(prog, InstOp.CHAR_RANGE);
      assertThat(charRangeCount).isGreaterThanOrEqualTo(3);
    }

    @Test
    void compileBoundedRepeat() {
      Prog prog = compile("a{2,4}");
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.CHAR_RANGE);
      assertHasInstOp(prog, InstOp.MATCH);
    }
  }

  // ---------------------------------------------------------------------------
  // Character classes
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Character classes")
  class CharClasses {

    @Test
    void compileCharClassRange() {
      Prog prog = compile("[a-z]");
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.CHAR_RANGE);
      assertHasCharRange(prog, 'a', 'z');
    }

    @Test
    void compileCharClassEnumeration() {
      Prog prog = compile("[abc]");
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.CHAR_RANGE);
    }

    @Test
    void compileNegatedCharClass() {
      Prog prog = compile("[^a]");
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.CHAR_RANGE);
      int count = countInstOp(prog, InstOp.CHAR_RANGE);
      assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    void compileAnyChar() {
      Prog prog = compile(".");
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.CHAR_RANGE);
    }

    @Test
    void compileAnyCharWithDotNl() {
      Prog prog = compile(".", DEFAULT_FLAGS | ParseFlags.DOT_NL);
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.CHAR_RANGE);
    }
  }

  // ---------------------------------------------------------------------------
  // Captures
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Captures")
  class Captures {

    @Test
    void compileSingleCapture() {
      Prog prog = compile("(a)");
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.CAPTURE);
      int captureCount = countInstOp(prog, InstOp.CAPTURE);
      assertThat(captureCount).isEqualTo(2);
      assertThat(prog.numCaptures()).isEqualTo(2);
    }

    @Test
    void compileMultiCapture() {
      Prog prog = compile("(a)(b)");
      assertThat(prog).isNotNull();
      int captureCount = countInstOp(prog, InstOp.CAPTURE);
      assertThat(captureCount).isEqualTo(4);
      assertThat(prog.numCaptures()).isEqualTo(3);
    }

    @Test
    void compileNonCapturingGroup() {
      Prog prog = compile("(?:a)");
      assertThat(prog).isNotNull();
      int captureCount = countInstOp(prog, InstOp.CAPTURE);
      assertThat(captureCount).isEqualTo(0);
    }

    @Test
    void compileNestedGroups() {
      Prog prog = compile("((a)(b))");
      assertThat(prog).isNotNull();
      assertThat(prog.numCaptures()).isEqualTo(4);
    }
  }

  // ---------------------------------------------------------------------------
  // Anchors and empty-width assertions
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Anchors and assertions")
  class Anchors {

    @Test
    void compileBeginLine() {
      Prog prog = compile("^a");
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.EMPTY_WIDTH);
    }

    @Test
    void compileEndLine() {
      Prog prog = compile("a$");
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.EMPTY_WIDTH);
    }

    @Test
    void compileWordBoundary() {
      Prog prog = compile("\\ba\\b");
      assertThat(prog).isNotNull();
      int emptyCount = countInstOp(prog, InstOp.EMPTY_WIDTH);
      assertThat(emptyCount).isGreaterThanOrEqualTo(2);
    }

    @Test
    void anchoredStartPattern() {
      Prog prog = compile("\\Aabc");
      assertThat(prog).isNotNull();
      assertThat(prog.anchorStart()).isTrue();
      assertThat(prog.start()).isEqualTo(prog.startUnanchored());
    }

    @Test
    void anchoredEndPattern() {
      Prog prog = compile("abc\\z");
      assertThat(prog).isNotNull();
      assertThat(prog.anchorEnd()).isTrue();
    }

    @Test
    void fullyAnchoredPattern() {
      Prog prog = compile("\\Aabc\\z");
      assertThat(prog).isNotNull();
      assertThat(prog.anchorStart()).isTrue();
      assertThat(prog.anchorEnd()).isTrue();
    }

    @Test
    void unanchoredPatternHasDotStar() {
      Prog prog = compile("abc");
      assertThat(prog).isNotNull();
      assertThat(prog.anchorStart()).isFalse();
      assertThat(prog.startUnanchored()).isNotEqualTo(prog.start());
    }
  }

  // ---------------------------------------------------------------------------
  // Complex patterns, flags, and output
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Complex patterns and flags")
  class Complex {

    @Test
    void compileComplexPattern() {
      Prog prog = compile("a(b|c)*d");
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.ALT);
      assertHasInstOp(prog, InstOp.CHAR_RANGE);
      assertHasInstOp(prog, InstOp.CAPTURE);
      assertHasInstOp(prog, InstOp.MATCH);
    }

    @Test
    void compileDigitsAndDot() {
      Prog prog = compile("(\\d+\\.\\d+)");
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.CAPTURE);
      assertHasInstOp(prog, InstOp.MATCH);
    }

    @Test
    void compileCaseInsensitive() {
      Prog prog = compile("(?i)abc");
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.CHAR_RANGE);
      boolean hasFoldCase = false;
      for (int i = 0; i < prog.size(); i++) {
        Inst inst = prog.inst(i);
        if (inst.op == InstOp.CHAR_RANGE && inst.foldCase) {
          hasFoldCase = true;
          break;
        }
      }
      assertThat(hasFoldCase).isTrue();
    }

    @Test
    void compileUnicodeCharacter() {
      Prog prog = compile("\\x{1F600}");
      assertThat(prog).isNotNull();
      assertHasInstOp(prog, InstOp.CHAR_RANGE);
      assertHasInstOp(prog, InstOp.MATCH);
    }

    @Test
    void compileReversed() {
      Regexp re = Parser.parse("ab", DEFAULT_FLAGS);
      Prog prog = Compiler.compile(re, true);
      assertThat(prog).isNotNull();
      assertThat(prog.reversed()).isTrue();
    }

    @Test
    void reasonableInstructionCount() {
      Prog prog = compile("a");
      assertThat(prog).isNotNull();
      assertThat(prog.size()).isLessThan(20);
    }

    @Test
    void largerPatternReasonableSize() {
      Prog prog = compile("[a-zA-Z_][a-zA-Z0-9_]*");
      assertThat(prog).isNotNull();
      assertThat(prog.size()).isLessThan(100);
    }

    @Test
    void dumpContainsMeaningfulOutput() {
      Prog prog = compile("a");
      assertThat(prog).isNotNull();
      String dump = prog.dump();
      assertThat(dump).isNotEmpty();
      assertThat(dump).contains("match");
    }

    @Test
    void progToStringNotNull() {
      Prog prog = compile("a+");
      assertThat(prog).isNotNull();
      assertThat(prog.toString()).isNotNull();
    }
  }

  // ---------------------------------------------------------------------------
  // Helper methods
  // ---------------------------------------------------------------------------

  private static void assertHasInstOp(Prog prog, InstOp op) {
    boolean found = false;
    for (int i = 0; i < prog.size(); i++) {
      if (prog.inst(i).op == op) {
        found = true;
        break;
      }
    }
    assertThat(found)
        .withFailMessage("Expected instruction op %s not found in prog:\n%s", op, prog.dump())
        .isTrue();
  }

  private static int countInstOp(Prog prog, InstOp op) {
    int count = 0;
    for (int i = 0; i < prog.size(); i++) {
      if (prog.inst(i).op == op) {
        count++;
      }
    }
    return count;
  }

  private static void assertHasCharRange(Prog prog, int lo, int hi) {
    boolean found = false;
    for (int i = 0; i < prog.size(); i++) {
      Inst inst = prog.inst(i);
      if (inst.op == InstOp.CHAR_RANGE && inst.lo == lo && inst.hi == hi) {
        found = true;
        break;
      }
    }
    assertThat(found)
        .withFailMessage(
            "Expected CHAR_RANGE [0x%X-0x%X] not found in prog:\n%s", lo, hi, prog.dump())
        .isTrue();
  }
}
