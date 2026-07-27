// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BenchmarkInputMaterializerTest {

  @Test
  void emptyRepeatUnitIsRejectedWhenOutputIsRequired() {
    assertThatThrownBy(() -> BenchmarkInputMaterializer.repeatToSize("", 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Repeat unit must not be empty when size is positive");
  }

  @Test
  void emptyRepeatUnitCanProduceEmptyOutput() {
    assertThat(BenchmarkInputMaterializer.repeatToSize("", 0)).isEmpty();
  }
}
