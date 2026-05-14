// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
  void toJsonEscapesUnpairedSurrogates() {
    var object = SweepJson.object();
    object.addProperty("high", "\uD83D");
    object.addProperty("low", "\uDE00");
    object.addProperty("pair", "\uD83D\uDE00");

    String line = SweepJson.toJson(object);

    assertThat(line).contains("\"high\":\"\\ud83d\"", "\"low\":\"\\ude00\"");
    assertThat(line).contains("😀");
  }

  @Test
  void fieldReturnsNullForLegacyRawReplayLine() {
    assertThat(SweepJson.field("\\Qabc\\E", "regex")).isNull();
  }

  @Test
  void readsStructuredJsonFields() {
    var object = SweepJson.object();
    var nested = SweepJson.object();
    nested.addProperty("text", "abc");
    nested.addProperty("enabled", true);
    nested.addProperty("count", 7);
    object.add("nested", nested);

    var parsed = SweepJson.parseObject(SweepJson.toJson(object));
    var parsedNested = SweepJson.object(parsed, "nested");

    assertThat(SweepJson.string(parsedNested, "text")).isEqualTo("abc");
    assertThat(SweepJson.bool(parsedNested, "enabled")).isTrue();
    assertThat(SweepJson.integer(parsedNested, "count")).isEqualTo(7);
  }

  @Test
  void parseObjectRejectsLegacyRawReplayLine() {
    assertThatThrownBy(() -> SweepJson.parseObject("\\Qabc\\E"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expected JSON object");
  }
}
