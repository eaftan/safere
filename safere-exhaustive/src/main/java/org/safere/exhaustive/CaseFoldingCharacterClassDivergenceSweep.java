// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.PatternSyntaxException;

/** Offline differential sweep for case-insensitive character-class closure bugs. */
public final class CaseFoldingCharacterClassDivergenceSweep {
  private static final int INPUT_COUNT = Character.MAX_CODE_POINT + 1;

  private static final List<FlagMode> FLAG_MODES =
      List.of(
          new FlagMode("caseInsensitive", java.util.regex.Pattern.CASE_INSENSITIVE),
          new FlagMode(
              "unicodeCase",
              java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE),
          new FlagMode(
              "unicodeCharacterClass",
              java.util.regex.Pattern.CASE_INSENSITIVE
                  | java.util.regex.Pattern.UNICODE_CHARACTER_CLASS));

  private static final List<PatternSpec> PATTERNS =
      List.of(
          pattern("literalLowerI", "\\x{69}"),
          pattern("literalUpperI", "\\x{49}"),
          pattern("literalLowerK", "\\x{6B}"),
          pattern("literalUpperK", "\\x{4B}"),
          pattern("literalLongS", "\\x{17F}"),
          pattern("literalGreekSigma", "\\x{3A3}"),
          pattern("literalGreekFinalSigma", "\\x{3C2}"),
          pattern("classLowerI", "[\\x{69}]"),
          pattern("classUpperI", "[\\x{49}]"),
          pattern("classLowerK", "[\\x{6B}]"),
          pattern("classUpperK", "[\\x{4B}]"),
          pattern("classLongS", "[\\x{17F}]"),
          pattern("rangeLowerAZ", "[a-z]"),
          pattern("rangeUpperAZ", "[A-Z]"),
          pattern("rangeLowerHJ", "[h-j]"),
          pattern("rangeUpperHJ", "[H-J]"),
          pattern("negatedRangeLowerHJ", "[^h-j]"),
          pattern("negatedRangeUpperHJ", "[^H-J]"),
          pattern("categoryUpper", "\\p{Lu}"),
          pattern("categoryLower", "\\p{Ll}"),
          pattern("categoryTitle", "\\p{Lt}"),
          pattern("categoryLetter", "\\p{L}"),
          pattern("categoryIsUpper", "\\p{IsLu}"),
          pattern("categoryIsLower", "\\p{IsLl}"),
          pattern("categoryIsTitle", "\\p{IsLt}"),
          pattern("categoryGeneralUpper", "\\p{gc=Lu}"),
          pattern("categoryGeneralLower", "\\p{gc=Ll}"),
          pattern("categoryGeneralTitle", "\\p{gc=Lt}"),
          pattern("classCategoryUpper", "[\\p{Lu}]"),
          pattern("classCategoryLower", "[\\p{Ll}]"),
          pattern("classCategoryTitle", "[\\p{Lt}]"),
          pattern("negatedCategoryUpper", "\\P{Lu}"),
          pattern("negatedCategoryLower", "\\P{Ll}"),
          pattern("negatedCategoryTitle", "\\P{Lt}"),
          pattern("classNegatedCategoryUpper", "[^\\p{Lu}]"),
          pattern("classNegatedCategoryLower", "[^\\p{Ll}]"),
          pattern("classNegatedCategoryTitle", "[^\\p{Lt}]"),
          pattern("javaLower", "\\p{javaLowerCase}"),
          pattern("javaUpper", "\\p{javaUpperCase}"),
          pattern("titleRepeat4", "\\p{Lt}{4}", 4));

  private CaseFoldingCharacterClassDivergenceSweep() {}

  public static void main(String[] args) throws IOException {
    SweepOptions options =
        SweepOptions.parse(
            args,
            Path.of("target", "exhaustive-reports", "case-folding-character-class-sweep"),
            "case-folding-character-class-divergences.jsonl",
            1_000_000);
    Files.createDirectories(options.outputDir());
    if (options.replayFile() != null) {
      Files.deleteIfExists(options.jsonlPath());
    }
    options.printStartup("case-folding-character-class");

    if (options.replayFile() != null) {
      runReplay(options);
      return;
    }

    SweepRunState state = runSweep(options);

    System.out.println("checked=" + state.checked.sum());
    System.out.println("generated=" + state.generated);
    System.out.println("totalCases=" + totalCases());
    System.out.println("divergences=" + state.divergences.sum());
    System.out.println("threads=" + options.threads());
  }

  private static SweepRunState runSweep(SweepOptions options) throws IOException {
    try (SweepRunState runState = new SweepRunState(options, options.totalChecks(totalCases()))) {
      runState.enableCompactLogs(
          "case-folding-character-class",
          totalCases(),
          List.of("UNKNOWN"),
          List.of(DivergenceStatus.UNKNOWN));
      SweepWorkers.run(
          options.threads(),
          "case-folding-character-class-sweep-",
          workerIndex -> {
            SweepState worker = new SweepState(runState, workerIndex);
            worker.run();
            worker.finish();
          });
      return runState;
    }
  }

