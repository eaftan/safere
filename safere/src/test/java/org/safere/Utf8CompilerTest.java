// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link Utf8Compiler}. */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class Utf8CompilerTest {

  private static final int DEFAULT_FLAGS =
      ParseFlags.PERL_X | ParseFlags.PERL_CLASSES | ParseFlags.PERL_B | ParseFlags.UNICODE_GROUPS;

  private static Prog compile(String pattern) {
    Regexp re = Parser.parse(pattern, DEFAULT_FLAGS);
    return Utf8Compiler.compile(re);
  }

  @Nested
  @DisplayName("Literals")
  class Literals {

    @Test
    void compileSingleAsciiLiteral() {
      Prog prog = compile("\\Aa");
      assertThat(prog).isNotNull();

      // Should have 1 CHAR_RANGE instruction matching the ASCII byte 'a' (97)
      assertHasCharRange(prog, 97, 97);
      assertThat(countInstOp(prog, InstOp.CHAR_RANGE)).isEqualTo(1);
    }

    @Test
    void compileMultibyteLiteral() {
      Prog prog = compile("\\A一"); // \u4e00 -> UTF-8: E4 B8 80
      assertThat(prog).isNotNull();

      // Should compile to a concatenation of 3 single-byte range matches
      assertHasCharRange(prog, 0xE4, 0xE4);
      assertHasCharRange(prog, 0xB8, 0xB8);
      assertHasCharRange(prog, 0x80, 0x80);

      assertThat(countInstOp(prog, InstOp.CHAR_RANGE)).isEqualTo(3);
    }

    @Test
    void compileTwoCharLiteralString() {
      Prog prog = compile("\\Aa一"); // 'a' (97) + \u4e00 (E4 B8 80)
      assertThat(prog).isNotNull();

      assertHasCharRange(prog, 97, 97);
      assertHasCharRange(prog, 0xE4, 0xE4);
      assertHasCharRange(prog, 0xB8, 0xB8);
      assertHasCharRange(prog, 0x80, 0x80);

      assertThat(countInstOp(prog, InstOp.CHAR_RANGE)).isEqualTo(4);
    }
  }

  @Nested
  @DisplayName("Character Classes")
  class CharacterClasses {

    @Test
    void compileAsciiRange() {
      Prog prog = compile("\\A[a-c]");
      assertThat(prog).isNotNull();

      // ASCII range matches in 1 step: byte range 97 to 99
      assertHasCharRange(prog, 97, 99);
      assertThat(countInstOp(prog, InstOp.CHAR_RANGE)).isEqualTo(1);
    }

    @Test
    void compileMultibyteClass() {
      Prog prog = compile("\\A[一-二]"); // [\u4e00-\u4e8c]
      assertThat(prog).isNotNull();

      // Decomposes to an alternation of byte suffixes, so it should contain
      // byte matches for leading E4, second bytes (B8-BA), and trailing bytes
      assertHasCharRange(prog, 0xE4, 0xE4);

      // Instructions should all be single-byte range matches (0-255)
      for (int i = 0; i < prog.size(); i++) {
        Inst inst = prog.inst(i);
        if (inst.op == InstOp.CHAR_RANGE) {
          assertThat(inst.lo).isBetween(0, 255);
          assertThat(inst.hi).isBetween(0, 255);
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Helper methods
  // ---------------------------------------------------------------------------

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

  private static int countInstOp(Prog prog, InstOp op) {
    int count = 0;
    for (int i = 0; i < prog.size(); i++) {
      if (prog.inst(i).op == op) {
        count++;
      }
    }
    return count;
  }
}
