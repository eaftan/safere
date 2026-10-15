// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.safere.Matcher;
import org.safere.Pattern;
import org.safere.Utf8Input;
import org.safere.Utf8Matcher;

/** Unicode 17 rule-based grapheme audit; the historical name preserves saved fuzz seeds. */
public final class EmojiZwjGraphemeFuzzer {
  // Preserve the original choices first, then add every GCB/InCB/ExtPict overlap.
  private static final int[] ALPHABET = alphabet();

  private static int[] alphabet() {
    int[] original = {
      0x1F469, 0x1F44D, 0x0301, 0xFE0F, 0x1F3FD, 0x0903, 0x200D,
      0x0600, 0x110BD, 0x1F1E6, 0x1F1E7, 0x000D, 0x000A, 0x0061
    };
    return IntStream.concat(
            Arrays.stream(original), Arrays.stream(GraphemeReference.representatives()))
        .distinct()
        .toArray();
  }

  private static final List<Pattern> PATTERNS =
      List.of(
          Pattern.compile("\\X"),
          Pattern.compile("\\X\\b{g}"),
          Pattern.compile("(\\X)"),
          Pattern.compile("\\b{g}\\X\\b{g}"));
  private static final Pattern QUANTIFIED = Pattern.compile("\\A(\\X)*\\z");
  private static final Pattern FIRST_AND_REST = Pattern.compile("\\A(\\X)((?:\\X)*)\\z");
  private static final Pattern BOUNDARY = Pattern.compile("\\b{g}");
  private static final Pattern SCALAR_THEN_BOUNDARY = Pattern.compile("(?s).\\b{g}");
  private static final Pattern BOUNDARY_THEN_SCALAR = Pattern.compile("(?s)\\b{g}.");

  @FuzzTest(maxDuration = "30s")
  void emojiZwjGrapheme(FuzzedDataProvider data) {
    byte[] choices = data.consumeBytes(128);
    int[] codePoints = new int[choices.length];
    for (int i = 0; i < choices.length; i++) {
      codePoints[i] = ALPHABET[Byte.toUnsignedInt(choices[i]) % ALPHABET.length];
    }
    assertSegmentation(codePoints);
  }

  @Test
  void exhaustiveShortSequences() {
    // Check rule ordering deterministically as well as fuzzing longer combinations.
    assertSegmentation(new int[0]);
    for (int first : GraphemeReference.representatives()) {
      assertSegmentation(new int[] {first});
      for (int second : GraphemeReference.representatives()) {
        assertSegmentation(new int[] {first, second});
        for (int third : GraphemeReference.representatives()) {
          assertSegmentation(new int[] {first, second, third});
          for (int fourth : GraphemeReference.representatives()) {
            assertSegmentation(new int[] {first, second, third, fourth});
          }
        }
      }
    }
  }

  @Test
  void generatedLongerSequences() {
    Random random = new Random(954);
    for (int sample = 0; sample < 2_000; sample++) {
      int[] cps = new int[random.nextInt(4, 129)];
      for (int i = 0; i < cps.length; i++) {
        cps[i] = ALPHABET[random.nextInt(ALPHABET.length)];
      }
      assertSegmentation(cps);
    }
  }

