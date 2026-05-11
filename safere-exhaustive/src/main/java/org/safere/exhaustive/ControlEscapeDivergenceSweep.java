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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.PatternSyntaxException;

/** Offline differential sweep for {@code \cX} control-escape target bugs. */
public final class ControlEscapeDivergenceSweep {
  private static final int TARGET_COUNT = Character.MAX_CODE_POINT + 1;
  private static final int DEFAULT_MAX_PER_BUCKET = Integer.MAX_VALUE;

  private static final List<Context> CONTEXTS =
      List.of(
          context("bare", "\\c%s"),
          context("anchored", "^\\c%s$"),
          context("class", "[\\c%s]"),
          context("negatedClass", "[^\\c%s]"),
          context("prefixLiteral", "a\\c%s"),
          context("suffixLiteral", "\\c%sa"),
          context("captured", "(\\c%s)"),
          context("optional", "\\c%s?"));

  private static final List<FlagMode> FLAG_MODES =
      List.of(
          new FlagMode("none", "", 0),
          new FlagMode("comments", "(?x)", java.util.regex.Pattern.COMMENTS),
          new FlagMode("caseInsensitive", "(?i)", java.util.regex.Pattern.CASE_INSENSITIVE),
          new FlagMode(
              "commentsCaseInsensitive",
              "(?xi)",
              java.util.regex.Pattern.COMMENTS | java.util.regex.Pattern.CASE_INSENSITIVE));

