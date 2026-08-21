// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.safere.Pattern.FixedOffsetLiteral;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class StartAcceleratorTest {

  @Test
  void nullAndNoneDescriptorsProduceNullAccelerators() {
    assertThat(StringStartAccelerator.create(null, false)).isNull();
    assertThat(StringStartAccelerator.create(StartDescriptor.NONE, false)).isNull();
    assertThat(Utf8StartAccelerator.create(null, false)).isNull();
    assertThat(Utf8StartAccelerator.create(StartDescriptor.NONE, false)).isNull();
    assertThat(StartDescriptor.NONE.hasStartAcceleration()).isFalse();
  }

  @Test
  void literalPrefixAcceleratesStringAndUtf8() {
    StartDescriptor desc = descriptor("needle", false, null, null);
    assertThat(desc.hasStartAcceleration()).isTrue();

    StringStartAccelerator strAcc = StringStartAccelerator.create(desc, false);
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.Literal.class);
    assertThat(strAcc.policy()).isEqualTo(AcceleratorPolicy.LITERAL);
    assertThat(
            StringStartAccelerator.findNextCandidate(strAcc, "haystack with needle here", 0, false))
        .isEqualTo(14);
    assertThat(
            StringStartAccelerator.findNextCandidate(
                strAcc, "haystack with needle here", 15, false))
        .isEqualTo(-1);

    Utf8StartAccelerator utf8Acc = Utf8StartAccelerator.create(desc, false);
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
    StartDescriptor desc = descriptor("needle", true, null, null);
    assertThat(desc.hasStartAcceleration()).isTrue();

    StringStartAccelerator strAcc = StringStartAccelerator.create(desc, false);
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.CaseInsensitiveLiteral.class);
    assertThat(
            StringStartAccelerator.findNextCandidate(strAcc, "haystack with NEEDLE here", 0, false))
        .isEqualTo(14);

    Utf8StartAccelerator utf8Acc = Utf8StartAccelerator.create(desc, false);
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
    StartDescriptor singleDesc = descriptor("a", true, null, null);
    Utf8StartAccelerator singleUtf8 = Utf8StartAccelerator.create(singleDesc, false);
    assertThat(singleUtf8).isInstanceOf(Utf8StartAccelerator.CaseInsensitiveLiteral.class);
    assertThat(Utf8StartAccelerator.findNextCandidate(singleUtf8, utf8Scanner("xxxA"), 0))
        .isEqualTo(3);
    assertThat(Utf8StartAccelerator.findNextCandidate(singleUtf8, utf8Scanner("xxxa"), 0))
        .isEqualTo(3);

    // Non-ASCII case-insensitive prefix falls back (null)
    StartDescriptor nonAsciiDesc = descriptor("café", true, null, null);
    assertThat(Utf8StartAccelerator.create(nonAsciiDesc, false)).isNull();
  }

  @Test
  void fixedOffsetLiteralAcceleratesStringAndUtf8() {
    FixedOffsetLiteral fixed = new FixedOffsetLiteral("token", 2, 2, new int[] {2});
    StartDescriptor desc = descriptor(null, false, fixed, null);

    StringStartAccelerator strAcc = StringStartAccelerator.create(desc, false);
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.FixedOffset.class);
    assertThat(strAcc.policy()).isEqualTo(AcceleratorPolicy.LITERAL);
    assertThat(StringStartAccelerator.findNextCandidate(strAcc, "abtoken cd", 0, false))
        .isEqualTo(0);

    Utf8StartAccelerator utf8Acc = Utf8StartAccelerator.create(desc, false);
    assertThat(utf8Acc).isInstanceOf(Utf8StartAccelerator.FixedOffset.class);
    assertThat(utf8Acc.policy()).isEqualTo(AcceleratorPolicy.LITERAL);
    assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner("abtoken cd"), 0))
        .isEqualTo(0);
  }

  @Test
  void charClassPrefixAcceleratesStringAndUtf8() {
    CharClassScanInfo pairScanInfo = Pattern.compile("[ab]").charClassPrefix();
    StartDescriptor descPair = descriptor(null, false, null, pairScanInfo);

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

    CharClassScanInfo multiScanInfo = Pattern.compile("[abcd]").charClassPrefix();
    StartDescriptor descMulti = descriptor(null, false, null, multiScanInfo);
    Utf8StartAccelerator utf8MultiAcc = Utf8StartAccelerator.create(descMulti, false);
    assertThat(utf8MultiAcc).isInstanceOf(Utf8StartAccelerator.CharClass.class);
    assertThat(utf8MultiAcc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS);
    assertThat(Utf8StartAccelerator.findNextCandidate(utf8MultiAcc, utf8Scanner("xxxd"), 0))
        .isEqualTo(3);
  }

  private static StartDescriptor descriptor(
      String prefix,
      boolean prefixFoldCase,
      FixedOffsetLiteral fixedOffsetLiteral,
      CharClassScanInfo charClassPrefix) {
    return new StartDescriptor(
        prefix, prefixFoldCase, fixedOffsetLiteral, charClassPrefix, null, null, null, null);
  }

  @Test
  void unicodeCharClassPrefixAcceleratesStringAndUtf8() {
    Pattern pattern = Pattern.compile("[\\p{IsAlphabetic}]+");
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
    StringStartAccelerator accelerator =
        Pattern.compile("[\\p{IsAlphabetic}]+").stringStartAccelerator();

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

    Pattern charClassPat = Pattern.compile("[0-9][a-z]+");
    assertThat(charClassPat.stringStartAccelerator()).isNotNull();
    assertThat(charClassPat.utf8StartAccelerator()).isNotNull();
    assertThat(charClassPat.stringStartAccelerator().policy())
        .isEqualTo(AcceleratorPolicy.CHAR_CLASS);
    assertThat(charClassPat.utf8StartAccelerator().policy())
        .isEqualTo(AcceleratorPolicy.CHAR_CLASS);

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

  private static Utf8InputScanner utf8Scanner(String text) {
    byte[] bytes = text.getBytes(UTF_8);
    return new Utf8InputScanner(bytes, 0, bytes.length);
  }
}
