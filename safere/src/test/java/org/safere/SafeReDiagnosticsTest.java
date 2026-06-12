// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link SafeReDiagnostics}. */
@DisabledForCrosscheck("diagnostic exporter test uses SafeRE-specific bytecode shape")
class SafeReDiagnosticsTest {

  @Test
  void exportsNfaProgramShapeFromPatternProg() {
    String json =
        SafeReDiagnostics.bytecodeCaseToJsonLine(
            "literal-a",
            "a",
            0,
            "a",
            SafeReDiagnostics.BytecodeMatchMode.ANCHORED,
            Pattern.compile("a"));

    assertThat(json).contains("\"schema\":\"safere-bytecode-v1\"");
    assertThat(json).contains("\"producer\":{\"name\":\"safere\"}");
    assertThat(json).contains("\"shape\":\"nfa-prog\"");
    assertThat(json).contains("\"didFlatten\":false");
    assertThat(json).contains("\"op\":\"CHAR_RANGE\"");
    assertThat(json).contains("\"lo\":97");
    assertThat(json).contains("\"hi\":97");
    assertThat(json).contains("\"foldCase\":false");
    assertThat(json).contains("\"op\":\"MATCH\"");
  }

  @Test
  void exportsAnchoredNfaCaptureGroups() {
    String json =
        SafeReDiagnostics.bytecodeCaseToJsonLine(
            "capture",
            "(a)",
            0,
            "a",
            SafeReDiagnostics.BytecodeMatchMode.ANCHORED,
            Pattern.compile("(a)"));

    assertThat(json).contains("\"matched\":true");
    assertThat(json).contains("\"numCaptures\":2");
    assertThat(json).contains("\"numCaptureSlots\":4");
    assertThat(json).contains("{\"group\":0,\"start\":0,\"end\":1}");
    assertThat(json).contains("{\"group\":1,\"start\":0,\"end\":1}");
  }

  @Test
  void exportsUnmatchedAnchoredNfaResult() {
    String json =
        SafeReDiagnostics.bytecodeCaseToJsonLine(
            "miss",
            "a",
            0,
            "b",
            SafeReDiagnostics.BytecodeMatchMode.ANCHORED,
            Pattern.compile("a"));

    assertThat(json).contains("\"matched\":false");
    assertThat(json).contains("\"groups\":[]");
  }

  @Test
  void exportsCharClassRangesAsPairs() {
    String json =
        SafeReDiagnostics.bytecodeCaseToJsonLine(
            "digits",
            "[0-9A-Z]",
            0,
            "5",
            SafeReDiagnostics.BytecodeMatchMode.ANCHORED,
            Pattern.compile("[0-9A-Z]"));

    assertThat(json).contains("\"op\":\"CHAR_CLASS\"");
    assertThat(json).contains("\"ranges\":[[48,57],[65,90]]");
  }

  @Test
  void exportsFlagsAndEscapedStringsDeterministically() {
    int flags = Pattern.CASE_INSENSITIVE | Pattern.MULTILINE;
    String json =
        SafeReDiagnostics.bytecodeCaseToJsonLine(
            "escape",
            "a\\nb",
            flags,
            "a\nb",
            SafeReDiagnostics.BytecodeMatchMode.ANCHORED,
            Pattern.compile("a\\nb", flags));

    assertThat(json).contains("\"pattern\":\"a\\\\nb\"");
    assertThat(json).contains("\"input\":\"a\\nb\"");
    assertThat(json).contains("\"flags\":[\"CASE_INSENSITIVE\",\"MULTILINE\"]");
    assertThat(json).contains("\"flagsValue\":" + flags);
  }

  @Test
  void escapesLoneSurrogateCodeUnitsInJsonStrings() {
    String loneHighSurrogate = String.valueOf((char) 0xD800);
    String json =
        SafeReDiagnostics.bytecodeCaseToJsonLine(
            "surrogate",
            "a",
            0,
            loneHighSurrogate,
            SafeReDiagnostics.BytecodeMatchMode.ANCHORED,
            Pattern.compile("a"));

    assertThat(json).contains("\"input\":\"\\ud800\"");
    assertThat(json).doesNotContain(loneHighSurrogate);
  }

  @Test
  void exportsProgressCheckGreedinessBySemanticName() {
    String json =
        SafeReDiagnostics.bytecodeCaseToJsonLine(
            "nullable-loop",
            "(?:a?)*?",
            0,
            "",
            SafeReDiagnostics.BytecodeMatchMode.ANCHORED,
            Pattern.compile("(?:a?)*?"));

    String progressCheck = instructionWithOp(json, "PROGRESS_CHECK");
    assertThat(progressCheck).contains("\"nonGreedy\":true");
    assertThat(progressCheck).doesNotContain("\"foldCase\"");
  }

  @Test
  void omitsUnsupportedMatcherEndpointMetadata() {
    String json =
        SafeReDiagnostics.bytecodeCaseToJsonLine(
            "literal-a",
            "a",
            0,
            "a",
            SafeReDiagnostics.BytecodeMatchMode.ANCHORED,
            Pattern.compile("a"));

    assertThat(json).doesNotContain("hitEnd");
    assertThat(json).doesNotContain("requireEnd");
  }

  private static String instructionWithOp(String json, String op) {
    String marker = "\"op\":\"" + op + "\"";
    int opIndex = json.indexOf(marker);
    assertThat(opIndex).isGreaterThanOrEqualTo(0);
    int start = json.lastIndexOf('{', opIndex);
    int end = json.indexOf('}', opIndex);
    assertThat(start).isGreaterThanOrEqualTo(0);
    assertThat(end).isGreaterThan(opIndex);
    return json.substring(start, end + 1);
  }
}
