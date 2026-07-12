// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.util.ConcurrentModificationException;
import org.junit.jupiter.api.Test;

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
}