  private static void runReplay(SweepOptions options) throws IOException {
    try (BufferedReader reader =
            Files.newBufferedReader(options.replayFile(), StandardCharsets.UTF_8);
        SweepRunState runState = new SweepRunState(options, 0)) {
      long generated =
          SweepWorkers.runStreamingLines(
              options.threads(),
              "case-folding-character-class-replay-",
              reader,
              line -> {
                runState.checked.increment();
                evaluateCase(runState, replayCase(line), -1, -1);
              });
      runState.recordGenerated(generated);
      System.out.println("checked=" + runState.checked.sum());
      System.out.println("generated=" + runState.generated);
      System.out.println("divergences=" + runState.divergences.sum());
      System.out.println("threads=" + options.threads());
      System.out.println("jsonl=" + options.jsonlPath());
      if (runState.divergences.sum() > 0) {
        throw new IllegalStateException(
            "replay found " + runState.divergences.sum() + " behavioral divergences");
      }
    }
  }

  private static CaseSpec replayCase(String line) {
    var object = SweepJson.parseObject(line);
    var caseObject = SweepJson.object(object, "case");
    return new CaseSpec(
        new PatternSpec(
            SweepJson.string(caseObject, "patternLabel"),
            SweepJson.string(caseObject, "regex"),
            SweepJson.integer(caseObject, "inputRepeat")),
        new FlagMode(
            SweepJson.string(caseObject, "flagLabel"), SweepJson.integer(caseObject, "flags")),
        SweepJson.integer(caseObject, "inputCodePoint"));
  }

  static long totalCases() {
    return (long) INPUT_COUNT * PATTERNS.size() * FLAG_MODES.size();
  }

  static String compactReplayJson(long caseIndex, String classification) {
    CaseSpec spec = caseAt(caseIndex);
    var object = SweepJson.object();
    object.addProperty("caseIndex", caseIndex);
    object.addProperty("classification", classification);
    object.add("case", Divergence.caseJson(spec));
    return SweepJson.toJson(object);
  }

  private static CaseSpec caseAt(long index) {
    int flagIndex = (int) (index % FLAG_MODES.size());
    index /= FLAG_MODES.size();
    int patternIndex = (int) (index % PATTERNS.size());
    index /= PATTERNS.size();
    int inputCodePoint = (int) index;
    return new CaseSpec(PATTERNS.get(patternIndex), FLAG_MODES.get(flagIndex), inputCodePoint);
  }

