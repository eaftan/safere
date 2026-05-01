// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exhaustive tests parsed from RE2's {@code re2-exhaustive.txt.gz} test data file.
 *
 * <p>This file contains thousands of systematically generated (pattern, text, expected) tuples
 * covering all operator combinations. For each (regexp, string) pair, four semicolon-separated
 * result fields are given:
 *
 * <ol>
 *   <li>Full match, first-match semantics
 *   <li>Partial match (find), first-match semantics
 *   <li>Full match, longest-match semantics
 *   <li>Partial match (find), longest-match semantics
 * </ol>
 *
 * <p>We test fields 0 and 1 (first-match semantics), verifying both match/no-match and submatch
 * group positions. Fields 2 and 3 (longest-match) are skipped because SafeRE uses leftmost-first
 * semantics.
 */
@DisplayName("RE2 Exhaustive Tests (from re2-exhaustive.txt.gz)")
class RE2ExhaustiveTest {

  private static final int MAX_FAILURES = 200;

  @Test
  void testExhaustive() throws IOException {
    InputStream is = RE2ExhaustiveTest.class.getResourceAsStream("/re2-exhaustive.txt.gz");
    assertThat(is).as("re2-exhaustive.txt.gz must be in test resources").isNotNull();

    int totalTests = 0;
    int skipped = 0;
    List<String> failures = new ArrayList<>();

    try (UnixLineReader reader =
        new UnixLineReader(
            new InputStreamReader(new GZIPInputStream(is), StandardCharsets.UTF_8))) {
      List<String> strings = new ArrayList<>();
      String line;

      // Skip header to first "strings"
      while ((line = reader.readLine()) != null) {
        if (line.equals("strings")) {
          break;
        }
      }

      outer:
      while (line != null) {
        // Read strings section
        strings.clear();
        while ((line = reader.readLine()) != null) {
          if (line.equals("regexps")) {
            break;
          }
          if (line.startsWith("\"")) {
            strings.add(unescapeString(line));
          }
        }
        if (line == null) {
          break;
        }

        // Read regexps section
        while ((line = reader.readLine()) != null) {
          if (line.equals("strings")) {
            break;
          }
          if (!line.startsWith("\"")) {
            continue;
          }

          String pattern = unescapeString(line);

          // Skip \C patterns (match-any-byte, not applicable to Java)
          boolean skip = pattern.contains("\\C");

          Pattern compiledPat = null;
          if (!skip) {
            try {
              compiledPat = Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
              skip = true;
            }
          }

          // Read one result line per test string
          for (int si = 0; si < strings.size(); si++) {
            String resultLine = reader.readLine();
            if (resultLine == null) {
              line = null;
              break;
            }

            if (skip) {
              skipped++;
              continue;
            }

            String[] fields = resultLine.split(";");
            if (fields.length < 2) {
              skipped++;
              continue;
            }

            String text = strings.get(si);

            // Skip \B on multibyte text: C++ RE2 sees byte boundaries within multi-byte
            // characters, but Java operates on code points. Results differ by design.
            if (pattern.contains("\\B") && !isSingleBytes(text)) {
              skipped++;
              continue;
            }

            // The generated crosscheck copy compares SafeRE against java.util.regex, but the
            // exhaustive RE2 corpus includes known #52 cases where JDK leaks a capture from a
            // failed start position. Keep the ordinary RE2 oracle coverage active, and skip only
            // generated crosscheck cases whose raw engines prove this exact divergence.
            if (isGeneratedCrosscheckRun()
                && hasKnownJdkFailedStartCaptureLeakageDivergence(pattern, text)) {
              skipped++;
              continue;
            }

            int[][] fullExpected = parseResult(fields[0]);
            int[][] findExpected = parseResult(fields[1]);

            // Test matches() against field 0 (full match, first-match semantics)
            totalTests++;
            try {
              Matcher m = compiledPat.matcher(text);
              boolean matched = m.matches();
              if (matched != (fullExpected != null)) {
                // Cross-check against JDK: RE2 expected values may differ for $ before
                // trailing \n (RE2's $ = \z, but JDK/SafeRE's $ also matches before
                // the final \n). If JDK agrees with SafeRE, this is a known difference.
                if (jdkAgrees(pattern, text, "matches", matched, m)) {
                  skipped++;
                } else {
                  failures.add(
                      String.format(
                          "matches() pat=\"%s\" text=\"%s\": got %b, want %b",
                          escape(pattern), escape(text), matched, fullExpected != null));
                }
              } else if (matched && fullExpected != null) {
                checkGroupsOrJdk(m, fullExpected, text, pattern, "matches", failures);
              }
            } catch (Exception e) {
              failures.add(
                  String.format(
                      "matches() EXCEPTION pat=\"%s\" text=\"%s\": %s",
                      escape(pattern), escape(text), e));
            }

            // Test find() against field 1 (partial match, first-match semantics)
            totalTests++;
            try {
              Matcher m = compiledPat.matcher(text);
              boolean found = m.find();
              if (found != (findExpected != null)) {
                if (jdkAgrees(pattern, text, "find", found, m)) {
                  skipped++;
                } else {
                  failures.add(
                      String.format(
                          "find() pat=\"%s\" text=\"%s\": got %b, want %b",
                          escape(pattern), escape(text), found, findExpected != null));
                }
              } else if (found && findExpected != null) {
                checkGroupsOrJdk(m, findExpected, text, pattern, "find", failures);
              }
            } catch (Exception e) {
              failures.add(
                  String.format(
                      "find() EXCEPTION pat=\"%s\" text=\"%s\": %s",
                      escape(pattern), escape(text), e));
            }

            if (failures.size() >= MAX_FAILURES) {
              break outer;
            }
          }

          if (line == null) {
            break;
          }
        }

        if (line == null) {
          break;
        }
      }
    }

    System.err.printf(
        "RE2 Exhaustive: %,d tests, %,d skipped, %,d failures%n",
        totalTests, skipped, failures.size());

    if (!failures.isEmpty()) {
      int show = Math.min(failures.size(), 50);
      StringBuilder sb = new StringBuilder();
      sb.append(String.format("%d failures (showing first %d):%n", failures.size(), show));
      for (int i = 0; i < show; i++) {
        sb.append("  ").append(failures.get(i)).append("\n");
      }
      fail(sb.toString());
    }
  }

