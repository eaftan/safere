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
  void testOnePassEligibility() {
    Pattern p1 =
        Pattern.compile(
            "\\s*[\\[\\x{FF3B}]\\s*((?:[0-9]+\\.?){3,4}(?:\\s*[,\\x{3001}]\\s*(?:[0-9]+\\.?){3,4})*)\\s*[\\]\\x{FF3D}]");
    assertThat(p1.onePass()).isNull();
    assertThat(p1.canOnePassPrimary()).isFalse();

    Pattern p2 = Pattern.compile("\\b[Ff]ormer [Cc][Ee][Oo] ([Aa]lice\\b|\\*\\*[Aa]lice\\*\\*)");
    assertThat(p2.onePass()).isNotNull();
    assertThat(p2.canOnePassPrimary()).isTrue();
    assertThat(p2.canOnePassFind()).isFalse();
    assertThat(p2.canOnePassSubmatch()).isTrue();
  }

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

    assertThat(p.charClassPrefix()).isNotNull();
    assertThat(p.charClassPrefixContains('A')).isTrue();
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
    assertThat(p.charClassPrefix()).isNotNull();
    assertThat(p.charClassPrefixContains('c')).isTrue();
    assertThat(p.charClassPrefixContains('d')).isTrue();
    assertThat(p.charClassPrefixContains('b')).isTrue();
    assertThat(p.charClassPrefixContains('a')).isFalse();
  }

  @Test
  void alternatePrefixCaseInsensitiveAcceleration() {
    Pattern p = Pattern.compile("(?i)(?:cat|dog|bird)s?");
    assertThat(p.charClassPrefix()).isNotNull();
    assertThat(p.charClassPrefixContains('c')).isTrue();
    assertThat(p.charClassPrefixContains('C')).isTrue();
    assertThat(p.charClassPrefixContains('d')).isTrue();
    assertThat(p.charClassPrefixContains('D')).isTrue();
    assertThat(p.charClassPrefixContains('b')).isTrue();
    assertThat(p.charClassPrefixContains('B')).isTrue();
    assertThat(p.charClassPrefixContains('a')).isFalse();
  }

  @Test
  void deeplyNestedRequiredQuantifierPrefixExtractionIsStackSafe() {
    Pattern p = Pattern.compile(nestedRequiredPlusPattern(1_000, "[ab]"));

    assertThat(p.charClassPrefix()).isNotNull();
    assertThat(p.charClassPrefixContains('a')).isTrue();
    assertThat(p.charClassPrefixContains('b')).isTrue();
    assertThat(p.charClassPrefixContains('c')).isFalse();
  }

  @Test
  void deeplyNestedAlternationPrefixExtractionIsStackSafe() {
    Pattern p = Pattern.compile(nestedAlternationPattern(1_000));

    assertThat(p.charClassPrefix()).isNotNull();
    assertThat(p.charClassPrefixContains('a')).isTrue();
    assertThat(p.charClassPrefixContains('b')).isTrue();
    assertThat(p.charClassPrefixContains('c')).isFalse();
  }

  @Test
  void deeplyNestedConcatPrefixExtractionIsStackSafe() {
    Pattern p = Pattern.compile(nestedPrefixConcatPattern(1_000));

    assertThat(p.prefix()).isEqualTo("foo");
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

  private static String nestedPrefixConcatPattern(int depth) {
    StringBuilder regex = new StringBuilder(depth * 3 + 3);
    for (int i = 0; i < depth; i++) {
      regex.append('(');
    }
    regex.append("foo");
    for (int i = 0; i < depth; i++) {
      regex.append(")x");
    }
    return regex.toString();
  }

  @Test
  void prefixExtractionFromNestedCaptureInConcat() {
    Pattern p1 = Pattern.compile("(foo bar)baz");
    assertThat(p1.prefix()).isEqualTo("foo bar");

    Pattern p2 = Pattern.compile("(<template name>.*)");
    assertThat(p2.prefix()).isEqualTo("<template name>");

    // reproducing the actual templateTagMatch pattern structure
    Pattern p3 = Pattern.compile("(<template name>.*)([^>])");
    assertThat(p3.prefix()).isEqualTo("<template name>");
  }
}
