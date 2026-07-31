// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.vector.benchmark;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Loads Vector benchmark trials from the shared materialized benchmark manifest. */
final class VectorScanConfiguration {
  private static final String CORPUS_PROPERTY = "safere.benchmark.corpus";

  private VectorScanConfiguration() {}

  static List<String> trials(String profile) {
    String field =
        switch (profile) {
          case "smoke" -> "smokeTrials";
          case "standard" -> "standardTrials";
          default -> throw new IllegalArgumentException("Unknown Vector scan profile: " + profile);
        };
    JsonObject vectorScan =
        manifest()
            .getAsJsonObject("benchmarkData")
            .getAsJsonObject("configuration")
            .getAsJsonObject("vectorScan");
    List<String> result = new ArrayList<>();
    vectorScan.getAsJsonArray(field).forEach(value -> result.add(value.getAsString()));
    return List.copyOf(result);
  }

  static byte[] input(String density, int length) {
    JsonObject manifest = manifest();
    String inputId = "vectorScan." + density + "." + length;
    String file =
        manifest.getAsJsonObject("inputs").getAsJsonObject(inputId).get("file").getAsString();
    try {
      return Files.readAllBytes(corpusDirectory().resolve(file));
    } catch (IOException exception) {
      throw new RuntimeException("Failed to read Vector scan input " + inputId, exception);
    }
  }

  private static JsonObject manifest() {
    try {
      return JsonParser.parseString(Files.readString(corpusDirectory().resolve("manifest.json")))
          .getAsJsonObject();
    } catch (IOException exception) {
      throw new RuntimeException("Failed to read benchmark manifest", exception);
    }
  }

  private static Path corpusDirectory() {
    String value = System.getProperty(CORPUS_PROPERTY);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          "Missing -D" + CORPUS_PROPERTY + "=<directory>; use run-vector-benchmarks.sh");
    }
    return Path.of(value);
  }
}
