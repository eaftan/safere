// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
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
    return escapeUnpairedSurrogates(GSON.toJson(object));
  }

  static String field(String line, String field) {
    JsonObject object = parseObjectOrNull(line);
    if (object == null) {
      return null;
    }
    JsonElement value = object.get(field);
    if (value == null || value.isJsonNull()) {
      return null;
    }
    return value.getAsString();
  }

  static JsonObject parseObject(String line) {
    JsonObject object = parseObjectOrNull(line);
    if (object == null) {
      throw new IllegalArgumentException("expected JSON object: " + line);
    }
    return object;
  }

  static JsonObject object(JsonObject object, String field) {
    JsonElement value = object.get(field);
    if (value == null || value.isJsonNull()) {
      throw new IllegalArgumentException("missing JSON object field: " + field);
    }
    if (!value.isJsonObject()) {
      throw new IllegalArgumentException("expected JSON object field: " + field);
    }
    return value.getAsJsonObject();
  }

  static JsonArray array(JsonObject object, String field) {
    JsonElement value = object.get(field);
    if (value == null || value.isJsonNull()) {
      throw new IllegalArgumentException("missing JSON array field: " + field);
    }
    if (!value.isJsonArray()) {
      throw new IllegalArgumentException("expected JSON array field: " + field);
    }
    return value.getAsJsonArray();
  }

  static String string(JsonObject object, String field) {
    JsonElement value = object.get(field);
    if (value == null || value.isJsonNull()) {
      throw new IllegalArgumentException("missing JSON string field: " + field);
    }
    return value.getAsString();
  }

  static boolean bool(JsonObject object, String field) {
    JsonElement value = object.get(field);
    if (value == null || value.isJsonNull()) {
      throw new IllegalArgumentException("missing JSON boolean field: " + field);
    }
    return value.getAsBoolean();
  }

  static int integer(JsonObject object, String field) {
    JsonElement value = object.get(field);
    if (value == null || value.isJsonNull()) {
      throw new IllegalArgumentException("missing JSON integer field: " + field);
    }
    return value.getAsInt();
  }

  static JsonObject parseObjectOrNull(String line) {
    JsonElement element;
    try {
      element = JsonParser.parseString(line);
    } catch (JsonParseException e) {
      return null;
    }
    if (!element.isJsonObject()) {
      return null;
    }
    return element.getAsJsonObject();
  }

  private static String escapeUnpairedSurrogates(String json) {
    StringBuilder result = new StringBuilder(json.length());
    for (int i = 0; i < json.length(); i++) {
      char c = json.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (i + 1 < json.length() && Character.isLowSurrogate(json.charAt(i + 1))) {
          result.append(c);
          result.append(json.charAt(i + 1));
          i++;
        } else {
          appendUnicodeEscape(result, c);
        }
      } else if (Character.isLowSurrogate(c)) {
        appendUnicodeEscape(result, c);
      } else {
        result.append(c);
      }
    }
    return result.toString();
  }

  private static void appendUnicodeEscape(StringBuilder result, char c) {
    result.append("\\u");
    String hex = Integer.toHexString(c);
    for (int i = hex.length(); i < 4; i++) {
      result.append('0');
    }
    result.append(hex);
  }
}
