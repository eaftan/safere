// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.PatternSyntaxException;

/**
 * Exhaustive test utilities ported from RE2's testing infrastructure.
 *
 * <p>Generates all possible regular expressions within given parameters (maxatoms, maxops, alphabet
 * of atoms, set of operators), then generates all possible strings of up to a given length over a
 * given alphabet, and compares SafeRE against JDK regex for each (regexp, string) pair.
 *
 * <p>This is the mechanical approach to finding bugs: enumerate all combinations and check that
 * SafeRE and JDK agree.
 */
final class ExhaustiveUtils {

  /** Result of a single regexp+string test. */
  record TestResult(
      String regexp, String text, int flags, String method, String safereResult, String jdkResult) {
    @Override
    public String toString() {
      return String.format(
          "%s(\"%s\", \"%s\", flags=%d): safere=%s, jdk=%s",
          method, escape(regexp), escape(text), flags, safereResult, jdkResult);
    }
  }

  /** Configuration for an exhaustive test run. */
  record Config(
      int maxAtoms,
      int maxOps,
      List<String> atoms,
      List<String> ops,
      int maxStrLen,
      List<String> strAlphabet,
      String wrapper,
      int flags,
      boolean testLongText) {

    /** Padding prefix used to push text beyond 256 chars (forcing DFA path). */
    static final String LONG_TEXT_PREFIX = "#".repeat(260);
  }

  // Standard egrep operators (like RE2's EgrepOps, minus \C*)
  static final List<String> EGREP_OPS = List.of("%s%s", "%s|%s", "%s*", "%s+", "%s?");

  /**
   * Run an exhaustive test with the given configuration. Generates all regexps from atoms+ops, all
   * strings from alphabet, and compares SafeRE vs JDK for each pair. Regexps are tested in parallel
   * using the common ForkJoinPool.
   *
   * @return the number of test cases executed
   */
  static int run(Config config) {
    List<String> allRegexps = new ArrayList<>();
    generateRegexps(
        config.maxAtoms(),
        config.maxOps(),
        config.atoms(),
        config.ops(),
        config.wrapper(),
        allRegexps::add);

    List<String> strings = generateStrings(config.maxStrLen(), config.strAlphabet());
    List<TestResult> failures = Collections.synchronizedList(new ArrayList<>());
    AtomicInteger testCount = new AtomicInteger();

    allRegexps.parallelStream()
        .forEach(
            regexp -> {
              List<TestResult> localFailures = new ArrayList<>();
              for (String text : strings) {
                testCount.addAndGet(testPair(regexp, text, config.flags(), localFailures));

                if (config.testLongText()) {
                  testCount.addAndGet(
                      testPair(
                          regexp, Config.LONG_TEXT_PREFIX + text, config.flags(), localFailures));
                }
              }
              if (!localFailures.isEmpty()) {
                failures.addAll(localFailures);
              }
            });

    if (!failures.isEmpty()) {
      int show = Math.min(failures.size(), 50);
      StringBuilder sb = new StringBuilder();
      sb.append(
          String.format(
              "%d failures out of %d tests (showing first %d):%n",
              failures.size(), testCount.get(), show));
      for (int i = 0; i < show; i++) {
        sb.append("  ").append(failures.get(i)).append("\n");
      }
      fail(sb.toString());
    }

    return testCount.get();
  }

