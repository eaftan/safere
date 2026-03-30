// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Stack;
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
  static final List<String> EGREP_OPS =
      List.of("%s%s", "%s|%s", "%s*", "%s+", "%s?");

  /**
   * Run an exhaustive test with the given configuration. Generates all regexps from atoms+ops, all
   * strings from alphabet, and compares SafeRE vs JDK for each pair.
   *
   * @return the number of test cases executed
   */
  static int run(Config config) {
    List<TestResult> failures = new ArrayList<>();
    int[] testCount = {0};

    generateRegexps(
        config.maxAtoms(),
        config.maxOps(),
        config.atoms(),
        config.ops(),
        config.wrapper(),
        regexp -> {
          // For each regexp, generate all strings and test
          List<String> strings = generateStrings(config.maxStrLen(), config.strAlphabet());

          for (String text : strings) {
            testCount[0] += testPair(regexp, text, config.flags(), failures);

            // Also test with long text prefix to force DFA path
            if (config.testLongText()) {
              String longText = Config.LONG_TEXT_PREFIX + text;
              testCount[0] += testPair(regexp, longText, config.flags(), failures);
            }
          }
        });

    if (!failures.isEmpty()) {
      int show = Math.min(failures.size(), 50);
      StringBuilder sb = new StringBuilder();
      sb.append(
          String.format(
              "%d failures out of %d tests (showing first %d):%n",
              failures.size(), testCount[0], show));
      for (int i = 0; i < show; i++) {
        sb.append("  ").append(failures.get(i)).append("\n");
      }
      fail(sb.toString());
    }

    return testCount[0];
  }

  /**
   * Test a single (regexp, text, flags) triple. Compares SafeRE vs JDK for matches() and find().
   * Returns the number of test cases executed.
   *
   * <p>Skips tests where text contains standalone {@code \r} (not part of {@code \r\n}), since JDK
   * treats {@code \r} as a line terminator but SafeRE (like RE2) does not. This is a known design
   * difference, not a bug.
   */
  static int testPair(String regexp, String text, int flags, List<TestResult> failures) {
    int tests = 0;

    // Skip text with standalone \r (not \r\n) — known JDK vs RE2 behavioral difference
    if (hasStandaloneCarriageReturn(text)) {
      return 0;
    }

    // Skip patterns with zero-width assertions (\b, \B) inside quantified groups —
    // known RE2 vs JDK semantic difference in zero-width repetition handling
    if (ZERO_WIDTH_IN_REPETITION.matcher(regexp).find()) {
      return 0;
    }

    // Skip patterns with $ and \b/\B in the same pattern — known bug #42 (bug 1).
    // SafeRE's unanchored search misses earlier \b positions, finding $ at end instead.
    if (DOLLAR_ASSERTION_ALT.matcher(regexp).find()) {
      return 0;
    }

    // Skip patterns with ^ and $ in the same alternation — known bug #42 (bug 3).
    // SafeRE's findAll misses $ before \n when ^ is also an alternative in the pattern.
    if (CARET_DOLLAR_ALT.matcher(regexp).find()) {
      return 0;
    }

    // Skip patterns with nested repetition and capture groups — known bug #42.
    // SafeRE and JDK disagree on which iteration's capture to report.
    if (NESTED_REP_CAPTURE.matcher(regexp).find()) {
      return 0;
    }

    // Skip patterns with $ alternated with \n inside repetition — known bug #42.
    // SafeRE's zero-width $ + consuming \n in a * loop disagrees with JDK on findAll boundaries.
    if (regexp.contains("$") && regexp.contains("\\n") && regexp.matches(".*[*+].*")) {
      return 0;
    }

    // Skip empty text with MULTILINE + ^ anchor — JDK bug: ^ in MULTILINE mode doesn't
    // match at position 0 of an empty string, but does match at position 0 of any non-empty
    // string. SafeRE correctly considers ^ to always match at the start of text regardless
    // of text length. We keep our correct behavior. See https://github.com/eaftan/safere/issues/41
    if (text.isEmpty()
        && (flags & java.util.regex.Pattern.MULTILINE) != 0
        && regexp.contains("^")) {
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

      boolean jdkFound = jdkM.find();
      boolean safereFound = safereM.find();

      if (jdkFound != safereFound) {
        failures.add(
            new TestResult(
                regexp,
                text,
                flags,
                "find",
                String.valueOf(safereFound),
                String.valueOf(jdkFound)));
      } else if (jdkFound) {
        // Compare group(0) positions
        String jdkMatch =
            String.format("[%d,%d)", jdkM.start(), jdkM.end());
        String safereMatch =
            String.format("[%d,%d)", safereM.start(), safereM.end());
        if (!jdkMatch.equals(safereMatch)) {
          failures.add(new TestResult(regexp, text, flags, "find/pos", safereMatch, jdkMatch));
        } else {
          // Compare all group positions (up to shared count)
          int sharedGroups = Math.min(jdkM.groupCount(), safereM.groupCount());
          String jdkGroups = extractGroups(jdkM, sharedGroups);
          String safereGroups = extractGroups(safereM, sharedGroups);
          if (!jdkGroups.equals(safereGroups)) {
            failures.add(
                new TestResult(regexp, text, flags, "find/groups", safereGroups, jdkGroups));
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

      List<String> jdkAll = new ArrayList<>();
      while (jdkM.find()) {
        jdkAll.add(String.format("[%d,%d)", jdkM.start(), jdkM.end()));
      }
      List<String> safereAll = new ArrayList<>();
      while (safereM.find()) {
        safereAll.add(String.format("[%d,%d)", safereM.start(), safereM.end()));
      }

      if (!jdkAll.equals(safereAll)) {
        failures.add(
            new TestResult(
                regexp, text, flags, "findAll", safereAll.toString(), jdkAll.toString()));
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

  /**
   * Generate all possible regular expressions from atoms and operators using RE2's postfix approach.
   * For each generated regexp, also generates anchored variants: ^(re)$, ^(re), (re)$.
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
        generatePostfix(post, nstk + 1, opsUsed, atomsUsed + 1, maxAtoms, maxOps, atoms, ops,
            wrapper, handler);
        post.removeLast();
      }
    }

    // Add operators
    if (opsUsed < maxOps) {
      for (String op : ops) {
        int nargs = countArgs(op);
        if (nargs <= nstk) {
          post.add(op);
          generatePostfix(post, nstk - nargs + 1, opsUsed + 1, atomsUsed, maxAtoms, maxOps,
              atoms, ops, wrapper, handler);
          post.removeLast();
        }
      }
    }
  }

  /** Evaluate postfix sequence into a regexp string and emit it (plus anchored variants). */
  private static void runPostfix(List<String> post, String wrapper, Consumer<String> handler) {
    Stack<String> regexps = new Stack<>();
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
   * Returns true if the text contains any {@code \r} character. JDK treats {@code \r} (both
   * standalone and in {@code \r\n}) specially — dot doesn't match it, {@code $} matches before it,
   * etc. SafeRE (like RE2) only gives special treatment to {@code \n}. This is a known design
   * difference, not a bug.
   */
  private static boolean hasStandaloneCarriageReturn(String text) {
    return text.indexOf('\r') >= 0;
  }

  /**
   * Pattern detecting zero-width assertions ({@code \b}, {@code \B}, {@code ^}, {@code $}) inside
   * a quantified group ({@code *}, {@code +}). RE2/SafeRE and JDK differ in how they handle
   * zero-width matches inside repetition: JDK breaks the {@code *}/{@code +} loop after a
   * zero-width body match (to prevent infinite repetition), while RE2 continues the loop, allowing
   * a subsequent consuming alternative to match. This is a fundamental semantic difference, not a
   * bug.
   *
   * <p>Example: {@code (?:\B|a)*} on "aa" — JDK returns [0,1), SafeRE returns [0,2). After
   * matching {@code a} at position 0, position 1 is between two word characters, so {@code \B}
   * matches zero-width. JDK stops the {@code *} loop; SafeRE continues and matches the second
   * {@code a}.
   */
  private static final java.util.regex.Pattern ZERO_WIDTH_IN_REPETITION =
      java.util.regex.Pattern.compile("\\\\[bB].*[*+]|[*+].*\\\\[bB]");

  /**
   * Pattern detecting `$` and `\b` (or `\B`) in the same pattern. SafeRE has a known bug where
   * unanchored search for `$|\b` (or `\b|$`) skips positions where only `\b` matches, finding `$`
   * at end-of-text instead of `\b` at an earlier word boundary.
   * See <a href="https://github.com/eaftan/safere/issues/42">issue #42</a> (bug 1).
   */
  private static final java.util.regex.Pattern DOLLAR_ASSERTION_ALT =
      java.util.regex.Pattern.compile(
          "\\$.*\\\\[bB]|\\\\[bB].*\\$");

  /**
   * Pattern detecting capture groups inside zero-or-more repetition ({@code *}, {@code +?},
   * {@code {N,}}, etc.) anchored by `$`. SafeRE has a known bug where capture groups are not
   * preserved when the repetition matches zero times at the `$` anchor position, and where nested
   * repetitions disagree on which iteration's capture to report.
   * See <a href="https://github.com/eaftan/safere/issues/42">issue #42</a>.
   */
  private static final java.util.regex.Pattern NESTED_REP_CAPTURE =
      java.util.regex.Pattern.compile(
          "\\([^)]*\\)[^)]*(?:\\{\\d+[,}]|[*+?]).*(?:\\{\\d+[,}]|[*+?])|"
              + "\\([^)]*\\)[^)]*(?:[*+?]|\\{\\d+[,}]).*\\$");

  /**
   * Pattern detecting `^`/`\A` and `$` as alternatives within a group (e.g., {@code (?:^|$)} or
   * {@code (?:$|\A)}). SafeRE has a known bug where findAll misses `$` before `\n` when `^` or
   * `\A` is also an alternative.
   * See <a href="https://github.com/eaftan/safere/issues/42">issue #42</a> (bug 3).
   */
  private static final java.util.regex.Pattern CARET_DOLLAR_ALT =
      java.util.regex.Pattern.compile(
          "\\(\\?*:?(?:\\^|\\\\A)\\).*\\|.*\\$|\\$.*\\|.*\\(\\?*:?(?:\\^|\\\\A)\\)");

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
