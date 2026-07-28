// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;

/** Validates the complete checked-in {@code benchmark-data.json} document envelope. */
final class BenchmarkDataSchema {
  private static final Set<String> ROOT_FIELDS =
      Set.of("schemaVersion", "configuration", "inputs", "workloads");
  private static final Set<String> CONFIGURATION_FIELDS =
      Set.of("collection", "crosscheckOverhead");
  private static final Set<String> COLLECTION_FIELDS = Set.of("allocationWorkloadPrefixes");
  private static final Set<String> CROSSCHECK_OVERHEAD_FIELDS = Set.of("pattern", "replacement");

  private BenchmarkDataSchema() {}

  static void validate(JsonObject source) {
    requireOnly(source, "benchmark-data.json", ROOT_FIELDS);
    required(source, "schemaVersion").getAsInt();
    requiredArray(source, "inputs");
    if (source.has("workloads")) {
      requiredArray(source, "workloads");
    }
    if (source.has("configuration")) {
      validateConfiguration(requiredObject(source, "configuration"));
    }
  }

  static void requireWorkloads(JsonObject source) {
    JsonArray workloads = requiredArray(source, "workloads");
    if (workloads.isEmpty()) {
      throw new IllegalArgumentException("benchmark-data.json workloads must not be empty");
    }
  }

  private static void validateConfiguration(JsonObject configuration) {
    requireOnly(configuration, "benchmark-data.json configuration", CONFIGURATION_FIELDS);
    if (configuration.has("collection")) {
      JsonObject collection = requiredObject(configuration, "collection");
      requireOnly(collection, "benchmark-data.json configuration.collection", COLLECTION_FIELDS);
      JsonArray prefixes = requiredArray(collection, "allocationWorkloadPrefixes");
      for (JsonElement prefix : prefixes) {
        if (!prefix.isJsonPrimitive()
            || !prefix.getAsJsonPrimitive().isString()
            || prefix.getAsString().isBlank()) {
          throw new IllegalArgumentException(
              "benchmark-data.json allocation workload prefixes must be nonblank strings");
        }
      }
    }
    if (configuration.has("crosscheckOverhead")) {
      JsonObject crosscheck = requiredObject(configuration, "crosscheckOverhead");
      requireOnly(
          crosscheck,
          "benchmark-data.json configuration.crosscheckOverhead",
          CROSSCHECK_OVERHEAD_FIELDS);
      requiredString(crosscheck, "pattern");
      requiredString(crosscheck, "replacement");
    }
  }

  private static void requireOnly(JsonObject object, String context, Set<String> allowed) {
    for (String field : object.keySet()) {
      if (!allowed.contains(field)) {
        throw new IllegalArgumentException(context + " has unknown field: " + field);
      }
    }
  }

  private static JsonElement required(JsonObject object, String field) {
    JsonElement value = object.get(field);
    if (value == null || value.isJsonNull()) {
      throw new IllegalArgumentException("benchmark-data.json requires " + field);
    }
    return value;
  }

  private static JsonObject requiredObject(JsonObject object, String field) {
    JsonElement value = required(object, field);
    if (!value.isJsonObject()) {
      throw new IllegalArgumentException("benchmark-data.json field must be an object: " + field);
    }
    return value.getAsJsonObject();
  }

  private static JsonArray requiredArray(JsonObject object, String field) {
    JsonElement value = required(object, field);
    if (!value.isJsonArray()) {
      throw new IllegalArgumentException("benchmark-data.json field must be an array: " + field);
    }
    return value.getAsJsonArray();
  }

  private static String requiredString(JsonObject object, String field) {
    JsonElement value = required(object, field);
    if (!value.isJsonPrimitive()
        || !value.getAsJsonPrimitive().isString()
        || value.getAsString().isBlank()) {
      throw new IllegalArgumentException(
          "benchmark-data.json field must be a nonblank string: " + field);
    }
    return value.getAsString();
  }
}
