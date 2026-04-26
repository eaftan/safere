// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.regex.MatchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link Matcher}. */
class MatcherTest {

  @Nested
  @DisplayName("matches() and lookingAt()")
  class MatchesTests {

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

    @Test
    @DisplayName("matches() with alternation and non-participating group")
    void matchesAlternation() {
      Pattern p = Pattern.compile("(a)|(b)");
      Matcher m = p.matcher("a");
      assertThat(m.matches()).isTrue();
      assertThat(m.group(1)).isEqualTo("a");
      assertThat(m.group(2)).isNull();
    }

    @Test
    @DisplayName("alternation with matches() picks the correct branch")
    void alternationMatches() {
      Pattern p = Pattern.compile("(a)|(b)|(c)");
      Matcher m = p.matcher("b");
      assertThat(m.matches()).isTrue();
      assertThat(m.group(1)).isNull();
      assertThat(m.group(2)).isEqualTo("b");
      assertThat(m.group(3)).isNull();
      assertThat(m.start(1)).isEqualTo(-1);
      assertThat(m.start(2)).isEqualTo(0);
      assertThat(m.start(3)).isEqualTo(-1);
    }

    @Test
    @DisplayName("matches() updates match information for group access")
    void matchesUpdatesMatchInformation() {
      Pattern p = Pattern.compile("(\\d+)(\\w+)");
      Matcher m = p.matcher("123abc");
      assertThat(m.matches()).isTrue();
      assertThat(m.group(0)).isEqualTo("123abc");
      assertThat(m.group(1)).isEqualTo("123");
      assertThat(m.group(2)).isEqualTo("abc");
      assertThat(m.start(1)).isEqualTo(0);
      assertThat(m.end(1)).isEqualTo(3);
      assertThat(m.start(2)).isEqualTo(3);
      assertThat(m.end(2)).isEqualTo(6);
    }

    @Test
    @DisplayName("group access after lookingAt() works correctly")
    void lookingAtUpdatesGroupInfo() {
      Pattern p = Pattern.compile("(\\d+)(\\w+)");
      Matcher m = p.matcher("123abc!!!");
      assertThat(m.lookingAt()).isTrue();
      assertThat(m.group(0)).isEqualTo("123abc");
      assertThat(m.group(1)).isEqualTo("123");
      assertThat(m.group(2)).isEqualTo("abc");
    }

    @Test
    @DisplayName("matches() stays linear for repeated dot-star with bounded captures")
    void matchesWithRepeatedDotStarAndBoundedCaptures() {
      String input = issue161SqlUnionInput(10);
      Pattern p =
          Pattern.compile(
              ".*SELECT.*FROM.*(.*INFORMATION_SCHEMA.*){5,}.*",
              Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
      Matcher m = p.matcher(input);

      assertTimeoutPreemptively(
          Duration.ofSeconds(1),
          () -> {
            assertThat(m.matches()).isTrue();
            assertThat(m.group()).isEqualTo(input);
            assertThat(m.start()).isEqualTo(0);
            assertThat(m.end()).isEqualTo(input.length());
          });
    }

  }

  @Nested
  @DisplayName("find()")
  class FindTests {

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
    void findEmptyMatchAtEndOfText() {
      Pattern p = Pattern.compile("a*");
      Matcher m = p.matcher("a");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("a");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("");
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("find(int) resets match state to the given position")
    void findIntResetsState() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("a1b2c3");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("1");
      // Jump to position 0 — should find "1" again
      assertThat(m.find(0)).isTrue();
      assertThat(m.group()).isEqualTo("1");
      // Jump past "1" and "2"
      assertThat(m.find(4)).isTrue();
      assertThat(m.group()).isEqualTo("3");
    }
  }

  @Nested
  @DisplayName("Group extraction")
  class GroupTests {

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

    @Test
    @DisplayName("find() with zero-width assertions and groups")
    void groupZeroWidthAssertions() {
      Pattern p = Pattern.compile("(^|[^a-zA-Z])(\\w+)");
      Matcher m = p.matcher("hello, world");
      assertThat(m.find()).isTrue();
      assertThat(m.group(2)).isEqualTo("hello");
      assertThat(m.find()).isTrue();
      assertThat(m.group(2)).isEqualTo("world");
    }

  }

  @Nested
  @DisplayName("Replace operations")
  class ReplaceTests {

    @Test
    @DisplayName("quoteReplacement() escapes dollar signs and backslashes")
    void quoteReplacement() {
      assertThat(Matcher.quoteReplacement("hello")).isEqualTo("hello");
      assertThat(Matcher.quoteReplacement("$1")).isEqualTo("\\$1");
      assertThat(Matcher.quoteReplacement("a\\b")).isEqualTo("a\\\\b");
      assertThat(Matcher.quoteReplacement("$foo\\bar$"))
          .isEqualTo("\\$foo\\\\bar\\$");
      assertThat(Matcher.quoteReplacement("")).isEqualTo("");
    }

    @Test
    @DisplayName("quoteReplacement() result used in replaceAll() is literal")
    void quoteReplacementInReplace() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("a1b2c3");
      assertThat(m.replaceAll(Matcher.quoteReplacement("$0")))
          .isEqualTo("a$0b$0c$0");
    }

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
    @DisplayName("numeric replacement references keep trailing digits literal when needed")
    void numericReplacementReferencesUseLongestLegalGroup() {
      Pattern p = Pattern.compile("(\\w+)");

      assertThat(p.matcher("abc").replaceFirst("$11")).isEqualTo("abc1");
      assertThat(p.matcher("abc").replaceFirst("$19")).isEqualTo("abc9");
      assertThat(p.matcher("abc").replaceFirst("$10")).isEqualTo("abc0");
      assertThat(p.matcher("ab cd").replaceAll("$11")).isEqualTo("ab1 cd1");
    }

    @Test
    @DisplayName("numeric replacement references use multiple digits for existing groups")
    void numericReplacementReferencesUseExistingMultiDigitGroup() {
      Pattern p = Pattern.compile("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)");

      assertThat(p.matcher("abcdefghijkl").replaceFirst("$11")).isEqualTo("kl");
      assertThat(p.matcher("abcdefghijkl").replaceFirst("$111")).isEqualTo("k1l");
    }

