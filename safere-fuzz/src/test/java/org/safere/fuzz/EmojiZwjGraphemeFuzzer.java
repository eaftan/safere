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
import org.junit.jupiter.api.Test;
import org.safere.Matcher;
import org.safere.Pattern;
import org.safere.Utf8Input;
import org.safere.Utf8Matcher;

/** Independent UAX #29 oracle restricted to this alphabet; not full Unicode conformance. */
public final class EmojiZwjGraphemeFuzzer {
  // Pinned property representatives: ExtPict, Extend, SpacingMark, ZWJ, Prepend, RI,
  // CR, LF, Other. No Hangul or Indic consonants/linkers, so GB6-8 and GB9c cannot apply.
  private static final int[] ALPHABET = {
    0x1F469, 0x1F44D, 0x0301, 0xFE0F, 0x1F3FD, 0x0903, 0x200D,
    0x0600, 0x110BD, 0x1F1E6, 0x1F1E7, 0x000D, 0x000A, 0x0061
  };
  private static final List<Pattern> PATTERNS =
      List.of(Pattern.compile("\\X"), Pattern.compile("\\X\\b{g}"), Pattern.compile("(\\X)"));
  private static final Pattern BOUNDARY = Pattern.compile("\\b{g}");

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
    for (int first : ALPHABET) {
      assertSegmentation(new int[] {first});
      for (int second : ALPHABET) {
        assertSegmentation(new int[] {first, second});
        for (int third : ALPHABET) {
          assertSegmentation(new int[] {first, second, third});
        }
      }
    }
  }

  private static void assertSegmentation(int[] codePoints) {
    String input = new String(codePoints, 0, codePoints.length);
    List<String> expected = referenceClusters(codePoints);
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
    // split("") returns one empty string; there are no clusters in empty text.
    if (!input.isEmpty()) {
      check("split", codePoints, Arrays.asList(BOUNDARY.split(input)), expected);
    }
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

  private static List<String> referenceClusters(int[] codePoints) {
    List<String> clusters = new ArrayList<>();
    int start = 0;
    for (int i = 1; i < codePoints.length; i++) {
      if (breakBefore(codePoints, i)) {
        clusters.add(new String(codePoints, start, i - start));
        start = i;
      }
    }
    if (start < codePoints.length) {
      clusters.add(new String(codePoints, start, codePoints.length - start));
    }
    return clusters;
  }

  private static boolean breakBefore(int[] codePoints, int i) {
    int left = codePoints[i - 1];
    int right = codePoints[i];
    if (left == 0x000D && right == 0x000A) { // GB3
      return false;
    }
    if (isControl(left) || isControl(right)) { // GB4/5, before GB9 and GB9b
      return true;
    }
    if (isExtend(right) || right == 0x200D || right == 0x0903) { // GB9/9a
      return false;
    }
    if (left == 0x0600 || left == 0x110BD) { // GB9b
      return false;
    }
    if (left == 0x200D && isPictograph(right)) { // GB11: look before the ZWJ
      int j = i - 2;
      while (j >= 0 && isExtend(codePoints[j])) {
        j--;
      }
      if (j >= 0 && isPictograph(codePoints[j])) {
        return false;
      }
    }
    if (isRegionalIndicator(left) && isRegionalIndicator(right)) { // GB12/13
      int count = 0;
      for (int j = i - 1; j >= 0 && isRegionalIndicator(codePoints[j]); j--) {
        count++;
      }
      return count % 2 == 0;
    }
    return true; // GB999
  }

  private static boolean isExtend(int cp) {
    return cp == 0x0301 || cp == 0xFE0F || cp == 0x1F3FD;
  }

  private static boolean isPictograph(int cp) {
    return cp == 0x1F469 || cp == 0x1F44D;
  }

  private static boolean isRegionalIndicator(int cp) {
    return cp == 0x1F1E6 || cp == 0x1F1E7;
  }

  private static boolean isControl(int cp) {
    return cp == 0x000D || cp == 0x000A;
  }
}
