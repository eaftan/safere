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

  private RealWorldRegexCase(
      String name, String op, String pattern, String match, String nonMatch) {
    this.name = name;
    this.op = op;
    this.pattern = pattern;
    this.match = match;
    this.nonMatch = nonMatch;
  }

  static RealWorldRegexCase fromJson(JsonObject obj) {
    String name = requireString(obj, "name");
    String op = requireString(obj, "op");
    String pattern = requireString(obj, "pattern");
    String match = requireString(obj, "match");
    String nonMatch = requireString(obj, "nonMatch");
    if (!"find".equals(op) && !"replaceAllEmpty".equals(op)) {
      throw new IllegalArgumentException("Unknown real-world regex benchmark op: " + op);
    }
    return new RealWorldRegexCase(name, op, pattern, match, nonMatch);
  }

  private static String requireString(JsonObject obj, String field) {
    if (!obj.has(field) || obj.get(field).isJsonNull()) {
      throw new IllegalArgumentException("Real-world regex case requires " + field);
    }
    return obj.get(field).getAsString();
  }
}
