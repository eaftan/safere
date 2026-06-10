// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Smoke tests for the exhaustive sweep command-line entry points. */
class SweepCliSmokeTest {
  @TempDir Path tempDir;

  @Test
  void characterClassSweepRunsTinyRange() throws Exception {
    Path outputDir = tempDir.resolve("character");

    CharacterClassDivergenceSweep.main(args(outputDir));

    assertThat(Files.exists(outputDir.resolve("character-class-divergences.jsonl"))).isFalse();
    assertCompactOutput(outputDir);
  }

  @Test
  void graphemeClusterSweepRunsTinyRange() throws Exception {
    Path outputDir = tempDir.resolve("grapheme");

    GraphemeClusterDivergenceSweep.main(args(outputDir));

    assertThat(Files.exists(outputDir.resolve("grapheme-cluster-divergences.jsonl"))).isFalse();
    assertThat(Files.exists(outputDir.resolve("grapheme-cluster-class-counts.tsv"))).isFalse();
    assertThat(Files.exists(outputDir.resolve("grapheme-cluster-unknown-stratified.jsonl")))
        .isFalse();
    assertCompactOutput(outputDir);
  }

  @Test
  void controlEscapeSweepRunsTinyRange() throws Exception {
    Path outputDir = tempDir.resolve("control");

    ControlEscapeDivergenceSweep.main(args(outputDir));

    assertThat(Files.exists(outputDir.resolve("control-escape-divergences.jsonl"))).isFalse();
    assertCompactOutput(outputDir);
  }

  @Test
  void unicodeCharacterClassSweepRunsTinyRange() throws Exception {
    Path outputDir = tempDir.resolve("unicode-character");

    UnicodeCharacterClassDivergenceSweep.main(args(outputDir));

    assertThat(Files.exists(outputDir.resolve("unicode-character-class-divergences.jsonl")))
        .isFalse();
    assertCompactOutput(outputDir);
  }

  @Test
  void regionScalarSweepRunsTinyRange() throws Exception {
    Path outputDir = tempDir.resolve("region-scalar");

    RegionScalarDivergenceSweep.main(args(outputDir));

    assertThat(Files.exists(outputDir.resolve("region-scalar-divergences.jsonl"))).isFalse();
    assertCompactOutput(outputDir);
  }

  @Test
  void regionZeroWidthSweepRunsTinyRange() throws Exception {
    Path outputDir = tempDir.resolve("region-zero-width");

    RegionZeroWidthDivergenceSweep.main(args(outputDir));

    assertThat(Files.exists(outputDir.resolve("region-zero-width-divergences.jsonl"))).isFalse();
    assertCompactOutput(outputDir);
  }

  @Test
  void caseFoldingCharacterClassSweepRunsTinyRange() throws Exception {
    Path outputDir = tempDir.resolve("case-folding");

    CaseFoldingCharacterClassDivergenceSweep.main(args(outputDir));

    assertThat(Files.exists(outputDir.resolve("case-folding-character-class-divergences.jsonl")))
        .isFalse();
    assertCompactOutput(outputDir);
  }

  @Test
  void zeroWidthQuantifierSweepRunsTinyRange() throws Exception {
    Path outputDir = tempDir.resolve("zero-width");

    ZeroWidthQuantifierDivergenceSweep.main(args(outputDir));

    assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-divergences.jsonl")))
        .isFalse();
    assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-class-counts.tsv"))).isFalse();
    assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-unknown-stratified.jsonl")))
        .isFalse();
    assertCompactOutput(outputDir);
  }

  @Test
  void zeroWidthQuantifierSweepHandlesEmptyHighStartRange() throws Exception {
    Path outputDir = tempDir.resolve("zero-width-empty-high-start");

    String output =
        captureOutput(
            () ->
                ZeroWidthQuantifierDivergenceSweep.main(
                    new String[] {
                      "--range=" + Long.MAX_VALUE + ":", "--threads=2", "--output-dir=" + outputDir
                    }));

    assertThat(output).contains("checked=0");
    assertCompactOutput(outputDir);
  }

  @Test
  void graphemeClusterSweepClampsGeneratedProgressToSelectedRange() throws Exception {
    Path outputDir = tempDir.resolve("grapheme-clamped-progress");

    String output =
        captureOutput(
            () ->
                GraphemeClusterDivergenceSweep.main(
                    new String[] {"--range=:10", "--threads=3", "--output-dir=" + outputDir}));

    assertThat(output).contains("generated=10");
    assertThat(Files.readString(outputDir.resolve("progress.json")))
        .doesNotContain("\"nextCaseIndex\":11", "\"nextCaseIndex\":12");
  }

  @Test
  void characterClassCompactReplayJsonReconstructsIndexedCase() {
    assertThat(CharacterClassDivergenceSweep.compactReplayJson(0, "UNKNOWN"))
        .contains("\"caseIndex\":0", "\"classification\":\"UNKNOWN\"", "\"case\"");
  }