  /**
   * Test a single (regexp, text, flags) triple. Compares SafeRE vs JDK for matches() and find().
   * Returns the number of test cases executed.
   */
  static int testPair(String regexp, String text, int flags, List<TestResult> failures) {
    int tests = 0;

    // Skip patterns with nested repetition and capture groups — known behavioral difference.
    // NFA engines start a fresh thread at each position with captures initialized to -1.
    // When a {0,}/*  repetition matches zero times, captures from earlier (failed) starting
    // positions are not preserved. JDK's backtracking engine leaks captures from failed attempts
    // (and is itself inconsistent: `(a)*$` vs `(?:(a))*$` give different g1 results).
    // This is a suspected JDK bug, not a SafeRE bug.
    // See https://github.com/eaftan/safere/issues/42 (bug 2) and
    // https://github.com/eaftan/safere/issues/52 for detailed analysis.
    if (NESTED_REP_CAPTURE.matcher(regexp).find()) {
      return 0;
    }

    // Try to compile with JDK first — if JDK rejects it, skip
    java.util.regex.Pattern jdkPat;
    try {
      jdkPat = java.util.regex.Pattern.compile(regexp, flags);
    } catch (PatternSyntaxException e) {
      return 0;
    }

    // Try to compile with SafeRE — if it rejects (unsupported feature), skip
    Pattern saferePat;
    try {
      saferePat = Pattern.compile(regexp, flags);
    } catch (PatternSyntaxException e) {
      return 0;
    }

    Utf8Coordinates utf8Coordinates = Utf8Coordinates.forText(text);
    byte[] utf8 = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);

    // Compare matches()
    tests++;
    try {
      java.util.regex.Matcher jdkM = jdkPat.matcher(text);
      Matcher safereM = saferePat.matcher(text);

      boolean jdkMatches = jdkM.matches();
      boolean safereMatches = safereM.matches();

      if (jdkMatches != safereMatches) {
        failures.add(
            new TestResult(
                regexp,
                text,
                flags,
                "matches",
                String.valueOf(safereMatches),
                String.valueOf(jdkMatches)));
      } else if (jdkMatches) {
        // Compare group positions (up to the shared group count)
        int sharedGroups = Math.min(jdkM.groupCount(), safereM.groupCount());
        String jdkGroups = extractGroups(jdkM, sharedGroups);
        String safereGroups = extractGroups(safereM, sharedGroups);
        if (!jdkGroups.equals(safereGroups)) {
          failures.add(
              new TestResult(regexp, text, flags, "matches/groups", safereGroups, jdkGroups));
        }
      }
    } catch (Exception e) {
      failures.add(
          new TestResult(
              regexp, text, flags, "matches", "EXCEPTION: " + e.getMessage(), "(expected)"));
    }

    // Compare find()
    tests++;
    try {
      java.util.regex.Matcher jdkM = jdkPat.matcher(text);
      Matcher safereM = saferePat.matcher(text);
      Utf8Matcher safereByteM = saferePat.matcher(Utf8Input.validated(utf8));

      boolean jdkFound = jdkM.find();
      boolean safereFound = safereM.find();
      boolean safereByteFound = safereByteM.find();

      if (jdkFound != safereFound) {
        failures.add(
            new TestResult(
                regexp,
                text,
                flags,
                "find",
                String.valueOf(safereFound),
                String.valueOf(jdkFound)));
      } else if (jdkFound != safereByteFound) {
        failures.add(
            new TestResult(
                regexp,
                text,
                flags,
                "find(bytes)",
                String.valueOf(safereByteFound),
                String.valueOf(jdkFound)));
      } else if (jdkFound) {
        // Compare group(0) positions
        String jdkMatch = String.format("[%d,%d)", jdkM.start(), jdkM.end());
        String safereMatch = String.format("[%d,%d)", safereM.start(), safereM.end());
        int byteStart = safereByteM.start();
        int byteEnd = safereByteM.end();
        String safereByteMatch =
            String.format(
                "[%d,%d)", utf8Coordinates.toUtf16(byteStart), utf8Coordinates.toUtf16(byteEnd));

        if (!jdkMatch.equals(safereMatch)) {
          failures.add(new TestResult(regexp, text, flags, "find/pos", safereMatch, jdkMatch));
        } else if (!jdkMatch.equals(safereByteMatch)) {
          failures.add(
              new TestResult(regexp, text, flags, "find/pos(bytes)", safereByteMatch, jdkMatch));
        } else {
          // Compare all group positions (up to shared count)
          int sharedGroups = Math.min(jdkM.groupCount(), safereM.groupCount());
          String jdkGroups = extractGroups(jdkM, sharedGroups);
          String safereGroups = extractGroups(safereM, sharedGroups);
          String safereByteGroups =
              extractGroupsForBytes(safereByteM, sharedGroups, utf8Coordinates);
          if (!jdkGroups.equals(safereGroups)) {
            failures.add(
                new TestResult(regexp, text, flags, "find/groups", safereGroups, jdkGroups));
          } else if (!jdkGroups.equals(safereByteGroups)) {
            failures.add(
                new TestResult(
                    regexp, text, flags, "find/groups(bytes)", safereByteGroups, jdkGroups));
          }
        }
      }
    } catch (Exception e) {
      failures.add(
          new TestResult(
              regexp, text, flags, "find", "EXCEPTION: " + e.getMessage(), "(expected)"));
    }

