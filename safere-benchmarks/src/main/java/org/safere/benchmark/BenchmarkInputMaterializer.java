// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Materializes resolved benchmark configuration and deterministic UTF-8 input files.
 *
 * <p>The bounded recipes in {@code benchmark-data.json} remain the human-editable source of truth.
 * Benchmark engines consume only the resulting byte-identical corpus.
 */
public final class BenchmarkInputMaterializer {
  private static final Gson GSON =
      new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  private static final String MANIFEST_FILE = "manifest.json";

  private final JsonObject data;
  private final String benchmarkDataSha256;
  private final Map<String, DeclarativeBenchmarkPlan.InputDeclaration> declarations;
  private final Map<String, byte[]> inputs = new LinkedHashMap<>();

  private BenchmarkInputMaterializer(JsonObject data, String benchmarkDataSha256) {
    this.data = data;
    this.benchmarkDataSha256 = benchmarkDataSha256;
    if (!data.has("schemaVersion")
        || data.get("schemaVersion").getAsInt() != DeclarativeBenchmarkPlan.SCHEMA_VERSION) {
      throw new IllegalArgumentException(
          "benchmark-data.json requires schemaVersion " + DeclarativeBenchmarkPlan.SCHEMA_VERSION);
    }
    if (!data.has("inputs") || !data.get("inputs").isJsonArray()) {
      throw new IllegalArgumentException("benchmark-data.json requires declarative inputs");
    }
    declarations = DeclarativeBenchmarkPlan.parseInputDeclarations(data.getAsJsonArray("inputs"));
  }

  /**
   * Generates the resolved benchmark manifest and materialized input corpus.
   *
   * @param args {@code [safere-benchmarks-directory] [output-directory]}
   * @throws Exception if materialization fails
   */
  public static void main(String[] args) throws Exception {
    if (args.length > 2) {
      throw new IllegalArgumentException(
          "Usage: BenchmarkInputMaterializer "
              + "[safere-benchmarks-directory] [output-directory]");
    }
    Path benchmarkDirectory = Path.of("safere-benchmarks");
    if (args.length >= 1) {
      benchmarkDirectory = Path.of(args[0]);
    }
    Path outputDirectory = benchmarkDirectory.resolve("target/benchmark-corpus");
    if (args.length == 2) {
      outputDirectory = Path.of(args[1]);
    }

    byte[] benchmarkDataBytes =
        Files.readAllBytes(benchmarkDirectory.resolve("benchmark-data.json"));
    JsonObject data =
        GSON.fromJson(new String(benchmarkDataBytes, StandardCharsets.UTF_8), JsonObject.class);

    BenchmarkInputMaterializer materializer =
        new BenchmarkInputMaterializer(data, sha256(benchmarkDataBytes));
    materializer.generate();
    materializer.write(outputDirectory);
  }

  static Map<String, byte[]> materialize(JsonObject data) {
    BenchmarkInputMaterializer materializer =
        new BenchmarkInputMaterializer(
            data, sha256(GSON.toJson(data).getBytes(StandardCharsets.UTF_8)));
    materializer.generate();
    Map<String, byte[]> result = new LinkedHashMap<>();
    materializer.inputs.forEach((id, bytes) -> result.put(id, bytes.clone()));
    return result;
  }

  private void generate() {
    for (String inputId : declarations.keySet()) {
      materialize(inputId);
    }
    if (inputs.size() != declarations.size()) {
      throw new IllegalStateException(
          "Materialized " + inputs.size() + " inputs for " + declarations.size() + " declarations");
    }
  }

