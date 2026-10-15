// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.MatchResult;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Exact reviewed reproductions; never used to suppress a fuzz finding. */
final class IntentionalReproductionTest {
  @TestFactory
  Stream<DynamicTest> replayReviewedCases() throws IOException {
    List<String> ids;
    try (var stream = resource("index.txt")) {
      ids =
          new String(stream.readAllBytes(), StandardCharsets.UTF_8)
              .lines()
              .filter(line -> !line.isBlank() && !line.startsWith("#"))
              .toList();
    }
    String selected = System.getProperty("safere.fuzz.reproduction");
    if (selected != null) {
      assertThat(ids).as("unknown reproduction ID").contains(selected);
      ids = List.of(selected);
    }
    return ids.stream().map(id -> DynamicTest.dynamicTest(id, () -> replay(id)));
  }

  private static java.io.InputStream resource(String name) {
    return Objects.requireNonNull(
        IntentionalReproductionTest.class.getResourceAsStream("/intentional-reproductions/" + name),
        name);
  }

  private static void replay(String id) throws IOException {
    Properties spec = new Properties();
    try (var reader = new InputStreamReader(resource(id + ".properties"), StandardCharsets.UTF_8)) {
      spec.load(reader);
    }
    assertThat(spec.getProperty("id")).isEqualTo(id);
    assertThat(spec.getProperty("rule")).startsWith("INTENTIONAL_DIVERGENCES.md#");
    assertThat(spec.getProperty("modelTest")).isNotBlank();
    String regex = required(spec, "regex");
    String input = required(spec, "input");
    String operation = required(spec, "operation");
    int flags = Integer.parseInt(required(spec, "flags"));
    String safe = safeOutcome(regex, flags, input, operation);
    String jdk = jdkOutcome(regex, flags, input, operation);
    assertThat(safe)
        .as("%s: SafeRE model changed; reclassify this reproduction", id)
        .isEqualTo(required(spec, "safeRe"));
    assertThat(jdk)
        .as("%s: JDK outcome changed; reclassify this reproduction", id)
        .isEqualTo(required(spec, "jdk"));
    assertThat(safe).isNotEqualTo(jdk);
    // The registry classifies only this exact case. Broad comparison must still expose it.
    assertThatThrownBy(
            () -> {
              var compiled = FuzzSupport.compileCompatibleOrSkip(regex, flags);
              assertThat(compiled).isNotNull();
              if (operation.equals("matches")) {
                compiled.matcher(input).matches();
              } else if (operation.equals("find")) {
                compiled.matcher(input).find();
              } else if (!operation.equals("compile")) {
                throw new IllegalArgumentException(operation);
              }
            })
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("divergence");
  }

  private static String required(Properties spec, String key) {
    return Objects.requireNonNull(spec.getProperty(key), key);
  }

  private static String safeOutcome(String regex, int flags, String input, String operation) {
    org.safere.Pattern pattern;
    try {
      pattern = org.safere.Pattern.compile(regex, flags);
    } catch (PatternSyntaxException expected) {
      return "rejected";
    }
    if (operation.equals("compile")) {
      return "accepted";
    }
    var matcher = pattern.matcher(input);
    boolean found =
        switch (operation) {
          case "matches" -> matcher.matches();
          case "find" -> matcher.find();
          default -> throw new IllegalArgumentException(operation);
        };
    return snapshot(found, matcher);
  }

  private static String jdkOutcome(String regex, int flags, String input, String operation) {
    java.util.regex.Pattern pattern;
    try {
      pattern = java.util.regex.Pattern.compile(regex, flags);
    } catch (PatternSyntaxException expected) {
      return "rejected";
    }
    if (operation.equals("compile")) {
      return "accepted";
    }
    var matcher = pattern.matcher(input);
    boolean found =
        switch (operation) {
          case "matches" -> matcher.matches();
          case "find" -> matcher.find();
          default -> throw new IllegalArgumentException(operation);
        };
    return snapshot(found, matcher);
  }

  private static String snapshot(boolean found, MatchResult matcher) {
    StringBuilder result = new StringBuilder(Boolean.toString(found));
    if (found) {
      for (int group = 0; group <= matcher.groupCount(); group++) {
        String text = matcher.group(group);
        result
            .append('|')
            .append(group)
            .append(':')
            .append(matcher.start(group))
            .append(':')
            .append(matcher.end(group))
            .append(':')
            .append(text == null ? "null" : FuzzSupport.javaStringLiteral(text));
      }
    }
    return result.toString();
  }
}
