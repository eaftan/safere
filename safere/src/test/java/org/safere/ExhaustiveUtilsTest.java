// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExhaustiveUtilsTest {
  @Test
  void utf8CoordinatesMapOnlyUnicodeScalarBoundaries() {
    ExhaustiveUtils.Utf8Coordinates coordinates = ExhaustiveUtils.Utf8Coordinates.forText("Aé😀Z");

    assertThat(coordinates.toUtf16(0)).isEqualTo(0);
    assertThat(coordinates.toUtf16(1)).isEqualTo(1);
    assertThat(coordinates.toUtf16(3)).isEqualTo(2);
    assertThat(coordinates.toUtf16(7)).isEqualTo(4);
    assertThat(coordinates.toUtf16(8)).isEqualTo(5);
    assertThatThrownBy(() -> coordinates.toUtf16(2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a Unicode scalar boundary");
    assertThatThrownBy(() -> coordinates.toUtf16(5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a Unicode scalar boundary");
  }

  @Test
  void utf8CoordinatesRejectUnpairedSurrogates() {
    assertThatThrownBy(() -> ExhaustiveUtils.Utf8Coordinates.forText("a\ud800b"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unpaired surrogate");
    assertThatThrownBy(() -> ExhaustiveUtils.Utf8Coordinates.forText("a\udc00b"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unpaired surrogate");
  }
}