  private byte[] materialize(String inputId) {
    Deque<MaterializationFrame> pending = new ArrayDeque<>();
    Set<String> activeRecipes = new LinkedHashSet<>();
    pending.push(new MaterializationFrame(inputId, false));
    while (!pending.isEmpty()) {
      MaterializationFrame frame = pending.pop();
      if (inputs.containsKey(frame.inputId())) {
        activeRecipes.remove(frame.inputId());
        continue;
      }
      DeclarativeBenchmarkPlan.InputDeclaration declaration = declarations.get(frame.inputId());
      if (declaration == null) {
        throw new IllegalArgumentException(
            "Input recipe references unknown materialized input: " + frame.inputId());
      }
      if (frame.dependenciesResolved()) {
        byte[] bytes = evaluate(declaration.recipe()).getBytes(StandardCharsets.UTF_8);
        if (inputs.put(frame.inputId(), bytes) != null) {
          throw new IllegalArgumentException(
              "Duplicate materialized input key: " + frame.inputId());
        }
        activeRecipes.remove(frame.inputId());
        continue;
      }
      if (!activeRecipes.add(frame.inputId())) {
        throw new IllegalArgumentException(
            "Cyclic materialized input recipe dependency: "
                + String.join(" -> ", activeRecipes)
                + " -> "
                + frame.inputId());
      }
      pending.push(new MaterializationFrame(frame.inputId(), true));
      String dependency = inputDependency(declaration.recipe());
      if (dependency != null && !inputs.containsKey(dependency)) {
        if (activeRecipes.contains(dependency)) {
          throw new IllegalArgumentException(
              "Cyclic materialized input recipe dependency: "
                  + String.join(" -> ", activeRecipes)
                  + " -> "
                  + dependency);
        }
        pending.push(new MaterializationFrame(dependency, false));
      }
    }
    return inputs.get(inputId);
  }

  private static String inputDependency(DeclarativeBenchmarkPlan.InputRecipe recipe) {
    if (recipe.kind() != DeclarativeBenchmarkPlan.RecipeKind.APPEND_INPUT) {
      return null;
    }
    return string(recipe.arguments(), "input");
  }

  private String evaluate(DeclarativeBenchmarkPlan.InputRecipe recipe) {
    Map<String, DeclarativeBenchmarkPlan.RecipeValue> arguments = recipe.arguments();
    return switch (recipe.kind()) {
      case LITERAL -> string(arguments, "text");
      case REPEAT -> string(arguments, "value").repeat(integer(arguments, "count"));
      case REPEAT_TO_LENGTH ->
          repeatToSize(string(arguments, "unit"), integer(arguments, "length"));
      case REPEAT_AT_LEAST_LENGTH ->
          repeatAtLeastSize(string(arguments, "unit"), integer(arguments, "minimumLength"));
      case DELIMITED_REPEAT_TO_LENGTH ->
          repeatedInput(
              string(arguments, "value"),
              integer(arguments, "length"),
              string(arguments, "delimiterAlphabet"),
              integer(arguments, "seed"));
      case APPEND_INPUT ->
          new String(inputs.get(string(arguments, "input")), StandardCharsets.UTF_8)
              + string(arguments, "suffix");
      case RANDOM_CHARS ->
          randomChars(
              string(arguments, "alphabet"),
              integer(arguments, "length"),
              integer(arguments, "seed"));
      case RANDOM_CODE_POINTS ->
          randomCodePoints(
              integers(arguments, "codePoints"),
              integer(arguments, "minimumCodeUnits"),
              integer(arguments, "seed"));
      case SURROUND_TO_LENGTH ->
          surroundToSize(
              string(arguments, "prefix"),
              string(arguments, "unit"),
              string(arguments, "suffix"),
              integer(arguments, "length"));
      case SUFFIX_TO_LENGTH ->
          suffixMatchToSize(
              string(arguments, "prefixUnit"),
              string(arguments, "suffix"),
              integer(arguments, "length"));
      case PREFIXED_REPEAT_TO_LENGTH ->
          prefixedInput(
              string(arguments, "prefix"),
              string(arguments, "value"),
              integer(arguments, "length"),
              string(arguments, "delimiterAlphabet"),
              integer(arguments, "seed"));
      case SPARSE_MATCH_TO_LENGTH ->
          sparseInput(
              string(arguments, "match"),
              string(arguments, "nonMatch"),
              integer(arguments, "length"),
              integer(arguments, "seed"),
              integer(arguments, "nonMatchRepeats"),
              string(arguments, "delimiterAlphabet"));
      case CENTER_IN_SPACES ->
          surroundWithSpaces(string(arguments, "body"), integer(arguments, "length"));
      case SCALED_CENTER_IN_SPACES ->
          scaledSurroundWithSpaces(
              string(arguments, "bodyPrefix"),
              string(arguments, "bodySuffix"),
              string(arguments, "bodyFill"),
              integer(arguments, "bodyScalePercent"),
              integer(arguments, "length"));
      case LAZY_ALTERNATION_TO_LENGTH ->
          lazyAltInput(
              string(arguments, "prefixUnit"),
              string(arguments, "match"),
              string(arguments, "suffixUnit"),
              integer(arguments, "length"));
      case PERIODIC_ALTERNATION_TO_LENGTH ->
          altCaptureInput(
              string(arguments, "hitUnit"),
              string(arguments, "missUnit"),
              integer(arguments, "hitInterval"),
              integer(arguments, "length"));
      case OPTIONAL_REQUIRED_REPEAT_PATTERN ->
          string(arguments, "literal").concat("?").repeat(integer(arguments, "count"))
              + string(arguments, "literal").repeat(integer(arguments, "count"));
    };
  }

