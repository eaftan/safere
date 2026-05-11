// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.PatternSyntaxException;

/** Offline differential sweep for {@code \X} and {@code \b{g}} grapheme-cluster bugs. */
public final class GraphemeClusterDivergenceSweep {
  private static final int DEFAULT_MAX_PER_BUCKET = Integer.MAX_VALUE;
  private static final int FIND_LIMIT = 32;

  private static final List<RegexTemplate> REGEX_TEMPLATES =
      List.of(
          regex("oneCluster", "\\X"),
          regex("twoClusters", "\\X\\X"),
          regex("anchoredTwoClusters", "^\\X\\X"),
          regex("anchoredOptionalCaretTwoClusters", "^\\^?\\X\\X"),
          regex("exactTwoClusters", "^\\X\\X$"),
          regex("twoClusterRepeat", "\\X{2}"),
          regex("anchoredTwoClusterRepeat", "^\\X{2}"),
          regex("exactTwoClusterRepeat", "^\\X{2}$"),
          regex("capturedCluster", "(\\X)"),
          regex("capturedTwoClusters", "(\\X)(\\X)"),
          regex("anchoredCapturedTwoClusters", "^(\\X)(\\X)"),
          regex("clusterPlus", "\\X+"),
          regex("anchoredClusterPlus", "^\\X+"),
          regex("invalidClusterInClass", "[\\X]"),
          regex("boundary", "\\b{g}"),
          regex("capturedBoundary", "(\\b{g})"),
          regex("optionalBoundary", "\\b{g}?"),
          regex("invalidBoundaryInClass", "[\\b{g}]"),
          regex("boundaryBetweenBaseAndMark", "a\\b{g}\\u0300"),
          regex("boundaryAroundBaseMark", "\\b{g}a\\u0300\\b{g}"),
          regex("boundaryBetweenCrLf", "\\r\\b{g}\\n"),
          regex("clusterThenBoundary", "\\X\\b{g}"),
          regex("anchoredClusterThenBoundary", "^\\X\\b{g}"),
          regex("boundaryClusterBoundary", "\\b{g}\\X\\b{g}"),
          regex("anchoredBoundaryClusterBoundary", "^\\b{g}\\X\\b{g}"));

  private static final List<InputTemplate> INPUT_TEMPLATES =
      List.of(
          input("empty", ""),
          input("ascii", "a"),
          input("twoAscii", "ab"),
          input("baseMark", "a\u0300"),
          input("baseExtend", "e\u0301"),
          input("baseExtendAscii", "e\u0301a"),
          input("leadingExtend", "\u0301"),
          input("twoLeadingExtends", "\u0301\u0301"),
          input("leadingExtendsThenBase", "\u0301\u0301a"),
          input("longLeadingExtendsThenBase", "\u0301".repeat(44) + "a".repeat(8)),
          input("crlf", "\r\n"),
          input("prependBase", "\u0600a"),
          input("hangulJamo", "\u1100\u1161"),
          input("hangulSyllableTail", "\uAC00\u11A8"),
          input("regionalPair", "\uD83C\uDDFA\uD83C\uDDF8"),
          input("regionalTriple", "\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDE8"),
          input("regionalQuad", "\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDE8\uD83C\uDDE6"),
          input("emojiModifier", "\uD83D\uDC4D\uD83C\uDFFD"),
          input("emojiModifierThenAscii", "\uD83D\uDC4D\uD83C\uDFFDa"),
          input("zwjEmoji", "\uD83D\uDC69\u200D\uD83D\uDCBB"),
          input("lowSurrogateZwj", "\uDC69\u200D\uD83D\uDCBB"),
          input("zwjEmojiModifier", "\uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDCBB"),
          input("zwjEmojiThenAscii", "\uD83D\uDC69\u200D\uD83D\uDCBBa"),
          input("hangulLeadingVowel", "\u1161\u11A8"),
          input("hangulLeadingTrail", "\u11A8\u11A8"),
          input("supplementary", "\uD83D\uDE00"),
          input("twoSupplementary", "\uD83D\uDE00\uD83D\uDE01"),
          input("zwjAfterAscii", "a\u200D"));