  @Test
  void regionScalarCompactReplayJsonReconstructsIndexedCase() {
    assertThat(RegionScalarDivergenceSweep.compactReplayJson(0, "UNKNOWN"))
        .contains("\"caseIndex\":0", "\"classification\":\"UNKNOWN\"", "\"case\"");
  }

  @Test
  void regionScalarSweepClassifiesQuantifiedSplitSurrogateCompositionAsIntentional() {
    assertThat(
            RegionScalarDivergenceSweep.classifyDivergenceShapeForTesting(
                ".", "%s+", java.util.regex.Pattern.DOTALL, "\uD83D\uDE00", 0, 1))
        .isEqualTo("QUANTIFIED_SPLIT_SURROGATE_SCALAR_COMPOSITION");
    assertThat(
            RegionScalarDivergenceSweep.classifyDivergenceShapeForTesting(
                "[\\s\\S]", "%s*", 0, "a\uD83D\uDE00", 1, 2))
        .isEqualTo("QUANTIFIED_SPLIT_SURROGATE_SCALAR_COMPOSITION");
  }

  @Test
  void regionScalarSweepIncludesSplitSurrogateOrdinaryAtomCases() {
    assertThat(
            RegionScalarDivergenceSweep.containsGeneratedCaseForTesting(
                ".", "%s", java.util.regex.Pattern.DOTALL, "\uD83D\uDE00", 0, 1))
        .isTrue();
    assertThat(
            RegionScalarDivergenceSweep.containsGeneratedCaseForTesting(
                "[^a]", "(%s)", 0, "a\uD83D\uDE00", 1, 2))
        .isTrue();
    assertThat(
            RegionScalarDivergenceSweep.containsGeneratedCaseForTesting(
                "\\P{Cs}", "^%s$", 0, "\uD801\uDC00", 0, 1))
        .isTrue();
  }

  @Test
  void regionZeroWidthSweepIncludesSplitSurrogateEndAnchorCase() {
    assertThat(
            RegionZeroWidthDivergenceSweep.containsGeneratedCaseForTesting(
                "$", 0, "\uD83D\uDE00", 0, 1))
        .isTrue();
    assertThat(
            RegionZeroWidthDivergenceSweep.containsGeneratedCaseForTesting(
                "\\B", java.util.regex.Pattern.UNICODE_CHARACTER_CLASS, "a\uD83D\uDE00", 1, 2))
        .isTrue();
    assertThat(
            RegionZeroWidthDivergenceSweep.containsGeneratedCaseForTesting(
                "\\B|y", 0, "x\uD83D\uDE00y", 1, 4))
        .isTrue();
    assertThat(
            RegionZeroWidthDivergenceSweep.containsGeneratedCaseForTesting(
                "\\B|a", 0, "x\uD83D\uDE00y", 1, 4))
        .isTrue();
    assertThat(
            RegionZeroWidthDivergenceSweep.containsGeneratedCaseForTesting(
                "\\B.", java.util.regex.Pattern.DOTALL, "\uD83D\uDE00", 0, 1))
        .isTrue();
    assertThat(
            RegionZeroWidthDivergenceSweep.containsGeneratedCaseForTesting(
                "\\B[\\s\\S]", 0, "\uD83D\uDE00", 0, 1))
        .isTrue();
  }

  @Test
  void regionZeroWidthSweepClassifiesKnownIntentionalDivergences() {
    assertThat(
            RegionZeroWidthDivergenceSweep.classifyDivergenceShapeForTesting(
                "\\b", 0, "a\u0301", 0, 1, true, true))
        .isEqualTo("ASCII_WORD_BOUNDARY_COMBINING_MARK");
    assertThat(
            RegionZeroWidthDivergenceSweep.classifyDivergenceShapeForTesting(
                "$", 0, "\r\n", 1, 2, false, true))
        .isEqualTo("OPAQUE_REGION_CRLF_PAIR_CONTEXT");
    assertThat(
            RegionZeroWidthDivergenceSweep.classifyDivergenceShapeForTesting(
                "\\B", 0, "x\uD83D\uDE00y", 1, 4, true, true))
        .isEqualTo("NON_WORD_BOUNDARY_SPLIT_SURROGATE_INTERIOR_POSITION");
    assertThat(
            RegionZeroWidthDivergenceSweep.classifyDivergenceShapeForTesting(
                "y|\\B.", java.util.regex.Pattern.DOTALL, "x\uD83D\uDE00y", 1, 4, true, false))
        .isEqualTo("NON_WORD_BOUNDARY_SPLIT_SURROGATE_INTERIOR_POSITION");
  }

  @Test
  void regionZeroWidthSweepTreatsAcceptanceMismatchAsDivergence() {
    assertThat(RegionZeroWidthDivergenceSweep.semanticallyEqualForTesting(false, "", true, "trace"))
        .isFalse();
    assertThat(RegionZeroWidthDivergenceSweep.semanticallyEqualForTesting(true, "trace", false, ""))
        .isFalse();
  }

