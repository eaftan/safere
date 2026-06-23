// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tests for package-private {@link Pattern} metadata. */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class PatternInternalTest {

  @Test
  void numGroupsCounting() {
    Pattern p = Pattern.compile("(a)(b)(c)");
    assertThat(p.numGroups()).isEqualTo(3);
  }

  @Test
  void numGroupsNoCaptures() {
    Pattern p = Pattern.compile("abc");
    assertThat(p.numGroups()).isZero();
  }

  @Test
  void transparentGroupsPreserveLiteralAccelerators() {
    Pattern p = Pattern.compile("(?:abcdef)");

    assertThat(p.literalMatch()).isEqualTo("abcdef");
    assertThat(p.prefix()).isEqualTo("abcdef");
  }

  @Test
  void transparentGroupsPreserveCharacterClassAccelerators() {
    Pattern p = Pattern.compile("(?:[A-Z]+)");

    boolean[] prefix = p.charClassPrefixAscii();
    assertThat(prefix).isNotNull();
    assertThat(prefix['A']).isTrue();
    assertThat(p.charClassMatchRanges()).isNotNull();
  }

  @Test
  void transparentGroupsPreserveKeywordAlternationAccelerator() {
    Pattern p = Pattern.compile("(?i)\\b(?:error|warning)\\b");

    assertThat(p.keywordAlternation()).isNotNull();
  }

  @Test
  void caseInsensitiveAsciiLiteralPreservesLiteralAccelerators() {
    Pattern p = Pattern.compile("(?i)needle");

    assertThat(p.literalMatch()).isEqualTo("needle");
    assertThat(p.prefix()).isEqualTo("needle");
    assertThat(p.prefixFoldCase()).isTrue();
  }

  @Test
  void caseInsensitiveAsciiPrefixPreservesPrefixAccelerator() {
    Pattern p = Pattern.compile("(?i)needle\\d+");

    assertThat(p.literalMatch()).isNull();
    assertThat(p.prefix()).isEqualTo("needle");
    assertThat(p.prefixFoldCase()).isTrue();
  }

  @Test
  void leadingWordBoundaryPreservesLiteralPrefixAccelerator() {
    Pattern p = Pattern.compile("\\bSCRUB:begin_strip\\b(?s:.*?)\\bSCRUB:end_strip\\b");

    assertThat(p.literalMatch()).isNull();
    assertThat(p.prefix()).isEqualTo("SCRUB:begin_strip");
  }

  @Test
  void leadingTextAnchorsDoNotExposeMovableLiteralPrefixAccelerator() {
    assertThat(Pattern.compile("^SCRUB").prefix()).isNull();
    assertThat(Pattern.compile("\\ASCRUB").prefix()).isNull();
  }

  @Test
  void alternatePrefixAcceleration() {
    Pattern p = Pattern.compile("(?:cat|dog|bird)s?");
    boolean[] prefix = p.charClassPrefixAscii();
    assertThat(prefix).isNotNull();
    assertThat(prefix['c']).isTrue();
    assertThat(prefix['d']).isTrue();
    assertThat(prefix['b']).isTrue();
    assertThat(prefix['a']).isFalse();
  }

  @Test
  void alternatePrefixCaseInsensitiveAcceleration() {
    Pattern p = Pattern.compile("(?i)(?:cat|dog|bird)s?");
    boolean[] prefix = p.charClassPrefixAscii();
    assertThat(prefix).isNotNull();
    assertThat(prefix['c']).isTrue();
    assertThat(prefix['C']).isTrue();
    assertThat(prefix['d']).isTrue();
    assertThat(prefix['D']).isTrue();
    assertThat(prefix['b']).isTrue();
    assertThat(prefix['B']).isTrue();
    assertThat(prefix['a']).isFalse();
  }

  @Test
  void deeplyNestedRequiredQuantifierPrefixExtractionIsStackSafe() {
    Pattern p = Pattern.compile(nestedRequiredPlusPattern(1_000, "[ab]"));

    boolean[] prefix = p.charClassPrefixAscii();
    assertThat(prefix).isNotNull();
    assertThat(prefix['a']).isTrue();
    assertThat(prefix['b']).isTrue();
    assertThat(prefix['c']).isFalse();
  }

  @Test
  void deeplyNestedAlternationPrefixExtractionIsStackSafe() {
    Pattern p = Pattern.compile(nestedAlternationPattern(1_000));

    boolean[] prefix = p.charClassPrefixAscii();
    assertThat(prefix).isNotNull();
    assertThat(prefix['a']).isTrue();
    assertThat(prefix['b']).isTrue();
    assertThat(prefix['c']).isFalse();
  }

  @Test
  void caseInsensitiveAsciiLiteralUsesLiteralMatchMetadata() {
    Pattern p = Pattern.compile("(?i)i");

    assertThat(p.literalMatch()).isEqualTo("i");
    assertThat(p.prefix()).isEqualTo("i");
    assertThat(p.prefixFoldCase()).isTrue();
  }

  @Test
  void dotStarAroundWhitespaceRecordsRequiredWhitespaceClass() {
    Pattern p = Pattern.compile(".*\\s+.*");

    assertThat(p.requiredMatchClassRanges()).isNotNull();
  }

  @Test
  void boundaryPrefixedLiteralRecordsRequiredClass() {
    Pattern p = Pattern.compile("\\b{g}z");

    assertThat(p.requiredMatchClassRanges()).isNotNull();
  }

  @Test
  void pureNullablePatternsDoNotRecordRequiredCharacterClasses() {
    Pattern p = Pattern.compile(".*");

    assertThat(p.requiredMatchClassRanges()).isNull();
  }

  @ParameterizedTest(name = "compile(\"{0}\").numGroups() == {1}")
  @CsvSource({
    "'',         0",
    "'.*',        0",
    "'abba',      0",
    "'ab(b)a',    1",
    "'ab(.*)a',   1",
    "'(.*)ab(.*)a',  2",
    "'(.*)(ab)(.*)a', 3",
    "'(.*)((a)b)(.*)a', 4",
    "'(.*)(\\(ab)(.*)a', 3",
    "'(.*)(\\(a\\)b)(.*)a', 3",
  })
  void numGroups(String pattern, int expected) {
    assertThat(Pattern.compile(pattern).numGroups()).isEqualTo(expected);
  }

  private static String nestedRequiredPlusPattern(int depth, String atom) {
    StringBuilder regex = new StringBuilder(depth * 5 + atom.length());
    for (int i = 0; i < depth; i++) {
      regex.append("(?:");
    }
    regex.append(atom);
    for (int i = 0; i < depth; i++) {
      regex.append(")+");
    }
    return regex.toString();
  }

  private static String nestedAlternationPattern(int depth) {
    StringBuilder regex = new StringBuilder(depth * 5 + 1);
    for (int i = 0; i < depth; i++) {
      regex.append("(?:");
    }
    regex.append('a');
    for (int i = 0; i < depth; i++) {
      regex.append("|b)");
    }
    return regex.toString();
  }
}
