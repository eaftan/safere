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
    if (VectorScanProviders.vectorProviderAvailable()) {
      assertThat(accelerator).isInstanceOf(Utf8StartAccelerator.MultiLiteral.class);
    } else if (VectorScanProviders.vectorProviderAvailable()) {
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
  void utf8LiteralPrefixFindsCandidatesAfterRepeatedFalsePrefixes() {
    String asciiPrefix = "aaaaQaaaa";
    Utf8StartAccelerator.Literal ascii =
        (Utf8StartAccelerator.Literal)
            Utf8StartAccelerator.create(plan(asciiPrefix, false, null, null), false);
    assertThat(ascii.rareByteOffset()).isEqualTo(4);
    Utf8InputScanner asciiScanner = utf8Scanner("aaaaYaaaa".repeat(100) + asciiPrefix + "1");
    assertThat(Utf8StartAccelerator.findNextCandidate(ascii, asciiScanner, 0)).isEqualTo(900);
    assertThat(Utf8StartAccelerator.findNextCandidate(ascii, asciiScanner, 901)).isEqualTo(-1);

    String unicodePrefix = "aaaaШaaaa";
    Utf8StartAccelerator.Literal unicode =
        (Utf8StartAccelerator.Literal)
            Utf8StartAccelerator.create(plan(unicodePrefix, false, null, null), false);
    assertThat(unicode.rareByteOffset()).isGreaterThan(0);
    Utf8InputScanner unicodeScanner = utf8Scanner("aaaaЮaaaa".repeat(100) + unicodePrefix + "1");
    assertThat(Utf8StartAccelerator.findNextCandidate(unicode, unicodeScanner, 0))
        .isEqualTo("aaaaЮaaaa".repeat(100).getBytes(UTF_8).length);
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

    // Non-ASCII case-insensitive prefixes use Unicode-aware accelerators for both inputs.
    MultiAnchorDescriptor.StartPlan nonAsciiDesc = plan("café", true, null, null);
    StringStartAccelerator unicodeStr = StringStartAccelerator.create(nonAsciiDesc, false);
    assertThat(unicodeStr).isInstanceOf(StringStartAccelerator.UnicodeCaseInsensitiveLiteral.class);
    assertThat(StringStartAccelerator.findNextCandidate(unicodeStr, "prefix cafE CAFÉ", 0, false))
        .isEqualTo(12);
    assertThat(StringStartAccelerator.findNextCandidate(unicodeStr, "prefix CAFÉ", 0, false))
        .isEqualTo(7);

    Utf8StartAccelerator unicodeUtf8 = Utf8StartAccelerator.create(nonAsciiDesc, false);
    assertThat(unicodeUtf8).isInstanceOf(Utf8StartAccelerator.UnicodeCaseInsensitiveLiteral.class);
    assertThat(
            Utf8StartAccelerator.findNextCandidate(
                unicodeUtf8, utf8Scanner("cafE CAFÉ caFé café"), 0))
        .isEqualTo("cafE ".getBytes(UTF_8).length);
  }

  @Test
  void unicodeCaseInsensitiveUtf8LiteralFindsOnlyFoldEquivalentPrefixCandidates() {
    Utf8StartAccelerator accelerator =
        Utf8StartAccelerator.create(plan("Шерлок Холмс", true, null, null), false);
    assertThat(accelerator).isInstanceOf(Utf8StartAccelerator.UnicodeCaseInsensitiveLiteral.class);

    String text = "Шерлок ХолмX шЕРЛОК хОЛМС ШЕРЛОК ХОЛМС";
    Utf8InputScanner scanner = utf8Scanner(text);
    int expected = "Шерлок ХолмX ".getBytes(UTF_8).length;
    assertThat(Utf8StartAccelerator.findNextCandidate(accelerator, scanner, 0)).isEqualTo(expected);
    assertThat(Utf8StartAccelerator.findNextCandidate(accelerator, scanner, expected + 1))
        .isEqualTo(expected + "шЕРЛОК хОЛМС ".getBytes(UTF_8).length);
    assertThat(Utf8StartAccelerator.findNextCandidate(accelerator, scanner, scanner.length()))
        .isEqualTo(-1);
  }

  @Test
  void unicodeCaseInsensitiveUtf8LiteralHandlesVariableWidthFoldBeforeLaterAnchor() {
    Utf8StartAccelerator accelerator =
        Utf8StartAccelerator.create(plan("Ké", true, null, null), false);
    assertThat(accelerator).isInstanceOf(Utf8StartAccelerator.UnicodeCaseInsensitiveLiteral.class);
    Utf8InputScanner scanner = utf8Scanner("x ké y KÉ z Ké");
    assertThat(Utf8StartAccelerator.findNextCandidate(accelerator, scanner, 0)).isEqualTo(2);
    assertThat(Utf8StartAccelerator.findNextCandidate(accelerator, scanner, 3))
        .isEqualTo("x ké y ".getBytes(UTF_8).length);
  }

  @Test
  void unicodeCaseInsensitiveUtf8LiteralFindsCandidatesAcrossScanWindows() {
    Utf8StartAccelerator accelerator =
        Utf8StartAccelerator.create(plan("Шx", true, null, null), false);
    String first = "x".repeat(255);
    String middle = "x".repeat(255);
    Utf8InputScanner scanner = utf8Scanner(first + "шx" + middle + "Шx");
    int second = (first + "шx" + middle).getBytes(UTF_8).length;

    assertThat(Utf8StartAccelerator.findNextCandidate(accelerator, scanner, 0)).isEqualTo(255);
    assertThat(Utf8StartAccelerator.findNextCandidate(accelerator, scanner, 256)).isEqualTo(second);
    assertThat(Utf8StartAccelerator.findNextCandidate(accelerator, scanner, second + 1))
        .isEqualTo(-1);
  }

  @Test
  void unicodeCaseInsensitiveUtf8LiteralPreservesFullFindSequence() {
    String[] patterns = {"(?iu)Шерлок Холмс", "(?iu)café", "(?iu)Ké", "(?iu)ϑϑ"};
    String[] inputs = {
      "шЕРЛОК хОЛМС Шерлок ХолмX ШЕРЛОК ХОЛМС", "CAFÉ cafe cAfÉ", "ké KÉ Ké", "ϑθ Θϑ ϑx"
    };
    EnginePathOptions noAcceleration = EnginePathOptions.builder().startAcceleration(false).build();
    for (int i = 0; i < patterns.length; i++) {
      Pattern accelerated = Pattern.compile(patterns[i]);
      Pattern control = Pattern.compile(patterns[i], 0, noAcceleration);
      if (i < 2) {
        assertThat(accelerated.utf8StartAccelerator())
            .as("UTF-8 accelerator for %s", patterns[i])
            .isInstanceOf(Utf8StartAccelerator.UnicodeCaseInsensitiveLiteral.class);
      }
      Utf8Input input = Utf8Input.validated(inputs[i].getBytes(UTF_8));
      Utf8Matcher actual = accelerated.matcher(input);
      Utf8Matcher expected = control.matcher(input);
      while (true) {
        boolean found = expected.find();
        assertThat(actual.find()).as("find for %s", patterns[i]).isEqualTo(found);
        if (!found) {
          break;
        }
        assertThat(actual.start()).as("start for %s", patterns[i]).isEqualTo(expected.start());
        assertThat(actual.end()).as("end for %s", patterns[i]).isEqualTo(expected.end());
      }
    }
  }

  @Test
  void unicodeCaseInsensitiveUtf8FindIncludesWholeCaseFamily() {
    String[] literals = {"éİ", "éİ", "éİ", "éİ", "éı", "éı", "éı", "éı", "éK", "éS"};
    String[] inputs = {"éİ", "éı", "éi", "éI", "éİ", "éı", "éi", "éI", "éK", "éſ"};
    EnginePathOptions noAcceleration = EnginePathOptions.builder().startAcceleration(false).build();
    for (int i = 0; i < literals.length; i++) {
      String regex = "(?iu)" + literals[i];
      assertThat(java.util.regex.Pattern.compile(regex).matcher(inputs[i]).find())
          .as("JDK find for %s against %s", regex, inputs[i])
          .isTrue();
      Utf8Input utf8 = Utf8Input.validated(inputs[i].getBytes(UTF_8));
      assertThat(Pattern.compile(regex, 0, noAcceleration).matcher(utf8).find())
          .as("unaccelerated UTF-8 find for %s against %s", regex, inputs[i])
          .isTrue();
      assertThat(Pattern.compile(regex).matcher(utf8).find())
          .as("accelerated UTF-8 find for %s against %s", regex, inputs[i])
          .isTrue();
    }
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
      return new MultiAnchorDescriptor.StartPlan.Literal(prefix, prefixFoldCase);
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

  @Test
  void discreteOffsetsResolveTheExactStartAgainstTheLeadingClass() {
    MultiAnchorDescriptor.StartPlan plan = Pattern.compile("https?://\\S*").startPlan();
    assertThat(plan).isInstanceOf(MultiAnchorDescriptor.StartPlan.FixedOffset.class);

    // `://` sits four characters after `h` in `http://` and five in `https://`. Retreating by the
    // larger offset alone lands on the space before `http://` and wakes the engine at a position
    // the leading class already excludes.
    String text = "see http://a and https://b";

    StringStartAccelerator strAcc = StringStartAccelerator.create(plan, false);
    assertThat(StringStartAccelerator.findNextCandidate(strAcc, text, 0, false)).isEqualTo(4);
    assertThat(StringStartAccelerator.findNextCandidate(strAcc, text, 5, false)).isEqualTo(17);

    Utf8StartAccelerator utf8Acc = Utf8StartAccelerator.create(plan, false);
    assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner(text), 0)).isEqualTo(4);
    assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner(text), 5)).isEqualTo(17);
  }

  @Test
  void discreteOffsetResolutionStaysBelowStartsBelongingToLaterLiterals() {
    MultiAnchorDescriptor.StartPlan plan = Pattern.compile("(aq|b[a-z]{9})z").startPlan();
    assertThat(plan).isInstanceOf(MultiAnchorDescriptor.StartPlan.FixedOffset.class);

    // Offsets 2 and 10 are far apart, so the starts a literal occurrence implies are not ordered by
    // the occurrence itself: the `z` at index 5 admits only the start at 3, while the `z` at index
    // 10 starts the match at 0. The caller treats the result as a floor, so it has to report 0.
    String text = "bxxaxzxxxxz";

    StringStartAccelerator strAcc = StringStartAccelerator.create(plan, false);
    assertThat(StringStartAccelerator.findNextCandidate(strAcc, text, 0, false)).isZero();

    Utf8StartAccelerator utf8Acc = Utf8StartAccelerator.create(plan, false);
    assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner(text), 0)).isZero();

    // Past that start nothing can match, and the leading class is enough to prove it without
    // handing the engine a candidate.
    assertThat(StringStartAccelerator.findNextCandidate(strAcc, text, 5, false)).isEqualTo(-1);
    assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner(text), 5)).isEqualTo(-1);
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

  @Test
  void nullableLeadingExpansionVerifiesCandidateAtInnerMatch() {
    Pattern pattern = Pattern.compile("(\\s*)# [Nn][Oo][Qq][Aa]");
    MultiAnchorDescriptor.StartPlan plan = pattern.startPlan();
    assertThat(plan).isInstanceOf(MultiAnchorDescriptor.StartPlan.LeadingExpansion.class);
    MultiAnchorDescriptor.StartPlan.LeadingExpansion le =
        (MultiAnchorDescriptor.StartPlan.LeadingExpansion) plan;
    assertThat(le.minRepetition()).isEqualTo(0);
    assertThat(le.hasLeadingAssertions()).isFalse();

    StringStartAccelerator strAcc = pattern.stringStartAccelerator();
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.LeadingExpansion.class);
    StringStartAccelerator.LeadingExpansion strLe =
        (StringStartAccelerator.LeadingExpansion) strAcc;
    assertThat(strLe.canVerifyAtInner()).isTrue();

    // Matching lines: verify correct leftmost start and capture groups
    Matcher m1 = pattern.matcher("        # noqa: E501");
    assertThat(m1.find()).isTrue();
    assertThat(m1.start()).isEqualTo(0);
    assertThat(m1.group(1)).isEqualTo("        ");

    // Non-matching indented comment: rejected
    Matcher m2 = pattern.matcher("        # This is a comment, not noqa");
    assertThat(m2.find()).isFalse();

    // Multiple candidates on same line: leftmost match found
    Matcher m3 = pattern.matcher("    # foo    # noqa");
    assertThat(m3.find()).isTrue();
    assertThat(m3.start()).isEqualTo(9);
    assertThat(m3.group(1)).isEqualTo("    ");

    // UTF-8 matching
    Utf8Matcher u1 = pattern.matcher(Utf8Input.validated("        # noqa: E501".getBytes(UTF_8)));
    assertThat(u1.find()).isTrue();
    assertThat(u1.start()).isEqualTo(0);
    assertThat(u1.start(1)).isEqualTo(0);
    assertThat(u1.end(1)).isEqualTo(8);

    Utf8Matcher u2 =
        pattern.matcher(Utf8Input.validated("        # This is a comment".getBytes(UTF_8)));
    assertThat(u2.find()).isFalse();
  }

  @Test
  void leadingExpansionWithLeadingBoundaryPreservesAssertions() {
    Pattern pattern = Pattern.compile("\\b[a-z]*target");
    MultiAnchorDescriptor.StartPlan plan = pattern.startPlan();
    assertThat(plan).isInstanceOf(MultiAnchorDescriptor.StartPlan.LeadingExpansion.class);
    MultiAnchorDescriptor.StartPlan.LeadingExpansion le =
        (MultiAnchorDescriptor.StartPlan.LeadingExpansion) plan;
    assertThat(le.minRepetition()).isEqualTo(0);
    assertThat(le.hasLeadingAssertions()).isTrue();

    StringStartAccelerator strAcc = pattern.stringStartAccelerator();
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.LeadingExpansion.class);
    StringStartAccelerator.LeadingExpansion strLe =
        (StringStartAccelerator.LeadingExpansion) strAcc;
    assertThat(strLe.canVerifyAtInner()).isFalse();

    Matcher m = pattern.matcher("prefix wordtarget suffix");
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(7); // "wordtarget" starts at word boundary index 7
  }

  @Test
  void leadingExpansionExpandsBackwardAcrossMultiByteCharacters() {
    // `\s` is single-unit, so the other tests always take the singleUnitCodePointBefore fast
    // path. U+00E0..U+00FF are two bytes each in UTF-8, which forces decodeBackward.
    Pattern pattern = Pattern.compile("[\\u00e0-\\u00ff]*RARE");
    assertThat(pattern.startPlan())
        .isInstanceOf(MultiAnchorDescriptor.StartPlan.LeadingExpansion.class);
    Utf8StartAccelerator u8Acc = pattern.utf8StartAccelerator();
    assertThat(u8Acc).isInstanceOf(Utf8StartAccelerator.LeadingExpansion.class);
    assertThat(((Utf8StartAccelerator.LeadingExpansion) u8Acc).canVerifyAtInner()).isTrue();

    // Three two-byte characters precede the literal, so the match starts at byte 0 of 10.
    Utf8Matcher m1 = pattern.matcher(Utf8Input.validated("\u00e0\u00e8\u00ecRARE".getBytes(UTF_8)));
    assertThat(m1.find()).isTrue();
    assertThat(m1.start()).isEqualTo(0);
    assertThat(m1.end()).isEqualTo(10);

    // Backward expansion stops at the ASCII 'x', which the leading class excludes.
    Utf8Matcher m2 =
        pattern.matcher(Utf8Input.validated("x\u00e0\u00e8\u00ecRARE".getBytes(UTF_8)));
    assertThat(m2.find()).isTrue();
    assertThat(m2.start()).isEqualTo(1);

    // A rejected candidate must not swallow the rest of the input: the real match is later.
    Utf8Matcher m3 =
        pattern.matcher(Utf8Input.validated("\u00e0\u00e8\u00ecNOPE \u00e0RARE".getBytes(UTF_8)));
    assertThat(m3.find()).isTrue();
    assertThat(m3.start()).isEqualTo(11);
    assertThat(m3.end()).isEqualTo(17);

    Utf8Matcher m4 = pattern.matcher(Utf8Input.validated("\u00e0\u00e8\u00ecNOPE".getBytes(UTF_8)));
    assertThat(m4.find()).isFalse();
  }

  @Test
  void leadingExpansionWithUnicodeFoldedLiteralUsesScalarFallback() {
    Pattern pattern = Pattern.compile("(?iu)[0-9]*Шерлок Холмс");
    assertThat(pattern.startPlan())
        .isInstanceOf(MultiAnchorDescriptor.StartPlan.LeadingExpansion.class);
    assertThat(pattern.utf8StartAccelerator()).isNull();

    Utf8Matcher matcher = pattern.matcher(Utf8Input.validated("12шЕРЛОК ХОЛМС".getBytes(UTF_8)));
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isZero();
  }

  @Test
  void leadingExpansionWithNonNullableRepetitionKeepsTheScalarPath() {
    Pattern pattern = Pattern.compile("\\s+# noqa");
    MultiAnchorDescriptor.StartPlan plan = pattern.startPlan();
    assertThat(plan).isInstanceOf(MultiAnchorDescriptor.StartPlan.LeadingExpansion.class);
    MultiAnchorDescriptor.StartPlan.LeadingExpansion le =
        (MultiAnchorDescriptor.StartPlan.LeadingExpansion) plan;
    assertThat(le.minRepetition()).isEqualTo(1);
    assertThat(le.hasLeadingAssertions()).isFalse();

    // minRepetition > 0 breaks the zero-repetition step of the rejection argument, so the
    // candidate must not be verified at the inner match even with no leading assertions.
    assertThat(
            ((StringStartAccelerator.LeadingExpansion) pattern.stringStartAccelerator())
                .canVerifyAtInner())
        .isFalse();
    assertThat(
            ((Utf8StartAccelerator.LeadingExpansion) pattern.utf8StartAccelerator())
                .canVerifyAtInner())
        .isFalse();

    Matcher m1 = pattern.matcher("    # noqa");
    assertThat(m1.find()).isTrue();
    assertThat(m1.start()).isEqualTo(0);

    // Verifying at the inner match would accept this; the mandatory whitespace is absent.
    Matcher m2 = pattern.matcher("# noqa");
    assertThat(m2.find()).isFalse();
  }

  @Test
  void nullableLeadingExpansionVerifiesCandidateAtInnerMatchWhenSplitting() {
    // Pattern.split reaches Matcher.findSplitPositions -> findNextMatchPacked, a third copy of
    // the candidate-verification block that the find() tests never execute.
    Pattern pattern = Pattern.compile("\\s*# noqa");

    assertThat(pattern.split("alpha    # noqa beta")).containsExactly("alpha", " beta");

    // The `# nope` candidate must be rejected at the inner match and left in the output.
    assertThat(pattern.split("alpha    # nope beta   # noqa gamma"))
        .containsExactly("alpha    # nope beta", " gamma");
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
