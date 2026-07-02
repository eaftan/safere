// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/** Comparative performance benchmark for regex engines using real-world regex patterns. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RealWorldRegexBenchmark {

  /** Functional interface for regex search operation. */
  @FunctionalInterface
  public interface RegexFind {
    boolean find(String input);
  }

  /** Functional interface for regex replace operation. */
  @FunctionalInterface
  public interface RegexReplaceAll {
    String replaceAll(String input, String replacement);
  }

  /** Container wrapping pattern instances and operations for a specific regex engine. */
  public record RegexEngine(Object pattern, RegexFind finder, RegexReplaceAll replacer)
      implements AutoCloseable {

    @Override
    public void close() throws Exception {
      if (pattern instanceof AutoCloseable closeable) {
        closeable.close();
      }
    }
  }

  /** Unified benchmark operation resolved during setup. */
  @FunctionalInterface
  public interface BenchmarkOperation {
    /** Executes the pre-configured operation. */
    Object execute(String input);
  }

  /** Engine types available for benchmarking. */
  public enum EngineType {
    SafeRE {
      @Override
      public RegexEngine compile(String patternStr) {
        org.safere.Pattern p = org.safere.Pattern.compile(patternStr);
        return new RegexEngine(
            p,
            input -> p.matcher(input).find(),
            (input, replacement) -> p.matcher(input).replaceAll(replacement));
      }
    },
    JDK {
      @Override
      public RegexEngine compile(String patternStr) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(patternStr);
        return new RegexEngine(
            p,
            input -> p.matcher(input).find(),
            (input, replacement) -> p.matcher(input).replaceAll(replacement));
      }
    },
    RE2J {
      @Override
      public RegexEngine compile(String patternStr) {
        com.google.re2j.Pattern p = com.google.re2j.Pattern.compile(patternStr);
        return new RegexEngine(
            p,
            input -> p.matcher(input).find(),
            (input, replacement) -> p.matcher(input).replaceAll(replacement));
      }
    },
    RE2_FFM {
      @Override
      public RegexEngine compile(String patternStr) {
        org.safere.re2ffm.RE2FfmPattern p = org.safere.re2ffm.RE2FfmPattern.compile(patternStr);
        return new RegexEngine(
            p,
            input -> p.matcher(input).find(),
            (input, replacement) -> p.matcher(input).replaceAll(replacement));
      }
    };

    public abstract RegexEngine compile(String patternStr);
  }

  @Param public EngineType engine;

  @Param({
    "mapFieldPath",
    "markupImageLink",
    "versionList",
    "malformedEntity",
    "markupEntity",
    "customProtocolLink",
    "wildcardSearch",
    "metadataBlock",
    "blockedTags1",
    "blockedTags2",
    "overlappingUrl",
    "caseInsensitiveKeyword",
    "boundedNameMatch",
    "templateTagMatch",
    "sparseUrl",
    "unprefixedWordBoundary",
    "fruitSearchQuery",
    "fruitMarkupTag",
    "charReplace",
    "layoutBlock",
    "turnTitleWhitespaceCjk"
  })
  public String patternName;

  @Param({"1000", "10000", "100000"})
  public int inputSize;

  @Param({"true", "false"})
  public boolean match;

  private RegexEngine regexEngine;
  private BenchmarkOperation benchmarkOp;
  private String testInput;

  @Setup
  public void setup() {
    BenchmarkData data = BenchmarkData.get();
    Map<String, RealWorldRegexCase> cases = data.getRealWorldRegexCases();
    RealWorldRegexCase regexCase = cases.get(patternName);
    if (regexCase == null) {
      throw new IllegalArgumentException("Unknown real-world regex benchmark case: " + patternName);
    }

    regexEngine = engine.compile(regexCase.pattern);

    benchmarkOp =
        switch (regexCase.op) {
          case "find" -> input -> regexEngine.finder().find(input);
          case "replaceAllEmpty" -> input -> regexEngine.replacer().replaceAll(input, "");
          case "replaceAllGroup1" -> input -> regexEngine.replacer().replaceAll(input, "$1");
          case "replaceAllLiteral" -> input -> regexEngine.replacer().replaceAll(input, "xyz");
          default ->
              throw new IllegalArgumentException(
                  "Unknown real-world regex benchmark op: " + regexCase.op);
        };

    RealWorldRegexCase.InputSpec inputSpec = match ? regexCase.matchInput : regexCase.nonMatchInput;
    String alphabet = data.getString("realWorldRegex.safeDelimiterAlphabet");
    int seed = data.getInt("realWorldRegex.seed");
    testInput = generateInput(regexCase, inputSpec, match, inputSize, alphabet, seed);
  }

  @TearDown
  public void tearDown() throws Exception {
    regexEngine.close();
  }

  private String generateInput(String template, int size, String alphabet, int seed) {
    if (template.length() >= size) {
      return template.substring(0, size);
    }
    StringBuilder sb = new StringBuilder(size);
    int delimiterIndex = seed;
    while (sb.length() < size) {
      sb.append(template);
      if (sb.length() < size) {
        sb.append(alphabet.charAt(Math.floorMod(delimiterIndex, alphabet.length())));
        delimiterIndex++;
      }
    }
    return sb.substring(0, size);
  }

  private String generateInput(
      RealWorldRegexCase regexCase,
      RealWorldRegexCase.InputSpec inputSpec,
      boolean match,
      int size,
      String alphabet,
      int seed) {
    String template = match ? regexCase.match : regexCase.nonMatch;
    return switch (inputSpec.kind) {
      case "repeat" -> generateInput(template, size, alphabet, seed);
      case "prefixedRepeat" ->
          generatePrefixedInput(inputSpec.prefix, template, size, alphabet, seed);
      case "sparseMatch" ->
          generateSparseInput(
              regexCase.match,
              regexCase.nonMatch,
              size,
              seed,
              inputSpec.nonMatchRepeats,
              inputSpec.delimiterAlphabet);
      case "surroundWithSpaces" -> generateSurroundWithSpacesInput(inputSpec.body, size);
      default ->
          throw new IllegalArgumentException("Unknown real-world input kind: " + inputSpec.kind);
    };
  }

  private String generateSurroundWithSpacesInput(String body, int size) {
    if (body.length() >= size) {
      return body.substring(0, size);
    }
    int totalPadding = size - body.length();
    int leadingPadding = totalPadding / 2;
    int trailingPadding = totalPadding - leadingPadding;
    return " ".repeat(leadingPadding) + body + " ".repeat(trailingPadding);
  }

  private String generatePrefixedInput(
      String prefix, String template, int size, String alphabet, int seed) {
    if (prefix.length() >= size) {
      return prefix.substring(0, size);
    }
    return prefix + generateInput(template, size - prefix.length(), alphabet, seed);
  }

  private String generateSparseInput(
      String matchPart,
      String nonMatchPart,
      int size,
      int seed,
      int nonMatchRepeats,
      String delimiterAlphabet) {
    StringBuilder sb = new StringBuilder(size);
    int delimiterIndex = seed;
    while (sb.length() < size) {
      for (int i = 0; i < nonMatchRepeats && sb.length() < size; i++) {
        sb.append(nonMatchPart);
        if (sb.length() < size) {
          sb.append(
              delimiterAlphabet.charAt(Math.floorMod(delimiterIndex, delimiterAlphabet.length())));
          delimiterIndex++;
        }
      }
      if (sb.length() < size) {
        sb.append(" ");
        sb.append(matchPart);
        sb.append(" ");
      }
    }
    return sb.substring(0, size);
  }

  @Benchmark
  public void runBenchmark(Blackhole bh) {
    bh.consume(benchmarkOp.execute(testInput));
  }
}
