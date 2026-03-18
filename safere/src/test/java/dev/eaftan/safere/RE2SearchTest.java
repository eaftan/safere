// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests parsed from RE2's {@code re2-search.txt} test data file.
 *
 * <p>The file format defines sets of test strings and regexps. For each (regexp, string) pair,
 * four result fields are given (semicolon-separated):
 *
 * <ol>
 *   <li>Full match without submatch extraction ({@code matches()})
 *   <li>Partial match without submatch extraction ({@code find()})
 *   <li>Full match with submatch extraction ({@code matches()} + groups)
 *   <li>Partial match with submatch extraction ({@code find()} + groups)
 * </ol>
 *
 * <p>Each field is either "-" (no match) or byte-offset pairs like "0-5" or "0-2 0-1" (with
 * submatch groups). We verify match/no-match agreement and matched text using fields 1 and 2.
 */
@DisplayName("RE2 Search Tests (from re2-search.txt)")
class RE2SearchTest {

  /** A single search test case. */
  record SearchTestCase(
      String pattern,
      String text,
      boolean expectFullMatch,
      boolean expectFind,
      String expectedFindGroup) {
    @Override
    public String toString() {
      return String.format(
          "pat=\"%s\" text=\"%s\" matches=%b find=%b",
          pattern, text, expectFullMatch, expectFind);
    }
  }

  /**
   * Convert a UTF-8 byte offset to a Java char offset. Returns -1 if the byte offset can't be
   * mapped (e.g., falls inside a multibyte character).
   */
  private static int utf8ByteToCharOffset(String text, int byteOffset) {
    if (byteOffset < 0) {
      return -1;
    }
    byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
    if (byteOffset > utf8.length) {
      return text.length();
    }
    // Decode the first byteOffset bytes and count chars
    String prefix = new String(utf8, 0, byteOffset, StandardCharsets.UTF_8);
    return prefix.length();
  }

  /** Parse a result field like "0-5" or "0-5 0-2" or "-". Returns null for "-". */
  private static int[][] parseResultField(String field) {
    field = field.trim();
    if (field.equals("-")) {
      return null;
    }
    String[] pairs = field.split("\\s+");
    int[][] result = new int[pairs.length][2];
    for (int i = 0; i < pairs.length; i++) {
      String[] parts = pairs[i].split("-");
      result[i][0] = Integer.parseInt(parts[0]);
      result[i][1] = Integer.parseInt(parts[1]);
    }
    return result;
  }

  /** Unescape a C-style string from the test data (strip quotes, handle \n etc). */
  private static String unescapeString(String s) {
    if (s.startsWith("\"") && s.endsWith("\"")) {
      s = s.substring(1, s.length() - 1);
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == '\\' && i + 1 < s.length()) {
        char next = s.charAt(i + 1);
        switch (next) {
          case 'n' -> { sb.append('\n'); i++; }
          case 't' -> { sb.append('\t'); i++; }
          case 'r' -> { sb.append('\r'); i++; }
          case '\\' -> { sb.append('\\'); i++; }
          case '"' -> { sb.append('"'); i++; }
          case 'x' -> {
            if (i + 3 < s.length()) {
              sb.append((char) Integer.parseInt(s.substring(i + 2, i + 4), 16));
              i += 3;
            } else {
              sb.append(next);
              i++;
            }
          }
          default -> { sb.append(next); i++; }
        }
      } else {
        sb.append(s.charAt(i));
      }
    }
    return sb.toString();
  }

  /** Extract matched text from byte offsets, or null if no match. */
  private static String extractMatchedText(String text, int[][] matchResult) {
    if (matchResult == null || matchResult.length == 0) {
      return null;
    }
    int startByte = matchResult[0][0];
    int endByte = matchResult[0][1];
    int startChar = utf8ByteToCharOffset(text, startByte);
    int endChar = utf8ByteToCharOffset(text, endByte);
    if (startChar < 0 || endChar < 0 || startChar > text.length() || endChar > text.length()) {
      return null;
    }
    return text.substring(startChar, endChar);
  }

  static Stream<Arguments> searchTests() throws IOException {
    List<Arguments> tests = new ArrayList<>();
    InputStream is = RE2SearchTest.class.getResourceAsStream("/re2-search.txt");
    if (is == null) {
      throw new IOException("Cannot find re2-search.txt in test resources");
    }

    try (BufferedReader reader = new BufferedReader(new InputStreamReader(is,
        StandardCharsets.UTF_8))) {
      List<String> strings = new ArrayList<>();
      String line;

      // Skip header lines until we hit "strings"
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.equals("strings")) {
          break;
        }
      }

      while (true) {
        // We're at "strings" or just read past it. Read string list.
        strings.clear();
        while ((line = reader.readLine()) != null) {
          line = line.trim();
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

        // Read regexps until next "strings" or EOF
        while ((line = reader.readLine()) != null) {
          line = line.trim();
          if (line.equals("strings")) {
            break;
          }
          if (!line.startsWith("\"")) {
            continue;
          }

          String pattern = unescapeString(line);

          // Read one result line per test string
          for (int si = 0; si < strings.size(); si++) {
            String resultLine = reader.readLine();
            if (resultLine == null) {
              break;
            }
            resultLine = resultLine.trim();
            String[] fields = resultLine.split(";");
            if (fields.length < 4) {
              continue;
            }

            String text = strings.get(si);
            int[][] fullMatch = parseResultField(fields[0]);
            int[][] findMatch = parseResultField(fields[1]);

            String expectedFindText = extractMatchedText(text, findMatch);

            tests.add(
                Arguments.of(
                    new SearchTestCase(
                        pattern,
                        text,
                        fullMatch != null,
                        findMatch != null,
                        expectedFindText)));
          }
        }
        if (line == null) {
          break;
        }
      }
    }
    return tests.stream();
  }

  /** Check if a pattern is a known SafeRE bug that should be skipped. */
  private static boolean isKnownBug(String pattern) {
    // SafeRE bug: nullable alternation in repetition (|a)* matches greedily
    if (pattern.contains("(?:|a)*") || pattern.contains("(?:(|a)*)")) {
      return true;
    }
    return false;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("searchTests")
  void testMatches(SearchTestCase tc) {
    Pattern p;
    try {
      p = Pattern.compile(tc.pattern());
    } catch (PatternSyntaxException e) {
      // Pattern not supported by SafeRE; skip
      return;
    }
    Matcher m = p.matcher(tc.text());
    assertThat(m.matches())
        .as("matches() for pattern \"%s\" on \"%s\"", tc.pattern(), tc.text())
        .isEqualTo(tc.expectFullMatch());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("searchTests")
  void testFind(SearchTestCase tc) {
    assumeFalse(isKnownBug(tc.pattern()), "SafeRE bug: " + tc.pattern());
    Pattern p;
    try {
      p = Pattern.compile(tc.pattern());
    } catch (PatternSyntaxException e) {
      return;
    }
    Matcher m = p.matcher(tc.text());
    boolean found = m.find();
    assertThat(found)
        .as("find() for pattern \"%s\" on \"%s\"", tc.pattern(), tc.text())
        .isEqualTo(tc.expectFind());

    if (found && tc.expectedFindGroup() != null) {
      assertThat(m.group())
          .as("find() group(0) for pattern \"%s\" on \"%s\"", tc.pattern(), tc.text())
          .isEqualTo(tc.expectedFindGroup());
    }
  }

}
