// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.recording;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/** Lossless typed encoding for facade arguments and results. */
final class ValueEncoder {

  private ValueEncoder() {}

  static String encode(Object value) {
    if (value == null) {
      return "N";
    }
    if (value instanceof CharSequence sequence) {
      return encodeString("S", sequence.toString());
    }
    if (value instanceof Pattern pattern) {
      return encodeString(
          "P", pattern.delegate().pattern() + "\u0000" + pattern.delegate().flags());
    }
    if (value instanceof Matcher) {
      return "T";
    }
    if (value instanceof Character character) {
      return "C" + (int) character;
    }
    if (value instanceof Boolean bool) {
      return bool ? "B1" : "B0";
    }
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      return "I" + value;
    }
    if (value instanceof Float || value instanceof Double) {
      return "F" + value;
    }
    if (value instanceof String[] strings) {
      return encodeArray(strings);
    }
    if (value instanceof byte[] bytes) {
      return "Y" + Base64.getEncoder().encodeToString(bytes);
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, String> sorted = new TreeMap<>();
      map.forEach((key, mapValue) -> sorted.put(Objects.toString(key), Objects.toString(mapValue)));
      return encodeString("M", sorted.toString());
    }
    if (value instanceof Function<?, ?>) {
      return "L";
    }
    if (value instanceof java.util.regex.MatchResult matchResult) {
      return "R" + matchResult.getClass().getName();
    }
    return encodeString("O", Objects.toString(value));
  }

  private static String encodeArray(String[] strings) {
    StringBuilder result = new StringBuilder("A").append(strings.length).append(':');
    Arrays.stream(strings)
        .forEach(
            string -> {
              String encoded = encode(string);
              result.append(encoded.length()).append(':').append(encoded);
            });
    return result.toString();
  }

  private static String encodeString(String tag, String value) {
    return tag + value.length() + ':' + value;
  }
}
