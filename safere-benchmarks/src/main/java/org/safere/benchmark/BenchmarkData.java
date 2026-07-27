// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads resolved benchmark configuration and exact generated inputs from the materialized corpus.
 */
public final class BenchmarkData {

  private static final String CORPUS_PROPERTY = "safere.benchmark.corpus";
  private static final BenchmarkData INSTANCE = new BenchmarkData();

  private final Path corpusDirectory;
  private final JsonObject root;
  private final JsonObject materializedInputs;
  private final Map<String, byte[]> inputBytes = new ConcurrentHashMap<>();
  private final Map<String, String> inputStrings = new ConcurrentHashMap<>();

  private BenchmarkData() {
    String configuredCorpus = System.getProperty(CORPUS_PROPERTY);
    if (configuredCorpus == null || configuredCorpus.isBlank()) {
      throw new IllegalArgumentException(
          "Missing -D"
              + CORPUS_PROPERTY
              + "=<directory>; run benchmarks through the project scripts");
    }
    corpusDirectory = Path.of(configuredCorpus);
    Path manifestPath = corpusDirectory.resolve("manifest.json");
    try (Reader reader = Files.newBufferedReader(manifestPath, StandardCharsets.UTF_8)) {
      JsonObject manifest = new Gson().fromJson(reader, JsonObject.class);
      if (manifest.get("version").getAsInt() != 1) {
        throw new IllegalArgumentException(
            "Unsupported benchmark input manifest version: " + manifest.get("version"));
      }
      root = manifest.getAsJsonObject("benchmarkData");
      materializedInputs = manifest.getAsJsonObject("inputs");
    } catch (Exception e) {
      throw new RuntimeException("Failed to load materialized benchmark data: " + manifestPath, e);
    }
  }

  /** Returns the singleton instance. */
  public static BenchmarkData get() {
    return INSTANCE;
  }

  /** Get a string value by dot-separated path (e.g., "regex.literalMatch.pattern"). */
  public String getString(String path) {
    String[] parts = path.split("\\.");
    JsonElement el = root;
    for (String part : parts) {
      el = el.getAsJsonObject().get(part);
      if (el == null) {
        throw new IllegalArgumentException("No value at path: " + path);
      }
    }
    return el.getAsString();
  }

  /** Get an int value by dot-separated path. */
  public int getInt(String path) {
    String[] parts = path.split("\\.");
    JsonElement el = root;
    for (String part : parts) {
      el = el.getAsJsonObject().get(part);
      if (el == null) {
        throw new IllegalArgumentException("No value at path: " + path);
      }
    }
    return el.getAsInt();
  }

  /** Get an int array by dot-separated path. */
  public int[] getIntArray(String path) {
    String[] parts = path.split("\\.");
    JsonElement el = root;
    for (String part : parts) {
      el = el.getAsJsonObject().get(part);
      if (el == null) {
        throw new IllegalArgumentException("No value at path: " + path);
      }
    }
    JsonArray arr = el.getAsJsonArray();
    int[] result = new int[arr.size()];
    for (int i = 0; i < arr.size(); i++) {
      result[i] = arr.get(i).getAsInt();
    }
    return result;
  }

  /** Get a string array by dot-separated path. */
  public List<String> getStringList(String path) {
    String[] parts = path.split("\\.");
    JsonElement el = root;
    for (String part : parts) {
      el = el.getAsJsonObject().get(part);
      if (el == null) {
        throw new IllegalArgumentException("No value at path: " + path);
      }
    }
    JsonArray arr = el.getAsJsonArray();
    List<String> result = new ArrayList<>(arr.size());
    for (JsonElement item : arr) {
      result.add(item.getAsString());
    }
    return result;
  }

  /**
   * Returns one materialized benchmark input decoded from its canonical UTF-8 bytes.
   *
   * @param key logical input key from the materialized manifest
   * @return immutable decoded input
   */
  public String getInputString(String key) {
    return inputStrings.computeIfAbsent(
        key, ignored -> new String(loadInputBytes(key), StandardCharsets.UTF_8));
  }

  /**
   * Returns a copy of one canonical materialized UTF-8 benchmark input.
   *
   * @param key logical input key from the materialized manifest
   * @return copy of the materialized bytes
   */
  public byte[] getInputBytes(String key) {
    return loadInputBytes(key).clone();
  }

  /** Returns application benchmark cases in JSON order, keyed by case name. */
  public Map<String, ApplicationCase> getApplicationCases() {
    JsonArray arr = root.getAsJsonArray("application");
    Map<String, ApplicationCase> cases = new LinkedHashMap<>();
    for (JsonElement item : arr) {
      ApplicationCase appCase = ApplicationCase.fromJson(item.getAsJsonObject());
      if (cases.put(appCase.name, appCase) != null) {
        throw new IllegalArgumentException("Duplicate application benchmark case: " + appCase.name);
      }
    }
    return Collections.unmodifiableMap(cases);
  }

  /** Returns real-world regex benchmark cases in JSON order, keyed by case name. */
  public Map<String, RealWorldRegexCase> getRealWorldRegexCases() {
    JsonArray arr = root.getAsJsonObject("realWorldRegex").getAsJsonArray("cases");
    Map<String, RealWorldRegexCase> cases = new LinkedHashMap<>();
    for (JsonElement item : arr) {
      RealWorldRegexCase regexCase = RealWorldRegexCase.fromJson(item.getAsJsonObject());
      if (cases.put(regexCase.name, regexCase) != null) {
        throw new IllegalArgumentException(
            "Duplicate real-world regex benchmark case: " + regexCase.name);
      }
    }
    return Collections.unmodifiableMap(cases);
  }

  private byte[] loadInputBytes(String key) {
    return inputBytes.computeIfAbsent(key, this::readAndVerifyInput);
  }

  private byte[] readAndVerifyInput(String key) {
    JsonObject entry = materializedInputs.getAsJsonObject(key);
    if (entry == null) {
      throw new IllegalArgumentException("Unknown materialized benchmark input: " + key);
    }
    String relativePath = entry.get("file").getAsString();
    Path inputPath = corpusDirectory.resolve(relativePath);
    try {
      byte[] bytes = Files.readAllBytes(inputPath);
      int expectedLength = entry.get("utf8Bytes").getAsInt();
      if (bytes.length != expectedLength) {
        throw new IllegalArgumentException(
            "Materialized benchmark input has wrong length: "
                + key
                + " expected "
                + expectedLength
                + " but was "
                + bytes.length);
      }
      String expectedHash = entry.get("sha256").getAsString();
      String actualHash = sha256(bytes);
      if (!expectedHash.equals(actualHash)) {
        throw new IllegalArgumentException(
            "Materialized benchmark input has wrong SHA-256: "
                + key
                + " expected "
                + expectedHash
                + " but was "
                + actualHash);
      }
      return bytes;
    } catch (Exception exception) {
      throw new IllegalArgumentException(
          "Failed to load materialized benchmark input: " + key + " (" + inputPath + ")",
          exception);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError("SHA-256 must be available", exception);
    }
  }
}
