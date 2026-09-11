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
  void delimitedHtmlTagsMatchCorrectly() {
    String regex = "<div[^>]*>.*?</div>";
    assertFirstMatchEqualsJdk(regex, "<div class=\"foo\">hello world</div>");
    assertFirstMatchEqualsJdk(
        regex, "prefix <div id=\"1\">content 1</div> middle <div id=\"2\">content 2</div> suffix");
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
  void fixedCompoundGapExecutesViaMultiAnchor() {
    String regex = "AAA(?:[0-9]x){2}BB";
    Pattern pattern = Pattern.compile(regex);
    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();
    assertFirstMatchEqualsJdk(regex, "AAA1x2xBB");
    assertFirstMatchEqualsJdk(regex, "noise AAA9x0xBB trailing");
    assertFirstMatchEqualsJdk(regex, "AAAaxaxBB");

    Utf8Matcher utf8Matcher =
        pattern.matcher(Utf8Input.validated("noise AAA1x2xBB trailing".getBytes(UTF_8)));
    assertThat(utf8Matcher.find()).isTrue();
    assertThat(utf8Matcher.start()).isEqualTo(6);
    assertThat(utf8Matcher.end()).isEqualTo(15);
  }

  @Test
  void fixedCompoundDateSequenceExecutesViaMultiAnchor() {
    String regex = "DATE_(?:\\d{4}-\\d{2}-\\d{2})_LOG";
    Pattern pattern = Pattern.compile(regex);
    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();
    assertFirstMatchEqualsJdk(regex, "DATE_2026-09-07_LOG");
    assertFirstMatchEqualsJdk(regex, "prefix DATE_2026-09-07_LOG suffix");
    assertFirstMatchEqualsJdk(regex, "DATE_2026-99-99_LOG");
    assertFirstMatchEqualsJdk(regex, "DATE_2026-xx-yy_LOG");

    Utf8Matcher utf8Matcher =
        pattern.matcher(Utf8Input.validated("noise DATE_2026-09-07_LOG trailing".getBytes(UTF_8)));
    assertThat(utf8Matcher.find()).isTrue();
    assertThat(utf8Matcher.start()).isEqualTo(6);
    assertThat(utf8Matcher.end()).isEqualTo(25);
  }

  @Test
  void compoundGapWithRarestDownstreamDriver() {
    String regex = "AA(?:[0-9]x){2}RARE_ANCHOR";
    Pattern pattern = Pattern.compile(regex);
    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();
    assertFirstMatchEqualsJdk(regex, "AA1x2xRARE_ANCHOR");
    assertFirstMatchEqualsJdk(regex, "noise AA1x2xRARE_ANCHOR trailing");
    assertFirstMatchEqualsJdk(regex, "AAaxbxRARE_ANCHOR");

    Utf8Matcher utf8Matcher =
        pattern.matcher(Utf8Input.validated("noise AA1x2xRARE_ANCHOR trailing".getBytes(UTF_8)));
    assertThat(utf8Matcher.find()).isTrue();
    assertThat(utf8Matcher.start()).isEqualTo(6);
    assertThat(utf8Matcher.end()).isEqualTo(23);
  }

  @Test
  void hybridCompoundDateAndWildcardGapExecutesViaMultiAnchor() {
    String regex = "DATE_(?:\\d{4}-\\d{2}-\\d{2})_LOG.*?msg:crash";
    Pattern pattern = Pattern.compile(regex);
    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();
    assertFirstMatchEqualsJdk(regex, "prefix DATE_2026-09-07_LOG noise text msg:crash suffix");
    assertFirstMatchEqualsJdk(
        regex, "DATE_2026-01-01_INFO line 1\nDATE_2026-09-07_LOG event msg:crash end");

    Utf8Matcher utf8Matcher =
        pattern.matcher(
            Utf8Input.validated(
                "noise DATE_2026-09-07_LOG extra msg:crash trailing".getBytes(UTF_8)));
    assertThat(utf8Matcher.find()).isTrue();
    assertThat(utf8Matcher.start()).isEqualTo(6);
    assertThat(utf8Matcher.end()).isEqualTo(41);
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

  @Test
  void deepDownstreamChainDoesNotOverflowStack() {
    int depth = 2_000;
    StringBuilder regex = new StringBuilder("AAA");
    StringBuilder input = new StringBuilder("AAA");
    for (int i = 0; i < depth; i++) {
      regex.append(".*?BBB");
      input.append("BBB");
    }
    regex.append(".*?CCC");
    input.append("CCC");

    Pattern pattern = Pattern.compile(regex.toString(), Pattern.DOTALL);
    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();

    Matcher matcher = pattern.matcher(input.toString());
    assertThat(MultiAnchorExecutor.find(pattern.multiAnchor(), input.toString(), 0).isMatched())
        .isTrue();
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(0);
    assertThat(matcher.end()).isEqualTo(input.length());

    Utf8Matcher utf8Matcher =
        pattern.matcher(Utf8Input.validated(input.toString().getBytes(UTF_8)));
    assertThat(utf8Matcher.find()).isTrue();
    assertThat(utf8Matcher.start()).isEqualTo(0);
    assertThat(utf8Matcher.end()).isEqualTo(input.toString().getBytes(UTF_8).length);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "(error:\\[)[A-Z](\\] code:500)",
        "(error:\\[)([A-Z])(\\] code:500)",
        "error:\\[([A-Z])\\] code:500",
        "((error:\\[)[A-Z](\\] code:500))",
        "(?<err>error:\\[)(?<sev>[A-Z])(?<msg>\\] code:500)",
        "(AAA)[0-9]{2}(BBB)",
        "(AAA)([0-9]{2})(BBB)",
        "AAA([0-9]{2})BBB",
        "(AAA)[0-9]{2}(BBB)([a-z]{2})",
        "(東京)[0-9]{2}(京都)",
        "(東京)([0-9]{2})(京都)",
        "((東京)[0-9]{2}(京都))",
        "(αβγ)[0-9]{2}(δεζ)",
      })
  void directGapCaptureExtractionMatchesJdk(String regex) {
    Pattern pattern = Pattern.compile(regex);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);

    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();
    assertThat(pattern.multiAnchor().canExtractAllCaptures()).isTrue();

    String[] testInputs = {
      "error:[A] code:500",
      "prefix error:[E] code:500 suffix",
      "no match here",
      "prefix error:[W] code:500 and another error:[C] code:500 at the end",
      "AAA42BBB",
      "noise AAA99BBB extra AAA01BBB trailing",
      "noise AAA99BBBextra",
      // Multibyte UTF-8 tests (2-byte Greek/Latin, 3-byte CJK, 4-byte emoji)
      "αβγ error:[A] code:500 δεζ",
      "日本語 prefix error:[E] code:500 suffix 測試",
      "🎉🚀 error:[W] code:500 ✨ and another error:[C] code:500 🌟",
      "こんにちは AAA42BBB 世界",
      "🔥 AAA99BBB 💡 AAA01BBB 🎯",
      "Élégant error:[S] code:500 café error:[X] code:500 résumé",
      "東京42京都",
      "前奏 東京99京都 後記",
      "noise 東京12京都 extra 東京88京都 trailing",
      "🎉 東京77京都 🚀",
      "αβγ99δεζ",
      "pre αβγ12δεζ mid αβγ34δεζ post"
    };

    for (String input : testInputs) {
      java.util.regex.Matcher jdkMatcher = jdkPattern.matcher(input);
      Matcher matcher = pattern.matcher(input);
      Utf8Matcher utf8Matcher = pattern.matcher(Utf8Input.validated(input.getBytes(UTF_8)));

      while (jdkMatcher.find()) {
        assertThat(matcher.find()).isTrue();
        assertThat(utf8Matcher.find()).isTrue();

        assertThat(matcher.groupCount()).isEqualTo(jdkMatcher.groupCount());
        assertThat(utf8Matcher.groupCount()).isEqualTo(jdkMatcher.groupCount());

        for (int g = 0; g <= jdkMatcher.groupCount(); g++) {
          assertThat(matcher.start(g)).as("group %d start", g).isEqualTo(jdkMatcher.start(g));
          assertThat(matcher.end(g)).as("group %d end", g).isEqualTo(jdkMatcher.end(g));
          assertThat(matcher.group(g)).as("group %d content", g).isEqualTo(jdkMatcher.group(g));

          int byteStart =
              jdkMatcher.start(g) >= 0
                  ? input.substring(0, jdkMatcher.start(g)).getBytes(UTF_8).length
                  : -1;
          int byteEnd =
              jdkMatcher.end(g) >= 0
                  ? input.substring(0, jdkMatcher.end(g)).getBytes(UTF_8).length
                  : -1;
          assertThat(utf8Matcher.start(g)).as("utf8 group %d start", g).isEqualTo(byteStart);
          assertThat(utf8Matcher.end(g)).as("utf8 group %d end", g).isEqualTo(byteEnd);
        }
      }
      assertThat(matcher.find()).isFalse();
      assertThat(utf8Matcher.find()).isFalse();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "TAG_([0-9]{4})-([0-9]{2})$",
        "(TAG_)([0-9]{4})-([0-9]{2})$",
        "TAG_([0-9]{4})-([0-9]{2})\\z",
        "(TAG_)([0-9]{4})-([0-9]{2})\\z",
        "(TAG_)([0-9]{4})(-)([0-9]{2})$",
        "((TAG_)([0-9]{4})-([0-9]{2})$)"
      })
  void endAnchoredDirectCaptures(String regex) {
    Pattern pattern = Pattern.compile(regex);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);

    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();
    assertThat(pattern.multiAnchor().canExtractAllCaptures()).isTrue();

    String[] testInputs = {
      "TAG_2026-09",
      "prefix_TAG_2026-09",
      "TAG_2026-09\n",
      "prefix_TAG_2026-09\n",
      "ABC-1234-TAG",
      "ABC-1234-TAG\n",
      "prefix ABC-1234-TAG",
      "TAG_2026-09_extra",
      "no match"
    };

    for (String input : testInputs) {
      java.util.regex.Matcher jdkMatcher = jdkPattern.matcher(input);
      Matcher matcher = pattern.matcher(input);
      Utf8Matcher utf8Matcher = pattern.matcher(Utf8Input.validated(input.getBytes(UTF_8)));

      boolean jdkFound = jdkMatcher.find();
      boolean found = matcher.find();
      boolean utf8Found = utf8Matcher.find();

      assertThat(found).as("match presence for input %s", input).isEqualTo(jdkFound);
      assertThat(utf8Found).as("utf8 match presence for input %s", input).isEqualTo(jdkFound);

      if (jdkFound) {
        assertThat(matcher.groupCount()).isEqualTo(jdkMatcher.groupCount());
        assertThat(utf8Matcher.groupCount()).isEqualTo(jdkMatcher.groupCount());

        for (int g = 0; g <= jdkMatcher.groupCount(); g++) {
          assertThat(matcher.start(g)).as("group %d start", g).isEqualTo(jdkMatcher.start(g));
          assertThat(matcher.end(g)).as("group %d end", g).isEqualTo(jdkMatcher.end(g));
          assertThat(matcher.group(g)).as("group %d content", g).isEqualTo(jdkMatcher.group(g));

          int byteStart =
              jdkMatcher.start(g) >= 0
                  ? input.substring(0, jdkMatcher.start(g)).getBytes(UTF_8).length
                  : -1;
          int byteEnd =
              jdkMatcher.end(g) >= 0
                  ? input.substring(0, jdkMatcher.end(g)).getBytes(UTF_8).length
                  : -1;
          assertThat(utf8Matcher.start(g)).as("utf8 group %d start", g).isEqualTo(byteStart);
          assertThat(utf8Matcher.end(g)).as("utf8 group %d end", g).isEqualTo(byteEnd);
        }
      }
    }
  }

  @Test
  void directGapCaptureExtractionMultibyteUtf8ByteOffsets() {
    String regex = "(AAA)([0-9]{2})(BBB)";
    Pattern pattern = Pattern.compile(regex);
    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();
    assertThat(pattern.multiAnchor().canExtractAllCaptures()).isTrue();

    // 2-byte, 3-byte, and 4-byte sequences before, between, and after matches
    String input = "🎉 日本語 αβγ AAA42BBB 🚀 世界 δεζ AAA99BBB ✨";
    byte[] utf8Bytes = input.getBytes(UTF_8);

    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regex).matcher(input);
    Matcher stringMatcher = pattern.matcher(input);
    Utf8Matcher utf8Matcher = pattern.matcher(Utf8Input.validated(utf8Bytes));

    int matchCount = 0;
    while (jdk.find()) {
      matchCount++;
      assertThat(stringMatcher.find()).isTrue();
      assertThat(utf8Matcher.find()).isTrue();

      // String matcher matches character indices
      assertThat(stringMatcher.start()).isEqualTo(jdk.start());
      assertThat(stringMatcher.end()).isEqualTo(jdk.end());
      assertThat(stringMatcher.start(1)).isEqualTo(jdk.start(1));
      assertThat(stringMatcher.end(1)).isEqualTo(jdk.end(1));
      assertThat(stringMatcher.start(2)).isEqualTo(jdk.start(2));
      assertThat(stringMatcher.end(2)).isEqualTo(jdk.end(2));
      assertThat(stringMatcher.start(3)).isEqualTo(jdk.start(3));
      assertThat(stringMatcher.end(3)).isEqualTo(jdk.end(3));

      // Utf8Matcher matches byte offsets, which differ from character indices
      int expectedByteStart = input.substring(0, jdk.start()).getBytes(UTF_8).length;
      int expectedByteEnd = input.substring(0, jdk.end()).getBytes(UTF_8).length;
      int expectedByteG1Start = input.substring(0, jdk.start(1)).getBytes(UTF_8).length;
      int expectedByteG1End = input.substring(0, jdk.end(1)).getBytes(UTF_8).length;
      int expectedByteG2Start = input.substring(0, jdk.start(2)).getBytes(UTF_8).length;
      int expectedByteG2End = input.substring(0, jdk.end(2)).getBytes(UTF_8).length;
      int expectedByteG3Start = input.substring(0, jdk.start(3)).getBytes(UTF_8).length;
      int expectedByteG3End = input.substring(0, jdk.end(3)).getBytes(UTF_8).length;

      assertThat(utf8Matcher.start()).isEqualTo(expectedByteStart);
      assertThat(utf8Matcher.end()).isEqualTo(expectedByteEnd);
      assertThat(utf8Matcher.start(1)).isEqualTo(expectedByteG1Start);
      assertThat(utf8Matcher.end(1)).isEqualTo(expectedByteG1End);
      assertThat(utf8Matcher.start(2)).isEqualTo(expectedByteG2Start);
      assertThat(utf8Matcher.end(2)).isEqualTo(expectedByteG2End);
      assertThat(utf8Matcher.start(3)).isEqualTo(expectedByteG3Start);
      assertThat(utf8Matcher.end(3)).isEqualTo(expectedByteG3End);

      // Slicing the raw byte array with utf8Matcher coordinates reproduces the exact text
      assertThat(
              new String(
                  utf8Bytes, utf8Matcher.start(0), utf8Matcher.end() - utf8Matcher.start(0), UTF_8))
          .isEqualTo(jdk.group(0));
      assertThat(
              new String(
                  utf8Bytes,
                  utf8Matcher.start(1),
                  utf8Matcher.end(1) - utf8Matcher.start(1),
                  UTF_8))
          .isEqualTo(jdk.group(1));
      assertThat(
              new String(
                  utf8Bytes,
                  utf8Matcher.start(2),
                  utf8Matcher.end(2) - utf8Matcher.start(2),
                  UTF_8))
          .isEqualTo(jdk.group(2));
      assertThat(
              new String(
                  utf8Bytes,
                  utf8Matcher.start(3),
                  utf8Matcher.end(3) - utf8Matcher.start(3),
                  UTF_8))
          .isEqualTo(jdk.group(3));
    }
    assertThat(matchCount).isEqualTo(2);
    assertThat(stringMatcher.find()).isFalse();
    assertThat(utf8Matcher.find()).isFalse();
  }

  @Test
  void directGapCaptureExtractionMultibyteAnchorsInCapturedIntervals() {
    String regex = "((東京)[0-9]{2}(京都))";
    Pattern pattern = Pattern.compile(regex);
    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();
    assertThat(pattern.multiAnchor().canExtractAllCaptures()).isTrue();

    String input = "前奏 (( 東京42京都 )) 中間 (( 東京99京都 )) 後記";
    byte[] utf8Bytes = input.getBytes(UTF_8);

    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regex).matcher(input);
    Matcher stringMatcher = pattern.matcher(input);
    Utf8Matcher utf8Matcher = pattern.matcher(Utf8Input.validated(utf8Bytes));

    while (jdk.find()) {
      assertThat(stringMatcher.find()).isTrue();
      assertThat(utf8Matcher.find()).isTrue();

      for (int g = 0; g <= jdk.groupCount(); g++) {
        assertThat(stringMatcher.group(g)).isEqualTo(jdk.group(g));
        int expectedByteStart = input.substring(0, jdk.start(g)).getBytes(UTF_8).length;
        int expectedByteEnd = input.substring(0, jdk.end(g)).getBytes(UTF_8).length;
        assertThat(utf8Matcher.start(g)).as("group %d start", g).isEqualTo(expectedByteStart);
        assertThat(utf8Matcher.end(g)).as("group %d end", g).isEqualTo(expectedByteEnd);

        assertThat(
                new String(
                    utf8Bytes,
                    utf8Matcher.start(g),
                    utf8Matcher.end(g) - utf8Matcher.start(g),
                    UTF_8))
            .as("group %d content", g)
            .isEqualTo(jdk.group(g));
      }
    }
    assertThat(stringMatcher.find()).isFalse();
    assertThat(utf8Matcher.find()).isFalse();
  }

  @Test
  void caseInsensitiveAnchorSingleFindNextWithin() {
    MultiAnchorDescriptor.Anchor.Single anchor =
        MultiAnchorDescriptor.Anchor.Single.create("TARGET", true);
    String text = "abc target 123 TARGET 456 Target xyz";

    // Unbounded / full range finds first
    assertThat(anchor.findNextWithin(text, 0, text.length())).isEqualTo(4);

    // Bounded search before first occurrence
    assertThat(anchor.findNextWithin(text, 0, 3)).isEqualTo(-1);

    // Bounded search starting after first occurrence
    assertThat(anchor.findNextWithin(text, 5, 20)).isEqualTo(15);

    // Bounded search exactly at occurrence
    assertThat(anchor.findNextWithin(text, 4, 4)).isEqualTo(4);

    // Negative fromIndex handled defensively
    assertThat(anchor.findNextWithin(text, -5, 10)).isEqualTo(4);
  }

  @Test
  void caseInsensitiveAnchorSingleLastIndexOf() {
    MultiAnchorDescriptor.Anchor.Single anchor =
        MultiAnchorDescriptor.Anchor.Single.create("TARGET", true);
    String text = "abc target 123 TARGET 456 Target xyz";
    // Occurrences at 4 ("target"), 15 ("TARGET"), 26 ("Target")

    // Full range returns last occurrence
    assertThat(anchor.lastIndexOf(text, 0, text.length())).isEqualTo(26);

    // Bounded to exclude last occurrence
    assertThat(anchor.lastIndexOf(text, 0, 25)).isEqualTo(15);

    // Bounded to single occurrence in middle
    assertThat(anchor.lastIndexOf(text, 10, 20)).isEqualTo(15);

    // Bounded range where no occurrence falls within [fromIndex, toIndex]
    assertThat(anchor.lastIndexOf(text, 11, 14)).isEqualTo(-1);

    // Bounded range before all occurrences
    assertThat(anchor.lastIndexOf(text, 0, 3)).isEqualTo(-1);

    // Bounded range after all occurrences
    assertThat(anchor.lastIndexOf(text, 33, text.length())).isEqualTo(-1);

    // Repeated dense characters
    MultiAnchorDescriptor.Anchor.Single denseAnchor =
        MultiAnchorDescriptor.Anchor.Single.create("ab", true);
    String denseText = "aBaBaBab";
    assertThat(denseAnchor.lastIndexOf(denseText, 0, 7)).isEqualTo(6);
    assertThat(denseAnchor.lastIndexOf(denseText, 0, 5)).isEqualTo(4);
    assertThat(denseAnchor.lastIndexOf(denseText, 0, 3)).isEqualTo(2);
    assertThat(denseAnchor.lastIndexOf(denseText, 0, 1)).isEqualTo(0);
  }

  @Test
  void gapScannerFindLastGuardByteString() {
    byte[] singleGuard = new byte[] {(byte) ';'};
    String text1 = "abc;def;ghi";
    assertThat(GapScanner.findLastGuardByte(singleGuard, text1, 0, text1.length() - 1))
        .isEqualTo(7);
    assertThat(GapScanner.findLastGuardByte(singleGuard, text1, 0, 6)).isEqualTo(3);
    assertThat(GapScanner.findLastGuardByte(singleGuard, text1, 0, 2)).isEqualTo(-1);
    assertThat(GapScanner.findLastGuardByte(singleGuard, text1, 4, 6)).isEqualTo(-1);

    byte[] doubleGuard = new byte[] {(byte) '\r', (byte) '\n'};
    String text2 = "line1\r\nline2\nline3\rline4";
    // indices: \r at 5, \n at 6, \n at 12, \r at 18
    assertThat(GapScanner.findLastGuardByte(doubleGuard, text2, 0, text2.length() - 1))
        .isEqualTo(18);
    assertThat(GapScanner.findLastGuardByte(doubleGuard, text2, 0, 17)).isEqualTo(12);
    assertThat(GapScanner.findLastGuardByte(doubleGuard, text2, 0, 11)).isEqualTo(6);
    assertThat(GapScanner.findLastGuardByte(doubleGuard, text2, 0, 4)).isEqualTo(-1);

    byte[] tripleGuard = new byte[] {(byte) 'x', (byte) 'y', (byte) 'z'};
    String text3 = "a-x-b-y-c-z-d";
    // x at 2, y at 6, z at 10
    assertThat(GapScanner.findLastGuardByte(tripleGuard, text3, 0, text3.length() - 1))
        .isEqualTo(10);
    assertThat(GapScanner.findLastGuardByte(tripleGuard, text3, 0, 9)).isEqualTo(6);
    assertThat(GapScanner.findLastGuardByte(tripleGuard, text3, 0, 5)).isEqualTo(2);
    assertThat(GapScanner.findLastGuardByte(tripleGuard, text3, 0, 1)).isEqualTo(-1);
  }

  @Test
  void caseInsensitiveEndAnchoredGapMatching() {
    String regex = "(?i)user:[a-z]+-host:[a-z]+-status:[0-9]+$";
    Pattern pattern = Pattern.compile(regex);
    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();

    String input = "noise\n2026-09-11 USER:Alice-Host:PROD-Status:200\n";
    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regex).matcher(input);
    Matcher stringMatcher = pattern.matcher(input);

    assertThat(jdk.find()).isTrue();
    assertThat(stringMatcher.find()).isTrue();
    assertThat(stringMatcher.start()).isEqualTo(jdk.start());
    assertThat(stringMatcher.end()).isEqualTo(jdk.end());
    assertThat(stringMatcher.group()).isEqualTo(jdk.group());
    assertThat(stringMatcher.find()).isFalse();
  }

  @Test
  void caseInsensitiveVariableGapMatchingAndBacktracking() {
    String regex = "(?i)START.*?MIDDLE.*?FINAL";
    Pattern pattern = Pattern.compile(regex);
    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();
    assertThat(
            Pattern.compile("(?i)user:.*?host:.*?status:[0-9]+").multiAnchor().isExecutableChain())
        .isTrue();
    assertThat(
            Pattern.compile("(?i)user:.{0,100}?host:.{0,100}?status:[0-9]+")
                .multiAnchor()
                .isExecutableChain())
        .isTrue();

    String input = "prefix start_foo_middle_bar_middle_baz_final suffix";
    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regex).matcher(input);
    Matcher stringMatcher = pattern.matcher(input);

    assertThat(jdk.find()).isTrue();
    assertThat(stringMatcher.find()).isTrue();
    assertThat(stringMatcher.start()).isEqualTo(jdk.start());
    assertThat(stringMatcher.end()).isEqualTo(jdk.end());
    assertThat(stringMatcher.group()).isEqualTo(jdk.group());

    String logRegex = "(?i)user:.*?host:.*?status:[0-9]+";
    Pattern logPattern = Pattern.compile(logRegex);
    String logInput =
        """
        2026-08-27 12:00:00 [system] status:500 healthcheck
        2026-08-27 12:00:01 user:alice trace=abc-123 host:prod dc=iad outcome=ok
        2026-08-27 12:00:02 user:alice trace=xyz-987 host:prod dc=iad status:200
        """;
    Matcher logMatcher = logPattern.matcher(logInput);
    java.util.regex.Matcher jdkLogMatcher =
        java.util.regex.Pattern.compile(logRegex).matcher(logInput);
    assertThat(jdkLogMatcher.find()).isTrue();
    assertThat(logMatcher.find()).isTrue();
    assertThat(logMatcher.start()).isEqualTo(jdkLogMatcher.start());
    assertThat(logMatcher.end()).isEqualTo(jdkLogMatcher.end());
    assertThat(logMatcher.group()).isEqualTo(jdkLogMatcher.group());
    assertThat(logMatcher.find()).isFalse();
  }

  @Test
  void alternationAnchorInGapMatchesJdk() {
    String[] regexes = {
      "start:.*(foo|bar)",
      "start:.*?(foo|bar)",
      "(?i)start:.*(foo|bar)",
      "start:.*(foo|bar)zz",
      "start:[^;]*(foo|bar)",
    };
    String[] inputs = {
      "start:xxxfooyyy",
      "start:xxxbaryyy",
      "start:xxx",
      "start:barxxxfoo",
      "start:fooxxxbarzz",
      "START:xxxFOOyyy",
      "start:foo",
      "prefix start:zzzbar",
      "start:" + "q".repeat(200) + "bar",
    };
    for (String regex : regexes) {
      java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);
      Pattern pattern = Pattern.compile(regex);
      for (String input : inputs) {
        boolean jdkFound = jdkPattern.matcher(input).find();
        assertThat(pattern.matcher(input).find())
            .as("string %s / %s", regex, input)
            .isEqualTo(jdkFound);
        Utf8Matcher utf8Matcher = pattern.matcher(Utf8Input.validated(input.getBytes(UTF_8)));
        assertThat(utf8Matcher.find()).as("utf8 %s / %s", regex, input).isEqualTo(jdkFound);
      }
    }
  }

  @Test
  void reverseLiteralSearchReturnsRightmostCandidateInWindow() {
    // "user:" repeats, so the greedy reverse search has many candidates to reject.
    String text = "user:a user:b user:c user:d";
    for (boolean foldCase : new boolean[] {false, true}) {
      MultiAnchorDescriptor.Anchor anchor =
          MultiAnchorDescriptor.Anchor.Single.create("user:", foldCase);

      assertThat(anchor.lastIndexOf(text, 0, text.length())).isEqualTo(21);
      assertThat(anchor.lastIndexOf(text, 0, 20)).isEqualTo(14);
      assertThat(anchor.lastIndexOf(text, 0, 13)).isEqualTo(7);
      assertThat(anchor.lastIndexOf(text, 0, 6)).isEqualTo(0);
      assertThat(anchor.lastIndexOf(text, 1, 6)).isEqualTo(-1);
      assertThat(anchor.lastIndexOf(text, -5, 6)).isEqualTo(0);
      assertThat(anchor.lastIndexOf(text, 8, 13)).isEqualTo(-1);
    }
  }

  @Test
  void reverseLiteralSearchIsCaseSensitiveWhenNotFolding() {
    String text = "USER:a user:b USER:c";
    MultiAnchorDescriptor.Anchor exact = MultiAnchorDescriptor.Anchor.Single.create("user:");
    MultiAnchorDescriptor.Anchor folded = MultiAnchorDescriptor.Anchor.Single.create("user:", true);

    assertThat(exact.lastIndexOf(text, 0, text.length())).isEqualTo(7);
    assertThat(folded.lastIndexOf(text, 0, text.length())).isEqualTo(14);
  }

  @Test
  void reverseLiteralSearchRespectsSurrogatePairs() {
    // Indices: 0='a', 1=high surrogate, 2=low surrogate, 3='b', 4='c', 5='b', 6='c'.
    String text = "a\uD83D\uDE00bcbc";
    MultiAnchorDescriptor.Anchor bc = MultiAnchorDescriptor.Anchor.Single.create("bc");

    assertThat(bc.lastIndexOf(text, 0, text.length())).isEqualTo(5);
    assertThat(bc.lastIndexOf(text, 0, 4)).isEqualTo(3);

    // The only occurrence of the lone low surrogate splits a surrogate pair, so it is not a
    // candidate even though the raw characters match.
    MultiAnchorDescriptor.Anchor lowSurrogate =
        MultiAnchorDescriptor.Anchor.Single.create("\uDE00");
    assertThat(lowSurrogate.lastIndexOf(text, 0, text.length())).isEqualTo(-1);
  }

  @Test
  void caseInsensitiveUtf8EndAnchoredGapMatching() {
    String regex = "(?i)user:[a-z]+-host:[a-z]+-status:[0-9]+$";
    Pattern pattern = Pattern.compile(regex);
    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();

    String input = "noise\n2026-09-11 USER:Alice-Host:PROD-Status:200\n";
    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regex).matcher(input);
    assertThat(jdk.find()).isTrue();

    byte[] bytes = input.getBytes(UTF_8);
    Utf8Matcher utf8Matcher = pattern.matcher(Utf8Input.validated(bytes));

    assertThat(utf8Matcher.find()).isTrue();
    assertThat(utf8Matcher.start()).isEqualTo(jdk.start());
    assertThat(utf8Matcher.end()).isEqualTo(jdk.end());
    assertThat(
            new String(bytes, utf8Matcher.start(), utf8Matcher.end() - utf8Matcher.start(), UTF_8))
        .isEqualTo(jdk.group());
    assertThat(utf8Matcher.find()).isFalse();
  }

  @Test
  void caseInsensitiveUtf8GreedyGapMatching() {
    String regex = "(?i)user:.*status:[0-9]+";
    Pattern pattern = Pattern.compile(regex);
    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();

    String input = "noise USER:Alice intermediate HOST:prod extra details STATUS:200 trailing";
    byte[] bytes = input.getBytes(UTF_8);
    Utf8Matcher utf8Matcher = pattern.matcher(Utf8Input.validated(bytes));

    assertThat(utf8Matcher.find()).isTrue();
    assertThat(
            new String(bytes, utf8Matcher.start(), utf8Matcher.end() - utf8Matcher.start(), UTF_8))
        .isEqualTo("USER:Alice intermediate HOST:prod extra details STATUS:200");
    assertThat(utf8Matcher.find()).isFalse();
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
