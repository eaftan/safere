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
import java.util.function.IntConsumer;
import java.util.regex.MatchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link Matcher}. */
class MatcherTest {
  private static final Duration PERFORMANCE_SCENARIO_TIMEOUT = Duration.ofSeconds(30);

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
    @DisplayName("lookingAt() respects lazy quantifier priority")
    void lookingAtRespectsLazyQuantifierPriority() {
      Matcher m = Pattern.compile("(a+?)").matcher("aaa");

      assertThat(m.lookingAt()).isTrue();
      assertThat(m.group()).isEqualTo("a");
      assertThat(m.group(1)).isEqualTo("a");
      assertThat(m.end()).isEqualTo(1);
    }

    @Test
    @DisplayName("lookingAt() respects empty alternative priority")
    void lookingAtRespectsEmptyAlternativePriority() {
      Matcher m = Pattern.compile("(|a)").matcher("a");

      assertThat(m.lookingAt()).isTrue();
      assertThat(m.group()).isEmpty();
      assertThat(m.group(1)).isEmpty();
      assertThat(m.end()).isEqualTo(0);
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
    @DisabledForCrosscheck("java.util.regex backtracks on this SafeRE linear-time stress case")
    @DisplayName("matches() stays linear for repeated dot-star with bounded captures")
    void matchesWithRepeatedDotStarAndBoundedCaptures() {
      Pattern p = repeatedDotStarSqlUnionPattern();

      assertNoPerformanceCliff(
          "matches()",
          blocks -> {
            String input = repeatedDotStarSqlUnionInput(blocks);
            Matcher m = p.matcher(input);
            assertThat(m.matches()).isTrue();
            assertThat(m.group()).isEqualTo(input);
            assertThat(m.start()).isEqualTo(0);
            assertThat(m.end()).isEqualTo(input.length());
          });
    }

    @Test
    @DisabledForCrosscheck("java.util.regex backtracks on this SafeRE linear-time stress case")
    @DisplayName("lookingAt() stays linear for repeated dot-star with bounded captures")
    void lookingAtWithRepeatedDotStarAndBoundedCaptures() {
      Pattern p = repeatedDotStarSqlUnionPattern();

      assertNoPerformanceCliff(
          "lookingAt()",
          blocks -> {
            Matcher m = p.matcher(repeatedDotStarSqlUnionInput(blocks));
            assertThat(m.lookingAt()).isTrue();
            assertThat(m.group(1)).contains("INFORMATION_SCHEMA");
          });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    @DisplayName("captures in repeated dot-star bodies match JDK semantics")
    void matchesWithRepeatedDotStarCapturesMatchJdkSemantics(int captures) {
      String pattern = repeatedDotStarCapturePattern(5, captures);
      String input = repeatedDotStarCaptureInput(5, captures);
      Matcher m = Pattern.compile(pattern).matcher(input);
      java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(pattern).matcher(input);

      assertThat(m.matches()).isTrue();
      assertThat(jdk.matches()).isTrue();
      assertThat(m.group(1)).isEqualTo(jdk.group(1));
      for (int i = 2; i <= captures + 1; i++) {
        assertThat(m.group(i)).isEqualTo(jdk.group(i));
      }
    }

    @Test
    @DisabledForCrosscheck("java.util.regex backtracks on this SafeRE linear-time stress case")
    @DisplayName("matches() stays linear for repeated dot-star bodies with multiple captures")
    void matchesWithRepeatedDotStarBodiesAndMultipleCaptures() {
      assertNoSuperlinearRepeatedDotStarCaptureScaling(
          repetitions -> {
            String pattern = repeatedDotStarCapturePattern(repetitions, 3);
            String input = repeatedDotStarCaptureInput(repetitions, 3);
            Matcher m = Pattern.compile(pattern).matcher(input);
            assertThat(m.matches()).isTrue();
            assertThat(m.group(2)).endsWith("A");
            assertThat(m.group(3)).endsWith("B");
            assertThat(m.group(4)).endsWith("C");
          });
    }

  }

  @Nested
  @DisplayName("find()")
  class FindTests {

    @Test
    @DisabledForCrosscheck("java.util.regex backtracks on this SafeRE linear-time stress case")
    @DisplayName("group access after find() stays linear for repeated dot-star captures")
    void findGroupWithRepeatedDotStarAndBoundedCaptures() {
      Pattern p = repeatedDotStarSqlUnionPattern();

      assertNoPerformanceCliff(
          "find()+group(1)",
          blocks -> {
            Matcher m = p.matcher(repeatedDotStarSqlUnionInput(blocks));
            assertThat(m.find()).isTrue();
            assertThat(m.group(1)).contains("INFORMATION_SCHEMA");
          });
    }

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
    @DisplayName("find(int) resets region before searching")
    void findStartResetsRegion() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("abc123def456");
      m.region(3, 6); // "123"

      assertThat(m.find(6)).isTrue();
      assertThat(m.regionStart()).isEqualTo(0);
      assertThat(m.regionEnd()).isEqualTo(12);
      assertThat(m.group()).isEqualTo("456");
      assertThat(m.start()).isEqualTo(9);
      assertThat(m.end()).isEqualTo(12);
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

    @Test
    @DisplayName("find() exposes group 0 before deferred fallback captures are resolved")
    void findGroupZeroBeforeDeferredFallbackCaptures() {
      Pattern p = Pattern.compile("(?m)(?:^|,)(?:\"([^\"]*)\"|([^,\r\n]+))");
      Matcher m = p.matcher("id,name\n42,\"Ada Lovelace\"");

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("id");
      assertThat(m.group(0)).isEqualTo("id");
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(2);
    }

    @Test
    @DisplayName("group access resolves deferred fallback captures")
    void groupAccessResolvesDeferredFallbackCaptures() {
      Pattern p = Pattern.compile("(?m)(?:^|,)(?:\"([^\"]*)\"|([^,\r\n]+))");
      Matcher m = p.matcher("id,name\n42,\"Ada Lovelace\"");

      assertThat(m.find()).isTrue();
      assertThat(m.group(1)).isNull();
      assertThat(m.group(2)).isEqualTo("id");
      assertThat(m.start(2)).isEqualTo(0);
      assertThat(m.end(2)).isEqualTo(2);

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo(",name");
      assertThat(m.group(1)).isNull();
      assertThat(m.group(2)).isEqualTo("name");
    }

    @Test
    @DisplayName("toMatchResult() resolves deferred fallback captures into snapshot")
    void toMatchResultResolvesDeferredFallbackCaptures() {
      Pattern p = Pattern.compile("(?m)(?:^|,)(?:\"([^\"]*)\"|([^,\r\n]+))");
      Matcher m = p.matcher("id,name\n42,\"Ada Lovelace\"");

      assertThat(m.find()).isTrue();
      assertThat(m.find()).isTrue();
      assertThat(m.find()).isTrue();
      assertThat(m.find()).isTrue();
      MatchResult snapshot = m.toMatchResult();

      assertThat(snapshot.group()).isEqualTo(",\"Ada Lovelace\"");
      assertThat(snapshot.group(1)).isEqualTo("Ada Lovelace");
      assertThat(snapshot.group(2)).isNull();
      assertThat(snapshot.start(1)).isEqualTo(12);
      assertThat(snapshot.end(1)).isEqualTo(24);
    }

    @Test
    @DisplayName("successive find() calls do not require resolving previous fallback captures")
    void successiveFindCallsWithDeferredFallbackCaptures() {
      Pattern p = Pattern.compile("(?i)\\b(error|warning|timeout|failed)\\b");
      Matcher m = p.matcher("Warning cache miss\nrequest failed after TIMEOUT\nERROR");

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("Warning");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("failed");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("TIMEOUT");
      assertThat(m.group(1)).isEqualTo("TIMEOUT");
      assertThat(m.find()).isTrue();
      assertThat(m.group(1)).isEqualTo("ERROR");
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("find() keyword alternation fast path matches JDK across case and punctuation")
    void findKeywordAlternationFastPathMatchesJdk() {
      assertAllFindsMatchJdk(
          "(?i)\\b(error|warning|timeout|failed)\\b",
          "Info: Warning, request failed after TIMEOUT; ERROR-rate ignored");
    }

    @Test
    @DisplayName("find() keyword alternation rejects adjacent ASCII word characters")
    void findKeywordAlternationRejectsAdjacentAsciiWordCharacters() {
      assertAllFindsMatchJdk(
          "(?i)\\b(error|warning|timeout|failed)\\b",
          "preerror error2 _warning warning-post failed");
    }

    @Test
    @DisplayName("find() keyword alternation uses Unicode boundaries when requested")
    void findKeywordAlternationUsesUnicodeBoundaries() {
      assertAllFindsMatchJdk(
          "(?i)\\b(error|warning)\\b",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS,
          "éerroré error βwarning warning!");
    }

    @Test
    @DisplayName("find() keyword alternation rejects partly case-sensitive scoped flags")
    void findKeywordAlternationRejectsPartlyCaseSensitiveScopedFlags() {
      assertAllFindsMatchJdk("\\b((?i:a)B|(?i:x)Y)\\b", "ab aB AB xy xY XY");
    }

    @Test
    @DisplayName("find() start acceleration matches JDK for comma-or-line-start CSV fields")
    void findStartAccelerationForCsvFields() {
      assertAllFindsMatchJdk(
          "(?m)(?:^|,)(?:\"([^\"]*)\"|([^,\r\n]+))",
          "id,name,email\r\n42,\"Ada Lovelace\",ada@example.com\n"
              + "43,\"Grace Hopper\",grace@example.com");
    }

    @Test
    @DisplayName("find() start acceleration matches JDK for multiline line-start whitespace")
    void findStartAccelerationForLineStartWhitespace() {
      assertAllFindsMatchJdk(
          "(?m)^\\s+at\\s+([A-Za-z0-9_.$]+)\\.([A-Za-z0-9_$<>]+)\\(([^:()]+):(\\d+)\\)$",
          "java.lang.IllegalStateException: failed\n"
              + "\tat org.example.api.Handler.handle(Handler.java:87)\n"
              + "Caused by: java.io.IOException: timeout\r\n"
              + "\tat org.example.net.Client.read(Client.java:203)");
    }

    @Test
    @DisplayName("find() start acceleration handles Unicode line terminators")
    void findStartAccelerationWithUnicodeLineTerminators() {
      assertAllFindsMatchJdk("(?m)^\\s+at\\s+(\\w+)$", "header\u2028\tat alpha\u2029\tat beta");
    }

    private void assertAllFindsMatchJdk(String regex, String input) {
      assertAllFindsMatchJdk(regex, 0, input);
    }

    private void assertAllFindsMatchJdk(String regex, int flags, String input) {
      Matcher m = Pattern.compile(regex, flags).matcher(input);
      java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regex, flags).matcher(input);

      while (true) {
        boolean safereFound = m.find();
        boolean jdkFound = jdk.find();
        assertThat(safereFound).isEqualTo(jdkFound);
        if (!jdkFound) {
          return;
        }
        assertThat(m.group()).isEqualTo(jdk.group());
        assertThat(m.start()).isEqualTo(jdk.start());
        assertThat(m.end()).isEqualTo(jdk.end());
        for (int group = 1; group <= jdk.groupCount(); group++) {
          assertThat(m.group(group)).isEqualTo(jdk.group(group));
          assertThat(m.start(group)).isEqualTo(jdk.start(group));
          assertThat(m.end(group)).isEqualTo(jdk.end(group));
        }
      }
    }

    @ParameterizedTest
    @ValueSource(strings = {
      "(bcd|abcde)",
      "(.+?X|bc)",
      "(?:a{1,3})?a{3}",
      "(?m)^\\s+at\\s+([A-Za-z0-9_.$]+)\\.([A-Za-z0-9_$<>]+)\\(([^:()]+):(\\d+)\\)$",
      "(?i)\\b(error|warning|timeout|failed)\\b"
    })
    @DisplayName("find() fallback paths match JDK for unreliable DFA-start patterns")
    void findFallbackMatchesJdkForUnreliableDfaStartPatterns(String regex) {
      String text =
          "java.lang.IllegalStateException: failed\n"
              + "\tat org.example.Main.main(Main.java:25)\n"
              + "xxabcde yy aaa failed bcX";
      Matcher m = Pattern.compile(regex).matcher(text);
      java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regex).matcher(text);

      boolean safereFound = m.find();
      boolean jdkFound = jdk.find();
      assertThat(safereFound).isEqualTo(jdkFound);
      if (!jdkFound) {
        return;
      }
      assertThat(m.group()).isEqualTo(jdk.group());
      assertThat(m.start()).isEqualTo(jdk.start());
      assertThat(m.end()).isEqualTo(jdk.end());
      for (int group = 1; group <= jdk.groupCount(); group++) {
        assertThat(m.group(group)).isEqualTo(jdk.group(group));
        assertThat(m.start(group)).isEqualTo(jdk.start(group));
        assertThat(m.end(group)).isEqualTo(jdk.end(group));
      }
    }

    @ParameterizedTest
    @ValueSource(strings = {
      "(bcd|abcde)",
      "(.+?X|bc)",
      "(?:a{1,3})?a{3}",
      "(?m)^\\s+at\\s+([A-Za-z0-9_.$]+)\\.([A-Za-z0-9_$<>]+)\\(([^:()]+):(\\d+)\\)$",
      "(?i)\\b(error|warning|timeout|failed)\\b"
    })
    @DisplayName("find() returns false for no-match unreliable DFA-start patterns")
    void findFallbackNoMatchForUnreliableDfaStartPatterns(String regex) {
      Matcher m = Pattern.compile(regex).matcher("plain text without the target shape");
      java.util.regex.Matcher jdk =
          java.util.regex.Pattern.compile(regex).matcher("plain text without the target shape");

      assertThat(m.find()).isEqualTo(jdk.find());
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
    @DisabledForCrosscheck("(?P<name>...) named groups are SafeRE syntax, not JDK syntax")
    @DisplayName("group(String) returns named group text")
    void namedGroup() {
      Pattern p = Pattern.compile("(?P<first>\\w+) (?P<last>\\w+)");
      Matcher m = p.matcher("John Smith");
      assertThat(m.matches()).isTrue();
      assertThat(m.group("first")).isEqualTo("John");
      assertThat(m.group("last")).isEqualTo("Smith");
    }

    @Test
    @DisabledForCrosscheck("(?P<name>...) named groups are SafeRE syntax, not JDK syntax")
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
    @DisabledForCrosscheck("(?P<name>...) named groups are SafeRE syntax, not JDK syntax")
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
    @DisabledForCrosscheck("SafeRE supports CharSequence group access that JDK cannot")
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
    @DisabledForCrosscheck("Matcher.toString() format is implementation-specific")
    @DisplayName("toString() reports pattern, region, and last match")
    void toStringReportsMatcherState() {
      Matcher m = Pattern.compile("a").matcher("ba");

      assertThat(m.toString())
          .contains("pattern=a")
          .contains("region=0,2")
          .contains("lastmatch=");

      assertThat(m.find()).isTrue();
      assertThat(m.toString())
          .contains("pattern=a")
          .contains("region=0,2")
          .contains("lastmatch=a");
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

    @Test
    @DisplayName("appendReplacement with region preserves text outside the region")
    void stringBufferAppendReplacementPreservesTextOutsideRegion() {
      Pattern p = Pattern.compile("\\d");
      Matcher m = p.matcher("ab1cd2ef");
      m.region(2, 6);
      StringBuffer sb = new StringBuffer();

      while (m.find()) {
        m.appendReplacement(sb, "X");
      }
      m.appendTail(sb);

      assertThat(sb.toString()).isEqualTo("abXcdXef");
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
    @DisabledForCrosscheck("java.util.regex backtracks on this SafeRE linear-time stress case")
    @DisplayName("region find stays linear for repeated dot-star captures")
    void regionFindWithRepeatedDotStarAndBoundedCaptures() {
      Pattern p = repeatedDotStarSqlUnionPattern();

      assertNoPerformanceCliff(
          "region().find()",
          blocks -> {
            String input = "prefix\n" + repeatedDotStarSqlUnionInput(blocks) + "suffix\n";
            Matcher m = p.matcher(input);
            m.region("prefix\n".length(), input.length() - "suffix\n".length());
            assertThat(m.find()).isTrue();
            assertThat(m.group(1)).contains("INFORMATION_SCHEMA");
          });
    }

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
    @DisabledForCrosscheck("#226 hitEnd/requireEnd state differs after region reset")
    @DisplayName("region clears stale requireEnd state")
    void regionClearsRequireEndState() {
      Pattern p = Pattern.compile("a$");
      Matcher m = p.matcher("a");
      assertThat(m.find()).isTrue();
      assertThat(m.requireEnd()).isTrue();

      m.region(0, 1);
      assertThat(m.hitEnd()).isFalse();
      assertThat(m.requireEnd()).isFalse();
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
    @DisplayName("^ does not match region start when anchoring bounds are disabled")
    void caretDoesNotMatchRegionStartWithoutAnchoringBounds() {
      Pattern p = Pattern.compile("^\\d+");
      Matcher m = p.matcher("abc123def");
      m.region(3, 6); // "123"
      m.useAnchoringBounds(false);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("$ does not match region end when anchoring bounds are disabled")
    void dollarDoesNotMatchRegionEndWithoutAnchoringBounds() {
      Pattern p = Pattern.compile("\\d+$");
      Matcher m = p.matcher("abc123def");
      m.region(3, 6); // "123"
      m.useAnchoringBounds(false);
      assertThat(m.find()).isFalse();
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

    @Test
    @DisplayName("transparent bounds let word boundary see before region start")
    void transparentBoundsWordBoundarySeesBeforeRegionStart() {
      Pattern p = Pattern.compile("\\bfoo");
      Matcher m = p.matcher("afoo");
      m.region(1, 4); // "foo", preceded by word char outside the region
      m.useTransparentBounds(true);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("transparent bounds let word boundary see after region end")
    void transparentBoundsWordBoundarySeesAfterRegionEnd() {
      Pattern p = Pattern.compile("foo\\b");
      Matcher m = p.matcher("fooa");
      m.region(0, 3); // "foo", followed by word char outside the region
      m.useTransparentBounds(true);
      assertThat(m.find()).isFalse();
    }
  }

  @Nested
  @DisplayName("hitEnd()")
  class HitEndTests {

    @Test
    @DisplayName("hitEnd is true when a variable-length match reaches end")
    void hitEndTrueForVariableLengthMatchAtEnd() {
      Pattern p = Pattern.compile("\\w+");
      Matcher m = p.matcher("abc");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("abc");
      assertThat(m.hitEnd()).isTrue();
      assertThat(m.requireEnd()).isFalse();
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
    @DisplayName("hitEnd is true when a variable-length match reaches region end")
    void hitEndTrueForVariableLengthMatchAtRegionEnd() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("abc123def456ghi");
      m.region(3, 6); // "123"
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("123");
      assertThat(m.hitEnd()).isTrue();
      assertThat(m.requireEnd()).isFalse();
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
    @DisplayName("hitEnd is false for literal matches()")
    void hitEndFalseForLiteralMatches() {
      Pattern p = Pattern.compile("abc");
      Matcher m = p.matcher("abc");
      assertThat(m.matches()).isTrue();
      assertThat(m.hitEnd()).isFalse();
    }

    @Test
    @DisplayName("hitEnd is true for variable-length matches() at end")
    void hitEndTrueForVariableLengthMatchesAtEnd() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("123");
      assertThat(m.matches()).isTrue();
      assertThat(m.hitEnd()).isTrue();
      assertThat(m.requireEnd()).isFalse();
    }

    @Test
    @DisplayName("hitEnd is false for literal lookingAt()")
    void hitEndFalseForLiteralLookingAt() {
      Pattern p = Pattern.compile("abc");
      Matcher m = p.matcher("abc");
      assertThat(m.lookingAt()).isTrue();
      assertThat(m.hitEnd()).isFalse();
    }

    @Test
    @DisplayName("hitEnd is false for failed matches() that does not need more input")
    void hitEndFalseForFailedLiteralMatches() {
      Pattern p = Pattern.compile("abc");
      Matcher m = p.matcher("abx");
      assertThat(m.matches()).isFalse();
      assertThat(m.hitEnd()).isFalse();
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
    @DisplayName("requireEnd() is true for dollar anchor before trailing terminator")
    void requireEndTrueForDollarAnchorBeforeTrailingTerminator() {
      Pattern p = Pattern.compile("abc$");
      Matcher m = p.matcher("abc\n");
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
    @DisabledForCrosscheck("#226 SafeRE and java.util.regex differ on requireEnd state after reset")
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
  @DisplayName("Repetition with nullable bodies")
  class RepetitionWithNullableBodies {

    @Test
    @DisplayName("(?:\\B|a)* stops after the first consuming iteration")
    void nonWordBoundaryOrCharStar() {
      // Regression for issue #55: JDK breaks a repetition loop after a zero-width body match.
      Pattern p = Pattern.compile("(?:\\B|a)*");
      Matcher m = p.matcher("aa");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(1);
    }

    @Test
    @DisplayName("(?:\\B|a)+ stops after the first consuming iteration")
    void nonWordBoundaryOrCharPlus() {
      Pattern p = Pattern.compile("(?:\\B|a)+");
      Matcher m = p.matcher("aa");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(1);
    }

    @Test
    @DisplayName("(?:\\b|a)* terminates on the zero-width word-boundary match")
    void wordBoundaryOrCharStar() {
      Pattern p = Pattern.compile("(?:\\b|a)*");
      Matcher m = p.matcher("aa");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(0);
    }

    @Test
    @DisplayName("(a|)* can consume before the empty exit match")
    void consumingOrEmptyStar() {
      Pattern p = Pattern.compile("(a|)*");
      Matcher m = p.matcher("aa");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(2);
      assertThat(m.start(1)).isEqualTo(2);
      assertThat(m.end(1)).isEqualTo(2);
    }

    @Test
    @DisplayName("(a|)+ can consume before the empty exit match")
    void consumingOrEmptyPlus() {
      Pattern p = Pattern.compile("(a|)+");
      Matcher m = p.matcher("aa");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(2);
    }

    @Test
    @DisplayName("([a]*)* captures the zero-width exit match")
    void starOfStar() {
      Pattern p = Pattern.compile("([a]*)*");
      Matcher m = p.matcher("aaaaaa");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(6);
      assertThat(m.start(1)).isEqualTo(6);
      assertThat(m.end(1)).isEqualTo(6);
    }

    @Test
    @DisplayName("X(.?){2,}Y captures the zero-width exit match")
    void boundedRepetitionCapture() {
      Pattern p = Pattern.compile("X(.?){2,}Y");
      Matcher m = p.matcher("XABCDEFY");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(8);
      assertThat(m.start(1)).isEqualTo(7);
      assertThat(m.end(1)).isEqualTo(7);
    }

    @Test
    @DisplayName("(?:$)+ finds the zero-width end-of-input match")
    void dollarPlus() {
      Pattern p = Pattern.compile("(?:$)+");
      Matcher m = p.matcher("a");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(1);
      assertThat(m.end()).isEqualTo(1);
    }

    @Test
    @DisplayName("(?:^)+ matches zero-width at the start of input")
    void caretPlus() {
      Pattern p = Pattern.compile("(?:^)+");
      Matcher m = p.matcher("a");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(0);
    }

    @Test
    @DisplayName("(?:(?:^)|\\w)* full-matches a word character")
    void caretOrWordStar() {
      assertThat(Pattern.matches("(?:(?:^)|\\w)*", "a")).isTrue();
    }

    @Test
    @DisplayName("(a|)*? prefers the empty match")
    void nonGreedyStarNullable() {
      Pattern p = Pattern.compile("(a|)*?");
      Matcher m = p.matcher("aa");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(0);
    }

    @Test
    @DisplayName("(a|)+? consumes the minimum non-empty match")
    void nonGreedyPlusNullable() {
      Pattern p = Pattern.compile("(a|)+?");
      Matcher m = p.matcher("aa");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(1);
    }

    @Test
    @DisplayName("(?:$|\\n)+ matches JDK dollar-newline repetition behavior")
    void dollarOrNewlinePlus() {
      Pattern p = Pattern.compile("(?:$|\\n)+");
      Matcher m = p.matcher("a\n\n");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(1);
      assertThat(m.end()).isEqualTo(2);
    }
  }

  @Test
  @DisabledForCrosscheck("#227 BitState crash for this generated regression case")
  @DisplayName("find() with empty branch before complex char class")
  void findEmptyBranchBeforeComplexCharClass() {
    Matcher m =
        Pattern.compile("|[\u0166&\u00bfA&&?;]+&?\udb7d\udda6+];]+&?&\u00e9v")
            .matcher("");

    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEmpty();
    assertThat(m.find()).isFalse();
  }

  private static String repeatedDotStarSqlUnionInput(int selectCount) {
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

  private static Pattern repeatedDotStarSqlUnionPattern() {
    return Pattern.compile(
        ".*SELECT.*FROM.*(.*INFORMATION_SCHEMA.*){5,}.*",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  }

  private static void assertNoPerformanceCliff(String api, IntConsumer scenario) {
    long largerPositiveNanos = runtimeNanos(() -> scenario.accept(16));
    long nearMinimumNanos = runtimeNanos(() -> scenario.accept(5));

    assertThat(nearMinimumNanos)
        .as("%s near-minimum input should not be dramatically slower than a larger "
            + "positive input; near=%d ns, larger=%d ns",
            api, nearMinimumNanos, largerPositiveNanos)
        .isLessThan(largerPositiveNanos * 50);
  }

  private static long runtimeNanos(Runnable task) {
    return assertTimeoutPreemptively(
        PERFORMANCE_SCENARIO_TIMEOUT,
        () -> {
          long start = System.nanoTime();
          task.run();
          return System.nanoTime() - start;
        });
  }

  private static String repeatedDotStarCapturePattern(int repetitions, int captures) {
    StringBuilder pattern = new StringBuilder("(");
    for (int i = 0; i < captures; i++) {
      pattern.append("(.*").append((char) ('A' + i)).append(")");
    }
    pattern.append("){").append(repetitions).append(",}");
    return pattern.toString();
  }

  private static String repeatedDotStarCaptureInput(int repetitions, int captures) {
    StringBuilder input = new StringBuilder();
    for (int i = 0; i < repetitions; i++) {
      for (int j = 0; j < captures; j++) {
        input.append((char) ('x' + j)).append((char) ('A' + j));
      }
    }
    return input.toString();
  }

  private static void assertNoSuperlinearRepeatedDotStarCaptureScaling(IntConsumer scenario) {
    scenario.accept(100);
    scenario.accept(500);

    int smallRepetitions = 100;
    int largeRepetitions = 500;
    long smallNanos = bestRuntimeNanos(5, () -> scenario.accept(smallRepetitions));
    long largeNanos = bestRuntimeNanos(5, () -> scenario.accept(largeRepetitions));
    long smallNanosPerRepetition = smallNanos / smallRepetitions;
    long largeNanosPerRepetition = largeNanos / largeRepetitions;

    // This is a regression guard for accidental superlinear capture extraction, not a
    // microbenchmark. The wide per-repetition allowance absorbs ordinary full-suite
    // timing noise while still failing on the dramatic cliff this test was added to catch.
    assertThat(largeNanos)
        .as("larger repeated-capture input should scale roughly with input size; "
            + "small=%d ns, large=%d ns",
            smallNanos, largeNanos)
        .isLessThan(smallNanos * largeRepetitions / smallRepetitions * 12);
    assertThat(largeNanosPerRepetition)
        .as("larger repeated-capture input should not be dramatically slower per repetition; "
            + "small=%d ns/repetition, large=%d ns/repetition",
            smallNanosPerRepetition, largeNanosPerRepetition)
        .isLessThan(smallNanosPerRepetition * 12);
  }

  private static long bestRuntimeNanos(int runs, Runnable task) {
    long best = Long.MAX_VALUE;
    for (int i = 0; i < runs; i++) {
      long start = System.nanoTime();
      task.run();
      best = Math.min(best, System.nanoTime() - start);
    }
    return best;
  }
}