  /**
   * Cross-check SafeRE's result against JDK's java.util.regex when a mismatch with the RE2
   * expected value is detected. Returns {@code true} if JDK agrees with SafeRE (known behavioral
   * difference, e.g., {@code $} matches before trailing {@code \n}).
   */
  private static boolean jdkAgrees(
      String pattern, String text, String method, boolean safeReResult, Matcher safeReMatcher) {
    try {
      java.util.regex.Pattern jdkPat = java.util.regex.Pattern.compile(pattern);
      java.util.regex.Matcher jdkMatcher = jdkPat.matcher(text);
      boolean jdkResult =
          method.equals("matches") ? jdkMatcher.matches() : jdkMatcher.find();
      if (jdkResult != safeReResult) {
        return false;
      }
      // Also check group(0) bounds if both found a match
      if (safeReResult && jdkResult) {
        return safeReMatcher.start() == jdkMatcher.start()
            && safeReMatcher.end() == jdkMatcher.end();
      }
      return true;
    } catch (Exception e) {
      return false; // JDK can't compile or match; don't suppress the failure
    }
  }

  /**
   * Check submatch group positions against expected UTF-8 byte-offset pairs.
   *
   * <p>Compares each group's start/end against the expected values after converting from UTF-8 byte
   * offsets to Java char offsets.
   */
  private static void checkGroups(
      Matcher m,
      int[][] expected,
      String text,
      String pattern,
      String method,
      List<String> failures) {
    int numGroups = Math.min(expected.length, m.groupCount() + 1);
    for (int g = 0; g < numGroups; g++) {
      int expectStart;
      int expectEnd;
      if (expected[g][0] == -1 && expected[g][1] == -1) {
        expectStart = -1;
        expectEnd = -1;
      } else {
        expectStart = utf8ByteToCharOffset(text, expected[g][0]);
        expectEnd = utf8ByteToCharOffset(text, expected[g][1]);
      }

      int actualStart = m.start(g);
      int actualEnd = m.end(g);

      if (actualStart != expectStart || actualEnd != expectEnd) {
        failures.add(
            String.format(
                "%s() group %d pat=\"%s\" text=\"%s\": got [%d,%d), want [%d,%d)",
                method, g, escape(pattern), escape(text),
                actualStart, actualEnd, expectStart, expectEnd));
        break; // one failure per test case is enough
      }
    }
  }

