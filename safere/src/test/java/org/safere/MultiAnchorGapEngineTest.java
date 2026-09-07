// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.safere.MultiAnchorDescriptor.GapKind;
import org.safere.MultiAnchorDescriptor.RejectPlan;
import org.safere.MultiAnchorDescriptor.StartPlan;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class MultiAnchorGapEngineTest {

  @ParameterizedTest
  @Tag("work-counter")
  @ValueSource(strings = {"AAA[0-9]BBB", "AAA[0-9]BBB[0-9]CCC[0-9]DDD"})
  void eligibilityQueriesDoNotRevisitCompiledSegments(String regex) {
    MultiAnchorDescriptor descriptor = Pattern.compile(regex).multiAnchor();
    assertThat(descriptor.isExecutableChain()).isTrue();
    assertThat(descriptor.isExecutableUtf8Chain()).isTrue();

    long work =
        WorkCounter.countForTesting(
            () -> {
              for (int i = 0; i < 100; i++) {
                assertThat(descriptor.isExecutableChain()).isTrue();
                assertThat(descriptor.isExecutableUtf8Chain()).isTrue();
              }
            });

    assertThat(work).isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z error",
        "[0-9]{2}TOKEN",
        "AAA[0-9]{2}TOKEN",
        "AAA[0-9]{2}TOKEN[a-z]{2}",
        "(?i)AAA[0-9]{2}TOKEN"
      })
  void fixedChainsReuseCompiledMetadataAcrossSearches(String regex) {
    Pattern pattern = Pattern.compile(regex);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);
    String valid = regex.startsWith("[0-9]{4}") ? "2026-08-30T12:00:00Z error" : "AAA12TOKENab";
    String noise = "2026/08/30 12:00:00 [worker-42] info: normal periodic heartbeat\n";
    for (String input :
        new String[] {
          "",
          noise.repeat(20),
          valid,
          noise + valid,
          valid + noise + valid,
          "Z error TOKEN AAAxxTOKEN " + noise + valid
        }) {
      Matcher matcher = pattern.matcher(input);
      Utf8Matcher utf8Matcher = pattern.matcher(Utf8Input.validated(input.getBytes(UTF_8)));
      for (int repeat = 0; repeat < 2; repeat++) {
        java.util.regex.Matcher expected = jdkPattern.matcher(input);
        while (expected.find()) {
          assertThat(matcher.find()).as(regex).isTrue();
          assertThat(matcher.start()).isEqualTo(expected.start());
          assertThat(matcher.end()).isEqualTo(expected.end());
          assertThat(utf8Matcher.find()).as(regex).isTrue();
          assertThat(utf8Matcher.start()).isEqualTo(expected.start());
          assertThat(utf8Matcher.end()).isEqualTo(expected.end());
        }
        assertThat(matcher.find()).isFalse();
        assertThat(utf8Matcher.find()).isFalse();
        matcher.reset();
        utf8Matcher.reset();
      }
    }
  }

  @Test
  void patternMultiAnchorIntegrationStructure() {
    Pattern pattern = Pattern.compile("header:.*body:.*footer");
    MultiAnchorDescriptor actual = pattern.multiAnchor();

    MultiAnchorDescriptor expected =
        MultiAnchorDescriptorBuilder.create()
            .segment("header:")
            .segment(GapKind.SINGLE_LINE_ANY_STAR, "body:")
            .segment(GapKind.SINGLE_LINE_ANY_STAR, "footer")
            .checkOrder(1, 0, 2)
            .startPlan(new StartPlan.Literal("header:", false, null))
            .rejectPlan(new RejectPlan.RequiredLiteral("body:"))
            .build();

    assertThat(actual).usingRecursiveComparison().isEqualTo(expected);
  }

  @Test
  void boundaryGapsRuntimeBehavior() {
    Pattern anchoredPattern = Pattern.compile("^START.*END$");
    assertThat(anchoredPattern.matcher("START between END").find()).isTrue();
    assertThat(anchoredPattern.matcher("prefix START between END").find()).isFalse();
    assertThat(anchoredPattern.matcher("START between END suffix").find()).isFalse();

    Pattern wordPattern = Pattern.compile("\\bWORD.*TAIL");
    assertThat(wordPattern.matcher("a WORD with TAIL").find()).isTrue();
    assertThat(wordPattern.matcher("NO_WORD with TAIL").find()).isFalse();
  }

  @Test
  void emptyGapsAndAdjacentAnchors() {
    Pattern pattern = Pattern.compile("ABC.*DEF");
    String text = "ABCDEF";
    Matcher matcher = pattern.matcher(text);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(0)).isEqualTo("ABCDEF");
  }

  @Test
  void deferredCaptureExtractionParity() {
    String regex = "A(?<grp1>\\d+)B(?<grp2>[a-z]+)C";
    Pattern saferePattern = Pattern.compile(regex);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);

    String text = "noise A12345BqwertyC trailing";
    Matcher safereMatcher = saferePattern.matcher(text);
    java.util.regex.Matcher jdkMatcher = jdkPattern.matcher(text);

    assertThat(safereMatcher.find()).isTrue();
    assertThat(jdkMatcher.find()).isTrue();
    assertThat(safereMatcher.group("grp1")).isEqualTo(jdkMatcher.group("grp1"));
    assertThat(safereMatcher.group("grp2")).isEqualTo(jdkMatcher.group("grp2"));
    assertThat(safereMatcher.start()).isEqualTo(jdkMatcher.start());
    assertThat(safereMatcher.end()).isEqualTo(jdkMatcher.end());
  }

  @Test
  void multiInfixBasicMatch() {
    String regex = ".*foo.*bar.*baz.*";
    Pattern pattern = Pattern.compile(regex);

    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();

    String text = "prefix foo intermediate bar trailing baz suffix";
    Matcher matcher = pattern.matcher(text);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(0);
    assertThat(matcher.end()).isEqualTo(text.length());
  }

  @Test
  void multiInfixPartialMatchNegativeRejection() {
    String regex = ".*foo.*bar.*baz.*";
    Pattern pattern = Pattern.compile(regex);

    // "foo" and "bar" present, but "baz" is absent -> instant rejection in Phase 1
    String text = "prefix foo intermediate bar trailing qux suffix";
    Matcher matcher = pattern.matcher(text);
    assertThat(matcher.find()).isFalse();
  }

  @Test
  void multiInfixUtf8Input() {
    String regex = ".*foo.*bar.*baz.*";
    Pattern pattern = Pattern.compile(regex);

    byte[] bytes = "prefix foo intermediate bar trailing baz suffix".getBytes(UTF_8);
    Utf8Input input = Utf8Input.validated(bytes);

    Utf8Matcher matcher = pattern.matcher(input);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(0);
    assertThat(matcher.end()).isEqualTo(bytes.length);
  }

  @Test
  void structuredMultiClauseLog() {
    String regex = "error:\\[(\\w+)\\]\\s+code:(\\d+)\\s+msg:([^\n]+)";
    Pattern pattern = Pattern.compile(regex);

    String text = "2026-08-27 12:00:00 error:[CRITICAL] code:500 msg:Internal Server Error\n";
    Matcher matcher = pattern.matcher(text);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(0)).isEqualTo("error:[CRITICAL] code:500 msg:Internal Server Error");
    assertThat(matcher.group(1)).isEqualTo("CRITICAL");
    assertThat(matcher.group(2)).isEqualTo("500");
    assertThat(matcher.group(3)).isEqualTo("Internal Server Error");
  }

  @Test
  void singleLineGapNewlineBoundary() {
    String regex = "START[^\n]*MIDDLE[^\n]*END";
    Pattern pattern = Pattern.compile(regex);

    // Fails when a newline is present between START and MIDDLE
    String multilineFail = "START some text\nMIDDLE some text END";
    Matcher m1 = pattern.matcher(multilineFail);
    assertThat(m1.find()).isFalse();

    // Succeeds when all tokens are on the same line
    String singleLineSuccess = "START some text MIDDLE some text END";
    Matcher m2 = pattern.matcher(singleLineSuccess);
    assertThat(m2.find()).isTrue();
    assertThat(m2.group(0)).isEqualTo("START some text MIDDLE some text END");
  }

  @Test
  void dotallMultiLineSuccess() {
    String regex = "(?s)START.*MIDDLE.*END";
    Pattern pattern = Pattern.compile(regex);

    String text = "START line 1\nMIDDLE line 2\nEND";
    Matcher matcher = pattern.matcher(text);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(0)).isEqualTo(text);
  }

  @Test
  void adversarialDenseNoiseWorkLimitFallback() {
    // Pattern looking for A followed by B with bounded noise
    String regex = "A[0-9]{3}B";
    Pattern pattern = Pattern.compile(regex);

    // Dense stream of 5,000 'A's without digits, ending with valid match
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 5000; i++) {
      sb.append("Axx");
    }
    sb.append("A123B");

    String haystack = sb.toString();
    Matcher matcher = pattern.matcher(haystack);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(0)).isEqualTo("A123B");
    assertThat(matcher.start()).isEqualTo(haystack.length() - 5);
  }

  @Test
  void alternationAnchors() {
    String regex = ".*(GET|POST|PUT)\\s+/api/v1/(users|orders).*";
    Pattern pattern = Pattern.compile(regex);

    String text = "Incoming request: POST /api/v1/orders HTTP/1.1";
    Matcher matcher = pattern.matcher(text);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(0);
    assertThat(matcher.end()).isEqualTo(text.length());
  }

  @Test
  void jdkEquivalenceAcrossOffsets() {
    String regex = "id:([a-z]+)\\s+count:(\\d+)";
    Pattern saferePattern = Pattern.compile(regex);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);

    String text = "noise id:first count:100 intermediate id:second count:200 trailing";
    Matcher safereMatcher = saferePattern.matcher(text);
    java.util.regex.Matcher jdkMatcher = jdkPattern.matcher(text);

    while (jdkMatcher.find()) {
      assertThat(safereMatcher.find()).isTrue();
      assertThat(safereMatcher.start()).isEqualTo(jdkMatcher.start());
      assertThat(safereMatcher.end()).isEqualTo(jdkMatcher.end());
      assertThat(safereMatcher.group(0)).isEqualTo(jdkMatcher.group(0));
      assertThat(safereMatcher.group(1)).isEqualTo(jdkMatcher.group(1));
      assertThat(safereMatcher.group(2)).isEqualTo(jdkMatcher.group(2));
    }
    assertThat(safereMatcher.find()).isFalse();
  }

  @Test
  void multiAnchorLogCaptureExtraction() {
    String regex = "error:\\[([A-Z]+)\\]\\s+code:(\\d+)\\s+msg:([a-z]+)";
    Pattern saferePattern = Pattern.compile(regex);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);

    String text = "noise error:[CRITICAL] code:500 msg:crash trailing";
    Matcher safereMatcher = saferePattern.matcher(text);
    java.util.regex.Matcher jdkMatcher = jdkPattern.matcher(text);

    assertThat(safereMatcher.find()).isTrue();
    assertThat(jdkMatcher.find()).isTrue();
    assertThat(safereMatcher.start()).isEqualTo(jdkMatcher.start());
    assertThat(safereMatcher.end()).isEqualTo(jdkMatcher.end());
    assertThat(safereMatcher.group(0)).isEqualTo(jdkMatcher.group(0));
    assertThat(safereMatcher.group(1)).isEqualTo("CRITICAL");
    assertThat(safereMatcher.group(2)).isEqualTo("500");
    assertThat(safereMatcher.group(3)).isEqualTo("crash");
  }

  @Test
  void endAnchoredChainDoesNotAcceptAnEarlierAnchor() {
    assertFirstMatchEqualsJdk("AAA.*BB$", "AAABBxBB");
  }

  @Test
  void greedyInternalGapChoosesTheLastCompatibleAnchor() {
    assertFirstMatchEqualsJdk("AAA.*BB", "AAABBxBB");
  }

  @Test
  void lazyInternalGapRetriesAnAnchorThatCanCompleteTheChain() {
    assertFirstMatchEqualsJdk("AAA.*?BB.CC", "AAAxBBxxBBzCC");
  }

  @Test
  void quantifiedInternalGapCountsUnicodeCodePoints() {
    assertFirstMatchEqualsJdk("AAA.BB", "AAA😀BB");

    Pattern pattern = Pattern.compile("AAA.BB");
    Utf8Matcher matcher = pattern.matcher(Utf8Input.validated("AAA😀BB".getBytes(UTF_8)));
    assertThat(matcher.find()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"\r", "", " ", " "})
  void defaultDotRejectsEveryJdkLineTerminator(String lineTerminator) {
    assertFirstMatchEqualsJdk("AAA.*BB", "AAA" + lineTerminator + "BB");
  }

  @Test
  void subthresholdAnchorsFallBackToGeneralEngine() {
    assertThat(Pattern.compile("A.*B.*C").multiAnchor().isExecutableChain()).isFalse();
    assertThat(Pattern.compile("A[0-9]{3}B").multiAnchor().isExecutableChain()).isFalse();
  }

  @Test
  void variableInternalGapsRemainExecutable() {
    assertThat(Pattern.compile("AAA.*BBB.*CCC").multiAnchor().isExecutableChain()).isTrue();
    assertThat(Pattern.compile("AAA[0-9]+BBB").multiAnchor().isExecutableChain()).isTrue();
    assertThat(Pattern.compile(".*AAA\\s+BBB\\s+CCC.*").multiAnchor().isExecutableChain()).isTrue();
  }

  @Test
  void ambiguousInteriorWildcardMatchesCorrectly() {
    assertFirstMatchEqualsJdk("AAA.*BBB.*CCC", "AAA xxx BBB yyy CCC zzz BBB www");
  }

  @Test
  void fixedAnchorChainRemainsExecutable() {
    Pattern pattern = Pattern.compile("AAA[0-9]BB");
    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();

    Matcher matcher = pattern.matcher("noise AAA1BB trailing");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group()).isEqualTo("AAA1BB");

    Utf8Matcher utf8Matcher =
        pattern.matcher(Utf8Input.validated("noise AAA1BB trailing".getBytes(UTF_8)));
    assertThat(utf8Matcher.find()).isTrue();
  }

  @Test
  void fixedCompoundGapWithoutCharacterMetadataFallsBack() {
    Pattern pattern = Pattern.compile("AAA(?:[0-9]x){2}BB");
    assertThat(pattern.multiAnchor().isExecutableChain()).isFalse();
    assertFirstMatchEqualsJdk("AAA(?:[0-9]x){2}BB", "AAAaxaxBB");
  }

  @Test
  void multipleTrailingConstraintsFallBackWhenTheyCannotShareOneGap() {
    for (String regex : new String[] {"AAA[0-9]BB[0-9]\\b", "AAA[0-9]BB[0-9][A-Za-z0-9_]"}) {
      Pattern pattern = Pattern.compile(regex);

      assertThat(pattern.multiAnchor().isExecutableChain()).as(regex).isFalse();
    }

    assertFirstMatchEqualsJdk("AAA[0-9]BB[0-9]\\b", "AAA1BB2x");
    assertFirstMatchEqualsJdk("AAA[0-9]BB[0-9][A-Za-z0-9_]", "x AAA1BB2!");
  }

  @Test
  void variableLengthAlternationAnchorFallsBack() {
    Pattern pattern = Pattern.compile("(foo|foobar)[0-9]ZZ");
    assertThat(pattern.multiAnchor().isExecutableChain()).isFalse();
    assertFirstMatchEqualsJdk("(foo|foobar)[0-9]ZZ", "foobar1ZZ");
  }

  @Test
  void equalWidthAlternationAnchorFallsBackToAvoidSuffixRescans() {
    Pattern pattern = Pattern.compile("(AAA|ZZZ)[0-9]BB");
    assertThat(pattern.multiAnchor().isExecutableChain()).isFalse();
  }

  @Test
  void foldedSupplementaryLiteralFallsBackForUtf8() {
    Pattern pattern = Pattern.compile("😀A[0-9]BB", Pattern.CASE_INSENSITIVE);
    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();
    assertThat(pattern.multiAnchor().isExecutableUtf8Chain()).isFalse();

    Utf8Matcher matcher = pattern.matcher(Utf8Input.validated("😀A1BB".getBytes(UTF_8)));
    assertThat(matcher.find()).isTrue();
  }

  @Test
  void apiCaseInsensitiveFlagRespectsScopedDisabling() {
    for (String regex :
        new String[] {"(?-i:AAA)[0-9]BB", "AAA[0-9](?-i:BB)", "(?-i:AAA)([0-9])BB"}) {
      assertFirstMatchEqualsJdk(regex, Pattern.CASE_INSENSITIVE, "aaa1bb");
    }
  }

  @Test
  void rarestAnchorBidirectionalExecution() {
    // "security_alert_code" is rarest anchor at index 2
    String regex = "user:([a-z]+)\\s+action:([a-z]+)\\s+security_alert_code:(\\d+)";
    Pattern saferePattern = Pattern.compile(regex);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);

    String text =
        "user:alice action:read status:200 user:bob action:write security_alert_code:999 trailing";
    Matcher safereMatcher = saferePattern.matcher(text);
    java.util.regex.Matcher jdkMatcher = jdkPattern.matcher(text);

    assertThat(safereMatcher.find()).isTrue();
    assertThat(jdkMatcher.find()).isTrue();
    assertThat(safereMatcher.start()).isEqualTo(jdkMatcher.start());
    assertThat(safereMatcher.end()).isEqualTo(jdkMatcher.end());
    assertThat(safereMatcher.group(0)).isEqualTo(jdkMatcher.group(0));
    assertThat(safereMatcher.group(1)).isEqualTo("bob");
    assertThat(safereMatcher.group(2)).isEqualTo("write");
    assertThat(safereMatcher.group(3)).isEqualTo("999");
  }

  @Test
  void rarestAnchorBoundedUpstreamVerification() {
    String regex = "PREFIX[0-9]MIDDLE[0-9]RAREST_TOKEN_XYZ[0-9]SUFFIX";
    Pattern pattern = Pattern.compile(regex);

    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();

    String text = "noise PREFIX1MIDDLE2RAREST_TOKEN_XYZ3SUFFIX trailing";
    Matcher matcher = pattern.matcher(text);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(0)).isEqualTo("PREFIX1MIDDLE2RAREST_TOKEN_XYZ3SUFFIX");

    // Absent rarest token -> instant mismatch
    String absent = "noise PREFIX1MIDDLE2OTHER_TOKEN_1233SUFFIX trailing";
    Matcher m2 = pattern.matcher(absent);
    assertThat(m2.find()).isFalse();
  }

  @Test
  void rarestAnchorUnboundedUpstreamVerification() {
    String regex = "START.*RAREST_ANCHOR_12345.*END";
    Pattern saferePattern = Pattern.compile(regex);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);

    String text = "START noise intermediate RAREST_ANCHOR_12345 more noise END";
    Matcher safereMatcher = saferePattern.matcher(text);
    java.util.regex.Matcher jdkMatcher = jdkPattern.matcher(text);

    assertThat(safereMatcher.find()).isTrue();
    assertThat(jdkMatcher.find()).isTrue();
    assertThat(safereMatcher.start()).isEqualTo(jdkMatcher.start());
    assertThat(safereMatcher.end()).isEqualTo(jdkMatcher.end());
    assertThat(safereMatcher.group(0)).isEqualTo(jdkMatcher.group(0));
  }

  @Test
  void rarestAnchorMultipleOccurrencesLeftmost() {
    String regex = "HEAD[0-9]{2}RAREST[0-9]{2}TAIL";
    Pattern saferePattern = Pattern.compile(regex);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);

    String text = "HEAD11RAREST22TAIL noise HEAD33RAREST44TAIL";
    Matcher safereMatcher = saferePattern.matcher(text);
    java.util.regex.Matcher jdkMatcher = jdkPattern.matcher(text);

    assertThat(safereMatcher.find()).isTrue();
    assertThat(jdkMatcher.find()).isTrue();
    assertThat(safereMatcher.start()).isEqualTo(jdkMatcher.start());
    assertThat(safereMatcher.end()).isEqualTo(jdkMatcher.end());
    assertThat(safereMatcher.group(0)).isEqualTo("HEAD11RAREST22TAIL");

    assertThat(safereMatcher.find()).isTrue();
    assertThat(jdkMatcher.find()).isTrue();
    assertThat(safereMatcher.start()).isEqualTo(jdkMatcher.start());
    assertThat(safereMatcher.end()).isEqualTo(jdkMatcher.end());
    assertThat(safereMatcher.group(0)).isEqualTo("HEAD33RAREST44TAIL");
  }

  @Test
  void rarestAnchorUtf8Equivalence() {
    String regex = "tag:([a-z]+)\\s+RAREST_KEY_TOKEN=(\\d+)";
    Pattern pattern = Pattern.compile(regex);

    byte[] bytes = "noise tag:alpha RAREST_KEY_TOKEN=42 trailing".getBytes(UTF_8);
    Utf8Input input = Utf8Input.validated(bytes);

    Utf8Matcher matcher = pattern.matcher(input);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(6);
    assertThat(matcher.end()).isEqualTo(35);
    assertThat(matcher.start(1)).isEqualTo(10);
    assertThat(matcher.end(1)).isEqualTo(15);
    assertThat(matcher.start(2)).isEqualTo(33);
    assertThat(matcher.end(2)).isEqualTo(35);
  }

  @Test
  void reverseDriverPreservesLeftmostStartAcrossVariableGap() {
    assertFirstMatchEqualsJdk("111[0-9]+RAREST_TOKEN", "1111112RAREST_TOKEN");
  }

  @Test
  void variableUpstreamGapsStayOnForwardExecution() {
    MultiAnchorDescriptor descriptor = Pattern.compile("AAA[A-Z]+RAREST_TOKEN").multiAnchor();

    assertThat(descriptor.selectDriver(MultiAnchorDescriptor.InputDomain.STRING, true)).isZero();
    assertThat(descriptor.selectDriver(MultiAnchorDescriptor.InputDomain.UTF8, true)).isZero();
  }

  @Test
  void utf8ReverseWindowAllowsMultibyteUpstreamLiteral() {
    String regex = "é".repeat(10) + "[0-9]" + "z".repeat(30);
    String text = regex.replace("[0-9]", "7");
    Pattern pattern = Pattern.compile(regex);

    assertThat(pattern.matcher(text).find()).isTrue();
    Utf8Matcher matcher = pattern.matcher(Utf8Input.validated(text.getBytes(UTF_8)));
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isZero();
    assertThat(matcher.end()).isEqualTo(text.getBytes(UTF_8).length);
  }

  @Test
  void overlappingReverseDriverCandidatesRetainUpstreamSearchRange() {
    String regex = "aaaaaaaaaa[zZ]zzzz";
    String text = "Xaaaaaaaaaazzzzz";
    assertFirstMatchEqualsJdk(regex, text);

    Pattern pattern = Pattern.compile(regex);
    Utf8Matcher matcher = pattern.matcher(Utf8Input.validated(text.getBytes(UTF_8)));
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(1);
    assertThat(matcher.end()).isEqualTo(16);
  }

  @Test
  void finiteDotallWildcardGapsHonorTheirCodePointBounds() {
    assertFirstMatchEqualsJdk("(?s)TARGET.", "TARGETabc");
    assertFirstMatchEqualsJdk("(?s).TARGET", "abcTARGET");
    assertFirstMatchEqualsJdk("(?s)TARGET.{1,2}", "TARGETabc");

    Pattern pattern = Pattern.compile("(?s)TARGET.");
    Utf8Matcher matcher = pattern.matcher(Utf8Input.validated("TARGET😀x".getBytes(UTF_8)));
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isZero();
    assertThat(matcher.end()).isEqualTo("TARGET😀".getBytes(UTF_8).length);
  }

  @Test
  void variableLeadingAndInteriorGapsPreserveQuantifierPriority() {
    assertFirstMatchEqualsJdk(".*AAA", "AAA x AAA");
    assertFirstMatchEqualsJdk(".*?AAA", "xAAA");
    assertFirstMatchEqualsJdk("AAA[A-Z]+BBB", "AAAXBBB1BBB");
  }

  @Test
  void boundedUnicodeClassGapsCountCodePoints() {
    String regex = "AAA[éê]{1,10}BBB";
    String text = "AAA" + "éê".repeat(5) + "BBB";
    assertFirstMatchEqualsJdk(regex, text);

    Pattern pattern = Pattern.compile(regex);
    Utf8Matcher matcher = pattern.matcher(Utf8Input.validated(text.getBytes(UTF_8)));
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isZero();
    assertThat(matcher.end()).isEqualTo(text.getBytes(UTF_8).length);
  }

  @Test
  void adjacentMixedFlavorGapsPreservePriority() {
    assertFirstMatchEqualsJdk("AAA.*?.*", "AAAxyz");
    assertFirstMatchEqualsJdk("AAA.*.*?", "AAAxyz");
  }

  @Test
  void broadCharacterClassesRetainTheirExactMembership() {
    assertFirstMatchEqualsJdk("AAA[^\\nX]*", "AAAabXcd");
    assertFirstMatchEqualsJdk("AAA.*", Pattern.UNIX_LINES, "AAAa\rnext");
  }

  @Test
  void multipleLeadingWildcardsCoalesce() {
    Pattern pattern = Pattern.compile(".*.*AAA.*.*");
    assertThat(pattern.multiAnchor().isExecutableChain()).isFalse();
    assertFirstMatchEqualsJdk(".*.*AAA.*.*", "hello world AAA foo bar\nnext line");
  }

  @Test
  void singleAnchorWithVariableLeadingAndTrailingGapsFallsBack() {
    Pattern pattern = Pattern.compile(".*AAA.*");
    assertThat(pattern.multiAnchor().isExecutableChain()).isFalse();
    assertFirstMatchEqualsJdk(".*AAA.*", "noise AAA trailing\nsecond line");

    Pattern patternBounded = Pattern.compile("\\s+AAA\\s+");
    assertThat(patternBounded.multiAnchor().isExecutableChain()).isFalse();
    assertFirstMatchEqualsJdk("\\s+AAA\\s+", "hello   AAA   world");
  }

  @Test
  void singleAnchorWithLeadingWildcardFallsBackInUtf8() {
    Pattern pattern = Pattern.compile(".*TARGET_KEY");
    assertThat(pattern.multiAnchor().isExecutableChain()).isFalse();

    byte[] bytes = "prefix data TARGET_KEY trailing".getBytes(UTF_8);
    Utf8Input input = Utf8Input.validated(bytes);
    Utf8Matcher matcher = pattern.matcher(input);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(0);
    assertThat(matcher.end()).isEqualTo(22);
  }

  @Test
  void assertionsAfterLeadingCharacterClassFallBackToGeneralEngine() {
    for (String[] testCase :
        new String[][] {
          {"[a-z]\\bAAA", "xAAA"},
          {"[ ]\\BAAA", " AAA"},
          {"(?m)[a-z]^AAA", "xAAA"},
          {"(?m)[a-z]$AAA", "xAAA"},
          {"[a-z]\\bAAA[0-9]RAREST_TOKEN", "xAAA1RAREST_TOKEN"}
        }) {
      String regex = testCase[0];
      String text = testCase[1];
      Pattern pattern = Pattern.compile(regex);

      assertThat(pattern.multiAnchor().isExecutableChain()).as(regex).isFalse();
      assertFirstMatchEqualsJdk(regex, text);

      Utf8Matcher utf8Matcher = pattern.matcher(Utf8Input.validated(text.getBytes(UTF_8)));
      assertThat(utf8Matcher.find())
          .as("UTF-8 membership for %s", regex)
          .isEqualTo(java.util.regex.Pattern.compile(regex).matcher(text).find());
    }
  }

  @Test
  void factoredAlternationPrefixesPreserveScopedCaseFlags() {
    for (String[] testCase :
        new String[][] {
          {"(?:(?i:abc)X|abcY)", "ABCX"},
          {"(?:abcX|(?i:abc)Y)", "ABCY"},
          {"(?i:(?-i:abc)X|abcY)", "ABCY"},
          {"(?i:(?-i:abc)X|abcY)", "ABCX"},
          {"(?:(?i:abc)X|abcY)[0-9]RAREST_TOKEN", "ABCX1RAREST_TOKEN"}
        }) {
      assertFirstMatchEqualsJdk(testCase[0], testCase[1]);
    }
  }

  private static void assertFirstMatchEqualsJdk(String regex, String text) {
    assertFirstMatchEqualsJdk(regex, 0, text);
  }

  private static void assertFirstMatchEqualsJdk(String regex, int flags, String text) {
    Matcher safere = Pattern.compile(regex, flags).matcher(text);
    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regex, flags).matcher(text);

    boolean expected = jdk.find();
    assertThat(safere.find()).isEqualTo(expected);
    if (expected) {
      assertThat(safere.start()).isEqualTo(jdk.start());
      assertThat(safere.end()).isEqualTo(jdk.end());
      assertThat(safere.group()).isEqualTo(jdk.group());
    }
  }

  @Test
  void alternationPrefixFactoring() {
    String regex = "(?:application/json|application/xml|application/pdf)";
    Pattern pattern = Pattern.compile(regex);

    String text = "Content-Type: application/json; charset=utf-8";
    Matcher matcher = pattern.matcher(text);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(0)).isEqualTo("application/json");

    String xmlText = "Accept: application/xml";
    Matcher mXml = pattern.matcher(xmlText);
    assertThat(mXml.find()).isTrue();
    assertThat(mXml.group(0)).isEqualTo("application/xml");

    String absentText = "Content-Type: text/plain";
    Matcher mAbsent = pattern.matcher(absentText);
    assertThat(mAbsent.find()).isFalse();
  }

  @Test
  void alternationSuffixFactoring() {
    String regex = "(?:https?://|ftp://|sftp://)api/v1/[a-z]+";
    Pattern pattern = Pattern.compile(regex);

    String text = "Endpoint: https://api/v1/users and ftp://api/v1/files";
    Matcher matcher = pattern.matcher(text);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(0)).isEqualTo("https://api/v1/users");

    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(0)).isEqualTo("ftp://api/v1/files");

    String absentText = "Endpoint: gopher://other/path";
    Matcher mAbsent = pattern.matcher(absentText);
    assertThat(mAbsent.find()).isFalse();
  }

  @Test
  void fixedWidthRepetitionDateGap() {
    String regex = "\\d{4}-\\d{2}-\\d{2}";
    Pattern pattern = Pattern.compile(regex);

    String text = "Event logged at 2026-08-29 in system";
    Matcher matcher = pattern.matcher(text);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(0)).isEqualTo("2026-08-29");

    // Invalid format (e.g. 3 digits instead of 4) -> mismatch
    String invalid = "Event logged at 202-08-29 in system";
    Matcher mInv = pattern.matcher(invalid);
    assertThat(mInv.find()).isFalse();
  }

  @Test
  void fixedWidthRepetitionUuidGap() {
    String regex = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    Pattern pattern = Pattern.compile(regex);

    String text = "Request ID: 12345678-abcd-ef01-2345-6789abcdef01 processed";
    Matcher matcher = pattern.matcher(text);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(0)).isEqualTo("12345678-abcd-ef01-2345-6789abcdef01");

    String text2 = "Malformed ID: 1234567-abcd-ef01-2345-6789abcdef01";
    Matcher m2 = pattern.matcher(text2);
    assertThat(m2.find()).isFalse();
  }

  @Test
  void dynamicCoalescedWeakAnchorChain() {
    String regex = "PREFIX_START_[a-z0-9]{2}_MID_[a-z0-9]{2}_RAREST_FINAL_TOKEN";
    Pattern pattern = Pattern.compile(regex);

    String text = "noise PREFIX_START_ab_MID_cd_RAREST_FINAL_TOKEN trailing";
    Matcher matcher = pattern.matcher(text);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(0)).isEqualTo("PREFIX_START_ab_MID_cd_RAREST_FINAL_TOKEN");

    String absent = "noise PREFIX_START_ab_MID_cd_OTHER_FINAL_TOKEN trailing";
    Matcher mAbsent = pattern.matcher(absent);
    assertThat(mAbsent.find()).isFalse();
  }

  @Test
  void guardBytesExtractionAndPureComplement() {
    Pattern p1 = Pattern.compile("header:[^\\r\\n;]*val");
    MultiAnchorDescriptor d1 = p1.multiAnchor();
    assertThat(d1.segments()).hasSize(2);
    MultiAnchorDescriptor.Gap g1 = d1.segments()[1].gap();
    assertThat(g1.guardBytes()).containsExactly((byte) '\n', (byte) '\r', (byte) ';');
    assertThat(g1.isPureComplement()).isTrue();

    Pattern p2 = Pattern.compile("START\"[^\"]*\"END");
    MultiAnchorDescriptor d2 = p2.multiAnchor();
    assertThat(d2.segments()).hasSize(2);
    MultiAnchorDescriptor.Gap g2 = d2.segments()[1].gap();
    assertThat(g2.guardBytes()).containsExactly((byte) '"');
    assertThat(g2.isPureComplement()).isTrue();

    Pattern p3 = Pattern.compile("START[^\\n]*END");
    MultiAnchorDescriptor d3 = p3.multiAnchor();
    assertThat(d3.segments()).hasSize(2);
    MultiAnchorDescriptor.Gap g3 = d3.segments()[1].gap();
    assertThat(g3.guardBytes()).containsExactly((byte) '\n', (byte) '\r');
    assertThat(g3.isPureComplement()).isTrue();

    Pattern p5 = Pattern.compile("START[^;]*END");
    MultiAnchorDescriptor d5 = p5.multiAnchor();
    assertThat(d5.segments()).hasSize(2);
    MultiAnchorDescriptor.Gap g5 = d5.segments()[1].gap();
    assertThat(g5.guardBytes()).containsExactly((byte) ';');
    assertThat(g5.isPureComplement()).isTrue();

    Pattern p4 = Pattern.compile("START[a-z]*END");
    MultiAnchorDescriptor d4 = p4.multiAnchor();
    assertThat(d4.segments()).hasSize(2);
    MultiAnchorDescriptor.Gap g4 = d4.segments()[1].gap();
    assertThat(g4.guardBytes()).isNull();
    assertThat(g4.isPureComplement()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void structuredHeaderGuardByteRejection(boolean useUtf8) {
    Pattern pattern = Pattern.compile("header:[^\\r\\n;]*val");
    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();

    String valid = "prefix header:custom-content-12345val suffix";
    assertThat(findMatches(pattern, valid, useUtf8))
        .containsExactly("header:custom-content-12345val");

    // Newline in gap -> rejected
    String withNl = "prefix header:custom\ncontentval suffix";
    assertThat(findMatches(pattern, withNl, useUtf8)).isEmpty();

    // Semicolon in gap -> rejected
    String withSemi = "prefix header:custom;contentval suffix";
    assertThat(findMatches(pattern, withSemi, useUtf8)).isEmpty();

    // Carriage return in gap -> rejected
    String withCr = "prefix header:custom\rcontentval suffix";
    assertThat(findMatches(pattern, withCr, useUtf8)).isEmpty();

    // Multiline runaway where false anchor "val" is on subsequent line
    String multiline = "header:some_header_text\nother_text_without_start val";
    assertThat(findMatches(pattern, multiline, useUtf8)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void quotedStringDelimiterGuardByteRejection(boolean useUtf8) {
    Pattern pattern = Pattern.compile("\"[^\"]*\"");

    String simple = "leading \"hello world\" trailing";
    assertThat(findMatches(pattern, simple, useUtf8)).containsExactly("\"hello world\"");

    // Multiple quoted strings on one line: engine must stop at the first quote delimiter
    String multi = "first \"foo\" and second \"bar\" end";
    assertThat(findMatches(pattern, multi, useUtf8)).containsExactly("\"foo\"", "\"bar\"");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void singleLineDelimiterReverseRejection(boolean useUtf8) {
    Pattern pattern = Pattern.compile("START[^\\n]*END");

    String sameLine = "noise START some content END trailing";
    assertThat(findMatches(pattern, sameLine, useUtf8)).containsExactly("START some content END");

    // START on line 1, END on line 2 -> reverse driver must reject across newline
    String acrossLines = "START line one\nline two with END";
    assertThat(findMatches(pattern, acrossLines, useUtf8)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void guardedGreedyGapChoosesSuffixBeforeFirstGuard(boolean useUtf8) {
    Pattern pattern = Pattern.compile("AAA[^;]*BBB");

    assertThat(findMatches(pattern, "AAABBB;BBB", useUtf8)).containsExactly("AAABBB");
    assertThat(findMatches(pattern, "AAAxxBBB;BBB", useUtf8)).containsExactly("AAAxxBBB");
    assertThat(findMatches(pattern, "AAA;BBB", useUtf8)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void reverseGuardPruningRetainsUpstreamAnchorContainingGuard(boolean useUtf8) {
    Pattern ascii = Pattern.compile("AAA;[^;]RAREBBBB");
    Pattern nonAscii = Pattern.compile("é;[^;]RAREBBBBBBBB");

    assertThat(findMatches(ascii, "xxAAA;xRAREBBBByy", useUtf8)).containsExactly("AAA;xRAREBBBB");
    assertThat(findMatches(nonAscii, "é;xRAREBBBBBBBB", useUtf8))
        .containsExactly("é;xRAREBBBBBBBB");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void guardedGapWithFallibleContinuationUsesGeneralEngine(boolean useUtf8) {
    Pattern greedy = Pattern.compile("AAA[^;]*BBB[^;]*CCC");
    Pattern reluctant = Pattern.compile("AAA[^;]*?BBB[^:]*CCC");

    assertThat(greedy.multiAnchor().isExecutableChain()).isFalse();
    assertThat(reluctant.multiAnchor().isExecutableChain()).isFalse();
    assertThat(findMatches(greedy, "AAABBBCCCBBB;CCC", useUtf8)).containsExactly("AAABBBCCC");
    assertThat(findMatches(reluctant, "AAABBB:BBBCCC", useUtf8)).containsExactly("AAABBB:BBBCCC");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void guardedBoundedTrailingGapEndsOnCodePointBoundary(boolean useUtf8) {
    Pattern optional = Pattern.compile("AAA[^;]?");
    Pattern bounded = Pattern.compile("AAA[^;]{0,2}");
    Pattern interior = Pattern.compile("AAA[^;]?BBB");

    assertThat(findMatches(optional, "xxAAA\ud83d\ude00zz", useUtf8))
        .containsExactly("AAA\ud83d\ude00");
    assertThat(findMatches(bounded, "xxAAA\ud83d\ude00\ud83d\ude03z", useUtf8))
        .containsExactly("AAA\ud83d\ude00\ud83d\ude03");
    assertThat(findMatches(interior, "xxAAA\ud83d\ude00BBBzz", useUtf8))
        .containsExactly("AAA\ud83d\ude00BBB");
    assertThat(findMatches(optional, "xxAAA;zz", useUtf8)).containsExactly("AAA");
  }

  @Test
  void guardedGapAnchorsStartOnlyAtCodePointBoundaries() {
    String text = "xxAAA\ud83d\ude00BBBzz";

    for (String regex : new String[] {"AAA[^;]*\\uDE00BBB", "AAA[^;]?\\uDE00BBB"}) {
      assertThat(java.util.regex.Pattern.compile(regex).matcher(text).find()).isFalse();
      assertThat(Pattern.compile(regex).matcher(text).find()).as(regex).isFalse();
    }

    String laterValidSuffix = "AAA\ud83d\ude00BBB\ude00BBB";
    String regex = "AAA[^;]*\\uDE00BBB";
    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regex).matcher(laterValidSuffix);
    Matcher safeRe = Pattern.compile(regex).matcher(laterValidSuffix);
    assertThat(jdk.find()).isTrue();
    assertThat(safeRe.find()).isTrue();
    assertThat(safeRe.group()).isEqualTo(jdk.group());

    assertThat(Pattern.compile("AAA\\uD83D[^;]*").matcher("AAA\ud83d\ude00").find()).isFalse();
    assertThat(Pattern.compile("AAA[^;]*BBB\\uD83D").matcher("AAABBB\ud83d\ude00").find())
        .isFalse();

    Utf8Matcher utf8 =
        Pattern.compile(regex).matcher(Utf8Input.validated("AAA?BBB".getBytes(UTF_8)));
    assertThat(utf8.find()).isFalse();
  }

  @Test
  void unixLinesDotMatchesCarriageReturnAndStopsAtNewline() {
    Pattern pattern = Pattern.compile("AAA.*BBB", Pattern.UNIX_LINES);

    // Contains \r -> matches because \r is allowed under UNIX_LINES
    String withCr = "AAAcontent\rwith_crBBB";
    Matcher m1 = pattern.matcher(withCr);
    assertThat(m1.find()).isTrue();
    assertThat(m1.group(0)).isEqualTo(withCr);

    // Contains \n -> rejected across newline boundary
    String withNl = "AAAcontent\nnewlineBBB";
    Matcher m2 = pattern.matcher(withNl);
    assertThat(m2.find()).isFalse();

    // UTF-8 input test
    Utf8Matcher utf8m1 = pattern.matcher(Utf8Input.validated(withCr.getBytes(UTF_8)));
    assertThat(utf8m1.find()).isTrue();
    assertThat(utf8m1.end() - utf8m1.start()).isEqualTo(withCr.getBytes(UTF_8).length);
  }

  @Test
  void anchorSingleCaseInsensitivePrecomputedState() {
    // Length >= 4 case-insensitive: ClassHashChain precomputed
    MultiAnchorDescriptor.Anchor.Single singleLong =
        MultiAnchorDescriptor.Anchor.Single.create("abcdef", true);
    assertThat(singleLong.classHashChain()).isNotNull();
    assertThat(singleLong.findNext("prefix_ABCDEF_suffix", 0)).isEqualTo(7);
    assertThat(singleLong.findNext("prefix_aBcDeF_suffix", 0)).isEqualTo(7);
    assertThat(singleLong.findNext("prefix_abcdef_suffix", 8)).isEqualTo(-1);

    // Length < 4 case-insensitive: ClassHashChain is null
    MultiAnchorDescriptor.Anchor.Single singleShort =
        MultiAnchorDescriptor.Anchor.Single.create("abc", true);
    assertThat(singleShort.classHashChain()).isNull();
    assertThat(singleShort.findNext("xyz_ABC_123", 0)).isEqualTo(4);
    assertThat(singleShort.findNext("xyz_aBc_123", 0)).isEqualTo(4);
    assertThat(singleShort.findNext("xyz_abc_123", 5)).isEqualTo(-1);

    // Case-sensitive: ClassHashChain is null
    MultiAnchorDescriptor.Anchor.Single singleExact =
        MultiAnchorDescriptor.Anchor.Single.create("abcdef", false);
    assertThat(singleExact.classHashChain()).isNull();
    assertThat(singleExact.findNext("prefix_ABCDEF_suffix", 0)).isEqualTo(-1);
    assertThat(singleExact.findNext("prefix_abcdef_suffix", 0)).isEqualTo(7);
  }

  private static List<String> findMatches(Pattern pattern, String text, boolean useUtf8) {
    List<String> matches = new ArrayList<>();
    if (useUtf8) {
      byte[] bytes = text.getBytes(UTF_8);
      Utf8Matcher matcher = pattern.matcher(Utf8Input.validated(bytes));
      while (matcher.find()) {
        matches.add(new String(bytes, matcher.start(), matcher.end() - matcher.start(), UTF_8));
      }
    } else {
      Matcher matcher = pattern.matcher(text);
      while (matcher.find()) {
        matches.add(matcher.group(0));
      }
    }
    return matches;
  }
}
