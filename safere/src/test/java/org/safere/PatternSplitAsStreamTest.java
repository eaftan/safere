// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for {@link Pattern#splitAsStream(CharSequence)}. */
class PatternSplitAsStreamTest {
  private static final class LiteralCharSequence implements CharSequence {
    private final String value;

    LiteralCharSequence(String value) {
      this.value = value;
    }

    @Override
    public int length() {
      return value.length();
    }

    @Override
    public char charAt(int index) {
      return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return value.subSequence(start, end);
    }
  }

  private static final class MutableCharSequence implements CharSequence {
    private final StringBuilder value;

    MutableCharSequence(String value) {
      this.value = new StringBuilder(value);
    }

    void set(String newValue) {
      value.setLength(0);
      value.append(newValue);
    }

    @Override
    public int length() {
      return value.length();
    }

    @Override
    public char charAt(int index) {
      return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return value.subSequence(start, end);
    }

    @Override
    public String toString() {
      return value.toString();
    }
  }

  @Test
  @DisplayName("splitAsStream splits input around matches")
  void splitAsStreamBasic() {
    Pattern p = Pattern.compile(",");
    java.util.List<String> parts =
        p.splitAsStream("a,b,c").collect(java.util.stream.Collectors.toList());
    assertThat(parts).containsExactly("a", "b", "c");
  }

  @Test
  @DisplayName("splitAsStream with no match returns entire input")
  void splitAsStreamNoMatch() {
    Pattern p = Pattern.compile(",");
    java.util.List<String> parts =
        p.splitAsStream("abc").collect(java.util.stream.Collectors.toList());
    assertThat(parts).containsExactly("abc");
  }

  @Test
  @DisplayName("splitAsStream with regex pattern")
  void splitAsStreamRegex() {
    Pattern p = Pattern.compile("\\s+");
    java.util.List<String> parts =
        p.splitAsStream("hello  world\tfoo").collect(java.util.stream.Collectors.toList());
    assertThat(parts).containsExactly("hello", "world", "foo");
  }

  @Test
  @DisplayName("splitAsStream count() works without collecting")
  void splitAsStreamCount() {
    Pattern p = Pattern.compile(",");
    long count = p.splitAsStream("a,b,c,d").count();
    assertThat(count).isEqualTo(4);
  }

  @Test
  @DisplayName("splitAsStream discards trailing empty strings")
  void splitAsStreamTrailingEmpty() {
    Pattern p = Pattern.compile(",");
    java.util.List<String> parts =
        p.splitAsStream("a,b,").collect(java.util.stream.Collectors.toList());
    assertThat(parts).containsExactly("a", "b");
  }

  @Test
  @DisplayName("splitAsStream reads custom CharSequence content via charAt()")
  void splitAsStreamCustomCharSequence() {
    Pattern p = Pattern.compile(",");
    java.util.List<String> parts =
        p.splitAsStream(new LiteralCharSequence("a,b,c"))
            .collect(java.util.stream.Collectors.toList());
    assertThat(parts).containsExactly("a", "b", "c");
  }

  @Test
  @DisplayName("splitAsStream reads input lazily when stream is consumed")
  void splitAsStreamReadsInputLazily() {
    Pattern p = Pattern.compile(",");
    MutableCharSequence input = new MutableCharSequence("a,b");
    Stream<String> stream = p.splitAsStream(input);

    input.set("x,y,z");

    assertThat(stream.collect(java.util.stream.Collectors.toList())).containsExactly("x", "y", "z");
  }
}
