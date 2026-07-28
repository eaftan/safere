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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Explicit engine-profile alternatives for Java-canonical benchmark regex syntax. */
final class PatternProfiles {
  private static final Pattern PROFILE_ID = Pattern.compile("[a-z][a-z0-9-]*");
  private static final Set<String> FLAG_SETS =
      Set.of(
          "0",
          "CASE_INSENSITIVE",
          "CASE_INSENSITIVE_UNICODE_CASE",
          "UNICODE_CHARACTER_CLASS",
          "CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS");

  private final Map<String, Map<String, Selection>> profiles;

  private PatternProfiles(Map<String, Map<String, Selection>> profiles) {
    this.profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
  }

  static JsonObject normalizeInline(JsonObject source) {
    if (source.has("patternProfiles") || source.has("replacementProfiles")) {
      throw new IllegalArgumentException(
          "benchmark-data.json must define syntax alternates next to their Java values");
    }
    JsonObject normalized = source.deepCopy();
    Map<String, Map<String, Alternate>> patternProfiles = new LinkedHashMap<>();
    Map<String, Map<String, Alternate>> replacementProfiles = new LinkedHashMap<>();
    Deque<NormalizationFrame> pending = new ArrayDeque<>();
    JsonElement syntaxRoot =
        normalized.has("schemaVersion") ? normalized.getAsJsonArray("workloads") : normalized;
    if (syntaxRoot != null) {
      pending.push(new NormalizationFrame(syntaxRoot, null));
    }
    while (!pending.isEmpty()) {
      NormalizationFrame frame = pending.pop();
      JsonElement element = frame.element();
      if (element.isJsonObject()) {
        JsonObject object = element.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : List.copyOf(object.entrySet())) {
          JsonElement child = entry.getValue();
          ValueKind childKind = valueKind(entry.getKey(), frame.kind());
          if (isInlinePattern(child)) {
            object.add(
                entry.getKey(),
                normalizePattern(
                    child.getAsJsonObject(), childKind, patternProfiles, replacementProfiles));
          } else if (child.isJsonArray() || child.isJsonObject()) {
            pending.push(new NormalizationFrame(child, childKind));
          }
        }
      } else {
        JsonArray array = element.getAsJsonArray();
        for (int index = 0; index < array.size(); index++) {
          JsonElement child = array.get(index);
          if (isInlinePattern(child)) {
            array.set(
                index,
                normalizePattern(
                    child.getAsJsonObject(), frame.kind(), patternProfiles, replacementProfiles));
          } else if (child.isJsonArray() || child.isJsonObject()) {
            pending.push(new NormalizationFrame(child, frame.kind()));
          }
        }
      }
    }
    normalized.add("patternProfiles", profileJson(patternProfiles));
    normalized.add("replacementProfiles", profileJson(replacementProfiles));
    return normalized;
  }

  private static boolean isInlinePattern(JsonElement element) {
    return element.isJsonObject()
        && (element.getAsJsonObject().has("java") || element.getAsJsonObject().has("alternates"));
  }

  private static ValueKind valueKind(String field, ValueKind inherited) {
    if (field.equals("pattern")
        || field.equals("patterns")
        || field.equals("regex")
        || field.endsWith("Pattern")) {
      return ValueKind.PATTERN;
    }
    return field.equals("replacement") ? ValueKind.REPLACEMENT : inherited;
  }

  private static JsonPrimitive normalizePattern(
      JsonObject definition,
      ValueKind expectedKind,
      Map<String, Map<String, Alternate>> patternProfiles,
      Map<String, Map<String, Alternate>> replacementProfiles) {
    if (expectedKind == null) {
      throw new IllegalArgumentException(
          "Inline benchmark syntax definition is not inside a pattern or replacement field");
    }
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
    String definitionKind = null;
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
        if (!field.equals("pattern")
            && !field.equals("replacement")
            && !field.equals("unsupported")
            && !field.equals("reason")
            && !field.equals("flagSets")) {
          throw new IllegalArgumentException(
              "Inline benchmark pattern alternate " + profileId + " has unknown field: " + field);
        }
      }
      boolean hasPattern = alternateObject.has("pattern");
      boolean hasReplacement = alternateObject.has("replacement");
      boolean unsupported = alternateObject.has("unsupported");
      int selectionCount = (hasPattern ? 1 : 0) + (hasReplacement ? 1 : 0) + (unsupported ? 1 : 0);
      if (selectionCount != 1) {
        throw new IllegalArgumentException(
            "Inline benchmark pattern alternate "
                + profileId
                + " requires exactly one of: pattern, replacement, unsupported");
      }
      if (unsupported
          && (!alternateObject.get("unsupported").isJsonPrimitive()
              || !alternateObject.getAsJsonPrimitive("unsupported").isBoolean()
              || !alternateObject.get("unsupported").getAsBoolean())) {
        throw new IllegalArgumentException(
            "Inline benchmark pattern alternate " + profileId + " field unsupported must be true");
      }
      String valueField = hasPattern ? "pattern" : hasReplacement ? "replacement" : null;
      ValueKind alternateKind =
          hasPattern ? ValueKind.PATTERN : hasReplacement ? ValueKind.REPLACEMENT : expectedKind;
      if (alternateKind != expectedKind) {
        throw new IllegalArgumentException(
            "Inline benchmark "
                + expectedKind.field()
                + " definition contains "
                + alternateKind.field()
                + " alternate");
      }
      if (valueField != null && definitionKind != null && !definitionKind.equals(valueField)) {
        throw new IllegalArgumentException(
            "Inline benchmark definition mixes pattern and replacement alternates");
      }
      if (valueField != null) {
        definitionKind = valueField;
      }
      Alternate alternate =
          new Alternate(
              unsupported
                  ? null
                  : requiredInlineString(
                      alternateObject,
                      valueField,
                      "Inline benchmark pattern alternate " + profileId),
              requiredInlineString(
                  alternateObject, "reason", "Inline benchmark pattern alternate " + profileId),
              optionalFlagSets(alternateObject, "Inline benchmark pattern alternate " + profileId));
      Alternate previous =
          (expectedKind == ValueKind.PATTERN ? patternProfiles : replacementProfiles)
              .computeIfAbsent(profileId, unused -> new LinkedHashMap<>())
              .putIfAbsent(javaPattern, alternate);
      if (previous != null && !previous.equals(alternate)) {
        throw new IllegalArgumentException(
            "Conflicting "
                + profileId
                + " alternates for Java "
                + expectedKind.field()
                + ": "
                + javaPattern);
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
        if (alternate.getValue().value() == null) {
          entry.addProperty("unsupported", true);
        } else {
          entry.addProperty("alternate", alternate.getValue().value());
        }
        entry.addProperty("reason", alternate.getValue().reason());
        if (!alternate.getValue().flagSets().isEmpty()) {
          JsonArray flagSets = new JsonArray();
          alternate.getValue().flagSets().forEach(flagSets::add);
          entry.add("flagSets", flagSets);
        }
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
    Map<String, Map<String, Selection>> profiles = new LinkedHashMap<>();
    for (Map.Entry<String, JsonElement> profileEntry : element.getAsJsonObject().entrySet()) {
      String profileId = profileEntry.getKey();
      if (!PROFILE_ID.matcher(profileId).matches()) {
        throw new IllegalArgumentException("Invalid benchmark pattern profile ID: " + profileId);
      }
      if (!profileEntry.getValue().isJsonArray()) {
        throw new IllegalArgumentException("Pattern profile " + profileId + " must be an array");
      }
      Map<String, Selection> alternatives =
          parseProfile(profileId, profileEntry.getValue().getAsJsonArray());
      profiles.put(profileId, Collections.unmodifiableMap(alternatives));
    }
    return new PatternProfiles(profiles);
  }

  private static Map<String, Selection> parseProfile(String profileId, JsonArray entries) {
    Map<String, Selection> alternatives = new LinkedHashMap<>();
    for (JsonElement element : entries) {
      if (!element.isJsonObject()) {
        throw new IllegalArgumentException(
            "Pattern profile " + profileId + " entries must be objects");
      }
      JsonObject entry = element.getAsJsonObject();
      for (String field : entry.keySet()) {
        if (!field.equals("java")
            && !field.equals("alternate")
            && !field.equals("unsupported")
            && !field.equals("reason")
            && !field.equals("flagSets")) {
          throw new IllegalArgumentException(
              "Pattern profile " + profileId + " entry has unknown field: " + field);
        }
      }
      String javaPattern = requiredString(entry, profileId, "java");
      boolean unsupported =
          entry.has("unsupported")
              && entry.get("unsupported").isJsonPrimitive()
              && entry.getAsJsonPrimitive("unsupported").isBoolean()
              && entry.get("unsupported").getAsBoolean();
      if (entry.has("alternate") == unsupported) {
        throw new IllegalArgumentException(
            "Pattern profile "
                + profileId
                + " entry requires exactly one of alternate or unsupported=true");
      }
      String reason = requiredString(entry, profileId, "reason");
      Selection selection =
          unsupported
              ? new Selection(null, reason, optionalFlagSets(entry, "Pattern profile " + profileId))
              : new Selection(
                  requiredString(entry, profileId, "alternate"),
                  null,
                  optionalFlagSets(entry, "Pattern profile " + profileId));
      if (alternatives.put(javaPattern, selection) != null) {
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

  private static Set<String> optionalFlagSets(JsonObject entry, String description) {
    JsonElement value = entry.get("flagSets");
    if (value == null) {
      return Set.of();
    }
    if (!value.isJsonArray() || value.getAsJsonArray().isEmpty()) {
      throw new IllegalArgumentException(description + " field flagSets must be a nonempty array");
    }
    Set<String> result = new LinkedHashSet<>();
    for (JsonElement element : value.getAsJsonArray()) {
      if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
        throw new IllegalArgumentException(description + " flagSets must contain strings");
      }
      String flagSet = element.getAsString();
      if (!FLAG_SETS.contains(flagSet)) {
        throw new IllegalArgumentException(description + " has unknown flag set: " + flagSet);
      }
      if (!result.add(flagSet)) {
        throw new IllegalArgumentException(description + " repeats flag set: " + flagSet);
      }
    }
    return Collections.unmodifiableSet(result);
  }

  String select(String profileId, String javaPattern) {
    return select(profileId, javaPattern, "0");
  }

  String select(String profileId, String javaPattern, String flagSet) {
    Selection selection = resolve(profileId, javaPattern, flagSet);
    if (selection.unsupportedReason() != null) {
      throw new IllegalArgumentException(
          "Profile "
              + profileId
              + " does not support Java syntax "
              + javaPattern
              + ": "
              + selection.unsupportedReason());
    }
    return selection.value();
  }

  Selection resolve(String profileId, String javaPattern) {
    return resolve(profileId, javaPattern, "0");
  }

  Selection resolve(String profileId, String javaPattern, String flagSet) {
    Map<String, Selection> alternatives = profiles.get(profileId);
    Selection selection = alternatives == null ? null : alternatives.get(javaPattern);
    return selection == null
            || (!selection.flagSets().isEmpty() && !selection.flagSets().contains(flagSet))
        ? new Selection(javaPattern, null, Set.of())
        : selection;
  }

  void validateReferences(Set<String> authoritativeValues, String kind) {
    for (Map.Entry<String, Map<String, Selection>> profile : profiles.entrySet()) {
      for (String javaValue : profile.getValue().keySet()) {
        if (!authoritativeValues.contains(javaValue)) {
          throw new IllegalArgumentException(
              "Unreferenced " + kind + " profile value for " + profile.getKey() + ": " + javaValue);
        }
      }
    }
  }

  private enum ValueKind {
    PATTERN("pattern"),
    REPLACEMENT("replacement");

    private final String field;

    ValueKind(String field) {
      this.field = field;
    }

    String field() {
      return field;
    }
  }

  private record NormalizationFrame(JsonElement element, ValueKind kind) {}

  record Selection(String value, String unsupportedReason, Set<String> flagSets) {}

  private record Alternate(String value, String reason, Set<String> flagSets) {}
}
