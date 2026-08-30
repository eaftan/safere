// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.safere.Pattern.FixedOffsetLiteral;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class StartAcceleratorTest {

  @Test
  void nullAndNoneDescriptorsProduceNullAccelerators() {
    assertThat(StringStartAccelerator.create((MultiAnchorDescriptor) null, false)).isNull();
    assertThat(StringStartAccelerator.create((MultiAnchorDescriptor.StartPlan) null, false))
        .isNull();
    assertThat(StringStartAccelerator.create(MultiAnchorDescriptor.StartPlan.None.INSTANCE, false))
        .isNull();
    assertThat(Utf8StartAccelerator.create((MultiAnchorDescriptor) null, false)).isNull();
    assertThat(Utf8StartAccelerator.create((MultiAnchorDescriptor.StartPlan) null, false)).isNull();
    assertThat(Utf8StartAccelerator.create(MultiAnchorDescriptor.StartPlan.None.INSTANCE, false))
        .isNull();
    assertThat(MultiAnchorDescriptor.NONE.hasStartAcceleration()).isFalse();
  }

  @Test
  void stringMultiLiteralPlanRetainsSelectiveCharacterClassFallback() {
    MultiAnchorDescriptor.StartPlan plan = Pattern.compile("apple|banana|cherry").startPlan();

    assertThat(plan).isInstanceOf(MultiAnchorDescriptor.StartPlan.MultiLiteral.class);
    assertThat(StringStartAccelerator.create(plan, false))
        .isInstanceOf(StringStartAccelerator.CharClass.class);
  }

  @Test
  void utf8MultiLiteralPlanDoesNotUseNonselectiveCharacterClassFallback() {
    MultiAnchorDescriptor.StartPlan plan = Pattern.compile("afoo|bfoo|cfoo|dfoo").startPlan();

    assertThat(plan).isInstanceOf(MultiAnchorDescriptor.StartPlan.MultiLiteral.class);
    Utf8StartAccelerator accelerator = Utf8StartAccelerator.create(plan, false);
    if (VectorScanProviders.multiLiteralProviderAvailable()) {
      assertThat(accelerator).isInstanceOf(Utf8StartAccelerator.MultiLiteral.class);
    } else if (VectorScanProviders.teddyProviderAvailable()) {
      assertThat(accelerator).isInstanceOf(Utf8StartAccelerator.Teddy.class);
    } else {
      assertThat(accelerator).isNull();
    }
  }

  @Test
  void literalPrefixAcceleratesStringAndUtf8() {
    MultiAnchorDescriptor.StartPlan plan = plan("needle", false, null, null);

    StringStartAccelerator strAcc = StringStartAccelerator.create(plan, false);
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.Literal.class);
    assertThat(strAcc.policy()).isEqualTo(AcceleratorPolicy.LITERAL);
    assertThat(
            StringStartAccelerator.findNextCandidate(strAcc, "haystack with needle here", 0, false))
        .isEqualTo(14);
    assertThat(
            StringStartAccelerator.findNextCandidate(
                strAcc, "haystack with needle here", 15, false))
        .isEqualTo(-1);

    Utf8StartAccelerator utf8Acc = Utf8StartAccelerator.create(plan, false);
    assertThat(utf8Acc).isInstanceOf(Utf8StartAccelerator.Literal.class);
    assertThat(utf8Acc.policy()).isEqualTo(AcceleratorPolicy.LITERAL);
    assertThat(
            Utf8StartAccelerator.findNextCandidate(
                utf8Acc, utf8Scanner("haystack with needle here"), 0))
        .isEqualTo(14);
    assertThat(
            Utf8StartAccelerator.findNextCandidate(
                utf8Acc, utf8Scanner("haystack with needle here"), 15))
        .isEqualTo(-1);
  }

  @Test
  void caseInsensitiveLiteralAcceleratesStringAndUtf8() {
    MultiAnchorDescriptor.StartPlan plan = plan("needle", true, null, null);

    StringStartAccelerator strAcc = StringStartAccelerator.create(plan, false);
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.CaseInsensitiveLiteral.class);
    assertThat(
            StringStartAccelerator.findNextCandidate(strAcc, "haystack with NEEDLE here", 0, false))
        .isEqualTo(14);

    Utf8StartAccelerator utf8Acc = Utf8StartAccelerator.create(plan, false);
    assertThat(utf8Acc).isInstanceOf(Utf8StartAccelerator.CaseInsensitiveLiteral.class);
    assertThat(utf8Acc.policy().strategy()).isEqualTo(MatchStrategy.LITERAL);
    assertThat(utf8Acc.policy().isExactMatchCandidate()).isTrue();
    assertThat(
            Utf8StartAccelerator.findNextCandidate(
                utf8Acc, utf8Scanner("haystack with NEEDLE here"), 0))
        .isEqualTo(14);
    assertThat(
            Utf8StartAccelerator.findNextCandidate(
                utf8Acc, utf8Scanner("haystack with nEeDlE here"), 0))
        .isEqualTo(14);
    assertThat(
            Utf8StartAccelerator.findNextCandidate(
                utf8Acc, utf8Scanner("haystack with needle here"), 15))
        .isEqualTo(-1);

    // Single character case-insensitive prefix
    MultiAnchorDescriptor.StartPlan singleDesc = plan("a", true, null, null);
    Utf8StartAccelerator singleUtf8 = Utf8StartAccelerator.create(singleDesc, false);
    assertThat(singleUtf8).isInstanceOf(Utf8StartAccelerator.CaseInsensitiveLiteral.class);
    assertThat(Utf8StartAccelerator.findNextCandidate(singleUtf8, utf8Scanner("xxxA"), 0))
        .isEqualTo(3);
    assertThat(Utf8StartAccelerator.findNextCandidate(singleUtf8, utf8Scanner("xxxa"), 0))
        .isEqualTo(3);

    // Non-ASCII case-insensitive prefix falls back (null)
    MultiAnchorDescriptor.StartPlan nonAsciiDesc = plan("café", true, null, null);
    assertThat(Utf8StartAccelerator.create(nonAsciiDesc, false)).isNull();
  }

  @Test
  void fixedOffsetLiteralAcceleratesStringAndUtf8() {
    FixedOffsetLiteral fixed = new FixedOffsetLiteral("token", 2, 2, new int[] {2});
    MultiAnchorDescriptor.StartPlan plan = plan(null, false, fixed, null);

    StringStartAccelerator strAcc = StringStartAccelerator.create(plan, false);
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.FixedOffset.class);
    assertThat(strAcc.policy()).isEqualTo(AcceleratorPolicy.LITERAL);
    assertThat(StringStartAccelerator.findNextCandidate(strAcc, "abtoken cd", 0, false))
        .isEqualTo(0);

    Utf8StartAccelerator utf8Acc = Utf8StartAccelerator.create(plan, false);
    assertThat(utf8Acc).isInstanceOf(Utf8StartAccelerator.FixedOffset.class);
    assertThat(utf8Acc.policy()).isEqualTo(AcceleratorPolicy.LITERAL);
    assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner("abtoken cd"), 0))
        .isEqualTo(0);
  }

  @Test
  void charClassPrefixAcceleratesStringAndUtf8() {
    CharClassScanInfo pairScanInfo = Pattern.compile("[ab]").charClassPrefix();
    MultiAnchorDescriptor.StartPlan descPair = plan(null, false, null, pairScanInfo);

    StringStartAccelerator strAcc = StringStartAccelerator.create(descPair, false);
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.CharClass.class);
    assertThat(strAcc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS);
    assertThat(StringStartAccelerator.findNextCandidate(strAcc, "xxxa", 0, false)).isEqualTo(3);

    Utf8StartAccelerator utf8PairAcc = Utf8StartAccelerator.create(descPair, false);
    assertThat(utf8PairAcc).isInstanceOf(Utf8StartAccelerator.CharClass.class);
    assertThat(((Utf8StartAccelerator.CharClass) utf8PairAcc).scanInfo())
        .isInstanceOf(CharClassScanInfo.AsciiSmallSet.class);
    assertThat(utf8PairAcc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS);
    assertThat(Utf8StartAccelerator.findNextCandidate(utf8PairAcc, utf8Scanner("xxxb"), 0))
        .isEqualTo(3);

    CharClassScanInfo tripleScanInfo = Pattern.compile("[abc]").charClassPrefix();
    MultiAnchorDescriptor.StartPlan planTriple = plan(null, false, null, tripleScanInfo);
    Utf8StartAccelerator utf8TripleAcc = Utf8StartAccelerator.create(planTriple, false);
    assertThat(utf8TripleAcc).isInstanceOf(Utf8StartAccelerator.CharClass.class);
    assertThat(utf8TripleAcc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS);
    assertThat(Utf8StartAccelerator.findNextCandidate(utf8TripleAcc, utf8Scanner("xxxc"), 0))
        .isEqualTo(3);

    CharClassScanInfo multiScanInfo = Pattern.compile("[abcd]").charClassPrefix();
    MultiAnchorDescriptor.StartPlan descMulti = plan(null, false, null, multiScanInfo);
    assertThat(Utf8StartAccelerator.create(descMulti, false)).isNull();
    assertThat(StringStartAccelerator.create(descMulti, false)).isNull();

    CharClassScanInfo denseUnicodeScanInfo = Pattern.compile("[0-9é]").charClassPrefix();
    MultiAnchorDescriptor.StartPlan denseUnicode = plan(null, false, null, denseUnicodeScanInfo);
    assertThat(Utf8StartAccelerator.create(denseUnicode, false)).isNull();
    assertThat(StringStartAccelerator.create(denseUnicode, false)).isNull();

    CharClassScanInfo sparseUnicodeScanInfo = Pattern.compile("[aé]").charClassPrefix();
    MultiAnchorDescriptor.StartPlan sparseUnicode = plan(null, false, null, sparseUnicodeScanInfo);
    assertThat(Utf8StartAccelerator.create(sparseUnicode, false)).isNotNull();
    assertThat(StringStartAccelerator.create(sparseUnicode, false)).isNotNull();

    CharClassScanInfo nonAsciiScanInfo = Pattern.compile("[éê]").charClassPrefix();
    MultiAnchorDescriptor.StartPlan nonAscii = plan(null, false, null, nonAsciiScanInfo);
    assertThat(Utf8StartAccelerator.create(nonAscii, false)).isNotNull();
    assertThat(StringStartAccelerator.create(nonAscii, false)).isNotNull();
  }

  private static MultiAnchorDescriptor.StartPlan plan(
      String prefix,
      boolean prefixFoldCase,
      FixedOffsetLiteral fixedOffsetLiteral,
      CharClassScanInfo charClassPrefix) {
    if (prefix != null) {
      ClassHashChain chain = prefixFoldCase ? ClassHashChain.compileCaseInsensitive(prefix) : null;
      return new MultiAnchorDescriptor.StartPlan.Literal(prefix, prefixFoldCase, chain);
    }
    if (fixedOffsetLiteral != null) {
      return new MultiAnchorDescriptor.StartPlan.FixedOffset(fixedOffsetLiteral, charClassPrefix);
    }
    if (charClassPrefix != null) {
      return new MultiAnchorDescriptor.StartPlan.CharClass(charClassPrefix);
    }
    return MultiAnchorDescriptor.StartPlan.None.INSTANCE;
  }

  @Test
  void unicodeCharClassPrefixAcceleratesStringAndUtf8() {
    Pattern pattern = Pattern.compile("[aéĀ]+");
    StringStartAccelerator strAcc = pattern.stringStartAccelerator();
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.CharClass.class);
    assertThat(strAcc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS);
    assertThat(StringStartAccelerator.findNextCandidate(strAcc, "123\u00e945", 0, false))
        .isEqualTo(3);

    Utf8StartAccelerator utf8Acc = pattern.utf8StartAccelerator();
    assertThat(utf8Acc).isInstanceOf(Utf8StartAccelerator.CharClass.class);
    assertThat(utf8Acc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS);
    assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner("123\u00e945"), 0))
        .isEqualTo(3);
  }

  @Test
  void supplementaryUnicodeCharClassPrefixAcceleratesStringAndUtf8() {
    Pattern pattern = Pattern.compile("[(é)|(😀)]");
    StringStartAccelerator strAcc = pattern.stringStartAccelerator();
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.CharClass.class);
    assertThat(strAcc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS);
    assertThat(StringStartAccelerator.findNextCandidate(strAcc, "x\uD83D\uDE00y", 0, false))
        .isEqualTo(1);

    Utf8StartAccelerator utf8Acc = pattern.utf8StartAccelerator();
    assertThat(utf8Acc).isInstanceOf(Utf8StartAccelerator.CharClass.class);
    assertThat(utf8Acc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS);
    assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner("x😀y"), 0))
        .isEqualTo(1);
  }

  @Test
  void unicodeCharClassPrefixResumesAsciiScanningAfterNonAsciiCodePoints() {
    StringStartAccelerator accelerator = Pattern.compile("[aéĀ]+").stringStartAccelerator();

    assertThat(StringStartAccelerator.findNextCandidate(accelerator, "000©000", 0, false))
        .isEqualTo(-1);
    assertThat(StringStartAccelerator.findNextCandidate(accelerator, "000©000Ā", 0, false))
        .isEqualTo(7);
    assertThat(StringStartAccelerator.findNextCandidate(accelerator, "000😀000a", 0, false))
        .isEqualTo(8);
    assertThat(StringStartAccelerator.findNextCandidate(accelerator, "000😀000a", 4, false))
        .isEqualTo(8);
  }

  @Test
  void compiledPatternAcceleratorsInSync() {
    String[] testPatterns = {
      "(?i)needle.*", "(?i)a.*", "(?i)HTTP://.*", "needle.*", "[a-z].*", "[0-9].*", "ab+c.*"
    };

    String[] testInputs = {
      "prefix with NEEDLE in middle",
      "prefix with needle in middle",
      "prefix with nEeDlE in middle",
      "prefix with no match",
      "HTTP://EXAMPLE.COM",
      "http://example.com",
      "123 numbers",
      "letters abc"
    };

    for (String patStr : testPatterns) {
      Pattern pattern = Pattern.compile(patStr);
      StringStartAccelerator strAcc = pattern.stringStartAccelerator();
      Utf8StartAccelerator utf8Acc = pattern.utf8StartAccelerator();

      if (strAcc != null) {
        assertThat(utf8Acc)
            .as(
                "Utf8StartAccelerator should match StringStartAccelerator presence for pattern: %s",
                patStr)
            .isNotNull();
        assertThat(utf8Acc.policy().strategy())
            .as("Strategies should match for pattern: %s", patStr)
            .isEqualTo(strAcc.policy().strategy());

        for (String input : testInputs) {
          int strCandidate = StringStartAccelerator.findNextCandidate(strAcc, input, 0, false);
          int utf8Candidate =
              Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner(input), 0);
          assertThat(utf8Candidate)
              .as("Candidate indices should match for pattern '%s' on input '%s'", patStr, input)
              .isEqualTo(strCandidate);
        }
      }
    }
  }

  @Test
  void compiledPatternPoliciesMatchExpectedAccelerators() {
    Pattern literalPat = Pattern.compile("abc");
    assertThat(literalPat.stringStartAccelerator()).isNotNull();
    assertThat(literalPat.utf8StartAccelerator()).isNotNull();
    assertThat(literalPat.stringStartAccelerator().policy()).isEqualTo(AcceleratorPolicy.LITERAL);
    assertThat(literalPat.utf8StartAccelerator().policy()).isEqualTo(AcceleratorPolicy.LITERAL);

    Pattern caseInsensitivePat = Pattern.compile("(?i)abc");
    assertThat(caseInsensitivePat.stringStartAccelerator()).isNotNull();
    assertThat(caseInsensitivePat.utf8StartAccelerator()).isNotNull();
    assertThat(caseInsensitivePat.utf8StartAccelerator().policy().strategy())
        .isEqualTo(MatchStrategy.LITERAL);

    Pattern charClassPat = Pattern.compile("[0-2][a-z]+");
    assertThat(charClassPat.stringStartAccelerator()).isNotNull();
    assertThat(charClassPat.utf8StartAccelerator()).isNotNull();
    assertThat(charClassPat.stringStartAccelerator().policy())
        .isEqualTo(AcceleratorPolicy.CHAR_CLASS);
    assertThat(charClassPat.utf8StartAccelerator().policy())
        .isEqualTo(AcceleratorPolicy.CHAR_CLASS);

    Pattern broadCharClassPat = Pattern.compile("[0-9][a-z]+");
    assertThat(broadCharClassPat.stringStartAccelerator()).isNull();
    assertThat(broadCharClassPat.utf8StartAccelerator()).isNull();

    Pattern fixedOffsetPat = Pattern.compile("..needle");
    assertThat(fixedOffsetPat.stringStartAccelerator()).isNotNull();
    assertThat(fixedOffsetPat.utf8StartAccelerator()).isNotNull();
    assertThat(fixedOffsetPat.stringStartAccelerator().policy())
        .isEqualTo(AcceleratorPolicy.LITERAL);
    assertThat(fixedOffsetPat.utf8StartAccelerator().policy()).isEqualTo(AcceleratorPolicy.LITERAL);

    Pattern unacceleratedPat = Pattern.compile(".*");
    assertThat(unacceleratedPat.stringStartAccelerator()).isNull();
    assertThat(unacceleratedPat.utf8StartAccelerator()).isNull();
  }

  @Test
  void multiLiteralWithSharedPrefixReturnsNullAndFallsBackToTeddyOrNone() {
    MultiLiteralInfo info = MultiLiteralInfo.create(new String[] {"cat", "car"});
    assertThat(info).as("MultiLiteralInfo must reject colliding initial anchor chars").isNull();

    MultiLiteralInfo distinctInfo = MultiLiteralInfo.create(new String[] {"cat", "dog", "fox"});
    assertThat(distinctInfo).isNotNull();
    assertThat(distinctInfo.literals()).containsExactly("cat", "dog", "fox");
  }

  @Test
  void literalAlternationRetainsStringCharacterClassFallback() {
    Pattern pattern = Pattern.compile("apple|banana|cherry");

    assertThat(pattern.startPlan())
        .isInstanceOf(MultiAnchorDescriptor.StartPlan.MultiLiteral.class);
    assertThat(pattern.stringStartAccelerator())
        .isInstanceOf(StringStartAccelerator.CharClass.class);
  }

  @Test
  void multiLiteralScanReturnsUnsupportedWhenWorkLimitExhaustedOnNoise() {
    if (!isVectorApiAvailable()) {
      return;
    }
    String[] literals = new String[] {"APPLE", "BANANA", "CHERRY"};
    MultiLiteralInfo info = MultiLiteralInfo.create(literals);
    assertThat(info).isNotNull();

    byte[] denseNoise = "A B C A B C ".repeat(1000).getBytes(UTF_8);

    int result =
        ByteVectorScan.indexOfMultiLiteral(
            denseNoise,
            0,
            denseNoise.length,
            info.literals(),
            info.anchorChars(),
            info.anchorOffsets(),
            info.anchorRanges(),
            info.minLength(),
            0);

    assertThat(result)
        .as("Dense candidate false positives must exhaust WorkLimit and return UNSUPPORTED")
        .isEqualTo(VectorScanProvider.UNSUPPORTED);
  }

  @Test
  void adaptiveTeddySelectionUsesEstimatedCandidateVerificationCost() {
    assertThat(MultiLiteralSelectionPolicy.prefersTeddy(5L * 7, 64)).isTrue();
    assertThat(MultiLiteralSelectionPolicy.prefersTeddy(4L * 7, 64)).isFalse();
    assertThat(MultiLiteralSelectionPolicy.prefersTeddy(16L * 7, 256)).isFalse();
    assertThat(MultiLiteralSelectionPolicy.prefersTeddy(19L * 7, 256)).isTrue();
    assertThat(MultiLiteralSelectionPolicy.shouldObserve(255)).isTrue();
    assertThat(MultiLiteralSelectionPolicy.shouldObserve(256)).isFalse();
  }

  @Test
  void adaptiveTeddySelectionPreservesTheEarliestLiteralMatch() {
    if (!isVectorApiAvailable()) {
      return;
    }
    String[] literals = new String[] {"blossom", "sparkling", "twilight"};
    MultiLiteralInfo info = MultiLiteralInfo.create(literals);
    TeddyModel teddyModel = TeddyModel.compile(literals, 64);
    assertThat(info).isNotNull();
    assertThat(teddyModel).isNotNull();
    String prefix = "b s t ".repeat(12);
    byte[] input = (prefix + "sparkling then blossom").getBytes(UTF_8);

    int result =
        ByteVectorScan.indexOfMultiLiteral(
            input,
            0,
            input.length,
            info.literals(),
            info.anchorChars(),
            info.anchorOffsets(),
            info.anchorRanges(),
            info.minLength(),
            teddyModel,
            0);

    assertThat(result).isEqualTo(prefix.length());
  }

  private static Utf8InputScanner utf8Scanner(String text) {
    byte[] bytes = text.getBytes(UTF_8);
    return new Utf8InputScanner(bytes, 0, bytes.length);
  }

  @Test
  void leadingWhitespaceCharClassExpansionAcceleratesStringAndUtf8() {
    Pattern pattern = Pattern.compile("\\s*[\\[\\uff3b]\\d+[\\]\\uff3d]");
    MultiAnchorDescriptor.StartPlan plan = pattern.startPlan();
    assertThat(plan).isInstanceOf(MultiAnchorDescriptor.StartPlan.LeadingExpansion.class);

    StringStartAccelerator strAcc = pattern.stringStartAccelerator();
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.LeadingExpansion.class);
    assertThat(StringStartAccelerator.findNextCandidate(strAcc, "hello   [123] world", 0, false))
        .isEqualTo(5);
    assertThat(StringStartAccelerator.findNextCandidate(strAcc, "hello [123] world", 0, false))
        .isEqualTo(5);
    assertThat(StringStartAccelerator.findNextCandidate(strAcc, "[123] world", 0, false))
        .isEqualTo(0);
    assertThat(
            StringStartAccelerator.findNextCandidate(strAcc, "hello world without match", 0, false))
        .isEqualTo(-1);

    Utf8StartAccelerator utf8Acc = pattern.utf8StartAccelerator();
    assertThat(utf8Acc).isInstanceOf(Utf8StartAccelerator.LeadingExpansion.class);
    assertThat(
            Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner("hello   [123] world"), 0))
        .isEqualTo(5);
    assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner("hello [123] world"), 0))
        .isEqualTo(5);
    assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner("[123] world"), 0))
        .isEqualTo(0);
    assertThat(
            Utf8StartAccelerator.findNextCandidate(
                utf8Acc, utf8Scanner("hello world without match"), 0))
        .isEqualTo(-1);
  }

  @Test
  void leadingWhitespaceLiteralExpansionAcceleratesStringAndUtf8() {
    Pattern pattern = Pattern.compile("\\s+https?://\\w+");
    MultiAnchorDescriptor.StartPlan plan = pattern.startPlan();
    assertThat(plan).isInstanceOf(MultiAnchorDescriptor.StartPlan.LeadingExpansion.class);

    StringStartAccelerator strAcc = pattern.stringStartAccelerator();
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.LeadingExpansion.class);
    // Requires at least 1 leading whitespace
    assertThat(StringStartAccelerator.findNextCandidate(strAcc, "visit  http://example", 0, false))
        .isEqualTo(5);
    assertThat(
            StringStartAccelerator.findNextCandidate(
                strAcc, "http://example without leading space", 0, false))
        .isEqualTo(-1);

    Utf8StartAccelerator utf8Acc = pattern.utf8StartAccelerator();
    assertThat(utf8Acc).isInstanceOf(Utf8StartAccelerator.LeadingExpansion.class);
    assertThat(
            Utf8StartAccelerator.findNextCandidate(
                utf8Acc, utf8Scanner("visit  http://example"), 0))
        .isEqualTo(5);
    assertThat(
            Utf8StartAccelerator.findNextCandidate(
                utf8Acc, utf8Scanner("http://example without leading space"), 0))
        .isEqualTo(-1);
  }

  @Test
  void leadingBoundedUnicodeExpansionAcceleratesStringAndUtf8() {
    Pattern pattern = Pattern.compile("[\\u00e9\\u00e8]+:target");
    MultiAnchorDescriptor.StartPlan plan = pattern.startPlan();
    assertThat(plan).isInstanceOf(MultiAnchorDescriptor.StartPlan.LeadingExpansion.class);
    MultiAnchorDescriptor.StartPlan.LeadingExpansion le =
        (MultiAnchorDescriptor.StartPlan.LeadingExpansion) plan;
    assertThat(le.minRepetition()).isEqualTo(1);
    assertThat(le.maxRepetition()).isEqualTo(Integer.MAX_VALUE);

    StringStartAccelerator strAcc = pattern.stringStartAccelerator();
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.LeadingExpansion.class);
    // 5 \u00e9 chars before :target
    assertThat(
            StringStartAccelerator.findNextCandidate(
                strAcc, "prefix\u00e9\u00e9\u00e9\u00e9\u00e9:target", 0, false))
        .isEqualTo(6);
    // 1 \u00e9 char
    assertThat(StringStartAccelerator.findNextCandidate(strAcc, "prefix\u00e9:target", 0, false))
        .isEqualTo(6);
    // 0 \u00e9 chars -> fails minRepetition check (1)
    assertThat(StringStartAccelerator.findNextCandidate(strAcc, "prefix:target", 0, false))
        .isEqualTo(-1);

    Utf8StartAccelerator utf8Acc = pattern.utf8StartAccelerator();
    assertThat(utf8Acc).isInstanceOf(Utf8StartAccelerator.LeadingExpansion.class);
    // In UTF-8, "prefix" is 6 bytes. Each \u00e9 is 2 bytes (0xC3 0xA9).
    // ":target" starts at byte 6 + (5 * 2) = 16.
    // Leftmost \u00e9 is at byte 6.
    assertThat(
            Utf8StartAccelerator.findNextCandidate(
                utf8Acc, utf8Scanner("prefix\u00e9\u00e9\u00e9\u00e9\u00e9:target"), 0))
        .isEqualTo(6);
    // 1 code point -> byte 6
    assertThat(
            Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner("prefix\u00e9:target"), 0))
        .isEqualTo(6);
    // 0 code points -> fails minRepetition
    assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner("prefix:target"), 0))
        .isEqualTo(-1);
  }

  @Test
  void leadingSupplementaryUnicodeExpansionAcceleratesStringAndUtf8() {
    // Supplementary code point class: U+1F600, U+1F601 (Grinning Face, Beaming Face)
    Pattern pattern = Pattern.compile("[\\x{1F600}\\x{1F601}]+:target");
    MultiAnchorDescriptor.StartPlan plan = pattern.startPlan();
    assertThat(plan).isInstanceOf(MultiAnchorDescriptor.StartPlan.LeadingExpansion.class);

    String emoji4 =
        new StringBuilder("abc")
            .appendCodePoint(0x1F600)
            .appendCodePoint(0x1F601)
            .appendCodePoint(0x1F600)
            .appendCodePoint(0x1F601)
            .append(":target")
            .toString();
    String noEmoji = "abc:target";

    StringStartAccelerator strAcc = pattern.stringStartAccelerator();
    // In UTF-16, "abc" is 3 chars. Each emoji is 2 chars (surrogate pair).
    // "abc...:target" -> Leftmost emoji is at index 3.
    assertThat(StringStartAccelerator.findNextCandidate(strAcc, emoji4, 0, false)).isEqualTo(3);
    // 0 emoji -> fails minRepetition (1)
    assertThat(StringStartAccelerator.findNextCandidate(strAcc, noEmoji, 0, false)).isEqualTo(-1);

    Utf8StartAccelerator utf8Acc = pattern.utf8StartAccelerator();
    // In UTF-8, "abc" is 3 bytes. Each emoji is 4 bytes.
    // Leftmost emoji is at byte 3.
    assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner(emoji4), 0))
        .isEqualTo(3);
    assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner(noEmoji), 0))
        .isEqualTo(-1);
  }

  @Test
  void consecutiveLeadingRepetitionsDoNotOverflowDuringCompilation() {
    StringBuilder regex = new StringBuilder();
    for (int i = 0; i < 5_000; i++) {
      regex.append((i & 1) == 0 ? "[ab]*" : "[cd]*");
    }
    regex.append('z');

    assertThatCode(() -> Pattern.compile(regex.toString())).doesNotThrowAnyException();
  }

  private static boolean isVectorApiAvailable() {
    try {
      Class.forName("jdk.incubator.vector.ByteVector");
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }
}
