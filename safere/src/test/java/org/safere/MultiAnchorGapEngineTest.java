// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.safere.MultiAnchorDescriptor.GapKind;
import org.safere.MultiAnchorDescriptor.RejectPlan;
import org.safere.MultiAnchorDescriptor.StartPlan;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class MultiAnchorGapEngineTest {

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

    assertThat(pattern.multiAnchor().isExecutableChain()).isFalse();

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
  void unboundedInteriorGapsFallBackToGeneralEngine() {
    assertThat(Pattern.compile("AAA.*BBB.*CCC").multiAnchor().isExecutableChain()).isFalse();
  }

  @Test
  void boundedInteriorGapsRemainExecutable() {
    assertThat(Pattern.compile("AAA[0-9]+BBB").multiAnchor().isExecutableChain()).isTrue();
    assertThat(Pattern.compile("AAA[0-9]BBB").multiAnchor().isExecutableChain()).isTrue();
    assertThat(Pattern.compile(".*AAA\\s+BBB\\s+CCC.*").multiAnchor().isExecutableChain()).isTrue();
  }

  @Test
  void ambiguousInteriorWildcardMatchesCorrectlyViaGeneralEngine() {
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
  void multipleLeadingWildcardsCoalesce() {
    Pattern pattern = Pattern.compile(".*.*AAA.*.*");
    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();
    assertFirstMatchEqualsJdk(".*.*AAA.*.*", "hello world AAA foo bar\nnext line");
  }

  @Test
  void singleAnchorWithLeadingAndTrailingGapsExecutes() {
    Pattern pattern = Pattern.compile(".*AAA.*");
    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();
    assertFirstMatchEqualsJdk(".*AAA.*", "noise AAA trailing\nsecond line");

    Pattern patternBounded = Pattern.compile("\\s+AAA\\s+");
    assertThat(patternBounded.multiAnchor().isExecutableChain()).isTrue();
    assertFirstMatchEqualsJdk("\\s+AAA\\s+", "hello   AAA   world");
  }

  @Test
  void singleAnchorWithLeadingWildcardExecutesInUtf8() {
    Pattern pattern = Pattern.compile(".*TARGET_KEY");
    assertThat(pattern.multiAnchor().isExecutableChain()).isTrue();

    byte[] bytes = "prefix data TARGET_KEY trailing".getBytes(UTF_8);
    Utf8Input input = Utf8Input.validated(bytes);
    Utf8Matcher matcher = pattern.matcher(input);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(0);
    assertThat(matcher.end()).isEqualTo(22);
  }

  private static void assertFirstMatchEqualsJdk(String regex, String text) {
    Matcher safere = Pattern.compile(regex).matcher(text);
    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regex).matcher(text);

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

  private static List<String> findMatches(Pattern pattern, String text, boolean useUtf8) {
    List<String> matches = new ArrayList<>();
    if (useUtf8) {
      Utf8Matcher matcher = pattern.matcher(Utf8Input.validated(text.getBytes(UTF_8)));
      while (matcher.find()) {
        matches.add(text.substring(matcher.start(), matcher.end()));
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