  @Test
  void zeroWidthQuantifierSweepIncludesRepeatedQuantifierRegressions() {
    assertThat(ZeroWidthQuantifierDivergenceSweep.containsQuantifierChainForTesting("*+")).isTrue();
    assertThat(ZeroWidthQuantifierDivergenceSweep.containsQuantifierChainForTesting("?+")).isTrue();
    assertThat(ZeroWidthQuantifierDivergenceSweep.containsQuantifierChainForTesting("{2}+"))
        .isTrue();
    assertThat(ZeroWidthQuantifierDivergenceSweep.containsQuantifierChainForTesting("*??"))
        .isTrue();
    assertThat(ZeroWidthQuantifierDivergenceSweep.containsQuantifierChainForTesting("*{1}+"))
        .isTrue();
    assertThat(ZeroWidthQuantifierDivergenceSweep.containsQuantifierChainForTesting("*{0}+"))
        .isTrue();
    assertThat(ZeroWidthQuantifierDivergenceSweep.containsQuantifierChainForTesting("{0,2}++"))
        .isTrue();
    assertThat(
            ZeroWidthQuantifierDivergenceSweep.containsCartesianCaseForTesting(
                "()", "%s", "*{0}+", "%sa", "ba"))
        .isTrue();
  }

  @Test
  void zeroWidthQuantifierSweepIncludesMixedLeadingGraphemeBoundaryFindCases() {
    String trailingZwjGrapheme = "\uD83D\uDC69\u200D\uD83D\uDCBBx";
    String precededZwjGrapheme = "x\uD83D\uDC69\u200D\uD83D\uDCBBx";

    assertThat(
            ZeroWidthQuantifierDivergenceSweep.containsCartesianCaseForTesting(
                "\\b{g}", "%s", "{1}{1}", "(?:%s|a).", trailingZwjGrapheme))
        .isTrue();
    assertThat(
            ZeroWidthQuantifierDivergenceSweep.containsCartesianCaseForTesting(
                "\\b{g}", "%s", "{1}{1}", "(?:a|%s).", trailingZwjGrapheme))
        .isTrue();
    assertThat(
            ZeroWidthQuantifierDivergenceSweep.containsCartesianCaseForTesting(
                "\\b{g}", "%s", "++", "(?:%s|a).", precededZwjGrapheme))
        .isTrue();
  }

  @Test
  void regexSweepTraceIncludesCapturesForZeroWidthPossessiveStar() {
    RegexSweep.Outcome outcome = RegexSweep.jdkTraceOutcome("()*+", 0, java.util.List.of(""), 1);

    assertThat(outcome.accepted()).isTrue();
    assertThat(outcome.trace()).contains(":matches=true@0-0{groups=1;g1=0-0:");
  }

  @Test
  void zeroWidthQuantifierSweepIncludesPossessiveCaptureAlternationCases() {
    assertThat(
            ZeroWidthQuantifierDivergenceSweep.containsCartesianCaseForTesting(
                "()", "%s", "*+", "(?:%s|a).", "ab"))
        .isTrue();
    assertThat(
            ZeroWidthQuantifierDivergenceSweep.containsCartesianCaseForTesting(
                "^", "(%s)", "*+", "%sa", "ba"))
        .isTrue();
    assertThat(
            ZeroWidthQuantifierDivergenceSweep.containsCartesianCaseForTesting(
                "()", "%s", "{0}+", "%sa", "ba"))
        .isTrue();
    assertThat(
            ZeroWidthQuantifierDivergenceSweep.containsGeneratedCaseForTesting(
                "((\\b{g})*+)a?", "a"))
        .isTrue();

    RegexSweep.Outcome outcome =
        RegexSweep.jdkTraceOutcome("(?:()*+|a).", 0, java.util.List.of("ab"), 1);
    RegexSweep.Outcome capturedAnchor =
        RegexSweep.jdkTraceOutcome("(^)*+a", 0, java.util.List.of("ba"), 1);
    RegexSweep.Outcome zeroCount =
        RegexSweep.jdkTraceOutcome("(){0}+a", 0, java.util.List.of("ba"), 1);
    RegexSweep.Outcome repeatedFindZeroLength =
        RegexSweep.jdkTraceOutcome("((\\b{g})*+)a?", 0, java.util.List.of("a"), 2);

    assertThat(outcome.accepted()).isTrue();
    assertThat(outcome.trace()).contains("ab:matches=true@0-2{groups=1;g1=0-0:");
    assertThat(capturedAnchor.accepted()).isTrue();
    assertThat(capturedAnchor.trace()).contains("ba:find0=true@1-2{groups=1;g1=0-0:");
    assertThat(zeroCount.accepted()).isTrue();
    assertThat(zeroCount.trace()).contains("ba:find0=true@1-2{groups=1;g1=null}");
    assertThat(repeatedFindZeroLength.accepted()).isTrue();
    assertThat(repeatedFindZeroLength.trace())
        .contains("a:find1=true@1-1{groups=2;g1=1-1:;g2=1-1:");
  }

