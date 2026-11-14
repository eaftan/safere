// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@DisabledForCrosscheck("compares UTF-8 diagnostics and the forced fallback path with the JDK")
class LiteralAlternationUtf8Test {
  private static final EnginePathOptions NO_LITERAL_FAST_PATHS =
      EnginePathOptions.builder().literalFastPaths(false).build();

  private static final List<String> PATTERNS =
      List.of(
          "apple|banana|cherry",
          "APPLE|BANANA|CHERRY",
          "blossom|sparkling|gardens",
          "foo|barfoo",
          "ab|bcd",
          "ab|cherry|abc",
          "abc|cherry|ab",
          "red|green|blue|yellow|orange|purple",
          "ALPHA|BETA|GAMMA|DELTA|EPSILON|WXYZ",
          "north|south|east|west|upper|lower",
          "north|south|east|west|upper|lower|front|back",
          "@root|3value|abc|north|south|west");

  // Dense fillers switch the UTF-8 search to per-literal scans; sparse ones keep the first-byte
  // hops.
  private static final List<String> FILLERS =
      List.of("abc rgb Abc ", "x", "é", "日本", "😀", "the quick brown fox ");

  private final List<OperationDiagnostics> operations = new ArrayList<>();
  private final SafeReMatchDiagnostics recorder =
      new SafeReMatchDiagnostics() {
        @Override
        public void onOperationCompleted(OperationDiagnostics event) {
          operations.add(event);
        }
      };

  @AfterEach
  void disableDiagnostics() {
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
  }

  @Test
  void findAgreesWithJdkStringAndFallback() {
    for (String regex : PATTERNS) {
      Pattern pattern = Pattern.compile(regex);
      Pattern fallback = Pattern.compile(regex, 0, NO_LITERAL_FAST_PATHS);
      assertThat(pattern.preparedMatchRunner(false))
          .as(regex)
          .isInstanceOf(Matcher.LiteralAlternationPreparedRunner.class);
      for (String filler : FILLERS) {
        for (int repeat : new int[] {0, 1, 7, 128, 1_000, 3_000}) {
          for (String text :
              List.of(
                  filler.repeat(repeat),
                  filler.repeat(repeat) + "barfoo banana gardens abc red 😀éclair apple",
                  filler.repeat(repeat) + "APPLE Banana cherrY abd",
                  "cherry" + filler.repeat(repeat) + "abc",
                  filler.repeat(repeat) + "ab" + filler.repeat(repeat) + "cherry")) {
            List<String> expected = jdkFinds(regex, text);
            assertThat(utf8Finds(pattern, text)).as("/%s/ %s", regex, filler).isEqualTo(expected);
            assertThat(utf8Finds(fallback, text)).isEqualTo(expected);
            assertThat(stringFinds(pattern, text)).isEqualTo(expected);
            // The matcher-free existence check uses the same search.
            assertThat(pattern.find(Utf8Input.validated(text.getBytes(UTF_8))))
                .isEqualTo(!expected.isEmpty());
          }
        }
      }
    }
  }

  @Test
  void windowBoundariesPreserveLeftmostMatchAndAlternativePriority() {
    for (String regex :
        List.of(
            "ab|cherry|abc",
            "abc|cherry|ab",
            "abc|bcd|cde",
            "cherry|banana|apple",
            "ab|north|south|east|west|abc",
            "abc|north|south|east|west|ab")) {
      Pattern pattern = Pattern.compile(regex);
      for (int boundary : new int[] {1_024, 3_072, 4_096, 5_120, 7_168, 15_360, 31_744}) {
        for (int delta = -14; delta <= 2; delta++) {
          // Dense prefix of first bytes that matches none of the patterns, so the UTF-8 search
          // runs and the first find reaches the boundary; then filler with no first bytes.
          String prefix = "a b c ".repeat(32);
          String text =
              prefix
                  + "é".repeat((boundary - prefix.length() + delta) / 2)
                  + "abcde"
                  + "x".repeat(70_000)
                  + "cherry";
          assertThat(utf8Finds(pattern, text))
              .as("/%s/ boundary %s%+d", regex, boundary, delta)
              .isEqualTo(jdkFinds(regex, text));
        }
      }
    }
  }

