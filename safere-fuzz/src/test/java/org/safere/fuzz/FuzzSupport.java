// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.PatternSyntaxException;

final class FuzzSupport {
  private static final String JDK_ORACLE_TIMEOUT_PROPERTY =
      "safere.fuzz.jdkOracleTimeoutMillis";
  private static final long DEFAULT_JDK_ORACLE_TIMEOUT_MILLIS = 250;
  private static final ExecutorService JDK_ORACLE_EXECUTOR =
      Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "safere-jdk-regex-oracle");
        thread.setDaemon(true);
        return thread;
      });

  private static final int[] FLAGS = {
      org.safere.Pattern.UNIX_LINES,
      org.safere.Pattern.CASE_INSENSITIVE,
      org.safere.Pattern.COMMENTS,
      org.safere.Pattern.MULTILINE,
      org.safere.Pattern.LITERAL,
      org.safere.Pattern.DOTALL,
      org.safere.Pattern.UNICODE_CASE,
      org.safere.Pattern.UNICODE_CHARACTER_CLASS
  };

  private FuzzSupport() {}

  static CompiledPattern compileCompatibleOrSkip(String regex, int flags) {
    org.safere.Pattern safeRePattern = null;
    java.util.regex.Pattern jdkPattern = null;
    PatternSyntaxException safeReException = null;
    PatternSyntaxException jdkException = null;

    try {
      safeRePattern = org.safere.Pattern.compile(regex, flags);
    } catch (PatternSyntaxException e) {
      safeReException = e;
    }
    try {
      jdkPattern = java.util.regex.Pattern.compile(regex, flags);
    } catch (PatternSyntaxException e) {
      jdkException = e;
    }

    if (safeRePattern != null && jdkPattern != null) {
      return new CompiledPattern(regex, flags, safeRePattern, jdkPattern);
    }
    if (safeReException != null && jdkException != null) {
      return null;
    }
    if (safeReException != null && isIntentionallyUnsupported(regex, safeReException)) {
      return null;
    }

    String safeRe = safeReException == null
        ? "compiled successfully"
        : safeReException.getClass().getSimpleName() + ": " + safeReException.getMessage();
    String jdk = jdkException == null
        ? "compiled successfully"
        : jdkException.getClass().getSimpleName() + ": " + jdkException.getMessage();
    throw new AssertionError("compile divergence"
        + "\nRegex: " + javaStringLiteral(regex)
        + "\nFlags: " + flags
        + "\nSafeRE: " + safeRe + "\nJDK: " + jdk);
  }

  static void assertFullMatchesJdk(String regex, int flags, List<String> inputs) {
    CompiledPattern pattern = compileCompatibleOrSkip(regex, flags);
    if (pattern == null) {
      return;
    }
    for (String input : inputs) {
      pattern.matcher(input).matches();
    }
  }

  static CompiledPattern compileOrSkip(String regex, int flags) {
    return compileCompatibleOrSkip(regex, flags);
  }

  static boolean jdkOracleCompletesForTesting(String regex, String input) {
    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
    return runJdkOracle(
            "matches",
            regex,
            0,
            input,
            () -> pattern.matcher(interruptible(input)).matches())
        .available();
  }

  static int consumeFlags(FuzzedDataProvider data) {
    int flags = 0;
    for (int flag : FLAGS) {
      if (data.consumeBoolean()) {
        flags |= flag;
      }
    }
    return flags;
  }

  static int consumeParserFlags(FuzzedDataProvider data) {
    int flags = 0;
    if (data.consumeBoolean()) {
      flags |= org.safere.Pattern.COMMENTS;
    }
    if (data.consumeBoolean()) {
      flags |= org.safere.Pattern.CASE_INSENSITIVE;
    }
    if (data.consumeBoolean()) {
      flags |= org.safere.Pattern.UNICODE_CASE;
    }
    if (data.consumeBoolean()) {
      flags |= org.safere.Pattern.UNICODE_CHARACTER_CLASS;
    }
    return flags;
  }

  record CompiledPattern(
      String regex,
      int flags,
      org.safere.Pattern safeRePattern,
      java.util.regex.Pattern jdkPattern) {

    MatcherPair matcher(CharSequence input) {
      return new MatcherPair(
          regex,
          flags,
          input.toString(),
          safeRePattern.matcher(input),
          jdkPattern.matcher(interruptible(input)));
    }

    void split(CharSequence input) {
      String inputText = input.toString();
      JdkOracleResult<String[]> jdk =
          runJdkOracle(
              "split", regex, flags, inputText, () -> jdkPattern.split(interruptible(input)));
      if (!jdk.available()) {
        return;
      }
      assertArrayEquals(
          "split",
          inputText,
          safeRePattern.split(input),
          jdk.value());
    }

    void split(CharSequence input, int limit) {
      String inputText = input.toString();
      JdkOracleResult<String[]> jdk =
          runJdkOracle(
              "split(" + limit + ")",
              regex,
              flags,
              inputText,
              () -> jdkPattern.split(interruptible(input), limit));
      if (!jdk.available()) {
        return;
      }
      assertArrayEquals(
          "split(" + limit + ")",
          inputText,
          safeRePattern.split(input, limit),
          jdk.value());
    }

    void splitWithDelimiters(CharSequence input) {
      String inputText = input.toString();
      JdkOracleResult<String[]> jdk =
          runJdkOracle(
              "splitWithDelimiters",
              regex,
              flags,
              inputText,
              () -> jdkPattern.splitWithDelimiters(interruptible(input), 0));
      if (!jdk.available()) {
        return;
      }
      assertArrayEquals(
          "splitWithDelimiters",
          inputText,
          safeRePattern.splitWithDelimiters(input),
          jdk.value());
    }

    void splitWithDelimiters(CharSequence input, int limit) {
      String inputText = input.toString();
      JdkOracleResult<String[]> jdk =
          runJdkOracle(
              "splitWithDelimiters(" + limit + ")",
              regex,
              flags,
              inputText,
              () -> jdkPattern.splitWithDelimiters(interruptible(input), limit));
      if (!jdk.available()) {
        return;
      }
      assertArrayEquals(
          "splitWithDelimiters(" + limit + ")",
          inputText,
          safeRePattern.splitWithDelimiters(input, limit),
          jdk.value());
    }

    private void assertArrayEquals(
        String operation, String input, String[] safeRe, String[] jdk) {
      if (!Arrays.equals(safeRe, jdk)) {
        throw new AssertionError(operation + " divergence"
            + "\nRegex: " + javaStringLiteral(regex)
            + "\nFlags: " + flags
            + "\nInput: " + javaStringLiteral(input)
            + "\nSafeRE: " + describeArray(safeRe)
            + "\nJDK: " + describeArray(jdk));
      }
    }
  }

  static final class MatcherPair {
    private final String regex;
    private final int flags;
    private String input;
    private String lastReplacement;
    private final org.safere.Matcher safeReMatcher;
    private final java.util.regex.Matcher jdkMatcher;
    private boolean jdkOracleAvailable = true;

    MatcherPair(
        String regex,
        int flags,
        String input,
        org.safere.Matcher safeReMatcher,
        java.util.regex.Matcher jdkMatcher) {
      this.regex = regex;
      this.flags = flags;
      this.input = input;
      this.safeReMatcher = safeReMatcher;
      this.jdkMatcher = jdkMatcher;
    }

    boolean matches() {
      boolean safeRe = safeReMatcher.matches();
      boolean jdk = runJdkOracle("matches", safeRe, () -> jdkMatcher.matches());
      assertSame("matches", safeRe, jdk);
      if (safeRe) {
        assertMatchState("matches");
      }
      return safeRe;
    }

    boolean lookingAt() {
      boolean safeRe = safeReMatcher.lookingAt();
      boolean jdk = runJdkOracle("lookingAt", safeRe, () -> jdkMatcher.lookingAt());
      assertSame("lookingAt", safeRe, jdk);
      if (safeRe) {
        assertMatchState("lookingAt");
      }
      return safeRe;
    }

    boolean find() {
      boolean safeRe = safeReMatcher.find();
      boolean jdk = runJdkOracle("find", safeRe, () -> jdkMatcher.find());
      assertSame("find", safeRe, jdk);
      if (safeRe) {
        assertMatchState("find");
      }
      return safeRe;
    }

    boolean find(int start) {
      boolean safeRe = safeReMatcher.find(start);
      boolean jdk = runJdkOracle("find(" + start + ")", safeRe, () -> jdkMatcher.find(start));
      assertSame("find(" + start + ")", safeRe, jdk);
      if (safeRe) {
        assertMatchState("find(" + start + ")");
      }
      return safeRe;
    }

    MatcherPair reset() {
      lastReplacement = null;
      safeReMatcher.reset();
      runJdkOracle("reset", null, () -> jdkMatcher.reset());
      return this;
    }

    MatcherPair reset(CharSequence input) {
      this.input = input.toString();
      this.lastReplacement = null;
      safeReMatcher.reset(input);
      runJdkOracle("reset(input)", null, () -> jdkMatcher.reset(interruptible(input)));
      return this;
    }

    int groupCount() {
      int safeRe = safeReMatcher.groupCount();
      int jdk = runJdkOracle("groupCount", safeRe, () -> jdkMatcher.groupCount());
      assertSame("groupCount", safeRe, jdk);
      return safeRe;
    }

    String group(int group) {
      String safeRe = safeReMatcher.group(group);
      String jdk = runJdkOracle("group(" + group + ")", safeRe, () -> jdkMatcher.group(group));
      assertSame("group(" + group + ")", safeRe, jdk);
      return safeRe;
    }

    String group(String name) {
      String safeRe = safeReMatcher.group(name);
      String jdk = runJdkOracle("group(" + name + ")", safeRe, () -> jdkMatcher.group(name));
      assertSame("group(" + name + ")", safeRe, jdk);
      return safeRe;
    }

    int start(int group) {
      int safeRe = safeReMatcher.start(group);
      int jdk = runJdkOracle("start(" + group + ")", safeRe, () -> jdkMatcher.start(group));
      assertSame("start(" + group + ")", safeRe, jdk);
      return safeRe;
    }

    int start(String name) {
      int safeRe = safeReMatcher.start(name);
      int jdk = runJdkOracle("start(" + name + ")", safeRe, () -> jdkMatcher.start(name));
      assertSame("start(" + name + ")", safeRe, jdk);
      return safeRe;
    }

    int end(int group) {
      int safeRe = safeReMatcher.end(group);
      int jdk = runJdkOracle("end(" + group + ")", safeRe, () -> jdkMatcher.end(group));
      assertSame("end(" + group + ")", safeRe, jdk);
      return safeRe;
    }

    int end(String name) {
      int safeRe = safeReMatcher.end(name);
      int jdk = runJdkOracle("end(" + name + ")", safeRe, () -> jdkMatcher.end(name));
      assertSame("end(" + name + ")", safeRe, jdk);
      return safeRe;
    }

    boolean hitEnd() {
      boolean safeRe = safeReMatcher.hitEnd();
      boolean jdk = runJdkOracle("hitEnd", safeRe, () -> jdkMatcher.hitEnd());
      assertSame("hitEnd", safeRe, jdk);
      return safeRe;
    }

    boolean requireEnd() {
      boolean safeRe = safeReMatcher.requireEnd();
      boolean jdk = runJdkOracle("requireEnd", safeRe, () -> jdkMatcher.requireEnd());
      assertSame("requireEnd", safeRe, jdk);
      return safeRe;
    }

    MatcherPair region(int start, int end) {
      safeReMatcher.region(start, end);
      runJdkOracle("region", null, () -> jdkMatcher.region(start, end));
      return this;
    }

    int regionStart() {
      int safeRe = safeReMatcher.regionStart();
      int jdk = runJdkOracle("regionStart", safeRe, () -> jdkMatcher.regionStart());
      assertSame("regionStart", safeRe, jdk);
      return safeRe;
    }

    int regionEnd() {
      int safeRe = safeReMatcher.regionEnd();
      int jdk = runJdkOracle("regionEnd", safeRe, () -> jdkMatcher.regionEnd());
      assertSame("regionEnd", safeRe, jdk);
      return safeRe;
    }

    MatcherPair useAnchoringBounds(boolean value) {
      safeReMatcher.useAnchoringBounds(value);
      runJdkOracle("useAnchoringBounds", null, () -> jdkMatcher.useAnchoringBounds(value));
      return this;
    }

    MatcherPair useTransparentBounds(boolean value) {
      safeReMatcher.useTransparentBounds(value);
      runJdkOracle("useTransparentBounds", null, () -> jdkMatcher.useTransparentBounds(value));
      return this;
    }

    boolean hasAnchoringBounds() {
      boolean safeRe = safeReMatcher.hasAnchoringBounds();
      boolean jdk =
          runJdkOracle("hasAnchoringBounds", safeRe, () -> jdkMatcher.hasAnchoringBounds());
      assertSame("hasAnchoringBounds", safeRe, jdk);
      return safeRe;
    }

    boolean hasTransparentBounds() {
      boolean safeRe = safeReMatcher.hasTransparentBounds();
      boolean jdk =
          runJdkOracle("hasTransparentBounds", safeRe, () -> jdkMatcher.hasTransparentBounds());
      assertSame("hasTransparentBounds", safeRe, jdk);
      return safeRe;
    }

    boolean replaceAll(String replacement) {
      return assertSameReplacementOutcome(
          "replaceAll",
          replacement,
          () -> safeReMatcher.replaceAll(replacement),
          () -> runJdkOracle("replaceAll", null, () -> jdkMatcher.replaceAll(replacement)));
    }

    boolean replaceAll(Function<MatchResult, String> replacer) {
      return assertSameReplacementOutcome(
          "replaceAll(function)",
          null,
          () -> safeReMatcher.replaceAll(replacer),
          () -> runJdkOracle("replaceAll(function)", null, () -> jdkMatcher.replaceAll(replacer)));
    }

    boolean replaceFirst(String replacement) {
      return assertSameReplacementOutcome(
          "replaceFirst",
          replacement,
          () -> safeReMatcher.replaceFirst(replacement),
          () -> runJdkOracle("replaceFirst", null, () -> jdkMatcher.replaceFirst(replacement)));
    }

    boolean appendReplacement(StringBuilder output, String replacement) {
      StringBuilder safeRe = new StringBuilder();
      StringBuilder jdk = new StringBuilder();
      lastReplacement = replacement;
      boolean completed = assertSameReplacementOutcome(
          "appendReplacement",
          replacement,
          () -> {
            safeReMatcher.appendReplacement(safeRe, replacement);
            return safeRe.toString();
          },
          () -> {
            runJdkOracle(
                "appendReplacement", null, () -> jdkMatcher.appendReplacement(jdk, replacement));
            return jdk.toString();
          });
      if (completed) {
        output.append(safeRe);
      }
      return completed;
    }

    boolean appendReplacement(StringBuffer output, String replacement) {
      StringBuffer safeRe = new StringBuffer();
      StringBuffer jdk = new StringBuffer();
      lastReplacement = replacement;
      boolean completed = assertSameReplacementOutcome(
          "appendReplacement",
          replacement,
          () -> {
            safeReMatcher.appendReplacement(safeRe, replacement);
            return safeRe.toString();
          },
          () -> {
            runJdkOracle(
                "appendReplacement", null, () -> jdkMatcher.appendReplacement(jdk, replacement));
            return jdk.toString();
          });
      if (completed) {
        output.append(safeRe);
      }
      return completed;
    }

    void appendTail(StringBuilder output) {
      StringBuilder safeRe = new StringBuilder();
      StringBuilder jdk = new StringBuilder();
      safeReMatcher.appendTail(safeRe);
      runJdkOracle("appendTail", null, () -> jdkMatcher.appendTail(jdk));
      if (!jdkOracleAvailable) {
        output.append(safeRe);
        return;
      }
      assertSame("appendTail", safeRe.toString(), jdk.toString());
      output.append(safeRe);
    }

    void appendTail(StringBuffer output) {
      StringBuffer safeRe = new StringBuffer();
      StringBuffer jdk = new StringBuffer();
      safeReMatcher.appendTail(safeRe);
      runJdkOracle("appendTail", null, () -> jdkMatcher.appendTail(jdk));
      if (!jdkOracleAvailable) {
        output.append(safeRe);
        return;
      }
      assertSame("appendTail", safeRe.toString(), jdk.toString());
      output.append(safeRe);
    }

    void toMatchResult() {
      MatchResult safeRe = safeReMatcher.toMatchResult();
      MatchResult jdk = runJdkOracle("toMatchResult", safeRe, () -> jdkMatcher.toMatchResult());
      assertMatchResult("toMatchResult", safeRe, jdk);
    }

    private void assertMatchState(String operation) {
      assertSame(operation + ".start", safeReMatcher.start(),
          runJdkOracle(operation + ".start", safeReMatcher.start(), () -> jdkMatcher.start()));
      assertSame(operation + ".end", safeReMatcher.end(),
          runJdkOracle(operation + ".end", safeReMatcher.end(), () -> jdkMatcher.end()));
      int groupCount = groupCount();
      for (int i = 0; i <= groupCount; i++) {
        group(i);
        start(i);
        end(i);
      }
    }

    private void assertMatchResult(String operation, MatchResult safeRe, MatchResult jdk) {
      assertSame(operation + ".start", safeRe.start(), jdk.start());
      assertSame(operation + ".end", safeRe.end(), jdk.end());
      int groupCount = safeRe.groupCount();
      assertSame(operation + ".groupCount", groupCount, jdk.groupCount());
      for (int i = 0; i <= groupCount; i++) {
        assertSame(operation + ".group(" + i + ")", safeRe.group(i), jdk.group(i));
        assertSame(operation + ".start(" + i + ")", safeRe.start(i), jdk.start(i));
        assertSame(operation + ".end(" + i + ")", safeRe.end(i), jdk.end(i));
      }
    }

    private boolean assertSameReplacementOutcome(
        String operation,
        String replacement,
        StringOperation safeReOperation,
        StringOperation jdkOperation) {
      OperationResult<String> safeRe = OperationResult.capture(safeReOperation);
      if (!jdkOracleAvailable) {
        return false;
      }
      OperationResult<String> jdk = OperationResult.capture(jdkOperation);
      if (!jdkOracleAvailable) {
        return false;
      }
      if (safeRe.throwable() == null && jdk.throwable() == null) {
        assertSame(operation, replacement, safeRe.value(), jdk.value());
        return true;
      }
      if (safeRe.throwable() != null
          && jdk.throwable() != null
          && safeRe.throwable().getClass().equals(jdk.throwable().getClass())
          && isExpectedReplacementException(safeRe.throwable())) {
        return false;
      }
      throw divergence(operation, replacement, safeRe.describe(), jdk.describe());
    }

    private AssertionError divergence(String operation, Object safeRe, Object jdk) {
      return divergence(operation, lastReplacement, safeRe, jdk);
    }

    private AssertionError divergence(
        String operation, String replacement, Object safeRe, Object jdk) {
      return new AssertionError(operation + " divergence"
          + "\nRegex: " + javaStringLiteral(regex)
          + "\nFlags: " + flags
          + "\nInput: " + javaStringLiteral(input)
          + (replacement == null ? "" : "\nReplacement: " + javaStringLiteral(replacement))
          + "\nSafeRE: " + safeRe + "\nJDK: " + jdk);
    }

    private void assertSame(String operation, Object safeRe, Object jdk) {
      assertSame(operation, lastReplacement, safeRe, jdk);
    }

    private void assertSame(String operation, String replacement, Object safeRe, Object jdk) {
      if (!Objects.equals(safeRe, jdk)) {
        throw divergence(operation, replacement, safeRe, jdk);
      }
    }

    private <T> T runJdkOracle(
        String operation, T fallback, JdkOracleOperation<T> jdkOperation) {
      if (!jdkOracleAvailable) {
        return fallback;
      }
      JdkOracleResult<T> result =
          FuzzSupport.runJdkOracle(operation, regex, flags, input, jdkOperation);
      if (!result.available()) {
        jdkOracleAvailable = false;
        return fallback;
      }
      return result.value();
    }
  }

  private record OperationResult<T>(T value, RuntimeException throwable) {
    static OperationResult<String> capture(StringOperation operation) {
      try {
        return new OperationResult<>(operation.run(), null);
      } catch (RuntimeException e) {
        return new OperationResult<>(null, e);
      }
    }

    String describe() {
      return throwable == null
          ? Objects.toString(value)
          : throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
    }
  }

  private interface StringOperation {
    String run();
  }

  private interface JdkOracleOperation<T> {
    T run();
  }

  private record JdkOracleResult<T>(boolean available, T value) {}

  private static <T> JdkOracleResult<T> runJdkOracle(
      String operation,
      String regex,
      int flags,
      String input,
      JdkOracleOperation<T> jdkOperation) {
    Future<T> task = JDK_ORACLE_EXECUTOR.submit(jdkOperation::run);

    try {
      return new JdkOracleResult<>(true, task.get(jdkOracleTimeoutMillis(), TimeUnit.MILLISECONDS));
    } catch (TimeoutException e) {
      task.cancel(true);
      return unavailableJdkOracle();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for JDK regex oracle", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof JdkOracleInterruptedException) {
        return unavailableJdkOracle();
      }
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new AssertionError("JDK regex oracle failed with checked exception", cause);
    }
  }

  private static <T> JdkOracleResult<T> unavailableJdkOracle() {
    return new JdkOracleResult<>(false, null);
  }

  private static long jdkOracleTimeoutMillis() {
    String configured = System.getProperty(JDK_ORACLE_TIMEOUT_PROPERTY);
    if (configured == null) {
      return DEFAULT_JDK_ORACLE_TIMEOUT_MILLIS;
    }
    try {
      return Math.max(1, Long.parseLong(configured));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          JDK_ORACLE_TIMEOUT_PROPERTY + " must be a positive integer", e);
    }
  }

  private static CharSequence interruptible(CharSequence delegate) {
    return delegate instanceof InterruptibleCharSequence
        ? delegate
        : new InterruptibleCharSequence(delegate);
  }

  private record InterruptibleCharSequence(CharSequence delegate) implements CharSequence {
    @Override
    public int length() {
      checkInterrupted();
      return delegate.length();
    }

    @Override
    public char charAt(int index) {
      checkInterrupted();
      return delegate.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      checkInterrupted();
      return new InterruptibleCharSequence(delegate.subSequence(start, end));
    }

    @Override
    public String toString() {
      return delegate.toString();
    }
  }

  private static void checkInterrupted() {
    if (Thread.currentThread().isInterrupted()) {
      throw new JdkOracleInterruptedException();
    }
  }

  private static final class JdkOracleInterruptedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  private static boolean isExpectedReplacementException(RuntimeException exception) {
    return exception instanceof IllegalArgumentException
        || exception instanceof IndexOutOfBoundsException;
  }

  private static String describeArray(String[] values) {
    return Arrays.stream(values)
        .map(FuzzSupport::javaStringLiteral)
        .toList()
        .toString();
  }

  private static String javaStringLiteral(String value) {
    StringBuilder result = new StringBuilder(value.length() + 2);
    result.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> result.append("\\\\");
        case '"' -> result.append("\\\"");
        case '\b' -> result.append("\\b");
        case '\f' -> result.append("\\f");
        case '\n' -> result.append("\\n");
        case '\r' -> result.append("\\r");
        case '\t' -> result.append("\\t");
        default -> {
          if (c < 0x20 || Character.isSurrogate(c)) {
            result.append(String.format("\\u%04x", (int) c));
          } else {
            result.append(c);
          }
        }
      }
    }
    result.append('"');
    return result.toString();
  }

  private static boolean isIntentionallyUnsupported(
      String regex, PatternSyntaxException safeReException) {
    return hasLookaround(regex)
        || hasBackreference(regex)
        || hasPossessiveQuantifier(regex)
        || isOverCompilerBudget(safeReException);
  }

  private static boolean isOverCompilerBudget(PatternSyntaxException safeReException) {
    return "compiled program too large".equals(safeReException.getDescription());
  }

  private static boolean hasLookaround(String regex) {
    return regex.contains("(?=")
        || regex.contains("(?!")
        || regex.contains("(?<=")
        || regex.contains("(?<!");
  }

  private static boolean hasBackreference(String regex) {
    return regex.matches(".*\\\\[1-9].*")
        || regex.contains("\\k<")
        || regex.contains("\\g{")
        || regex.contains("\\g");
  }

  private static boolean hasPossessiveQuantifier(String regex) {
    for (int i = 1; i < regex.length(); i++) {
      if (regex.charAt(i) == '+' && isPossessiveQuantifierPrefix(regex.charAt(i - 1))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isPossessiveQuantifierPrefix(char c) {
    return c == '?' || c == '*' || c == '+' || c == '}';
  }

  static int consumeIndex(FuzzedDataProvider data, String input) {
    return data.consumeInt(0, input.length());
  }

  static int[] consumeRegion(FuzzedDataProvider data, String input) {
    int start = consumeIndex(data, input);
    int end = data.consumeInt(start, input.length());
    return new int[] {start, end};
  }

  static String consumeUnicodeHeavyString(FuzzedDataProvider data, int maxCodePoints) {
    int codePoints = data.consumeInt(0, maxCodePoints);
    StringBuilder sb = new StringBuilder(codePoints);
    for (int i = 0; i < codePoints; i++) {
      switch (data.consumeInt(0, 9)) {
        case 0 -> sb.append((char) data.consumeInt('a', 'z'));
        case 1 -> sb.append((char) data.consumeInt('0', '9'));
        case 2 -> sb.append('\n');
        case 3 -> sb.append('\r');
        case 4 -> sb.append('\u2028');
        case 5 -> sb.append('\u0301');
        case 6 -> sb.append(Character.toChars(data.consumeInt(0x10000, 0x10FFFF)));
        case 7 -> sb.append((char) data.consumeInt(0xD800, 0xDBFF));
        case 8 -> sb.append((char) data.consumeInt(0xDC00, 0xDFFF));
        case 9 -> sb.append((char) data.consumeInt(0x80, 0xFFFD));
        default -> throw new AssertionError();
      }
    }
    return sb.toString();
  }
}