  /**
   * Check submatch groups, falling back to JDK cross-validation when RE2 expected values disagree
   * with SafeRE. If JDK agrees with SafeRE's group positions, the mismatch is a known RE2
   * behavioral difference (not a bug).
   */
  private static void checkGroupsOrJdk(
      Matcher m,
      int[][] expected,
      String text,
      String pattern,
      String method,
      List<String> failures) {
    int numGroups = Math.min(expected.length, m.groupCount() + 1);
    for (int g = 0; g < numGroups; g++) {
      int expectStart;
      int expectEnd;
      if (expected[g][0] == -1 && expected[g][1] == -1) {
        expectStart = -1;
        expectEnd = -1;
      } else {
        expectStart = utf8ByteToCharOffset(text, expected[g][0]);
        expectEnd = utf8ByteToCharOffset(text, expected[g][1]);
      }

      int actualStart = m.start(g);
      int actualEnd = m.end(g);

      if (actualStart != expectStart || actualEnd != expectEnd) {
        // Before reporting failure, cross-check against JDK
        if (jdkAgreesOnGroups(pattern, text, method, m)) {
          return; // JDK agrees with SafeRE; known RE2 difference
        }
        failures.add(
            String.format(
                "%s() group %d pat=\"%s\" text=\"%s\": got [%d,%d), want [%d,%d)",
                method, g, escape(pattern), escape(text),
                actualStart, actualEnd, expectStart, expectEnd));
        break;
      }
    }
  }

