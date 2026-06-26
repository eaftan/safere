// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import com.google.gson.JsonObject;

/** One data-driven real-world regex benchmark case from {@code benchmark-data.json}. */
final class RealWorldRegexCase {

  final String name;
  final String op;
  final String pattern;
  final String match;
  final String nonMatch;
  final InputSpec matchInput;
  final InputSpec nonMatchInput;

  private RealWorldRegexCase(
      String name,
      String op,
      String pattern,
      String match,
      String nonMatch,
      InputSpec matchInput,
      InputSpec nonMatchInput) {
    this.name = name;
    this.op = op;
    this.pattern = pattern;
    this.match = match;
    this.nonMatch = nonMatch;
    this.matchInput = matchInput;
    this.nonMatchInput = nonMatchInput;
  }

  static RealWorldRegexCase fromJson(JsonObject obj) {
    String name = requireString(obj, "name");
    String op = requireString(obj, "op");
    String pattern = requireString(obj, "pattern");
    String match = requireString(obj, "match");
    String nonMatch = requireString(obj, "nonMatch");
    if (!"find".equals(op) && !"replaceAllEmpty".equals(op) && !"replaceAllGroup1".equals(op)) {
      throw new IllegalArgumentException("Unknown real-world regex benchmark op: " + op);
    }
    InputSpec matchInput = InputSpec.fromJson(obj, "matchInput");
    InputSpec nonMatchInput = InputSpec.fromJson(obj, "nonMatchInput");
    return new RealWorldRegexCase(name, op, pattern, match, nonMatch, matchInput, nonMatchInput);
  }

  private static String requireString(JsonObject obj, String field) {
    if (!obj.has(field) || obj.get(field).isJsonNull()) {
      throw new IllegalArgumentException("Real-world regex case requires " + field);
    }
    return obj.get(field).getAsString();
  }

  static final class InputSpec {

    final String kind;
    final String prefix;
    final int nonMatchRepeats;
    final String delimiterAlphabet;

    private InputSpec(String kind, String prefix, int nonMatchRepeats, String delimiterAlphabet) {
      this.kind = kind;
      this.prefix = prefix;
      this.nonMatchRepeats = nonMatchRepeats;
      this.delimiterAlphabet = delimiterAlphabet;
    }

    static InputSpec repeat() {
      return new InputSpec("repeat", "", 0, "");
    }

    static InputSpec fromJson(JsonObject obj, String field) {
      if (!obj.has(field) || obj.get(field).isJsonNull()) {
        return repeat();
      }
      JsonObject spec = obj.getAsJsonObject(field);
      String kind = requireString(spec, "kind");
      return switch (kind) {
        case "repeat" -> repeat();
        case "prefixedRepeat" -> new InputSpec(kind, requireString(spec, "prefix"), 0, "");
        case "sparseMatch" ->
            new InputSpec(
                kind,
                "",
                requireInt(spec, "nonMatchRepeats"),
                requireString(spec, "delimiterAlphabet"));
        default -> throw new IllegalArgumentException("Unknown real-world input kind: " + kind);
      };
    }

    private static int requireInt(JsonObject obj, String field) {
      if (!obj.has(field) || obj.get(field).isJsonNull()) {
        throw new IllegalArgumentException("Real-world regex input spec requires " + field);
      }
      return obj.get(field).getAsInt();
    }
  }
}
