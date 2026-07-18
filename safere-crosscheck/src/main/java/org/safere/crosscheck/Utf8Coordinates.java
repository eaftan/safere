// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.crosscheck;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Arrays;

/** Exact coordinate conversion at Unicode scalar boundaries. */
final class Utf8Coordinates {
  private final int[] utf16ByUtf8Offset;
  private final int[] utf8ByUtf16Offset;

  private Utf8Coordinates(int[] utf16ByUtf8Offset, int[] utf8ByUtf16Offset) {
    this.utf16ByUtf8Offset = utf16ByUtf8Offset;
    this.utf8ByUtf16Offset = utf8ByUtf16Offset;
  }

  static Utf8Coordinates create(String text) {
    byte[] utf8 = text.getBytes(UTF_8);
    int[] utf16ByUtf8Offset = new int[utf8.length + 1];
    int[] utf8ByUtf16Offset = new int[text.length() + 1];
    Arrays.fill(utf16ByUtf8Offset, -1);
    Arrays.fill(utf8ByUtf16Offset, -1);
    int utf8Offset = 0;
    for (int utf16Offset = 0; utf16Offset < text.length(); ) {
      char first = text.charAt(utf16Offset);
      if (Character.isSurrogate(first)
          && !(Character.isHighSurrogate(first)
              && utf16Offset + 1 < text.length()
              && Character.isLowSurrogate(text.charAt(utf16Offset + 1)))) {
        return null;
      }
      utf16ByUtf8Offset[utf8Offset] = utf16Offset;
      utf8ByUtf16Offset[utf16Offset] = utf8Offset;
      int codePoint = text.codePointAt(utf16Offset);
      utf8Offset += utf8Length(codePoint);
      utf16Offset += Character.charCount(codePoint);
    }
    utf16ByUtf8Offset[utf8Offset] = text.length();
    utf8ByUtf16Offset[text.length()] = utf8Offset;
    if (utf8Offset != utf8.length) {
      throw new IllegalStateException("UTF-8 coordinate map length mismatch");
    }
    return new Utf8Coordinates(utf16ByUtf8Offset, utf8ByUtf16Offset);
  }

  int toUtf16(int utf8Offset) {
    if (utf8Offset < 0 || utf8Offset >= utf16ByUtf8Offset.length) {
      throw new IllegalStateException("UTF-8 matcher returned out-of-range offset " + utf8Offset);
    }
    int utf16Offset = utf16ByUtf8Offset[utf8Offset];
    if (utf16Offset < 0) {
      throw new IllegalStateException(
          "UTF-8 matcher returned a non-scalar-boundary offset " + utf8Offset);
    }
    return utf16Offset;
  }

  int toUtf8(int utf16Offset) {
    if (!isUtf16Boundary(utf16Offset)) {
      throw new IllegalArgumentException(
          "UTF-16 offset is not a Unicode scalar boundary: " + utf16Offset);
    }
    return utf8ByUtf16Offset[utf16Offset];
  }

  boolean isUtf16Boundary(int utf16Offset) {
    return utf16Offset >= 0
        && utf16Offset < utf8ByUtf16Offset.length
        && utf8ByUtf16Offset[utf16Offset] >= 0;
  }

  String describe() {
    StringBuilder result = new StringBuilder();
    for (int utf16Offset = 0; utf16Offset < utf8ByUtf16Offset.length; utf16Offset++) {
      int utf8Offset = utf8ByUtf16Offset[utf16Offset];
      if (utf8Offset >= 0) {
        if (!result.isEmpty()) {
          result.append(", ");
        }
        result.append(utf16Offset).append("->").append(utf8Offset);
      }
    }
    return result.toString();
  }

  private static int utf8Length(int codePoint) {
    if (codePoint <= 0x7f) {
      return 1;
    }
    if (codePoint <= 0x7ff) {
      return 2;
    }
    if (codePoint <= 0xffff) {
      return 3;
    }
    return 4;
  }
}
