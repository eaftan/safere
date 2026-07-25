// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.crosscheck;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class Utf8CrosscheckTest {
  @Test
  void validatedInputCrosschecksRepeatedFindAndCaptureByteBounds() {
    Utf8Matcher matcher =
        Pattern.compile("(é)|(😀)").matcher(Utf8Input.validated("xé😀y".getBytes(UTF_8)));

    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(1);
    assertThat(matcher.end()).isEqualTo(3);
    assertThat(matcher.start(1)).isEqualTo(1);
    assertThat(matcher.end(1)).isEqualTo(3);
    assertThat(matcher.start(2)).isEqualTo(-1);
    assertThat(matcher.end(2)).isEqualTo(-1);

    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(3);
    assertThat(matcher.end()).isEqualTo(7);
    assertThat(matcher.start(1)).isEqualTo(-1);
    assertThat(matcher.start(2)).isEqualTo(3);
    assertThat(matcher.end(2)).isEqualTo(7);
    assertThat(matcher.find()).isFalse();
  }

  @Test
  void emptyFindSkipsJdkPositionsInsideSupplementaryScalars() {
    Utf8Matcher matcher = Pattern.compile("").matcher(Utf8Input.validated("😀".getBytes(UTF_8)));

    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isZero();
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(4);
    assertThat(matcher.find()).isFalse();
  }

  @Test
  void logicalWindowCoordinatesRemainRelative() {
    byte[] logical = "xéy".getBytes(UTF_8);
    byte[] storage = new byte[logical.length + 8];
    System.arraycopy(logical, 0, storage, 4, logical.length);

    Utf8Matcher matcher =
        Pattern.compile("é").matcher(Utf8Input.validated(storage, 4, logical.length));

    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(1);
    assertThat(matcher.end()).isEqualTo(3);
  }

  @Test
  void observationsCrosscheckSuccessAndExceptionStates() {
    Utf8Matcher matcher = Pattern.compile("(a)").matcher(input("a"));

    assertThatThrownBy(matcher::start).isInstanceOf(IllegalStateException.class);
    assertThat(matcher.groupCount()).isEqualTo(1);
    assertThat(matcher.find()).isTrue();
    assertThatThrownBy(() -> matcher.start(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> matcher.end(2)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThat(matcher.find()).isFalse();
    assertThatThrownBy(matcher::end).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void replacementOutputIsCrosscheckedCallByCall() {
    Utf8Matcher matcher = Pattern.compile("(?<letter>é)").matcher(input("xéyéz"));
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    while (matcher.find()) {
      matcher.appendReplacement(output::write, input("${letter}${letter}"));
    }
    matcher.appendTail(output::write);

    assertThat(output.toString(UTF_8)).isEqualTo("xééyééz");
  }

  @Test
  void captureFreeFindUsesDecodedOraclesForValidatedInput() {
    Pattern pattern = Pattern.compile("😀$");

    assertThat(pattern.find(input("x😀"))).isTrue();
    assertThat(pattern.find(input("😀x"))).isFalse();
  }

  @Test
  void trustedMalformedInputRunsWithoutSemanticOracle() {
    Utf8Input malformed = Utf8Input.trusted(new byte[] {'a', (byte) 0x80, 'b'});

    Utf8Matcher matcher = Pattern.compile(".").matcher(malformed);
    while (matcher.find()) {
      assertThat(matcher.start()).isBetween(0, malformed.length());
      assertThat(matcher.end()).isBetween(matcher.start(), malformed.length());
    }
    Pattern.compile("a").find(malformed);
  }

  private static Utf8Input input(String text) {
    return Utf8Input.validated(text.getBytes(UTF_8));
  }
}