  private static void assertSegmentation(int[] codePoints) {
    String input = new String(codePoints, 0, codePoints.length);
    List<String> expected = GraphemeReference.clusters(codePoints);
    for (Pattern pattern : PATTERNS) {
      Matcher matcher = pattern.matcher(input);
      List<String> actual = new ArrayList<>();
      while (matcher.find()) {
        actual.add(matcher.group());
      }
      check(pattern.pattern(), codePoints, actual, expected);
      byte[] bytes = input.getBytes(UTF_8);
      Utf8Matcher utf8 = pattern.matcher(Utf8Input.validated(bytes));
      actual.clear();
      while (utf8.find()) {
        actual.add(new String(bytes, utf8.start(), utf8.end() - utf8.start(), UTF_8));
      }
      check("UTF-8 " + pattern.pattern(), codePoints, actual, expected);
    }
    List<String> lastScalars =
        expected.stream()
            .map(cluster -> Character.toString(cluster.codePointBefore(cluster.length())))
            .toList();
    List<String> firstScalars =
        expected.stream().map(cluster -> Character.toString(cluster.codePointAt(0))).toList();
    checkFinds(SCALAR_THEN_BOUNDARY, input, codePoints, lastScalars);
    checkFinds(BOUNDARY_THEN_SCALAR, input, codePoints, firstScalars);
    List<Integer> expectedBoundaries = new ArrayList<>();
    expectedBoundaries.add(
        0); // Regex boundary convention also includes position zero on empty input.
    int end = 0;
    for (String cluster : expected) {
      end += cluster.length();
      expectedBoundaries.add(end);
    }
    List<Integer> boundaries = new ArrayList<>();
    Matcher boundary = BOUNDARY.matcher(input);
    while (boundary.find()) {
      boundaries.add(boundary.start());
    }
    if (!boundaries.equals(expectedBoundaries)) {
      throw new AssertionError(
          "Boundary trace on "
              + Arrays.toString(codePoints)
              + ": expected "
              + expectedBoundaries
              + ", actual "
              + boundaries);
    }
    Matcher quantified = QUANTIFIED.matcher(input);
    if (!quantified.matches()
        || !Objects.equals(quantified.group(1), expected.isEmpty() ? null : expected.getLast())) {
      throw new AssertionError("Quantified grapheme capture on " + Arrays.toString(codePoints));
    }
    if (!expected.isEmpty()) {
      Matcher composed = FIRST_AND_REST.matcher(input);
      if (!composed.matches()
          || !composed.group(1).equals(expected.getFirst())
          || !composed.group(2).equals(String.join("", expected.subList(1, expected.size())))) {
        throw new AssertionError("Consecutive grapheme captures on " + Arrays.toString(codePoints));
      }
    }
    byte[] encoded = input.getBytes(UTF_8);
    Utf8Matcher utf8Quantified = QUANTIFIED.matcher(Utf8Input.validated(encoded));
    if (!utf8Quantified.matches()) {
      throw new AssertionError("UTF-8 quantified clusters on " + Arrays.toString(codePoints));
    }
    int expectedLastStart =
        expected.isEmpty() ? -1 : encoded.length - expected.getLast().getBytes(UTF_8).length;
    if (utf8Quantified.start(1) != expectedLastStart
        || utf8Quantified.end(1) != (expected.isEmpty() ? -1 : encoded.length)) {
      throw new AssertionError("UTF-8 quantified capture on " + Arrays.toString(codePoints));
    }
    if (!expected.isEmpty()) {
      Utf8Matcher utf8Composed = FIRST_AND_REST.matcher(Utf8Input.validated(encoded));
      int firstEnd = expected.getFirst().getBytes(UTF_8).length;
      if (!utf8Composed.matches()
          || utf8Composed.start(1) != 0
          || utf8Composed.end(1) != firstEnd
          || utf8Composed.start(2) != firstEnd
          || utf8Composed.end(2) != encoded.length) {
        throw new AssertionError("UTF-8 consecutive captures on " + Arrays.toString(codePoints));
      }
    }
    List<Integer> byteBoundaries = new ArrayList<>();
    int byteEnd = 0;
    byteBoundaries.add(byteEnd);
    for (String cluster : expected) {
      byteEnd += cluster.getBytes(UTF_8).length;
      byteBoundaries.add(byteEnd);
    }
    Utf8Matcher utf8Boundary = BOUNDARY.matcher(Utf8Input.validated(encoded));
    boundaries.clear();
    while (utf8Boundary.find()) {
      boundaries.add(utf8Boundary.start());
    }
    if (!boundaries.equals(byteBoundaries)) {
      throw new AssertionError(
          "UTF-8 boundary trace on "
              + Arrays.toString(codePoints)
              + ": expected "
              + byteBoundaries
              + ", actual "
              + boundaries);
    }
    // split("") returns one empty string; there are no clusters in empty text.
    if (!input.isEmpty()) {
      check("split", codePoints, Arrays.asList(BOUNDARY.split(input)), expected);
    }
  }

  private static void checkFinds(
      Pattern pattern, String input, int[] codePoints, List<String> expected) {
    List<String> actual = new ArrayList<>();
    Matcher scalar = pattern.matcher(input);
    while (scalar.find()) {
      actual.add(scalar.group());
    }
    check(pattern.pattern(), codePoints, actual, expected);
    byte[] bytes = input.getBytes(UTF_8);
    Utf8Matcher utf8 = pattern.matcher(Utf8Input.validated(bytes));
    actual.clear();
    while (utf8.find()) {
      actual.add(new String(bytes, utf8.start(), utf8.end() - utf8.start(), UTF_8));
    }
    check("UTF-8 " + pattern.pattern(), codePoints, actual, expected);
  }

  private static void check(
      String operation, int[] codePoints, List<String> actual, List<String> expected) {
    if (!actual.equals(expected)) {
      throw new AssertionError(
          "UAX #29 mismatch for "
              + operation
              + " on "
              + Arrays.toString(codePoints)
              + ": expected "
              + expected
              + ", actual "
              + actual);
    }
  }
}