    // Compare find-all (all occurrences)
    tests++;
    try {
      java.util.regex.Matcher jdkM = jdkPat.matcher(text);
      Matcher safereM = saferePat.matcher(text);
      Utf8Matcher safereByteM = saferePat.matcher(Utf8Input.validated(utf8));

      List<String> jdkAll = new ArrayList<>();
      while (jdkM.find()) {
        jdkAll.add(String.format("[%d,%d)", jdkM.start(), jdkM.end()));
      }
      List<String> safereAll = new ArrayList<>();
      while (safereM.find()) {
        safereAll.add(String.format("[%d,%d)", safereM.start(), safereM.end()));
      }
      List<String> safereByteAll = new ArrayList<>();
      while (safereByteM.find()) {
        int byteStart = safereByteM.start();
        int byteEnd = safereByteM.end();
        safereByteAll.add(
            String.format(
                "[%d,%d)", utf8Coordinates.toUtf16(byteStart), utf8Coordinates.toUtf16(byteEnd)));
      }

      if (!jdkAll.equals(safereAll)) {
        failures.add(
            new TestResult(
                regexp, text, flags, "findAll", safereAll.toString(), jdkAll.toString()));
      } else if (!jdkAll.equals(safereByteAll)) {
        failures.add(
            new TestResult(
                regexp,
                text,
                flags,
                "findAll(bytes)",
                safereByteAll.toString(),
                jdkAll.toString()));
      }
    } catch (Exception e) {
      failures.add(
          new TestResult(
              regexp, text, flags, "findAll", "EXCEPTION: " + e.getMessage(), "(expected)"));
    }

