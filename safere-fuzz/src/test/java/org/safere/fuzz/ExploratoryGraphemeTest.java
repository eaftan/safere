// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class ExploratoryGraphemeTest {
  @Test
  void intentionalGraphemeDifferenceRemainsAVisibleFinding() {
    String input = "\uda5f\udc2e\u0301";
    assertThat(org.safere.Pattern.compile("\\X").matcher(input).matches()).isTrue();
    var pair = FuzzSupport.compileOrSkip("\\X", 0).matcher(input);
    assertThatThrownBy(pair::find).isInstanceOf(AssertionError.class);
  }

  @Test
  void intentionalCrLfSplitDifferenceRemainsAVisibleFinding() {
    String regex = "\\r\\b{g}\\n";
    String input = "#\r\n\r\n$";
    assertThat(org.safere.Pattern.compile(regex).split(input)).containsExactly(input);
    var pattern = FuzzSupport.compileOrSkip(regex, 0);
    assertThat(pattern).isNotNull();
    assertThatThrownBy(() -> pattern.split(input))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("split divergence");
    assertThatThrownBy(() -> pattern.splitWithDelimiters(input))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("splitWithDelimiters divergence");
  }
}