  @Test
  void sparseManyInitialsPreservePriorityAndByteOffsetsAcrossBoundaries() {
    for (String regex :
        List.of(
            "ab|north|south|east|west|abc",
            "abc|north|south|east|west|ab",
            "north|south|east|west|upper|lower|front|back")) {
      Pattern pattern = Pattern.compile(regex);
      for (String padding : List.of("z", "é", "😀")) {
        for (int repeat : new int[] {255, 256, 257, 511, 512, 1_024}) {
          String text = padding.repeat(repeat) + "abc lower back north" + padding.repeat(2_048);
          assertThat(utf8Finds(pattern, text))
              .as("/%s/ %s x %s", regex, padding, repeat)
              .isEqualTo(jdkFinds(regex, text));
          assertThat(pattern.find(Utf8Input.validated(text.getBytes(UTF_8)))).isTrue();
        }
      }
    }
  }

  @Test
  void firstFindOnDenseInputReportsLiteralAlternation() {
    Pattern.setDiagnostics(recorder);
    for (String regex : List.of("apple|banana|cherry", "blossom|sparkling|gardens")) {
      Pattern pattern = Pattern.compile(regex);
      String text = "a bright sunlit cobbled street ".repeat(64) + "cherry gardens";
      operations.clear();
      Utf8Matcher utf8 = pattern.matcher(Utf8Input.validated(text.getBytes(UTF_8)));
      assertThat(utf8.find()).isTrue();
      OperationDiagnostics utf8Event = operations.getLast();
      operations.clear();
      assertThat(pattern.find(Utf8Input.validated(text.getBytes(UTF_8)))).isTrue();
      OperationDiagnostics matcherFreeEvent = operations.getLast();
      operations.clear();
      assertThat(pattern.matcher(text).find()).isTrue();
      OperationDiagnostics stringEvent = operations.getLast();
      if (VectorScanProviders.vectorProviderAvailable()) {
        // The vector multi-literal start search is faster than one byte scan per literal.
        assertThat(utf8Event.boundaryStrategy())
            .as(regex)
            .isNotEqualTo(MatchStrategy.LITERAL_ALTERNATION);
      } else {
        assertThat(utf8Event.boundaryStrategy())
            .as(regex)
            .isEqualTo(MatchStrategy.LITERAL_ALTERNATION)
            .isEqualTo(stringEvent.boundaryStrategy());
        assertThat(matcherFreeEvent.boundaryStrategy())
            .isEqualTo(MatchStrategy.LITERAL_ALTERNATION);
        assertThat(utf8Event.forwardDfaSearchCount()).isZero();
      }
    }
  }

  @Test
  void defaultProviderUsesLiteralSearchForManyFirstBytes() {
    Pattern.setDiagnostics(recorder);
    for (String[] regexAndText :
        new String[][] {
          {"APPLE|BANANA|CHERRY", "the quick brown fox ".repeat(64) + "CHERRY"},
          {"apple|banana|cherry", "日本語のテキスト".repeat(64) + "cherry"},
          {"red|green|blue|yellow|orange|purple", "日本語".repeat(128) + "purple"},
        }) {
      Pattern pattern = Pattern.compile(regexAndText[0]);
      operations.clear();
      assertThat(pattern.matcher(Utf8Input.validated(regexAndText[1].getBytes(UTF_8))).find())
          .isTrue();
      boolean literalSearch = !VectorScanProviders.vectorProviderAvailable();
      assertThat(operations.getLast().boundaryStrategy() == MatchStrategy.LITERAL_ALTERNATION)
          .as(regexAndText[0])
          .isEqualTo(literalSearch);
    }
  }

  @Test
  void manyInitialsKeepTheFallbackForTinyDenseAndWideRangeInputs() {
    Pattern.setDiagnostics(recorder);
    for (String[] regexAndText :
        new String[][] {
          {"red|green|blue|yellow|orange|purple", "purple"},
          {"red|green|blue|yellow|orange|purple", "rgbyop ".repeat(64) + "purple"},
          {"@root|3value|abc|north|south|west", "x".repeat(512) + "west"}
        }) {
      operations.clear();
      Pattern pattern = Pattern.compile(regexAndText[0]);
      assertThat(pattern.matcher(Utf8Input.validated(regexAndText[1].getBytes(UTF_8))).find())
          .isTrue();
      assertThat(operations.getLast().boundaryStrategy())
          .as(regexAndText[0])
          .isNotEqualTo(MatchStrategy.LITERAL_ALTERNATION);
    }
  }

