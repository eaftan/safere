// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

final class Utf8InputScanner implements InputScanner {
  private final byte[] bytes;

  Utf8InputScanner(byte[] bytes) {
    this.bytes = bytes;
  }

  @Override
  public int length() {
    return bytes.length;
  }

  @Override
  public int charOrByteAt(int pos) {
    return bytes[pos] & 0xFF;
  }

  @Override
  public int codePointAt(int pos) {
    int b1 = bytes[pos] & 0xFF;
    if (b1 < 128) {
      return b1;
    }
    if ((b1 & 0xE0) == 0xC0) {
      if (pos + 1 < bytes.length) {
        int b2 = bytes[pos + 1] & 0xFF;
        if ((b2 & 0xC0) == 0x80) {
          return ((b1 & 0x1F) << 6) | (b2 & 0x3F);
        }
      }
    } else if ((b1 & 0xF0) == 0xE0) {
      if (pos + 2 < bytes.length) {
        int b2 = bytes[pos + 1] & 0xFF;
        int b3 = bytes[pos + 2] & 0xFF;
        if ((b2 & 0xC0) == 0x80 && (b3 & 0xC0) == 0x80) {
          return ((b1 & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
        }
      }
    } else if ((b1 & 0xF8) == 0xF0) {
      if (pos + 3 < bytes.length) {
        int b2 = bytes[pos + 1] & 0xFF;
        int b3 = bytes[pos + 2] & 0xFF;
        int b4 = bytes[pos + 3] & 0xFF;
        if ((b2 & 0xC0) == 0x80 && (b3 & 0xC0) == 0x80 && (b4 & 0xC0) == 0x80) {
          return ((b1 & 0x07) << 18) | ((b2 & 0x3F) << 12) | ((b3 & 0x3F) << 6) | (b4 & 0x3F);
        }
      }
    }
    return 0xFFFD;
  }

  @Override
  public int codePointAt(int pos, int[] nextPos) {
    int b1 = bytes[pos] & 0xFF;
    if (b1 < 128) {
      nextPos[0] = pos + 1;
      return b1;
    }
    if ((b1 & 0xE0) == 0xC0) {
      if (pos + 1 < bytes.length) {
        int b2 = bytes[pos + 1] & 0xFF;
        if ((b2 & 0xC0) == 0x80) {
          nextPos[0] = pos + 2;
          return ((b1 & 0x1F) << 6) | (b2 & 0x3F);
        }
      }
    } else if ((b1 & 0xF0) == 0xE0) {
      if (pos + 2 < bytes.length) {
        int b2 = bytes[pos + 1] & 0xFF;
        int b3 = bytes[pos + 2] & 0xFF;
        if ((b2 & 0xC0) == 0x80 && (b3 & 0xC0) == 0x80) {
          nextPos[0] = pos + 3;
          return ((b1 & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
        }
      }
    } else if ((b1 & 0xF8) == 0xF0) {
      if (pos + 3 < bytes.length) {
        int b2 = bytes[pos + 1] & 0xFF;
        int b3 = bytes[pos + 2] & 0xFF;
        int b4 = bytes[pos + 3] & 0xFF;
        if ((b2 & 0xC0) == 0x80 && (b3 & 0xC0) == 0x80 && (b4 & 0xC0) == 0x80) {
          nextPos[0] = pos + 4;
          return ((b1 & 0x07) << 18) | ((b2 & 0x3F) << 12) | ((b3 & 0x3F) << 6) | (b4 & 0x3F);
        }
      }
    }
    nextPos[0] = pos + 1;
    return 0xFFFD;
  }

  @Override
  public int codePointBefore(int pos) {
    if (pos <= 0) {
      return -1;
    }
    int b = bytes[pos - 1] & 0xFF;
    if (b < 128) {
      return b;
    }
    int start = pos - 1;
    while (start > 0 && (bytes[start] & 0xC0) == 0x80 && (pos - start) < 4) {
      start--;
    }
    return codePointAt(start);
  }

  @Override
  public int codePointBefore(int pos, int[] prevPos) {
    if (pos <= 0) {
      prevPos[0] = -1;
      return -1;
    }
    int b = bytes[pos - 1] & 0xFF;
    if (b < 128) {
      prevPos[0] = pos - 1;
      return b;
    }
    int start = pos - 1;
    while (start > 0 && (bytes[start] & 0xC0) == 0x80 && (pos - start) < 4) {
      start--;
    }
    int b1 = bytes[start] & 0xFF;
    int len = pos - start;
    if (len == 2 && (b1 & 0xE0) == 0xC0) {
      int b2 = bytes[start + 1] & 0xFF;
      if ((b2 & 0xC0) == 0x80) {
        prevPos[0] = start;
        return ((b1 & 0x1F) << 6) | (b2 & 0x3F);
      }
    } else if (len == 3 && (b1 & 0xF0) == 0xE0) {
      int b2 = bytes[start + 1] & 0xFF;
      int b3 = bytes[start + 2] & 0xFF;
      if ((b2 & 0xC0) == 0x80 && (b3 & 0xC0) == 0x80) {
        prevPos[0] = start;
        return ((b1 & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
      }
    } else if (len == 4 && (b1 & 0xF8) == 0xF0) {
      int b2 = bytes[start + 1] & 0xFF;
      int b3 = bytes[start + 2] & 0xFF;
      int b4 = bytes[start + 3] & 0xFF;
      if ((b2 & 0xC0) == 0x80 && (b3 & 0xC0) == 0x80 && (b4 & 0xC0) == 0x80) {
        prevPos[0] = start;
        return ((b1 & 0x07) << 18) | ((b2 & 0x3F) << 12) | ((b3 & 0x3F) << 6) | (b4 & 0x3F);
      }
    }
    prevPos[0] = pos - 1;
    return 0xFFFD;
  }

  @Override
  public int trailingLineTerminatorStart(boolean unixLines, int logicalEndPos) {
    int len = logicalEndPos;
    if (len <= 0 || len > bytes.length) {
      return -1;
    }
    int[] prev = new int[1];
    int cp = codePointBefore(len, prev);
    if (cp < 0) {
      return -1;
    }
    if (unixLines) {
      return cp == '\n' ? prev[0] : -1;
    }
    if (cp == '\n') {
      int prevPos = prev[0];
      if (prevPos > 0) {
        int[] prev2 = new int[1];
        int cp2 = codePointBefore(prevPos, prev2);
        if (cp2 == '\r') {
          return prev2[0];
        }
      }
      return prev[0];
    }
    if (cp == '\r' || cp == '\u0085' || cp == '\u2028' || cp == '\u2029') {
      return prev[0];
    }
    return -1;
  }

  @Override
  public int positionDependentThreshold(boolean dollarAnchorEnd, boolean unixLines) {
    int threshold = Integer.MAX_VALUE;
    if (dollarAnchorEnd) {
      int trailingTermStart = trailingLineTerminatorStart(unixLines, bytes.length);
      if (trailingTermStart >= 0) {
        threshold = trailingTermStart;
      } else {
        threshold = bytes.length;
      }
    }
    return threshold;
  }
}
