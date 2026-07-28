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
import java.util.List;
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
        .hasMessage("benchmark-data.json must define syntax alternates next to their Java values");

    assertThatThrownBy(
            () ->
                PatternProfiles.normalizeInline(
                    JsonParser.parseString(
                            """
                            {
                              "first": {
                                "pattern": {
                                  "java": "x",
                                  "alternates": {
                                    "re2": {"pattern": "y", "reason": "first"}
                                  }
                                }
                              },
                              "second": {
                                "pattern": {
                                  "java": "x",
                                  "alternates": {
                                    "re2": {"pattern": "z", "reason": "second"}
                                  }
                                }
                              }
                            }
                            """)
                        .getAsJsonObject()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Conflicting re2 alternates for Java pattern: x");
  }

  @Test
  void rejectsAlternateWhoseKindDoesNotMatchContainingField() {
    assertThatThrownBy(
            () ->
                PatternProfiles.normalizeInline(
                    JsonParser.parseString(
                            """
                            {
                              "pattern": {
                                "java": "x",
                                "alternates": {
                                  "rust-regex": {
                                    "replacement": "y",
                                    "reason": "wrong kind"
                                  }
                                }
                              }
                            }
                            """)
                        .getAsJsonObject()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Inline benchmark pattern definition contains replacement alternate");

    assertThatThrownBy(
            () ->
                PatternProfiles.normalizeInline(
                    JsonParser.parseString(
                            """
                            {
                              "replacement": {
                                "java": "x",
                                "alternates": {
                                  "rust-regex": {
                                    "pattern": "y",
                                    "reason": "wrong kind"
                                  }
                                }
                              }
                            }
                            """)
                        .getAsJsonObject()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Inline benchmark replacement definition contains pattern alternate");
  }

  @Test
  void separatesPatternAndReplacementAlternatesWithTheSameJavaValue() {
    JsonObject normalized =
        PatternProfiles.normalizeInline(
            JsonParser.parseString(
                    """
                    {
                      "pattern": {
                        "java": "same",
                        "alternates": {
                          "rust-regex": {"pattern": "pattern", "reason": "pattern syntax"}
                        }
                      },
                      "replacement": {
                        "java": "same",
                        "alternates": {
                          "rust-regex": {
                            "replacement": "replacement",
                            "reason": "replacement syntax"
                          }
                        }
                      }
                    }
                    """)
                .getAsJsonObject());

    PatternProfiles patterns = PatternProfiles.parse(normalized.get("patternProfiles"));
    PatternProfiles replacements = PatternProfiles.parse(normalized.get("replacementProfiles"));
    assertThat(patterns.select("rust-regex", "same")).isEqualTo("pattern");
    assertThat(replacements.select("rust-regex", "same")).isEqualTo("replacement");
  }

  @Test
  void checkedInProfilesContainReviewedDialectAlternates() throws Exception {
    Path benchmarkData =
        Files.exists(Path.of("benchmark-data.json"))
            ? Path.of("benchmark-data.json")
            : Path.of("safere-benchmarks", "benchmark-data.json");
    JsonObject root = JsonParser.parseString(Files.readString(benchmarkData)).getAsJsonObject();
    JsonObject normalized = PatternProfiles.normalizeInline(root);
    PatternProfiles patterns = PatternProfiles.parse(normalized.get("patternProfiles"));
    PatternProfiles replacements = PatternProfiles.parse(normalized.get("replacementProfiles"));

    assertThat(patterns.select("re2", "\\p{script=Latin}+")).isEqualTo("\\p{Latin}+");
    assertThat(patterns.select("re2", "[\\p{L}&&[^\\p{Lu}]]+"))
        .isEqualTo("[\\p{Ll}\\p{Lt}\\p{Lm}\\p{Lo}]+");
    assertThat(patterns.select("pcre2", "\\p{block=BasicLatin}+")).isEqualTo("[\\x{0}-\\x{7F}]+");
    assertThat(patterns.select("pcre2", "[\\p{L}&&[^\\p{Lu}]]+"))
        .isEqualTo("[\\p{Ll}\\p{Lt}\\p{Lm}\\p{Lo}]+");
    assertThat(patterns.select("pcre2", "\\p{javaLetter}")).isEqualTo("\\p{L}");
    assertThat(patterns.select("pcre2", "\\p{script=Latin}+")).isEqualTo("\\p{script=Latin}+");
    assertThat(patterns.select("re2", "\\p{IsAlphabetic}+")).isEqualTo("\\p{IsAlphabetic}+");
    assertThat(patterns.select("re2", "\\p{IsIdeographic}+")).isEqualTo("\\p{IsIdeographic}+");
    assertThat(patterns.select("rust-regex", "^\\s*<(\\QApple\\E|\\QBanana\\E|\\QCherry\\E)>\\s*$"))
        .isEqualTo("^[[:space:]]*<(Apple|Banana|Cherry)>[[:space:]]*$");
    assertThat(patterns.select("rust-regex", "(\\d{4})-(\\d{2})-(\\d{2})"))
        .isEqualTo("([0-9]{4})-([0-9]{2})-([0-9]{2})");
    assertThat(patterns.select("rust-regex", "\\b\\w+ing\\b"))
        .isEqualTo("(?-u:\\b)[A-Za-z0-9_]+ing(?-u:\\b)");
    assertThat(replacements.select("re2-cpp", "$1=[$2]")).isEqualTo("\\1=[\\2]");
    assertThat(replacements.select("re2-cpp", "${key}=[${value}]")).isEqualTo("\\1=[\\2]");
    assertThat(replacements.select("re2-cpp", "$1<tag type=\"custom\""))
        .isEqualTo("\\1<tag type=\"custom\"");
    assertThat(replacements.select("re2-cpp", "$2$1ay")).isEqualTo("\\2\\1ay");
    assertThat(replacements.select("re2-cpp", "$1=REDACTED")).isEqualTo("\\1=REDACTED");
    assertThat(replacements.select("re2-cpp", "$1")).isEqualTo("\\1");
    assertThat(replacements.select("go-regexp", "$2$1ay")).isEqualTo("${2}${1}ay");
    assertThat(replacements.select("pcre2", "$1")).isEqualTo("$1");
    assertThat(replacements.select("rust-regex", "$2$1ay")).isEqualTo("${2}${1}ay");
    assertThat(patterns.select("dotnet", "^\\s*<(\\QApple\\E|\\QBanana\\E|\\QCherry\\E)>\\s*$"))
        .isEqualTo("^\\s*<(Apple|Banana|Cherry)>\\s*$");
    assertThat(patterns.select("dotnet", "[😀-😇]")).isEqualTo("\\uD83D[\\uDE00-\\uDE07]");
    assertThat(patterns.select("dotnet", "\\p{javaLetter}")).isEqualTo("\\p{L}");
    assertThat(patterns.select("dotnet", "\\p{block=BasicLatin}+")).isEqualTo("[\\u0000-\\u007F]+");
    assertThat(patterns.select("dotnet", "\\p{block=CJK_Unified_Ideographs}+"))
        .isEqualTo("[\\u4E00-\\u9FFF]+");
    assertThat(patterns.select("dotnet", "[\\p{L}&&[^\\p{Lu}]]+")).isEqualTo("[\\p{L}-[\\p{Lu}]]+");
    for (String profileType : List.of("patternProfiles", "replacementProfiles")) {
      for (JsonElement profile : normalized.getAsJsonObject(profileType).asMap().values()) {
        for (JsonElement entry : profile.getAsJsonArray()) {
          String javaValue = entry.getAsJsonObject().get("java").getAsString();
          assertThat(containsString(root.getAsJsonArray("workloads"), javaValue))
              .as("checked-in workload uses canonical syntax value %s", javaValue)
              .isTrue();
        }
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
