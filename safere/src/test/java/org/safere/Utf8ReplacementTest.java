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
import org.junit.jupiter.params.provider.ValueSource;

class Utf8ReplacementTest {
  @Test
  void numberedAndNamedGroupsAreExpandedWithoutDecodingTheSubject() {
    assertThat(replace("(?<letter>é)(😀)?", "xé😀 éy", "${letter}-$2-$0"))
        .isEqualTo("xé-😀-é😀 é--éy");
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
