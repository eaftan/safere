// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BenchmarkDataSchemaTest {
  @Test
  void checkedInDocumentUsesOnlyTheAuthoritativeSchema() throws Exception {
    Path benchmarkData =
        Files.exists(Path.of("benchmark-data.json"))
            ? Path.of("benchmark-data.json")
            : Path.of("safere-benchmarks", "benchmark-data.json");
    JsonObject root = JsonParser.parseString(Files.readString(benchmarkData)).getAsJsonObject();

    BenchmarkDataSchema.validate(root);
    BenchmarkDataSchema.requireWorkloads(root);

    assertThat(root.keySet())
        .containsExactlyInAnyOrder("schemaVersion", "configuration", "inputs", "workloads");
    assertThat(root.getAsJsonObject("configuration").keySet())
        .containsExactlyInAnyOrder("collection", "crosscheckOverhead");
  }

  @Test
  void rejectsUnknownTopLevelFields() {
    JsonObject root =
        JsonParser.parseString(
                """
                {
                  "schemaVersion": 1,
                  "inputs": [],
                  "workloads": [],
                  "regex": {}
                }
                """)
            .getAsJsonObject();

    assertThatThrownBy(() -> BenchmarkDataSchema.validate(root))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("benchmark-data.json has unknown field: regex");
  }

  @Test
  void rejectsUnknownConfigurationFields() {
    JsonObject root =
        JsonParser.parseString(
                """
                {
                  "schemaVersion": 1,
                  "configuration": {"legacyWorkloads": {}},
                  "inputs": [],
                  "workloads": []
                }
                """)
            .getAsJsonObject();

    assertThatThrownBy(() -> BenchmarkDataSchema.validate(root))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("benchmark-data.json configuration has unknown field: legacyWorkloads");
  }
}
