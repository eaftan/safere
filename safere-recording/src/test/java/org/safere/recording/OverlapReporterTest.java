// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.recording;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class OverlapReporterTest {

  @Test
  void reportsExactOverlapAndContainmentAcrossTestMethods() {
    String firstTest =
        "[engine:junit-jupiter]/[class:Example]/[test-template:first(java.lang.String)]"
            + "/[test-template-invocation:#1]";
    String secondTest = "[engine:junit-jupiter]/[class:Example]/[method:second()]";
    String matcherArguments = RecordingRuntime.encodeArguments("(a+)", 0, "aaa");
    String findArguments = RecordingRuntime.encodeArguments();

    List<String> events =
        List.of(
            event("M", firstTest, 1, 0, "matcher", matcherArguments, "created"),
            event("M", firstTest, 1, 1, "find", findArguments, true),
            event("M", secondTest, 2, 0, "matcher", matcherArguments, "created"),
            event("M", secondTest, 2, 1, "find", findArguments, true));

    String report = OverlapReporter.analyze(events).markdown();

    assertThat(report)
        .contains("Recorded object lifecycles: 2")
        .contains("Distinct execution fingerprints: 1")
        .contains("100.00%")
        .contains("matcher(\"(a+)\", 0, \"aaa\")")
        .contains("find() -> true");
  }

  private static String event(
      String kind,
      String test,
      long objectId,
      long sequence,
      String method,
      String arguments,
      Object result) {
    return String.join(
        "\t",
        kind,
        RecordingRuntime.encodeField(test),
        Long.toString(objectId),
        Long.toString(sequence),
        RecordingRuntime.encodeField(method),
        RecordingRuntime.encodeField(arguments),
        RecordingRuntime.encodeField(ValueEncoder.encode(result)),
        RecordingRuntime.encodeField(""));
  }
}
