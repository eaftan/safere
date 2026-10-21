// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Failed inner candidates must be rejected before scanning the leading repetition backward. */
@DisabledForCrosscheck("uses SafeRE work counters and diagnostics")
@Tag("work-counter")
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class LeadingExpansionVerificationWorkTest {
  @AfterEach
  void disableDiagnostics() {
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failedInnerCandidatesCostComparableWorkAcrossEntryPoints(boolean diagnostics) {
    Pattern.setDiagnostics(
        diagnostics ? new SafeReMatchDiagnostics() {} : SafeReMatchDiagnostics.NONE);
    List<String> failures = new ArrayList<>();
    for (String regex : List.of("\\s*# noqa[0-9]", "\\s*# noqa\\b", "[é😀]*# noqa[0-9]")) {
      Pattern pattern = Pattern.compile(regex);
      for (int size : new int[] {2_000, 20_000}) {
        String input = (regex.startsWith("[é") ? "é😀" : " ").repeat(size) + "# noqaX";
        Utf8Input utf8 = Utf8Input.trusted(input.getBytes(UTF_8));
        Map<String, Runnable> operations = new LinkedHashMap<>();
        operations.put("String find", () -> assertThat(pattern.matcher(input).find()).isFalse());
        operations.put("UTF-8 find", () -> assertThat(pattern.matcher(utf8).find()).isFalse());
        operations.put("matcher-free find", () -> assertThat(pattern.find(utf8)).isFalse());
        operations.put(
            "replaceAll",
            () -> assertThat(pattern.matcher(input).replaceAll("X")).isEqualTo(input));
        operations.put(
            "replaceFirst",
            () -> assertThat(pattern.matcher(input).replaceFirst("X")).isEqualTo(input));
        operations.put(
            "function replacement",
            () -> assertThat(pattern.matcher(input).replaceAll(m -> "X")).isEqualTo(input));
        operations.put("split", () -> assertThat(pattern.split(input)).containsExactly(input));
        long reference = work(operations.get("String find"));
        operations.forEach(
            (name, operation) -> {
              long actual = work(operation);
              if (actual > reference * 2 + 100) {
                failures.add(
                    String.format(
                        "%s: %s at %s repetitions: %s units versus %s for find()",
                        regex, name, size, actual, reference));
              }
            });
      }
    }
    assertThat(failures).as("diagnostics=%s", diagnostics).isEmpty();
  }

  private static long work(Runnable operation) {
    operation.run();
    return WorkCounter.countForTesting(operation);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failedInnerCandidatesAfterSuccessfulReplacementHaveTheSameBound(boolean diagnostics) {
    Pattern.setDiagnostics(
        diagnostics ? new SafeReMatchDiagnostics() {} : SafeReMatchDiagnostics.NONE);
    Pattern pattern = Pattern.compile("\\s*# noqa[0-9]");
    for (int size : new int[] {2_000, 20_000}) {
      String input = "# noqa1;" + " ".repeat(size) + "# noqaX";
      String expected = "X;" + " ".repeat(size) + "# noqaX";
      long reference =
          work(() -> assertThat(pattern.matcher(input).replaceAll(m -> "X")).isEqualTo(expected));
      long actual =
          work(() -> assertThat(pattern.matcher(input).replaceAll("X")).isEqualTo(expected));
      assertThat(actual)
          .as("%s leading spaces after a match, diagnostics=%s", size, diagnostics)
          .isLessThanOrEqualTo(reference * 2 + 100);
    }
  }
}