  @Test
  void regionsUseByteBounds() {
    for (String regex :
        List.of("apple|banana|cherry", "foo|barfoo", "ab|cherry|abc", "abc|cherry|ab")) {
      Pattern pattern = Pattern.compile(regex);
      for (String padding : List.of("abc ", "x", "é", "😀")) {
        String text = padding.repeat(300) + "barfoo banana apple abc cherry" + padding;
        byte[] bytes = text.getBytes(UTF_8);
        int middle = padding.repeat(300).length() + 3;
        for (int startChar : new int[] {0, 1, middle}) {
          for (int endChar : new int[] {text.length() - 1, middle + 10, middle}) {
            if (startChar > endChar || !isBoundary(text, startChar) || !isBoundary(text, endChar)) {
              continue;
            }
            int startByte = byteOffset(text, startChar);
            int endByte = byteOffset(text, endChar);
            java.util.regex.Matcher expected =
                java.util.regex.Pattern.compile(regex).matcher(text).region(startChar, endChar);
            Utf8Matcher actual =
                pattern.matcher(Utf8Input.validated(bytes)).region(startByte, endByte);
            while (expected.find()) {
              assertThat(actual.find()).as("/%s/ region %s", regex, startChar).isTrue();
              assertThat(actual.start()).isEqualTo(byteOffset(text, expected.start()));
              assertThat(actual.end()).isEqualTo(byteOffset(text, expected.end()));
            }
            assertThat(actual.find()).isFalse();
          }
        }
      }
    }
  }

  @Test
  void malformedTrustedInputAgreesWithFallback() {
    byte[] garbage = {(byte) 0x80, 'a', (byte) 0xED, (byte) 0xA0, (byte) 0x80, (byte) 0xC3, 'b'};
    for (String regex :
        List.of(
            "apple|banana|cherry",
            "ab|cherry|abc",
            "abc|cherry|ab",
            "abc|north|south|east|west|ab")) {
      Pattern pattern = Pattern.compile(regex);
      Pattern fallback = Pattern.compile(regex, 0, NO_LITERAL_FAST_PATHS);
      for (String literal : List.of("", "apple", "abc", "cherry")) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 64; i++) {
          out.writeBytes(garbage);
          out.writeBytes("abc ".getBytes(UTF_8));
        }
        out.writeBytes(literal.getBytes(UTF_8));
        out.writeBytes(garbage);
        byte[] bytes = out.toByteArray();
        assertThat(bytesFinds(pattern, bytes))
            .as("/%s/ + %s", regex, literal)
            .isEqualTo(bytesFinds(fallback, bytes));
      }
    }
  }

  @Test
  void fullAndPrefixMatchesAgreeWithJdk() {
    for (String regex : PATTERNS) {
      Pattern pattern = Pattern.compile(regex);
      for (String text : List.of("", "apple", "APPLE", "Apple", "banana", "abc", "ab", "abcé")) {
        java.util.regex.Matcher expected = java.util.regex.Pattern.compile(regex).matcher(text);
        Utf8Matcher actual = pattern.matcher(Utf8Input.validated(text.getBytes(UTF_8)));
        assertThat(actual.matches()).as("/%s/ %s", regex, text).isEqualTo(expected.matches());
        assertThat(actual.lookingAt()).isEqualTo(expected.lookingAt());
      }
    }
  }

  private static List<String> jdkFinds(String regex, String text) {
    List<String> finds = new ArrayList<>();
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex).matcher(text);
    while (matcher.find()) {
      finds.add(byteOffset(text, matcher.start()) + "-" + byteOffset(text, matcher.end()));
    }
    return finds;
  }

  private static List<String> utf8Finds(Pattern pattern, String text) {
    return bytesFinds(pattern, text.getBytes(UTF_8));
  }

  private static List<String> bytesFinds(Pattern pattern, byte[] bytes) {
    List<String> finds = new ArrayList<>();
    Utf8Matcher matcher = pattern.matcher(Utf8Input.trusted(bytes));
    while (matcher.find()) {
      finds.add(matcher.start() + "-" + matcher.end());
    }
    return finds;
  }

  private static List<String> stringFinds(Pattern pattern, String text) {
    List<String> finds = new ArrayList<>();
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      finds.add(byteOffset(text, matcher.start()) + "-" + byteOffset(text, matcher.end()));
    }
    return finds;
  }

  private static boolean isBoundary(String text, int index) {
    return index == 0 || index == text.length() || !Character.isLowSurrogate(text.charAt(index));
  }

  private static int byteOffset(String text, int charIndex) {
    return text.substring(0, charIndex).getBytes(UTF_8).length;
  }
}