  private ControlEscapeDivergenceSweep() {}

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
                "control-escape-sweep-" + workerIndex);
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
        String regex = jsonField(trimmed, "regex");
        regex = regex == null ? unjson(trimmed) : regex;
        Outcome jdk = jdkOutcome(regex);
        Outcome safere = safeReOutcome(regex);
        if (semanticallyEqual(jdk, safere)) {
          continue;
        }
        divergences++;
        String replayLine =
            "{"
                + "\"regex\":\""
                + json(regex)
                + "\","
                + "\"jdkAccepted\":"
                + jdk.accepted()
                + ","
                + "\"safeReAccepted\":"
                + safere.accepted()
                + ","
                + "\"jdkMatches\":\""
                + json(jdk.matches())
                + "\","
                + "\"safeReMatches\":\""
                + json(safere.matches())
                + "\","
                + "\"jdkError\":\""
                + json(jdk.error())
                + "\","
                + "\"safeReError\":\""
                + json(safere.error())
                + "\""
                + "}";
        Files.writeString(
            options.jsonlPath(),
            replayLine + "\n",
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
    return (long) TARGET_COUNT * CONTEXTS.size() * FLAG_MODES.size();
  }

  private static CaseSpec caseAt(long index) {
    int flagIndex = (int) (index % FLAG_MODES.size());
    index /= FLAG_MODES.size();
    int contextIndex = (int) (index % CONTEXTS.size());
    index /= CONTEXTS.size();
    int target = (int) index;
    return new CaseSpec(target, CONTEXTS.get(contextIndex), FLAG_MODES.get(flagIndex));
  }

  private static Outcome jdkOutcome(String regex, int flags, List<String> inputs) {
    try {
      return new Outcome(true, matches(java.util.regex.Pattern.compile(regex, flags), inputs), "");
    } catch (PatternSyntaxException e) {
      return new Outcome(false, "", e.getDescription());
    } catch (RuntimeException e) {
      return new Outcome(false, "", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static Outcome safeReOutcome(String regex, int flags, List<String> inputs) {
    try {
      return new Outcome(true, matches(org.safere.Pattern.compile(regex, flags), inputs), "");
    } catch (PatternSyntaxException e) {
      return new Outcome(false, "", e.getDescription());
    } catch (RuntimeException e) {
      return new Outcome(false, "", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static Outcome jdkOutcome(String regex) {
    return jdkOutcome(regex, 0, defaultReplayInputs());
  }

  private static Outcome safeReOutcome(String regex) {
    return safeReOutcome(regex, 0, defaultReplayInputs());
  }

  private static String matches(java.util.regex.Pattern pattern, List<String> inputs) {
    StringBuilder result = new StringBuilder();
    for (String input : inputs) {
      if (pattern.matcher(input).matches()) {
        appendInput(result, input);
      }
    }
    return result.toString();
  }

  private static String matches(org.safere.Pattern pattern, List<String> inputs) {
    StringBuilder result = new StringBuilder();
    for (String input : inputs) {
      if (pattern.matcher(input).matches()) {
        appendInput(result, input);
      }
    }
    return result.toString();
  }

  private static List<String> inputsFor(CaseSpec spec) {
    String target = stringForCodePoint(spec.target());
    String transformed = stringForCodePoint(spec.target() ^ 0x40);
    Set<String> inputs = new LinkedHashSet<>();
    inputs.add("");
    inputs.add(transformed);
    inputs.add(target);
    addIfValid(inputs, (spec.target() ^ 0x40) - 1);
    addIfValid(inputs, (spec.target() ^ 0x40) + 1);
    inputs.add("a");
    inputs.add("A");
    inputs.add("!");
    inputs.add("\u0001");
    inputs.add("\u001b");
    inputs.add("\u0140");
    inputs.add("\uf57f");
    inputs.add("\uD83D\uDE40");
    if (spec.context().needsPrefixA()) {
      inputs.add("a" + transformed);
      inputs.add("aa");
      inputs.add("a" + target);
    }
    if (spec.context().needsSuffixA()) {
      inputs.add(transformed + "a");
      inputs.add(target + "a");
    }
    return List.copyOf(inputs);
  }

  private static List<String> defaultReplayInputs() {
    return List.of("", "a", "A", "!", "\u0001", "\u001b", "\u0140", "\uf57f", "\uD83D\uDE40");
  }

  private static void addIfValid(Set<String> inputs, int codePoint) {
    if (Character.isValidCodePoint(codePoint)) {
      inputs.add(stringForCodePoint(codePoint));
    }
  }

  private static String stringForCodePoint(int codePoint) {
    if (codePoint <= Character.MAX_VALUE) {
      return Character.toString((char) codePoint);
    }
    return new String(Character.toChars(codePoint));
  }

  private static void appendInput(StringBuilder result, String input) {
    if (result.length() > 0) {
      result.append(',');
    }
    result.append(escape(input));
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

  private static String bucketFor(CaseSpec spec, Outcome jdk, Outcome safere) {
    String kind = jdk.accepted() != safere.accepted() ? "compile" : "membership";
    return String.join(
        "|",
        "kind=" + kind,
        "direction=" + direction(jdk, safere),
        "context=" + spec.context().label(),
        "flags=" + spec.flagMode().label(),
        "targetClass=" + targetClass(spec.target()));
  }

  private static String direction(Outcome jdk, Outcome safere) {
    if (jdk.accepted() && !safere.accepted()) {
      return "jdk-accepts";
    }
    if (!jdk.accepted() && safere.accepted()) {
      return "safere-accepts";
    }
    return "matches";
  }

  private static String targetClass(int target) {
    if (Character.isHighSurrogate((char) target)) {
      return "high-surrogate";
    }
    if (Character.isLowSurrogate((char) target)) {
      return "low-surrogate";
    }
    if (target < 0x20 || target == 0x7F) {
      return "ascii-control";
    }
    if (target < 0x80) {
      return "ascii-printable";
    }
    if (target <= Character.MAX_VALUE) {
      return "bmp";
    }
    return "supplementary";
  }

  private static boolean semanticallyEqual(Outcome left, Outcome right) {
    if (left.accepted() != right.accepted()) {
      return false;
    }
    return !left.accepted() || left.matches().equals(right.matches());
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

  private static Context context(String label, String template) {
    return new Context(label, template);
  }

  private record Context(String label, String template) {
    String regex(String target) {
      return template.formatted(target);
    }

    boolean needsPrefixA() {
      return label.equals("prefixLiteral");
    }

    boolean needsSuffixA() {
      return label.equals("suffixLiteral");
    }
  }

  private record FlagMode(String label, String prefix, int flags) {}

  private record CaseSpec(int target, Context context, FlagMode flagMode) {
    String regex() {
      return flagMode.prefix() + context.regex(stringForCodePoint(target));
    }

    String labels() {
      return "target="
          + String.format("U+%04X", target)
          + ",context="
          + context.label()
          + ",flags="
          + flagMode.label()
          + ",targetClass="
          + targetClass(target);
    }
  }

  private record Outcome(boolean accepted, String matches, String error) {}

  private record Divergence(
      CaseSpec spec, String regex, Outcome jdk, Outcome safere, String bucket) {
    String toJson() {
      return "{"
          + "\"bucket\":\""
          + json(bucket)
          + "\","
          + "\"labels\":\""
          + json(spec.labels())
          + "\","
          + "\"regex\":\""
          + json(regex)
          + "\","
          + "\"target\":"
          + spec.target()
          + ","
          + "\"context\":\""
          + json(spec.context().label())
          + "\","
          + "\"flags\":\""
          + json(spec.flagMode().label())
          + "\","
          + "\"jdkAccepted\":"
          + jdk.accepted()
          + ","
          + "\"safeReAccepted\":"
          + safere.accepted()
          + ","
          + "\"jdkMatches\":\""
          + json(jdk.matches())
          + "\","
          + "\"safeReMatches\":\""
          + json(safere.matches())
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
      String regex = spec.regex();
      List<String> inputs = inputsFor(spec);
      Outcome jdk = jdkOutcome(regex, spec.flagMode().flags(), inputs);
      Outcome safere = safeReOutcome(regex, spec.flagMode().flags(), inputs);
      if (semanticallyEqual(jdk, safere)) {
        reportProgressIfNeeded();
        return;
      }
      String bucketName = bucketFor(spec, jdk, safere);
      if (!runState.reserveDivergenceExample(bucketName)) {
        reportProgressIfNeeded();
        return;
      }
      runState.appendJsonl(new Divergence(spec, regex, jdk, safere, bucketName));
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
      return outputDir.resolve("control-escape-divergences.jsonl");
    }

    static Options parse(String[] args) {
      long rangeStartInclusive = 0;
      long rangeEndExclusive = Long.MAX_VALUE;
      int maxPerBucket = DEFAULT_MAX_PER_BUCKET;
      Path outputDir = Path.of("target", "exhaustive-reports", "control-escape-sweep");
      long progressInterval = 1_000_000;
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
