// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.safere.Utf8Matcher;

/** Byte validation and progress checks shared without a regex oracle. */
final class Utf8RobustnessChecks {
  private Utf8RobustnessChecks() {}

  static void walk(Utf8Matcher matcher, int length) {
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

  static boolean isValidUtf8(byte[] bytes, int offset, int length) {
    try {
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes, offset, length));
      return true;
    } catch (CharacterCodingException e) {
      return false;
    }
  }
}
