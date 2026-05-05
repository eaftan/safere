// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.BitSet;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Tests for {@link CharClass} and {@link CharClassBuilder}. */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class CharClassTest {

  @Test
  void emptyCharClass() {
    assertThat(CharClass.EMPTY.numRanges()).isEqualTo(0);
    assertThat(CharClass.EMPTY.numRunes()).isEqualTo(0);
    assertThat(CharClass.EMPTY.isEmpty()).isTrue();
    assertThat(CharClass.EMPTY.contains('a')).isFalse();
  }

  @Test
  void singleRange() {
    CharClass cc = new CharClassBuilder().addRange('a', 'z').build();
    assertThat(cc.numRanges()).isEqualTo(1);
    assertThat(cc.numRunes()).isEqualTo(26);
    assertThat(cc.lo(0)).isEqualTo('a');
    assertThat(cc.hi(0)).isEqualTo('z');
    assertThat(cc.contains('a')).isTrue();
    assertThat(cc.contains('m')).isTrue();
    assertThat(cc.contains('z')).isTrue();
    assertThat(cc.contains('A')).isFalse();
    assertThat(cc.contains('0')).isFalse();
  }

  @Test
  void multipleDisjointRanges() {
    CharClass cc =
        new CharClassBuilder().addRange('0', '9').addRange('A', 'Z').addRange('a', 'z').build();
    assertThat(cc.numRanges()).isEqualTo(3);
    assertThat(cc.numRunes()).isEqualTo(10 + 26 + 26);
    assertThat(cc.contains('5')).isTrue();
    assertThat(cc.contains('G')).isTrue();
    assertThat(cc.contains('p')).isTrue();
    assertThat(cc.contains('!')).isFalse();
    assertThat(cc.contains('[')).isFalse();
  }

  @Test
  void overlappingRangesMerge() {
    CharClass cc = new CharClassBuilder().addRange('a', 'm').addRange('k', 'z').build();
    assertThat(cc.numRanges()).isEqualTo(1);
    assertThat(cc.numRunes()).isEqualTo(26);
    assertThat(cc.lo(0)).isEqualTo('a');
    assertThat(cc.hi(0)).isEqualTo('z');
  }

  @Test
  void adjacentRangesMerge() {
    CharClass cc = new CharClassBuilder().addRange('a', 'f').addRange('g', 'z').build();
    assertThat(cc.numRanges()).isEqualTo(1);
    assertThat(cc.numRunes()).isEqualTo(26);
  }

  @Test
  void addRangeMergesOnlyNeighboringRanges() {
    CharClass cc =
        new CharClassBuilder()
            .addRange('a', 'c')
            .addRange('m', 'p')
            .addRange('x', 'z')
            .addRange('d', 'n')
            .build();

    assertThat(cc.numRanges()).isEqualTo(2);
    assertThat(cc.lo(0)).isEqualTo('a');
    assertThat(cc.hi(0)).isEqualTo('p');
    assertThat(cc.lo(1)).isEqualTo('x');
    assertThat(cc.hi(1)).isEqualTo('z');
  }

  @Test
  void addRangeKeepsEarlierDisjointRanges() {
    CharClass cc =
        new CharClassBuilder()
            .addRange('a', 'c')
            .addRange('m', 'p')
            .addRange('x', 'z')
            .addRange('q', 'v')
            .build();

    assertThat(cc.numRanges()).isEqualTo(3);
    assertThat(cc.lo(0)).isEqualTo('a');
    assertThat(cc.hi(0)).isEqualTo('c');
    assertThat(cc.lo(1)).isEqualTo('m');
    assertThat(cc.hi(1)).isEqualTo('v');
    assertThat(cc.lo(2)).isEqualTo('x');
    assertThat(cc.hi(2)).isEqualTo('z');
  }

  @Test
  void addRangeBeforeAllRanges() {
    CharClass cc =
        new CharClassBuilder()
            .addRange(100, 110)
            .addRange(200, 210)
            .addRange(10, 20)
            .build();

    assertRanges(cc, 10, 20, 100, 110, 200, 210);
  }

  @Test
  void addRangeAfterAllRanges() {
    CharClass cc =
        new CharClassBuilder()
            .addRange(10, 20)
            .addRange(100, 110)
            .addRange(200, 210)
            .build();

    assertRanges(cc, 10, 20, 100, 110, 200, 210);
  }

  @Test
  void addRangeMergesPreviousNeighbor() {
    CharClass cc =
        new CharClassBuilder()
            .addRange(10, 20)
            .addRange(100, 110)
            .addRange(21, 30)
            .build();

    assertRanges(cc, 10, 30, 100, 110);
  }

  @Test
  void addRangeMergesNextNeighbor() {
    CharClass cc =
        new CharClassBuilder()
            .addRange(10, 20)
            .addRange(100, 110)
            .addRange(90, 99)
            .build();

    assertRanges(cc, 10, 20, 90, 110);
  }

  @Test
  void addRangeMergesAcrossMultipleRanges() {
    CharClass cc =
        new CharClassBuilder()
            .addRange(10, 20)
            .addRange(30, 40)
            .addRange(50, 60)
            .addRange(21, 49)
            .build();

    assertRanges(cc, 10, 60);
  }

  @Test
  void addRangeHandlesEmptyAndBoundaryRanges() {
    CharClass cc =
        new CharClassBuilder()
            .addRange(20, 10)
            .addRange(0, 0)
            .addRange(Utils.MAX_RUNE, Utils.MAX_RUNE)
            .build();

    assertRanges(cc, 0, 0, Utils.MAX_RUNE, Utils.MAX_RUNE);
  }

  @Test
  void randomizedAddRangeMatchesBitSetReferenceModel() {
    int domainSize = 4096;
    Random random = new Random(0x5AFE_289L);

    for (int trial = 0; trial < 200; trial++) {
      CharClassBuilder builder = new CharClassBuilder();
      BitSet expected = new BitSet(domainSize);

      for (int step = 0; step < 300; step++) {
        int a = random.nextInt(domainSize);
        int b = random.nextInt(domainSize);
        if (random.nextInt(10) == 0) {
          builder.addRange(a, b);
          if (a <= b) {
            expected.set(a, b + 1);
          }
        } else {
          int lo = Math.min(a, b);
          int hi = Math.max(a, b);
          builder.addRange(lo, hi);
          expected.set(lo, hi + 1);
        }
      }

      assertMatchesReferenceModel(builder.build(), expected, domainSize);
    }
  }

  @Test
  void singleRune() {
    CharClass cc = new CharClassBuilder().addRune('X').build();
    assertThat(cc.numRanges()).isEqualTo(1);
    assertThat(cc.numRunes()).isEqualTo(1);
    assertThat(cc.contains('X')).isTrue();
    assertThat(cc.contains('Y')).isFalse();
  }

  @Test
  void addTable() {
    int[][] table = {{0x30, 0x39}, {0x41, 0x5A}, {0x61, 0x7A}};
    CharClass cc = new CharClassBuilder().addTable(table).build();
    assertThat(cc.numRanges()).isEqualTo(3);
    assertThat(cc.contains('5')).isTrue();
    assertThat(cc.contains('G')).isTrue();
    assertThat(cc.contains('p')).isTrue();
  }

  @Test
  void negateAsciiDigits() {
    CharClass digits = new CharClassBuilder().addRange('0', '9').build();
    CharClass notDigits = digits.negate();
    assertThat(notDigits.contains('5')).isFalse();
    assertThat(notDigits.contains('a')).isTrue();
    assertThat(notDigits.contains(0)).isTrue();
    assertThat(notDigits.contains(0x10FFFF)).isTrue();
  }

  @Test
  void negateSkipsSurrogates() {
    // A negated class should not include surrogates (0xD800-0xDFFF).
    CharClass empty = CharClass.EMPTY.negate();
    assertThat(empty.contains(0xD800)).isFalse();
    assertThat(empty.contains(0xDBFF)).isFalse();
    assertThat(empty.contains(0xDC00)).isFalse();
    assertThat(empty.contains(0xDFFF)).isFalse();
    assertThat(empty.contains(0xD7FF)).isTrue();
    assertThat(empty.contains(0xE000)).isTrue();
  }

  @Test
  void doubleNegateRestoresOriginal() {
    CharClass cc = new CharClassBuilder().addRange('a', 'z').build();
    CharClass doubleNeg = cc.negate().negate();
    assertThat(doubleNeg.numRanges()).isEqualTo(cc.numRanges());
    assertThat(doubleNeg.numRunes()).isEqualTo(cc.numRunes());
    for (int i = 0; i < cc.numRanges(); i++) {
      assertThat(doubleNeg.lo(i)).isEqualTo(cc.lo(i));
      assertThat(doubleNeg.hi(i)).isEqualTo(cc.hi(i));
    }
  }

  @Test
  void removeRange() {
    CharClassBuilder builder = new CharClassBuilder();
    builder.addRange('a', 'z');
    builder.removeRange('m', 'n');
    CharClass cc = builder.build();
    assertThat(cc.numRanges()).isEqualTo(2);
    assertThat(cc.contains('a')).isTrue();
    assertThat(cc.contains('l')).isTrue();
    assertThat(cc.contains('m')).isFalse();
    assertThat(cc.contains('n')).isFalse();
    assertThat(cc.contains('o')).isTrue();
    assertThat(cc.contains('z')).isTrue();
  }

  @Test
  void removeEntireRange() {
    CharClassBuilder builder = new CharClassBuilder();
    builder.addRange('a', 'c');
    builder.removeRange('a', 'c');
    assertThat(builder.isEmpty()).isTrue();
  }

  @Test
  void builderContains() {
    CharClassBuilder builder = new CharClassBuilder();
    builder.addRange('a', 'z');
    assertThat(builder.contains('a')).isTrue();
    assertThat(builder.contains('m')).isTrue();
    assertThat(builder.contains('z')).isTrue();
    assertThat(builder.contains('A')).isFalse();
  }

  @Test
  void addCharClassMerges() {
    CharClass cc1 = new CharClassBuilder().addRange('a', 'f').build();
    CharClass cc2 = new CharClassBuilder().addRange('d', 'z').build();
    CharClass merged = new CharClassBuilder().addCharClass(cc1).addCharClass(cc2).build();
    assertThat(merged.numRanges()).isEqualTo(1);
    assertThat(merged.numRunes()).isEqualTo(26);
  }

  @Test
  void builderFromCharClass() {
    CharClass original = new CharClassBuilder().addRange('a', 'z').addRange('0', '9').build();
    CharClassBuilder copy = new CharClassBuilder(original);
    CharClass rebuilt = copy.build();
    assertThat(rebuilt.numRanges()).isEqualTo(original.numRanges());
    assertThat(rebuilt.numRunes()).isEqualTo(original.numRunes());
  }

  @Test
  void charClassToString() {
    CharClass cc = new CharClassBuilder().addRange('a', 'z').addRune('!').build();
    String s = cc.toString();
    assertThat(s).startsWith("[");
    assertThat(s).endsWith("]");
    assertThat(s).contains("0x61-0x7A"); // a-z
    assertThat(s).contains("0x21"); // !
  }

  @Test
  void supplementaryCodePoints() {
    // U+1F600 to U+1F64F (Emoticons)
    CharClass cc = new CharClassBuilder().addRange(0x1F600, 0x1F64F).build();
    assertThat(cc.numRanges()).isEqualTo(1);
    assertThat(cc.contains(0x1F600)).isTrue();
    assertThat(cc.contains(0x1F64F)).isTrue();
    assertThat(cc.contains(0x1F5FF)).isFalse();
  }

  private static void assertRanges(CharClass cc, int... endpoints) {
    assertThat(endpoints.length).isEven();
    assertThat(cc.numRanges()).isEqualTo(endpoints.length / 2);

    int expectedRunes = 0;
    for (int i = 0; i < endpoints.length; i += 2) {
      int rangeIndex = i / 2;
      assertThat(cc.lo(rangeIndex)).isEqualTo(endpoints[i]);
      assertThat(cc.hi(rangeIndex)).isEqualTo(endpoints[i + 1]);
      expectedRunes += endpoints[i + 1] - endpoints[i] + 1;
    }
    assertThat(cc.numRunes()).isEqualTo(expectedRunes);
  }

  private static void assertMatchesReferenceModel(CharClass cc, BitSet expected, int domainSize) {
    int expectedRunes = 0;
    for (int r = 0; r < domainSize; r++) {
      boolean shouldContain = expected.get(r);
      assertThat(cc.contains(r)).isEqualTo(shouldContain);
      if (shouldContain) {
        expectedRunes++;
      }
    }

    assertThat(cc.numRunes()).isEqualTo(expectedRunes);

    int rangeIndex = 0;
    for (int lo = expected.nextSetBit(0); lo >= 0; ) {
      int hiExclusive = expected.nextClearBit(lo);
      assertThat(rangeIndex).isLessThan(cc.numRanges());
      assertThat(cc.lo(rangeIndex)).isEqualTo(lo);
      assertThat(cc.hi(rangeIndex)).isEqualTo(hiExclusive - 1);
      rangeIndex++;
      lo = expected.nextSetBit(hiExclusive);
    }
    assertThat(rangeIndex).isEqualTo(cc.numRanges());
  }
}
