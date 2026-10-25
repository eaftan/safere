// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Capture consumption is a Pattern hint independent of how a result was produced. */
@DisabledForCrosscheck("asserts SafeRE's internal adaptive capture policy")
class CaptureDemandTest {
  private static final EnginePathOptions EXACT =
      EnginePathOptions.builder().onePass(false).dfa(false).build();

  static Stream<Arguments> captureReads() {
    return Stream.<Consumer<Matcher>>of(
            m -> m.start(1),
            m -> m.end(1),
            m -> m.group(1),
            m -> m.start("value"),
            m -> m.end("value"),
            m -> m.group("value"),
            Matcher::toMatchResult)
        .flatMap(read -> Stream.of(false, true).map(eager -> Arguments.of(read, eager)));
  }

  @ParameterizedTest
  @MethodSource("captureReads")
  void consumptionRecordsDemandForResolvedAndDeferredResults(Consumer<Matcher> read, boolean eager)
      throws Exception {
    Pattern pattern =
        Pattern.compile(
            "a\\s+(?<value>.+)",
            0,
            EnginePathOptions.builder()
                .onePass(false)
                .dfa(!eager)
                .lazyCaptureExtraction(!eager)
                .build());
    Matcher matcher = pattern.matcher("a value");
    assertThat(matcher.find()).isTrue();
    assertThat(resolved(matcher)).isEqualTo(eager);
    assertThat(pattern.innerCapturesObserved()).isFalse();
    read.accept(matcher);
    assertThat(pattern.innerCapturesObserved()).isTrue();
    assertThat(matcher.group("value")).isEqualTo("value");
  }

