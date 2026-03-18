// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for find/match functionality, ported from RE2/J's FindTest.
 *
 * <p>The original RE2/J test data uses UTF-8 byte offsets. Since SafeRE operates on Java chars
 * (UTF-16), we verify matches by comparing matched text ({@code group()}) rather than raw offsets.
 * For ASCII-only test strings, char offsets and byte offsets are identical.
 */
@DisplayName("RE2 Find Tests (ported from RE2/J)")
class RE2FindTest {

  /**
   * A single find test case. {@code matches} contains the expected matches: each element is an
   * array of (start, end) UTF-8 byte-offset pairs for group 0, group 1, etc. A value of -1 means
   * the group did not participate.
   */
  record FindTestCase(String pattern, String text, int[][] matches) {
    @Override
    public String toString() {
      return String.format("pat=\"%s\" text=\"%s\" nMatch=%d", pattern, text, matches.length);
    }
  }

  /**
   * Build a FindTestCase. {@code n} is the number of matches. {@code x} is a flat array of
   * (start, end) pairs in UTF-8 byte offsets. Each match has {@code x.length / n} values
   * (group 0 start, group 0 end, group 1 start, group 1 end, ...).
   */
  private static FindTestCase tc(String pat, String text, int n, int... x) {
    int[][] matches = new int[n][];
    if (n > 0) {
      int runLength = x.length / n;
      for (int j = 0, i = 0; i < n; i++) {
        matches[i] = new int[runLength];
        System.arraycopy(x, j, matches[i], 0, runLength);
        j += runLength;
      }
    }
    return new FindTestCase(pat, text, matches);
  }

  /**
   * Convert a UTF-8 byte offset to a Java char (UTF-16) offset within the given string. Returns -1
   * if the input offset is -1 (meaning "no match").
   */
  private static int utf8ByteOffsetToCharOffset(String text, int byteOffset) {
    if (byteOffset < 0) {
      return -1;
    }
    byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
    int charOffset = 0;
    int b = 0;
    while (b < byteOffset && charOffset < text.length()) {
      int cp = text.codePointAt(charOffset);
      int cpUtf8Len = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
      b += cpUtf8Len;
      charOffset += Character.charCount(cp);
    }
    return charOffset;
  }

  /** Extract the expected matched text for match i, group j, from a FindTestCase. */
  private static String expectedGroupText(FindTestCase tc, int matchIdx, int groupIdx) {
    int[] match = tc.matches()[matchIdx];
    int startByte = match[2 * groupIdx];
    int endByte = match[2 * groupIdx + 1];
    if (startByte < 0 || endByte < 0) {
      return null; // group did not participate
    }
    int startChar = utf8ByteOffsetToCharOffset(tc.text(), startByte);
    int endChar = utf8ByteOffsetToCharOffset(tc.text(), endByte);
    return tc.text().substring(startChar, endChar);
  }

