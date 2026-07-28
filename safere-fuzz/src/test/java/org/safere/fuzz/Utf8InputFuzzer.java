// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.regex.PatternSyntaxException;
import org.safere.Pattern;
import org.safere.Utf8Input;
import org.safere.Utf8Matcher;

/** Exercises arbitrary UTF-8 storage, windows, and repeated matcher transitions. */
final class Utf8InputFuzzer {
  private record RegionCaptureCase(String regex, String input, int start, int end) {}

  @FuzzTest(maxDuration = "30s")
  void arbitraryWindow(FuzzedDataProvider data) {
    assertLiteralSearchMatchesString("XXXXXX", "..XXXXXX");
    assertBoundarySensitiveRegionCaptures();
    String repeatedLiteral =
        String.valueOf((char) data.consumeInt('A', 'Z')).repeat(data.consumeInt(2, 32));
    String suffix = new String(data.consumeBytes(data.consumeInt(0, 64)), StandardCharsets.UTF_8);
    String literalInput = ".".repeat(data.consumeInt(0, 64)) + repeatedLiteral + suffix;
    assertLiteralSearchMatchesString(repeatedLiteral, literalInput);

    Pattern pattern;
    try {
      pattern = Pattern.compile(data.consumeString(128), FuzzSupport.consumeFlags(data));
    } catch (PatternSyntaxException ignored) {
      return;
    }
    byte[] bytes = data.consumeBytes(2048);
    int offset = data.consumeInt(0, bytes.length);
    int length = data.consumeInt(0, bytes.length - offset);

    walk(pattern.matcher(Utf8Input.trusted(bytes, offset, length)), length);
    boolean valid = isValidUtf8(bytes, offset, length);
    try {
      Utf8Input validated = Utf8Input.validated(bytes, offset, length);
      if (!valid) {
        throw new AssertionError("strict validation accepted malformed UTF-8");
      }
      walk(pattern.matcher(validated), length);
      pattern.find(validated);
    } catch (IllegalArgumentException e) {
      if (valid) {
        throw new AssertionError("strict validation rejected valid UTF-8", e);
      }
    }
  }

  private static void assertBoundarySensitiveRegionCaptures() {
    for (RegionCaptureCase testCase :
        List.of(
            new RegionCaptureCase("(\\b(?:a|aa))", "xaa", 1, 3),
            new RegionCaptureCase("((?:a|aa)\\b)", "aax", 0, 2))) {
      for (String operation : List.of("matches", "lookingAt", "find")) {
        java.util.regex.Matcher stringMatcher =
            java.util.regex.Pattern.compile(testCase.regex()).matcher(testCase.input());
        stringMatcher.region(testCase.start(), testCase.end());
        Utf8Matcher utf8Matcher =
            Pattern.compile(testCase.regex())
                .matcher(Utf8Input.validated(testCase.input().getBytes(StandardCharsets.UTF_8)))
                .region(testCase.start(), testCase.end());

        boolean stringMatched =
            switch (operation) {
              case "matches" -> stringMatcher.matches();
              case "lookingAt" -> stringMatcher.lookingAt();
              case "find" -> stringMatcher.find();
              default -> throw new AssertionError(operation);
            };
        boolean utf8Matched =
            switch (operation) {
              case "matches" -> utf8Matcher.matches();
              case "lookingAt" -> utf8Matcher.lookingAt();
              case "find" -> utf8Matcher.find();
              default -> throw new AssertionError(operation);
            };
        if (utf8Matched != stringMatched
            || (utf8Matched
                && (utf8Matcher.start(1) != stringMatcher.start(1)
                    || utf8Matcher.end(1) != stringMatcher.end(1)))) {
          throw new AssertionError(
              "UTF-8 region capture bounds differ from String matcher for " + operation);
        }
      }
    }
  }

  private static void assertLiteralSearchMatchesString(String regex, String input) {
    Pattern pattern = Pattern.compile(regex);
    org.safere.Matcher stringMatcher = pattern.matcher(input);
    byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
    Utf8Input utf8Input = Utf8Input.validated(bytes);
    Utf8Matcher utf8Matcher = pattern.matcher(utf8Input);

    boolean stringFound = stringMatcher.find();
    boolean utf8Found = utf8Matcher.find();
    if (stringFound != utf8Found || pattern.find(utf8Input) != stringFound) {
      throw new AssertionError("UTF-8 literal search result differs from String search");
    }
    if (stringFound
        && (!Objects.equals(stringMatcher.group(), decodeGroup(bytes, utf8Matcher))
            || utf8Matcher.start() < 0
            || utf8Matcher.end() > bytes.length)) {
      throw new AssertionError("UTF-8 literal search bounds differ from String search");
    }
  }

  private static String decodeGroup(byte[] bytes, Utf8Matcher matcher) {
    return new String(
        bytes, matcher.start(), matcher.end() - matcher.start(), StandardCharsets.UTF_8);
  }

  private static void walk(Utf8Matcher matcher, int length) {
    int previousEnd = -1;
    int attempts = 0;
    while (matcher.find()) {
      int start = matcher.start();
      int end = matcher.end();
      if (start < 0 || start > end || end > length || end < previousEnd) {
        throw new AssertionError("non-monotonic or out-of-window match bounds");
      }
      for (int group = 0; group <= matcher.groupCount(); group++) {
        int groupStart = matcher.start(group);
        int groupEnd = matcher.end(group);
        if ((groupStart < 0) != (groupEnd < 0) || groupStart > groupEnd || groupEnd > length) {
          throw new AssertionError("invalid capture bounds");
        }
      }
      previousEnd = end;
      if (++attempts > length + 1) {
        throw new AssertionError("find did not make bounded progress");
      }
    }
  }

  private static boolean isValidUtf8(byte[] bytes, int offset, int length) {
    try {
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes, offset, length));
      return true;
    } catch (java.nio.charset.CharacterCodingException e) {
      return false;
    }
  }
}