  @ParameterizedTest
  @ValueSource(strings = {"matches", "lookingAt", "find"})
  void eagerResultsPropagateDemandToOtherMatchers(String operation) {
    Pattern pattern =
        Pattern.compile(
            "(a+)(b+)", 0, EnginePathOptions.builder().lazyCaptureExtraction(false).build());
    Matcher matcher = pattern.matcher("aaabb");
    assertThat(match(matcher, operation)).isTrue();
    assertThat(pattern.innerCapturesObserved()).isFalse();
    assertThat(matcher.group(1)).isEqualTo("aaa");
    assertThat(pattern.innerCapturesObserved()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void utf8CaptureAccessRecordsDemandEvenWhenAlreadyResolved(boolean eager) {
    Pattern pattern =
        Pattern.compile(
            "a\\s+(é+)",
            0,
            EnginePathOptions.builder()
                .onePass(false)
                .dfa(!eager)
                .lazyCaptureExtraction(!eager)
                .build());
    Utf8Matcher matcher = pattern.matcher(Utf8Input.trusted("a éé".getBytes(UTF_8)));
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start(1)).isEqualTo(2);
    assertThat(matcher.end(1)).isEqualTo(6);
    assertThat(pattern.innerCapturesObserved()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"$1", "${value}"})
  void anchoredReplacementRecordsAlreadyResolvedCaptureConsumption(String replacement) {
    Pattern pattern = Pattern.compile("^(?<value>a+)$");
    assertThat(pattern.matcher("aaa").replaceAll(replacement)).isEqualTo("aaa");
    assertThat(pattern.innerCapturesObserved()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"$1", "${value}"})
  void utf8ReplacementRecordsAlreadyResolvedCaptureConsumption(String replacement) {
    Pattern pattern =
        Pattern.compile(
            "(?<value>a+)",
            0,
            EnginePathOptions.builder().dfa(false).lazyCaptureExtraction(false).build());
    Utf8Matcher matcher = pattern.matcher(Utf8Input.trusted("aaa".getBytes(UTF_8)));
    assertThat(matcher.find()).isTrue();
    matcher.appendReplacement(
        (bytes, offset, length) -> {}, Utf8Input.trusted(replacement.getBytes(UTF_8)));
    assertThat(pattern.innerCapturesObserved()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"$0", "\\$1", "literal"})
  void fallbackReplacementWithoutInnerReferencesDoesNotCreateDemand(String replacement)
      throws Exception {
    Pattern pattern = Pattern.compile("x(a+)y", 0, EXACT);
    Matcher matcher = pattern.matcher("xaay");
    String expected =
        java.util.regex.Pattern.compile(pattern.pattern())
            .matcher("xaay")
            .replaceFirst(replacement);
    assertThat(matcher.replaceFirst(replacement)).isEqualTo(expected);
    assertThat(pattern.innerCapturesObserved()).isFalse();
    matcher.reset();
    assertThat(matcher.find()).isTrue();
    assertThat(resolved(matcher)).isFalse();
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void exactFallbackUsesDemandFromOtherMatchers(boolean utf8, boolean bitState) throws Exception {
    Pattern pattern =
        Pattern.compile("(a?)*y", 0, EnginePathOptions.builder().bitState(bitState).build());
    String text = "a".repeat(1023) + "y";
    Matcher first = matcher(pattern, text, utf8);
    assertThat(first.find()).isTrue();
    assertThat(first.start(1)).isGreaterThanOrEqualTo(0);
    Matcher second = matcher(pattern, text, utf8);
    assertThat(second.find()).isTrue();
    assertThat(resolved(second)).isTrue();
    assertThat(second.start(1)).isEqualTo(first.start(1));
    assertThat(second.end(1)).isEqualTo(first.end(1));
  }

  @Test
  void internalResolutionAndGroupZeroAccessDoNotCreateDemand() {
    Pattern pattern = Pattern.compile("a\\s+(.+)");
    Matcher matcher = pattern.matcher("a value");
    assertThat(matcher.find()).isTrue();
    matcher.useAnchoringBounds(false);
    matcher.useTransparentBounds(true);
    assertThat(matcher.group()).isEqualTo("a value");
    assertThat(matcher.start()).isZero();
    assertThat(matcher.end()).isEqualTo(7);
    assertThat(pattern.innerCapturesObserved()).isFalse();
    assertThat(matcher.group(1)).isEqualTo("value");
    assertThat(pattern.innerCapturesObserved()).isTrue();
  }

  @Test
  void invalidAccessAndSnapshotsWithoutSuccessfulCapturesDoNotCreateDemand() {
    Pattern pattern = Pattern.compile("(a+)");
    Matcher matcher = pattern.matcher("bbb");
    matcher.toMatchResult();
    assertThat(matcher.find()).isFalse();
    matcher.toMatchResult();
    assertThatThrownBy(() -> matcher.group(1)).isInstanceOf(IllegalStateException.class);
    assertThat(pattern.innerCapturesObserved()).isFalse();
    matcher.reset("aaa");
    assertThat(matcher.find()).isTrue();
    assertThatThrownBy(() -> matcher.group(2)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> matcher.group("missing")).isInstanceOf(IllegalArgumentException.class);
    assertThat(pattern.innerCapturesObserved()).isFalse();
  }

  @Test
  void emptySnapshotsDoNotMakeFutureFallbackSearchesEager() throws Exception {
    Pattern pattern = Pattern.compile("x(a+)y", 0, EXACT);
    Matcher matcher = pattern.matcher("xaay");
    matcher.toMatchResult();
    assertThat(matcher.find()).isTrue();
    assertThat(resolved(matcher)).isFalse();
    assertThat(matcher.find()).isFalse();
    matcher.toMatchResult();
    matcher.reset();
    assertThat(matcher.find()).isTrue();
    assertThat(resolved(matcher)).isFalse();
  }

  @Test
  void unsuccessfulReplacementDoesNotCreateFutureDemand() throws Exception {
    Pattern pattern = Pattern.compile("x(a+)y", 0, EXACT);
    Matcher matcher = pattern.matcher("no match");
    assertThat(matcher.replaceAll("$1")).isEqualTo("no match");
    assertThat(pattern.innerCapturesObserved()).isFalse();
    matcher.reset("xaay");
    assertThat(matcher.find()).isTrue();
    assertThat(resolved(matcher)).isFalse();
  }

  @Test
  void snapshotsAndStreamsRecordConsumptionOfEagerCaptures() {
    Pattern snapshot = Pattern.compile("(a+)");
    Matcher matcher = snapshot.matcher("aaa");
    assertThat(matcher.matches()).isTrue();
    assertThat(matcher.toMatchResult().group(1)).isEqualTo("aaa");
    assertThat(snapshot.innerCapturesObserved()).isTrue();
    Pattern stream =
        Pattern.compile(
            "(a+)", 0, EnginePathOptions.builder().lazyCaptureExtraction(false).build());
    assertThat(stream.matcher("aaa").results().findFirst().orElseThrow().group(1)).isEqualTo("aaa");
    assertThat(stream.innerCapturesObserved()).isTrue();
  }

  @Test
  void functionalReplacementPromisesSnapshotCapturesEvenForLiteralOutput() {
    Pattern pattern =
        Pattern.compile(
            "(a+)", 0, EnginePathOptions.builder().lazyCaptureExtraction(false).build());
    assertThat(pattern.matcher("aaa").replaceAll(result -> "x")).isEqualTo("x");
    assertThat(pattern.innerCapturesObserved()).isTrue();
  }

  @Test
  void demandSurvivesInputAndRegionChangesButBelongsToThePattern() throws Exception {
    Pattern first = Pattern.compile("a\\s+(.+)", 0, EXACT);
    Matcher matcher = first.matcher("a first");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(1)).isEqualTo("first");
    matcher.reset("a second").region(0, 8).useAnchoringBounds(false);
    assertThat(matcher.find()).isTrue();
    assertThat(resolved(matcher)).isTrue();
    Pattern second = Pattern.compile("a\\s+(.+)", 0, EXACT);
    matcher.usePattern(second).reset();
    assertThat(matcher.find()).isTrue();
    assertThat(resolved(matcher)).isFalse();
    assertThat(second.innerCapturesObserved()).isFalse();
    matcher.usePattern(first).reset();
    assertThat(matcher.find()).isTrue();
    assertThat(resolved(matcher)).isTrue();
  }

  private static boolean match(Matcher matcher, String operation) {
    return switch (operation) {
      case "matches" -> matcher.matches();
      case "lookingAt" -> matcher.lookingAt();
      case "find" -> matcher.find();
      default -> throw new AssertionError(operation);
    };
  }

  private static Matcher matcher(Pattern pattern, String text, boolean utf8) throws Exception {
    if (!utf8) {
      return pattern.matcher(text);
    }
    Utf8Matcher matcher = pattern.matcher(Utf8Input.trusted(text.getBytes(UTF_8)));
    Field delegate = Utf8Matcher.class.getDeclaredField("delegate");
    delegate.setAccessible(true);
    return (Matcher) delegate.get(matcher);
  }

  private static boolean resolved(Matcher matcher) throws Exception {
    Field field = Matcher.class.getDeclaredField("capturesResolved");
    field.setAccessible(true);
    return field.getBoolean(matcher);
  }
}
