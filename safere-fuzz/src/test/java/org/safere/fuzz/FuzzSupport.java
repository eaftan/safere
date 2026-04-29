// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import java.util.regex.PatternSyntaxException;
import org.safere.crosscheck.Pattern;

final class FuzzSupport {

  private static final int[] FLAGS = {
      Pattern.UNIX_LINES,
      Pattern.CASE_INSENSITIVE,
      Pattern.COMMENTS,
      Pattern.MULTILINE,
      Pattern.LITERAL,
      Pattern.DOTALL,
      Pattern.UNICODE_CASE,
      Pattern.UNICODE_CHARACTER_CLASS
  };

  private FuzzSupport() {}

  static Pattern compileOrSkip(String regex, int flags) {
    try {
      return Pattern.compile(regex, flags);
    } catch (PatternSyntaxException expected) {
      return null;
    }
  }

  static int consumeFlags(FuzzedDataProvider data) {
    int flags = 0;
    for (int flag : FLAGS) {
      if (data.consumeBoolean()) {
        flags |= flag;
      }
    }
    return flags;
  }

  static int consumeIndex(FuzzedDataProvider data, String input) {
    return data.consumeInt(0, input.length());
  }

  static int[] consumeRegion(FuzzedDataProvider data, String input) {
    int start = consumeIndex(data, input);
    int end = data.consumeInt(start, input.length());
    return new int[] {start, end};
  }

  static String consumeUnicodeHeavyString(FuzzedDataProvider data, int maxCodePoints) {
    int codePoints = data.consumeInt(0, maxCodePoints);
    StringBuilder sb = new StringBuilder(codePoints);
    for (int i = 0; i < codePoints; i++) {
      switch (data.consumeInt(0, 9)) {
        case 0 -> sb.append((char) data.consumeInt('a', 'z'));
        case 1 -> sb.append((char) data.consumeInt('0', '9'));
        case 2 -> sb.append('\n');
        case 3 -> sb.append('\r');
        case 4 -> sb.append('\u2028');
        case 5 -> sb.append('\u0301');
        case 6 -> sb.append(Character.toChars(data.consumeInt(0x10000, 0x10FFFF)));
        case 7 -> sb.append((char) data.consumeInt(0xD800, 0xDBFF));
        case 8 -> sb.append((char) data.consumeInt(0xDC00, 0xDFFF));
        case 9 -> sb.append((char) data.consumeInt(0x80, 0xFFFD));
        default -> throw new AssertionError();
      }
    }
    return sb.toString();
  }
}
