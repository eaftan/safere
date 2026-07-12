// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("uses the package-private array adapter to verify borrowed storage")
class Utf8InputTest {
  @Test
  void wholeArrayFactoriesExposeLogicalLength() {
    byte[] bytes = "aé😀".getBytes(UTF_8);

    assertThat(Utf8Input.trusted(bytes).length()).isEqualTo(bytes.length);
    assertThat(Utf8Input.validated(bytes).length()).isEqualTo(bytes.length);
  }

  @Test
  void windowFactoriesUseRelativeCoordinates() {
    byte[] logical = "é😀".getBytes(UTF_8);
    byte[] storage = new byte[logical.length + 4];
    System.arraycopy(logical, 0, storage, 2, logical.length);

    Utf8Input trusted = Utf8Input.trusted(storage, 2, logical.length);
    Utf8Input validated = Utf8Input.validated(storage, 2, logical.length);

    assertThat(trusted.length()).isEqualTo(logical.length);
    assertThat(validated.length()).isEqualTo(logical.length);
  }

  @Test
  void factoriesRejectNullAndInvalidBounds() {
    assertThatNullPointerException().isThrownBy(() -> Utf8Input.trusted(null));
    assertThatNullPointerException().isThrownBy(() -> Utf8Input.validated(null));
    assertThatThrownBy(() -> Utf8Input.trusted(new byte[1], -1, 1))
        .isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> Utf8Input.trusted(new byte[1], 0, Integer.MAX_VALUE))
        .isInstanceOf(IndexOutOfBoundsException.class);
  }

  @Test
  void validatedFactoryRejectsMalformedInputAtRelativeOffset() {
    byte[] storage = {'x', 'a', (byte) 0xC0, (byte) 0x80, 'z'};

    assertThatThrownBy(() -> Utf8Input.validated(storage, 1, 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("byte 1");
  }

  @Test
  void trustedFactoryAcceptsMalformedInput() {
    byte[] malformed = {(byte) 0xC0, (byte) 0x80};

    assertThat(Utf8Input.trusted(malformed).length()).isEqualTo(2);
  }

  @Test
  void factoriesBorrowCallerStorageRatherThanCopyingIt() {
    byte[] storage = "borrowed".getBytes(UTF_8);

    ArrayUtf8Input input = (ArrayUtf8Input) Utf8Input.validated(storage);

    assertThat(input.scanner().bytes()).isSameAs(storage);
  }

  @Test
  void mutationIsAnExplicitBorrowedStoragePreconditionBoundary() {
    byte[] storage = "a".getBytes(UTF_8);
    Utf8Input input = Utf8Input.trusted(storage);

    storage[0] = 'b';

    assertThat(Pattern.compile("b").find(input)).isTrue();
  }
}
