// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.MatchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for {@link Matcher}. */
class MatcherTest {

  // ---------------------------------------------------------------------------
  // matches()
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("matches() returns true for a full match")
  void matchesSuccess() {
    Pattern p = Pattern.compile("abc");
    Matcher m = p.matcher("abc");
    assertThat(m.matches()).isTrue();
    assertThat(m.group()).isEqualTo("abc");
    assertThat(m.start()).isEqualTo(0);
    assertThat(m.end()).isEqualTo(3);
  }

  @Test
  @DisplayName("matches() returns false when pattern does not match entire input")
  void matchesFailure() {
    Pattern p = Pattern.compile("abc");
    Matcher m = p.matcher("xabcx");
    assertThat(m.matches()).isFalse();
  }

  @Test
  @DisplayName("matches() with capturing groups")
  void matchesWithGroups() {
    Pattern p = Pattern.compile("(a+)(b+)");
    Matcher m = p.matcher("aaabb");
    assertThat(m.matches()).isTrue();
    assertThat(m.group(0)).isEqualTo("aaabb");
    assertThat(m.group(1)).isEqualTo("aaa");
    assertThat(m.group(2)).isEqualTo("bb");
  }

  // ---------------------------------------------------------------------------
  // find()
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("find() locates a single match in the input")
  void findSingle() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("abc123def");
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("123");
    assertThat(m.start()).isEqualTo(3);
    assertThat(m.end()).isEqualTo(6);
    assertThat(m.find()).isFalse();
  }

  @Test
  @DisplayName("find() locates multiple successive matches")
  void findMultiple() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("a1b22c333");
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("1");
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("22");
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("333");
    assertThat(m.find()).isFalse();
  }

  @Test
  @DisplayName("find() handles empty matches by advancing one character")
  void findEmptyMatches() {
    Pattern p = Pattern.compile("a*");
    Matcher m = p.matcher("b");
    // Empty match at position 0
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(0);
    assertThat(m.end()).isEqualTo(0);
    // Empty match at position 1 (past 'b')
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(1);
    assertThat(m.end()).isEqualTo(1);
    // No more matches
    assertThat(m.find()).isFalse();
  }

  @Test
  @DisplayName("find() returns false when no match exists")
  void findNoMatch() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("abc");
    assertThat(m.find()).isFalse();
  }

  // ---------------------------------------------------------------------------
  // find(int)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("find(int) starts search from given position")
  void findWithStart() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("a1b2c3");
    assertThat(m.find(3)).isTrue();
    assertThat(m.group()).isEqualTo("2");
  }

  @Test
  @DisplayName("find(int) throws for negative start")
  void findWithNegativeStart() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("abc");
    assertThatThrownBy(() -> m.find(-1))
        .isInstanceOf(IndexOutOfBoundsException.class);
  }

  @Test
  @DisplayName("find(int) throws for start past end of input")
  void findWithStartPastEnd() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("abc");
    assertThatThrownBy(() -> m.find(4))
        .isInstanceOf(IndexOutOfBoundsException.class);
  }

  // ---------------------------------------------------------------------------
  // lookingAt()
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("lookingAt() matches at start of input")
  void lookingAtSuccess() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("123abc");
    assertThat(m.lookingAt()).isTrue();
    assertThat(m.group()).isEqualTo("123");
    assertThat(m.start()).isEqualTo(0);
    assertThat(m.end()).isEqualTo(3);
  }

  @Test
  @DisplayName("lookingAt() fails when no match at start")
  void lookingAtFailure() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("abc123");
    assertThat(m.lookingAt()).isFalse();
  }

  @Test
  @DisplayName("lookingAt() does not require full-string match")
  void lookingAtPartialMatch() {
    Pattern p = Pattern.compile("abc");
    Matcher m = p.matcher("abcdef");
    assertThat(m.lookingAt()).isTrue();
    assertThat(m.group()).isEqualTo("abc");
  }

  // ---------------------------------------------------------------------------
  // Group access
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("group() returns the full match, group(int) returns subgroups")
  void groupAccess() {
    Pattern p = Pattern.compile("(a)(b)");
    Matcher m = p.matcher("ab");
    m.find();
    assertThat(m.group()).isEqualTo("ab");
    assertThat(m.group(0)).isEqualTo("ab");
    assertThat(m.group(1)).isEqualTo("a");
    assertThat(m.group(2)).isEqualTo("b");
  }

  @Test
  @DisplayName("group(String) returns named group text")
  void namedGroup() {
    Pattern p = Pattern.compile("(?P<first>\\w+) (?P<last>\\w+)");
    Matcher m = p.matcher("John Smith");
    assertThat(m.matches()).isTrue();
    assertThat(m.group("first")).isEqualTo("John");
    assertThat(m.group("last")).isEqualTo("Smith");
  }

  @Test
  @DisplayName("group(String) throws for unknown name")
  void namedGroupUnknown() {
    Pattern p = Pattern.compile("(?P<first>\\w+)");
    Matcher m = p.matcher("hello");
    m.find();
    assertThatThrownBy(() -> m.group("unknown"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("groupCount() returns number of capturing groups (excluding group 0)")
  void groupCount() {
    Pattern p = Pattern.compile("(a)(b)(c)");
    Matcher m = p.matcher("abc");
    assertThat(m.groupCount()).isEqualTo(3);
  }

  @Test
  @DisplayName("non-participating group returns null")
  void nonParticipatingGroup() {
    Pattern p = Pattern.compile("(a)|(b)");
    Matcher m = p.matcher("b");
    m.find();
    assertThat(m.group(1)).isNull();
    assertThat(m.group(2)).isEqualTo("b");
    assertThat(m.start(1)).isEqualTo(-1);
    assertThat(m.end(1)).isEqualTo(-1);
  }

  // ---------------------------------------------------------------------------
  // start() and end()
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("start() and end() return full match positions")
  void startEnd() {
    Pattern p = Pattern.compile("b+");
    Matcher m = p.matcher("aabba");
    m.find();
    assertThat(m.start()).isEqualTo(2);
    assertThat(m.end()).isEqualTo(4);
  }

  @Test
  @DisplayName("start(int) and end(int) return group positions")
  void startEndGroup() {
    Pattern p = Pattern.compile("(a+)(b+)");
    Matcher m = p.matcher("aaabb");
    m.find();
    assertThat(m.start(1)).isEqualTo(0);
    assertThat(m.end(1)).isEqualTo(3);
    assertThat(m.start(2)).isEqualTo(3);
    assertThat(m.end(2)).isEqualTo(5);
  }

  // ---------------------------------------------------------------------------
  // Exception handling
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("start() throws IllegalStateException before any match")
  void startNoMatch() {
    Pattern p = Pattern.compile("x");
    Matcher m = p.matcher("abc");
    assertThatThrownBy(m::start)
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("group() throws IllegalStateException before any match")
  void groupNoMatch() {
    Pattern p = Pattern.compile("x");
    Matcher m = p.matcher("abc");
    assertThatThrownBy(m::group)
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("end() throws IllegalStateException before any match")
  void endNoMatch() {
    Pattern p = Pattern.compile("x");
    Matcher m = p.matcher("abc");
    assertThatThrownBy(m::end)
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("start(int) throws IndexOutOfBoundsException for invalid group")
  void invalidGroupIndexPositive() {
    Pattern p = Pattern.compile("(a)");
    Matcher m = p.matcher("a");
    m.find();
    assertThatThrownBy(() -> m.start(2))
        .isInstanceOf(IndexOutOfBoundsException.class);
  }

  @Test
  @DisplayName("start(int) throws IndexOutOfBoundsException for negative group")
  void invalidGroupIndexNegative() {
    Pattern p = Pattern.compile("(a)");
    Matcher m = p.matcher("a");
    m.find();
    assertThatThrownBy(() -> m.start(-1))
        .isInstanceOf(IndexOutOfBoundsException.class);
  }

  @Test
  @DisplayName("end(int) throws IndexOutOfBoundsException for invalid group")
  void invalidGroupIndexEnd() {
    Pattern p = Pattern.compile("(a)");
    Matcher m = p.matcher("a");
    m.find();
    assertThatThrownBy(() -> m.end(2))
        .isInstanceOf(IndexOutOfBoundsException.class);
  }

  // ---------------------------------------------------------------------------
  // Replacement methods
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("replaceFirst() replaces only the first match")
  void replaceFirst() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("a1b2c3");
    assertThat(m.replaceFirst("X")).isEqualTo("aXb2c3");
  }

  @Test
  @DisplayName("replaceAll() replaces all matches")
  void replaceAll() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("a1b2c3");
    assertThat(m.replaceAll("X")).isEqualTo("aXbXcX");
  }

  @Test
  @DisplayName("replaceFirst() with no match returns original text")
  void replaceFirstNoMatch() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("abc");
    assertThat(m.replaceFirst("X")).isEqualTo("abc");
  }

  @Test
  @DisplayName("replaceAll() with no match returns original text")
  void replaceAllNoMatch() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("abc");
    assertThat(m.replaceAll("X")).isEqualTo("abc");
  }

  @Test
  @DisplayName("replaceAll() with numeric backreference")
  void replaceAllWithBackref() {
    Pattern p = Pattern.compile("(\\w+)");
    Matcher m = p.matcher("hello world");
    assertThat(m.replaceAll("[$1]")).isEqualTo("[hello] [world]");
  }

  @Test
  @DisplayName("replaceAll() with named backreference")
  void replaceAllWithNamedBackref() {
    Pattern p = Pattern.compile("(?P<word>\\w+)");
    Matcher m = p.matcher("hello world");
    assertThat(m.replaceAll("${word}!")).isEqualTo("hello! world!");
  }

  @Test
  @DisplayName("replacement string with escaped dollar and backslash")
  void replacementEscapes() {
    Pattern p = Pattern.compile("x");
    Matcher m = p.matcher("x");
    assertThat(m.replaceFirst("\\$1")).isEqualTo("$1");
  }

  @Test
  @DisplayName("replacement string with escaped backslash")
  void replacementEscapedBackslash() {
    Pattern p = Pattern.compile("x");
    Matcher m = p.matcher("x");
    assertThat(m.replaceFirst("\\\\")).isEqualTo("\\");
  }

  // ---------------------------------------------------------------------------
  // appendReplacement() / appendTail()
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("appendReplacement() and appendTail() manual iteration")
  void appendReplacementAndTail() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("a1b2c3");
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(sb, "X");
    }
    m.appendTail(sb);
    assertThat(sb.toString()).isEqualTo("aXbXcX");
  }

  @Test
  @DisplayName("appendReplacement() with backreference in replacement")
  void appendReplacementWithBackref() {
    Pattern p = Pattern.compile("(\\d+)");
    Matcher m = p.matcher("a1b22");
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(sb, "[$1]");
    }
    m.appendTail(sb);
    assertThat(sb.toString()).isEqualTo("a[1]b[22]");
  }

  // ---------------------------------------------------------------------------
  // State management
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("reset() clears match state and restarts from beginning")
  void reset() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("a1b2");
    m.find();
    assertThat(m.group()).isEqualTo("1");
    m.reset();
    m.find();
    assertThat(m.group()).isEqualTo("1"); // restarts from the beginning
  }

  @Test
  @DisplayName("reset(CharSequence) changes input and clears state")
  void resetWithNewInput() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("a1b2");
    m.find();
    assertThat(m.group()).isEqualTo("1");
    m.reset("x9y8");
    m.find();
    assertThat(m.group()).isEqualTo("9");
  }

  @Test
  @DisplayName("pattern() returns the Pattern that created this Matcher")
  void patternAccess() {
    Pattern p = Pattern.compile("abc");
    Matcher m = p.matcher("abc");
    assertThat(m.pattern()).isSameAs(p);
  }

  // ---------------------------------------------------------------------------
  // toMatchResult()
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("toMatchResult() returns an independent snapshot")
  void toMatchResult() {
    Pattern p = Pattern.compile("(\\d+)");
    Matcher m = p.matcher("a1b2");
    m.find();
    MatchResult r = m.toMatchResult();
    assertThat(r.group()).isEqualTo("1");
    assertThat(r.group(1)).isEqualTo("1");
    assertThat(r.start()).isEqualTo(1);
    assertThat(r.end()).isEqualTo(2);
    assertThat(r.groupCount()).isEqualTo(1);
    // Advance matcher; snapshot should be unaffected.
    m.find();
    assertThat(m.group()).isEqualTo("2");
    assertThat(r.group()).isEqualTo("1");
  }

  @Test
  @DisplayName("toMatchResult() throws when no match")
  void toMatchResultNoMatch() {
    Pattern p = Pattern.compile("x");
    Matcher m = p.matcher("abc");
    assertThatThrownBy(m::toMatchResult)
        .isInstanceOf(IllegalStateException.class);
  }

  // ---------------------------------------------------------------------------
  // Edge cases
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("find() after failed matches() searches from beginning")
  void findAfterFailedMatches() {
    Pattern p = Pattern.compile("a");
    Matcher m = p.matcher("bab");
    assertThat(m.matches()).isFalse();
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("a");
    assertThat(m.start()).isEqualTo(1);
  }

  @Test
  @DisplayName("find() at end of input returns false")
  void findAtEnd() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("abc123");
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("123");
    assertThat(m.find()).isFalse();
  }

  @Test
  @DisplayName("matches() with alternation and non-participating group")
  void matchesAlternation() {
    Pattern p = Pattern.compile("(a)|(b)");
    Matcher m = p.matcher("a");
    assertThat(m.matches()).isTrue();
    assertThat(m.group(1)).isEqualTo("a");
    assertThat(m.group(2)).isNull();
  }
}
