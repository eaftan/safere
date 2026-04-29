// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.MatchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for Matcher APIs that are SafeRE-specific extensions. */
@DisabledForCrosscheck("SafeRE-only Matcher APIs and (?P<name>...) syntax have no JDK equivalent")
class MatcherSafeReApiTest {

  @Test
  void replaceFirstNamedGroupRef() {
    Pattern p = Pattern.compile("(?P<word>\\w+)");
    Matcher m = p.matcher("hello world");
    String result = m.replaceFirst("${word}!");
    assertThat(result).isEqualTo("hello! world");
  }

  @Test
  @DisplayName("toMatchResult() snapshot supports named-group lookup")
  void toMatchResultNamedGroups() {
    Pattern p = Pattern.compile("(?P<word>\\w+)");
    Matcher m = p.matcher("hello");
    assertThat(m.find()).isTrue();

    MatchResult mr = m.toMatchResult();
    assertThat(mr.namedGroups()).containsEntry("word", 1);
    assertThat(mr.group("word")).isEqualTo("hello");
    assertThat(mr.start("word")).isEqualTo(0);
    assertThat(mr.end("word")).isEqualTo(5);
  }

  @Test
  @DisplayName("start(String) and end(String) return named group positions")
  void namedGroupStartEnd() {
    Pattern p = Pattern.compile("(?P<word>\\w+)@(?P<host>\\w+)");
    Matcher m = p.matcher("user@host");
    assertThat(m.find()).isTrue();
    assertThat(m.start("word")).isEqualTo(0);
    assertThat(m.end("word")).isEqualTo(4);
    assertThat(m.start("host")).isEqualTo(5);
    assertThat(m.end("host")).isEqualTo(9);
  }

  @Test
  @DisplayName("start(String) throws for unknown group name")
  void startUnknownNameThrows() {
    Pattern p = Pattern.compile("(?P<word>\\w+)");
    Matcher m = p.matcher("hello");
    m.find();
    assertThatThrownBy(() -> m.start("missing")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("end(String) throws for unknown group name")
  void endUnknownNameThrows() {
    Pattern p = Pattern.compile("(?P<word>\\w+)");
    Matcher m = p.matcher("hello");
    m.find();
    assertThatThrownBy(() -> m.end("missing")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("named group that did not participate returns -1")
  void nonParticipatingNamedGroup() {
    Pattern p = Pattern.compile("(?P<a>a)|(?P<b>b)");
    Matcher m = p.matcher("b");
    assertThat(m.find()).isTrue();
    assertThat(m.start("a")).isEqualTo(-1);
    assertThat(m.end("a")).isEqualTo(-1);
    assertThat(m.start("b")).isEqualTo(0);
    assertThat(m.end("b")).isEqualTo(1);
  }

  @Test
  @DisplayName("namedGroups() returns named groups from pattern")
  void namedGroupsReturnsMap() {
    Pattern p = Pattern.compile("(?P<user>\\w+)@(?P<host>\\w+)");
    Matcher m = p.matcher("user@host");
    assertThat(m.namedGroups()).containsEntry("user", 1);
    assertThat(m.namedGroups()).containsEntry("host", 2);
  }

  @Test
  @DisplayName("namedGroups() returns empty map for no named groups")
  void namedGroupsEmpty() {
    Pattern p = Pattern.compile("(\\w+)@(\\w+)");
    Matcher m = p.matcher("user@host");
    assertThat(m.namedGroups()).isEmpty();
  }

  @Test
  @DisplayName("namedGroups() is unmodifiable")
  void namedGroupsUnmodifiable() {
    Pattern p = Pattern.compile("(?P<name>\\w+)");
    Matcher m = p.matcher("hello");
    assertThatThrownBy(() -> m.namedGroups().put("foo", 99))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("namedGroups() returns from MatchResult interface")
  void namedGroupsFromMatchResult() {
    Pattern p = Pattern.compile("(?P<word>\\w+)");
    Matcher m = p.matcher("hello");
    assertThat(m.find()).isTrue();
    MatchResult result = m.toMatchResult();
    assertThat(result.namedGroups()).containsEntry("word", 1);
  }

  @Test
  @DisplayName("named group methods reject null names")
  void namedGroupMethodsRejectNullNames() {
    Pattern p = Pattern.compile("(?P<word>\\w+)");
    Matcher m = p.matcher("hello");
    assertThat(m.find()).isTrue();

    assertThatThrownBy(() -> m.group((String) null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> m.start((String) null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> m.end((String) null)).isInstanceOf(NullPointerException.class);
  }
}
