// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link SweepJson}. */
class SweepJsonTest {
  @Test
  void writesAndReadsJsonFieldsWithEscapedCharacters() {
    String value = "quote\" slash\\ newline\n tab\t surrogate\uD83D";
    var object = SweepJson.object();
    object.addProperty("field", value);
    object.addProperty("other", "x");

    String line = SweepJson.toJson(object);

    assertThat(SweepJson.field(line, "field")).isEqualTo(value);
    assertThat(SweepJson.field(line, "missing")).isNull();
  }

  @Test
  void fieldReturnsNullForLegacyRawReplayLine() {
    assertThat(SweepJson.field("\\Qabc\\E", "regex")).isNull();
  }

  @Test
  void legacyUnescapeDecodesOldReplayEscapes() {
    assertThat(SweepJson.legacyUnescape("a\\nb\\t\\u0041\\\\\\\"")).isEqualTo("a\nb\tA\\\"");
  }
}
