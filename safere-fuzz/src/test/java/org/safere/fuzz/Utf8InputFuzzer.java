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
import java.util.regex.PatternSyntaxException;
import org.safere.Pattern;
import org.safere.Utf8Input;
import org.safere.Utf8Matcher;

/** Exercises arbitrary UTF-8 storage, windows, and repeated matcher transitions. */
final class Utf8InputFuzzer {
  @FuzzTest(maxDuration = "30s")
  void arbitraryWindow(FuzzedDataProvider data) {
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