  private record MaterializationFrame(String inputId, boolean dependenciesResolved) {}

  private static String randomChars(String alphabet, int size, int seed) {
    Random random = new Random(seed);
    char[] characters = new char[size];
    for (int index = 0; index < size; index++) {
      characters[index] = alphabet.charAt(random.nextInt(alphabet.length()));
    }
    return new String(characters);
  }

  private static String randomCodePoints(List<Integer> codePoints, int minimumSize, int seed) {
    Random random = new Random(seed);
    StringBuilder result = new StringBuilder(minimumSize);
    while (result.length() < minimumSize) {
      result.appendCodePoint(codePoints.get(random.nextInt(codePoints.size())));
    }
    return result.toString();
  }

  private static String repeatAtLeastSize(String unit, int minimumSize) {
    if (minimumSize == 0) {
      return "";
    }
    StringBuilder result = new StringBuilder(minimumSize + unit.length());
    while (result.length() < minimumSize) {
      result.append(unit);
    }
    return result.toString();
  }

  static String repeatToSize(String unit, int size) {
    if (size == 0) {
      return "";
    }
    if (unit.isEmpty()) {
      throw new IllegalArgumentException("Repeat unit must not be empty when size is positive");
    }
    StringBuilder result = new StringBuilder(size + unit.length());
    while (result.length() < size) {
      result.append(unit);
    }
    return result.substring(0, size);
  }

  private static String repeatedInput(String template, int size, String alphabet, int seed) {
    if (template.length() >= size) {
      return template.substring(0, size);
    }
    StringBuilder result = new StringBuilder(size);
    int delimiterIndex = seed;
    while (result.length() < size) {
      result.append(template);
      if (result.length() < size) {
        result.append(alphabet.charAt(Math.floorMod(delimiterIndex, alphabet.length())));
        delimiterIndex++;
      }
    }
    return result.substring(0, size);
  }

  private static String prefixedInput(
      String prefix, String template, int size, String alphabet, int seed) {
    if (prefix.length() >= size) {
      return prefix.substring(0, size);
    }
    return prefix + repeatedInput(template, size - prefix.length(), alphabet, seed);
  }

  private static String sparseInput(
      String match, String nonMatch, int size, int seed, int nonMatchRepeats, String alphabet) {
    StringBuilder result = new StringBuilder(size);
    int delimiterIndex = seed;
    while (result.length() < size) {
      for (int index = 0; index < nonMatchRepeats && result.length() < size; index++) {
        result.append(nonMatch);
        if (result.length() < size) {
          result.append(alphabet.charAt(Math.floorMod(delimiterIndex, alphabet.length())));
          delimiterIndex++;
        }
      }
      if (result.length() < size) {
        result.append(' ').append(match).append(' ');
      }
    }
    return result.substring(0, size);
  }

  private static String scaledSurroundWithSpaces(
      String bodyPrefix, String bodySuffix, String bodyFill, int scalePercent, int size) {
    int fixedLength = bodyPrefix.length() + bodySuffix.length();
    int targetLength = Math.max(fixedLength, size * scalePercent / 100);
    targetLength = Math.min(targetLength, size);
    String body =
        bodyPrefix + repeatToSize(bodyFill, Math.max(0, targetLength - fixedLength)) + bodySuffix;
    return surroundWithSpaces(body, size);
  }

  private static String surroundWithSpaces(String body, int size) {
    if (body.length() >= size) {
      return body.substring(0, size);
    }
    int padding = size - body.length();
    int leading = padding / 2;
    return " ".repeat(leading) + body + " ".repeat(padding - leading);
  }

