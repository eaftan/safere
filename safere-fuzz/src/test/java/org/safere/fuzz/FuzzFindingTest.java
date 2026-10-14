// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FuzzFindingTest {
  @TempDir Path directory;

  @Test
  void jsonPreservesUnpairedSurrogatesAndControlCharacters() {
    assertThat(FuzzFinding.json(Map.of("input", "\ud800\n\"\\")))
        .isEqualTo("{\"input\":\"\\ud800\\n\\\"\\\\\"}");
  }

  @Test
  void preservesFailureAndPersistsContext() throws Exception {
    String property = "safere.fuzz.findingsDir";
    String previous = System.getProperty(property);
    System.setProperty(property, directory.toString());
    try {
      var finding =
          FuzzFinding.failure("a", 0, "a", "find", "region=0:1", "reset,find", "true", "false");
      assertThat(finding).hasMessageContaining("find divergence");
      try (var files = Files.list(directory)) {
        var paths = files.toList();
        assertThat(paths).hasSize(1);
        assertThat(Files.readString(paths.getFirst()))
            .contains("\"history\":\"reset,find\"", "\"jdk\":\"false\"");
      }
    } finally {
      if (previous == null) {
        System.clearProperty(property);
      } else {
        System.setProperty(property, previous);
      }
    }
  }

  @Test
  void statefulFindingsIncludeBoundsAndPriorOperations() {
    var pair =
        new FuzzSupport.MatcherPair(
            "(a)?b$",
            0,
            "xab",
            org.safere.Pattern.compile("()??ab$").matcher("xab"),
            java.util.regex.Pattern.compile("(a)?b$").matcher("xab"));
    pair.region(1, 3).useTransparentBounds(true);
    assertThatThrownBy(pair::matches)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("region=1:3")
        .hasMessageContaining("transparent=true")
        .hasMessageContaining("region(1,3)")
        .hasMessageContaining("useTransparentBounds(true)");
  }
}
