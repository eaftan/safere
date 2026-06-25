// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

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

/** Tests the raw byte[] matcher using RE2's re2-search.txt test data. */
@DisplayName("RE2 Raw Byte Search Tests")
class RE2ByteSearchTest {

  record SearchTestCase(
      String pattern,
      String text,
      boolean expectFullMatch,
      boolean expectFind,
      String expectedFindGroup) {
    @Override
    public String toString() {
      return String.format(
          "pat=\"%s\" text=\"%s\" matches=%b find=%b", pattern, text, expectFullMatch, expectFind);
    }
  }

  private static int utf8ByteToCharOffset(String text, int byteOffset) {
    if (byteOffset < 0) {
      return -1;
    }
    byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
    if (byteOffset > utf8.length) {
      return text.length();
    }
    String prefix = new String(utf8, 0, byteOffset, StandardCharsets.UTF_8);
    return prefix.length();
  }

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

  private static String unescapeString(String s) {
    if (s.startsWith("\"") && s.endsWith("\"")) {
      s = s.substring(1, s.length() - 1);
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == '\\' && i + 1 < s.length()) {
        char next = s.charAt(i + 1);
        switch (next) {
          case 'n' -> {
            sb.append('\n');
            i++;
          }
          case 't' -> {
            sb.append('\t');
            i++;
          }
          case 'r' -> {
            sb.append('\r');
            i++;
          }
          case '\\' -> {
            sb.append('\\');
            i++;
          }
          case '"' -> {
            sb.append('"');
            i++;
          }
          case 'x' -> {
            if (i + 3 < s.length()) {
              sb.append((char) Integer.parseInt(s.substring(i + 2, i + 4), 16));
              i += 3;
            } else {
              sb.append(next);
              i++;
            }
          }
          default -> {
            sb.append(next);
            i++;
          }
        }
      } else {
        sb.append(s.charAt(i));
      }
    }
    return sb.toString();
  }

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

  private static boolean hasRe2NonZeroNumericEscape(String pattern) {
    int backslashes = 0;
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '\\') {
        backslashes++;
        continue;
      }
      if (backslashes % 2 == 1 && c >= '1' && c <= '9') {
        return true;
      }
      backslashes = 0;
    }
    return false;
  }

  static Stream<Arguments> searchTests() throws IOException {
    List<Arguments> tests = new ArrayList<>();
    InputStream is = RE2ByteSearchTest.class.getResourceAsStream("/re2-search.txt");
    if (is == null) {
      throw new IOException("Cannot find re2-search.txt in test resources");
    }

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
      List<String> strings = new ArrayList<>();
      String line;

      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.equals("strings")) {
          break;
        }
      }

      while (true) {
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

        while ((line = reader.readLine()) != null) {
          line = line.trim();
          if (line.equals("strings")) {
            break;
          }
          if (!line.startsWith("\"")) {
            continue;
          }

          String pattern = unescapeString(line);

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
                        pattern, text, fullMatch != null, findMatch != null, expectedFindText)));
          }
        }
        if (line == null) {
          break;
        }
      }
    }
    return tests.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("searchTests")
  void testMatches(SearchTestCase tc) {
    if (hasRe2NonZeroNumericEscape(tc.pattern())) {
      return;
    }
    Pattern p;
    try {
      p = Pattern.compile(tc.pattern());
    } catch (PatternSyntaxException e) {
      return;
    }

    byte[] bytes = tc.text().getBytes(StandardCharsets.UTF_8);
    Matcher m = p.matcher(bytes);
    assertThat(m.matches())
        .as(
            "matches() for pattern \"%s\" on byte representation of \"%s\"",
            tc.pattern(), tc.text())
        .isEqualTo(tc.expectFullMatch());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("searchTests")
  void testFind(SearchTestCase tc) {
    if (hasRe2NonZeroNumericEscape(tc.pattern())) {
      return;
    }
    Pattern p;
    try {
      p = Pattern.compile(tc.pattern());
    } catch (PatternSyntaxException e) {
      return;
    }

    byte[] bytes = tc.text().getBytes(StandardCharsets.UTF_8);
    Matcher m = p.matcher(bytes);
    boolean found = m.find();
    assertThat(found)
        .as("find() for pattern \"%s\" on byte representation of \"%s\"", tc.pattern(), tc.text())
        .isEqualTo(tc.expectFind());

    if (found && tc.expectedFindGroup() != null) {
      // Test string group access on byte matcher
      assertThat(m.group())
          .as(
              "find() group() for pattern \"%s\" on byte representation of \"%s\"",
              tc.pattern(), tc.text())
          .isEqualTo(tc.expectedFindGroup());

      // Test zero-copy byte group access on byte matcher
      byte[] expectedBytes = tc.expectedFindGroup().getBytes(StandardCharsets.UTF_8);
      assertThat(m.groupBytes())
          .as(
              "find() groupBytes() for pattern \"%s\" on byte representation of \"%s\"",
              tc.pattern(), tc.text())
          .isEqualTo(expectedBytes);
    }
  }

  @org.junit.jupiter.api.Test
  void testWordBoundaryDiscrepancy() {
    Pattern p = Pattern.compile("(?:(?:(?:\\b).)*)");
    byte[] bytes = "aa ".getBytes(StandardCharsets.UTF_8);
    Matcher m = p.matcher(bytes);
    List<String> matches = new ArrayList<>();
    while (m.find()) {
      matches.add(String.format("[%d,%d)", m.start(), m.end()));
    }
    assertThat(matches).containsExactly("[0,1)", "[1,1)", "[2,3)", "[3,3)");
  }

  @org.junit.jupiter.api.Test
  void testWordBoundaryDiscrepancyString() {
    Pattern p = Pattern.compile("(?:(?:(?:\\b).)*)");
    Matcher m = p.matcher("aa ");
    List<String> matches = new ArrayList<>();
    while (m.find()) {
      matches.add(String.format("[%d,%d)", m.start(), m.end()));
    }
    assertThat(matches).containsExactly("[0,1)", "[1,1)", "[2,3)", "[3,3)");
  }

  @org.junit.jupiter.api.Test
  void testDollarNewlineDiscrepancy() {
    Pattern p = Pattern.compile("(?:(?:(?:$)\n))$");
    byte[] bytes =
        "####################################################################################################################################################################################################################################################################bb\n"
            .getBytes(StandardCharsets.UTF_8);
    Matcher m = p.matcher(bytes);
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(262);
    assertThat(m.end()).isEqualTo(263);
  }

  @org.junit.jupiter.api.Test
  void testDollarNewlineDiscrepancyString() {
    Pattern p = Pattern.compile("(?:(?:(?:$)\n))$");
    String text =
        "####################################################################################################################################################################################################################################################################bb\n";
    Matcher m = p.matcher(text);
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(262);
    assertThat(m.end()).isEqualTo(263);
  }

  @org.junit.jupiter.api.Test
  void testUnixLinesDollarTrailingCr() {
    Pattern p = Pattern.compile("a$", Pattern.UNIX_LINES);
    byte[] bytes = "a\r".getBytes(StandardCharsets.UTF_8);
    Matcher m = p.matcher(bytes);
    assertThat(m.find()).isFalse();
  }

  @org.junit.jupiter.api.Test
  void testGraphemePatternByteMode() {
    Pattern p = Pattern.compile("a\\X");
    byte[] bytes = "ab".getBytes(StandardCharsets.UTF_8);
    Matcher m = p.matcher(bytes);
    var unused =
        org.junit.jupiter.api.Assertions.assertThrows(
            UnsupportedOperationException.class, () -> m.find());
  }

  @org.junit.jupiter.api.Test
  void testUnicodeWordBoundaryByteMode() {
    Pattern p = Pattern.compile("\\ba", Pattern.UNICODE_CHARACTER_CLASS);
    byte[] bytes = "ab".getBytes(StandardCharsets.UTF_8);
    Matcher m = p.matcher(bytes);
    var unused2 =
        org.junit.jupiter.api.Assertions.assertThrows(
            UnsupportedOperationException.class, () -> m.find());
  }
}