  private static Outcome jdkOutcome(String regex, int flags, String input) {
    try {
      return new Outcome(
          true, java.util.regex.Pattern.compile(regex, flags).matcher(input).matches(), "");
    } catch (PatternSyntaxException e) {
      return new Outcome(false, false, e.getDescription());
    } catch (RuntimeException e) {
      return new Outcome(false, false, e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static Outcome safeReOutcome(String regex, int flags, String input) {
    try {
      return new Outcome(
          true, org.safere.Pattern.compile(regex, flags).matcher(input).matches(), "");
    } catch (PatternSyntaxException e) {
      return new Outcome(false, false, e.getDescription());
    } catch (RuntimeException e) {
      return new Outcome(false, false, e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static String stringForCodePoint(int codePoint) {
    if (codePoint <= Character.MAX_VALUE) {
      return Character.toString((char) codePoint);
    }
    return new String(Character.toChars(codePoint));
  }

  private static String inputFor(CaseSpec spec) {
    return stringForCodePoint(spec.inputCodePoint()).repeat(spec.pattern().inputRepeat());
  }

  private static String escape(String value) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> result.append("\\\\");
        case '\n' -> result.append("\\n");
        case '\t' -> result.append("\\t");
        case '\r' -> result.append("\\r");
        case '"' -> result.append("\\\"");
        default -> {
          if (Character.isISOControl(c)
              || Character.isSurrogate(c)
              || Character.getType(c) == Character.PRIVATE_USE) {
            result.append(String.format("\\u%04X", (int) c));
          } else {
            result.append(c);
          }
        }
      }
    }
    return result.toString();
  }

  private static String bucketFor(CaseSpec spec, Outcome jdk, Outcome safere) {
    String kind = jdk.accepted() != safere.accepted() ? "compile" : "membership";
    return String.join(
        "|",
        "kind=" + kind,
        "direction=" + direction(jdk, safere),
        "pattern=" + spec.pattern().label(),
        "flags=" + spec.flagMode().label(),
        "inputClass=" + inputClass(spec.inputCodePoint()));
  }

  private static String direction(Outcome jdk, Outcome safere) {
    if (jdk.accepted() && !safere.accepted()) {
      return "jdk-accepts";
    }
    if (!jdk.accepted() && safere.accepted()) {
      return "safere-accepts";
    }
    if (jdk.matches()) {
      return "jdk-matches";
    }
    return "safere-matches";
  }

  private static String inputClass(int codePoint) {
    if (codePoint <= Character.MAX_VALUE && Character.isHighSurrogate((char) codePoint)) {
      return "high-surrogate";
    }
    if (codePoint <= Character.MAX_VALUE && Character.isLowSurrogate((char) codePoint)) {
      return "low-surrogate";
    }
    int type = Character.getType(codePoint);
    return switch (type) {
      case Character.UPPERCASE_LETTER -> "uppercase-letter";
      case Character.LOWERCASE_LETTER -> "lowercase-letter";
      case Character.TITLECASE_LETTER -> "titlecase-letter";
      case Character.MODIFIER_LETTER -> "modifier-letter";
      case Character.OTHER_LETTER -> "other-letter";
      default -> "type-" + type;
    };
  }

  private static boolean semanticallyEqual(Outcome left, Outcome right) {
    if (left.accepted() != right.accepted()) {
      return false;
    }
    return !left.accepted() || left.matches() == right.matches();
  }

  private static PatternSpec pattern(String label, String regex) {
    return new PatternSpec(label, regex, 1);
  }

  private static PatternSpec pattern(String label, String regex, int inputRepeat) {
    return new PatternSpec(label, regex, inputRepeat);
  }

  private record PatternSpec(String label, String regex, int inputRepeat) {}

  private record FlagMode(String label, int flags) {}

  private record CaseSpec(PatternSpec pattern, FlagMode flagMode, int inputCodePoint) {
    String labels() {
      return "pattern="
          + pattern.label()
          + ",flags="
          + flagMode.label()
          + ",input="
          + String.format("U+%04X", inputCodePoint)
          + ",inputClass="
          + inputClass(inputCodePoint);
    }
  }

  private record Outcome(boolean accepted, boolean matches, String error) {}

  private record Divergence(
      CaseSpec spec, String input, Outcome jdk, Outcome safere, String bucket) {
    String toJson() {
      var object = SweepJson.object();
      object.add("case", caseJson(spec));
      object.addProperty("bucket", bucket);
      object.addProperty("labels", spec.labels());
      object.addProperty("regex", spec.pattern().regex());
      object.addProperty("flags", spec.flagMode().label());
      object.addProperty("inputCodePoint", spec.inputCodePoint());
      object.addProperty("input", escape(input));
      object.addProperty("jdkAccepted", jdk.accepted());
      object.addProperty("safeReAccepted", safere.accepted());
      object.addProperty("jdkMatches", jdk.matches());
      object.addProperty("safeReMatches", safere.matches());
      object.addProperty("jdkError", jdk.error());
      object.addProperty("safeReError", safere.error());
      return SweepJson.toJson(object);
    }

    private static com.google.gson.JsonObject caseJson(CaseSpec spec) {
      var object = SweepJson.object();
      object.addProperty("patternLabel", spec.pattern().label());
      object.addProperty("regex", spec.pattern().regex());
      object.addProperty("inputRepeat", spec.pattern().inputRepeat());
      object.addProperty("flagLabel", spec.flagMode().label());
      object.addProperty("flags", spec.flagMode().flags());
      object.addProperty("inputCodePoint", spec.inputCodePoint());
      return object;
    }
  }

  private static final class SweepState {
    final SweepRunState runState;
    final SweepOptions options;
    final SweepWorkers.ProgressReporter progressReporter;
    final int workerIndex;
    long generated;

    SweepState(SweepRunState runState, int workerIndex) {
      this.runState = runState;
      this.options = runState.options;
      this.progressReporter = new SweepWorkers.ProgressReporter(runState, workerIndex);
      this.workerIndex = workerIndex;
    }

    void run() {
      long end = Math.min(options.rangeEndExclusive(), totalCases());
      while (generated < end) {
        long caseIndex = generated++;
        if (caseIndex < options.rangeStartInclusive()) {
          reportProgressIfNeeded();
          continue;
        }
        if (caseIndex % options.threads() != workerIndex) {
          continue;
        }
        progressReporter.checked();
        evaluateCase(caseIndex, caseAt(caseIndex));
      }
    }

    void evaluateCase(long caseIndex, CaseSpec spec) {
      CaseFoldingCharacterClassDivergenceSweep.evaluateCase(runState, spec, workerIndex, caseIndex);
      reportProgressIfNeeded();
    }

    void finish() {
      runState.recordGenerated(generated);
      runState.updateWorkerNextCaseIndex(workerIndex, generated);
    }

    void reportProgressIfNeeded() {
      progressReporter.reportIfNeeded(generated);
    }
  }

  private static void evaluateCase(
      SweepRunState runState, CaseSpec spec, int workerIndex, long caseIndex) {
    String input = inputFor(spec);
    Outcome jdk = jdkOutcome(spec.pattern().regex(), spec.flagMode().flags(), input);
    Outcome safere = safeReOutcome(spec.pattern().regex(), spec.flagMode().flags(), input);
    if (semanticallyEqual(jdk, safere)) {
      return;
    }
    String bucketName = bucketFor(spec, jdk, safere);
    if (workerIndex >= 0) {
      runState.recordCompactDivergence(workerIndex, caseIndex, 0);
    } else {
      runState.recordDivergence();
      runState.appendJsonl(new Divergence(spec, input, jdk, safere, bucketName).toJson());
    }
  }
}
