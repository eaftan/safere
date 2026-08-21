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
    assertThat(strAcc.findCandidate("haystack with needle here", 0, false)).isEqualTo(14);
    assertThat(strAcc.findCandidate("haystack with needle here", 15, false)).isEqualTo(-1);

    Utf8StartAccelerator utf8Acc = Utf8StartAccelerator.create(desc, false);
    assertThat(utf8Acc).isInstanceOf(Utf8StartAccelerator.Literal.class);
    assertThat(utf8Acc.policy()).isEqualTo(AcceleratorPolicy.LITERAL);
    assertThat(utf8Acc.findCandidate(utf8Scanner("haystack with needle here"), 0)).isEqualTo(14);
    assertThat(utf8Acc.findCandidate(utf8Scanner("haystack with needle here"), 15)).isEqualTo(-1);
  }

  @Test
  void caseInsensitiveLiteralAcceleratesStringAndUtf8() {
    StartDescriptor desc = descriptor("needle", true, null, null);
    assertThat(desc.hasStartAcceleration()).isTrue();

    StringStartAccelerator strAcc = StringStartAccelerator.create(desc, false);
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.Literal.class);
    assertThat(strAcc.findCandidate("haystack with NEEDLE here", 0, false)).isEqualTo(14);

    Utf8StartAccelerator utf8Acc = Utf8StartAccelerator.create(desc, false);
    assertThat(utf8Acc).isInstanceOf(Utf8StartAccelerator.CaseInsensitiveLiteral.class);
    assertThat(utf8Acc.policy().strategy()).isEqualTo(MatchStrategy.LITERAL);
    assertThat(utf8Acc.policy().isExactMatchCandidate()).isTrue();
    assertThat(utf8Acc.findCandidate(utf8Scanner("haystack with NEEDLE here"), 0)).isEqualTo(14);
    assertThat(utf8Acc.findCandidate(utf8Scanner("haystack with nEeDlE here"), 0)).isEqualTo(14);
    assertThat(utf8Acc.findCandidate(utf8Scanner("haystack with needle here"), 15)).isEqualTo(-1);

    // Single character case-insensitive prefix
    StartDescriptor singleDesc = descriptor("a", true, null, null);
    Utf8StartAccelerator singleUtf8 = Utf8StartAccelerator.create(singleDesc, false);
    assertThat(singleUtf8).isInstanceOf(Utf8StartAccelerator.CaseInsensitiveLiteral.class);
    assertThat(singleUtf8.findCandidate(utf8Scanner("xxxA"), 0)).isEqualTo(3);
    assertThat(singleUtf8.findCandidate(utf8Scanner("xxxa"), 0)).isEqualTo(3);

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
    assertThat(strAcc.findCandidate("abtoken cd", 0, false)).isEqualTo(0);

    Utf8StartAccelerator utf8Acc = Utf8StartAccelerator.create(desc, false);
    assertThat(utf8Acc).isInstanceOf(Utf8StartAccelerator.FixedOffset.class);
    assertThat(utf8Acc.policy()).isEqualTo(AcceleratorPolicy.LITERAL);
    assertThat(utf8Acc.findCandidate(utf8Scanner("abtoken cd"), 0)).isEqualTo(0);
  }

  @Test
  void charClassPrefixAcceleratesStringAndUtf8() {
    CharClassScanInfo pairScanInfo = Pattern.compile("[ab]").charClassPrefix();
    StartDescriptor descPair = descriptor(null, false, null, pairScanInfo);

    StringStartAccelerator strAcc = StringStartAccelerator.create(descPair, false);
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.CharClass.class);
    assertThat(strAcc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS);
    assertThat(strAcc.findCandidate("xxxa", 0, false)).isEqualTo(3);

    Utf8StartAccelerator utf8PairAcc = Utf8StartAccelerator.create(descPair, false);
    assertThat(utf8PairAcc).isInstanceOf(Utf8StartAccelerator.CharClass.class);
    assertThat(((Utf8StartAccelerator.CharClass) utf8PairAcc).scanInfo())
        .isInstanceOf(CharClassScanInfo.AsciiSmallSet.class);
    assertThat(utf8PairAcc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS);
    assertThat(utf8PairAcc.findCandidate(utf8Scanner("xxxb"), 0)).isEqualTo(3);

    CharClassScanInfo multiScanInfo = Pattern.compile("[abcd]").charClassPrefix();
    StartDescriptor descMulti = descriptor(null, false, null, multiScanInfo);
    Utf8StartAccelerator utf8MultiAcc = Utf8StartAccelerator.create(descMulti, false);
    assertThat(utf8MultiAcc).isInstanceOf(Utf8StartAccelerator.CharClass.class);
    assertThat(utf8MultiAcc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS);
    assertThat(utf8MultiAcc.findCandidate(utf8Scanner("xxxd"), 0)).isEqualTo(3);
  }

  private static StartDescriptor descriptor(
      String prefix,
      boolean prefixFoldCase,
      FixedOffsetLiteral fixedOffsetLiteral,
      CharClassScanInfo charClassPrefix) {
    return new StartDescriptor(
        prefix, prefixFoldCase, fixedOffsetLiteral, charClassPrefix, null, null, null, null, null);
  }

  @Test
  void unicodeCharClassPrefixAcceleratesStringAndUtf8() {
    Pattern pattern = Pattern.compile("[\\p{IsAlphabetic}]+");
    StringStartAccelerator strAcc = pattern.stringStartAccelerator();
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.CharClass.class);
    assertThat(strAcc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS);
    assertThat(strAcc.findCandidate("123\u00e945", 0, false)).isEqualTo(3);

    Utf8StartAccelerator utf8Acc = pattern.utf8StartAccelerator();
    assertThat(utf8Acc).isInstanceOf(Utf8StartAccelerator.CharClass.class);
    assertThat(utf8Acc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS);
    assertThat(utf8Acc.findCandidate(utf8Scanner("123\u00e945"), 0)).isEqualTo(3);
  }

  @Test
  void supplementaryUnicodeCharClassPrefixAcceleratesStringAndUtf8() {
    Pattern pattern = Pattern.compile("[(é)|(😀)]");
    StringStartAccelerator strAcc = pattern.stringStartAccelerator();
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.CharClass.class);
    assertThat(strAcc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS);
    assertThat(strAcc.findCandidate("x\uD83D\uDE00y", 0, false)).isEqualTo(1);

    Utf8StartAccelerator utf8Acc = pattern.utf8StartAccelerator();
    assertThat(utf8Acc).isInstanceOf(Utf8StartAccelerator.CharClass.class);
    assertThat(utf8Acc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS);
    assertThat(utf8Acc.findCandidate(utf8Scanner("x😀y"), 0)).isEqualTo(1);
  }

  @Test
  void unicodeCharClassPrefixResumesAsciiScanningAfterNonAsciiCodePoints() {
    StringStartAccelerator accelerator =
        Pattern.compile("[\\p{IsAlphabetic}]+").stringStartAccelerator();

    assertThat(accelerator.findCandidate("000©000", 0, false)).isEqualTo(-1);
    assertThat(accelerator.findCandidate("000©000Ā", 0, false)).isEqualTo(7);
    assertThat(accelerator.findCandidate("000😀000a", 0, false)).isEqualTo(8);
    assertThat(accelerator.findCandidate("000😀000a", 4, false)).isEqualTo(8);
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
          int strCandidate = strAcc.findCandidate(input, 0, false);
          int utf8Candidate = utf8Acc.findCandidate(utf8Scanner(input), 0);
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

  @Test
  void leadingWhitespaceCharClassExpansionAcceleratesStringAndUtf8() {
    Pattern pattern = Pattern.compile("\\s*[\\[\\uff3b]\\d+[\\]\\uff3d]");
    StartDescriptor desc = pattern.startDescriptor();
    assertThat(desc.hasStartAcceleration()).isTrue();
    assertThat(desc.leadingExpansion()).isNotNull();

    StringStartAccelerator strAcc = pattern.stringStartAccelerator();
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.LeadingExpansion.class);
    assertThat(strAcc.findCandidate("hello   [123] world", 0, false)).isEqualTo(5);
    assertThat(strAcc.findCandidate("hello [123] world", 0, false)).isEqualTo(5);
    assertThat(strAcc.findCandidate("[123] world", 0, false)).isEqualTo(0);
    assertThat(strAcc.findCandidate("hello world without match", 0, false)).isEqualTo(-1);

    Utf8StartAccelerator utf8Acc = pattern.utf8StartAccelerator();
    assertThat(utf8Acc).isInstanceOf(Utf8StartAccelerator.LeadingExpansion.class);
    assertThat(utf8Acc.findCandidate(utf8Scanner("hello   [123] world"), 0)).isEqualTo(5);
    assertThat(utf8Acc.findCandidate(utf8Scanner("hello [123] world"), 0)).isEqualTo(5);
    assertThat(utf8Acc.findCandidate(utf8Scanner("[123] world"), 0)).isEqualTo(0);
    assertThat(utf8Acc.findCandidate(utf8Scanner("hello world without match"), 0)).isEqualTo(-1);
  }

  @Test
  void leadingWhitespaceLiteralExpansionAcceleratesStringAndUtf8() {
    Pattern pattern = Pattern.compile("\\s+https?://\\w+");
    StartDescriptor desc = pattern.startDescriptor();
    assertThat(desc.hasStartAcceleration()).isTrue();
    assertThat(desc.leadingExpansion()).isNotNull();

    StringStartAccelerator strAcc = pattern.stringStartAccelerator();
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.LeadingExpansion.class);
    // Requires at least 1 leading whitespace
    assertThat(strAcc.findCandidate("visit  http://example", 0, false)).isEqualTo(5);
    assertThat(strAcc.findCandidate("http://example without leading space", 0, false))
        .isEqualTo(-1);

    Utf8StartAccelerator utf8Acc = pattern.utf8StartAccelerator();
    assertThat(utf8Acc).isInstanceOf(Utf8StartAccelerator.LeadingExpansion.class);
    assertThat(utf8Acc.findCandidate(utf8Scanner("visit  http://example"), 0)).isEqualTo(5);
    assertThat(utf8Acc.findCandidate(utf8Scanner("http://example without leading space"), 0))
        .isEqualTo(-1);
  }

  @Test
  void leadingBoundedUnicodeExpansionAcceleratesStringAndUtf8() {
    Pattern pattern = Pattern.compile("[\\u00e9\\u00e8]+:target");
    StartDescriptor desc = pattern.startDescriptor();
    assertThat(desc.hasStartAcceleration()).isTrue();
    assertThat(desc.leadingExpansion()).isNotNull();
    assertThat(desc.leadingExpansion().minRepetition()).isEqualTo(1);
    assertThat(desc.leadingExpansion().maxRepetition()).isEqualTo(Integer.MAX_VALUE);

    StringStartAccelerator strAcc = pattern.stringStartAccelerator();
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.LeadingExpansion.class);
    // 5 \u00e9 chars before :target
    assertThat(strAcc.findCandidate("prefix\u00e9\u00e9\u00e9\u00e9\u00e9:target", 0, false))
        .isEqualTo(6);
    // 1 \u00e9 char
    assertThat(strAcc.findCandidate("prefix\u00e9:target", 0, false)).isEqualTo(6);
    // 0 \u00e9 chars -> fails minRepetition check (1)
    assertThat(strAcc.findCandidate("prefix:target", 0, false)).isEqualTo(-1);

    Utf8StartAccelerator utf8Acc = pattern.utf8StartAccelerator();
    assertThat(utf8Acc).isInstanceOf(Utf8StartAccelerator.LeadingExpansion.class);
    // In UTF-8, "prefix" is 6 bytes. Each \u00e9 is 2 bytes (0xC3 0xA9).
    // ":target" starts at byte 6 + (5 * 2) = 16.
    // Leftmost \u00e9 is at byte 6.
    assertThat(utf8Acc.findCandidate(utf8Scanner("prefix\u00e9\u00e9\u00e9\u00e9\u00e9:target"), 0))
        .isEqualTo(6);
    // 1 code point -> byte 6
    assertThat(utf8Acc.findCandidate(utf8Scanner("prefix\u00e9:target"), 0)).isEqualTo(6);
    // 0 code points -> fails minRepetition
    assertThat(utf8Acc.findCandidate(utf8Scanner("prefix:target"), 0)).isEqualTo(-1);
  }

  @Test
  void leadingSupplementaryUnicodeExpansionAcceleratesStringAndUtf8() {
    // Supplementary code point class: U+1F600, U+1F601 (Grinning Face, Beaming Face)
    Pattern pattern = Pattern.compile("[\\x{1F600}\\x{1F601}]+:target");
    StartDescriptor desc = pattern.startDescriptor();
    assertThat(desc.hasStartAcceleration()).isTrue();
    assertThat(desc.leadingExpansion()).isNotNull();

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
    assertThat(strAcc.findCandidate(emoji4, 0, false)).isEqualTo(3);
    // 0 emoji -> fails minRepetition (1)
    assertThat(strAcc.findCandidate(noEmoji, 0, false)).isEqualTo(-1);

    Utf8StartAccelerator utf8Acc = pattern.utf8StartAccelerator();
    // In UTF-8, "abc" is 3 bytes. Each emoji is 4 bytes.
    // Leftmost emoji is at byte 3.
    assertThat(utf8Acc.findCandidate(utf8Scanner(emoji4), 0)).isEqualTo(3);
    assertThat(utf8Acc.findCandidate(utf8Scanner(noEmoji), 0)).isEqualTo(-1);
  }
}
