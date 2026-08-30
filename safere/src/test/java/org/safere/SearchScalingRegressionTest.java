// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("WorkCounter is an internal SafeRE API")
@Tag("work-counter")
class SearchScalingRegressionTest {

  @Test
  void multiAnchorCompilationDoesNotRepeatAstAnalysis() {
    Regexp regexp = Parser.parse("foo.*bar.*baz", Pattern.toParseFlags(0));

    long work = WorkCounter.countForTesting(() -> MultiAnchorCompiler.compile(regexp, 0));

    assertThat(work)
        .as("Compilation work includes descriptor assembly but not a second full AST analysis")
        .isLessThan(countAstNodes(regexp) * 4L);
  }

  @Test
  void nestedRequiredLiteralAnalysisDoesNotRepeatSelectivityScoring() {
    Regexp smaller = nestedRequiredLiteral(8_000);
    Regexp larger = nestedRequiredLiteral(16_000);

    long smallerWork = WorkCounter.countForTesting(() -> MultiAnchorCompiler.analyze(smaller));
    long largerWork = WorkCounter.countForTesting(() -> MultiAnchorCompiler.analyze(larger));

    assertThat(smallerWork).as("Required-literal scoring must be observed").isPositive();
    assertThat(largerWork)
        .as("Required-literal selectivity scoring should scale linearly")
        .isLessThanOrEqualTo(smallerWork * 3);
  }