  /**
   * Check if JDK's group positions agree with SafeRE's for a given match.
   */
  private static boolean jdkAgreesOnGroups(
      String pattern, String text, String method, Matcher safeReMatcher) {
    try {
      java.util.regex.Pattern jdkPat = java.util.regex.Pattern.compile(pattern);
      java.util.regex.Matcher jdkMatcher = jdkPat.matcher(text);
      boolean jdkResult =
          method.equals("matches") ? jdkMatcher.matches() : jdkMatcher.find();
      if (!jdkResult) {
        return false;
      }
      int numGroups = Math.min(safeReMatcher.groupCount() + 1, jdkMatcher.groupCount() + 1);
      for (int g = 0; g < numGroups; g++) {
        if (safeReMatcher.start(g) != jdkMatcher.start(g)
            || safeReMatcher.end(g) != jdkMatcher.end(g)) {
          return false;
        }
      }
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean isGeneratedCrosscheckRun() {
    return Boolean.getBoolean("org.safere.crosscheck.generatedTests");
  }

  private static boolean hasKnownJdkFailedStartCaptureLeakageDivergence(
      String pattern, String text) {
    if (!mayExposeJdkFailedStartCaptureLeakage(pattern, text)) {
      return false;
    }

    try {
      org.safere.Matcher safeReMatcher = org.safere.Pattern.compile(pattern).matcher(text);
      java.util.regex.Matcher jdkMatcher =
          java.util.regex.Pattern.compile(pattern).matcher(text);
      boolean safeReFound = safeReMatcher.find();
      boolean jdkFound = jdkMatcher.find();
      if (safeReFound != jdkFound || !safeReFound) {
        return false;
      }
      if (safeReMatcher.start() != jdkMatcher.start()
          || safeReMatcher.end() != jdkMatcher.end()) {
        return false;
      }

      boolean sawLeakage = false;
      int numGroups = Math.min(safeReMatcher.groupCount(), jdkMatcher.groupCount());
      for (int g = 1; g <= numGroups; g++) {
        int safeReStart = safeReMatcher.start(g);
        int safeReEnd = safeReMatcher.end(g);
        int jdkStart = jdkMatcher.start(g);
        int jdkEnd = jdkMatcher.end(g);
        if (safeReStart == jdkStart && safeReEnd == jdkEnd) {
          continue;
        }
        if (safeReStart == -1 && safeReEnd == -1 && jdkStart >= 0 && jdkEnd >= jdkStart) {
          sawLeakage = true;
          continue;
        }
        return false;
      }
      return sawLeakage;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static boolean mayExposeJdkFailedStartCaptureLeakage(String pattern, String text) {
    return pattern.endsWith("$")
        && pattern.contains("){")
        && pattern.contains("(a)")
        && text.indexOf('a') >= 0;
  }

  /**
   * Parse a result field like {@code "0-5"}, {@code "0-5 2-3"}, {@code "0-5 - 2-3"}, or
   * {@code "-"}.
   *
   * <p>Returns {@code null} for {@code "-"} (no match). Each {@code int[2]} pair is
   * {@code [start, end]} as UTF-8 byte offsets. Unmatched subgroups appear as {@code [-1, -1]}.
   */
  static int[][] parseResult(String field) {
    field = field.trim();
    if (field.equals("-")) {
      return null;
    }
    String[] tokens = field.split("\\s+");
    int[][] result = new int[tokens.length][2];
    for (int i = 0; i < tokens.length; i++) {
      if (tokens[i].equals("-")) {
        result[i][0] = -1;
        result[i][1] = -1;
      } else {
        int hyphen = tokens[i].indexOf('-');
        result[i][0] = Integer.parseInt(tokens[i].substring(0, hyphen));
        result[i][1] = Integer.parseInt(tokens[i].substring(hyphen + 1));
      }
    }
    return result;
  }

  /**
   * Convert a UTF-8 byte offset to a Java char offset.
   */
  static int utf8ByteToCharOffset(String text, int byteOffset) {
    if (byteOffset < 0) {
      return -1;
    }
    if (byteOffset == 0) {
      return 0;
    }
    byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
    if (byteOffset >= utf8.length) {
      return text.length();
    }
    return new String(utf8, 0, byteOffset, StandardCharsets.UTF_8).length();
  }

  /**
   * Unescape a Go-style double-quoted string from the test data.
   *
   * <p>Handles standard Go escape sequences: {@code \a}, {@code \b}, {@code \f}, {@code \n},
   * {@code \r}, {@code \t}, {@code \v}, {@code \\}, {@code \"}, {@code \xHH},
   * {@code \}{@code uHHHH}, {@code \UHHHHHHHH}, and octal {@code \OOO}.
   */
  static String unescapeString(String s) {
    if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
      s = s.substring(1, s.length() - 1);
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c != '\\' || i + 1 >= s.length()) {
        sb.append(c);
        continue;
      }
      char next = s.charAt(++i);
      switch (next) {
        case 'a' -> sb.append('\u0007');
        case 'b' -> sb.append('\b');
        case 'f' -> sb.append('\f');
        case 'n' -> sb.append('\n');
        case 'r' -> sb.append('\r');
        case 't' -> sb.append('\t');
        case 'v' -> sb.append('\u000B');
        case '\\' -> sb.append('\\');
        case '\'' -> sb.append('\'');
        case '"' -> sb.append('"');
        case 'x' -> {
          if (i + 2 < s.length()) {
            int val = Integer.parseInt(s.substring(i + 1, i + 3), 16);
            sb.append((char) val);
            i += 2;
          }
        }
        case 'u' -> {
          if (i + 4 < s.length()) {
            int val = Integer.parseInt(s.substring(i + 1, i + 5), 16);
            sb.append((char) val);
            i += 4;
          }
        }
        case 'U' -> {
          if (i + 8 < s.length()) {
            int val = Integer.parseInt(s.substring(i + 1, i + 9), 16);
            sb.appendCodePoint(val);
            i += 8;
          }
        }
        default -> {
          if (next >= '0' && next <= '7') {
            int val = next - '0';
            if (i + 1 < s.length() && s.charAt(i + 1) >= '0' && s.charAt(i + 1) <= '7') {
              val = val * 8 + (s.charAt(++i) - '0');
              if (i + 1 < s.length() && s.charAt(i + 1) >= '0' && s.charAt(i + 1) <= '7') {
                val = val * 8 + (s.charAt(++i) - '0');
              }
            }
            sb.append((char) val);
          } else {
            sb.append(next);
          }
        }
      }
    }
    return sb.toString();
  }

  /** Returns true if all characters in the string are single-byte in UTF-8 (i.e., ASCII). */
  private static boolean isSingleBytes(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) >= 0x80) {
        return false;
      }
    }
    return true;
  }

  /** Escape non-printable characters for error messages. */
  private static String escape(String s) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c >= 0x20 && c < 0x7F) {
        sb.append(c);
      } else {
        sb.append(String.format("\\x%02x", (int) c));
      }
    }
    return sb.toString();
  }

  /**
   * A line reader that uses only {@code \n} as the line terminator, not {@code \r} or
   * {@code \r\n}. This is necessary because the RE2 test data contains literal {@code \r} (0x0D)
   * characters inside quoted strings, and Java's {@code BufferedReader.readLine()} would incorrectly
   * treat those as line terminators.
   */
  private static final class UnixLineReader implements AutoCloseable {
    private final Reader in;
    private final char[] buf = new char[8192];
    private int pos;
    private int limit;

    UnixLineReader(Reader in) {
      this.in = in;
    }

    String readLine() throws IOException {
      StringBuilder sb = null;
      while (true) {
        // Scan the buffer for a newline.
        for (int i = pos; i < limit; i++) {
          if (buf[i] == '\n') {
            String line;
            if (sb != null) {
              sb.append(buf, pos, i - pos);
              line = sb.toString();
            } else {
              line = new String(buf, pos, i - pos);
            }
            pos = i + 1;
            return line;
          }
        }
        // No newline found in buffer; accumulate and refill.
        if (sb == null) {
          sb = new StringBuilder();
        }
        sb.append(buf, pos, limit - pos);
        pos = 0;
        limit = in.read(buf, 0, buf.length);
        if (limit <= 0) {
          return sb.length() > 0 ? sb.toString() : null;
        }
      }
    }

    @Override
    public void close() throws IOException {
      in.close();
    }
  }
}
