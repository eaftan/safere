// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;

final class ArrayUtf8Input implements Utf8Input {
  private final Utf8InputScanner scanner;
  private final boolean validated;

  ArrayUtf8Input(byte[] bytes, int offset, int length, boolean validated) {
    scanner = new Utf8InputScanner(bytes, offset, length);
    this.validated = validated;
  }

  @Override
  public int length() {
    return scanner.length();
  }

  Utf8InputScanner scanner() {
    return scanner;
  }

  boolean validated() {
    return validated;
  }

  String decode() {
    return new String(scanner.bytes(), scanner.offset(), scanner.length(), UTF_8);
  }

  void appendRange(Utf8Sink sink, int start, int end) {
    if (start < 0 || end < start || end > length()) {
      throw new IndexOutOfBoundsException("start=" + start + ", end=" + end);
    }
    sink.append(scanner.bytes(), scanner.offset() + start, end - start);
  }
}
