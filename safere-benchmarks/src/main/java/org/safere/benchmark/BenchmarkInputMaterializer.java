// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Materializes resolved benchmark configuration and deterministic UTF-8 input files.
 *
 * <p>The compact recipes in {@code benchmark-data.json} remain the human-editable source of truth.
 * Benchmark engines consume only the resulting byte-identical corpus.
 */
public final class BenchmarkInputMaterializer {
  private static final Gson GSON =
      new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  private static final String MANIFEST_FILE = "manifest.json";

  private final JsonObject data;
  private final String benchmarkDataSha256;
  private final Map<String, byte[]> inputs = new LinkedHashMap<>();

  private BenchmarkInputMaterializer(JsonObject data, String benchmarkDataSha256) {
    this.data = data;
    this.benchmarkDataSha256 = benchmarkDataSha256;
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

  private void generate() {
    generateUtf8Matching();
    generateSearchScaling();
    generateIssue481Scaling();
    generateIssue488ReplaceAll();
    generateRealWorldRegex();
    generateScrubber();
    generatePathological();
    generateFanout();
    add("memory.dfaCacheText", "contact user.name+tag@example.co.uk for info".repeat(200));
  }

  private void generateUtf8Matching() {
    String prefix = "utf8Matching.literalScan.";
    String alphabet = string(prefix + "alphabet");
    int size = integer(prefix + "textSize");
    Random random = new Random(integer(prefix + "seed"));
    StringBuilder text = new StringBuilder(size);
    for (int index = 0; index < size; index++) {
      text.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    add("utf8Matching.literalScan.text", text.toString());

    prefix = "utf8Matching.requiredClassNonmatch.";
    add(
        "utf8Matching.requiredClassNonmatch.text",
        repeatToSize(string(prefix + "unit"), integer(prefix + "textSize")));

    for (int textSize : integers("utf8Matching.hardFailure.textSizes")) {
      add(
          "utf8Matching.hardFailure." + textSize,
          repeatToSize(string("utf8Matching.hardFailure.unit"), textSize));
    }
  }

  private void generateSearchScaling() {
    String alphabet = string("searchScaling.randomText.alphabet");
    int seed = integer("searchScaling.randomText.seed");
    String matchSuffix = string("searchScaling.matchSuffix");
    String proseUnit = string("searchScaling.proseUnit");
    for (int textSize : integers("searchScaling.textSizes")) {
      Random random = new Random(seed);
      char[] characters = new char[textSize];
      for (int index = 0; index < textSize; index++) {
        characters[index] = alphabet.charAt(random.nextInt(alphabet.length()));
      }
      String randomText = new String(characters);
      add("searchScaling.random." + textSize, randomText);
      add("searchScaling.success." + textSize, randomText + matchSuffix);

      StringBuilder prose = new StringBuilder(textSize + proseUnit.length());
      while (prose.length() < textSize) {
        prose.append(proseUnit);
      }
      add("searchScaling.prose." + textSize, prose.toString());
    }
  }

  private void generateIssue481Scaling() {
    for (int textSize : integers("issue481Scaling.textSizes")) {
      add(
          "issue481Scaling.splitW." + textSize,
          repeatToSize(string("issue481Scaling.splitW.unit"), textSize));
      add(
          "issue481Scaling.block." + textSize,
          surroundToSize(
              string("issue481Scaling.block.prefix"),
              string("issue481Scaling.block.unit"),
              string("issue481Scaling.block.suffix"),
              textSize));
      add(
          "issue481Scaling.blockNegative." + textSize,
          surroundToSize(
              string("issue481Scaling.block.prefix"),
              string("issue481Scaling.block.unit"),
              string("issue481Scaling.block.negativeSuffix"),
              textSize));
      add(
          "issue481Scaling.tag." + textSize,
          suffixMatchToSize(
              string("issue481Scaling.tag.prefixUnit"),
              string("issue481Scaling.tag.match"),
              textSize));
      add(
          "issue481Scaling.tagNegative." + textSize,
          suffixMatchToSize(
              string("issue481Scaling.tag.prefixUnit"),
              string("issue481Scaling.tag.negativeMatch"),
              textSize));
      add(
          "issue481Scaling.scheme." + textSize,
          suffixMatchToSize(
              string("issue481Scaling.scheme.prefixUnit"),
              string("issue481Scaling.scheme.match"),
              textSize));
      add(
          "issue481Scaling.schemeNegative." + textSize,
          suffixMatchToSize(
              string("issue481Scaling.scheme.prefixUnit"),
              string("issue481Scaling.scheme.negativeMatch"),
              textSize));
    }
  }

  private void generateIssue488ReplaceAll() {
    for (int textSize : integers("issue488ReplaceAll.textSizes")) {
      add(
          "issue488ReplaceAll.lazyAlt." + textSize,
          lazyAltInput(
              string("issue488ReplaceAll.lazyAlt.prefixUnit"),
              string("issue488ReplaceAll.lazyAlt.match"),
              string("issue488ReplaceAll.lazyAlt.suffixUnit"),
              textSize));
      add(
          "issue488ReplaceAll.altCapture." + textSize,
          altCaptureInput(
              string("issue488ReplaceAll.altCapture.hitUnit"),
              string("issue488ReplaceAll.altCapture.missUnit"),
              integer("issue488ReplaceAll.altCapture.hitInterval"),
              textSize));
    }
  }

  private void generateRealWorldRegex() {
    JsonObject section = object("realWorldRegex");
    int[] textSizes = integers("realWorldRegex.textSizes");
    String alphabet = string("realWorldRegex.safeDelimiterAlphabet");
    int seed = integer("realWorldRegex.seed");
    for (JsonElement element : section.getAsJsonArray("cases")) {
      RealWorldRegexCase benchmarkCase = RealWorldRegexCase.fromJson(element.getAsJsonObject());
      for (boolean match : new boolean[] {true, false}) {
        String label = match ? "match" : "noMatch";
        RealWorldRegexCase.InputSpec inputSpec =
            match ? benchmarkCase.matchInput : benchmarkCase.nonMatchInput;
        for (int textSize : textSizes) {
          add(
              "realWorldRegex." + benchmarkCase.name + "." + label + "." + textSize,
              realWorldInput(benchmarkCase, inputSpec, match, textSize, alphabet, seed));
        }
      }
    }
  }

  private void generateScrubber() {
    int repeatCount = integer("scrubber.repeatCount");
    add("scrubber.withoutDirectives", string("scrubber.baseWithoutDirectives").repeat(repeatCount));
    add("scrubber.withDirectives", string("scrubber.baseWithDirectives").repeat(repeatCount));
  }

  private void generatePathological() {
    for (int n : integers("pathological.nValues")) {
      add("pathological.pattern." + n, "a?".repeat(n) + "a".repeat(n));
      add("pathological.text." + n, "a".repeat(n));
    }
  }

  private void generateFanout() {
    int[] codePoints = integers("fanout.unicodeFanout.codePoints");
    int unicodeSeed = integer("fanout.unicodeFanout.seed");
    String alphabet = string("fanout.nestedQuantifier.alphabet");
    int asciiSeed = integer("fanout.nestedQuantifier.seed");
    for (int textSize : integers("fanout.textSizes")) {
      Random unicodeRandom = new Random(unicodeSeed);
      StringBuilder unicodeText = new StringBuilder();
      while (unicodeText.length() < textSize) {
        unicodeText.appendCodePoint(codePoints[unicodeRandom.nextInt(codePoints.length)]);
      }
      add("fanout.unicode." + textSize, unicodeText.toString());

      Random asciiRandom = new Random(asciiSeed);
      char[] asciiText = new char[textSize];
      for (int index = 0; index < textSize; index++) {
        asciiText[index] = alphabet.charAt(asciiRandom.nextInt(alphabet.length()));
      }
      add("fanout.ascii." + textSize, new String(asciiText));
    }
  }

  private String realWorldInput(
      RealWorldRegexCase benchmarkCase,
      RealWorldRegexCase.InputSpec inputSpec,
      boolean match,
      int size,
      String alphabet,
      int seed) {
    String template = match ? benchmarkCase.match : benchmarkCase.nonMatch;
    return switch (inputSpec.kind) {
      case "repeat" -> repeatedInput(template, size, alphabet, seed);
      case "prefixedRepeat" -> prefixedInput(inputSpec.prefix, template, size, alphabet, seed);
      case "sparseMatch" ->
          sparseInput(
              benchmarkCase.match,
              benchmarkCase.nonMatch,
              size,
              seed,
              inputSpec.nonMatchRepeats,
              inputSpec.delimiterAlphabet);
      case "surroundWithSpaces" -> surroundWithSpaces(inputSpec.body, size);
      case "scaledSurroundWithSpaces" ->
          scaledSurroundWithSpaces(
              inputSpec.bodyPrefix,
              inputSpec.bodySuffix,
              inputSpec.bodyFill,
              inputSpec.bodyScalePercent,
              size);
      default ->
          throw new IllegalArgumentException("Unknown real-world input kind: " + inputSpec.kind);
    };
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

  private void add(String key, String value) {
    byte[] previous = inputs.put(key, value.getBytes(StandardCharsets.UTF_8));
    if (previous != null) {
      throw new IllegalArgumentException("Duplicate materialized input key: " + key);
    }
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
      JsonObject entry = new JsonObject();
      entry.addProperty("file", input.getKey().replace('.', '/') + ".txt");
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

  private JsonObject object(String path) {
    return element(path).getAsJsonObject();
  }

  private String string(String path) {
    return element(path).getAsString();
  }

  private int integer(String path) {
    return element(path).getAsInt();
  }

  private int[] integers(String path) {
    JsonArray array = element(path).getAsJsonArray();
    int[] values = new int[array.size()];
    for (int index = 0; index < array.size(); index++) {
      values[index] = array.get(index).getAsInt();
    }
    return values;
  }

  private JsonElement element(String path) {
    JsonElement current = data;
    for (String part : path.split("\\.")) {
      current = current.getAsJsonObject().get(part);
      if (current == null) {
        throw new IllegalArgumentException("No benchmark data at path: " + path);
      }
    }
    return current;
  }
}
