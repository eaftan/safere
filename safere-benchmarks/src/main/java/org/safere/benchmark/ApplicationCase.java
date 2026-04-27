// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** One data-driven application benchmark case from {@code benchmark-data.json}. */
final class ApplicationCase {

  final String name;
  final String op;
  final String pattern;
  final List<String> texts;
  final String text;
  final int[] groups;
  final String replacement;
  final JsonElement expected;

  private ApplicationCase(
      String name,
      String op,
      String pattern,
      List<String> texts,
      String text,
      int[] groups,
      String replacement,
      JsonElement expected) {
    this.name = name;
    this.op = op;
    this.pattern = pattern;
    this.texts = texts;
    this.text = text;
    this.groups = groups;
    this.replacement = replacement;
    this.expected = expected;
  }

  static ApplicationCase fromJson(JsonObject obj) {
    String name = requireString(obj, "name");
    String op = requireString(obj, "op");
    String pattern = requireString(obj, "pattern");
    List<String> texts = stringList(obj, "texts");
    String text = optionalString(obj, "text");
    int[] groups = intArray(obj, "groups");
    String replacement = optionalString(obj, "replacement");
    JsonElement expected = require(obj, "expected");

    switch (op) {
      case "matchesCorpus" -> requireTexts(name, texts);
      case "matchesGroupLengthSum" -> {
        requireTexts(name, texts);
        requireGroups(name, groups);
      }
      case "findAllCount", "findAllLengthSum" -> requireText(name, text);
      case "findAllGroupLengthSum" -> {
        requireText(name, text);
        requireGroups(name, groups);
      }
      case "replaceAll" -> {
        requireText(name, text);
        if (replacement == null) {
          throw new IllegalArgumentException(name + " requires replacement");
        }
      }
      default -> throw new IllegalArgumentException("Unknown application benchmark op: " + op);
    }
    return new ApplicationCase(name, op, pattern, texts, text, groups, replacement, expected);
  }

  boolean expectsString() {
    return "replaceAll".equals(op);
  }

  int expectedInt() {
    return expected.getAsInt();
  }

  String expectedString() {
    return expected.getAsString();
  }

  private static JsonElement require(JsonObject obj, String key) {
    JsonElement value = obj.get(key);
    if (value == null) {
      throw new IllegalArgumentException("Missing application case field: " + key);
    }
    return value;
  }

  private static String requireString(JsonObject obj, String key) {
    return require(obj, key).getAsString();
  }

  private static String optionalString(JsonObject obj, String key) {
    JsonElement value = obj.get(key);
    return value == null ? null : value.getAsString();
  }

  private static List<String> stringList(JsonObject obj, String key) {
    JsonElement value = obj.get(key);
    if (value == null) {
      return List.of();
    }
    List<String> result = new ArrayList<>(value.getAsJsonArray().size());
    value.getAsJsonArray().forEach(item -> result.add(item.getAsString()));
    return List.copyOf(result);
  }

  private static int[] intArray(JsonObject obj, String key) {
    JsonElement value = obj.get(key);
    if (value == null) {
      return new int[0];
    }
    int[] result = new int[value.getAsJsonArray().size()];
    for (int i = 0; i < result.length; i++) {
      result[i] = value.getAsJsonArray().get(i).getAsInt();
    }
    return result;
  }

  private static void requireTexts(String name, List<String> texts) {
    if (texts.isEmpty()) {
      throw new IllegalArgumentException(name + " requires texts");
    }
  }

  private static void requireText(String name, String text) {
    if (text == null) {
      throw new IllegalArgumentException(name + " requires text");
    }
  }

  private static void requireGroups(String name, int[] groups) {
    if (groups.length == 0) {
      throw new IllegalArgumentException(name + " requires groups");
    }
  }
}