  @Test
  void shiftDfaTransitionsAreCountedForStringInput() {
    Pattern pattern = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    String input = "a".repeat(10_000);

    long work =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input).matches()).isTrue());

    assertThat(work).isGreaterThanOrEqualTo(input.length());
  }

  @Test
  void shiftDfaTransitionsAreCountedForUtf8Input() {
    Pattern pattern = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    byte[] input = "a".repeat(10_000).getBytes(UTF_8);

    long work =
        WorkCounter.countForTesting(
            () -> assertThat(pattern.matcher(Utf8Input.trusted(input)).matches()).isTrue());

    assertThat(work).isGreaterThanOrEqualTo(input.length);
  }

  @Test
  void reverseDfaSuffixFailureIsConstantWorkForStringInput() {
    Pattern pattern = Pattern.compile("[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$");
    assertReverseDfaSuffixFailureIsConstantWork(size -> pattern.matcher("a".repeat(size)).find());
  }

  @Test
  void reverseDfaSuffixFailureIsConstantWorkForUtf8Input() {
    Pattern pattern = Pattern.compile("[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$");
    assertReverseDfaSuffixFailureIsConstantWork(
        size -> pattern.matcher(Utf8Input.trusted("a".repeat(size).getBytes(UTF_8))).find());
  }

  @Test
  void greedyKeywordAlternationSuccessNearEndIsConstantWorkForUtf8Input() {
    Pattern pattern = Pattern.compile("(?is).*\\b(you|your)\\b.*");
    assertConstantWork(
        size ->
            pattern
                .matcher(Utf8Input.trusted(("a".repeat(size) + " YOUR tail").getBytes(UTF_8)))
                .find(),
        "UTF-8 keyword search");
  }

  @Test
  void disjointRequiredLiteralPrefilterIsLinearAcrossStringFindIteration() {
    Pattern pattern = Pattern.compile("(?:banana\\d|apple\\d)");
    assertRepeatedFindWorkIsLinear(size -> pattern.matcher("apple0 ".repeat(size))::find, "String");
  }

  @Test
  void disjointRequiredLiteralPrefilterIsLinearAcrossUtf8FindIteration() {
    Pattern pattern = Pattern.compile("(?:banana\\d|apple\\d)");
    assertRepeatedFindWorkIsLinear(
        size -> pattern.matcher(Utf8Input.trusted("apple0 ".repeat(size).getBytes(UTF_8)))::find,
        "UTF-8");
  }

  @Test
  void replaceAllWithoutCaptureReferencesSkipsCaptureResolutionWork() {
    Pattern pattern = Pattern.compile("x(a+)y([0-9]+)z");
    String input = "xay1z xay2z";

    assertThat(pattern.innerCapturesObserved()).isFalse();
    String replacedLiteral = pattern.matcher(input).replaceAll("REPLACED");
    assertThat(replacedLiteral).isEqualTo("REPLACED REPLACED");
    assertThat(pattern.innerCapturesObserved())
        .as("Literal replacement must not mark inner captures as observed")
        .isFalse();

    String replacedWithCaptures = pattern.matcher(input).replaceAll("$1-$2");
    assertThat(replacedWithCaptures).isEqualTo("a-1 a-2");
    assertThat(pattern.innerCapturesObserved())
        .as("Replacement with capture references must mark inner captures as observed")
        .isTrue();
  }

  @Test
  void literalReplaceWithGroupZeroReferenceUsesFastPathWithLinearWork() {
    Pattern pattern = Pattern.compile("(abc)");
    String input = "abc ".repeat(1_000);

    long work =
        WorkCounter.countForTesting(
            () -> {
              String replaced = pattern.matcher(input).replaceAll("[$0]");
              assertThat(replaced).isNotNull();
            });

    assertThat(work)
        .as("Literal replacement with group zero reference must scan the input only once")
        .isLessThanOrEqualTo(input.length());
  }

  @Test
  void literalReplaceFirstReusesLatePreflightMatch() {
    Pattern pattern = Pattern.compile("(needle)");
    String input = "x".repeat(10_000) + "needle";

    long work =
        WorkCounter.countForTesting(
            () -> assertThat(pattern.matcher(input).replaceFirst("[$0]")).endsWith("[needle]"));

    assertThat(work)
        .as("Literal replaceFirst must not rescan the prefix after finding the first match")
        .isLessThanOrEqualTo(input.length());
  }

  @Test
  void literalSplitWithParenthesesUsesFastPathWithoutDfaWork() {
    Pattern pattern = Pattern.compile("(delim)");
    String input = "item delim ".repeat(1_000);

    long work =
        WorkCounter.countForTesting(
            () -> {
              String[] parts = pattern.split(input);
              assertThat(parts).hasSize(1_001);
            });

    assertThat(work)
        .as("Literal split on parenthesized pattern should execute on fast path without DFA work")
        .isEqualTo(0);
  }

  @Test
  void caseInsensitivePrefixRepeatedFindIsLinearAcrossString() {
    Pattern pattern = Pattern.compile("(?i)keyword_to_find");
    assertRepeatedFindWorkIsLinear(
        size -> pattern.matcher("KEYWORD_TO_FIND ".repeat(size))::find, "String");
  }

  @Test
  void caseInsensitiveSingleCharacterRepeatedFindIsLinearAcrossString() {
    Pattern pattern = Pattern.compile("(?i)z");
    assertRepeatedFindWorkIsLinear(size -> pattern.matcher("z".repeat(size))::find, "String");
  }

  @Test
  void caseInsensitiveSparseFalseCandidatesAreLinearAcrossString() {
    Pattern pattern = Pattern.compile("(?i)zq");
    IntFunction<String> input = size -> ("zX" + "a".repeat(32)).repeat(size) + "Zq";

    long smallerWork = countAllMatches(pattern.matcher(input.apply(100))::find, 1);
    long largerWork = countAllMatches(pattern.matcher(input.apply(400))::find, 1);

    assertThat(largerWork)
        .as("String sparse false-candidate work should scale linearly")
        .isLessThanOrEqualTo(smallerWork * 6);
  }

  @Test
  void caseInsensitivePrefixRepeatedFindIsLinearAcrossUtf8() {
    Pattern pattern = Pattern.compile("(?i)keyword_to_find");
    assertRepeatedFindWorkIsLinear(
        size ->
            pattern.matcher(Utf8Input.trusted("KEYWORD_TO_FIND ".repeat(size).getBytes(UTF_8)))
                ::find,
        "UTF-8");
  }

  @Test
  void caseInsensitiveDensePrefixFailureIsLinearForStringInput() {
    Pattern pattern =
        Pattern.compile("(?i)aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab");
    String input = "a".repeat(10_000);
    long work =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input).find()).isFalse());
    assertThat(work)
        .as("Dense false candidate prefix verification on String must remain linearly bounded")
        .isLessThan(input.length() * 3L);
  }

  @Test
  void caseInsensitiveDensePrefixFailureIsLinearForUtf8Input() {
    Pattern pattern =
        Pattern.compile("(?i)aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab");
    String input = "a".repeat(10_000);
    long work =
        WorkCounter.countForTesting(
            () ->
                assertThat(pattern.matcher(Utf8Input.trusted(input.getBytes(UTF_8))).find())
                    .isFalse());
    assertThat(work)
        .as("Dense false candidate prefix verification on UTF-8 must remain linearly bounded")
        .isLessThan(input.length() * 3L);
  }

  @Test
  void disjointRequiredLiteralOptimizationDoesNotAddRedundantUtf8Scans() {
    String regex = "(?:banana\\d|apple\\d)";
    Pattern defaultPattern = Pattern.compile(regex);
    Pattern withoutLiteralFastPaths =
        Pattern.compile(regex, 0, EnginePathOptions.builder().literalFastPaths(false).build());
    Utf8Input input = Utf8Input.trusted("x".repeat(32_768).getBytes(UTF_8));

    long defaultWork = countAllMatches(defaultPattern.matcher(input)::find, 0);
    long fallbackWork = countAllMatches(withoutLiteralFastPaths.matcher(input)::find, 0);

    assertThat(defaultWork)
        .as("UTF-8 literal optimization should not add full-input scans before the DFA")
        .isLessThanOrEqualTo(fallbackWork);
  }

  @Test
  void disjointRequiredLiteralOptimizationDoesNotScanStartAnchoredInput() {
    Pattern pattern = Pattern.compile("^(?:banana\\d|apple\\d)");

    long work =
        WorkCounter.countForTesting(
            () -> assertThat(pattern.matcher("x".repeat(32_768)).find()).isFalse());

    assertThat(work)
        .as("start-anchored rejection should inspect only the viable start position")
        .isLessThan(100);
  }

  @Test
  void startAnchoredLiteralFindRejectionIsConstantWorkForStringInput() {
    Pattern pattern = Pattern.compile("^target");
    assertConstantRejectionWork(
        size -> pattern.matcher("x".repeat(size) + "target").find(), "String literal");
  }

  @Test
  void startAnchoredLiteralFindRejectionIsConstantWorkForUtf8Input() {
    Pattern pattern = Pattern.compile("^target");
    assertConstantRejectionWork(
        size ->
            pattern
                .matcher(Utf8Input.trusted(("x".repeat(size) + "target").getBytes(UTF_8)))
                .find(),
        "UTF-8 literal");
  }

  @Test
  void startAnchoredCharClassFindRejectionIsConstantWorkForStringInput() {
    Pattern pattern = Pattern.compile("^[a-z]");
    assertConstantRejectionWork(
        size -> pattern.matcher("9".repeat(size) + "a").find(), "String char-class");
  }

  @Test
  void startAnchoredCharClassFindRejectionIsConstantWorkForUtf8Input() {
    Pattern pattern = Pattern.compile("^[a-z]");
    assertConstantRejectionWork(
        size -> pattern.matcher(Utf8Input.trusted(("9".repeat(size) + "a").getBytes(UTF_8))).find(),
        "UTF-8 char-class");
  }

  @Test
  void startAnchoredKeywordAlternationFindRejectionIsConstantWorkForStringInput() {
    Pattern pattern = Pattern.compile("(?i)^(?:apple|banana)");
    assertConstantRejectionWork(
        size -> pattern.matcher("x".repeat(size) + "apple").find(), "String keyword");
  }

  @Test
  void startAnchoredKeywordAlternationFindRejectionIsConstantWorkForUtf8Input() {
    Pattern pattern = Pattern.compile("(?i)^(?:apple|banana)");
    assertConstantRejectionWork(
        size ->
            pattern.matcher(Utf8Input.trusted(("x".repeat(size) + "apple").getBytes(UTF_8))).find(),
        "UTF-8 keyword");
  }

  @Test
  void startAnchoredFindFromNonZeroIsConstantWork() {
    Pattern pattern = Pattern.compile("^abc");
    assertConstantRejectionWork(
        size -> pattern.matcher("abc" + "x".repeat(size)).find(1), "find(1) on ^abc");
  }

  @Test
  void startAnchoredRepeatedFindTerminatesInConstantWork() {
    Pattern pattern = Pattern.compile("^abc");
    long work2000 =
        WorkCounter.countForTesting(
            () -> {
              Matcher m = pattern.matcher("abc" + "x".repeat(2_000));
              int matches = 0;
              while (m.find()) {
                matches++;
              }
              assertThat(matches).isEqualTo(1);
            });
    long work10000 =
        WorkCounter.countForTesting(
            () -> {
              Matcher m = pattern.matcher("abc" + "x".repeat(10_000));
              int matches = 0;
              while (m.find()) {
                matches++;
              }
              assertThat(matches).isEqualTo(1);
            });

    assertThat(work2000).as("Short input repeated find work").isLessThan(50);
    assertThat(work10000).as("Long input repeated find work").isLessThan(50);
    assertThat(work10000)
        .as("Subsequent find() on start-anchored pattern must not scale with input size")
        .isLessThanOrEqualTo(Math.max(10, work2000 * 2));
  }

  @Test
  void disjointRequiredLiteralCandidateIsScannedOnlyOnce() {
    Pattern pattern = Pattern.compile(".*(?:apple|banana|cherry).*");
    String input = "x".repeat(32_768) + "cherry";

    long work =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input).find()).isTrue());

    assertThat(work)
        .as("a positive disjoint-literal candidate should not repeat every full-input scan")
        .isLessThan(input.length() * 5L);
  }

  @Test
  void fixedOffsetLiteralSelectsRareTokenToAvoidCandidateVerificationWork() {
    // "____" has length 4 with common underscores.
    // "zq" has length 2 with rare letters 'z' and 'q'.
    Pattern pattern = Pattern.compile("[0-9]{2}____[a-z]zq[a-z]");
    String input = "user_name_field_data____common_suffix\n".repeat(1_000);

    long work =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input).find()).isFalse());

    assertThat(work)
        .as("RarityOracle selection must avoid false candidate verification work on common tokens")
        .isLessThanOrEqualTo(input.length() + 100);
  }

  @Test
  void requiredLiteralSelectsRareTokenToRejectNoiseWithMinimalWork() {
    // "____________" has length 12 with common underscores.
    // "404_ERR" has length 7 with high-rarity digits and uppercase letters.
    Pattern pattern = Pattern.compile(".*(____________).*?(404_ERR).*");
    String input = "log_entry_line_with____________separators_and_delimiters\n".repeat(1_000);

    long work =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input).find()).isFalse());

    assertThat(work)
        .as("Required literal prefilter must reject on the selective token with minimal work")
        .isLessThanOrEqualTo(input.length() + 100);
  }

  @Test
  void requiredInfixLiteralRejectsDensePrefixNoiseWithSinglePassWork() {
    // Prefix "{Link:" is common (appears 1,000 times).
    // Infix "<<!nav>>" is rare and absent.
    Pattern pattern = Pattern.compile("(\\{Link:[^}]*?)<<!nav>>([^}]*?\\})");
    String input = "{Link: target=home_page, category=general, priority=high}\n".repeat(1_000);

    long work =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input).find()).isFalse());

    assertThat(work)
        .as(
            "Infix literal prefilter must reject dense-prefix noise in a single pass without DFA"
                + " churn")
        .isLessThanOrEqualTo(input.length() + 100);
  }

  @Test
  void requiredInfixLiteralRejectsDensePrefixNoiseInSplitWithSinglePassWork() {
    Pattern pattern = Pattern.compile("(\\{Link:[^}]*?)<<!nav>>([^}]*?\\})");
    String input = "{Link: target=home_page, category=general, priority=high}\n".repeat(1_000);

    long work =
        WorkCounter.countForTesting(() -> assertThat(pattern.split(input)).containsExactly(input));

    assertThat(work)
        .as("Split must reject dense-prefix noise in a single pass without DFA churn")
        .isLessThanOrEqualTo(input.length() + 100);
  }

  @Test
  void literalSelectivityScoringIsLinearInPatternSize() {
    long smallerWork = WorkCounter.countForTesting(() -> Pattern.compile(selectivityPattern(100)));
    long largerWork = WorkCounter.countForTesting(() -> Pattern.compile(selectivityPattern(400)));

    assertThat(largerWork)
        .as("Literal selectivity scoring should scale linearly with pattern size")
        .isLessThanOrEqualTo(smallerWork * 6);
  }

  private static void assertConstantRejectionWork(IntPredicate find, String description) {
    long work2000 = WorkCounter.countForTesting(() -> assertThat(find.test(2_000)).isFalse());
    long work10000 = WorkCounter.countForTesting(() -> assertThat(find.test(10_000)).isFalse());

    assertThat(work2000).as("%s rejection on short input", description).isLessThan(100);
    assertThat(work10000).as("%s rejection on long input", description).isLessThan(100);
    assertThat(work10000)
        .as("%s rejection work should not scale with trailing input size", description)
        .isLessThanOrEqualTo(Math.max(10, work2000 * 2));
  }

  @Test
  void caseInsensitiveSingleCharRejectionIsSinglePass() {
    Pattern pattern = Pattern.compile("(?i)z");
    int size = 10_000;
    String input = "a".repeat(size);

    long work =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input).find()).isFalse());

    assertThat(work)
        .as("Case-insensitive single char search must inspect text in a single pass")
        .isEqualTo(size);
  }

  @Test
  void caseInsensitiveDenseFalseCandidatesScaleLinearly() {
    // Pattern has anchor 'a' matching every position, but candidate fails on second char 'b'
    Pattern pattern = Pattern.compile("(?i)ab");

    long work2000 =
        WorkCounter.countForTesting(
            () -> assertThat(pattern.matcher("a".repeat(2_000)).find()).isFalse());
    long work10000 =
        WorkCounter.countForTesting(
            () -> assertThat(pattern.matcher("a".repeat(10_000)).find()).isFalse());

    // Without KMP fallback, work would be 2 * N. With KMP fallback after work exhaustion,
    // it must remain strictly linear (work(10000) <= work(2000) * 6).
    assertThat(work10000)
        .as("Dense false candidate verification must fall back to linear KMP")
        .isLessThanOrEqualTo(work2000 * 6);
  }

  @Test
  void caseInsensitiveLiteralFindWorkIsLinear() {
    Pattern pattern = Pattern.compile("(?i)keyword_to_find");

    assertRepeatedFindWorkIsLinear(
        size -> pattern.matcher("KEYWORD_TO_FIND ".repeat(size))::find,
        "Case-insensitive literal find");
  }

  @Test
  void preselectedUtf8DfaCandidateSkipsRedundantStartScan() {
    Pattern pattern = Pattern.compile("\\d{3}/\\d{3}/\\d{4}");
    byte[] bytes = ("123/456/7890" + "x".repeat(100)).getBytes(UTF_8);
    Utf8InputScanner scanner = new Utf8InputScanner(bytes, 0, bytes.length);
    int candidate =
        Utf8StartAccelerator.findNextCandidate(pattern.utf8StartAccelerator(), scanner, 0);
    Dfa dfa = pattern.forwardFirstMatchDfa();

    assertThat(candidate).isZero();
    dfa.doSearch(scanner, candidate, false, false, false);
    long ordinaryWork =
        WorkCounter.countForTesting(
            () ->
                assertThat(dfa.doSearch(scanner, candidate, false, false, false).matched())
                    .isTrue());
    long preselectedWork =
        WorkCounter.countForTesting(
            () ->
                assertThat(dfa.doSearch(scanner, candidate, false, false, true).matched())
                    .isTrue());

    assertThat(preselectedWork)
        .as("DFA must trust a candidate already selected by the caller")
        .isLessThan(ordinaryWork);
  }

  private static void assertRepeatedFindWorkIsLinear(
      IntFunction<FindIterator> matcherFactory, String description) {
    long smallerWork = countAllMatches(matcherFactory.apply(500), 500);
    long largerWork = countAllMatches(matcherFactory.apply(2_000), 2_000);

    assertThat(largerWork)
        .as("%s repeated find work should scale linearly", description)
        .isLessThanOrEqualTo(Math.max(10, smallerWork * 6));
  }

  private static String selectivityPattern(int size) {
    StringBuilder pattern = new StringBuilder("[0-9]").append("z".repeat(size));
    for (int i = 0; i < size; i++) {
      pattern.append("[0-9]aa");
    }
    return pattern.toString();
  }

  private static long countAllMatches(FindIterator matcher, int expectedMatches) {
    return WorkCounter.countForTesting(
        () -> {
          int matches = 0;
          while (matcher.find()) {
            matches++;
          }
          assertThat(matches).isEqualTo(expectedMatches);
        });
  }

  @FunctionalInterface
  private interface FindIterator {
    boolean find();
  }

  private static void assertReverseDfaSuffixFailureIsConstantWork(IntPredicate find) {
    long work2000 =
        WorkCounter.countForTesting(
            () -> {
              boolean matched = find.test(2_000);
              assertThat(matched).isFalse();
            });

    long work10000 =
        WorkCounter.countForTesting(
            () -> {
              boolean matched = find.test(10_000);
              assertThat(matched).isFalse();
            });

    // If a required-content prefilter runs first, it scans the entire input, resulting in at least
    // 2,000 and 10,000 operations respectively.
    //
    // Under reverse DFA suffix acceleration, it rejects after inspecting only a few characters
    // from the end of the text, executing in constant time independent of text size.
    assertThat(work2000)
        .as("Short text failing scan should run in constant-time reverse DFA setup")
        .isGreaterThanOrEqualTo(0)
        .isLessThan(200);

    assertThat(work10000)
        .as("Long text failing scan should also run in constant-time reverse DFA setup")
        .isGreaterThanOrEqualTo(0)
        .isLessThan(200);

    // Assert that scaling is sub-linear (effectively constant)
    assertThat(work10000)
        .as("Work scaling should be flat, not linear with input size increase")
        .isLessThanOrEqualTo(Math.max(10, work2000 * 2));
  }

  private static void assertConstantWork(IntPredicate find, String description) {
    long work2000 = WorkCounter.countForTesting(() -> assertThat(find.test(2_000)).isTrue());
    long work10000 = WorkCounter.countForTesting(() -> assertThat(find.test(10_000)).isTrue());

    assertThat(work2000).as("%s on short input", description).isPositive().isLessThan(200);
    assertThat(work10000).as("%s on long input", description).isPositive().isLessThan(200);
    assertThat(work10000)
        .as("%s should not scale with the prefix", description)
        .isLessThan(work2000 * 2);
  }

  @Test
  void stateAcceleratorEscapedQuoteScanIsLinearForStringInput() {
    Pattern pattern = Pattern.compile("\"[^\"]*\"");
    String input2000 = "\"" + "a".repeat(2_000) + "\"";
    String input10000 = "\"" + "a".repeat(10_000) + "\"";

    long work2000 =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input2000).find()).isTrue());
    long work10000 =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input10000).find()).isTrue());

    assertThat(work10000)
        .as("DFA self-loop state accelerator on String should scale linearly with input size")
        .isLessThan(work2000 * 6);
  }

  @Test
  void stateAcceleratorEscapedQuoteScanIsLinearForUtf8Input() {
    Pattern pattern = Pattern.compile("\"[^\"]*\"");
    byte[] input2000 = ("\"" + "a".repeat(2_000) + "\"").getBytes(UTF_8);
    byte[] input10000 = ("\"" + "a".repeat(10_000) + "\"").getBytes(UTF_8);

    long work2000 =
        WorkCounter.countForTesting(
            () -> assertThat(pattern.matcher(Utf8Input.trusted(input2000)).find()).isTrue());
    long work10000 =
        WorkCounter.countForTesting(
            () -> assertThat(pattern.matcher(Utf8Input.trusted(input10000)).find()).isTrue());

    assertThat(work10000)
        .as("DFA self-loop state accelerator on UTF-8 should scale linearly with input size")
        .isLessThan(work2000 * 6);
  }

  @Test
  void stateAcceleratorEscapedNewlineScanIsLinearForStringInput() {
    Pattern pattern = Pattern.compile("[^,\\n]*\\n");
    String input2000 = "a".repeat(2_000) + "\n";
    String input10000 = "a".repeat(10_000) + "\n";

    long work2000 =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input2000).find()).isTrue());
    long work10000 =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input10000).find()).isTrue());

    assertThat(work10000)
        .as("DFA self-loop state accelerator with newline on String should scale linearly")
        .isLessThan(work2000 * 6);
  }

  @Test
  void singleCharClassFindFastPathIsLinearForStringInput() {
    Pattern pattern = Pattern.compile("\\d");
    String input2000 = "a".repeat(2_000);
    String input10000 = "a".repeat(10_000);

    long work2000 =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input2000).find()).isFalse());
    long work10000 =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input10000).find()).isFalse());

    assertThat(work10000)
        .as("Single character class find on String should scale linearly")
        .isLessThan(work2000 * 6);
  }

  @Test
  void singleCharClassFindFastPathIsLinearForUtf8Input() {
    Pattern pattern = Pattern.compile("\\d");
    byte[] input2000 = "a".repeat(2_000).getBytes(UTF_8);
    byte[] input10000 = "a".repeat(10_000).getBytes(UTF_8);

    long work2000 =
        WorkCounter.countForTesting(
            () -> assertThat(pattern.matcher(Utf8Input.trusted(input2000)).find()).isFalse());
    long work10000 =
        WorkCounter.countForTesting(
            () -> assertThat(pattern.matcher(Utf8Input.trusted(input10000)).find()).isFalse());

    assertThat(work10000)
        .as("Single character class find on UTF-8 should scale linearly")
        .isLessThan(work2000 * 6);
  }

  @Test
  void negatedSingleCharClassFindFastPathIsLinearForStringInput() {
    Pattern pattern = Pattern.compile("[^a-z]");
    String input2000 = "a".repeat(2_000);
    String input10000 = "a".repeat(10_000);

    long work2000 =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input2000).find()).isFalse());
    long work10000 =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input10000).find()).isFalse());

    assertThat(work10000)
        .as("Negated character class find on String should scale linearly")
        .isLessThan(work2000 * 6);
  }

  @Test
  void searchScalingMaintainsParityAcrossLatin1Utf16AndUtf8() {
    Pattern pattern = Pattern.compile("[0-9]{3}-[A-Z]{3}");
    int size = 5_000;
    String latin1 = "abc ".repeat(size / 4);
    // Include a non-Latin1 character at the start so coder becomes UTF16
    String utf16 = "\u4e2d" + latin1.substring(1);
    byte[] utf8 = latin1.getBytes(UTF_8);

    long workLatin1 =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(latin1).find()).isFalse());
    long workUtf16 =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(utf16).find()).isFalse());
    long workUtf8 =
        WorkCounter.countForTesting(
            () -> assertThat(pattern.matcher(Utf8Input.trusted(utf8)).find()).isFalse());

    assertThat(workLatin1).as("Latin-1 search work").isLessThan(size * 4L);
    assertThat(workUtf16)
        .as("UTF-16 search work should remain within a reasonable factor of Latin-1")
        .isLessThan(workLatin1 * 4L + 100);
    assertThat(workUtf8)
        .as("UTF-8 search work should remain within a reasonable factor of Latin-1")
        .isLessThan(workLatin1 * 4L + 100);
  }

  @Test
  void vectorAndSwarScansFindMatchesAcrossChunkBoundaries() {
    int[] ranges = {'0', '9'};
    for (int len = 1; len <= 65; len++) {
      byte[] absent = "a".repeat(len).getBytes(UTF_8);
      byte[] matchAtEnd = absent.clone();
      matchAtEnd[len - 1] = '5';

      assertThat(ByteSwarScan.indexOfAsciiClass(absent, 0, len, ranges, 0))
          .as("SWAR absent result for length %d", len)
          .isEqualTo(-1);
      assertThat(ByteSwarScan.indexOfAsciiClass(matchAtEnd, 0, len, ranges, 0))
          .as("SWAR end match for length %d", len)
          .isEqualTo(len - 1);
      assertThat(ByteSwarScan.indexOfBytePair(absent, 0, len, (byte) 'y', (byte) 'z', 0))
          .as("SWAR pair absent result for length %d", len)
          .isEqualTo(-1);
      assertThat(
              ByteSwarScan.indexOfByteTriple(absent, 0, len, (byte) 'x', (byte) 'y', (byte) 'z', 0))
          .as("SWAR triple absent result for length %d", len)
          .isEqualTo(-1);

      if (isVectorApiAvailable()) {
        assertThat(ByteVectorScan.indexOfAsciiClass(absent, 0, len, ranges, 0))
            .as("vector absent result for length %d", len)
            .isEqualTo(-1);
        assertThat(ByteVectorScan.indexOfAsciiClass(matchAtEnd, 0, len, ranges, 0))
            .as("vector end match for length %d", len)
            .isEqualTo(len - 1);
        assertThat(ByteVectorScan.indexOfAsciiPair(absent, 0, len, (byte) 'y', (byte) 'z', 0))
            .as("vector pair absent result for length %d", len)
            .isEqualTo(-1);
        assertThat(
                ByteVectorScan.indexOfAsciiTriple(
                    absent, 0, len, (byte) 'x', (byte) 'y', (byte) 'z', 0))
            .as("vector triple absent result for length %d", len)
            .isEqualTo(-1);
      }
    }
  }

  @Test
  void startAnchoredLiteralRejectsDisplacedCandidateWithConstantWorkForString() {
    Pattern pattern = Pattern.compile("^abc");
    String displaced = "x".repeat(10_000) + "abc";
    long work =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(displaced).find()).isFalse());
    assertThat(work)
        .as("Start-anchored pattern with displaced candidate must reject in constant work")
        .isLessThan(20);
  }

  @Test
  void startAnchoredLiteralRejectsDisplacedCandidateWithConstantWorkForUtf8() {
    Pattern pattern = Pattern.compile("^abc");
    byte[] displaced = ("x".repeat(10_000) + "abc").getBytes(UTF_8);
    long work =
        WorkCounter.countForTesting(
            () -> assertThat(pattern.matcher(Utf8Input.trusted(displaced)).find()).isFalse());
    assertThat(work)
        .as("Start-anchored UTF-8 pattern with displaced candidate must reject in constant work")
        .isLessThan(20);
  }

  @Test
  void startAnchoredCharClassRejectsDisplacedCandidateWithConstantWork() {
    Pattern pattern = Pattern.compile("^[0-9]");
    String displaced = "a".repeat(10_000) + "5";
    long work =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(displaced).find()).isFalse());
    assertThat(work)
        .as("Start-anchored char-class with displaced match must reject in constant work")
        .isLessThan(20);
  }

  @Test
  void repeatedFindWithAlternatingCaseIsLinearForString() {
    Pattern pattern = Pattern.compile("(?i)needle");
    assertRepeatedFindWorkIsLinear(
        size -> {
          StringBuilder sb = new StringBuilder();
          String[] variants = {"Needle ", "needle ", "NEEDLE ", "nEeDlE "};
          for (int i = 0; i < size; i++) {
            sb.append(variants[i % variants.length]);
          }
          return pattern.matcher(sb.toString())::find;
        },
        "String");
  }

  @Test
  void repeatedFindWithAlternatingCaseIsLinearForUtf8() {
    Pattern pattern = Pattern.compile("(?i)needle");
    assertRepeatedFindWorkIsLinear(
        size -> {
          StringBuilder sb = new StringBuilder();
          String[] variants = {"Needle ", "needle ", "NEEDLE ", "nEeDlE "};
          for (int i = 0; i < size; i++) {
            sb.append(variants[i % variants.length]);
          }
          return pattern.matcher(Utf8Input.trusted(sb.toString().getBytes(UTF_8)))::find;
        },
        "UTF-8");
  }

  @Test
  void repeatedFindWithMultiBranchAlternationIsLinear() {
    Pattern pattern = Pattern.compile("(?:apple|banana|cherry|date)");
    assertRepeatedFindWorkIsLinear(
        size -> {
          StringBuilder sb = new StringBuilder();
          String[] variants = {"apple ", "banana ", "cherry ", "date "};
          for (int i = 0; i < size; i++) {
            sb.append(variants[i % variants.length]);
          }
          return pattern.matcher(sb.toString())::find;
        },
        "String");
  }

  @Test
  void classHashChainAchievesSublinearWorkOnCaseInsensitiveLiteralForStringInput() {
    ClassHashChain chc = ClassHashChain.compileCaseInsensitive("content_length_header");
    String text = "The quick brown fox jumps over the lazy dog. ".repeat(5); // 225 chars
    long work =
        WorkCounter.countForTesting(
            () ->
                assertThat(chc.search(text, 0, WorkLimit.forRemaining(text.length())))
                    .isEqualTo(-1));

    // 225 / 6 = ~37 operations (vs 225 operations for linear scan)
    assertThat(work)
        .as(
            "Class-HashChain must perform sublinear work on case-insensitive patterns for String"
                + " input")
        .isLessThanOrEqualTo(text.length() / 6 + 10);
  }

  @Test
  void classHashChainAchievesSublinearWorkOnNonAsciiCaseInsensitiveLiteralForStringInput() {
    ClassHashChain chc = ClassHashChain.compileCaseInsensitive("конфигурация_сервера"); // M = 20
    String text = "текст_без_совпадений_для_проверки_производительности_".repeat(5); // 270 chars
    long work =
        WorkCounter.countForTesting(
            () ->
                assertThat(chc.search(text, 0, WorkLimit.forRemaining(text.length())))
                    .isEqualTo(-1));

    // Sublinear bound: 270 / 19 = ~14 operations (vs 270 for linear scan)
    assertThat(work)
        .as(
            "ClassHashChain must perform sublinear work on non-ASCII case-insensitive patterns"
                + " for String input")
        .isLessThanOrEqualTo(text.length() / 15 + 10);
  }

  @Test
  void hybridCaseInsensitiveSearchIsImmuneToFalseAnchorStormsForStringInput() {
    Pattern pattern = Pattern.compile("(?i)keyword_to_find"); // anchor is 'k' / 'K'
    String text = "k_other_words_".repeat(20); // 280 chars with 20 'k' false anchors
    long work =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(text).find()).isFalse());

    // shiftAt skips forward on false anchors, keeping work well below quadratic O(N * M)
    assertThat(work)
        .as("Hybrid search with shiftAt must avoid quadratic work on false anchor floods")
        .isLessThanOrEqualTo(text.length() * 2);
  }

  @Test
  void unicodeCaseInsensitiveLinearChainUsesOnePassForSubmatchExtraction() {
    // (?iu) triggers inst.foldCase = true on literal runes (e.g. Kelvin sign K <-> K <-> k)
    Pattern pattern = Pattern.compile("(?iu)key:([0-9]+)");
    assertThat(pattern.canOnePassSubmatch()).isTrue();
    assertThat(pattern.onePass()).isNotNull();

    String input = "prefix noise KEY:98765 trailing text";
    Matcher matcher = pattern.matcher(input);
    assertThat(matcher.find()).isTrue();
    long groupReadWork =
        WorkCounter.countForTesting(
            () -> {
              assertThat(matcher.group(0)).isEqualTo("KEY:98765");
              assertThat(matcher.group(1)).isEqualTo("98765");
            });
    assertThat(groupReadWork)
        .as(
            "Unicode case-insensitive linear chain submatch extraction must run in OnePass with"
                + " zero NFA allocations")
        .isLessThanOrEqualTo(30);
  }

  @Test
  void unanchoredLinearChainSubmatchWorkIsBoundedByMatchSlice() {
    Pattern pattern = Pattern.compile("([a-z]+)@([a-z]+)\\.com");
    String prefix = "noise ".repeat(500);
    String match = "alice@google.com";
    String suffix = " trailing".repeat(500);
    String input = prefix + match + suffix;
    Matcher matcher = pattern.matcher(input);
    assertThat(matcher.find()).isTrue();
    long groupReadWork =
        WorkCounter.countForTesting(
            () -> {
              assertThat(matcher.group(1)).isEqualTo("alice");
              assertThat(matcher.group(2)).isEqualTo("google");
            });
    assertThat(groupReadWork)
        .as(
            "Submatch extraction work must be strictly bounded by match slice length, not haystack"
                + " length")
        .isLessThanOrEqualTo(match.length() * 2L + 20);
  }

  @Test
  void directDfaStartStateAcceleratesUnanchoredAlternationOnString() {
    Prog prog = Compiler.compile(Parser.parse("apple|banana|cherry", ParseFlags.MATCH_NL));
    Dfa dfa = new Dfa(prog, 1000, Dfa.buildSetup(prog), false);
    String input = "x".repeat(10_000) + "cherry";
    long work =
        WorkCounter.countForTesting(
            () -> {
              Dfa.SearchResult res = dfa.doSearch(input, false, false);
              assertThat(res).isNotNull();
              assertThat(res.matched()).isTrue();
            });
    assertThat(work)
        .as("Direct DFA start state acceleration must scan input linearly")
        .isLessThanOrEqualTo(input.length() + 20);
  }

  @Test
  void multiLiteralPrefilterOnDenseCandidateNoiseIsBoundedByWorkLimit() {
    Pattern pattern = Pattern.compile("APPLE|BANANA|CHERRY");
    byte[] noise = "A B C A B C ".repeat(10_000).getBytes(UTF_8);

    long work =
        WorkCounter.countForTesting(
            () -> assertThat(pattern.matcher(Utf8Input.trusted(noise)).find()).isFalse());

    assertThat(work)
        .as("Multi-literal candidate verification must be bounded by WorkLimit on dense noise")
        .isLessThan(500);
  }

  @Test
  void matchesWithCapturesDefersNfaWorkUntilGroupIsRead() {
    // Non-OnePass pattern due to ambiguous repetition where b is part of [a-z]
    Pattern pattern = Pattern.compile("([a-z]+)b([a-z]+)");
    String input = "helloworldbwelcome";
    Matcher matcher = pattern.matcher(input);
    long matchWork = WorkCounter.countForTesting(() -> assertThat(matcher.matches()).isTrue());
    assertThat(matcher.group(0)).isEqualTo(input);
    long groupReadWork =
        WorkCounter.countForTesting(
            () -> {
              assertThat(matcher.group(1)).isEqualTo("helloworld");
              assertThat(matcher.group(2)).isEqualTo("welcome");
            });
    assertThat(matchWork)
        .as("matches() must only run forward DFA work without eager NFA state building")
        .isLessThanOrEqualTo(input.length() * 2L + 20);
    assertThat(groupReadWork)
        .as("Submatch extraction happens on-demand when group(k) is read")
        .isLessThanOrEqualTo(input.length() * 5L + 20);
  }

  @Test
  void matchesWithCapturesRejectsNonMatchingInputInLinearWork() {
    Pattern pattern = Pattern.compile("([a-z]+)b([a-z]+)");
    String input = "x".repeat(10_000);
    Matcher matcher = pattern.matcher(input);
    long work = WorkCounter.countForTesting(() -> assertThat(matcher.matches()).isFalse());
    assertThat(work)
        .as("Non-matching input in matches() must reject in linear DFA steps without NFA work")
        .isLessThanOrEqualTo(input.length() + 20);
  }

  @Test
  void directDfaStartStateAcceleratesUnanchoredAlternationOnUtf8() {
    Prog prog = Compiler.compile(Parser.parse("apple|banana|cherry", ParseFlags.MATCH_NL));
    Dfa dfa = new Dfa(prog, 1000, Dfa.buildSetup(prog), false);
    byte[] input = ("x".repeat(10_000) + "cherry").getBytes(UTF_8);
    long work =
        WorkCounter.countForTesting(
            () -> {
              Dfa.SearchResult res =
                  dfa.doSearch(new Utf8InputScanner(input, 0, input.length), 0, false, false);
              assertThat(res).isNotNull();
              assertThat(res.matched()).isTrue();
            });
    assertThat(work)
        .as("Direct DFA start state acceleration on UTF-8 must scan input linearly")
        .isLessThanOrEqualTo(input.length + 20);
  }

  @Test
  void lookingAtWithCapturesDefersNfaWorkUntilGroupIsRead() {
    // Non-OnePass pattern due to overlapping alternation branches
    Pattern pattern = Pattern.compile("(?:apple|application|apply):([0-9]+)");
    String input = "application:12345 " + "x".repeat(10_000);
    Matcher matcher = pattern.matcher(input);
    long lookingAtWork =
        WorkCounter.countForTesting(() -> assertThat(matcher.lookingAt()).isTrue());
    assertThat(lookingAtWork)
        .as(
            "lookingAt() must only execute DFA prefix scan bounded by matched prefix, not trailing"
                + " 10k chars")
        .isLessThanOrEqualTo(50);
    assertThat(matcher.group(0)).isEqualTo("application:12345");
    assertThat(matcher.group(1)).isEqualTo("12345");
  }

  @Test
  void multiLiteralPrefilterDoesNotRestartScalarVerificationAfterLateDenseCandidates() {
    int literalLength = 512;
    Pattern pattern =
        Pattern.compile("A".repeat(literalLength - 1) + "B|" + "B".repeat(literalLength - 1) + "C");
    byte[] noise = ("x".repeat(256) + "A".repeat(8_192)).getBytes(UTF_8);

    long work =
        WorkCounter.countForTesting(
            () -> assertThat(pattern.matcher(Utf8Input.trusted(noise)).find()).isFalse());

    assertThat(work)
        .as("WorkLimit exhaustion must resume with the normal matcher without a scalar rescan")
        .isLessThan((long) noise.length * 10);
  }

  private static boolean isVectorApiAvailable() {
    try {
      Class.forName("jdk.incubator.vector.ByteVector");
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  @Test
  void leadingWhitespaceCharClassExpansionIsLinearForStringInput() {
    Pattern pattern = Pattern.compile("\\s*[\\[\\uff3b]\\d+[\\]\\uff3d]");
    String input2000 = "a".repeat(2_000);
    String input10000 = "a".repeat(10_000);

    long work2000 =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input2000).find()).isFalse());
    long work10000 =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input10000).find()).isFalse());

    assertThat(work10000)
        .as("Leading expansion char class find on String should scale linearly")
        .isLessThan(work2000 * 6);

    assertRepeatedFindWorkIsLinear(
        size -> pattern.matcher("   [123] ".repeat(size))::find, "String");
  }

  @Test
  void leadingWhitespaceCharClassExpansionIsLinearForUtf8Input() {
    Pattern pattern = Pattern.compile("\\s*[\\[\\uff3b]\\d+[\\]\\uff3d]");
    byte[] input2000 = "a".repeat(2_000).getBytes(UTF_8);
    byte[] input10000 = "a".repeat(10_000).getBytes(UTF_8);

    long work2000 =
        WorkCounter.countForTesting(
            () -> assertThat(pattern.matcher(Utf8Input.trusted(input2000)).find()).isFalse());
    long work10000 =
        WorkCounter.countForTesting(
            () -> assertThat(pattern.matcher(Utf8Input.trusted(input10000)).find()).isFalse());

    assertThat(work10000)
        .as("Leading expansion char class find on UTF-8 should scale linearly")
        .isLessThan(work2000 * 6);

    assertRepeatedFindWorkIsLinear(
        size -> pattern.matcher(Utf8Input.trusted("   [123] ".repeat(size).getBytes(UTF_8)))::find,
        "UTF-8");
  }

  @Test
  void leadingWhitespaceLiteralExpansionIsLinearForStringInput() {
    Pattern pattern = Pattern.compile("\\s+https?://\\w+");
    String input2000 = "a".repeat(2_000);
    String input10000 = "a".repeat(10_000);

    long work2000 =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input2000).find()).isFalse());
    long work10000 =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input10000).find()).isFalse());

    assertThat(work10000)
        .as("Leading expansion literal find on String should scale linearly")
        .isLessThan(work2000 * 6);

    assertRepeatedFindWorkIsLinear(
        size -> pattern.matcher("  http://example ".repeat(size))::find, "String");
  }

  @Test
  void leadingWhitespaceMultiLiteralExpansionIsLinearForStringInput() {
    Pattern pattern = Pattern.compile("\\s*(?:apple|banana|cherry)");
    String input2000 = "x".repeat(2_000);
    String input10000 = "x".repeat(10_000);

    long work2000 =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input2000).find()).isFalse());
    long work10000 =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input10000).find()).isFalse());

    assertThat(work10000)
        .as("Leading expansion multi-literal find on String should scale linearly")
        .isLessThan(work2000 * 6);
  }

  @Test
  void leadingUnicodeExpansionIsLinearForStringAndUtf8Input() {
    Pattern pattern = Pattern.compile("[\\u00e9\\u00e8]+:target");
    String input2000 = "x".repeat(2_000);
    String input10000 = "x".repeat(10_000);

    long work2000 =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input2000).find()).isFalse());
    long work10000 =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input10000).find()).isFalse());

    assertThat(work10000)
        .as("Leading expansion unicode find on String should scale linearly")
        .isLessThan(work2000 * 6);

    assertRepeatedFindWorkIsLinear(
        size -> pattern.matcher("  \u00e9\u00e9:target ".repeat(size))::find, "String");
    assertRepeatedFindWorkIsLinear(
        size ->
            pattern.matcher(
                    Utf8Input.trusted("  \u00e9\u00e9:target ".repeat(size).getBytes(UTF_8)))
                ::find,
        "UTF-8");
  }

  private static int countAstNodes(Regexp regexp) {
    int count = 1;
    if (regexp.subs != null) {
      for (Regexp child : regexp.subs) {
        count += countAstNodes(child);
      }
    }
    return count;
  }

  private static Regexp nestedRequiredLiteral(int size) {
    Regexp nested = Regexp.literalString("q".repeat(size).codePoints().toArray(), 0);
    for (int index = 0; index < size; index++) {
      nested =
          Regexp.concat(
              List.of(
                  Regexp.capture(nested, 0, index + 1, null),
                  Regexp.quest(Regexp.literal('x', 0), 0)),
              0);
    }
    return nested;
  }
}
