// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.PatternSyntaxException;

final class FuzzSupport {

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
          jdkPattern.matcher(input));
    }

    void split(CharSequence input) {
      assertArrayEquals(
          "split",
          input.toString(),
          safeRePattern.split(input),
          jdkPattern.split(input));
    }

    void split(CharSequence input, int limit) {
      assertArrayEquals(
          "split(" + limit + ")",
          input.toString(),
          safeRePattern.split(input, limit),
          jdkPattern.split(input, limit));
    }

    void splitWithDelimiters(CharSequence input) {
      assertArrayEquals(
          "splitWithDelimiters",
          input.toString(),
          safeRePattern.splitWithDelimiters(input),
          jdkPattern.splitWithDelimiters(input, 0));
    }

    void splitWithDelimiters(CharSequence input, int limit) {
      assertArrayEquals(
          "splitWithDelimiters(" + limit + ")",
          input.toString(),
          safeRePattern.splitWithDelimiters(input, limit),
          jdkPattern.splitWithDelimiters(input, limit));
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
      boolean jdk = jdkMatcher.matches();
      assertSame("matches", safeRe, jdk);
      if (safeRe) {
        assertMatchState("matches");
      }
      return safeRe;
    }

    boolean lookingAt() {
      boolean safeRe = safeReMatcher.lookingAt();
      boolean jdk = jdkMatcher.lookingAt();
      assertSame("lookingAt", safeRe, jdk);
      if (safeRe) {
        assertMatchState("lookingAt");
      }
      return safeRe;
    }

    boolean find() {
      boolean safeRe = safeReMatcher.find();
      boolean jdk = jdkMatcher.find();
      assertSame("find", safeRe, jdk);
      if (safeRe) {
        assertMatchState("find");
      }
      return safeRe;
    }

    boolean find(int start) {
      boolean safeRe = safeReMatcher.find(start);
      boolean jdk = jdkMatcher.find(start);
      assertSame("find(" + start + ")", safeRe, jdk);
      if (safeRe) {
        assertMatchState("find(" + start + ")");
      }
      return safeRe;
    }

    MatcherPair reset() {
      lastReplacement = null;
      safeReMatcher.reset();
      jdkMatcher.reset();
      return this;
    }

    MatcherPair reset(CharSequence input) {
      this.input = input.toString();
      this.lastReplacement = null;
      safeReMatcher.reset(input);
      jdkMatcher.reset(input);
      return this;
    }

    int groupCount() {
      int safeRe = safeReMatcher.groupCount();
      int jdk = jdkMatcher.groupCount();
      assertSame("groupCount", safeRe, jdk);
      return safeRe;
    }

    String group(int group) {
      String safeRe = safeReMatcher.group(group);
      String jdk = jdkMatcher.group(group);
      assertSame("group(" + group + ")", safeRe, jdk);
      return safeRe;
    }

    String group(String name) {
      String safeRe = safeReMatcher.group(name);
      String jdk = jdkMatcher.group(name);
      assertSame("group(" + name + ")", safeRe, jdk);
      return safeRe;
    }

    int start(int group) {
      int safeRe = safeReMatcher.start(group);
      int jdk = jdkMatcher.start(group);
      assertSame("start(" + group + ")", safeRe, jdk);
      return safeRe;
    }

    int start(String name) {
      int safeRe = safeReMatcher.start(name);
      int jdk = jdkMatcher.start(name);
      assertSame("start(" + name + ")", safeRe, jdk);
      return safeRe;
    }

    int end(int group) {
      int safeRe = safeReMatcher.end(group);
      int jdk = jdkMatcher.end(group);
      assertSame("end(" + group + ")", safeRe, jdk);
      return safeRe;
    }

    int end(String name) {
      int safeRe = safeReMatcher.end(name);
      int jdk = jdkMatcher.end(name);
      assertSame("end(" + name + ")", safeRe, jdk);
      return safeRe;
    }

    boolean hitEnd() {
      boolean safeRe = safeReMatcher.hitEnd();
      boolean jdk = jdkMatcher.hitEnd();
      assertSame("hitEnd", safeRe, jdk);
      return safeRe;
    }

    boolean requireEnd() {
      boolean safeRe = safeReMatcher.requireEnd();
      boolean jdk = jdkMatcher.requireEnd();
      assertSame("requireEnd", safeRe, jdk);
      return safeRe;
    }

    MatcherPair region(int start, int end) {
      safeReMatcher.region(start, end);
      jdkMatcher.region(start, end);
      return this;
    }

    int regionStart() {
      int safeRe = safeReMatcher.regionStart();
      int jdk = jdkMatcher.regionStart();
      assertSame("regionStart", safeRe, jdk);
      return safeRe;
    }

    int regionEnd() {
      int safeRe = safeReMatcher.regionEnd();
      int jdk = jdkMatcher.regionEnd();
      assertSame("regionEnd", safeRe, jdk);
      return safeRe;
    }

    MatcherPair useAnchoringBounds(boolean value) {
      safeReMatcher.useAnchoringBounds(value);
      jdkMatcher.useAnchoringBounds(value);
      return this;
    }

    MatcherPair useTransparentBounds(boolean value) {
      safeReMatcher.useTransparentBounds(value);
      jdkMatcher.useTransparentBounds(value);
      return this;
    }

    boolean hasAnchoringBounds() {
      boolean safeRe = safeReMatcher.hasAnchoringBounds();
      boolean jdk = jdkMatcher.hasAnchoringBounds();
      assertSame("hasAnchoringBounds", safeRe, jdk);
      return safeRe;
    }

    boolean hasTransparentBounds() {
      boolean safeRe = safeReMatcher.hasTransparentBounds();
      boolean jdk = jdkMatcher.hasTransparentBounds();
      assertSame("hasTransparentBounds", safeRe, jdk);
      return safeRe;
    }

    boolean replaceAll(String replacement) {
      return assertSameReplacementOutcome(
          "replaceAll",
          replacement,
          () -> safeReMatcher.replaceAll(replacement),
          () -> jdkMatcher.replaceAll(replacement));
    }

    boolean replaceAll(Function<MatchResult, String> replacer) {
      return assertSameReplacementOutcome(
          "replaceAll(function)",
          null,
          () -> safeReMatcher.replaceAll(replacer),
          () -> jdkMatcher.replaceAll(replacer));
    }

    boolean replaceFirst(String replacement) {
      return assertSameReplacementOutcome(
          "replaceFirst",
          replacement,
          () -> safeReMatcher.replaceFirst(replacement),
          () -> jdkMatcher.replaceFirst(replacement));
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
            jdkMatcher.appendReplacement(jdk, replacement);
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
            jdkMatcher.appendReplacement(jdk, replacement);
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
      jdkMatcher.appendTail(jdk);
      assertSame("appendTail", safeRe.toString(), jdk.toString());
      output.append(safeRe);
    }

    void appendTail(StringBuffer output) {
      StringBuffer safeRe = new StringBuffer();
      StringBuffer jdk = new StringBuffer();
      safeReMatcher.appendTail(safeRe);
      jdkMatcher.appendTail(jdk);
      assertSame("appendTail", safeRe.toString(), jdk.toString());
      output.append(safeRe);
    }

    void toMatchResult() {
      MatchResult safeRe = safeReMatcher.toMatchResult();
      MatchResult jdk = jdkMatcher.toMatchResult();
      assertMatchResult("toMatchResult", safeRe, jdk);
    }

    private void assertMatchState(String operation) {
      assertSame(operation + ".start", safeReMatcher.start(), jdkMatcher.start());
      assertSame(operation + ".end", safeReMatcher.end(), jdkMatcher.end());
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
      OperationResult<String> jdk = OperationResult.capture(jdkOperation);
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
