// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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

    CharClassScanInfo prefix = p.charClassPrefix();
    assertThat(prefix).isNotNull();
    assertThat(prefix.contains('A')).isTrue();
    assertThat(p.matchDescriptor().charClassMatch()).isNotNull();
  }

  @Test
  void textStartAnchorsPreservePrefixAccelerators() {
    assertThat(Pattern.compile("^https://.*").anchoredPrefix()).isEqualTo("https://");
    assertThat(Pattern.compile("\\Ahttps://.*").anchoredPrefix()).isEqualTo("https://");
    assertThat(Pattern.compile("\\A\\bhttps://.*").anchoredPrefix()).isEqualTo("https://");
    assertThat(Pattern.compile("(\\A)https://.*").anchoredPrefix()).isEqualTo("https://");

    CharClassScanInfo prefix = Pattern.compile("^[0-9]+").anchoredCharClassPrefix();
    assertThat(prefix).isNotNull();
    assertThat(prefix.contains('0')).isTrue();
    assertThat(prefix.contains('9')).isTrue();
    assertThat(prefix.contains('a')).isFalse();

    for (String regex : new String[] {"\\A\\b[0-9]+", "(\\A)[0-9]+"}) {
      CharClassScanInfo separatedPrefix = Pattern.compile(regex).anchoredCharClassPrefix();
      assertThat(separatedPrefix).as(regex).isNotNull();
      assertThat(separatedPrefix.contains('0')).as(regex).isTrue();
      assertThat(separatedPrefix.contains('9')).as(regex).isTrue();
      assertThat(separatedPrefix.contains('a')).as(regex).isFalse();
    }
  }

  @Test
  void asciiPrefixScanInfoHandlesMissingAndEmptyClasses() {
    assertThat(CharClassScanInfo.fromAsciiBitmap(null)).isNull();
    assertThat(CharClassScanInfo.fromAsciiBitmap(AsciiBitmap.EMPTY)).isNull();
  }

  @Test
  void asciiPrefixScanInfoExactlyRepresentsCommonClassShapes() {
    assertAsciiScanInfo(new int[] {'x'}, new int[] {'x', 'x'});
    assertAsciiScanInfo(new int[] {'x', 'y'}, new int[] {'x', 'y'});
    assertAsciiScanInfo(new int[] {'x', 'z'}, new int[] {'x', 'x', 'z', 'z'});
    assertAsciiScanInfo(asciiRange('0', '9'), new int[] {'0', '9'});
    assertAsciiScanInfo(new int[] {'a', 'c', 'e'}, new int[] {'a', 'a', 'c', 'c', 'e', 'e'});
  }

  @Test
  void asciiPrefixScanInfoPreservesMembersAcrossBitmapBoundary() {
    CharClassScanInfo info = assertAsciiScanInfo(new int[] {62, 63, 64, 65}, new int[] {62, 65});

    assertThat(info.bitmap0()).isEqualTo((1L << 62) | (1L << 63));
    assertThat(info.bitmap1()).isEqualTo((1L << 0) | (1L << 1));
  }

  @Test
  void transparentGroupsPreserveKeywordAlternationAccelerator() {
    Pattern p = Pattern.compile("(?i)\\b(?:error|warning)\\b");

    assertThat(p.keywordAlternation()).isNotNull();
  }

  @Test
  void greedyDotAllWrappersPreserveKeywordAlternationAccelerator() {
    Pattern p = Pattern.compile("(?is).*\\b(you|your)\\b.*");

    assertThat(p.keywordAlternation()).isNotNull();
    assertThat(p.keywordAlternation().greedyWholeInput).isTrue();
  }

  @Test
  void replacementGroupConsumptionRecordsCaptureDemand() {
    Pattern pattern = Pattern.compile("(qu|[b-df-hj-np-tv-z]*)([a-z]+)");

    pattern.matcher("the quick brown fox").replaceAll("$2$1ay");

    assertThat(pattern.innerCapturesObserved()).isTrue();
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
    CharClassScanInfo prefix = p.charClassPrefix();
    assertThat(prefix).isNotNull();
    assertThat(prefix.contains('c')).isTrue();
    assertThat(prefix.contains('d')).isTrue();
    assertThat(prefix.contains('b')).isTrue();
    assertThat(prefix.contains('a')).isFalse();
  }

  @Test
  void alternatePrefixCaseInsensitiveAcceleration() {
    Pattern p = Pattern.compile("(?i)(?:cat|dog|bird)s?");
    CharClassScanInfo prefix = p.charClassPrefix();
    assertThat(prefix).isNotNull();
    assertThat(prefix.contains('c')).isTrue();
    assertThat(prefix.contains('C')).isTrue();
    assertThat(prefix.contains('d')).isTrue();
    assertThat(prefix.contains('D')).isTrue();
    assertThat(prefix.contains('b')).isTrue();
    assertThat(prefix.contains('B')).isTrue();
    assertThat(prefix.contains('a')).isFalse();
  }

  @Test
  void unicodeCharacterClassPrefixAcceleration() {
    Pattern p = Pattern.compile("[\\p{IsAlphabetic}]+");
    CharClassScanInfo prefix = p.charClassPrefix();
    assertThat(prefix).isNotNull();
    assertThat(prefix.isAscii()).isFalse();
    assertThat(prefix.contains('a')).isTrue();
    assertThat(prefix.contains('Z')).isTrue();
    assertThat(prefix.contains('\u00e9')).isTrue(); // é
    assertThat(prefix.contains('\u03b1')).isTrue(); // α
    assertThat(prefix.contains('1')).isFalse();
    assertThat(prefix.contains(' ')).isFalse();
  }

  @Test
  void deeplyNestedRequiredQuantifierPrefixExtractionIsStackSafe() {
    Pattern p = Pattern.compile(nestedRequiredPlusPattern(1_000, "[ab]"));

    CharClassScanInfo prefix = p.charClassPrefix();
    assertThat(prefix).isNotNull();
    assertThat(prefix.contains('a')).isTrue();
    assertThat(prefix.contains('b')).isTrue();
    assertThat(prefix.contains('c')).isFalse();
  }

  @Test
  void deeplyNestedAlternationPrefixExtractionIsStackSafe() {
    Pattern p = Pattern.compile(nestedAlternationPattern(1_000));

    CharClassScanInfo prefix = p.charClassPrefix();
    assertThat(prefix).isNotNull();
    assertThat(prefix.contains('a')).isTrue();
    assertThat(prefix.contains('b')).isTrue();
    assertThat(prefix.contains('c')).isFalse();
  }

  @Test
  void deeplyNestedConcatPrefixExtractionIsStackSafe() {
    Pattern p = Pattern.compile(nestedPrefixConcatPattern(1_000));

    assertThat(p.prefix()).isEqualTo("foo");
  }

  @Test
  void deeplyNestedFixedOffsetWidthExtractionIsStackSafe() {
    Pattern p = Pattern.compile(nestedFixedOffsetPattern(2_000));

    assertThat(p.startPlan()).isInstanceOf(MultiAnchorDescriptor.StartPlan.FixedOffset.class);
  }

  @Test
  void largeCapturedLiteralConcatenationRecordsMaximalSuffix() {
    StringBuilder regex = new StringBuilder("[ab]");
    for (int i = 0; i < 2_000; i++) {
      regex.append("(x)");
    }

    assertThat(Pattern.compile(regex.toString()).startPlan())
        .isInstanceOfSatisfying(
            MultiAnchorDescriptor.StartPlan.FixedOffset.class,
            plan -> {
              assertThat(plan.fol().literal()).hasSize(2_000);
              assertThat(plan.fol().minOffset()).isEqualTo(1);
            });
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

    assertThat(requiredCharClass(p)).isNotNull();
  }

  @ParameterizedTest
  @CsvSource({
    "'\\d{3}/\\d{3}/\\d{4}', /, 0",
    "'[A-Z]{2}:[0-9]{4}',    :, A",
    "'\\w+#[a-f0-9]{8}',     #, a"
  })
  void requiredCharacterClassPrefersTheMostSelectiveMandatoryAtom(
      String regex, char expectedMember, char expectedNonMember) {
    Pattern p = Pattern.compile(regex);

    assertThat(requiredClassContains(p, expectedMember)).isTrue();
    assertThat(requiredClassContains(p, expectedNonMember)).isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "'\\d{3}/\\d{3}/\\d{4}', /, 3",
    "'[A-Z][0-9]::[a-z]+',   ::, 2",
    "'[ab][cd]-xyz',          -xyz, 2"
  })
  void fixedOffsetAsciiLiteralsAreRecorded(String regex, String literal, int offset) {
    assertThat(Pattern.compile(regex).startPlan())
        .isInstanceOfSatisfying(
            MultiAnchorDescriptor.StartPlan.FixedOffset.class,
            plan -> {
              assertThat(plan.fol().literal()).isEqualTo(literal);
              assertThat(plan.fol().offset()).isEqualTo(offset);
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"\\d+/x", "[ab](?i:x)", "literal-prefix"})
  void variableWidthUnicodeAndOrdinaryPrefixesDoNotRecordFixedOffsetLiterals(String regex) {
    assertThat(Pattern.compile(regex).startPlan())
        .isNotInstanceOf(MultiAnchorDescriptor.StartPlan.FixedOffset.class);
  }

  @Test
  void unicodeClassOffsetsAreRecordedAsNonDiscreteCodePointRanges() {
    assertThat(Pattern.compile("[αβ]/x").startPlan())
        .isInstanceOfSatisfying(
            MultiAnchorDescriptor.StartPlan.FixedOffset.class,
            plan -> {
              assertThat(plan.fol().minOffset()).isEqualTo(1);
              assertThat(plan.fol().maxOffset()).isEqualTo(1);
              assertThat(plan.fol().discreteOffsets()).isNull();
            });
  }

  @Test
  void discreteMultiOffsetLiteralsAreRecorded() {
    assertThat(Pattern.compile("(^|[a-z])(#!customTag)").startPlan())
        .isInstanceOfSatisfying(
            MultiAnchorDescriptor.StartPlan.FixedOffset.class,
            plan -> {
              assertThat(plan.fol().literal()).isEqualTo("#!customTag");
              assertThat(plan.fol().minOffset()).isZero();
              assertThat(plan.fol().maxOffset()).isEqualTo(1);
              assertThat(plan.fol().discreteOffsets()).containsExactly(0, 1);
            });
  }

  @Test
  void boundedRangeOffsetLiteralsAreRecorded() {
    assertThat(Pattern.compile("\\s{0,8}renderElement\\(").startPlan())
        .isInstanceOfSatisfying(
            MultiAnchorDescriptor.StartPlan.FixedOffset.class,
            plan -> {
              assertThat(plan.fol().literal()).isEqualTo("renderElement(");
              assertThat(plan.fol().minOffset()).isEqualTo(0);
              assertThat(plan.fol().maxOffset()).isEqualTo(8);
            });
  }

  @ParameterizedTest
  @CsvSource({
    "'.*(x|y).*',             xy, az",
    "'.*(?:m|n).*',           mn, xz",
    "'.*([0-2]|[7-9]).*',     08, 56",
    "'.*((?:α|β))+.*',        αβ, γδ",
    "'.*(?:ab|cd).*',         ac, bd"
  })
  void mandatoryAlternativesRecordTheirRequiredCharacterUnion(
      String regex, String members, String nonMembers) {
    Pattern p = Pattern.compile(regex);

    assertThat(requiredCharClass(p)).isNotNull();
    members
        .codePoints()
        .forEach(codePoint -> assertThat(requiredClassContains(p, codePoint)).isTrue());
    nonMembers
        .codePoints()
        .forEach(codePoint -> assertThat(requiredClassContains(p, codePoint)).isFalse());
  }

  @ParameterizedTest
  @ValueSource(strings = {".*(x|).*", ".*(?:x|y)?.*", ".*(?:x|y){0,3}.*", ".*(?:x|y|.*).*"})
  void nullableAlternativesDoNotRecordRequiredCharacterClasses(String regex) {
    assertThat(requiredCharClass(Pattern.compile(regex))).isNull();
  }

  @ParameterizedTest
  @CsvSource({
    "'.*coolfunctionname.*',       coolfunctionname",
    "'.*(needle).*',               needle",
    "'(?:ab)+.*',                  ab",
    "'.*short.*much-longer.*',     much-longer",
    "'.*前置.*かなり長い必要語.*', かなり長い必要語"
  })
  void mandatoryCaseSensitiveLiteralsAreRecorded(String regex, String expected) {
    assertThat(requiredLiteral(Pattern.compile(regex))).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        ".*(?:needle)?.*",
        "(?i).*needle.*",
        ".*(?:needle|thread).*",
        ".*(?:needle){0,2}.*",
        "needle.*"
      })
  void optionalCaseInsensitiveAndAlreadyPrefixedLiteralsAreNotRecorded(String regex) {
    assertThat(requiredLiteral(Pattern.compile(regex))).isNull();
  }

  @ParameterizedTest
  @CsvSource({
    "'.*\\.json$',                         .json,                        false",
    "'(?i).*\\.json$',                     .json,                        true",
    "'.*report_2026\\.log$',               report_2026.log,              false",
    "'.*(foo)(bar)$',                      foobar,                       false",
    "'[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$',  ABCDEFGHIJKLMNOPQRSTUVWXYZ,   false",
    "'.*test\\z',                          test,                         false",
    "'.*(?i:test)\\z',                     test,                         true"
  })
  void endAnchoredLiteralSuffixIsRecorded(String regex, String expected, boolean foldCase) {
    Pattern.SuffixInfo info = endAnchoredSuffix(Pattern.compile(regex));
    assertThat(info.suffix()).isEqualTo(expected);
    assertThat(info.foldCase()).isEqualTo(foldCase);
  }

  @ParameterizedTest
  @ValueSource(strings = {".*", ".*json", "(?m).*\\.json$"})
  void unanchoredOrMultilineDollarDoNotRecordEndAnchoredSuffix(String regex) {
    assertThat(endAnchoredSuffix(Pattern.compile(regex))).isNull();
  }

  @Test
  void endAnchoredSuffixRejectsUtf8Input() {
    Pattern p = Pattern.compile(".*\\.json$");
    assertThat(p.find(Utf8Input.trusted("config.json".getBytes(UTF_8)))).isTrue();
    assertThat(p.find(Utf8Input.trusted("config.yaml".getBytes(UTF_8)))).isFalse();
  }

  @Test
  void disjointRequiredLiteralsAreRecordedForAlternations() {
    Pattern p = Pattern.compile(".*(?:apple|banana|cherry).*");
    assertThat(findRejectPlan(p, MultiAnchorDescriptor.RejectPlan.DisjointLiterals.class))
        .isNotNull()
        .satisfies(
            plan -> assertThat(plan.literals()).containsExactly("apple", "banana", "cherry"));
    assertThat(requiredLiteral(p)).isNull();

    Pattern p2 = Pattern.compile("(foo.*|bar.*|baz.*)");
    assertThat(findRejectPlan(p2, MultiAnchorDescriptor.RejectPlan.DisjointLiterals.class))
        .isNotNull()
        .satisfies(plan -> assertThat(plan.literals()).containsExactly("foo", "bar", "baz"));

    Pattern p3 = Pattern.compile("(?:\\bfirstToken\\b|\\bsecondToken\\b)");
    assertThat(findRejectPlan(p3, MultiAnchorDescriptor.RejectPlan.DisjointLiterals.class))
        .isNotNull()
        .satisfies(
            plan -> assertThat(plan.literals()).containsExactly("firstToken", "secondToken"));
  }

  @Test
  void disjointRequiredLiteralsSubsumptionMinimization() {
    // pineapple contains apple, so pineapple is pruned and apple + banana are required.
    Pattern p1 = Pattern.compile(".*(?:apple|pineapple|banana).*");
    assertThat(findRejectPlan(p1, MultiAnchorDescriptor.RejectPlan.DisjointLiterals.class))
        .isNotNull()
        .satisfies(plan -> assertThat(plan.literals()).containsExactly("apple", "banana"));

    // prefix_foo contains foo, bar_baz contains baz
    Pattern p2 = Pattern.compile(".*(?:prefix_foo|foo|bar_baz|baz).*");
    assertThat(findRejectPlan(p2, MultiAnchorDescriptor.RejectPlan.DisjointLiterals.class))
        .isNotNull()
        .satisfies(plan -> assertThat(plan.literals()).containsExactly("foo", "baz"));

    // https contains http, ftp is distinct
    Pattern p3 = Pattern.compile(".*(?:http|https|ftp).*");
    assertThat(findRejectPlan(p3, MultiAnchorDescriptor.RejectPlan.DisjointLiterals.class))
        .isNotNull()
        .satisfies(plan -> assertThat(plan.literals()).containsExactly("http", "ftp"));

    // A lone surrogate is not a code-point substring of a supplementary character.
    Pattern p4 = Pattern.compile("(?:a\uD83D|za\uD83D\uDE00|banana)");
    assertThat(findRejectPlan(p4, MultiAnchorDescriptor.RejectPlan.DisjointLiterals.class))
        .isNotNull()
        .satisfies(
            plan ->
                assertThat(plan.literals()).containsExactly("a\uD83D", "za\uD83D\uDE00", "banana"));
  }

  @Test
  void tooManyRawDisjointLiteralsAreRejectedBeforeMinimization() {
    StringBuilder regex = new StringBuilder("(?:banana");
    for (int i = 0; i < 16; i++) {
      regex.append('|').append("x".repeat(i)).append("apple");
    }
    regex.append(')');

    assertThat(
            findRejectPlan(
                Pattern.compile(regex.toString()),
                MultiAnchorDescriptor.RejectPlan.DisjointLiterals.class))
        .isNull();
  }

  @Test
  void disjointRequiredLiteralCountIsBoundedByMeasuredCrossover() {
    assertThat(
            findRejectPlan(
                Pattern.compile("(?:apple|banana|cherry|orange)\\d"),
                MultiAnchorDescriptor.RejectPlan.DisjointLiterals.class))
        .isNotNull()
        .satisfies(
            plan ->
                assertThat(plan.literals()).containsExactly("apple", "banana", "cherry", "orange"));
    assertThat(
            findRejectPlan(
                Pattern.compile("(?:apple|banana|cherry|orange|papaya)\\d"),
                MultiAnchorDescriptor.RejectPlan.DisjointLiterals.class))
        .isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        ".*(?:apple|banana)?.*",
        ".*(?:apple|.*).*",
        ".*(?:apple|a).*",
        "(?i).*(?:apple|banana).*"
      })
  void invalidOrNullableAlternationsDoNotRecordDisjointLiterals(String regex) {
    Pattern p = Pattern.compile(regex);
    if (p.prefix() == null && requiredLiteral(p) == null) {
      assertThat(findRejectPlan(p, MultiAnchorDescriptor.RejectPlan.DisjointLiterals.class))
          .isNull();
    }
  }

  @Test
  void boundaryPrefixedLiteralRecordsRequiredClass() {
    Pattern p = Pattern.compile("\\b{g}z");

    assertThat(requiredCharClass(p)).isNotNull();
  }

  @Test
  void pureNullablePatternsDoNotRecordRequiredCharacterClasses() {
    Pattern p = Pattern.compile(".*");

    assertThat(requiredCharClass(p)).isNull();
  }

  @SuppressWarnings("unchecked")
  private static <T extends MultiAnchorDescriptor.RejectPlan> T findRejectPlan(
      Pattern p, Class<T> clazz) {
    if (clazz.isInstance(p.rejectPlan())) {
      return (T) p.rejectPlan();
    }
    if (p.rejectPlan() instanceof MultiAnchorDescriptor.RejectPlan.Composite comp) {
      for (MultiAnchorDescriptor.RejectPlan plan : comp.plans()) {
        if (clazz.isInstance(plan)) {
          return (T) plan;
        }
      }
    }
    return null;
  }

  private static CharClassScanInfo requiredCharClass(Pattern p) {
    MultiAnchorDescriptor.RejectPlan.RequiredCharClass cc =
        findRejectPlan(p, MultiAnchorDescriptor.RejectPlan.RequiredCharClass.class);
    return cc != null ? cc.scanInfo() : null;
  }

  private static Pattern.SuffixInfo endAnchoredSuffix(Pattern p) {
    MultiAnchorDescriptor.RejectPlan.EndAnchoredSuffix s =
        findRejectPlan(p, MultiAnchorDescriptor.RejectPlan.EndAnchoredSuffix.class);
    return s != null ? s.suffix() : null;
  }

  private static String requiredLiteral(Pattern p) {
    MultiAnchorDescriptor.RejectPlan.RequiredLiteral lit =
        findRejectPlan(p, MultiAnchorDescriptor.RejectPlan.RequiredLiteral.class);
    return lit != null ? lit.literal() : null;
  }

  private static boolean requiredClassContains(Pattern pattern, int codePoint) {
    CharClassScanInfo info = requiredCharClass(pattern);
    return info != null
        && InputScanner.classContains(info.ranges(), info.bitmap0(), info.bitmap1(), codePoint);
  }

  private static CharClassScanInfo assertAsciiScanInfo(int[] members, int[] expectedRanges) {
    AsciiBitmap.Builder builder = new AsciiBitmap.Builder();
    for (int member : members) {
      builder.add(member);
    }
    AsciiBitmap asciiClass = builder.build();

    CharClassScanInfo info = CharClassScanInfo.fromAsciiBitmap(asciiClass);

    assertThat(info).isNotNull();
    assertThat(info.ranges()).containsExactly(expectedRanges);
    for (int codePoint = 0; codePoint < 128; codePoint++) {
      assertThat(
              InputScanner.classContains(info.ranges(), info.bitmap0(), info.bitmap1(), codePoint))
          .as("ASCII member %s", codePoint)
          .isEqualTo(asciiClass.containsAscii(codePoint));
    }
    return info;
  }

  private static int[] asciiRange(int low, int high) {
    int[] members = new int[high - low + 1];
    for (int index = 0; index < members.length; index++) {
      members[index] = low + index;
    }
    return members;
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

  private static String nestedFixedOffsetPattern(int depth) {
    StringBuilder regex = new StringBuilder(depth * 3 + 6);
    for (int i = 0; i < depth; i++) {
      regex.append('(');
    }
    regex.append("[ab]");
    for (int i = 0; i < depth; i++) {
      regex.append(")x");
    }
    regex.append("ZZ");
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

  @Test
  void fixedOffsetLiteralPrefersRareTokenOverLongCommonToken() {
    // "____" has length 4 with common underscores.
    // "zq" has length 2 with rare letters 'z' and 'q'.
    Pattern pattern = Pattern.compile("[0-9]{2}____[a-z]zq[a-z]");
    assertThat(pattern.startPlan())
        .isInstanceOfSatisfying(
            MultiAnchorDescriptor.StartPlan.FixedOffset.class,
            plan -> assertThat(plan.fol().literal()).isEqualTo("zq"));
  }

  @Test
  void requiredLiteralPrefersRareToken() {
    Pattern pattern = Pattern.compile(".*(____).*?(404_ERROR).*");
    assertThat(requiredLiteral(pattern)).isEqualTo("404_ERROR");
  }

  @Test
  void requiredLiteralRetainsSelectivityFromLengthWhenCharactersAreCommon() {
    String spaces = " ".repeat(32);
    Pattern pattern = Pattern.compile(".*(" + spaces + ").*?(ee).*");

    assertThat(requiredLiteral(pattern)).isEqualTo(spaces);
  }
}
