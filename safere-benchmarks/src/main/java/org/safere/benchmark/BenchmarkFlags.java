// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

/** Shared parser for benchmark flag-set names. */
final class BenchmarkFlags {
  private BenchmarkFlags() {}

  static int parse(String flagSet) {
    return switch (flagSet) {
      case "0" -> 0;
      case "CASE_INSENSITIVE" -> org.safere.Pattern.CASE_INSENSITIVE;
      case "UNICODE_CHARACTER_CLASS" -> org.safere.Pattern.UNICODE_CHARACTER_CLASS;
      case "CASE_INSENSITIVE_UNICODE_CASE" ->
          org.safere.Pattern.CASE_INSENSITIVE | org.safere.Pattern.UNICODE_CASE;
      case "CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS" ->
          org.safere.Pattern.CASE_INSENSITIVE | org.safere.Pattern.UNICODE_CHARACTER_CLASS;
      default -> throw new IllegalArgumentException("Unknown flag set: " + flagSet);
    };
  }
}