  private static String surroundToSize(String prefix, String unit, String suffix, int size) {
    int bodySize = Math.max(0, size - prefix.length() - suffix.length());
    return prefix + repeatToSize(unit, bodySize) + suffix;
  }

  private static String suffixMatchToSize(String prefixUnit, String match, int size) {
    return repeatToSize(prefixUnit, Math.max(0, size - match.length())) + match;
  }

  private static String lazyAltInput(String prefixUnit, String match, String suffixUnit, int size) {
    StringBuilder result = new StringBuilder(size + match.length() + prefixUnit.length());
    int halfSize = size / 2;
    while (result.length() < halfSize) {
      result.append(prefixUnit);
    }
    result.append(match);
    while (result.length() < size) {
      result.append(suffixUnit);
    }
    return result.substring(0, size);
  }

  private static String altCaptureInput(
      String hitUnit, String missUnit, int hitInterval, int size) {
    StringBuilder result = new StringBuilder(size + Math.max(hitUnit.length(), missUnit.length()));
    int counter = 0;
    while (result.length() < size) {
      result.append(counter % hitInterval == 0 ? hitUnit : missUnit);
      counter++;
    }
    return result.substring(0, size);
  }

  private static String string(
      Map<String, DeclarativeBenchmarkPlan.RecipeValue> arguments, String name) {
    return ((DeclarativeBenchmarkPlan.RecipeString) arguments.get(name)).value();
  }

  private static int integer(
      Map<String, DeclarativeBenchmarkPlan.RecipeValue> arguments, String name) {
    return ((DeclarativeBenchmarkPlan.RecipeInteger) arguments.get(name)).value();
  }

  private static List<Integer> integers(
      Map<String, DeclarativeBenchmarkPlan.RecipeValue> arguments, String name) {
    return ((DeclarativeBenchmarkPlan.RecipeIntegerList) arguments.get(name)).values();
  }

  private void write(Path corpusDirectory) throws IOException {
    if (Files.exists(corpusDirectory)) {
      try (Stream<Path> paths = Files.walk(corpusDirectory)) {
        for (Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
          if (!path.equals(corpusDirectory)) {
            Files.delete(path);
          }
        }
      }
    }
    Files.createDirectories(corpusDirectory);

    JsonObject manifest = manifest();
    for (Map.Entry<String, byte[]> input : inputs.entrySet()) {
      String relativePath =
          manifest
              .getAsJsonObject("inputs")
              .getAsJsonObject(input.getKey())
              .get("file")
              .getAsString();
      Path output = corpusDirectory.resolve(relativePath);
      Files.createDirectories(output.getParent());
      Files.write(output, input.getValue());
    }
    Files.writeString(
        corpusDirectory.resolve(MANIFEST_FILE),
        GSON.toJson(manifest) + System.lineSeparator(),
        StandardCharsets.UTF_8);
    System.out.printf("Materialized %d benchmark inputs in %s%n", inputs.size(), corpusDirectory);
  }

  private JsonObject manifest() {
    JsonObject manifest = new JsonObject();
    manifest.addProperty("version", 1);
    manifest.addProperty(
        "description", "Resolved benchmark configuration and generated UTF-8 inputs.");
    manifest.addProperty("benchmarkDataSha256", benchmarkDataSha256);
    manifest.add("benchmarkData", data.deepCopy());
    JsonObject entries = new JsonObject();
    for (Map.Entry<String, byte[]> input : inputs.entrySet()) {
      byte[] bytes = input.getValue();
      String text = new String(bytes, StandardCharsets.UTF_8);
      DeclarativeBenchmarkPlan.InputDeclaration declaration = declarations.get(input.getKey());
      JsonObject entry = new JsonObject();
      entry.addProperty("file", input.getKey().replace('.', '/') + ".txt");
      entry.addProperty("shared", declaration.shared());
      entry.addProperty("utf8Bytes", bytes.length);
      entry.addProperty("utf16CodeUnits", text.length());
      entry.addProperty("unicodeScalars", text.codePointCount(0, text.length()));
      entry.addProperty("sha256", sha256(bytes));
      entries.add(input.getKey(), entry);
    }
    manifest.add("inputs", entries);
    return manifest;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError("SHA-256 must be available", exception);
    }
  }
}