  @Test
  void zeroWidthQuantifierSweepIncludesDeepPossessiveCaptureStackSentinel() {
    String regex = "(?:".repeat(12000) + "()" + ")".repeat(12000) + "*+a";

    assertThat(ZeroWidthQuantifierDivergenceSweep.containsGeneratedCaseForTesting(regex, "ba"))
        .isTrue();
  }

  @Test
  void zeroWidthQuantifierSweepIncludesDeepRepeatedBoundaryMetadataStackSentinel() {
    String regex = "\\b{g}{2}";
    for (int i = 0; i < 12000; i++) {
      regex = "(?:" + regex + "|a)";
    }
    regex = regex + "{1}{1}";

    assertThat(ZeroWidthQuantifierDivergenceSweep.containsGeneratedCaseForTesting(regex, "a"))
        .isTrue();
  }

  @Test
  void characterClassReplayUsesConfiguredThreads() throws Exception {
    Path replayFile = tempDir.resolve("character-replay.jsonl");
    Path outputDir = tempDir.resolve("character-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"template":"replay","comments":false,"negated":false,"pieces":[{"label":"literalA","text":"a"}]}}
        """);

    String output =
        captureOutput(() -> CharacterClassDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("mode=replay", "checked=1", "generated=1", "threads=2");
    assertThat(output).doesNotContain("threads=1");
  }

  @Test
  void controlEscapeReplayUsesConfiguredThreads() throws Exception {
    Path replayFile = tempDir.resolve("control-replay.jsonl");
    Path outputDir = tempDir.resolve("control-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"target":65,"contextLabel":"bare","contextTemplate":"\\\\c%s","flagLabel":"none","flagPrefix":"","flags":0}}
        """);

    String output =
        captureOutput(() -> ControlEscapeDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("mode=replay", "checked=1", "generated=1", "threads=2");
    assertThat(output).doesNotContain("threads=1");
  }

  @Test
  void unicodeCharacterClassReplayUsesConfiguredThreads() throws Exception {
    Path replayFile = tempDir.resolve("unicode-character-replay.jsonl");
    Path outputDir = tempDir.resolve("unicode-character-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"label":"word","regex":"\\\\w","codePoint":65}}
        """);

    String output =
        captureOutput(
            () -> UnicodeCharacterClassDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("mode=replay", "checked=1", "generated=1", "threads=2");
    assertThat(output).doesNotContain("threads=1");
  }

  @Test
  void caseFoldingCharacterClassReplayUsesConfiguredThreads() throws Exception {
    Path replayFile = tempDir.resolve("case-folding-replay.jsonl");
    Path outputDir = tempDir.resolve("case-folding-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"patternLabel":"rangeLowerHJ","regex":"[h-j]","inputRepeat":1,"flagLabel":"unicodeCase","flags":66,"inputCodePoint":104}}
        """);

    String output =
        captureOutput(
            () -> CaseFoldingCharacterClassDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("mode=replay", "checked=1", "generated=1", "threads=2");
    assertThat(output).doesNotContain("threads=1");
  }

  @Test
  void graphemeClusterReplayUsesConfiguredThreads() throws Exception {
    Path replayFile = tempDir.resolve("grapheme-replay.jsonl");
    Path outputDir = tempDir.resolve("grapheme-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"regexLabel":"literal","regex":"a","inputLabel":"literal","input":"a","region":"full","prefix":"","suffix":"","regionStart":0,"regionEnd":1,"bounds":"opaqueAnchoring","operation":"freshTrace"}}
        """);

    String output =
        captureOutput(() -> GraphemeClusterDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("mode=replay", "checked=1", "generated=1", "threads=2");
    assertThat(output).doesNotContain("threads=1");
  }

  @Test
  void zeroWidthQuantifierReplayUsesConfiguredThreads() throws Exception {
    Path replayFile = tempDir.resolve("zero-width-replay.jsonl");
    Path outputDir = tempDir.resolve("zero-width-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"operandLabel":"beginLine","operandRegex":"^","wrapperLabel":"bare","wrapperTemplate":"%s","firstQuantifierLabel":"plus","firstQuantifier":"+","suffixQuantifierLabel":"plus","suffixQuantifier":"+","contextLabel":"bare","contextTemplate":"%s","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        """);

    String output =
        captureOutput(
            () -> ZeroWidthQuantifierDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("mode=replay", "checked=1", "generated=1", "threads=2");
    assertThat(output).doesNotContain("threads=1");
  }

  @Test
  void zeroWidthQuantifierReplayCountsKnownIntentionalCaptureLeakage() throws Exception {
    Path replayFile = tempDir.resolve("zero-width-known-replay.jsonl");
    Path outputDir = tempDir.resolve("zero-width-known-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"operandLabel":"atom:emptyCapturing","operandRegex":"()","wrapperLabel":"capturing","wrapperTemplate":"(%s)","quantifierChainLabel":"star","quantifierChain":"*","contextLabel":"mixedLeadingLiteralAlternative","contextTemplate":"(?:%s|a).","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        {"case":{"operandLabel":"atom:emptyCapturing","operandRegex":"()","wrapperLabel":"nonCapturing","wrapperTemplate":"(?:%s)","quantifierChainLabel":"star","quantifierChain":"*","contextLabel":"mixedLeadingLiteralAlternative","contextTemplate":"(?:%s|a).","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        """);

    String output =
        captureOutput(
            () -> ZeroWidthQuantifierDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("actionableDivergences=0", "unknownDivergences=0");
    assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-divergences.jsonl")))
        .isFalse();
    assertThat(Files.readString(outputDir.resolve("zero-width-quantifier-class-counts.tsv")))
        .contains("FAILED_PATH_CAPTURE_LEAKAGE\tKNOWN_INTENTIONAL\t2");
  }

  @Test
  void zeroWidthQuantifierReplayAcceptsPossessiveQuantifiersOverZeroWidthOperands()
      throws Exception {
    Path replayFile = tempDir.resolve("zero-width-possessive-replay.jsonl");
    Path outputDir = tempDir.resolve("zero-width-possessive-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"operandLabel":"atom:beginLine","operandRegex":"^","wrapperLabel":"bare","wrapperTemplate":"%s","quantifierChainLabel":"star,plus","quantifierChain":"*+","contextLabel":"bare","contextTemplate":"%s","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        """);

    String output =
        captureOutput(
            () -> ZeroWidthQuantifierDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("divergences=0", "actionableDivergences=0");
    assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-divergences.jsonl")))
        .isFalse();
    assertThat(Files.readString(outputDir.resolve("zero-width-quantifier-class-counts.tsv")))
        .contains("ZERO_WIDTH_POSSESSIVE_QUANTIFIER_REJECTED\tEXPECTED_ZERO\t0");
  }

  @Test
  void zeroWidthQuantifierRejectedPossessiveClassIsActionable() {
    assertThat(ZeroWidthQuantifierDivergenceSweep.divergenceClassificationNames())
        .contains("ZERO_WIDTH_POSSESSIVE_QUANTIFIER_REJECTED");
    int index =
        ZeroWidthQuantifierDivergenceSweep.divergenceClassificationNames()
            .indexOf("ZERO_WIDTH_POSSESSIVE_QUANTIFIER_REJECTED");
    assertThat(ZeroWidthQuantifierDivergenceSweep.divergenceClassificationStatuses().get(index))
        .isEqualTo(DivergenceStatus.EXPECTED_ZERO);
  }

  @Test
  void zeroWidthQuantifierReplayClassifiesKnownGraphemeAlternativeCursorDivergence()
      throws Exception {
    Path replayFile = tempDir.resolve("zero-width-grapheme-known-replay.jsonl");
    Path outputDir = tempDir.resolve("zero-width-grapheme-known-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"operandLabel":"atom:graphemeBoundary","operandRegex":"\\\\b{g}","wrapperLabel":"bare","wrapperTemplate":"%s","quantifierChainLabel":"plus","quantifierChain":"+","contextLabel":"mixedLeadingLiteralAlternativeReversed","contextTemplate":"(?:a|%s).","flagLabel":"commentsEmbeddedComment","flagPrefix":"(?x)","flags":0,"trivia":"#q\\n"}}
        """);

    String output =
        captureOutput(
            () -> ZeroWidthQuantifierDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("divergences=1", "actionableDivergences=0");
    assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-divergences.jsonl")))
        .isFalse();
    assertThat(Files.readString(outputDir.resolve("zero-width-quantifier-class-counts.tsv")))
        .contains("GRAPHEME_BOUNDARY_ALTERNATIVE_FIND_CURSOR\tKNOWN_INTENTIONAL\t1");
  }

  @Test
  void zeroWidthQuantifierGraphemeCursorClassifierRequiresFindOnlyTraceDifference() {
    assertThat(
            ZeroWidthQuantifierDivergenceSweep
                .isKnownGraphemeBoundaryAlternativeFindCursorForTesting(
                    "a:matches=true@0-1,a:find0=true@0-1,a:find1=false",
                    "a:matches=true@0-1,a:find0=true@0-1,a:find1=true@1-2,a:find2=false"))
        .isTrue();
    assertThat(
            ZeroWidthQuantifierDivergenceSweep
                .isKnownGraphemeBoundaryAlternativeFindCursorForTesting(
                    "a:matches=true@0-1,a:find0=true@0-1", "a:matches=false,a:find0=true@0-1"))
        .isFalse();
  }

  @Test
  void zeroWidthQuantifierReplayClassifiesRepeatedGraphemeBoundaryCompositionDivergence()
      throws Exception {
    Path replayFile = tempDir.resolve("zero-width-repeated-grapheme-known-replay.jsonl");
    Path outputDir = tempDir.resolve("zero-width-repeated-grapheme-known-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"operandLabel":"atom:graphemeBoundary","operandRegex":"\\\\b{g}","wrapperLabel":"bare","wrapperTemplate":"%s","quantifierChainLabel":"repeatTwo","quantifierChain":"{2}","contextLabel":"bare","contextTemplate":"%s","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        {"case":{"operandLabel":"atom:graphemeBoundary","operandRegex":"\\\\b{g}","wrapperLabel":"bare","wrapperTemplate":"%s","quantifierChainLabel":"repeatTwo","quantifierChain":"{2}","contextLabel":"anchoredSurroundingLiterals","contextTemplate":"^a%sb$","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        {"case":{"operandLabel":"atom:graphemeBoundary","operandRegex":"\\\\b{g}","wrapperLabel":"nonCapturing","wrapperTemplate":"(?:%s)","quantifierChainLabel":"repeatTwo","quantifierChain":"{2}","contextLabel":"mixedLeadingLiteralAlternative","contextTemplate":"(?:%s|a).","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        """);

    String output =
        captureOutput(
            () -> ZeroWidthQuantifierDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("divergences=3", "actionableDivergences=0");
    assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-divergences.jsonl")))
        .isFalse();
    assertThat(Files.readString(outputDir.resolve("zero-width-quantifier-class-counts.tsv")))
        .contains("REPEATED_GRAPHEME_BOUNDARY_COMPOSITION\tKNOWN_INTENTIONAL\t3");
  }

  @Test
  void zeroWidthQuantifierRepeatedGraphemeCompositionClassifierRequiresAdditiveSafeReTrace() {
    assertThat(
            ZeroWidthQuantifierDivergenceSweep
                .isRepeatedGraphemeBoundaryCompositionTraceDifferenceForTesting(
                    "ab:matches=false,ab:lookingAt=true@0-0,ab:find0=true@0-0,ab:find1=false",
                    "ab:matches=true@0-2,ab:lookingAt=true@0-0,ab:find0=true@0-0,"
                        + "ab:find1=true@1-1,ab:find2=false"))
        .isTrue();
    assertThat(
            ZeroWidthQuantifierDivergenceSweep
                .isRepeatedGraphemeBoundaryCompositionTraceDifferenceForTesting(
                    "ab:matches=true@0-1,ab:find0=true@0-0",
                    "ab:matches=true@0-2,ab:find0=true@0-0"))
        .isFalse();
    assertThat(
            ZeroWidthQuantifierDivergenceSweep
                .isRepeatedGraphemeBoundaryCompositionTraceDifferenceForTesting(
                    "ab:matches=false,ab:find0=false", "ab:matches=false,ab:find0=false"))
        .isFalse();
  }

  @Test
  void zeroWidthQuantifierReplayClassifiesGraphemeAlternativeSegmentationDivergence()
      throws Exception {
    Path replayFile = tempDir.resolve("zero-width-grapheme-alternative-segmentation.jsonl");
    Path outputDir = tempDir.resolve("zero-width-grapheme-alternative-segmentation");
    Files.writeString(
        replayFile,
        """
        {"case":{"operandLabel":"atom:graphemeBoundary","operandRegex":"\\\\b{g}","wrapperLabel":"capturing","wrapperTemplate":"(%s)","quantifierChainLabel":"plus,repeatZero,question,repeatZero","quantifierChain":"+{0}?{0}","contextLabel":"mixedLeadingLiteralAlternative","contextTemplate":"(?:%s|a).","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        """);

    String output =
        captureOutput(
            () -> ZeroWidthQuantifierDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("divergences=1", "actionableDivergences=0");
    assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-divergences.jsonl")))
        .isFalse();
    assertThat(Files.readString(outputDir.resolve("zero-width-quantifier-class-counts.tsv")))
        .contains("GRAPHEME_BOUNDARY_ALTERNATIVE_GRAPHEME_MODEL\tKNOWN_INTENTIONAL\t1");
  }

  @Test
  void zeroWidthQuantifierReplayClassifiesAsciiWordBoundaryCombiningMarkDivergence()
      throws Exception {
    Path replayFile = tempDir.resolve("zero-width-word-boundary-known-replay.jsonl");
    Path outputDir = tempDir.resolve("zero-width-word-boundary-known-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"operandLabel":"atom:wordBoundary","operandRegex":"\\\\b","wrapperLabel":"bare","wrapperTemplate":"%s","quantifierChainLabel":"plus","quantifierChain":"+","contextLabel":"bare","contextTemplate":"%s","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        """);

    String output =
        captureOutput(
            () -> ZeroWidthQuantifierDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("divergences=1", "actionableDivergences=0");
    assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-divergences.jsonl")))
        .isFalse();
    assertThat(Files.readString(outputDir.resolve("zero-width-quantifier-class-counts.tsv")))
        .contains("ASCII_WORD_BOUNDARY_COMBINING_MARK\tKNOWN_INTENTIONAL\t1");
  }

  @Test
  void zeroWidthQuantifierReplayClassifiesCapturedPossessiveAsciiBoundaryAsKnownIntentional()
      throws Exception {
    Path replayFile = tempDir.resolve("zero-width-captured-word-boundary-possessive.jsonl");
    Path outputDir = tempDir.resolve("zero-width-captured-word-boundary-possessive");
    Files.writeString(
        replayFile,
        """
        {"case":{"operandLabel":"atom:wordBoundary","operandRegex":"\\\\b","wrapperLabel":"capturing","wrapperTemplate":"(%s)","quantifierChainLabel":"star,plus","quantifierChain":"*+","contextLabel":"bare","contextTemplate":"%s","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        """);

    String output =
        captureOutput(
            () -> ZeroWidthQuantifierDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("divergences=1", "actionableDivergences=0");
    assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-divergences.jsonl")))
        .isFalse();
    assertThat(Files.readString(outputDir.resolve("zero-width-quantifier-class-counts.tsv")))
        .contains("ASCII_WORD_BOUNDARY_COMBINING_MARK\tKNOWN_INTENTIONAL\t1")
        .contains("ZERO_WIDTH_POSSESSIVE_CAPTURE_RETENTION\tEXPECTED_ZERO\t0");
  }

  @Test
  void zeroWidthQuantifierReplayClassifiesTargetedCapturedPossessiveGraphemeBoundaryAsKnown()
      throws Exception {
    Path replayFile = tempDir.resolve("zero-width-targeted-grapheme-possessive.jsonl");
    Path outputDir = tempDir.resolve("zero-width-targeted-grapheme-possessive");
    Files.writeString(
        replayFile,
        """
        {"case":{"operandLabel":"targeted:capturedGraphemeBoundary","operandRegex":"\\\\b{g}","wrapperLabel":"capturing","wrapperTemplate":"(%s)","quantifierChainLabel":"star,plus","quantifierChain":"*+","contextLabel":"capturedThenOptionalLiteral","contextTemplate":"(%s)a?","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        """);

    String output =
        captureOutput(
            () -> ZeroWidthQuantifierDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("divergences=1", "actionableDivergences=0");
    assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-divergences.jsonl")))
        .isFalse();
    assertThat(Files.readString(outputDir.resolve("zero-width-quantifier-class-counts.tsv")))
        .contains("GRAPHEME_BOUNDARY_CAPTURE_GRAPHEME_MODEL\tKNOWN_INTENTIONAL\t1")
        .contains("UNKNOWN\tUNKNOWN\t0");
  }

  @Test
  void zeroWidthQuantifierAsciiWordBoundaryClassifierRequiresCombiningMarkOnlyDifference() {
    String common = ",a:matches=false,a:find0=false";

    assertThat(
            ZeroWidthQuantifierDivergenceSweep.isDecomposedEAcuteOnlyTraceDifferenceForTesting(
                "e\u0301:matches=false,e\u0301:find0=true@0-0" + common,
                "e\u0301:matches=true@0-0,e\u0301:find0=true@1-1" + common))
        .isTrue();
    assertThat(
            ZeroWidthQuantifierDivergenceSweep.isDecomposedEAcuteOnlyTraceDifferenceForTesting(
                "e\u0301:matches=false,a:find0=false", "e\u0301:matches=true@0-0,a:find0=true@0-0"))
        .isFalse();
  }

  @Test
  void zeroWidthQuantifierReplayFailsForUnknownDivergence() throws Exception {
    Path replayFile = tempDir.resolve("zero-width-unknown-replay.jsonl");
    Path outputDir = tempDir.resolve("zero-width-unknown-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"operandLabel":"atom:syntheticBoundary","operandRegex":"\\\\b","wrapperLabel":"bare","wrapperTemplate":"%s","quantifierChainLabel":"plus","quantifierChain":"+","contextLabel":"bare","contextTemplate":"%s","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        """);

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> ZeroWidthQuantifierDivergenceSweep.main(replayArgs(outputDir, replayFile))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unknown behavioral divergences");
    assertThat(Files.readString(outputDir.resolve("zero-width-quantifier-class-counts.tsv")))
        .contains("UNKNOWN\tUNKNOWN\t1");
  }

  @Test
  void graphemeClusterReplaySuppressesKnownIntentionalDivergences() throws Exception {
    Path replayFile = tempDir.resolve("grapheme-known-replay.jsonl");
    Path outputDir = tempDir.resolve("grapheme-known-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"regexLabel":"boundaryClusterBoundary","regex":"\\\\b{g}\\\\X\\\\b{g}","inputLabel":"twoAscii","input":"ab","region":"full","prefix":"","suffix":"","regionStart":0,"regionEnd":2,"bounds":"opaqueAnchoring","operation":"freshTrace"}}
        {"case":{"regexLabel":"boundaryClusterAlternativeAfterLiteral","regex":"b|\\\\b{g}\\\\X","inputLabel":"crlfTrace","input":"\\r\\r\\n\\r","region":"wrapped","prefix":"#","suffix":"$","regionStart":1,"regionEnd":5,"bounds":"opaqueAnchoring","operation":"freshTrace"}}
        {"case":{"regexLabel":"boundaryClusterAlternativeAfterLiteral","regex":"b|\\\\b{g}\\\\X","inputLabel":"transparentPrependTrace","input":"\\r؀a","region":"wrapped","prefix":"#","suffix":"$","regionStart":1,"regionEnd":4,"bounds":"transparentAnchoring","operation":"freshTrace"}}
        """);

    String output =
        captureOutput(() -> GraphemeClusterDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("divergences=3", "actionableDivergences=0", "unknownDivergences=0");
    assertThat(Files.exists(outputDir.resolve("grapheme-cluster-divergences.jsonl"))).isFalse();
    assertThat(Files.readString(outputDir.resolve("grapheme-cluster-class-counts.tsv")))
        .contains(
            "BOUNDARY_CLUSTER_BOUNDARY_COMPOSITION\tKNOWN_INTENTIONAL\t1",
            "BOUNDARY_CLUSTER_ALTERNATIVE_FIND_CURSOR\tKNOWN_INTENTIONAL\t2");
  }

  @Test
  void graphemeClusterReplayReportsBoundedFirstUnknownDivergencesWithoutRawOutput()
      throws Exception {
    Path replayFile = tempDir.resolve("grapheme-unknown-replay.jsonl");
    Path outputDir = tempDir.resolve("grapheme-unknown-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"regexLabel":"unclassifiedGraphemeAlternative","regex":"b|\\\\b{g}\\\\X","inputLabel":"crlfTrace","input":"\\r\\r\\n\\r","region":"wrapped","prefix":"#","suffix":"$","regionStart":1,"regionEnd":5,"bounds":"opaqueAnchoring","operation":"freshTrace"}}
        """);

    String output =
        captureOutput(() -> GraphemeClusterDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("divergences=1", "actionableDivergences=0", "unknownDivergences=1");
    assertThat(Files.exists(outputDir.resolve("grapheme-cluster-divergences.jsonl"))).isFalse();
    assertThat(Files.readString(outputDir.resolve("grapheme-cluster-unknown-first.jsonl")))
        .contains("\"classification\":\"UNKNOWN\"");
    assertThat(Files.exists(outputDir.resolve("grapheme-cluster-unknown-stratified.jsonl")))
        .isFalse();
  }

  @Test
  void graphemeClusterRejectsRetiredUnknownStratifiedSampleOption() throws Exception {
    Path replayFile = tempDir.resolve("grapheme-retired-option-replay.jsonl");
    Path outputDir = tempDir.resolve("grapheme-retired-option-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"regexLabel":"literal","regex":"a","inputLabel":"literal","input":"a","region":"full","prefix":"","suffix":"","regionStart":0,"regionEnd":1,"bounds":"opaqueAnchoring","operation":"freshTrace"}}
        """);

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    GraphemeClusterDivergenceSweep.main(
                        replayArgs(outputDir, replayFile, "--unknown-stratified-samples=2"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown argument: --unknown-stratified-samples=2");
  }

  private static String[] args(Path outputDir) {
    return new String[] {"--range=:10", "--threads=1", "--output-dir=" + outputDir};
  }

  private static void assertCompactOutput(Path outputDir) {
    assertThat(Files.exists(outputDir.resolve("run-manifest.json"))).isTrue();
    assertThat(Files.exists(outputDir.resolve("progress.json"))).isTrue();
    assertThat(Files.exists(outputDir.resolve("divergence-indices/worker-00.bin"))).isTrue();
  }

  private static String[] replayArgs(Path outputDir, Path replayFile) {
    return replayArgs(outputDir, replayFile, new String[0]);
  }

  private static String[] replayArgs(Path outputDir, Path replayFile, String... extraArgs) {
    String[] args = new String[3 + extraArgs.length];
    args[0] = "--threads=2";
    args[1] = "--output-dir=" + outputDir;
    args[2] = "--replay-file=" + replayFile;
    System.arraycopy(extraArgs, 0, args, 3, extraArgs.length);
    return args;
  }

  private static String captureOutput(ThrowingRunnable runnable) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    try {
      System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
      runnable.run();
    } finally {
      System.setOut(originalOut);
    }
    return output.toString(StandardCharsets.UTF_8);
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
