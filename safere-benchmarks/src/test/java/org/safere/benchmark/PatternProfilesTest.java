// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PatternProfilesTest {
  @Test
  void selectsExplicitAlternateAndFallsBackToJava() {
    PatternProfiles profiles =
        PatternProfiles.parse(
            JsonParser.parseString(
                """
                {
                  "alternate-engine": [{
                    "java": "\\\\Qfoo.bar\\\\E",
                    "alternate": "foo\\\\.bar",
                    "reason": "Alternate engine does not support Java quoted literals"
                  }]
                }
                """));

    assertThat(profiles.select("alternate-engine", "\\Qfoo.bar\\E")).isEqualTo("foo\\.bar");
    assertThat(profiles.select("alternate-engine", "unchanged")).isEqualTo("unchanged");
    assertThat(profiles.select("go-regexp", "\\Qfoo.bar\\E")).isEqualTo("\\Qfoo.bar\\E");
  }

  @Test
  void rejectsMalformedAndDuplicateAlternates() {
    assertThatThrownBy(
            () ->
                PatternProfiles.parse(
                    JsonParser.parseString(
                        """
                        {"Rust": []}
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid benchmark pattern profile ID: Rust");

    assertThatThrownBy(
            () ->
                PatternProfiles.parse(
                    JsonParser.parseString(
                        """
                        {
                          "alternate-engine": [
                            {"java": "x", "alternate": "y", "reason": "first"},
                            {"java": "x", "alternate": "z", "reason": "second"}
                          ]
                        }
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Pattern profile alternate-engine repeats Java pattern: x");
  }

  @Test
  void rejectsTopLevelAndConflictingInlineAlternates() {
    assertThatThrownBy(
            () ->
                PatternProfiles.normalizeInline(
                    JsonParser.parseString(
                            """
                            {"patternProfiles": {}}
                            """)
                        .getAsJsonObject()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "benchmark-data.json must define pattern alternates next to their Java patterns");

    assertThatThrownBy(
            () ->
                PatternProfiles.normalizeInline(
                    JsonParser.parseString(
                            """
                            {
                              "first": {
                                "java": "x",
                                "alternates": {
                                  "re2": {"pattern": "y", "reason": "first"}
                                }
                              },
                              "second": {
                                "java": "x",
                                "alternates": {
                                  "re2": {"pattern": "z", "reason": "second"}
                                }
                              }
                            }
                            """)
                        .getAsJsonObject()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Conflicting re2 alternates for Java pattern: x");
  }

  @Test
  void checkedInProfilesContainReviewedDialectAlternates() throws Exception {
    Path benchmarkData =
        Files.exists(Path.of("benchmark-data.json"))
            ? Path.of("benchmark-data.json")
            : Path.of("safere-benchmarks", "benchmark-data.json");
    JsonObject root = JsonParser.parseString(Files.readString(benchmarkData)).getAsJsonObject();
    JsonObject normalized = PatternProfiles.normalizeInline(root);
    PatternProfiles profiles = PatternProfiles.parse(normalized.get("patternProfiles"));

    assertThat(profiles.select("re2", "\\p{script=Latin}+")).isEqualTo("\\p{Latin}+");
    assertThat(profiles.select("re2", "[\\p{L}&&[^\\p{Lu}]]+"))
        .isEqualTo("[\\p{Ll}\\p{Lt}\\p{Lm}\\p{Lo}]+");
    assertThat(profiles.select("re2", "\\p{IsAlphabetic}+")).isEqualTo("\\p{IsAlphabetic}+");
    assertThat(profiles.select("re2", "\\p{IsIdeographic}+")).isEqualTo("\\p{IsIdeographic}+");
    for (JsonElement profile : normalized.getAsJsonObject("patternProfiles").asMap().values()) {
      for (JsonElement entry : profile.getAsJsonArray()) {
        String javaPattern = entry.getAsJsonObject().get("java").getAsString();
        assertThat(containsString(root.getAsJsonArray("workloads"), javaPattern))
            .as("checked-in workload uses canonical pattern %s", javaPattern)
            .isTrue();
      }
    }
    for (JsonElement entry : normalized.getAsJsonObject("patternProfiles").getAsJsonArray("re2")) {
      String alternate = entry.getAsJsonObject().get("alternate").getAsString();
      assertThat(com.google.re2j.Pattern.compile(alternate)).isNotNull();
    }
  }

  private static boolean containsString(JsonElement element, String expected) {
    if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
      return element.getAsString().equals(expected);
    }
    if (element.isJsonArray()) {
      for (JsonElement child : element.getAsJsonArray()) {
        if (containsString(child, expected)) {
          return true;
        }
      }
    } else if (element.isJsonObject()) {
      for (JsonElement child : element.getAsJsonObject().asMap().values()) {
        if (containsString(child, expected)) {
          return true;
        }
      }
    }
    return false;
  }
}