    @Test
    @DisplayName("appendReplacement parses numeric references using longest legal group")
    void appendReplacementNumericReferencesUseLongestLegalGroup() {
      Pattern p = Pattern.compile("(\\w+)");
      Matcher m = p.matcher("ab cd");
      StringBuilder sb = new StringBuilder();

      while (m.find()) {
        m.appendReplacement(sb, "$11");
      }
      m.appendTail(sb);

      assertThat(sb.toString()).isEqualTo("ab1 cd1");
    }

    @Test
    @DisplayName("numeric replacement reference with invalid first digit still throws")
    void numericReplacementReferenceInvalidFirstDigitThrows() {
      Pattern p = Pattern.compile("(\\w+)");

      assertThatThrownBy(() -> p.matcher("abc").replaceFirst("$99"))
          .isInstanceOf(IndexOutOfBoundsException.class);
      assertThatThrownBy(() -> p.matcher("abc").replaceAll("$99"))
          .isInstanceOf(IndexOutOfBoundsException.class);
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

    @Test
    void appendReplacementTrailingBackslash() {
      Pattern p = Pattern.compile("a");
      Matcher m = p.matcher("a");
      m.find();
      StringBuilder sb = new StringBuilder();
      assertThatThrownBy(() -> m.appendReplacement(sb, "\\"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appendReplacementTrailingDollar() {
      Pattern p = Pattern.compile("a");
      Matcher m = p.matcher("a");
      m.find();
      StringBuilder sb = new StringBuilder();
      assertThatThrownBy(() -> m.appendReplacement(sb, "$"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appendReplacementInvalidGroupRef() {
      Pattern p = Pattern.compile("a");
      Matcher m = p.matcher("a");
      m.find();
      StringBuilder sb = new StringBuilder();
      assertThatThrownBy(() -> m.appendReplacement(sb, "$9"))
          .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void appendReplacementUnclosedNameRef() {
      Pattern p = Pattern.compile("a");
      Matcher m = p.matcher("a");
      m.find();
      StringBuilder sb = new StringBuilder();
      assertThatThrownBy(() -> m.appendReplacement(sb, "${name"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replaceAllEmptyMatches() {
      Pattern p = Pattern.compile("a*");
      Matcher m = p.matcher("b");
      String result = m.replaceAll("x");
      assertThat(result).isEqualTo("xbx");
    }

    @Test
    void replaceFirstNamedGroupRef() {
      Pattern p = Pattern.compile("(?P<word>\\w+)");
      Matcher m = p.matcher("hello world");
      String result = m.replaceFirst("${word}!");
      assertThat(result).isEqualTo("hello! world");
    }

    @Test
    @DisplayName("replaceAll with backreference wrapping")
    void replaceAllBackrefWrapping() {
      Pattern p = Pattern.compile("(\\w+):(\\d+)");
      Matcher m = p.matcher("a:1 b:2");
      assertThat(m.replaceAll("$2=$1")).isEqualTo("1=a 2=b");
    }

    @Test
    @DisplayName("appendReplacement with empty capturing group in replacement")
    void appendReplacementEmptyGroup() {
      Pattern p = Pattern.compile("(a*)b");
      Matcher m = p.matcher("b");
      StringBuilder sb = new StringBuilder();
      assertThat(m.find()).isTrue();
      m.appendReplacement(sb, "[$1]");
      m.appendTail(sb);
      assertThat(sb.toString()).isEqualTo("[]");
    }

    @Test
    @DisplayName("appendReplacement handles multiple matches with groups")
    void appendReplacementMultipleMatches() {
      Pattern p = Pattern.compile("(\\d)(\\d)");
      Matcher m = p.matcher("a12b34c");
      StringBuilder sb = new StringBuilder();
      while (m.find()) {
        m.appendReplacement(sb, "$2$1");
      }
      m.appendTail(sb);
      assertThat(sb.toString()).isEqualTo("a21b43c");
    }

    @Test
    @DisplayName("replaceFirst with group that did not participate")
    void replaceFirstNonParticipatingGroup() {
      Pattern p = Pattern.compile("(a)|(b)");
      Matcher m = p.matcher("b");
      // $1 didn't participate (null), should be replaced with empty string
      assertThat(m.replaceFirst("[$1][$2]")).isEqualTo("[][b]");
    }

  }

  @Nested
  @DisplayName("State management")
  class StateTests {

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
    @DisplayName("reset() re-reads mutable CharSequence")
    void resetRereadsMutableCharSequence() {
      StringBuilder sb = new StringBuilder("a1b2");
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher(sb);
      m.find();
      assertThat(m.group()).isEqualTo("1");
      sb.setLength(0);
      sb.append("x9y8");
      m.reset();
      m.find();
      assertThat(m.group()).isEqualTo("9");
    }

    @Test
    @DisplayName("matcher works with CharSequence that does not override toString()")
    void customCharSequenceWithoutToString() {
      // A CharSequence backed by a byte array that does NOT override toString().
      // This mimics Ghidra's ByteCharSequence pattern.
      byte[] data = "hello world".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
      CharSequence byteSeq =
          new CharSequence() {
            @Override
            public int length() {
              return data.length;
            }

            @Override
            public char charAt(int index) {
              return (char) (data[index] & 0xff);
            }

            @Override
            public CharSequence subSequence(int start, int end) {
              throw new UnsupportedOperationException();
            }
            // Deliberately no toString() override — falls back to Object.toString()
          };

      Pattern p = Pattern.compile("world");
      Matcher m = p.matcher(byteSeq);
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("world");
      assertThat(m.start()).isEqualTo(6);

      // Also test find() returning false when pattern doesn't match
      Pattern p2 = Pattern.compile("xyz");
      Matcher m2 = p2.matcher(byteSeq);
      assertThat(m2.find()).isFalse();
    }

    @Test
    @DisplayName("pattern() returns the Pattern that created this Matcher")
    void patternAccess() {
      Pattern p = Pattern.compile("abc");
      Matcher m = p.matcher("abc");
      assertThat(m.pattern()).isSameAs(p);
    }

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

    @Test
    void toMatchResultNonParticipatingGroup() {
      Pattern p = Pattern.compile("(a)|(b)");
      Matcher m = p.matcher("a");
      assertThat(m.matches()).isTrue();
      MatchResult mr = m.toMatchResult();
      assertThat(mr.group(1)).isEqualTo("a");
      assertThat(mr.group(2)).isNull();
      assertThat(mr.start(2)).isEqualTo(-1);
      assertThat(mr.end(2)).isEqualTo(-1);
    }

    @Test
    @DisplayName("reset(CharSequence) allows reuse with different input")
    void resetCharSequenceReuse() {
      Pattern p = Pattern.compile("(\\d+)");
      Matcher m = p.matcher("abc123");
      assertThat(m.find()).isTrue();
      assertThat(m.group(1)).isEqualTo("123");

      m.reset("xyz789def");
      assertThat(m.find()).isTrue();
      assertThat(m.group(1)).isEqualTo("789");
    }

  }

  @Nested
  @DisplayName("Unicode support")
  class UnicodeTests {

    @Test
    @DisplayName("Unicode supplementary code point matching")
    void unicodeSupplementaryCodePoints() {
      // U+1F600 = 😀, a supplementary character (surrogate pair in Java)
      String smiley = "\uD83D\uDE00";
      Pattern p = Pattern.compile(".");
      Matcher m = p.matcher(smiley);
      assertThat(m.matches()).isTrue();
      assertThat(m.group()).isEqualTo(smiley);
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(2); // 2 Java chars (surrogate pair)
    }

    @Test
    @DisplayName("find() with Unicode surrogate pairs in text")
    void findWithSurrogatePairs() {
      // "a😀b" = 'a' + surrogate pair + 'b'
      String text = "a\uD83D\uDE00b";
      Pattern p = Pattern.compile("b");
      Matcher m = p.matcher(text);
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(3); // 'a'=0, smiley=1,2, 'b'=3
      assertThat(m.end()).isEqualTo(4);
    }

  }

  @Nested
  @DisplayName("Integration")
  class IntegrationTests {

    @Test
    @DisplayName("complex find/group iteration (documented example)")
    void documentedExample() {
      Pattern p = Pattern.compile("(\\d+)([a-z]+)");
      Matcher m = p.matcher("12abc 34def");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("12abc");
      assertThat(m.group(1)).isEqualTo("12");
      assertThat(m.group(2)).isEqualTo("abc");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("34def");
      assertThat(m.group(1)).isEqualTo("34");
      assertThat(m.group(2)).isEqualTo("def");
      assertThat(m.find()).isFalse();
    }

  }

  @Nested
  @DisplayName("Named group start/end")
  class NamedGroupPositionTests {

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
      assertThatThrownBy(() -> m.start("missing"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("end(String) throws for unknown group name")
    void endUnknownNameThrows() {
      Pattern p = Pattern.compile("(?P<word>\\w+)");
      Matcher m = p.matcher("hello");
      m.find();
      assertThatThrownBy(() -> m.end("missing"))
          .isInstanceOf(IllegalArgumentException.class);
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
  }

  @Nested
  @DisplayName("results() stream")
  class ResultsStreamTests {

    @Test
    @DisplayName("results() returns all matches as a stream")
    void resultsStream() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("abc 123 def 456 ghi");
      java.util.List<String> matches =
          m.results().map(MatchResult::group).collect(java.util.stream.Collectors.toList());
      assertThat(matches).containsExactly("123", "456");
    }

    @Test
    @DisplayName("results() returns empty stream when no matches")
    void resultsNoMatch() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("no digits here");
      assertThat(m.results().count()).isEqualTo(0);
    }

    @Test
    @DisplayName("results() match results have correct positions")
    void resultsPositions() {
      Pattern p = Pattern.compile("[a-z]+");
      Matcher m = p.matcher("123 abc 456 def");
      java.util.List<MatchResult> results =
          m.results().collect(java.util.stream.Collectors.toList());
      assertThat(results).hasSize(2);
      assertThat(results.get(0).start()).isEqualTo(4);
      assertThat(results.get(0).end()).isEqualTo(7);
      assertThat(results.get(1).start()).isEqualTo(12);
      assertThat(results.get(1).end()).isEqualTo(15);
    }
  }

  @Nested
  @DisplayName("Functional replacement")
  class FunctionalReplaceTests {

    @Test
    @DisplayName("replaceAll(Function) replaces all matches using function")
    void replaceAllFunction() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("a1b22c333");
      String result = m.replaceAll(mr -> "[" + mr.group() + "]");
      assertThat(result).isEqualTo("a[1]b[22]c[333]");
    }

    @Test
    @DisplayName("replaceFirst(Function) replaces only first match using function")
    void replaceFirstFunction() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("a1b22c333");
      String result = m.replaceFirst(mr -> "[" + mr.group() + "]");
      assertThat(result).isEqualTo("a[1]b22c333");
    }

    @Test
    @DisplayName("replaceAll(Function) with no matches returns original")
    void replaceAllFunctionNoMatch() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("no digits");
      String result = m.replaceAll(mr -> "X");
      assertThat(result).isEqualTo("no digits");
    }

    @Test
    @DisplayName("replaceFirst(Function) with no matches returns original")
    void replaceFirstFunctionNoMatch() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("no digits");
      String result = m.replaceFirst(mr -> "X");
      assertThat(result).isEqualTo("no digits");
    }

    @Test
    @DisplayName("replaceAll(Function) can access capture groups")
    void replaceAllFunctionWithGroups() {
      Pattern p = Pattern.compile("(\\w+)=(\\w+)");
      Matcher m = p.matcher("a=1 b=2");
      String result = m.replaceAll(mr -> mr.group(2) + ":" + mr.group(1));
      assertThat(result).isEqualTo("1:a 2:b");
    }

    @Test
    @DisplayName("replaceAll(Function) throws on null replacer")
    void replaceAllFunctionNullThrows() {
      Pattern p = Pattern.compile("x");
      Matcher m = p.matcher("x");
      assertThatThrownBy(() -> m.replaceAll((java.util.function.Function<MatchResult, String>) null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("replaceFirst(Function) throws on null replacer")
    void replaceFirstFunctionNullThrows() {
      Pattern p = Pattern.compile("x");
      Matcher m = p.matcher("x");
      assertThatThrownBy(
              () -> m.replaceFirst((java.util.function.Function<MatchResult, String>) null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("StringBuffer append methods")
  class StringBufferAppendTests {

    @Test
    @DisplayName("appendReplacement and appendTail with StringBuffer")
    void stringBufferAppendReplacementAndTail() {
      Pattern p = Pattern.compile("(\\d+)");
      Matcher m = p.matcher("abc 123 def 456");
      StringBuffer sb = new StringBuffer();
      while (m.find()) {
        m.appendReplacement(sb, "NUM");
      }
      m.appendTail(sb);
      assertThat(sb.toString()).isEqualTo("abc NUM def NUM");
    }

    @Test
    @DisplayName("appendReplacement with StringBuffer supports group references")
    void stringBufferGroupRef() {
      Pattern p = Pattern.compile("(\\w+)@(\\w+)");
      Matcher m = p.matcher("user@host");
      StringBuffer sb = new StringBuffer();
      m.find();
      m.appendReplacement(sb, "$2/$1");
      m.appendTail(sb);
      assertThat(sb.toString()).isEqualTo("host/user");
    }
  }

  @Nested
  @DisplayName("usePattern()")
  class UsePatternTests {

    @Test
    @DisplayName("usePattern swaps pattern and preserves position")
    void usePatternSwapsPattern() {
      Pattern p1 = Pattern.compile("\\d+");
      Pattern p2 = Pattern.compile("[a-z]+");
      Matcher m = p1.matcher("123abc456def");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("123");
      m.usePattern(p2);
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("abc");
    }

    @Test
    @DisplayName("usePattern returns this matcher")
    void usePatternReturnsSelf() {
      Pattern p1 = Pattern.compile("a");
      Pattern p2 = Pattern.compile("b");
      Matcher m = p1.matcher("ab");
      assertThat(m.usePattern(p2)).isSameAs(m);
    }

    @Test
    @DisplayName("usePattern with null throws")
    void usePatternNullThrows() {
      Pattern p = Pattern.compile("a");
      Matcher m = p.matcher("a");
      assertThatThrownBy(() -> m.usePattern(null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("pattern() reflects usePattern change")
    void patternReflectsChange() {
      Pattern p1 = Pattern.compile("a");
      Pattern p2 = Pattern.compile("b");
      Matcher m = p1.matcher("ab");
      assertThat(m.pattern()).isSameAs(p1);
      m.usePattern(p2);
      assertThat(m.pattern()).isSameAs(p2);
    }
  }

  @Nested
  @DisplayName("Transparent and anchoring bounds")
  class BoundsTests {

    @Test
    @DisplayName("default anchoring bounds is true")
    void defaultAnchoringBounds() {
      Pattern p = Pattern.compile("a");
      Matcher m = p.matcher("a");
      assertThat(m.hasAnchoringBounds()).isTrue();
    }

    @Test
    @DisplayName("useAnchoringBounds stores flag and returns this")
    void useAnchoringBounds() {
      Pattern p = Pattern.compile("a");
      Matcher m = p.matcher("a");
      assertThat(m.useAnchoringBounds(false)).isSameAs(m);
      assertThat(m.hasAnchoringBounds()).isFalse();
      m.useAnchoringBounds(true);
      assertThat(m.hasAnchoringBounds()).isTrue();
    }

    @Test
    @DisplayName("default transparent bounds is false")
    void defaultTransparentBounds() {
      Pattern p = Pattern.compile("a");
      Matcher m = p.matcher("a");
      assertThat(m.hasTransparentBounds()).isFalse();
    }

    @Test
    @DisplayName("useTransparentBounds stores flag and returns this")
    void useTransparentBounds() {
      Pattern p = Pattern.compile("a");
      Matcher m = p.matcher("a");
      assertThat(m.useTransparentBounds(true)).isSameAs(m);
      assertThat(m.hasTransparentBounds()).isTrue();
      m.useTransparentBounds(false);
      assertThat(m.hasTransparentBounds()).isFalse();
    }
  }

  @Nested
  @DisplayName("Case-insensitive DFA correctness")
  class CaseInsensitiveDfaTests {

    @Test
    @DisplayName("matches() works after failed case-insensitive match on reused matcher")
    void matchesAfterMismatch() {
      Pattern p = Pattern.compile("birds", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
      Matcher m = p.matcher("Cats");
      assertThat(m.matches()).isFalse();
      m.reset("Birds");
      assertThat(m.matches()).isTrue();
    }

    @Test
    @DisplayName("sequential case-insensitive matches on reused matcher")
    void sequentialCaseInsensitiveMatches() {
      Pattern p = Pattern.compile("birds", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
      Matcher m = p.matcher("");
      String[] tokens = {"dogs", "cats", "Cats", "Birds", "birds"};
      boolean[] expected = {false, false, false, true, true};
      for (int i = 0; i < tokens.length; i++) {
        m.reset(tokens[i]);
        assertThat(m.matches()).as("token '%s'", tokens[i]).isEqualTo(expected[i]);
      }
    }

    @Test
    @DisplayName("fresh matcher works after DFA cached non-matching transitions")
    void freshMatcherAfterDfaCache() {
      Pattern p = Pattern.compile("birds", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
      // Warm up DFA with non-matching inputs
      Matcher m1 = p.matcher("Cats");
      m1.matches();
      // Fresh matcher on same pattern should still work
      assertThat(p.matcher("Birds").matches()).isTrue();
    }
  }

  @Nested
  @DisplayName("region()")
  class RegionTests {

    @Test
    @DisplayName("find() respects region boundaries")
    void findRespectsRegion() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("abc 123 def 456 ghi");
      m.region(4, 7); // "123"
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("123");
      assertThat(m.start()).isEqualTo(4);
      assertThat(m.end()).isEqualTo(7);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("find() does not match outside region")
    void findDoesNotMatchOutsideRegion() {
      Pattern p = Pattern.compile("ghi");
      Matcher m = p.matcher("abc ghi xyz");
      m.region(0, 5); // "abc g"
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("matches() matches entire region")
    void matchesEntireRegion() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("abc123def");
      m.region(3, 6); // "123"
      assertThat(m.matches()).isTrue();
      assertThat(m.group()).isEqualTo("123");
      assertThat(m.start()).isEqualTo(3);
      assertThat(m.end()).isEqualTo(6);
    }

    @Test
    @DisplayName("matches() fails if region doesn't fully match")
    void matchesFailsPartialRegion() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("abc123def");
      m.region(2, 7); // "c123d"
      assertThat(m.matches()).isFalse();
    }

    @Test
    @DisplayName("lookingAt() matches at start of region")
    void lookingAtRegion() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("abc123def456");
      m.region(3, 9); // "123def"
      assertThat(m.lookingAt()).isTrue();
      assertThat(m.group()).isEqualTo("123");
      assertThat(m.start()).isEqualTo(3);
      assertThat(m.end()).isEqualTo(6);
    }

    @Test
    @DisplayName("lookingAt() fails if region doesn't start with match")
    void lookingAtRegionFails() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("abc123def");
      m.region(0, 6); // "abc123" — starts with 'a' not digit
      assertThat(m.lookingAt()).isFalse();
    }

    @Test
    @DisplayName("regionStart() and regionEnd() return bounds")
    void regionAccessors() {
      Pattern p = Pattern.compile("a");
      Matcher m = p.matcher("abcdef");
      m.region(2, 5);
      assertThat(m.regionStart()).isEqualTo(2);
      assertThat(m.regionEnd()).isEqualTo(5);
    }

    @Test
    @DisplayName("reset() restores region to full text")
    void resetRestoresRegion() {
      Pattern p = Pattern.compile("a");
      Matcher m = p.matcher("abcdef");
      m.region(2, 5);
      m.reset();
      assertThat(m.regionStart()).isEqualTo(0);
      assertThat(m.regionEnd()).isEqualTo(6);
    }

    @Test
    @DisplayName("region returns this matcher")
    void regionReturnsSelf() {
      Pattern p = Pattern.compile("a");
      Matcher m = p.matcher("abc");
      assertThat(m.region(0, 2)).isSameAs(m);
    }

    @Test
    @DisplayName("region with invalid bounds throws")
    void regionInvalidBoundsThrows() {
      Pattern p = Pattern.compile("a");
      Matcher m = p.matcher("abc");
      assertThatThrownBy(() -> m.region(-1, 3))
          .isInstanceOf(IndexOutOfBoundsException.class);
      assertThatThrownBy(() -> m.region(0, 4))
          .isInstanceOf(IndexOutOfBoundsException.class);
      assertThatThrownBy(() -> m.region(3, 1))
          .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("^ matches at region start with anchoring bounds")
    void caretMatchesAtRegionStart() {
      Pattern p = Pattern.compile("^\\d+");
      Matcher m = p.matcher("abc123def");
      m.region(3, 6); // "123"
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("123");
    }

    @Test
    @DisplayName("$ matches at region end with anchoring bounds")
    void dollarMatchesAtRegionEnd() {
      Pattern p = Pattern.compile("\\d+$");
      Matcher m = p.matcher("abc123def");
      m.region(3, 6); // "123"
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("123");
    }

    @Test
    @DisplayName("successive find() calls within region")
    void successiveFindInRegion() {
      Pattern p = Pattern.compile("[a-z]+");
      Matcher m = p.matcher("111aaa222bbb333ccc444");
      m.region(3, 15); // "aaa222bbb333"
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("aaa");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("bbb");
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("capture groups work with region")
    void captureGroupsWithRegion() {
      Pattern p = Pattern.compile("(\\w+)=(\\w+)");
      Matcher m = p.matcher("xxx a=1 yyy b=2 zzz");
      m.region(4, 15); // "a=1 yyy b=2"
      assertThat(m.find()).isTrue();
      assertThat(m.group(1)).isEqualTo("a");
      assertThat(m.group(2)).isEqualTo("1");
      assertThat(m.find()).isTrue();
      assertThat(m.group(1)).isEqualTo("b");
      assertThat(m.group(2)).isEqualTo("2");
    }

    @Test
    @DisplayName("empty region matches empty pattern")
    void emptyRegion() {
      Pattern p = Pattern.compile("");
      Matcher m = p.matcher("abc");
      m.region(1, 1); // empty region
      assertThat(m.matches()).isTrue();
      assertThat(m.start()).isEqualTo(1);
      assertThat(m.end()).isEqualTo(1);
    }

    @Test
    @DisplayName("region at end of text")
    void regionAtEnd() {
      Pattern p = Pattern.compile("xyz");
      Matcher m = p.matcher("abcxyz");
      m.region(3, 6); // "xyz"
      assertThat(m.matches()).isTrue();
    }

    @Test
    @DisplayName("replaceAll within region (after find loop)")
    void replaceAllWithRegion() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("aa11bb22cc");
      m.region(2, 8); // "11bb22"
      // replaceAll resets, which resets region to full text
      // So this tests that reset() works correctly
      String result = m.replaceAll("N");
      assertThat(result).isEqualTo("aaNbbNcc");
    }
  }

  @Nested
  @DisplayName("hitEnd()")
  class HitEndTests {

    @Test
    @DisplayName("hitEnd is true when match extends to end of input")
    void hitEndMatchAtEnd() {
      Pattern p = Pattern.compile("\\w+");
      Matcher m = p.matcher("abc");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("abc");
      assertThat(m.hitEnd()).isTrue();
    }

    @Test
    @DisplayName("hitEnd is false when match does not reach end")
    void hitEndMatchNotAtEnd() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("123 abc");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("123");
      assertThat(m.hitEnd()).isFalse();
    }

    @Test
    @DisplayName("hitEnd is true when no match found")
    void hitEndNoMatch() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("no digits");
      assertThat(m.find()).isFalse();
      assertThat(m.hitEnd()).isTrue();
    }

    @Test
    @DisplayName("hitEnd respects region boundaries")
    void hitEndWithRegion() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("abc123def456ghi");
      m.region(3, 6); // "123"
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("123");
      assertThat(m.hitEnd()).isTrue(); // match extends to regionEnd
    }

    @Test
    @DisplayName("hitEnd false with region when match doesn't reach end")
    void hitEndFalseWithRegion() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("abc123def456ghi");
      m.region(3, 9); // "123def"
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("123");
      assertThat(m.hitEnd()).isFalse();
    }

    @Test
    @DisplayName("hitEnd true with matches()")
    void hitEndWithMatches() {
      Pattern p = Pattern.compile("abc");
      Matcher m = p.matcher("abc");
      assertThat(m.matches()).isTrue();
      assertThat(m.hitEnd()).isTrue();
    }

    @Test
    @DisplayName("hitEnd true with lookingAt() matching to end")
    void hitEndWithLookingAtToEnd() {
      Pattern p = Pattern.compile("abc");
      Matcher m = p.matcher("abc");
      assertThat(m.lookingAt()).isTrue();
      assertThat(m.hitEnd()).isTrue();
    }

    @Test
    @DisplayName("hitEnd false with lookingAt() not reaching end")
    void hitEndFalseWithLookingAt() {
      Pattern p = Pattern.compile("abc");
      Matcher m = p.matcher("abcdef");
      assertThat(m.lookingAt()).isTrue();
      assertThat(m.hitEnd()).isFalse();
    }
  }

  @Nested
  @DisplayName("Reverse-first DFA optimization for end-anchored patterns")
  class ReverseFirstDfaTests {

    /** Helper to create a string of repeated characters. */
    private String repeat(char ch, int count) {
      return String.valueOf(ch).repeat(count);
    }

    @Test
    @DisplayName("end-anchored pattern, no match on large text — fast fail")
    void endAnchoredNoMatch() {
      Pattern p = Pattern.compile("[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$");
      // Random lowercase text — pattern can't match because no uppercase letters at end.
      String text = repeat('a', 2000);
      Matcher m = p.matcher(text);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("end-anchored pattern, match at end of large text")
    void endAnchoredMatchAtEnd() {
      Pattern p = Pattern.compile("[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$");
      String text = repeat('x', 2000) + "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
      Matcher m = p.matcher(text);
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo(text);
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(text.length());
    }

    @Test
    @DisplayName("dollar anchor with trailing newline — match before \\n")
    void dollarAnchorTrailingNewline() {
      Pattern p = Pattern.compile("abc$");
      String text = repeat('x', 2000) + "abc\n";
      Matcher m = p.matcher(text);
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("abc");
      assertThat(m.start()).isEqualTo(2000);
      assertThat(m.end()).isEqualTo(2003);
    }

    @Test
    @DisplayName("dollar anchor with trailing \\r\\n — match before \\r\\n")
    void dollarAnchorTrailingCrLf() {
      Pattern p = Pattern.compile("abc$");
      String text = repeat('x', 2000) + "abc\r\n";
      Matcher m = p.matcher(text);
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("abc");
      assertThat(m.start()).isEqualTo(2000);
      assertThat(m.end()).isEqualTo(2003);
    }

    @Test
    @DisplayName("dollar anchor, no trailing newline — match at absolute end")
    void dollarAnchorNoNewline() {
      Pattern p = Pattern.compile("abc$");
      String text = repeat('x', 2000) + "abc";
      Matcher m = p.matcher(text);
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("abc");
      assertThat(m.start()).isEqualTo(2000);
      assertThat(m.end()).isEqualTo(2003);
    }

    @Test
    @DisplayName("\\\\z anchor — match only at absolute end, not before \\n")
    void absoluteEndAnchor() {
      Pattern p = Pattern.compile("abc\\z");
      String text = repeat('x', 2000) + "abc";
      Matcher m = p.matcher(text);
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("abc");
    }

    @Test
    @DisplayName("\\\\z anchor with trailing newline — no match")
    void absoluteEndAnchorNoMatchBeforeNewline() {
      Pattern p = Pattern.compile("abc\\z");
      String text = repeat('x', 2000) + "abc\n";
      Matcher m = p.matcher(text);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("end-anchored with capture groups")
    void endAnchoredWithCaptures() {
      Pattern p = Pattern.compile("(\\w+)@(\\w+)$");
      // Use non-word chars as padding so \w+ doesn't match them.
      String text = repeat('-', 2000) + "user@host";
      Matcher m = p.matcher(text);
      assertThat(m.find()).isTrue();
      assertThat(m.group(0)).isEqualTo("user@host");
      assertThat(m.group(1)).isEqualTo("user");
      assertThat(m.group(2)).isEqualTo("host");
    }

    @Test
    @DisplayName("end-anchored, second find() returns false")
    void endAnchoredSecondFindFails() {
      Pattern p = Pattern.compile("abc$");
      String text = repeat('x', 2000) + "abc";
      Matcher m = p.matcher(text);
      assertThat(m.find()).isTrue();
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("char-class prefix + end anchor, no match")
    void charClassPrefixEndAnchorNoMatch() {
      Pattern p = Pattern.compile("[XYZ]ABCDEFGHIJKLMNOPQRSTUVWXYZ$");
      String text = repeat('a', 2000);
      Matcher m = p.matcher(text);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("char-class prefix + end anchor, match at end")
    void charClassPrefixEndAnchorMatch() {
      Pattern p = Pattern.compile("[XYZ]ABC$");
      String text = repeat('a', 2000) + "XABC";
      Matcher m = p.matcher(text);
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("XABC");
    }

    @Test
    @DisplayName("end-anchored pattern on text just above threshold")
    void textJustAboveThreshold() {
      Pattern p = Pattern.compile("xyz$");
      String text = repeat('a', 1024) + "xyz";
      Matcher m = p.matcher(text);
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("xyz");
      assertThat(m.start()).isEqualTo(1024);
    }

    @Test
    @DisplayName("end-anchored pattern on text below threshold — uses normal path")
    void textBelowThreshold() {
      Pattern p = Pattern.compile("xyz$");
      String text = repeat('a', 500) + "xyz";
      Matcher m = p.matcher(text);
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("xyz");
    }
  }

  /**
   * Tests for the OnePass fast path with alternation patterns. The anchored OnePass fast path
   * now handles non-nullable alternation (e.g., GET|POST) directly, skipping the DFA sandwich.
   * Nullable alternation (zero-width vs consuming branches) still falls through to DFA+BitState.
   */
  @Nested
  @DisplayName("OnePass alternation fast path")
  class OnePassAlternationFastPath {

    @Test
    @DisplayName("HTTP pattern — anchored with non-nullable alternation uses OnePass")
    void httpPatternFullRequest() {
      Pattern p = Pattern.compile("^(?:GET|POST) +([^ ]+) HTTP");
      Matcher m = p.matcher(
          "GET /asdfhjasdhfasdlfhasdflkjasdfkljasdhflaskdjhflkajsdhflkajshfklasjdhfklasjdhfklashdflka HTTP/1.1");
      assertThat(m.find()).isTrue();
      assertThat(m.group(0)).isEqualTo(
          "GET /asdfhjasdhfasdlfhasdflkjasdfkljasdhflaskdjhflkajsdhflkajshfklasjdhfklasjdhfklashdflka HTTP");
      assertThat(m.group(1)).isEqualTo(
          "/asdfhjasdhfasdlfhasdflkjasdfkljasdhflaskdjhflkajsdhflkajshfklasjdhfklasjdhfklashdflka");
    }

    @Test
    @DisplayName("HTTP pattern — POST variant")
    void httpPatternPost() {
      Pattern p = Pattern.compile("^(?:GET|POST) +([^ ]+) HTTP");
      Matcher m = p.matcher("POST /submit HTTP/1.1");
      assertThat(m.find()).isTrue();
      assertThat(m.group(0)).isEqualTo("POST /submit HTTP");
      assertThat(m.group(1)).isEqualTo("/submit");
    }

    @Test
    @DisplayName("HTTP pattern — small request")
    void httpPatternSmallRequest() {
      Pattern p = Pattern.compile("^(?:GET|POST) +([^ ]+) HTTP");
      Matcher m = p.matcher("GET /abc HTTP/1.1");
      assertThat(m.find()).isTrue();
      assertThat(m.group(0)).isEqualTo("GET /abc HTTP");
      assertThat(m.group(1)).isEqualTo("/abc");
    }

    @Test
    @DisplayName("HTTP pattern — no match")
    void httpPatternNoMatch() {
      Pattern p = Pattern.compile("^(?:GET|POST) +([^ ]+) HTTP");
      Matcher m = p.matcher("PUT /resource HTTP/1.1");
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("non-nullable alternation with captures — both branches")
    void nonNullableAlternationCaptures() {
      Pattern p = Pattern.compile("^(cat|dog) (\\w+)");
      Matcher m1 = p.matcher("cat fluffy");
      assertThat(m1.find()).isTrue();
      assertThat(m1.group(1)).isEqualTo("cat");
      assertThat(m1.group(2)).isEqualTo("fluffy");

      Matcher m2 = p.matcher("dog rex");
      assertThat(m2.find()).isTrue();
      assertThat(m2.group(1)).isEqualTo("dog");
      assertThat(m2.group(2)).isEqualTo("rex");
    }

    @Test
    @DisplayName("nullable alternation — zero-width branch wins via first-match priority")
    void nullableAlternationZeroWidth() {
      // \\b is zero-width, \\d is consuming — first-match should pick \\b
      Pattern p = Pattern.compile("(?:\\b|\\d)");
      Matcher m = p.matcher("1");
      assertThat(m.find()).isTrue();
      assertThat(m.group(0)).isEqualTo(""); // \\b matches empty at word boundary
    }

    @Test
    @DisplayName("nullable alternation — non-word-boundary vs consuming")
    void nullableAlternationNonWordBoundary() {
      Pattern p = Pattern.compile("(?:\\B|a)");
      Matcher m = p.matcher("ba _");
      assertThat(m.find()).isTrue();
      // First match: \\B at position 0 is not a word boundary (start of string before 'b'),
      // but JDK considers position 0 a word boundary, so \\B fails and 'a' is not at pos 0.
      // Actual behavior depends on JDK semantics — just verify we match JDK.
      java.util.regex.Matcher jdkM = java.util.regex.Pattern.compile("(?:\\B|a)").matcher("ba _");
      assertThat(jdkM.find()).isTrue();
      assertThat(m.group(0)).isEqualTo(jdkM.group(0));
    }

    @Test
    @DisplayName("unanchored non-nullable alternation on small text — uses OnePass")
    void unanchoredNonNullableSmallText() {
      Pattern p = Pattern.compile("(GET|POST) (\\w+)");
      Matcher m = p.matcher("method: GET data");
      assertThat(m.find()).isTrue();
      assertThat(m.group(1)).isEqualTo("GET");
      assertThat(m.group(2)).isEqualTo("data");
    }
  }

  @Nested
  @DisplayName("Atomic \\r\\n line terminator (#77, #78)")
  class AtomicCrLfTests {

    @Test
    @DisplayName("find() with $ on \\r\\n does not infinite-loop (#77)")
    void dollarFindOnCrLfTerminates() {
      // Before the fix, this never terminated because $ matched between \r and \n,
      // causing find() to loop without advancing.
      Pattern p = Pattern.compile("$");
      Matcher m = p.matcher("\r\n");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(2);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("(?m)$ on \\r\\n matches at 0 and 2 only (#78)")
    void multilineDollarOnCrLf() {
      Pattern p = Pattern.compile("(?m)$");
      Matcher m = p.matcher("\r\n");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(2);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("(?m)$ on a\\r\\nb matches at 1 and 4 only (#78)")
    void multilineDollarOnTextWithCrLf() {
      Pattern p = Pattern.compile("(?m)$");
      Matcher m = p.matcher("a\r\nb");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(1);
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(4);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("$ (non-multiline) on \\r\\n matches at end")
    void dollarNonMultilineOnCrLf() {
      Pattern p = Pattern.compile("$");
      Matcher m = p.matcher("\r\n");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(2);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("(?m)$ on standalone \\r still matches")
    void multilineDollarOnStandaloneCr() {
      Pattern p = Pattern.compile("(?m)$");
      Matcher m = p.matcher("a\rb");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(1);
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(3);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("(?m)$ on standalone \\n still matches")
    void multilineDollarOnStandaloneLf() {
      Pattern p = Pattern.compile("(?m)$");
      Matcher m = p.matcher("a\nb");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(1);
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(3);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("UNIX_LINES: (?m)$ on \\r\\n treats \\n only")
    void unixLinesMultilineDollarOnCrLf() {
      Pattern p = Pattern.compile("(?m)$", Pattern.UNIX_LINES);
      Matcher m = p.matcher("a\r\nb");
      assertThat(m.find()).isTrue();
      // In UNIX_LINES, only \n is a line terminator; \r is ordinary.
      // $ matches before \n (pos 2) and at end (pos 4).
      assertThat(m.start()).isEqualTo(2);
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(4);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("(?:$|\\n)+ on \\r\\n matches correctly (#78)")
    void dollarOrNewlineOnCrLf() {
      Pattern p = Pattern.compile("(?m)(?:$|\\n)+");
      Matcher m = p.matcher("\r\n");
      assertThat(m.find()).isTrue();
    }

    @Test
    @DisplayName("(?:$|\\r)+ on \\r\\n matches correctly (#78)")
    void dollarOrCrOnCrLf() {
      Pattern p = Pattern.compile("(?m)(?:$|\\r)+");
      Matcher m = p.matcher("\r\n");
      assertThat(m.find()).isTrue();
    }

    @Test
    @DisplayName("(?:$|[\\r\\n])+ on \\r\\n matches correctly (#78)")
    void dollarOrCrLfClassOnCrLf() {
      Pattern p = Pattern.compile("(?m)(?:$|[\\r\\n])+");
      Matcher m = p.matcher("\r\n");
      assertThat(m.find()).isTrue();
    }
  }

  @Nested
  @DisplayName("requireEnd()")
  class RequireEndTests {

    @Test
    @DisplayName("requireEnd() is false for simple literal find")
    void requireEndFalseForLiteralFind() {
      Pattern p = Pattern.compile("abc");
      Matcher m = p.matcher("abc");
      assertThat(m.find()).isTrue();
      assertThat(m.requireEnd()).isFalse();
    }

    @Test
    @DisplayName("requireEnd() is true for dollar-anchored find at end")
    void requireEndTrueForDollarAnchor() {
      Pattern p = Pattern.compile("abc$");
      Matcher m = p.matcher("abc");
      assertThat(m.find()).isTrue();
      assertThat(m.requireEnd()).isTrue();
    }

    @Test
    @DisplayName("requireEnd() is false for \\z anchor (JDK does not track \\z)")
    void requireEndFalseForEndTextAnchor() {
      Pattern p = Pattern.compile("abc\\z");
      Matcher m = p.matcher("abc");
      assertThat(m.find()).isTrue();
      // JDK does not set requireEnd for \z, only for $.
      assertThat(m.requireEnd()).isFalse();
    }

    @Test
    @DisplayName("requireEnd() is true for word boundary at end")
    void requireEndTrueForWordBoundary() {
      Pattern p = Pattern.compile("\\babc\\b");
      Matcher m = p.matcher("abc");
      assertThat(m.find()).isTrue();
      assertThat(m.requireEnd()).isTrue();
    }

    @Test
    @DisplayName("requireEnd() is false after reset")
    void requireEndFalseAfterReset() {
      Pattern p = Pattern.compile("abc$");
      Matcher m = p.matcher("abc");
      assertThat(m.find()).isTrue();
      assertThat(m.requireEnd()).isTrue();
      m.reset();
      assertThat(m.requireEnd()).isFalse();
    }

    @Test
    @DisplayName("requireEnd() is false when match does not hit end")
    void requireEndFalseWhenNotAtEnd() {
      Pattern p = Pattern.compile("abc");
      Matcher m = p.matcher("abcdef");
      assertThat(m.find()).isTrue();
      assertThat(m.hitEnd()).isFalse();
      assertThat(m.requireEnd()).isFalse();
    }

    @Test
    @DisplayName("requireEnd() with matches()")
    void requireEndWithMatches() {
      Pattern p = Pattern.compile("abc$");
      Matcher m = p.matcher("abc");
      assertThat(m.matches()).isTrue();
      assertThat(m.requireEnd()).isTrue();
    }

    @Test
    @DisplayName("requireEnd() with lookingAt()")
    void requireEndWithLookingAt() {
      Pattern p = Pattern.compile("abc$");
      Matcher m = p.matcher("abc");
      assertThat(m.lookingAt()).isTrue();
      assertThat(m.requireEnd()).isTrue();
    }

    @Test
    @DisplayName("requireEnd() is false for literal matches()")
    void requireEndFalseForLiteralMatches() {
      Pattern p = Pattern.compile("abc");
      Matcher m = p.matcher("abc");
      assertThat(m.matches()).isTrue();
      // No end assertions in pattern — match doesn't depend on end position.
      assertThat(m.requireEnd()).isFalse();
    }
  }

  @Nested
  @DisplayName("namedGroups()")
  class NamedGroupsTests {

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
      // MatchResult.namedGroups() should work via the override on SnapshotMatchResult
      // or the default method. SafeRE's Matcher overrides it.
      assertThat(m.namedGroups()).containsEntry("word", 1);
    }
  }

  private static String issue161SqlUnionInput(int selectCount) {
    StringBuilder input = new StringBuilder();
    for (int i = 1; i <= selectCount; i++) {
      input
          .append("(SELECT *, PARSE_DATE('%Y-%m-%d', '2025-06-25') AS snapshot_date FROM ")
          .append("`project-")
          .append("%02d".formatted(i))
          .append("`.`region2`.INFORMATION_SCHEMA.TABLE_OPTIONS)\n")
          .append("UNION ALL\n");
    }
    return input.toString();
  }
}
