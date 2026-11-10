// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class Utf8ReplacementTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void wholeInputReplacementMatchesJdkOutputAndSubsequentSearch(boolean all) {
    String[][] cases = {
      {"(?<letter>é)(😀)?", "xé😀 éy", "${letter}-$2-$0"},
      {"[0-9]+", "a12b34c", ""},
      {"a", "aaa", "\\$0\\\\"},
      {"^é", "éé", "前"},
      {"é$", "éé", "後"},
      {"(?m)^é", "é\né", "x"},
      {".*mandatory-token.*", "no token here", "$"},
      {"z", "", "x"}
    };
    for (String[] testCase : cases) {
      String regex = testCase[0];
      String source = testCase[1];
      String replacement = testCase[2];
      Utf8Matcher matcher = matcher(regex, source);
      java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regex).matcher(source);
      String expected = all ? jdk.replaceAll(replacement) : jdk.replaceFirst(replacement);
      CollectingSink sink = new CollectingSink();

      replace(matcher, sink, input(replacement), all);

      assertThat(sink.toString()).as("/%s/ on %s, all=%s", regex, source, all).isEqualTo(expected);
      if (all) {
        assertThatThrownBy(matcher::start).isInstanceOf(IllegalStateException.class);
      }
      boolean next = jdk.find();
      assertThat(matcher.find()).isEqualTo(next);
      if (next) {
        assertThat(matcher.start())
            .isEqualTo(source.substring(0, jdk.start()).getBytes(UTF_8).length);
        assertThat(matcher.end()).isEqualTo(source.substring(0, jdk.end()).getBytes(UTF_8).length);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void wholeInputReplacementResetsRegionAndAppendPositionAndRetainsSinkContents(boolean all) {
    Utf8Matcher matcher = matcher("é", "é😀é").region(6, 8);
    CollectingSink sink = new CollectingSink();
    assertThat(matcher.find()).isTrue();
    matcher.appendReplacement(sink, input("old"));

    replace(matcher, sink, input("new"), all);

    assertThat(sink.toString()).isEqualTo(all ? "é😀oldnew😀new" : "é😀oldnew😀é");
    assertThat(matcher.regionStart()).isZero();
    assertThat(matcher.regionEnd()).isEqualTo(8);
    if (!all) {
      assertThat(matcher.start()).isZero();
      assertThat(matcher.end()).isEqualTo(2);
    }
    CollectingSink tail = new CollectingSink();
    matcher.appendTail(tail);
    assertThat(tail.toString()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void wholeInputReplacementMakesScalarProgressForEmptyMatches(boolean all) {
    CollectingSink sink = new CollectingSink();
    Utf8Matcher matcher = matcher("", "é😀");

    replace(matcher, sink, input("x"), all);

    assertThat(sink.toString()).isEqualTo(all ? "xéx😀x" : "xé😀");
    if (!all) {
      assertThat(matcher.start()).isZero();
      assertThat(matcher.end()).isZero();
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.start()).isEqualTo(2);
    }
    CollectingSink empty = new CollectingSink();
    replace(matcher("", ""), empty, input("x"), all);
    assertThat(empty.toString()).isEqualTo("x");
  }

  @Test
  @DisabledForCrosscheck(
      "SafeRE intentionally invalidates match state after terminal empty matches")
  void replaceAllInvalidatesTerminalEmptyMatch() {
    for (String source : List.of("", "é😀")) {
      Utf8Matcher matcher = matcher("", source);

      matcher.replaceAll(new CollectingSink(), input("x"));

      assertThatThrownBy(matcher::start).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(matcher::end).isInstanceOf(IllegalStateException.class);
      assertThat(matcher.find()).isFalse();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void wholeInputReplacementUsesSubjectAndReplacementWindows(boolean all) {
    byte[] source = "beforeé😀éafter".getBytes(UTF_8);
    byte[] replacement = "before[$0]後after".getBytes(UTF_8);
    Utf8Matcher matcher = Pattern.compile("é").matcher(Utf8Input.validated(source, 6, 8));
    CollectingSink sink = new CollectingSink();

    replace(matcher, sink, Utf8Input.validated(replacement, 6, 7), all);

    assertThat(sink.toString()).isEqualTo(all ? "[é]後😀[é]後" : "[é]後😀é");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void wholeInputReplacementWithoutMatchesBorrowsOriginalRangeAndSkipsInvalidTemplate(boolean all) {
    byte[] source = "beforeé😀after".getBytes(UTF_8);
    Utf8Matcher matcher = Pattern.compile("z").matcher(Utf8Input.validated(source, 6, 6));
    List<Range> ranges = new ArrayList<>();

    replace(
        matcher,
        (bytes, offset, length) -> ranges.add(new Range(bytes, offset, length)),
        input("${missing}"),
        all);

    assertThat(ranges)
        .singleElement()
        .satisfies(range -> assertThat(range.bytes()).isSameAs(source));
    assertThat(ranges).extracting(Range::offset, Range::length).containsExactly(tuple(6, 6));
    assertThatThrownBy(matcher::start).isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void wholeInputReplacementRejectsInvalidTemplateBeforeWritingAndCanBeRetried(boolean all) {
    Utf8Matcher matcher = matcher("a", "xaxa");
    CollectingSink sink = new CollectingSink();

    assertThatThrownBy(() -> replace(matcher, sink, input("${missing}"), all))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(sink.toString()).isEmpty();
    assertThatThrownBy(() -> matcher.appendTail(sink)).isInstanceOf(IllegalStateException.class);

    replace(matcher, sink, input("b"), all);

    assertThat(sink.toString()).isEqualTo(all ? "xbxb" : "xbxa");
  }

  @ParameterizedTest
  @EnumSource(ReplacementOperation.class)
  void invalidNumberedReferencesFailBeforeWriting(ReplacementOperation operation) {
    for (int groupCount : new int[] {0, 1, 2, 8}) {
      String regex = "(".repeat(groupCount) + "b" + ")".repeat(groupCount);
      int invalidGroup = groupCount + 1;
      for (String replacement :
          List.of("$" + invalidGroup, "[$" + invalidGroup + "]", "é😀$0-$" + invalidGroup)) {
        for (boolean window : new boolean[] {false, true}) {
          String subject = "前é😀ab後b";
          Utf8Input source = windowedInput(subject, window);
          Utf8Input template = windowedInput(replacement, window);
          Utf8Matcher matcher = Pattern.compile(regex).matcher(source);
          CollectingSink sink = new CollectingSink();

          assertThatThrownBy(() -> replace(matcher, sink, template, operation))
              .as("%s /%s/ template %s, window=%s", operation, regex, replacement, window)
              .isExactlyInstanceOf(IndexOutOfBoundsException.class)
              .hasMessage("No group %s (groupCount=%s)", invalidGroup, groupCount);
          assertThat(sink.toString()).isEmpty();
          assertThatThrownBy(() -> matcher.appendTail(sink))
              .isInstanceOf(IllegalStateException.class);
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void invalidNumberedReferencesRemainLazyWithoutMatches(boolean all) {
    for (String replacement : List.of("$1", "[$9]", "é😀$0-$2")) {
      CollectingSink sink = new CollectingSink();
      replace(matcher("b", "前é😀a後"), sink, input(replacement), all);
      assertThat(sink.toString()).isEqualTo("前é😀a後");
    }
  }

  @ParameterizedTest
  @EnumSource(ReplacementOperation.class)
  void numberedReferenceDigitsRespectAvailableGroups(ReplacementOperation operation) {
    record Case(int groups, String replacement) {}
    for (Case c :
        List.of(
            new Case(0, "$00"),
            new Case(0, "$01"),
            new Case(1, "$10"),
            new Case(1, "$12"),
            new Case(2, "$20"),
            new Case(10, "$10"),
            new Case(10, "$11"))) {
      String regex = "(".repeat(c.groups()) + "b" + ")".repeat(c.groups());
      String subject = "前é😀ab後b";
      Matcher stringMatcher = Pattern.compile(regex).matcher(subject);
      String expected =
          operation == ReplacementOperation.ALL
              ? stringMatcher.replaceAll(c.replacement())
              : stringMatcher.replaceFirst(c.replacement());
      CollectingSink sink = new CollectingSink();

      Utf8Matcher utf8 = matcher(regex, subject);
      replace(utf8, sink, input(c.replacement()), operation);
      if (operation == ReplacementOperation.APPEND) {
        utf8.appendTail(sink);
      }
      assertThat(sink.toString()).isEqualTo(expected);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void wholeInputReplacementPreservesPartialOutputOnSinkFailure(boolean all) {
    Utf8Matcher matcher = matcher("a", "xaxa");
    CollectingSink sink = new CollectingSink();

    assertThatThrownBy(
            () ->
                replace(
                    matcher,
                    (bytes, offset, length) -> {
                      sink.append(bytes, offset, length);
                      throw new SinkFailure();
                    },
                    input("b"),
                    all))
        .isInstanceOf(SinkFailure.class);
    assertThat(sink.toString()).isEqualTo("x");
    assertThat(matcher.start()).isEqualTo(1);
    assertThat(matcher.end()).isEqualTo(2);
    assertThatThrownBy(() -> matcher.appendTail(sink)).isInstanceOf(IllegalStateException.class);

    CollectingSink retry = new CollectingSink();
    replace(matcher, retry, input("b"), all);
    assertThat(retry.toString()).isEqualTo(all ? "xbxb" : "xbxa");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void wholeInputReplacementSinkCanObserveCurrentCaptureBounds(boolean all) {
    Utf8Matcher matcher = matcher("(é)", "xéyéz");
    CollectingSink sink = new CollectingSink();
    List<Integer> captureStarts = new ArrayList<>();

    replace(
        matcher,
        (bytes, offset, length) -> {
          if (length == 1 && bytes[offset] == '[') {
            captureStarts.add(matcher.start(1));
          }
          sink.append(bytes, offset, length);
        },
        input("[$1]"),
        all);

    assertThat(sink.toString()).isEqualTo(all ? "x[é]y[é]z" : "x[é]yéz");
    assertThat(captureStarts).isEqualTo(all ? List.of(1, 4) : List.of(1));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void wholeInputReplacementSinkCannotResetOrReplaceMatcher(boolean all) {
    for (boolean hasMatch : new boolean[] {false, true}) {
      Utf8Matcher matcher = matcher(hasMatch ? "a" : "z", "xaxa");
      CollectingSink sink = new CollectingSink();
      assertThatThrownBy(
              () ->
                  replace(
                      matcher,
                      (bytes, offset, length) -> replace(matcher, sink, input("nested"), all),
                      input("b"),
                      all))
          .isInstanceOf(ConcurrentModificationException.class);
      assertThat(sink.toString()).isEmpty();
      assertThatThrownBy(() -> matcher.appendTail(sink)).isInstanceOf(IllegalStateException.class);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void wholeInputReplacementRejectsNullArgumentsWithoutResettingMatcher(boolean all) {
    Utf8Matcher matcher = matcher("a", "aa");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.find()).isTrue();

    assertThatThrownBy(() -> replace(matcher, null, input("b"), all))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> replace(matcher, new CollectingSink(), null, all))
        .isInstanceOf(NullPointerException.class);
    assertThat(matcher.start()).isEqualTo(1);
    assertThat(matcher.end()).isEqualTo(2);
  }

  @Test
  void numberedAndNamedGroupsAreExpandedWithoutDecodingTheSubject() {
    assertThat(replace("(?<letter>é)(😀)?", "xé😀 éy", "${letter}-$2-$0"))
        .isEqualTo("xé-😀-é😀 é--éy");
  }

  @ParameterizedTest
  @ValueSource(strings = {"EMAIL", "<$0>", "$0$0", "$2", "${user}@$2", "\\$1", "x\\\\y"})
  void replacementsMatchStringReplaceAllWithInnerGroups(String replacement) {
    String regex = "(?<user>\\w+)@(\\w+)";
    String source = "a@b café@hôte 😀 x@y";
    assertThat(replace(regex, source, replacement))
        .isEqualTo(Pattern.compile(regex).matcher(source).replaceAll(replacement));
  }

  @Test
  void escapedReplacementCharactersAreLiteral() {
    assertThat(replace("a", "a", "\\$0\\\\")).isEqualTo("$0\\");
  }

  @Test
  void emptyMatchesMakeCodePointProgress() {
    assertThat(replace("", "😀", "x")).isEqualTo("x😀x");
  }

  @Test
  void malformedReplacementIsRejected() {
    Utf8Matcher matcher = matcher("(a)", "a");
    assertThat(matcher.find()).isTrue();
    CollectingSink sink = new CollectingSink();

    assertThatThrownBy(() -> matcher.appendReplacement(sink, input("${missing}")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(sink.toString()).isEmpty();
    assertThatThrownBy(() -> matcher.appendTail(sink)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void sinkFailureInvalidatesOnlyReplacementState() {
    Utf8Matcher matcher = matcher("(a)", "a");
    assertThat(matcher.find()).isTrue();

    assertThatThrownBy(
            () ->
                matcher.appendReplacement(
                    (bytes, offset, length) -> {
                      throw new SinkFailure();
                    },
                    input("$1")))
        .isInstanceOf(SinkFailure.class);
    assertThat(matcher.start()).isZero();
    assertThat(matcher.end()).isEqualTo(1);
    assertThatThrownBy(() -> matcher.appendTail(new CollectingSink()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void sinkCannotAdvanceMatcherReentrantly() {
    Utf8Matcher matcher = matcher("a", "aa");
    assertThat(matcher.find()).isTrue();

    assertThatThrownBy(
            () ->
                matcher.appendReplacement(
                    (bytes, offset, length) -> matcher.find(), input("replacement")))
        .isInstanceOf(ConcurrentModificationException.class);
    assertThat(matcher.start()).isZero();
    assertThat(matcher.end()).isEqualTo(1);
  }

  @Test
  void replacementWorksOnNonzeroOffsetWindows() {
    byte[] logical = "xéy".getBytes(UTF_8);
    byte[] storage = new byte[logical.length + 6];
    System.arraycopy(logical, 0, storage, 3, logical.length);
    Utf8Input source = Utf8Input.validated(storage, 3, logical.length);
    Utf8Matcher matcher = Pattern.compile("(é)").matcher(source);
    CollectingSink sink = new CollectingSink();

    while (matcher.find()) {
      matcher.appendReplacement(sink, input("[$1]"));
    }
    matcher.appendTail(sink);

    assertThat(sink.toString()).isEqualTo("x[é]y");
  }

  @Test
  void unmatchedAndCapturedRangesUseOriginalSubjectStorage() {
    byte[] logical = "xéy".getBytes(UTF_8);
    byte[] storage = new byte[logical.length + 6];
    System.arraycopy(logical, 0, storage, 3, logical.length);
    Utf8Matcher matcher =
        Pattern.compile("(é)").matcher(Utf8Input.validated(storage, 3, logical.length));
    List<Range> ranges = new ArrayList<>();
    Utf8Sink sink = (bytes, offset, length) -> ranges.add(new Range(bytes, offset, length));

    assertThat(matcher.find()).isTrue();
    matcher.appendReplacement(sink, input("$1"));
    matcher.appendTail(sink);

    assertThat(ranges).hasSize(3);
    for (Range range : ranges) {
      assertThat(range.bytes()).isSameAs(storage);
    }
    assertThat(ranges)
        .extracting(Range::offset, Range::length)
        .containsExactly(tuple(3, 1), tuple(4, 2), tuple(6, 1));
  }

  @Test
  void replacementWorksOnNonzeroOffsetReplacementWindow() {
    byte[] logical = "前${letter}後".getBytes(UTF_8);
    byte[] storage = new byte[logical.length + 8];
    System.arraycopy(logical, 0, storage, 4, logical.length);
    Utf8Input replacement = Utf8Input.validated(storage, 4, logical.length);
    Utf8Matcher matcher = matcher("(?<letter>é)", "xéy");
    CollectingSink sink = new CollectingSink();

    assertThat(matcher.find()).isTrue();
    matcher.appendReplacement(sink, replacement);
    matcher.appendTail(sink);

    assertThat(sink.toString()).isEqualTo("x前é後y");
  }

  @ParameterizedTest
  @ValueSource(strings = {"\\", "$", "${", "${name", "$x"})
  void invalidReplacementSyntaxFailsBeforeWriting(String replacement) {
    Utf8Matcher matcher = matcher("(a)", "xa");
    CollectingSink sink = new CollectingSink();
    assertThat(matcher.find()).isTrue();

    assertThatThrownBy(() -> matcher.appendReplacement(sink, input(replacement)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(sink.toString()).isEmpty();
    assertThatThrownBy(() -> matcher.appendTail(sink)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void resetRecoversReplacementStateAndAppendPosition() {
    Utf8Matcher matcher = matcher("(a)", "xa");
    assertThat(matcher.find()).isTrue();
    assertThatThrownBy(() -> matcher.appendReplacement(new CollectingSink(), input("${missing}")))
        .isInstanceOf(IllegalArgumentException.class);

    matcher.reset();
    CollectingSink sink = new CollectingSink();
    assertThat(matcher.find()).isTrue();
    matcher.appendReplacement(sink, input("b"));
    matcher.appendTail(sink);

    assertThat(sink.toString()).isEqualTo("xb");
  }

  @Test
  void replacementTemplateIsCompiledOnceAcrossRepeatedMatches() {
    Utf8Matcher matcher = matcher("a", "aaa");
    byte[] expected = "é".getBytes(UTF_8);
    List<byte[]> literalStorages = new ArrayList<>();
    Utf8Sink sink =
        (bytes, offset, length) -> {
          if (length == expected.length
              && ByteBuffer.wrap(bytes, offset, length).mismatch(ByteBuffer.wrap(expected)) == -1) {
            literalStorages.add(bytes);
          }
        };
    Utf8Input replacement = input("é");

    while (matcher.find()) {
      matcher.appendReplacement(sink, replacement);
    }
    matcher.appendTail(sink);

    assertThat(literalStorages).hasSize(3);
    for (byte[] literalStorage : literalStorages) {
      assertThat(literalStorage).isSameAs(literalStorages.getFirst());
    }
  }

  @Test
  void appendPositionIsSharedAcrossSinkInstances() {
    Utf8Matcher matcher = matcher("[0-9]", "a1b2c");
    CollectingSink first = new CollectingSink();
    CollectingSink second = new CollectingSink();

    assertThat(matcher.find()).isTrue();
    matcher.appendReplacement(first, input("X"));
    assertThat(matcher.find()).isTrue();
    matcher.appendReplacement(second, input("Y"));
    matcher.appendTail(second);

    assertThat(first.toString()).isEqualTo("aX");
    assertThat(second.toString()).isEqualTo("bYc");
  }

  @Test
  void requiredCapturedClassPrefilterPreservesReplacementResults() {
    assertThat(replace(".*(x|y).*", "aaaaaaaa", "X")).isEqualTo("aaaaaaaa");
    assertThat(replace(".*(x|y).*", "aaaayaaa", "[$1]")).isEqualTo("[y]");
    assertThat(replace(".*(cat|dog).*", "birds only", "X")).isEqualTo("birds only");
    assertThat(replace(".*(cat|dog).*", "a dog appears", "[$1]")).isEqualTo("[dog]");
    assertThat(replace(".*(α|β).*", "γδ", "X")).isEqualTo("γδ");
    assertThat(replace(".*(α|β).*", "γβδ", "[$1]")).isEqualTo("[β]");
  }

  @Test
  void requiredLiteralPrefilterPreservesReplacementResults() {
    assertThat(replace(".*coolfunctionname.*", "cool-function-name", "X"))
        .isEqualTo("cool-function-name");
    assertThat(replace(".*coolfunctionname.*", "before coolfunctionname after", "X"))
        .isEqualTo("X");
    assertThat(replace(".*mandatory-token.*", "mandatory token", "X")).isEqualTo("mandatory token");
    assertThat(replace(".*mandatory-token.*", "before mandatory-token after", "X")).isEqualTo("X");
    assertThat(replace(".*必要語.*", "これは不要です", "X")).isEqualTo("これは不要です");
    assertThat(replace(".*必要語.*", "これは必要語です", "X")).isEqualTo("X");
  }

  @Test
  @DisplayName("array and buffer sink dispatch preserve chunk boundaries and caller position")
  void sinkDispatchPreservesChunksAndBufferPosition() {
    CollectingSink sink = new CollectingSink();
    ByteBuffer direct = ByteBuffer.allocateDirect(4).put("xéz".getBytes(UTF_8));
    direct.flip();
    direct.position(1);
    int originalPosition = direct.position();

    sink.append("α".getBytes(UTF_8), 0, "α".getBytes(UTF_8).length);
    sink.append(direct);

    assertThat(sink.toString()).isEqualTo("αéz");
    assertThat(direct.position()).isEqualTo(originalPosition);
  }

  private enum ReplacementOperation {
    ALL,
    FIRST,
    APPEND
  }

  private static void replace(
      Utf8Matcher matcher, Utf8Sink sink, Utf8Input replacement, ReplacementOperation operation) {
    switch (operation) {
      case ALL -> matcher.replaceAll(sink, replacement);
      case FIRST -> matcher.replaceFirst(sink, replacement);
      case APPEND -> {
        assertThat(matcher.find()).isTrue();
        matcher.appendReplacement(sink, replacement);
      }
    }
  }

  private static Utf8Input windowedInput(String text, boolean window) {
    if (!window) {
      return input(text);
    }
    byte[] storage = ("before" + text + "after").getBytes(UTF_8);
    return Utf8Input.validated(storage, 6, text.getBytes(UTF_8).length);
  }

  private static void replace(
      Utf8Matcher matcher, Utf8Sink sink, Utf8Input replacement, boolean all) {
    if (all) {
      matcher.replaceAll(sink, replacement);
    } else {
      matcher.replaceFirst(sink, replacement);
    }
  }

  private static String replace(String pattern, String source, String replacement) {
    Utf8Matcher matcher = matcher(pattern, source);
    CollectingSink sink = new CollectingSink();
    while (matcher.find()) {
      matcher.appendReplacement(sink, input(replacement));
    }
    matcher.appendTail(sink);
    return sink.toString();
  }

  private static Utf8Matcher matcher(String pattern, String input) {
    return Pattern.compile(pattern).matcher(Utf8ReplacementTest.input(input));
  }

  private static Utf8Input input(String input) {
    return Utf8Input.validated(input.getBytes(UTF_8));
  }

  private static final class CollectingSink implements Utf8Sink {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    @Override
    public void append(byte[] bytes, int offset, int length) {
      output.write(bytes, offset, length);
    }

    @Override
    public String toString() {
      return output.toString(UTF_8);
    }
  }

  private static final class SinkFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  private static final class Range {
    private final byte[] bytes;
    private final int offset;
    private final int length;

    Range(byte[] bytes, int offset, int length) {
      this.bytes = bytes;
      this.offset = offset;
      this.length = length;
    }

    byte[] bytes() {
      return bytes;
    }

    int offset() {
      return offset;
    }

    int length() {
      return length;
    }
  }
}
