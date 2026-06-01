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

/** Offline differential sweep for Unicode predefined and POSIX character classes. */
public final class UnicodeCharacterClassDivergenceSweep {
  private static final int FLAGS = java.util.regex.Pattern.UNICODE_CHARACTER_CLASS;
  private static final int SCALAR_COUNT =
      Character.MAX_CODE_POINT + 1 - (Character.MAX_SURROGATE - Character.MIN_SURROGATE + 1);
  private static final List<RegexCase> CASES =
      List.of(
          regexCase("digit", "\\d"),
          regexCase("nonDigit", "\\D"),
          regexCase("space", "\\s"),
          regexCase("nonSpace", "\\S"),
          regexCase("word", "\\w"),
          regexCase("nonWord", "\\W"),
          regexCase("posixLower", "\\p{Lower}"),
          regexCase("posixUpper", "\\p{Upper}"),
          regexCase("posixAscii", "\\p{ASCII}"),
          regexCase("posixAlpha", "\\p{Alpha}"),
          regexCase("posixDigit", "\\p{Digit}"),
          regexCase("posixAlnum", "\\p{Alnum}"),
          regexCase("posixPunct", "\\p{Punct}"),
          regexCase("posixGraph", "\\p{Graph}"),
          regexCase("posixPrint", "\\p{Print}"),
          regexCase("posixBlank", "\\p{Blank}"),
          regexCase("posixCntrl", "\\p{Cntrl}"),
          regexCase("posixXDigit", "\\p{XDigit}"),
          regexCase("posixSpace", "\\p{Space}"),
          regexCase("wordIntersectionWithoutUnderscore", "[\\w&&[^_]]"));

  private UnicodeCharacterClassDivergenceSweep() {}

  public static void main(String[] args) throws IOException {
    SweepOptions options =
        SweepOptions.parse(
            args,
            Path.of("target", "exhaustive-reports", "unicode-character-class-sweep"),
            "unicode-character-class-divergences.jsonl",
            1_000_000);
    Files.createDirectories(options.outputDir());
    if (options.replayFile() != null) {
      Files.deleteIfExists(options.jsonlPath());
    }
    options.printStartup("unicode-character-class");

    if (options.replayFile() != null) {
      runReplay(options);
      return;
    }

    SweepRunState state = runSweep(options);

    System.out.println("checked=" + state.checked.sum());
    System.out.println("generated=" + state.generated);
    System.out.println("divergences=" + state.divergences.sum());
    System.out.println("threads=" + options.threads());
  }

  private static SweepRunState runSweep(SweepOptions options) throws IOException {
    try (SweepRunState runState = new SweepRunState(options, options.totalChecks(totalCases()))) {
      runState.enableCompactLogs(
          "unicode-character-class",
          totalCases(),
          List.of("UNKNOWN"),
          List.of(DivergenceStatus.UNKNOWN));
      SweepWorkers.run(
          options.threads(),
          "unicode-character-class-sweep-",
          workerIndex -> {
            SweepWorkers.ProgressReporter progressReporter =
                new SweepWorkers.ProgressReporter(runState, workerIndex);
            long generated = 0;
            long end = Math.min(options.rangeEndExclusive(), totalCases());
            for (long caseIndex = options.rangeStartInclusive(); caseIndex < end; caseIndex++) {
              generated = caseIndex + 1;
              if (caseIndex % options.threads() != workerIndex) {
                continue;
              }
              progressReporter.checked();
              evaluateCase(runState, caseFromIndex(caseIndex), workerIndex, caseIndex);
              progressReporter.reportIfNeeded(generated);
            }
            runState.recordGenerated(generated);
            runState.updateWorkerNextCaseIndex(workerIndex, generated);
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
              "unicode-character-class-replay-",
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
        regexCase(SweepJson.string(caseObject, "label"), SweepJson.string(caseObject, "regex")),
        SweepJson.integer(caseObject, "codePoint"));
  }

  static long totalCases() {
    return (long) CASES.size() * SCALAR_COUNT;
  }

  static String compactReplayJson(long caseIndex, String classification) {
    CaseSpec spec = caseFromIndex(caseIndex);
    var object = SweepJson.object();
    object.addProperty("caseIndex", caseIndex);
    object.addProperty("classification", classification);
    object.add("case", Divergence.caseJson(spec));
    return SweepJson.toJson(object);
  }

  private static CaseSpec caseFromIndex(long index) {
    RegexCase regexCase = CASES.get((int) (index / SCALAR_COUNT));
    int codePoint = scalarCodePoint((int) (index % SCALAR_COUNT));
    return new CaseSpec(regexCase, codePoint);
  }

  private static int scalarCodePoint(int ordinal) {
    if (ordinal < Character.MIN_SURROGATE) {
      return ordinal;
    }
    return ordinal + (Character.MAX_SURROGATE - Character.MIN_SURROGATE + 1);
  }

  private static void evaluateCase(
      SweepRunState runState, CaseSpec spec, int workerIndex, long caseIndex) {
    String input = new String(Character.toChars(spec.codePoint()));
    boolean jdk = spec.regexCase().jdkPattern().matcher(input).matches();
    boolean safere = spec.regexCase().safeRePattern().matcher(input).matches();
    if (jdk == safere) {
      return;
    }
    if (workerIndex >= 0) {
      runState.recordCompactDivergence(workerIndex, caseIndex, 0);
    } else {
      runState.recordDivergence();
      runState.appendJsonl(new Divergence(spec, jdk, safere).toJson());
    }
  }

  private static RegexCase regexCase(String label, String regex) {
    return new RegexCase(
        label,
        regex,
        java.util.regex.Pattern.compile(regex, FLAGS),
        org.safere.Pattern.compile(regex, FLAGS));
  }

  private record RegexCase(
      String label,
      String regex,
      java.util.regex.Pattern jdkPattern,
      org.safere.Pattern safeRePattern) {}

  private record CaseSpec(RegexCase regexCase, int codePoint) {}

  private record Divergence(CaseSpec spec, boolean jdkMatches, boolean safeReMatches) {
    String toJson() {
      var object = SweepJson.object();
      object.add("case", caseJson(spec));
      object.addProperty("bucket", "regex=" + spec.regexCase().label());
      object.addProperty("regex", spec.regexCase().regex());
      object.addProperty("input", new String(Character.toChars(spec.codePoint())));
      object.addProperty("codePoint", String.format("U+%04X", spec.codePoint()));
      object.addProperty("characterType", Character.getType(spec.codePoint()));
      object.addProperty("alphabetic", Character.isAlphabetic(spec.codePoint()));
      object.addProperty("jdkMatches", jdkMatches);
      object.addProperty("safeReMatches", safeReMatches);
      return SweepJson.toJson(object);
    }

    private static com.google.gson.JsonObject caseJson(CaseSpec spec) {
      var object = SweepJson.object();
      object.addProperty("label", spec.regexCase().label());
      object.addProperty("regex", spec.regexCase().regex());
      object.addProperty("codePoint", spec.codePoint());
      return object;
    }
  }
}
