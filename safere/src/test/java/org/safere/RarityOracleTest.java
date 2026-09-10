// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class RarityOracleTest {

  @Test
  void spaceIsMostCommonAndRareLettersHaveHighRank() {
    assertThat(RarityOracle.exactByteRarity(' ')).isEqualTo(0);
    assertThat(RarityOracle.caseFoldedByteRarity(' ')).isEqualTo(0);
    assertThat(RarityOracle.exactByteRarity('e')).isLessThan(RarityOracle.exactByteRarity('z'));
    assertThat(RarityOracle.exactByteRarity('t')).isLessThan(RarityOracle.exactByteRarity('q'));
    assertThat(RarityOracle.exactByteRarity('a')).isLessThan(RarityOracle.exactByteRarity('x'));
  }

  @Test
  void caseInsensitiveLettersShareIdenticalRanks() {
    assertThat(RarityOracle.caseFoldedByteRarity('A'))
        .isEqualTo(RarityOracle.caseFoldedByteRarity('a'));
    assertThat(RarityOracle.caseFoldedByteRarity('Z'))
        .isEqualTo(RarityOracle.caseFoldedByteRarity('z'));
    assertThat(RarityOracle.caseFoldedByteRarity('E'))
        .isEqualTo(RarityOracle.caseFoldedByteRarity('e'));
  }

  @Test
  void exactCaseDistinguishesUppercaseAndLowercaseRarity() {
    assertThat(RarityOracle.exactByteRarity('A')).isGreaterThan(RarityOracle.exactByteRarity('a'));
    assertThat(RarityOracle.exactByteRarity('E')).isGreaterThan(RarityOracle.exactByteRarity('e'));
    assertThat(RarityOracle.exactByteRarity('Z')).isGreaterThan(RarityOracle.exactByteRarity('z'));
  }

  @Test
  void rarestAsciiOffsetFindsRarestCharacterCaseFolded() {
    // 't', 'h', 'e' are common, 'q' is rare
    String prefix = "the_query";
    int offset = RarityOracle.rarestAsciiOffset(prefix, prefix.length(), true);
    assertThat(offset).isEqualTo(prefix.indexOf('q'));

    // 'a' is common, 'z' is rare
    String zone = "authorization";
    assertThat(RarityOracle.rarestAsciiOffset(zone, zone.length(), true))
        .isEqualTo(zone.indexOf('z'));
  }

  @Test
  void rarestAsciiOffsetFindsUppercaseAnchorForExactCase() {
    String bean = "AbstractBeanFactory";
    int offset = RarityOracle.rarestAsciiOffset(bean, bean.length(), false);
    assertThat(bean.charAt(offset)).isIn('B', 'F');

    String header = "Content-Type";
    int headerOffset = RarityOracle.rarestAsciiOffset(header, header.length(), false);
    assertThat(header.charAt(headerOffset)).isIn('C', 'T');
  }

  @Test
  void literalSelectivityRewardsRareCharacters() {
    // "404_NOT_FOUND" contains digits, underscores, and rare letters
    int rareScore = RarityOracle.literalSelectivityScore("404_NOT_FOUND");
    // "              " (spaces of equal length) has very low score
    int commonScore = RarityOracle.literalSelectivityScore("             ");
    assertThat(rareScore).isGreaterThan(commonScore * 3);
  }

  @Test
  void literalSelectivityRetainsLengthForTheMostCommonCharacter() {
    assertThat(RarityOracle.literalSelectivityScore(" ".repeat(32)))
        .isGreaterThan(RarityOracle.literalSelectivityScore("ee"));
  }

  @Test
  void poisonousAnchorDetection() {
    assertThat(RarityOracle.isPoisonousAnchor(" ", false)).isTrue();
    assertThat(RarityOracle.isPoisonousAnchor("e", false)).isTrue();
    assertThat(RarityOracle.isPoisonousAnchor("E", false)).isFalse();
    assertThat(RarityOracle.isPoisonousAnchor("E", true)).isTrue();
    assertThat(RarityOracle.isPoisonousAnchor("z", false)).isFalse();
    assertThat(RarityOracle.isPoisonousAnchor("q", true)).isFalse();
    assertThat(RarityOracle.isPoisonousAnchor("404", false)).isFalse();
    assertThat(RarityOracle.isPoisonousAnchor("  ", false)).isFalse();
    assertThat(RarityOracle.isPoisonousAnchor(null, false)).isFalse();
    assertThat(RarityOracle.isPoisonousAnchor("", false)).isFalse();
  }

  @Test
  void literalSelectivityDistinguishesExactAndFoldedScores() {
    int exactUpperScore = RarityOracle.literalSelectivityScore("ERROR", false);
    int exactLowerScore = RarityOracle.literalSelectivityScore("error", false);
    assertThat(exactUpperScore).isGreaterThan(exactLowerScore);
  }

  @Test
  void foldedMultiAnchorSelectionIsInvariantToPatternCapitalization() {
    MultiAnchorDescriptor.Anchor.Single upper =
        MultiAnchorDescriptor.Anchor.Single.create("Xq", true);
    MultiAnchorDescriptor.Anchor.Single lower =
        MultiAnchorDescriptor.Anchor.Single.create("xq", true);

    assertThat(upper.anchorOffset()).isEqualTo(lower.anchorOffset()).isEqualTo(1);
    assertThat(upper.selectivityScore()).isEqualTo(lower.selectivityScore());

    MultiAnchorDescriptor.Anchor upperAlternation =
        MultiAnchorDescriptor.Anchor.create(new String[] {"Xq", "Za"}, true);
    MultiAnchorDescriptor.Anchor lowerAlternation =
        MultiAnchorDescriptor.Anchor.create(new String[] {"xq", "za"}, true);
    assertThat(upperAlternation.selectivityScore()).isEqualTo(lowerAlternation.selectivityScore());
  }

  @Test
  void foldedPreparedLiteralSelectsRarestCaseFoldedAnchor() {
    Matcher.PreparedMatchRunner runner = Pattern.compile("(?i)jq").preparedMatchRunner(false);

    assertThat(runner).isInstanceOf(Matcher.LiteralPreparedRunner.class);
    Matcher.LiteralPreparedRunner literalRunner = (Matcher.LiteralPreparedRunner) runner;
    assertThat(literalRunner.anchorOffset()).isEqualTo(1);
    assertThat(literalRunner.anchorLow()).isEqualTo('q');
    assertThat(literalRunner.anchorHigh()).isEqualTo('Q');
  }

  @Test
  void nonAsciiLiteralsAreNotScoredAsUtf8ByteValues() {
    assertThat(RarityOracle.literalSelectivityScore("é"))
        .isGreaterThan(RarityOracle.literalSelectivityScore("eee"));
    assertThat(RarityOracle.literalSelectivityScore("é"))
        .isEqualTo(RarityOracle.literalSelectivityScore("Ā"));
  }

  @Test
  void rarestAsciiOffsetIgnoresNonAsciiCharacters() {
    // '\u03B1' (Greek alpha, c >= 256) defaults to byteRarity 255, which would erroneously
    // preempt ASCII characters if not strictly filtered to c < 128.
    String mixedGreek = "c\u03B1b";
    assertThat(RarityOracle.rarestAsciiOffset(mixedGreek, mixedGreek.length(), false))
        .isEqualTo(2); // 'b' (offset 2, rank 39), not '\u03B1' (offset 1, rank 255)

    String mixedGreekFirst = "b\u03B1c";
    assertThat(RarityOracle.rarestAsciiOffset(mixedGreekFirst, mixedGreekFirst.length(), false))
        .isEqualTo(0); // 'b' (offset 0, rank 39), not '\u03B1' (offset 1, rank 255)

    String leadingNonAscii = "\u03B1the_query";
    assertThat(RarityOracle.rarestAsciiOffset(leadingNonAscii, leadingNonAscii.length(), true))
        .isEqualTo(leadingNonAscii.indexOf('q')); // 'q' (offset 5), not '\u03B1' (offset 0)

    // Latin-1 characters (c >= 128) such as '\u00E9' (0xE9 = 233) or '\u00A9' (0xA9 = 169)
    // must not be chosen as ASCII anchors.
    String mixedLatin1 = "caf\u00E9";
    assertThat(RarityOracle.rarestAsciiOffset(mixedLatin1, mixedLatin1.length(), false))
        .isEqualTo(2); // 'f' (offset 2), not '\u00E9' (offset 3)

    String highLatin1 = "the_\u00A9query";
    assertThat(RarityOracle.rarestAsciiOffset(highLatin1, highLatin1.length(), false))
        .isEqualTo(highLatin1.indexOf('q'));
  }

  @Test
  void rarestAsciiOffsetDefaultsToZeroWhenAllCharactersNonAscii() {
    String allGreek = "\u03B1\u03B2\u03B3";
    assertThat(RarityOracle.rarestAsciiOffset(allGreek, allGreek.length(), false)).isEqualTo(0);
    assertThat(RarityOracle.rarestAsciiOffset(allGreek, allGreek.length(), true)).isEqualTo(0);

    String allLatin1 = "\u00E9\u00E8\u00EA";
    assertThat(RarityOracle.rarestAsciiOffset(allLatin1, allLatin1.length(), false)).isEqualTo(0);
    assertThat(RarityOracle.rarestAsciiOffset(allLatin1, allLatin1.length(), true)).isEqualTo(0);

    assertThat(RarityOracle.rarestAsciiOffset("", 0, false)).isEqualTo(0);
    assertThat(RarityOracle.rarestAsciiOffset("abc", 0, false)).isEqualTo(0);
  }

  @Test
  void utf8LeadBytesClampedToZeroRarity() {
    // Bytes 0xC0..0xFF (192..255) are clamped to 0 (treated as frequent as space) to avoid
    // verification traps on UTF-8 continuation sequences in byte streams.
    for (int b = 0xC0; b <= 0xFF; b++) {
      assertThat(RarityOracle.exactByteRarity(b))
          .as("exactByteRarity for UTF-8 lead byte 0x%02X", b)
          .isEqualTo(0);
      assertThat(RarityOracle.caseFoldedByteRarity(b))
          .as("caseFoldedByteRarity for UTF-8 lead byte 0x%02X", b)
          .isEqualTo(0);
    }
    // Out-of-byte-range code units (c >= 256 or c < 0) default to 255.
    assertThat(RarityOracle.exactByteRarity(256)).isEqualTo(255);
    assertThat(RarityOracle.exactByteRarity(-1)).isEqualTo(255);
    assertThat(RarityOracle.caseFoldedByteRarity(256)).isEqualTo(255);
    assertThat(RarityOracle.caseFoldedByteRarity(-1)).isEqualTo(255);
  }

  @Test
  void rarestAsciiPairIgnoreCaseReturnsNullForShortOrNonAscii() {
    assertThat(RarityOracle.rarestAsciiPairIgnoreCase("", 0)).isNull();
    assertThat(RarityOracle.rarestAsciiPairIgnoreCase("a", 1)).isNull();
    assertThat(RarityOracle.rarestAsciiPairIgnoreCase("a\u0080", 2)).isNull();
    assertThat(RarityOracle.rarestAsciiPairIgnoreCase("hello\u00FFworld", 12)).isNull();
  }

  @Test
  void rarestAsciiPairIgnoreCaseFindsTwoRarestWithOrderedOffsets() {
    String prefix = "content-type";
    RarityOracle.AsciiPair pair = RarityOracle.rarestAsciiPairIgnoreCase(prefix, prefix.length());
    assertThat(pair).isNotNull();
    assertThat(pair.offset1()).isLessThan(pair.offset2());
    char c1 = prefix.charAt(pair.offset1());
    char c2 = prefix.charAt(pair.offset2());
    assertThat(pair.low1()).isEqualTo((byte) Ascii.toLowerCase(c1));
    assertThat(pair.high1()).isEqualTo((byte) Ascii.toUpperCase(c1));
    assertThat(pair.low2()).isEqualTo((byte) Ascii.toLowerCase(c2));
    assertThat(pair.high2()).isEqualTo((byte) Ascii.toUpperCase(c2));
  }

  @Test
  void rarestAsciiPairIgnoreCaseHandlesIdenticalCharacters() {
    String prefix = "banana";
    RarityOracle.AsciiPair pair = RarityOracle.rarestAsciiPairIgnoreCase(prefix, prefix.length());
    assertThat(pair).isNotNull();
    assertThat(pair.offset1()).isLessThan(pair.offset2());
    char c1 = prefix.charAt(pair.offset1());
    char c2 = prefix.charAt(pair.offset2());
    assertThat(pair.low1()).isEqualTo((byte) Ascii.toLowerCase(c1));
    assertThat(pair.high1()).isEqualTo((byte) Ascii.toUpperCase(c1));
    assertThat(pair.low2()).isEqualTo((byte) Ascii.toLowerCase(c2));
    assertThat(pair.high2()).isEqualTo((byte) Ascii.toUpperCase(c2));
  }
}