    return tests;
  }

  /** Extract group positions from a matcher as a string like "g0=[0,5) g1=[1,3) g2=[-1,-1)". */
  private static String extractGroups(java.util.regex.Matcher m, int maxGroups) {
    int count = Math.min(m.groupCount(), maxGroups);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i <= count; i++) {
      if (i > 0) {
        sb.append(' ');
      }
      sb.append(String.format("g%d=[%d,%d)", i, m.start(i), m.end(i)));
    }
    return sb.toString();
  }

  /** Extract group positions from a SafeRE matcher. */
  private static String extractGroups(Matcher m, int maxGroups) {
    int count = Math.min(m.groupCount(), maxGroups);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i <= count; i++) {
      if (i > 0) {
        sb.append(' ');
      }
      sb.append(String.format("g%d=[%d,%d)", i, m.start(i), m.end(i)));
    }
    return sb.toString();
  }

  private static String extractGroupsForBytes(
      Utf8Matcher m, int maxGroups, Utf8Coordinates coordinates) {
    int count = Math.min(m.groupCount(), maxGroups);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i <= count; i++) {
      if (i > 0) {
        sb.append(' ');
      }
      int start = m.start(i);
      int end = m.end(i);
      int charStart = start < 0 ? -1 : coordinates.toUtf16(start);
      int charEnd = end < 0 ? -1 : coordinates.toUtf16(end);
      sb.append(String.format("g%d=[%d,%d)", i, charStart, charEnd));
    }
    return sb.toString();
  }

  /** Exact scalar-boundary mapping from relative UTF-8 offsets to UTF-16 String offsets. */
  static final class Utf8Coordinates {
    private final int[] utf16ByUtf8Offset;

    private Utf8Coordinates(int[] utf16ByUtf8Offset) {
      this.utf16ByUtf8Offset = utf16ByUtf8Offset;
    }

    static Utf8Coordinates forText(String text) {
      byte[] utf8 = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      int[] utf16ByUtf8Offset = new int[utf8.length + 1];
      Arrays.fill(utf16ByUtf8Offset, -1);
      int utf8Offset = 0;
      for (int utf16Offset = 0; utf16Offset < text.length(); ) {
        char first = text.charAt(utf16Offset);
        if (Character.isSurrogate(first)
            && !(Character.isHighSurrogate(first)
                && utf16Offset + 1 < text.length()
                && Character.isLowSurrogate(text.charAt(utf16Offset + 1)))) {
          throw new IllegalArgumentException(
              "String contains an unpaired surrogate at UTF-16 offset " + utf16Offset);
        }
        utf16ByUtf8Offset[utf8Offset] = utf16Offset;
        int codePoint = text.codePointAt(utf16Offset);
        utf8Offset += utf8Length(codePoint);
        utf16Offset += Character.charCount(codePoint);
      }
      utf16ByUtf8Offset[utf8Offset] = text.length();
      if (utf8Offset != utf8.length) {
        throw new IllegalStateException("UTF-8 coordinate map length mismatch");
      }
      return new Utf8Coordinates(utf16ByUtf8Offset);
    }

    int toUtf16(int utf8Offset) {
      if (utf8Offset < 0 || utf8Offset >= utf16ByUtf8Offset.length) {
        throw new IndexOutOfBoundsException("UTF-8 offset: " + utf8Offset);
      }
      int utf16Offset = utf16ByUtf8Offset[utf8Offset];
      if (utf16Offset < 0) {
        throw new IllegalArgumentException(
            "UTF-8 offset is not a Unicode scalar boundary: " + utf8Offset);
      }
      return utf16Offset;
    }

    private static int utf8Length(int codePoint) {
      if (codePoint <= 0x7f) {
        return 1;
      }
      if (codePoint <= 0x7ff) {
        return 2;
      }
      if (codePoint <= 0xffff) {
        return 3;
      }
      return 4;
    }
  }

  /**
   * Generate all possible regular expressions from atoms and operators using RE2's postfix
   * approach. For each generated regexp, also generates anchored variants: ^(re)$, ^(re), (re)$.
   */
  static void generateRegexps(
      int maxAtoms,
      int maxOps,
      List<String> atoms,
      List<String> ops,
      String wrapper,
      Consumer<String> handler) {
    if (atoms.isEmpty()) {
      maxAtoms = 0;
    }
    if (ops.isEmpty()) {
      maxOps = 0;
    }
    List<String> postfix = new ArrayList<>();
    generatePostfix(postfix, 0, 0, 0, maxAtoms, maxOps, atoms, ops, wrapper, handler);
  }

  private static void generatePostfix(
      List<String> post,
      int nstk,
      int opsUsed,
      int atomsUsed,
      int maxAtoms,
      int maxOps,
      List<String> atoms,
      List<String> ops,
      String wrapper,
      Consumer<String> handler) {
    if (nstk == 1) {
      runPostfix(post, wrapper, handler);
    }

    // Early out: can't get back to single expression
    if (opsUsed + nstk - 1 > maxOps) {
      return;
    }

    // Add atoms
    if (atomsUsed < maxAtoms) {
      for (String atom : atoms) {
        post.add(atom);
        generatePostfix(
            post, nstk + 1, opsUsed, atomsUsed + 1, maxAtoms, maxOps, atoms, ops, wrapper, handler);
        post.removeLast();
      }
    }

    // Add operators
    if (opsUsed < maxOps) {
      for (String op : ops) {
        int nargs = countArgs(op);
        if (nargs <= nstk) {
          post.add(op);
          generatePostfix(
              post,
              nstk - nargs + 1,
              opsUsed + 1,
              atomsUsed,
              maxAtoms,
              maxOps,
              atoms,
              ops,
              wrapper,
              handler);
          post.removeLast();
        }
      }
    }
  }

  /** Evaluate postfix sequence into a regexp string and emit it (plus anchored variants). */
  private static void runPostfix(List<String> post, String wrapper, Consumer<String> handler) {
    Deque<String> regexps = new ArrayDeque<>();
    for (String cmd : post) {
      int nargs = countArgs(cmd);
      if (nargs == 0) {
        regexps.push(cmd);
      } else if (nargs == 1) {
        String a = regexps.pop();
        regexps.push("(?:" + substituteFirst(cmd, a) + ")");
      } else if (nargs == 2) {
        String b = regexps.pop();
        String a = regexps.pop();
        String result = substituteFirst(substituteFirst(cmd, a), b);
        regexps.push("(?:" + result + ")");
      }
    }
    if (regexps.size() != 1) {
      return; // invalid sequence
    }

    String re = regexps.pop();
    if (!wrapper.isEmpty()) {
      re = wrapper.replace("%s", re);
    }

    // Emit the regexp and anchored variants (like RE2's RunPostfix)
    handler.accept(re);
    handler.accept("^(?:" + re + ")$");
    handler.accept("^(?:" + re + ")");
    handler.accept("(?:" + re + ")$");
  }

  /** Count occurrences of "%s" in a string. */
  private static int countArgs(String s) {
    int count = 0;
    int idx = 0;
    while ((idx = s.indexOf("%s", idx)) != -1) {
      count++;
      idx += 2;
    }
    return count;
  }

  /** Replace the first occurrence of "%s" with the literal value (no regex interpretation). */
  private static String substituteFirst(String template, String value) {
    int idx = template.indexOf("%s");
    if (idx == -1) {
      return template;
    }
    return template.substring(0, idx) + value + template.substring(idx + 2);
  }

  /** Generate all strings of length 0..maxLen over the given alphabet. */
  static List<String> generateStrings(int maxLen, List<String> alphabet) {
    List<String> result = new ArrayList<>();
    result.add(""); // empty string
    generateStringsRecursive(result, new StringBuilder(), maxLen, alphabet);
    return result;
  }

  private static void generateStringsRecursive(
      List<String> result, StringBuilder current, int maxLen, List<String> alphabet) {
    if (current.length() > 0) {
      result.add(current.toString());
    }
    if (current.length() >= maxLen) {
      return;
    }
    for (String ch : alphabet) {
      current.append(ch);
      generateStringsRecursive(result, current, maxLen, alphabet);
      current.delete(current.length() - ch.length(), current.length());
    }
  }

  /**
   * Pattern detecting capture groups inside zero-or-more repetition ({@code *}, {@code +?}, {@code
   * {N,}}, etc.) anchored by {@code $}. NFA engines start a fresh thread at each position with
   * captures initialized to -1. When a {@code {0,}}/{@code *} repetition matches zero times,
   * captures from earlier (failed) starting positions are not preserved. JDK's backtracking engine
   * leaks captures from failed attempts (and is itself inconsistent: {@code (a)*$} vs {@code
   * (?:(a))*$} give different g1 results). This is an inherent NFA vs backtracking difference — a
   * suspected JDK bug, not a SafeRE bug.
   *
   * <p>See <a href="https://github.com/eaftan/safere/issues/42">issue #42</a> (bug 2) and <a
   * href="https://github.com/eaftan/safere/issues/52">issue #52</a> for detailed analysis.
   */
  private static final java.util.regex.Pattern NESTED_REP_CAPTURE =
      java.util.regex.Pattern.compile(
          "\\([^)]*\\)[^)]*(?:\\{\\d+[,}]|[*+?]).*(?:\\{\\d+[,}]|[*+?])|"
              + "\\([^)]*\\)[^)]*(?:[*+?]|\\{\\d+[,}]).*\\$");

  /** Escape non-printable characters for error messages. */
  static String escape(String s) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\n') {
        sb.append("\\n");
      } else if (c == '\r') {
        sb.append("\\r");
      } else if (c == '\t') {
        sb.append("\\t");
      } else if (c >= 0x20 && c < 0x7F) {
        sb.append(c);
      } else {
        sb.append(String.format("\\u%04x", (int) c));
      }
    }
    return sb.toString();
  }

  private ExhaustiveUtils() {}
}
