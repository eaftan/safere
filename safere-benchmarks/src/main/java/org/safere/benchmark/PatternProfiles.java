// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Explicit engine-profile alternatives for Java-canonical benchmark patterns. */
final class PatternProfiles {
  private static final Pattern PROFILE_ID = Pattern.compile("[a-z][a-z0-9-]*");

  private final Map<String, Map<String, String>> profiles;

  private PatternProfiles(Map<String, Map<String, String>> profiles) {
    this.profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
  }

  static JsonObject normalizeInline(JsonObject source) {
    if (source.has("patternProfiles")) {
      throw new IllegalArgumentException(
          "benchmark-data.json must define pattern alternates next to their Java patterns");
    }
    JsonObject normalized = source.deepCopy();
    Map<String, Map<String, Alternate>> profiles = new LinkedHashMap<>();
    Deque<JsonElement> pending = new ArrayDeque<>();
    pending.push(normalized);
    while (!pending.isEmpty()) {
      JsonElement element = pending.pop();
      if (element.isJsonObject()) {
        JsonObject object = element.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : List.copyOf(object.entrySet())) {
          JsonElement child = entry.getValue();
          if (isInlinePattern(child)) {
            object.add(entry.getKey(), normalizePattern(child.getAsJsonObject(), profiles));
          } else if (child.isJsonArray() || child.isJsonObject()) {
            pending.push(child);
          }
        }
      } else {
        JsonArray array = element.getAsJsonArray();
        for (int index = 0; index < array.size(); index++) {
          JsonElement child = array.get(index);
          if (isInlinePattern(child)) {
            array.set(index, normalizePattern(child.getAsJsonObject(), profiles));
          } else if (child.isJsonArray() || child.isJsonObject()) {
            pending.push(child);
          }
        }
      }
    }
    normalized.add("patternProfiles", profileJson(profiles));
    return normalized;
  }

  private static boolean isInlinePattern(JsonElement element) {
    return element.isJsonObject()
        && (element.getAsJsonObject().has("java") || element.getAsJsonObject().has("alternates"));
  }

  private static JsonPrimitive normalizePattern(
      JsonObject definition, Map<String, Map<String, Alternate>> profiles) {
    for (String field : definition.keySet()) {
      if (!field.equals("java") && !field.equals("alternates")) {
        throw new IllegalArgumentException("Inline benchmark pattern has unknown field: " + field);
      }
    }
    String javaPattern = requiredInlineString(definition, "java", "Inline benchmark pattern");
    JsonElement alternatesElement = definition.get("alternates");
    if (alternatesElement == null || !alternatesElement.isJsonObject()) {
      throw new IllegalArgumentException("Inline benchmark pattern requires object: alternates");
    }
    JsonObject alternates = alternatesElement.getAsJsonObject();
    if (alternates.isEmpty()) {
      throw new IllegalArgumentException("Inline benchmark pattern alternates must not be empty");
    }
    for (Map.Entry<String, JsonElement> entry : alternates.entrySet()) {
      String profileId = entry.getKey();
      if (!PROFILE_ID.matcher(profileId).matches()) {
        throw new IllegalArgumentException("Invalid benchmark pattern profile ID: " + profileId);
      }
      if (!entry.getValue().isJsonObject()) {
        throw new IllegalArgumentException(
            "Inline benchmark pattern alternate " + profileId + " must be an object");
      }
      JsonObject alternateObject = entry.getValue().getAsJsonObject();
      for (String field : alternateObject.keySet()) {
        if (!field.equals("pattern") && !field.equals("reason")) {
          throw new IllegalArgumentException(
              "Inline benchmark pattern alternate " + profileId + " has unknown field: " + field);
        }
      }
      Alternate alternate =
          new Alternate(
              requiredInlineString(
                  alternateObject, "pattern", "Inline benchmark pattern alternate " + profileId),
              requiredInlineString(
                  alternateObject, "reason", "Inline benchmark pattern alternate " + profileId));
      Alternate previous =
          profiles
              .computeIfAbsent(profileId, unused -> new LinkedHashMap<>())
              .putIfAbsent(javaPattern, alternate);
      if (previous != null && !previous.equals(alternate)) {
        throw new IllegalArgumentException(
            "Conflicting " + profileId + " alternates for Java pattern: " + javaPattern);
      }
    }
    return new JsonPrimitive(javaPattern);
  }

  private static String requiredInlineString(JsonObject object, String field, String description) {
    JsonElement value = object.get(field);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw new IllegalArgumentException(description + " requires string: " + field);
    }
    String string = value.getAsString();
    if (string.isBlank()) {
      throw new IllegalArgumentException(description + " field must not be blank: " + field);
    }
    return string;
  }

  private static JsonObject profileJson(Map<String, Map<String, Alternate>> profiles) {
    JsonObject result = new JsonObject();
    for (Map.Entry<String, Map<String, Alternate>> profile : profiles.entrySet()) {
      JsonArray entries = new JsonArray();
      for (Map.Entry<String, Alternate> alternate : profile.getValue().entrySet()) {
        JsonObject entry = new JsonObject();
        entry.addProperty("java", alternate.getKey());
        entry.addProperty("alternate", alternate.getValue().pattern());
        entry.addProperty("reason", alternate.getValue().reason());
        entries.add(entry);
      }
      result.add(profile.getKey(), entries);
    }
    return result;
  }

  static PatternProfiles parse(JsonElement element) {
    if (element == null) {
      return new PatternProfiles(Map.of());
    }
    if (!element.isJsonObject()) {
      throw new IllegalArgumentException("patternProfiles must be an object");
    }
    Map<String, Map<String, String>> profiles = new LinkedHashMap<>();
    for (Map.Entry<String, JsonElement> profileEntry : element.getAsJsonObject().entrySet()) {
      String profileId = profileEntry.getKey();
      if (!PROFILE_ID.matcher(profileId).matches()) {
        throw new IllegalArgumentException("Invalid benchmark pattern profile ID: " + profileId);
      }
      if (!profileEntry.getValue().isJsonArray()) {
        throw new IllegalArgumentException("Pattern profile " + profileId + " must be an array");
      }
      Map<String, String> alternatives =
          parseProfile(profileId, profileEntry.getValue().getAsJsonArray());
      profiles.put(profileId, Collections.unmodifiableMap(alternatives));
    }
    return new PatternProfiles(profiles);
  }

  private static Map<String, String> parseProfile(String profileId, JsonArray entries) {
    Map<String, String> alternatives = new LinkedHashMap<>();
    for (JsonElement element : entries) {
      if (!element.isJsonObject()) {
        throw new IllegalArgumentException(
            "Pattern profile " + profileId + " entries must be objects");
      }
      JsonObject entry = element.getAsJsonObject();
      for (String field : entry.keySet()) {
        if (!field.equals("java") && !field.equals("alternate") && !field.equals("reason")) {
          throw new IllegalArgumentException(
              "Pattern profile " + profileId + " entry has unknown field: " + field);
        }
      }
      String javaPattern = requiredString(entry, profileId, "java");
      String alternate = requiredString(entry, profileId, "alternate");
      requiredString(entry, profileId, "reason");
      if (alternatives.put(javaPattern, alternate) != null) {
        throw new IllegalArgumentException(
            "Pattern profile " + profileId + " repeats Java pattern: " + javaPattern);
      }
    }
    return alternatives;
  }

  private static String requiredString(JsonObject entry, String profileId, String field) {
    JsonElement value = entry.get(field);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw new IllegalArgumentException(
          "Pattern profile " + profileId + " entry requires string field: " + field);
    }
    String string = value.getAsString();
    if (string.isBlank()) {
      throw new IllegalArgumentException(
          "Pattern profile " + profileId + " entry field must not be blank: " + field);
    }
    return string;
  }

  String select(String profileId, String javaPattern) {
    Map<String, String> alternatives = profiles.get(profileId);
    return alternatives == null ? javaPattern : alternatives.getOrDefault(javaPattern, javaPattern);
  }

  private record Alternate(String pattern, String reason) {}
}
