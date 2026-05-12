// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/** JSON helpers for exhaustive sweep JSONL reports. */
final class SweepJson {
  private static final Gson GSON = new Gson();

  private SweepJson() {}

  static JsonObject object() {
    return new JsonObject();
  }

  static String toJson(JsonObject object) {
    return GSON.toJson(object);
  }

  static String field(String line, String field) {
    JsonElement element;
    try {
      element = JsonParser.parseString(line);
    } catch (JsonParseException e) {
      return null;
    }
    if (!element.isJsonObject()) {
      return null;
    }
    JsonElement value = element.getAsJsonObject().get(field);
    if (value == null || value.isJsonNull()) {
      return null;
    }
    return value.getAsString();
  }

  static String legacyUnescape(String value) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c != '\\') {
        result.append(c);
        continue;
      }
      if (++i >= value.length()) {
        throw new IllegalArgumentException("trailing JSON escape in: " + value);
      }
      char escaped = value.charAt(i);
      switch (escaped) {
        case 'n' -> result.append('\n');
        case 't' -> result.append('\t');
        case 'r' -> result.append('\r');
        case 'b' -> result.append('\b');
        case 'f' -> result.append('\f');
        case '"', '\\' -> result.append(escaped);
        case 'u' -> {
          if (i + 4 >= value.length()) {
            throw new IllegalArgumentException("short JSON unicode escape in: " + value);
          }
          result.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
          i += 4;
        }
        default -> result.append(escaped);
      }
    }
    return result.toString();
  }
}
