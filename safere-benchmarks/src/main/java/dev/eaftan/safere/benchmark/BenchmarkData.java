// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere.benchmark;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads benchmark data (patterns, texts, parameters) from the shared {@code benchmark-data.json}
 * file. This is the single source of truth for benchmark inputs, shared between Java and C++
 * harnesses.
 */
public final class BenchmarkData {

  private static final BenchmarkData INSTANCE = new BenchmarkData();

  private final JsonObject root;

  private BenchmarkData() {
    try (InputStream is = BenchmarkData.class.getResourceAsStream("/benchmark-data.json");
        InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
      root = new Gson().fromJson(reader, JsonObject.class);
    } catch (Exception e) {
      throw new RuntimeException("Failed to load benchmark-data.json", e);
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
}
