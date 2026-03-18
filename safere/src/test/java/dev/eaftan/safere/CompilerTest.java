// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;

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

  // ---- Simple literals ----

  @Test
  void compileSingleLiteral() {
    Prog prog = compile("a");
    assertThat(prog).isNotNull();
    assertThat(prog.size()).isGreaterThan(1);
    // Should contain a CHAR_RANGE for 'a' and a MATCH
    assertHasInstOp(prog, InstOp.CHAR_RANGE);
    assertHasInstOp(prog, InstOp.MATCH);
  }

  @Test
  void compileTwoLiterals() {
    Prog prog = compile("ab");
    assertThat(prog).isNotNull();
    // At least two CHAR_RANGE instructions (one for 'a', one for 'b') + MATCH
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

  // ---- Alternation ----

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
    // Should have at least one ALT for the multiple branches
    assertHasInstOp(prog, InstOp.ALT);
  }

  // ---- Quantifiers ----

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

  // ---- Character classes ----

  @Test
  void compileCharClassRange() {
    Prog prog = compile("[a-z]");
    assertThat(prog).isNotNull();
    assertHasInstOp(prog, InstOp.CHAR_RANGE);
    // Should have a CHAR_RANGE covering 'a' to 'z'
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
    // Should have multiple ranges for negation
    int count = countInstOp(prog, InstOp.CHAR_RANGE);
    assertThat(count).isGreaterThanOrEqualTo(1);
  }

  // ---- Captures ----

  @Test
  void compileSingleCapture() {
    Prog prog = compile("(a)");
    assertThat(prog).isNotNull();
    assertHasInstOp(prog, InstOp.CAPTURE);
    // Capture instructions come in pairs (start and end)
    int captureCount = countInstOp(prog, InstOp.CAPTURE);
    assertThat(captureCount).isEqualTo(2);
    assertThat(prog.numCaptures()).isEqualTo(2);
  }

  @Test
  void compileMultiCapture() {
    Prog prog = compile("(a)(b)");
    assertThat(prog).isNotNull();
    int captureCount = countInstOp(prog, InstOp.CAPTURE);
    assertThat(captureCount).isEqualTo(4); // 2 pairs
    assertThat(prog.numCaptures()).isEqualTo(3); // group 0 (full match) + groups 1,2
  }

  @Test
  void compileNonCapturingGroup() {
    Prog prog = compile("(?:a)");
    assertThat(prog).isNotNull();
    // Should NOT have CAPTURE instructions
    int captureCount = countInstOp(prog, InstOp.CAPTURE);
    assertThat(captureCount).isEqualTo(0);
  }

  // ---- Empty width assertions ----

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

  // ---- Any char ----

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

  // ---- Complex patterns ----

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

  // ---- Empty pattern ----

  @Test
  void compileEmptyPattern() {
    Prog prog = compile("");
    assertThat(prog).isNotNull();
    assertHasInstOp(prog, InstOp.MATCH);
  }

  // ---- Anchor detection ----

  @Test
  void anchoredStartPattern() {
    // \A is BEGIN_TEXT, which anchors at start.
    Prog prog = compile("\\Aabc");
    assertThat(prog).isNotNull();
    assertThat(prog.anchorStart()).isTrue();
    // Start and startUnanchored should be the same when anchored at start
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
    // start and startUnanchored should differ because there's a dotstar prefix
    assertThat(prog.startUnanchored()).isNotEqualTo(prog.start());
  }

  // ---- Instruction count ----

  @Test
  void reasonableInstructionCount() {
    Prog prog = compile("a");
    assertThat(prog).isNotNull();
    // Single literal 'a' should not produce a huge number of instructions
    // Fail + dotstar loop (ALT, CHAR_RANGE×2) + CHAR_RANGE for 'a' + MATCH = ~6-8 is reasonable
    assertThat(prog.size()).isLessThan(20);
  }

  @Test
  void largerPatternReasonableSize() {
    Prog prog = compile("[a-zA-Z_][a-zA-Z0-9_]*");
    assertThat(prog).isNotNull();
    assertThat(prog.size()).isLessThan(100);
  }

  // ---- Repeated quantifiers ----

  @Test
  void compileFixedRepeat() {
    // After simplification, a{3} becomes aaa
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

  // ---- Program dump ----

  @Test
  void dumpContainsMeaningfulOutput() {
    Prog prog = compile("a");
    assertThat(prog).isNotNull();
    String dump = prog.dump();
    assertThat(dump).isNotEmpty();
    assertThat(dump).contains("match");
  }

  // ---- Case-insensitive ----

  @Test
  void compileCaseInsensitive() {
    Prog prog = compile("(?i)abc");
    assertThat(prog).isNotNull();
    assertHasInstOp(prog, InstOp.CHAR_RANGE);
    // Should have foldCase CHAR_RANGE instructions
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

  // ---- Unicode characters ----

  @Test
  void compileUnicodeCharacter() {
    // Compile a pattern with a non-ASCII Unicode character
    Prog prog = compile("\\x{1F600}"); // emoji
    assertThat(prog).isNotNull();
    assertHasInstOp(prog, InstOp.CHAR_RANGE);
    assertHasInstOp(prog, InstOp.MATCH);
  }

  // ---- Reversed compilation ----

  @Test
  void compileReversed() {
    Regexp re = Parser.parse("ab", DEFAULT_FLAGS);
    Prog prog = Compiler.compile(re, true);
    assertThat(prog).isNotNull();
    assertThat(prog.reversed()).isTrue();
  }

  // ---- Nested groups ----

  @Test
  void compileNestedGroups() {
    Prog prog = compile("((a)(b))");
    assertThat(prog).isNotNull();
    // 3 capturing groups plus the implicit group 0
    assertThat(prog.numCaptures()).isEqualTo(4);
  }

  // ---- toString ----

  @Test
  void progToStringNotNull() {
    Prog prog = compile("a+");
    assertThat(prog).isNotNull();
    assertThat(prog.toString()).isNotNull();
  }

  // ---- Helper methods ----

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
