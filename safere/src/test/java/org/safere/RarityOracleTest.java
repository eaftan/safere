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
  void literalSelectivityDistinguishesExactAndFoldedScores() {
    int exactUpperScore = RarityOracle.literalSelectivityScore("ERROR", false);
    int exactLowerScore = RarityOracle.literalSelectivityScore("error", false);
    assertThat(exactUpperScore).isGreaterThan(exactLowerScore);
  }
}