  private static final List<RegionMode> REGION_MODES =
      List.of(
          new RegionMode("full", "", ""),
          new RegionMode("wrapped", "#", "$"),
          new RegionMode("prefixed", "zz", ""),
          new RegionMode("insideSupplementaryPrefix", "\uD83D", "\uDE00"),
          new RegionMode("afterBaseBeforeMark", "a", "\u0300"));

  private GraphemeClusterDivergenceSweep() {}

  public static void main(String[] args) throws IOException {
    Options options = Options.parse(args);
    Files.createDirectories(options.outputDir());
    Files.deleteIfExists(options.jsonlPath());

    if (options.replayFile() != null) {
      runReplay(options);
      return;
    }

    RunState state = runSweep(options);

    System.out.println("checked=" + state.checked.sum());
    System.out.println("generated=" + state.generated);
    System.out.println("totalCases=" + totalCases());
    System.out.println("divergences=" + state.divergences.sum());
    System.out.println("buckets=" + state.buckets.size());
    System.out.println("threads=" + options.threads());
    System.out.println("jsonl=" + options.jsonlPath());
  }

  private static RunState runSweep(Options options) throws IOException {
    try (RunState runState = new RunState(options)) {
      if (options.threads() == 1) {
        SweepState worker = new SweepState(runState, 0);
        worker.run();
        worker.finish();
        return runState;
      }

      AtomicReference<Throwable> failure = new AtomicReference<>();
      List<Thread> workers = new ArrayList<>();
      for (int i = 0; i < options.threads(); i++) {
        int workerIndex = i;
        Thread worker =
            new Thread(
                () -> {
                  try {
                    SweepState state = new SweepState(runState, workerIndex);
                    state.run();
                    state.finish();
                  } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                  }
                },
                "grapheme-cluster-sweep-" + workerIndex);
        worker.start();
        workers.add(worker);
      }
      for (Thread worker : workers) {
        try {
          worker.join();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("interrupted while waiting for sweep workers", e);
        }
      }
      Throwable throwable = failure.get();
      if (throwable != null) {
        if (throwable instanceof Error error) {
          throw error;
        }
        if (throwable instanceof RuntimeException runtimeException) {
          throw runtimeException;
        }
        throw new IOException("sweep worker failed", throwable);
      }
      return runState;
    }
  }

  private static void runReplay(Options options) throws IOException {
    long generated = 0;
    long checked = 0;
    long divergences = 0;
    try (BufferedReader reader =
        Files.newBufferedReader(options.replayFile(), StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        generated++;
        checked++;
        CaseSpec spec = replayCase(trimmed);
        Outcome jdk = jdkOutcome(spec);
        Outcome safere = safeReOutcome(spec);
        if (semanticallyEqual(jdk, safere)) {
          continue;
        }
        divergences++;
        Files.writeString(
            options.jsonlPath(),
            new Divergence(spec, jdk, safere, bucketFor(spec, jdk, safere)).toJson() + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      }
    }
    System.out.println("checked=" + checked);
    System.out.println("generated=" + generated);
    System.out.println("divergences=" + divergences);
    System.out.println("buckets=" + divergences);
    System.out.println("threads=1");
    System.out.println("jsonl=" + options.jsonlPath());
    if (divergences > 0) {
      throw new IllegalStateException("replay found " + divergences + " behavioral divergences");
    }
  }

  private static long totalCases() {
    return (long) REGEX_TEMPLATES.size() * INPUT_TEMPLATES.size() * REGION_MODES.size();
  }

  private static CaseSpec caseAt(long index) {
    int regionIndex = (int) (index % REGION_MODES.size());
    index /= REGION_MODES.size();
    int inputIndex = (int) (index % INPUT_TEMPLATES.size());
    index /= INPUT_TEMPLATES.size();
    int regexIndex = (int) index;
    return new CaseSpec(
        REGEX_TEMPLATES.get(regexIndex),
        INPUT_TEMPLATES.get(inputIndex),
        REGION_MODES.get(regionIndex));
  }

  private static Outcome jdkOutcome(CaseSpec spec) {
    try {
      java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(spec.regex());
      return new Outcome(true, operationTrace(pattern, spec), "");
    } catch (PatternSyntaxException e) {
      return new Outcome(false, "", e.getDescription());
    } catch (RuntimeException e) {
      return new Outcome(false, "", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static Outcome safeReOutcome(CaseSpec spec) {
    try {
      org.safere.Pattern pattern = org.safere.Pattern.compile(spec.regex());
      return new Outcome(true, operationTrace(pattern, spec), "");
    } catch (PatternSyntaxException e) {
      return new Outcome(false, "", e.getDescription());
    } catch (RuntimeException e) {
      return new Outcome(false, "", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static String operationTrace(java.util.regex.Pattern pattern, CaseSpec spec) {
    StringBuilder result = new StringBuilder();
    String text = spec.text();
    int start = spec.regionStart();
    int end = spec.regionEnd();
    java.util.regex.Matcher matcher = pattern.matcher(text).region(start, end);
    result.append("matches=").append(matchResult(matcher.matches(), matcher));
    matcher.reset(text).region(start, end);
    result.append(";lookingAt=").append(matchResult(matcher.lookingAt(), matcher));
    matcher.reset(text).region(start, end);
    result.append(";find=");
    appendFindTrace(result, matcher);
    return result.toString();
  }

  private static String operationTrace(org.safere.Pattern pattern, CaseSpec spec) {
    StringBuilder result = new StringBuilder();
    String text = spec.text();
    int start = spec.regionStart();
    int end = spec.regionEnd();
    org.safere.Matcher matcher = pattern.matcher(text).region(start, end);
    result.append("matches=").append(matchResult(matcher.matches(), matcher));
    matcher.reset(text).region(start, end);
    result.append(";lookingAt=").append(matchResult(matcher.lookingAt(), matcher));
    matcher.reset(text).region(start, end);
    result.append(";find=");
    appendFindTrace(result, matcher);
    return result.toString();
  }

  private static String matchResult(boolean matched, java.util.regex.Matcher matcher) {
    if (!matched) {
      return "false";
    }
    return "true@" + matchSpan(matcher);
  }

  private static String matchResult(boolean matched, org.safere.Matcher matcher) {
    if (!matched) {
      return "false";
    }
    return "true@" + matchSpan(matcher);
  }

  private static void appendFindTrace(StringBuilder result, java.util.regex.Matcher matcher) {
    int count = 0;
    while (matcher.find()) {
      if (count > 0) {
        result.append('|');
      }
      result.append(matchSpan(matcher));
      if (++count >= FIND_LIMIT) {
        result.append("|...");
        return;
      }
    }
    if (count == 0) {
      result.append("false");
    }
  }

  private static void appendFindTrace(StringBuilder result, org.safere.Matcher matcher) {
    int count = 0;
    while (matcher.find()) {
      if (count > 0) {
        result.append('|');
      }
      result.append(matchSpan(matcher));
      if (++count >= FIND_LIMIT) {
        result.append("|...");
        return;
      }
    }
    if (count == 0) {
      result.append("false");
    }
  }

  private static String matchSpan(java.util.regex.Matcher matcher) {
    return matchSpan(matcher.start(), matcher.end(), matcher.groupCount(), matcher::group);
  }

  private static String matchSpan(org.safere.Matcher matcher) {
    return matchSpan(matcher.start(), matcher.end(), matcher.groupCount(), matcher::group);
  }

  private static String matchSpan(int start, int end, int groupCount, GroupValue groupValue) {
    StringBuilder result = new StringBuilder();
    result.append(start).append('-').append(end).append(':').append(escape(groupValue.group(0)));
    for (int i = 1; i <= groupCount; i++) {
      result.append(':');
      String group = groupValue.group(i);
      result.append(group == null ? "<null>" : escape(group));
    }
    return result.toString();
  }

  private static CaseSpec replayCase(String line) {
    String regex = jsonField(line, "regex");
    String input = jsonField(line, "input");
    String prefix = jsonField(line, "prefix");
    String suffix = jsonField(line, "suffix");
    if (regex == null || input == null || prefix == null || suffix == null) {
      throw new IllegalArgumentException("replay line must contain regex, input, prefix, suffix");
    }
    return new CaseSpec(
        new RegexTemplate("replay", regex),
        new InputTemplate("replay", input),
        new RegionMode("replay", prefix, suffix));
  }

  private static String bucketFor(CaseSpec spec, Outcome jdk, Outcome safere) {
    String kind = jdk.accepted() != safere.accepted() ? "compile" : "behavior";
    return String.join(
        "|",
        "kind=" + kind,
        "direction=" + direction(jdk, safere),
        "regex=" + spec.regexTemplate().label(),
        "input=" + spec.inputTemplate().label(),
        "region=" + spec.regionMode().label());
  }

  private static String direction(Outcome jdk, Outcome safere) {
    if (jdk.accepted() && !safere.accepted()) {
      return "jdk-accepts";
    }
    if (!jdk.accepted() && safere.accepted()) {
      return "safere-accepts";
    }
    return "trace";
  }

  private static boolean semanticallyEqual(Outcome left, Outcome right) {
    if (left.accepted() != right.accepted()) {
      return false;
    }
    return !left.accepted() || left.trace().equals(right.trace());
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
          if (Character.isISOControl(c) || Character.isSurrogate(c)) {
            result.append(String.format("\\u%04X", (int) c));
          } else {
            result.append(c);
          }
        }
      }
    }
    return result.toString();
  }

  private static String json(String value) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> result.append("\\\\");
        case '"' -> result.append("\\\"");
        case '\n' -> result.append("\\n");
        case '\t' -> result.append("\\t");
        case '\r' -> result.append("\\r");
        case '\b' -> result.append("\\b");
        case '\f' -> result.append("\\f");
        default -> {
          if (c < 0x20 || Character.isSurrogate(c)) {
            result.append(String.format("\\u%04X", (int) c));
          } else {
            result.append(c);
          }
        }
      }
    }
    return result.toString();
  }

  private static String jsonField(String line, String field) {
    String prefix = "\"" + field + "\":\"";
    int start = line.indexOf(prefix);
    if (start < 0) {
      return null;
    }
    start += prefix.length();
    StringBuilder value = new StringBuilder();
    boolean escaped = false;
    for (int i = start; i < line.length(); i++) {
      char c = line.charAt(i);
      if (escaped) {
        value.append('\\').append(c);
        escaped = false;
      } else if (c == '\\') {
        escaped = true;
      } else if (c == '"') {
        return unjson(value.toString());
      } else {
        value.append(c);
      }
    }
    throw new IllegalArgumentException("unterminated JSON field in line: " + line);
  }

  private static String unjson(String value) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c != '\\') {
        result.append(c);
        continue;
      }
      if (++i >= value.length()) {
        throw new IllegalArgumentException("trailing JSON escape in: " + value);
      }
      char escaped = value.charAt(i);
      switch (escaped) {
        case 'n' -> result.append('\n');
        case 't' -> result.append('\t');
        case 'r' -> result.append('\r');
        case 'b' -> result.append('\b');
        case 'f' -> result.append('\f');
        case '"', '\\' -> result.append(escaped);
        case 'u' -> {
          if (i + 4 >= value.length()) {
            throw new IllegalArgumentException("short JSON unicode escape in: " + value);
          }
          result.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
          i += 4;
        }
        default -> result.append(escaped);
      }
    }
    return result.toString();
  }

  private static RegexTemplate regex(String label, String regex) {
    return new RegexTemplate(label, regex);
  }

  private static InputTemplate input(String label, String input) {
    return new InputTemplate(label, input);
  }

  private interface GroupValue {
    String group(int group);
  }

  private record RegexTemplate(String label, String regex) {}

  private record InputTemplate(String label, String input) {}

  private record RegionMode(String label, String prefix, String suffix) {}

  private record CaseSpec(
      RegexTemplate regexTemplate, InputTemplate inputTemplate, RegionMode regionMode) {
    String regex() {
      return regexTemplate.regex();
    }

    String input() {
      return inputTemplate.input();
    }

    String text() {
      return regionMode.prefix() + input() + regionMode.suffix();
    }

    int regionStart() {
      return regionMode.prefix().length();
    }

    int regionEnd() {
      return regionStart() + input().length();
    }

    String labels() {
      return "regex="
          + regexTemplate.label()
          + ",input="
          + inputTemplate.label()
          + ",region="
          + regionMode.label();
    }
  }

  private record Outcome(boolean accepted, String trace, String error) {}

  private record Divergence(CaseSpec spec, Outcome jdk, Outcome safere, String bucket) {
    String toJson() {
      return "{"
          + "\"bucket\":\""
          + json(bucket)
          + "\","
          + "\"labels\":\""
          + json(spec.labels())
          + "\","
          + "\"regex\":\""
          + json(spec.regex())
          + "\","
          + "\"input\":\""
          + json(spec.input())
          + "\","
          + "\"prefix\":\""
          + json(spec.regionMode().prefix())
          + "\","
          + "\"suffix\":\""
          + json(spec.regionMode().suffix())
          + "\","
          + "\"regionStart\":"
          + spec.regionStart()
          + ","
          + "\"regionEnd\":"
          + spec.regionEnd()
          + ","
          + "\"jdkAccepted\":"
          + jdk.accepted()
          + ","
          + "\"safeReAccepted\":"
          + safere.accepted()
          + ","
          + "\"jdkTrace\":\""
          + json(jdk.trace())
          + "\","
          + "\"safeReTrace\":\""
          + json(safere.trace())
          + "\","
          + "\"jdkError\":\""
          + json(jdk.error())
          + "\","
          + "\"safeReError\":\""
          + json(safere.error())
          + "\""
          + "}";
    }
  }

  private static final class RunState implements AutoCloseable {
    final Options options;
    final Map<String, Bucket> buckets = new LinkedHashMap<>();
    final LongAdder checked = new LongAdder();
    final LongAdder divergences = new LongAdder();
    final BufferedWriter jsonlWriter;
    long generated;
    long nextProgressReport;

    RunState(Options options) throws IOException {
      this.options = options;
      this.jsonlWriter =
          Files.newBufferedWriter(
              options.jsonlPath(),
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND);
      this.nextProgressReport =
          firstProgressAt(options.rangeStartInclusive(), options.progressInterval());
    }

    synchronized void recordGenerated(long workerGenerated) {
      if (workerGenerated > generated) {
        generated = workerGenerated;
      }
    }

    synchronized void reportProgressIfNeeded(long workerGenerated) {
      recordGenerated(workerGenerated);
      if (generated < nextProgressReport) {
        return;
      }
      System.out.printf(
          "progress generated=%,d checked=%,d divergences=%,d buckets=%,d jsonl=%s%n",
          generated, checked.sum(), divergences.sum(), buckets.size(), options.jsonlPath());
      while (nextProgressReport <= generated) {
        nextProgressReport += options.progressInterval();
      }
    }

    boolean reserveDivergenceExample(String bucketName) {
      synchronized (this) {
        divergences.increment();
        Bucket bucket = buckets.computeIfAbsent(bucketName, Bucket::new);
        if (bucket.savedExamples >= options.maxPerBucket()) {
          return false;
        }
        bucket.savedExamples++;
        return true;
      }
    }

    synchronized void appendJsonl(Divergence divergence) {
      try {
        jsonlWriter.write(divergence.toJson());
        jsonlWriter.newLine();
      } catch (IOException e) {
        throw new IllegalStateException("failed to write divergence report", e);
      }
    }

    @Override
    public synchronized void close() throws IOException {
      jsonlWriter.close();
    }
  }

  private static final class SweepState {
    final RunState runState;
    final Options options;
    final int workerIndex;
    long generated;
    long nextProgressReport;

    SweepState(RunState runState, int workerIndex) {
      this.runState = runState;
      this.options = runState.options;
      this.workerIndex = workerIndex;
      this.nextProgressReport =
          firstProgressAt(options.rangeStartInclusive(), options.progressInterval());
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
          reportProgressIfNeeded();
          continue;
        }
        runState.checked.increment();
        checkOwned(caseAt(caseIndex));
      }
    }

    void checkOwned(CaseSpec spec) {
      Outcome jdk = jdkOutcome(spec);
      Outcome safere = safeReOutcome(spec);
      if (semanticallyEqual(jdk, safere)) {
        reportProgressIfNeeded();
        return;
      }
      String bucketName = bucketFor(spec, jdk, safere);
      if (!runState.reserveDivergenceExample(bucketName)) {
        reportProgressIfNeeded();
        return;
      }
      runState.appendJsonl(new Divergence(spec, jdk, safere, bucketName));
      reportProgressIfNeeded();
    }

    void finish() {
      runState.recordGenerated(generated);
    }

    void reportProgressIfNeeded() {
      if (generated < nextProgressReport) {
        return;
      }
      runState.reportProgressIfNeeded(generated);
      while (nextProgressReport <= generated) {
        nextProgressReport += options.progressInterval();
      }
    }
  }

  private static final class Bucket {
    final String name;
    int savedExamples;

    Bucket(String name) {
      this.name = name;
    }
  }

  private static long firstProgressAt(long rangeStartInclusive, long progressInterval) {
    if (rangeStartInclusive <= 0) {
      return progressInterval;
    }
    long remainder = rangeStartInclusive % progressInterval;
    if (remainder == 0) {
      return rangeStartInclusive;
    }
    return rangeStartInclusive + (progressInterval - remainder);
  }

  private record Options(
      long rangeStartInclusive,
      long rangeEndExclusive,
      int maxPerBucket,
      Path outputDir,
      long progressInterval,
      int threads,
      Path replayFile) {
    Path jsonlPath() {
      return outputDir.resolve("grapheme-cluster-divergences.jsonl");
    }

    static Options parse(String[] args) {
      long rangeStartInclusive = 0;
      long rangeEndExclusive = Long.MAX_VALUE;
      int maxPerBucket = DEFAULT_MAX_PER_BUCKET;
      Path outputDir = Path.of("target", "exhaustive-reports", "grapheme-cluster-sweep");
      long progressInterval = 1_000;
      int threads = 1;
      Path replayFile = null;
      for (String arg : args) {
        if (arg.startsWith("--range=")) {
          String value = arg.substring("--range=".length());
          int colon = value.indexOf(':');
          if (colon < 0) {
            throw new IllegalArgumentException("--range must use start:end syntax");
          }
          String start = value.substring(0, colon);
          String end = value.substring(colon + 1);
          rangeStartInclusive = start.isEmpty() ? 0 : Long.parseLong(start);
          rangeEndExclusive = end.isEmpty() ? Long.MAX_VALUE : Long.parseLong(end);
        } else if (arg.startsWith("--max-per-bucket=")) {
          String value = arg.substring("--max-per-bucket=".length());
          maxPerBucket = value.equals("uncapped") ? Integer.MAX_VALUE : Integer.parseInt(value);
        } else if (arg.startsWith("--output-dir=")) {
          outputDir = Path.of(arg.substring("--output-dir=".length()));
        } else if (arg.startsWith("--progress-interval=")) {
          progressInterval = Long.parseLong(arg.substring("--progress-interval=".length()));
        } else if (arg.startsWith("--threads=")) {
          threads = Integer.parseInt(arg.substring("--threads=".length()));
        } else if (arg.startsWith("--replay-file=")) {
          replayFile = Path.of(arg.substring("--replay-file=".length()));
        } else {
          throw new IllegalArgumentException("unknown argument: " + arg);
        }
      }
      if (rangeStartInclusive < 0 || rangeEndExclusive < 0) {
        throw new IllegalArgumentException("--range bounds must be non-negative");
      }
      if (rangeEndExclusive < rangeStartInclusive) {
        throw new IllegalArgumentException("--range end must be greater than or equal to start");
      }
      if (maxPerBucket < 0) {
        throw new IllegalArgumentException("--max-per-bucket must be non-negative");
      }
      if (progressInterval < 1) {
        throw new IllegalArgumentException("--progress-interval must be at least 1");
      }
      if (threads < 1) {
        throw new IllegalArgumentException("--threads must be at least 1");
      }
      if (replayFile != null && !Files.isRegularFile(replayFile)) {
        throw new IllegalArgumentException("--replay-file must be a regular file");
      }
      return new Options(
          rangeStartInclusive,
          rangeEndExclusive,
          maxPerBucket,
          outputDir,
          progressInterval,
          threads,
          replayFile);
    }
  }
}