  // All test data from RE2/J FindTest.FIND_TESTS.
  // Each entry: tc(pattern, text, numMatches, start0, end0, [start1, end1, ...], ...)
  // Offsets are UTF-8 byte positions (converted to char positions during verification).
  static Stream<Arguments> findTests() {
    return Stream.of(
        Arguments.of(tc("", "", 1, 0, 0)),
        Arguments.of(tc("^abcdefg", "abcdefg", 1, 0, 7)),
        Arguments.of(tc("a+", "baaab", 1, 1, 4)),
        Arguments.of(tc("abcd..", "abcdef", 1, 0, 6)),
        Arguments.of(tc("a", "a", 1, 0, 1)),
        Arguments.of(tc("x", "y", 0)),
        Arguments.of(tc("b", "abc", 1, 1, 2)),
        Arguments.of(tc(".", "a", 1, 0, 1)),
        Arguments.of(tc(".*", "abcdef", 1, 0, 6)),
        Arguments.of(tc("^", "abcde", 1, 0, 0)),
        Arguments.of(tc("$", "abcde", 1, 5, 5)),
        Arguments.of(tc("^abcd$", "abcd", 1, 0, 4)),
        Arguments.of(tc("^bcd'", "abcdef", 0)),
        Arguments.of(tc("^abcd$", "abcde", 0)),
        Arguments.of(tc("a+", "baaab", 1, 1, 4)),
        Arguments.of(tc("a*", "baaab", 3, 0, 0, 1, 4, 5, 5)),
        Arguments.of(tc("[a-z]+", "abcd", 1, 0, 4)),
        Arguments.of(tc("[^a-z]+", "ab1234cd", 1, 2, 6)),
        Arguments.of(tc("[a\\-\\]z]+", "az]-bcz", 2, 0, 4, 6, 7)),
        Arguments.of(tc("[^\\n]+", "abcd\n", 1, 0, 4)),
        // Japanese characters: "日本語日本語" is 6 chars in Java, 18 bytes in UTF-8
        Arguments.of(tc("[日本語]+", "日本語日本語", 1, 0, 18)),
        // "日本語" is 3 chars, 9 bytes
        Arguments.of(tc("日本語+", "日本語", 1, 0, 9)),
        // "日本語語語語" is 6 chars, 18 bytes
        Arguments.of(tc("日本語+", "日本語語語語", 1, 0, 18)),
        // Capture group tests
        Arguments.of(tc("()", "", 1, 0, 0, 0, 0)),
        Arguments.of(tc("(a)", "a", 1, 0, 1, 0, 1)),
        // "日a": 日 = 3 bytes, a = 1 byte → total 4 bytes
        Arguments.of(tc("(.)(.)", "日a", 1, 0, 4, 0, 3, 3, 4)),
        Arguments.of(tc("(.*)", "", 1, 0, 0, 0, 0)),
        Arguments.of(tc("(.*)", "abcd", 1, 0, 4, 0, 4)),
        Arguments.of(tc("(..)(..)", "abcd", 1, 0, 4, 0, 2, 2, 4)),
        Arguments.of(tc("(([^xyz]*)(d))", "abcd", 1, 0, 4, 0, 4, 0, 3, 3, 4)),
        Arguments.of(tc("((a|b|c)*(d))", "abcd", 1, 0, 4, 0, 4, 2, 3, 3, 4)),
        Arguments.of(tc("(((a|b|c)*)(d))", "abcd", 1, 0, 4, 0, 4, 0, 3, 2, 3, 3, 4)),
        Arguments.of(tc("\\a\\f\\n\\r\\t\\v", "\007\f\n\r\t\013", 1, 0, 6)),
        Arguments.of(tc("[\\a\\f\\n\\r\\t\\v]+", "\007\f\n\r\t\013", 1, 0, 6)),
        Arguments.of(tc("a*(|(b))c*", "aacc", 1, 0, 4, 2, 2, -1, -1)),
        Arguments.of(tc("(.*).*", "ab", 1, 0, 2, 0, 2)),
        Arguments.of(tc("[.]", ".", 1, 0, 1)),
        Arguments.of(tc("/$", "/abc/", 1, 4, 5)),
        Arguments.of(tc("/$", "/abc", 0)),

        // multiple matches
        Arguments.of(tc(".", "abc", 3, 0, 1, 1, 2, 2, 3)),
        Arguments.of(tc("(.)", "abc", 3, 0, 1, 0, 1, 1, 2, 1, 2, 2, 3, 2, 3)),
        Arguments.of(tc(".(.)", "abcd", 2, 0, 2, 1, 2, 2, 4, 3, 4)),
        Arguments.of(tc("ab*", "abbaab", 3, 0, 3, 3, 4, 4, 6)),
        Arguments.of(tc("a(b*)", "abbaab", 3, 0, 3, 1, 3, 3, 4, 4, 4, 4, 6, 5, 6)),

        // fixed bugs
        Arguments.of(tc("ab$", "cab", 1, 1, 3)),
        Arguments.of(tc("axxb$", "axxcb", 0)),
        Arguments.of(tc("data", "daXY data", 1, 5, 9)),
        Arguments.of(tc("da(.)a$", "daXY data", 1, 5, 9, 7, 8)),
        Arguments.of(tc("zx+", "zzx", 1, 1, 3)),
        Arguments.of(tc("ab$", "abcab", 1, 3, 5)),
        Arguments.of(tc("(aa)*$", "a", 1, 1, 1, -1, -1)),
        Arguments.of(tc("(?:.|(?:.a))", "", 0)),
        Arguments.of(tc("(?:A(?:A|a))", "Aa", 1, 0, 2)),
        Arguments.of(tc("(?:A|(?:A|a))", "a", 1, 0, 1)),
        Arguments.of(tc("(a){0}", "", 1, 0, 0, -1, -1)),
        Arguments.of(tc("(?-s)(?:(?:^).)", "\n", 0)),
        Arguments.of(tc("(?s)(?:(?:^).)", "\n", 1, 0, 1)),
        Arguments.of(tc("(?:(?:^).)", "\n", 0)),
        // word boundary tests
        Arguments.of(tc("\\b", "x", 2, 0, 0, 1, 1)),
        Arguments.of(tc("\\b", "xx", 2, 0, 0, 2, 2)),
        Arguments.of(tc("\\b", "x y", 4, 0, 0, 1, 1, 2, 2, 3, 3)),
        Arguments.of(tc("\\b", "xx yy", 4, 0, 0, 2, 2, 3, 3, 5, 5)),
        Arguments.of(tc("\\B", "x", 0)),
        Arguments.of(tc("\\B", "xx", 1, 1, 1)),
        Arguments.of(tc("\\B", "x y", 0)),
        Arguments.of(tc("\\B", "xx yy", 2, 1, 1, 4, 4)),

        // RE2 tests
        Arguments.of(tc("[^\\S\\s]", "abcd", 0)),
        Arguments.of(tc("[^\\S[:space:]]", "abcd", 0)),
        Arguments.of(tc("[^\\D\\d]", "abcd", 0)),
        Arguments.of(tc("[^\\D[:digit:]]", "abcd", 0)),
        Arguments.of(tc("(?i)\\W", "x", 0)),
        Arguments.of(tc("(?i)\\W", "k", 0)),
        Arguments.of(tc("(?i)\\W", "s", 0)),

        // can backslash-escape any punctuation
        Arguments.of(
            tc(
                "\\!\\\"\\#\\$\\%\\&\\'\\(\\)\\*\\+\\,\\-\\.\\/\\:\\;\\<\\=\\>\\?\\@\\[\\\\"
                    + "\\]\\^\\_\\{\\|\\}\\~",
                "!\"#$%&'()*+,-./:;<=>?@[\\]^_{|}~",
                1,
                0,
                31)),
        Arguments.of(
            tc(
                "[\\!\\\"\\#\\$\\%\\&\\'\\(\\)\\*\\+\\,\\-\\.\\/\\:\\;\\<\\=\\>\\?\\@\\\\"
                    + "\\[\\\\\\]\\^\\_\\{\\|\\}\\~]+",
                "!\"#$%&'()*+,-./:;<=>?@[\\]^_{|}~",
                1,
                0,
                31)),
        Arguments.of(tc("\\`", "`", 1, 0, 1)),
        Arguments.of(tc("[\\`]+", "`", 1, 0, 1)),

        // long set of matches
        Arguments.of(
            tc(
                ".",
                "qwertyuiopasdfghjklzxcvbnm1234567890",
                36,
                0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12,
                12, 13, 13, 14, 14, 15, 15, 16, 16, 17, 17, 18, 18, 19, 19, 20, 20, 21, 21, 22,
                22, 23, 23, 24, 24, 25, 25, 26, 26, 27, 27, 28, 28, 29, 29, 30, 30, 31, 31, 32,
                32, 33, 33, 34, 34, 35, 35, 36)),
        Arguments.of(tc("(|a)*", "aa", 3, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("findTests")
  void testFirstMatchSubgroups(FindTestCase tc) {
    Pattern p = Pattern.compile(tc.pattern());
    Matcher m = p.matcher(tc.text());

    if (tc.matches().length == 0) {
      return; // no matches expected; testFindFirst handles this
    }

    assertThat(m.find()).as("find() for \"%s\" on \"%s\"", tc.pattern(), tc.text()).isTrue();
    int numGroups = tc.matches()[0].length / 2;

    for (int g = 0; g < numGroups && g <= m.groupCount(); g++) {
      String expectedText = expectedGroupText(tc, 0, g);
      assertThat(m.group(g))
          .as("group(%d) for pattern \"%s\"", g, tc.pattern())
          .isEqualTo(expectedText);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("findTests")
  void testFirstMatchPositions(FindTestCase tc) {
    Pattern p = Pattern.compile(tc.pattern());
    Matcher m = p.matcher(tc.text());

    if (tc.matches().length == 0) {
      return; // no matches expected
    }

    assertThat(m.find()).as("find() for \"%s\" on \"%s\"", tc.pattern(), tc.text()).isTrue();
    int numGroups = tc.matches()[0].length / 2;

    for (int g = 0; g < numGroups && g <= m.groupCount(); g++) {
      int expectedStartByte = tc.matches()[0][2 * g];
      int expectedEndByte = tc.matches()[0][2 * g + 1];
      int expectedStartChar = utf8ByteOffsetToCharOffset(tc.text(), expectedStartByte);
      int expectedEndChar = utf8ByteOffsetToCharOffset(tc.text(), expectedEndByte);

      assertThat(m.start(g))
          .as("start(%d) for pattern \"%s\"", g, tc.pattern())
          .isEqualTo(expectedStartChar);
      assertThat(m.end(g))
          .as("end(%d) for pattern \"%s\"", g, tc.pattern())
          .isEqualTo(expectedEndChar);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("findTests")
  void testFindFirst(FindTestCase tc) {
    Pattern p = Pattern.compile(tc.pattern());
    Matcher m = p.matcher(tc.text());

    if (tc.matches().length == 0) {
      assertThat(m.find()).as("find() should fail for \"%s\" on \"%s\"", tc.pattern(), tc.text())
          .isFalse();
    } else {
      assertThat(m.find()).as("find() should succeed for \"%s\" on \"%s\"", tc.pattern(), tc.text())
          .isTrue();
      String expectedText = expectedGroupText(tc, 0, 0);
      assertThat(m.group()).isEqualTo(expectedText);
    }
  }
}
