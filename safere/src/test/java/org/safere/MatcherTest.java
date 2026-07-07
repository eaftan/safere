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
import java.util.function.Consumer;
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
    @DisplayName("matches() fast-rejects when required whitespace is absent")
    void matchesFastRejectsWhenRequiredWhitespaceIsAbsent() {
      Matcher m = Pattern.compile(".*\\s+.*").matcher("44241504-44f6-4d2a-bdcb-1bf7fd927ba9");

      assertThat(m.matches()).isFalse();
    }

    @Test
    @DisplayName("matches() required-class precheck preserves positive cases")
    void matchesRequiredClassPrecheckPreservesPositiveCases() {
      Pattern p = Pattern.compile(".*\\s+.*");

      for (String input : new String[] {"a b", "a\nb c", "a\n \nc", "a\n\nc"}) {
        assertThat(p.matcher(input).matches())
            .as("SafeRE and JDK should agree on %s", input.replace("\n", "\\n"))
            .isEqualTo(java.util.regex.Pattern.compile(".*\\s+.*").matcher(input).matches());
      }
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
    @DisabledForCrosscheck("java.util.regex backtracking makes this a SafeRE linear-time check")
    @DisplayName("group access stays linear for ambiguous repeated captures")
    void groupAccessWithAmbiguousRepeatedCapturesStaysLinear() {
      Pattern p = Pattern.compile("((a|aa))*");

      assertNoPerformanceCliff(
          "matches()+group(1)",
          "a".repeat(100),
          "a".repeat(5_000),
          input -> {
            Matcher m = p.matcher(input);
            assertThat(m.matches()).isTrue();
            assertThat(m.group(1)).isEqualTo("a");
            assertThat(m.group(2)).isEqualTo("a");
          });
    }

    @Test
    @DisabledForCrosscheck("java.util.regex backtracking makes this a SafeRE linear-time check")
    @DisplayName("group access stays stack-safe for large repeated captures")
    void groupAccessWithLargeRepeatedCapturesStaysStackSafe() {
      assertCompletesWithinPerformanceTimeout(
          () -> {
            Matcher m = Pattern.compile("(a)*").matcher("a".repeat(20_000));
            assertThat(m.matches()).isTrue();
            assertThat(m.group(1)).isEqualTo("a");
          });
    }

    @Test
    @DisplayName("find() falls back when OnePass analysis exceeds its memory budget")
    void findFallsBackWhenOnePassAnalysisExceedsMemoryBudget() {
      String regex =
          "SELECT"
              + " REGE|||~\\u06ec?<?|||GEXP_REPLACe(^\"\\uffff\\uffff\\uffff\\uffff"
              + "\\uffff\\uffff\\uffff\\uffff"
              + " \\u00bf\\u7000\\u0400\\u5000\\u07b7@\\u02aa \\X@?` \\u0296"
              + " \\ua03d,\\u0106mv &.\\u017c&.?\t\t?\t\\uffff\\ufff1end1 68  ){680}"
              + "$ .1";
      String input = ",69\u00c3";

      boolean expected = java.util.regex.Pattern.compile(regex).matcher(input).find();

      assertThat(Pattern.compile(regex).matcher(input).find()).isEqualTo(expected);
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
    @DisplayName("matches() completes for repeated dot-star bodies with multiple captures")
    void matchesWithRepeatedDotStarBodiesAndMultipleCaptures() {
      String pattern = repeatedDotStarCapturePattern(500, 3);
      String input = repeatedDotStarCaptureInput(500, 3);

      assertCompletesWithinPerformanceTimeout(
          () -> {
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
    @DisplayName("find() required-class precheck starts at current search position")
    void findRequiredClassPrecheckStartsAtCurrentSearchPosition() {
      Matcher m = Pattern.compile("\\b{g}z").matcher("z\uD83C\uDDE6\uD83C\uDDE6");

      assertThat(m.find(1)).isFalse();
    }

    @Test
    @DisplayName("find() required-literal precheck respects case-insensitive flags")
    void findRequiredLiteralPrecheckRespectsCaseInsensitiveFlags() {
      Matcher m = Pattern.compile("(?i:abc)def").matcher("ABCdef");

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("ABCdef");
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
      assertThatThrownBy(() -> m.find(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("find(int) throws for start past end of input")
    void findWithStartPastEnd() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("abc");
      assertThatThrownBy(() -> m.find(4)).isInstanceOf(IndexOutOfBoundsException.class);
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
          """
          java.lang.IllegalStateException: failed
          \tat org.example.api.Handler.handle(Handler.java:87)
          Caused by: java.io.IOException: timeout\r
          \tat org.example.net.Client.read(Client.java:203)\
          """);
    }

    @Test
    @DisplayName("find() start acceleration handles Unicode line terminators")
    void findStartAccelerationWithUnicodeLineTerminators() {
      assertAllFindsMatchJdk("(?m)^\\s+at\\s+(\\w+)$", "header\u2028\tat alpha\u2029\tat beta");
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "[^{']*(?:'[^']*'[^{']*)*\\{([^}]*)\\}",
          "[^']*(?:'[^']*'[^']*)*END",
          "[^<]*(?:<[^>]*>[^<]*)*TARGET"
        })
    @DisplayName("find() preserves leftmost start before a later required literal")
    void findPreservesLeftmostStartBeforeLaterRequiredLiteral(String regex) {
      String input =
          switch (regex) {
            case "[^{']*(?:'[^']*'[^{']*)*\\{([^}]*)\\}" -> "Foo '{0}' Bar: {0}";
            case "[^']*(?:'[^']*'[^']*)*END" -> "prefix 'not END' suffix END";
            case "[^<]*(?:<[^>]*>[^<]*)*TARGET" -> "prefix <not TARGET> suffix TARGET";
            default -> throw new AssertionError("unexpected regex: " + regex);
          };

      assertFirstFindMatchesJdk(regex, input);
    }

    private static void assertAllFindsMatchJdk(String regex, String input) {
      assertAllFindsMatchJdk(regex, 0, input);
    }

    private static void assertAllFindsMatchJdk(String regex, int flags, String input) {
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
    @ValueSource(
        strings = {
          "(bcd|abcde)",
          "(.+?X|bc)",
          "(?:a{1,3})?a{3}",
          "(?m)^\\s+at\\s+([A-Za-z0-9_.$]+)\\.([A-Za-z0-9_$<>]+)\\(([^:()]+):(\\d+)\\)$",
          "(?i)\\b(error|warning|timeout|failed)\\b"
        })
    @DisplayName("find() fallback paths match JDK for unreliable DFA-start patterns")
    void findFallbackMatchesJdkForUnreliableDfaStartPatterns(String regex) {
      String text =
          """
          java.lang.IllegalStateException: failed
          \tat org.example.Main.main(Main.java:25)
          xxabcde yy aaa failed bcX\
          """;
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
    @ValueSource(strings = {"\n", "\r\n", "\u2028"})
    @DisplayName("find() after $ before a trailing terminator advances to text end")
    void findAfterDollarBeforeTrailingTerminatorAdvancesToTextEnd(String terminator) {
      assertAllFindsMatchJdk("$", "x".repeat(2000) + "a" + terminator);
    }

    @Test
    @DisplayName("find() falls back when reverse DFA start is ambiguous")
    void findFallsBackWhenReverseDfaStartIsAmbiguous() {
      assertAllFindsMatchJdk("(?:\\B{1}|a).a?", "ab".repeat(600) + "c");
    }

    @Test
    @DisplayName("find() does not overconsume before trailing terminator for end anchor")
    void findDoesNotOverconsumeBeforeTrailingTerminatorForEndAnchor() {
      assertAllFindsMatchJdk("(?:a+?|(?:[^x])*)$", "x".repeat(1100) + "a\n");
    }

    @Test
    @DisplayName("find() preserves boundary candidate priority in reverse DFA fallback")
    void findPreservesBoundaryCandidatePriorityInReverseDfaFallback() {
      assertAllFindsMatchJdk("(?:a{2,}|(?:.|\\B){1,2}){1,2}", "baax");
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
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

    @Test
    @DisplayName("find() preserves leftmost start for zero-width alternation branches")
    void findPreservesLeftmostStartForZeroWidthAlternationBranches() {
      assertFirstFindMatchesJdk("(?:\\B{1}|a).", "ab");
      assertFirstFindMatchesJdk("(?:a|\\B?).", "ab");
      assertFirstFindMatchesJdk("(?:a|(\\B)?).", "ab");
      assertFirstFindMatchesJdk("(?:(?:)\\B|a).", "ab");
    }

    @Test
    @DisplayName("find() preserves leftmost start before repeated consuming tails")
    void findPreservesLeftmostStartBeforeRepeatedConsumingTails() {
      assertFirstFindMatchesJdk("\\B([^a])*[^a][^a]", "bbbb");
      assertFirstFindMatchesJdk("\\B(?:[^a])*[^a][^a]", "bbbb");
      assertFirstFindMatchesJdk("\\B[^a]*[^a][^a]", "bbbb");
      assertFirstFindMatchesJdk("\\B(?:b|bb)*bb", "bbbb");
    }

    @Test
    @DisplayName("find() keeps searching after a failed boundary-prefixed literal candidate")
    void findKeepsSearchingAfterFailedBoundaryPrefixedLiteralCandidate() {
      assertFirstFindMatchesJdk("\\bfoo", "_foo foo");
      assertFirstFindMatchesJdk("\\Bfoo", " foo afoo");
    }

    @Test
    @DisplayName("find() respects non-word boundaries inside repetitions")
    void findNonWordBoundaryInsideRepetitions() {
      Pattern p = Pattern.compile("(?:(?:(?:\\B)\\w)*)");
      Matcher m = p.matcher("aba");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(0);
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(1);
      assertThat(m.end()).isEqualTo(3);
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(3);
      assertThat(m.end()).isEqualTo(3);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName(
        "find() preserves leftmost start for overlapping alternation branches (b/528687345)")
    void testAlternationPriorityInversion() {
      String regex = "(?:\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*')|(\\btype\\b)";
      String input = "x = \"type\"";
      Matcher m = Pattern.compile(regex).matcher(input);

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("\"type\"");
      assertThat(m.group(1)).isNull();
    }

    @Test
    @DisplayName("find() preserves leftmost start for overlapping lazy quantifiers (b/528722782)")
    void testLazyQuantifierPriorityInversion() {
      String regex = "\\[.*?\\]\\((.*?)\\)|(\\b\\w+\\.md\\b)";
      String input = "abc [def](xyz.md) ghi";
      Matcher m = Pattern.compile(regex).matcher(input);

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("[def](xyz.md)");
      assertThat(m.group(1)).isEqualTo("xyz.md");
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
      Pattern p = Pattern.compile("(?<first>\\w+) (?<last>\\w+)");
      Matcher m = p.matcher("John Smith");
      assertThat(m.matches()).isTrue();
      assertThat(m.group("first")).isEqualTo("John");
      assertThat(m.group("last")).isEqualTo("Smith");
    }

    @Test
    @DisplayName("group(String) throws for unknown name")
    void namedGroupUnknown() {
      Pattern p = Pattern.compile("(?<first>\\w+)");
      Matcher m = p.matcher("hello");
      m.find();
      assertThatThrownBy(() -> m.group("unknown")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toMatchResult() snapshot supports named-group lookup")
    void toMatchResultNamedGroups() {
      Pattern p = Pattern.compile("(?<word>\\w+)");
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
      Pattern p = Pattern.compile("(?<word>\\w+)@(?<host>\\w+)");
      Matcher m = p.matcher("user@host");
      assertThat(m.find()).isTrue();
      assertThat(m.start("word")).isEqualTo(0);
      assertThat(m.end("word")).isEqualTo(4);
      assertThat(m.start("host")).isEqualTo(5);
      assertThat(m.end("host")).isEqualTo(9);
    }

    @Test
    @DisplayName("start(String) throws for unknown name")
    void namedGroupStartUnknownThrows() {
      Pattern p = Pattern.compile("(?<word>\\w+)");
      Matcher m = p.matcher("hello");
      m.find();
      assertThatThrownBy(() -> m.start("missing")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("end(String) throws for unknown name")
    void namedGroupEndUnknownThrows() {
      Pattern p = Pattern.compile("(?<word>\\w+)");
      Matcher m = p.matcher("hello");
      m.find();
      assertThatThrownBy(() -> m.end("missing")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("named group that did not participate returns null and -1 positions")
    void nonParticipatingNamedGroup() {
      Pattern p = Pattern.compile("(?<a>a)|(?<b>b)");
      Matcher m = p.matcher("b");
      assertThat(m.find()).isTrue();
      assertThat(m.group("a")).isNull();
      assertThat(m.start("a")).isEqualTo(-1);
      assertThat(m.end("a")).isEqualTo(-1);
      assertThat(m.group("b")).isEqualTo("b");
      assertThat(m.start("b")).isEqualTo(0);
      assertThat(m.end("b")).isEqualTo(1);
    }

    @Test
    @DisplayName("namedGroups() returns named groups from pattern")
    void namedGroupsReturnsMap() {
      Pattern p = Pattern.compile("(?<user>\\w+)@(?<host>\\w+)");
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
      Pattern p = Pattern.compile("(?<name>\\w+)");
      Matcher m = p.matcher("hello");
      assertThatThrownBy(() -> m.namedGroups().put("foo", 99))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("namedGroups() returns from MatchResult interface")
    void namedGroupsFromMatchResult() {
      Pattern p = Pattern.compile("(?<word>\\w+)");
      Matcher m = p.matcher("hello");
      assertThat(m.find()).isTrue();
      MatchResult result = m.toMatchResult();
      assertThat(result.namedGroups()).containsEntry("word", 1);
    }

    @Test
    @DisplayName("named group methods reject null names")
    void namedGroupMethodsRejectNullNames() {
      Pattern p = Pattern.compile("(?<word>\\w+)");
      Matcher m = p.matcher("hello");
      assertThat(m.find()).isTrue();

      assertThatThrownBy(() -> m.group((String) null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> m.start((String) null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> m.end((String) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("groupCount() returns number of capturing groups (excluding group 0)")
    void groupCount() {
      Pattern p = Pattern.compile("(a)(b)(c)");
      Matcher m = p.matcher("abc");
      assertThat(m.groupCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("zero-count repetitions preserve capturing group count")
    void zeroCountRepetitionsPreserveCapturingGroupCount() {
      assertZeroCountGroupBehavior("(a){0}", "");
      assertZeroCountGroupBehavior("(?:(a){0})", "");
      assertZeroCountGroupBehavior("(?:(?:(a){0}))", "");
      assertZeroCountGroupBehavior("(a){0}(b)", "b");
      assertZeroCountGroupBehavior("((a){0})", "");
    }

    @Test
    @DisplayName("find() matches JDK captures for repeated empty groups")
    void findMatchesJdkCapturesForRepeatedEmptyGroups() {
      assertFirstFindMatchesJdk("()*", "");
      assertFirstFindMatchesJdk("(())*", "");
      assertFirstFindMatchesJdk("(?:())*", "");
      assertFirstFindMatchesJdk("(a?)*", "");
      assertFirstFindMatchesJdk("(?:((^){2}))*", "");
      assertFirstFindMatchesJdk("$|^Fe|()\u00fc|()*|||||^D*4", "\u007f*\u007f");
    }

    @Test
    @DisplayName("find() preserves counted-repeat captures retained by JDK")
    void findPreservesCountedRepeatCapturesRetainedByJdk() {
      assertFirstFindMatchesJdk("(?:(a){1}){0}$", "ab");
      assertFirstFindMatchesJdk("(?:(a){1}){0,1}$", "ab");
      assertFirstFindMatchesJdk("(?:(a){1,}){2}", "aaa");
      assertFullMatchMatchesJdk("(?:(a){1,}){2}", "aaa");
      assertFirstFindMatchesJdk("(?:(a){1,}){2}", "aa");
      assertFullMatchMatchesJdk("(?:(a){1,}){2}", "aa");
      assertFirstFindMatchesJdk("(?:(a){1,}){2,}", "aaa");
      assertFullMatchMatchesJdk("(?:(a){1,}){2,}", "aaa");
      assertFirstFindMatchesJdk("(?:(a){1,}){2,3}", "aaaaa");
      assertFullMatchMatchesJdk("(?:(a){1,}){2,3}", "aaaaa");
      assertFirstFindMatchesJdk("(?:(a){0,2})*", "aaa");
      assertFullMatchMatchesJdk("(?:(a){0,2})*", "aaa");
      assertFirstFindMatchesJdk("(?:(a){1,2})*", "aaa");
      assertFullMatchMatchesJdk("(?:(a){1,2})*", "aaa");
      assertFirstFindMatchesJdk("(?:(a){1,2}){2}", "aa");
      assertFullMatchMatchesJdk("(?:(a){1,2}){2}", "aa");
      assertFirstFindMatchesJdk("(?:(a){1,2}){2}", "aaa");
      assertFullMatchMatchesJdk("(?:(a){1,2}){2}", "aaa");
      assertFirstFindMatchesJdk("(?:(a){3,4})*", "aaaaaa");
      assertFullMatchMatchesJdk("(?:(a){3,4})*", "aaaaaa");
      assertFirstFindMatchesJdk("(?:(a){0,1})*", "aaa");
      assertFullMatchMatchesJdk("(?:(a){0,1})*", "aaa");
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
      assertThatThrownBy(m::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("group() throws IllegalStateException before any match")
    void groupNoMatch() {
      Pattern p = Pattern.compile("x");
      Matcher m = p.matcher("abc");
      assertThatThrownBy(m::group).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("end() throws IllegalStateException before any match")
    void endNoMatch() {
      Pattern p = Pattern.compile("x");
      Matcher m = p.matcher("abc");
      assertThatThrownBy(m::end).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("start(int) throws IndexOutOfBoundsException for invalid group")
    void invalidGroupIndexPositive() {
      Pattern p = Pattern.compile("(a)");
      Matcher m = p.matcher("a");
      m.find();
      assertThatThrownBy(() -> m.start(2)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("start(int) throws IndexOutOfBoundsException for negative group")
    void invalidGroupIndexNegative() {
      Pattern p = Pattern.compile("(a)");
      Matcher m = p.matcher("a");
      m.find();
      assertThatThrownBy(() -> m.start(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("end(int) throws IndexOutOfBoundsException for invalid group")
    void invalidGroupIndexEnd() {
      Pattern p = Pattern.compile("(a)");
      Matcher m = p.matcher("a");
      m.find();
      assertThatThrownBy(() -> m.end(2)).isInstanceOf(IndexOutOfBoundsException.class);
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
      assertThat(Matcher.quoteReplacement("$foo\\bar$")).isEqualTo("\\$foo\\\\bar\\$");
      assertThat(Matcher.quoteReplacement("")).isEqualTo("");
    }

    @Test
    @DisplayName("quoteReplacement() result used in replaceAll() is literal")
    void quoteReplacementInReplace() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("a1b2c3");
      assertThat(m.replaceAll(Matcher.quoteReplacement("$0"))).isEqualTo("a$0b$0c$0");
    }

    @Test
    @DisplayName("replaceFirst() replaces only the first match")
    void replaceFirst() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("a1b2c3");
      assertThat(m.replaceFirst("X")).isEqualTo("aXb2c3");
    }

    @Test
    @DisplayName("replaceFirst() retains capture group state of the matched occurrence")
    void replaceFirstRetainsCaptureGroups() {
      Pattern p = Pattern.compile("testingRecord\\[(.+?)\\]");
      Matcher m = p.matcher("object.testingDetails.testingRecord[0].result");
      if (m.find()) {
        m.replaceFirst("testingRecord");
        assertThat(m.group(1)).isEqualTo("0");
      }
    }

    @Test
    @DisplayName("replaceAll() replaces all matches")
    void replaceAll() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("a1b2c3");
      assertThat(m.replaceAll("X")).isEqualTo("aXbXcX");
    }

    @Test
    @DisplayName("replaceFirst() leaves find() positioned after the replaced match")
    void replaceFirstLeavesFindPositionAfterReplacedMatch() {
      Pattern p = Pattern.compile("[a-z]+");
      Matcher m = p.matcher("123abc456def");

      assertThat(m.replaceFirst("X")).isEqualTo("123X456def");

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("def");
    }

    @Test
    @DisplayName("replaceAll() leaves find() exhausted after replacing matches")
    void replaceAllLeavesFindExhaustedAfterReplacingMatches() {
      Pattern p = Pattern.compile("[a-z]+");
      Matcher m = p.matcher("123abc456def");

      assertThat(m.replaceAll("X")).isEqualTo("123X456X");

      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("replaceFirst() leaves appendTail positioned after the replaced match")
    void replaceFirstLeavesAppendTailPositionAfterReplacedMatch() {
      Pattern p = Pattern.compile("[a-z]+");
      Matcher m = p.matcher("123abc456def");
      StringBuilder sb = new StringBuilder();

      assertThat(m.replaceFirst("X")).isEqualTo("123X456def");
      m.appendTail(sb);

      assertThat(sb).hasToString("456def");
    }

    @Test
    @DisplayName("replaceAll() leaves appendTail positioned after the last replaced match")
    void replaceAllLeavesAppendTailPositionAfterLastReplacedMatch() {
      Pattern p = Pattern.compile("[a-z]+");
      Matcher m = p.matcher("123abc456def789");
      StringBuilder sb = new StringBuilder();

      assertThat(m.replaceAll("X")).isEqualTo("123X456X789");
      m.appendTail(sb);

      assertThat(sb).hasToString("789");
    }

    @Test
    @DisplayName("replaceAll() preserves lazy character-class match boundaries")
    void replaceAllPreservesLazyCharacterClassMatchBoundaries() {
      Pattern p = Pattern.compile("[a-z]+?");
      Matcher m = p.matcher("abc");

      assertThat(m.replaceAll("X")).isEqualTo("XXX");
    }

    @Test
    @DisplayName("replaceFirst() preserves lazy character-class match boundaries")
    void replaceFirstPreservesLazyCharacterClassMatchBoundaries() {
      Pattern p = Pattern.compile("[a-z]+?");
      Matcher m = p.matcher("abc");

      assertThat(m.replaceFirst("X")).isEqualTo("Xbc");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a?", "a*", ".*", ".?"})
    @DisplayName("replaceAll() with nullable pattern matches JDK replacement sequence")
    void replaceAllNullablePatternMatchesJdkReplacementSequence(String regex) {
      String input = "a";

      assertThat(Pattern.compile(regex).matcher(input).replaceAll("X"))
          .isEqualTo(java.util.regex.Pattern.compile(regex).matcher(input).replaceAll("X"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a?", "a*", ".*", ".?"})
    @DisplayName("replaceFirst() with nullable pattern matches JDK replacement sequence")
    void replaceFirstNullablePatternMatchesJdkReplacementSequence(String regex) {
      String input = "a";

      assertThat(Pattern.compile(regex).matcher(input).replaceFirst("X"))
          .isEqualTo(java.util.regex.Pattern.compile(regex).matcher(input).replaceFirst("X"));
    }

    @Test
    @DisplayName("replaceAll() with nullable alternation matches JDK replacement sequence")
    void replaceAllNullableAlternationMatchesJdkReplacementSequence() {
      String regex = "(?:\\B|a).a?";
      String input = "bbaaa";

      assertThat(Pattern.compile(regex).matcher(input).replaceAll("X"))
          .isEqualTo(java.util.regex.Pattern.compile(regex).matcher(input).replaceAll("X"));
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

    @ParameterizedTest
    @ValueSource(strings = {"$", "\\"})
    @DisplayName("replaceAll() with no match does not validate malformed replacement")
    void replaceAllNoMatchDoesNotValidateMalformedReplacement(String replacement) {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("abc");

      assertThat(m.replaceAll(replacement)).isEqualTo("abc");
    }

    @ParameterizedTest
    @ValueSource(strings = {"$", "\\"})
    @DisplayName("replaceFirst() with no match does not validate malformed replacement")
    void replaceFirstNoMatchDoesNotValidateMalformedReplacement(String replacement) {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("abc");

      assertThat(m.replaceFirst(replacement)).isEqualTo("abc");
    }

    @ParameterizedTest
    @ValueSource(strings = {"$", "\\"})
    @DisplayName("replaceAll() with malformed replacement records first match before throwing")
    void replaceAllMalformedReplacementRecordsFirstMatch(String replacement) {
      Pattern p = Pattern.compile("a.");
      Matcher m = p.matcher("abc");

      assertThatThrownBy(() -> m.replaceAll(replacement))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(2);
      assertThat(m.group()).isEqualTo("ab");
    }

    @ParameterizedTest
    @ValueSource(strings = {"$", "\\"})
    @DisplayName("replaceFirst() with malformed replacement records first match before throwing")
    void replaceFirstMalformedReplacementRecordsFirstMatch(String replacement) {
      Pattern p = Pattern.compile("a.");
      Matcher m = p.matcher("abc");

      assertThatThrownBy(() -> m.replaceFirst(replacement))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(2);
      assertThat(m.group()).isEqualTo("ab");
    }

    @ParameterizedTest
    @ValueSource(strings = {"$", "\\"})
    @DisplayName("replaceAll() char-class fast path records first match before throwing")
    void replaceAllCharClassMalformedReplacementRecordsFirstMatch(String replacement) {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("123 abc");

      assertThatThrownBy(() -> m.replaceAll(replacement))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(3);
      assertThat(m.group()).isEqualTo("123");
    }

    @ParameterizedTest
    @ValueSource(strings = {"$", "\\"})
    @DisplayName("replaceFirst() char-class fast path records first match before throwing")
    void replaceFirstCharClassMalformedReplacementRecordsFirstMatch(String replacement) {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("123 abc");

      assertThatThrownBy(() -> m.replaceFirst(replacement))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(3);
      assertThat(m.group()).isEqualTo("123");
    }

    @Test
    @DisplayName("replaceFirst() with no match rejects null replacement")
    void replaceFirstNoMatchRejectsNullReplacement() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("abc");

      assertThatThrownBy(() -> m.replaceFirst((String) null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("replaceFirst() with start-anchored pattern replaces correct match")
    void replaceFirstStartAnchored() {
      Pattern p = Pattern.compile("^\\d+");
      Matcher m = p.matcher("123abc456");
      assertThat(m.replaceFirst("X")).isEqualTo("Xabc456");
    }

    @Test
    @DisplayName("replaceFirst() leaves matcher state at the replaced occurrence")
    void replaceFirstLeavesMatcherAtReplacedOccurrence() {
      Matcher m = Pattern.compile("a").matcher("aba");

      assertThat(m.replaceFirst("X")).isEqualTo("Xba");

      assertThat(m.toMatchResult().start()).isEqualTo(0);
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(2);
    }

    @Test
    @DisplayName("replaceFirst() leaves append position after the replaced occurrence")
    void replaceFirstLeavesAppendPositionAfterReplacedOccurrence() {
      Matcher m = Pattern.compile("a").matcher("aba");

      assertThat(m.replaceFirst("X")).isEqualTo("Xba");
      StringBuilder sb = new StringBuilder();
      m.appendTail(sb);

      assertThat(sb).hasToString("ba");
    }

    @Test
    @DisplayName("replaceFirst() preserves dollar-anchor match before trailing line terminator")
    void replaceFirstPreservesDollarAnchorBeforeTrailingLineTerminator() {
      Matcher m = Pattern.compile("([^a](\\s\\s)+)*$").matcher("a\n");

      assertThat(m.replaceFirst("X")).isEqualTo("aX\n");
    }

    @Test
    @DisplayName("replaceAll() with start-anchored pattern replaces correct match")
    void replaceAllStartAnchored() {
      Pattern p = Pattern.compile("^\\d+");
      Matcher m = p.matcher("123abc456");
      assertThat(m.replaceAll("X")).isEqualTo("Xabc456");
    }

    @Test
    @DisplayName("replaceAll() leaves matcher exhausted after the final replacement")
    void replaceAllLeavesMatcherExhaustedAfterFinalReplacement() {
      Matcher m = Pattern.compile("a").matcher("aba");

      assertThat(m.replaceAll("X")).isEqualTo("XbX");

      StringBuilder sb = new StringBuilder();
      m.appendTail(sb);
      assertThat(sb).hasToString("");
      assertThat(m.find()).isFalse();
      assertThatThrownBy(() -> m.toMatchResult().start()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("replaceAll() with numeric backreference")
    void replaceAllWithBackref() {
      Pattern p = Pattern.compile("(\\w+)");
      Matcher m = p.matcher("hello world");
      assertThat(m.replaceAll("[$1]")).isEqualTo("[hello] [world]");
    }

    @Test
    @DisplayName("replaceAll() with ambiguous reverse DFA match start handles correctness")
    void replaceAllWithAmbiguousReverseDfaMatchStart() {
      Pattern p = Pattern.compile("(b|ab)c");
      Matcher m = p.matcher("abc");
      assertThat(m.replaceAll("X")).isEqualTo("X");
    }

    @Test
    @DisplayName("find() with alternation prefers leftmost match over earliest DFA end")
    void testAlternationDfaSandwichCorrectness() {
      Pattern p = Pattern.compile("b|a.c");
      Matcher m = p.matcher("abc");
      boolean found = m.find();
      assertThat(found).isTrue();
      assertThat(m.group()).isEqualTo("abc");
    }

    @Test
    @DisplayName("replaceAll with group references preserves start-anchor find semantics")
    void replaceAllWithGroupReferencesPreservesStartAnchorFindSemantics() {
      String[][] cases = {
        {"^(.*)", "ExistingValue", "An$1"},
        {"\\A(.*)", "ExistingValue", "An$1"},
        {"^(a*)", "aaa", "[$1]"},
        {"\\A(a*)", "aaa", "[$1]"},
        {"^(a*)", "bbb", "[$1]"},
        {"\\A(a*)", "bbb", "[$1]"},
        {"^([0-9].*)", "abc123", "<$1>"},
        {"\\A([0-9].*)", "abc123", "<$1>"},
      };

      for (String[] tc : cases) {
        String pattern = tc[0];
        String input = tc[1];
        String replacement = tc[2];
        assertThat(Pattern.compile(pattern).matcher(input).replaceAll(replacement))
            .as("replaceAll(%s) for /%s/ on %s", replacement, pattern, input)
            .isEqualTo(
                java.util.regex.Pattern.compile(pattern).matcher(input).replaceAll(replacement));
      }
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
    @DisplayName("numeric replacement reference with invalid digit does not throw if no match")
    void numericReplacementReferenceInvalidFirstDigitDoesNotThrowIfNoMatch() {
      Pattern p = Pattern.compile("\\d+");

      assertThat(p.matcher("foobar").replaceAll("$99")).isEqualTo("foobar");
    }

    @Test
    @DisplayName("replaceAll() with named backreference")
    void replaceAllWithNamedBackref() {
      Pattern p = Pattern.compile("(?<word>\\w+)");
      Matcher m = p.matcher("hello world");
      assertThat(m.replaceAll("${word}!")).isEqualTo("hello! world!");
    }

    @Test
    @DisplayName("replaceFirst() with named backreference")
    void replaceFirstWithNamedBackref() {
      Pattern p = Pattern.compile("(?<word>\\w+)");
      Matcher m = p.matcher("hello world");
      assertThat(m.replaceFirst("${word}!")).isEqualTo("hello! world");
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

    @Test
    @DisplayName("replaceAll() with dollar anchor and alternation matches JDK")
    void testReplaceAllDollarAnchorAlternation() {
      String regex = "(?:a+?|(?:[^x])*)$";
      String input = "x".repeat(1100) + "a\n";
      Pattern p = Pattern.compile(regex);
      Matcher m = p.matcher(input);
      String result = m.replaceAll("X");

      java.util.regex.Pattern jdkP = java.util.regex.Pattern.compile(regex);
      java.util.regex.Matcher jdkM = jdkP.matcher(input);
      String jdkResult = jdkM.replaceAll("X");

      assertThat(result).isEqualTo(jdkResult);
    }

    @Test
    @DisplayName("find() with ambiguous reverse DFA start matches JDK")
    void testFindAmbiguousReverseDfa() {
      String regex = "(?:\\B|a).a?";
      String input = "ab".repeat(600) + "c";
      Pattern p = Pattern.compile(regex);
      Matcher m = p.matcher(input);
      java.util.regex.Pattern jdkP = java.util.regex.Pattern.compile(regex);
      java.util.regex.Matcher jdkM = jdkP.matcher(input);

      while (jdkM.find()) {
        assertThat(m.find()).isTrue();
        assertThat(m.start()).isEqualTo(jdkM.start());
        assertThat(m.end()).isEqualTo(jdkM.end());
      }
      assertThat(m.find()).isFalse();
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

      assertThat(m.toString()).contains("pattern=a").contains("region=0,2").contains("lastmatch=");

      assertThat(m.find()).isTrue();
      assertThat(m.toString()).contains("pattern=a").contains("region=0,2").contains("lastmatch=a");
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
    @DisplayName("toMatchResult() before a match defers failures to result access")
    void toMatchResultBeforeMatchDefersFailuresToResultAccess() {
      Pattern p = Pattern.compile("(?<letter>x)(y)?");
      Matcher m = p.matcher("abc");

      MatchResult result = m.toMatchResult();

      assertThat(result.groupCount()).isEqualTo(2);
      assertThat(result.namedGroups()).containsEntry("letter", 1);
      assertThatThrownBy(result::start).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(result::end).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(result::group).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> result.start(1)).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> result.start(99)).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> result.group("letter")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("toMatchResult() after a failed match defers failures to result access")
    void toMatchResultAfterFailedMatchDefersFailuresToResultAccess() {
      Pattern p = Pattern.compile("x");
      Matcher m = p.matcher("abc");
      assertThat(m.find()).isFalse();

      MatchResult result = m.toMatchResult();

      assertThat(result.groupCount()).isZero();
      assertThatThrownBy(result::start).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(result::group).isInstanceOf(IllegalStateException.class);
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

    @Test
    @DisplayName("find() with negated classes can start at lone surrogates like JDK")
    void findWithNegatedClassesCanStartAtLoneSurrogatesLikeJdk() {
      assertFirstFindMatchesJdk("[^a-z]..", "\uD801\uD800\uDC00xyz");
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
  @SuppressWarnings("JdkObsolete") // Testing legacy StringBuffer API compatibility
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
    @DisplayName("quantified capture extraction evaluates anchors against opaque region bounds")
    void quantifiedCaptureExtractionRespectsOpaqueRegionAnchors() {
      Matcher m = Pattern.compile("^((a|aa))*$").matcher("xaa");
      m.region(1, 3);

      assertThat(m.matches()).isTrue();
      assertThat(m.group(1)).isEqualTo("a");
      assertThat(m.start(1)).isEqualTo(2);
      assertThat(m.end(1)).isEqualTo(3);
      assertThat(m.group(2)).isEqualTo("a");
      assertThat(m.start(2)).isEqualTo(2);
      assertThat(m.end(2)).isEqualTo(3);
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
    @DisplayName("grapheme matches() stays bounded to region without anchoring bounds")
    void graphemeMatchesStaysRegionBoundedWithoutAnchoringBounds() {
      java.util.regex.Matcher jdk =
          java.util.regex.Pattern.compile("\\X").matcher("#a$").region(1, 2);
      jdk.useAnchoringBounds(false);
      assertThat(jdk.matches()).isTrue();

      Matcher m = Pattern.compile("\\X").matcher("#a$").region(1, 2);
      m.useAnchoringBounds(false);
      assertThat(m.matches()).isTrue();
      assertThat(m.start()).isEqualTo(1);
      assertThat(m.end()).isEqualTo(2);
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
      assertThatThrownBy(() -> m.region(-1, 3)).isInstanceOf(IndexOutOfBoundsException.class);
      assertThatThrownBy(() -> m.region(0, 4)).isInstanceOf(IndexOutOfBoundsException.class);
      assertThatThrownBy(() -> m.region(3, 1)).isInstanceOf(IndexOutOfBoundsException.class);
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
    @DisplayName("$ uses full-text trailing terminator context without anchoring bounds")
    void dollarUsesFullTextTrailingTerminatorContextWithoutAnchoringBounds() {
      Pattern anchored = Pattern.compile("^[\\s\\S][\\s\\S]$");
      Matcher anchoredMatcher = anchored.matcher("a\u0000\n\n");
      anchoredMatcher.region(0, 3);
      anchoredMatcher.useAnchoringBounds(false);
      assertThat(anchoredMatcher.find()).isFalse();

      Pattern unanchored = Pattern.compile("[\\s\\S][\\s\\S]$");
      Matcher unanchoredMatcher = unanchored.matcher("a\u0000\n\n");
      unanchoredMatcher.region(0, 3);
      unanchoredMatcher.useAnchoringBounds(false);
      assertThat(unanchoredMatcher.find()).isTrue();
      assertThat(unanchoredMatcher.start()).isEqualTo(1);
      assertThat(unanchoredMatcher.end()).isEqualTo(3);
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
  @DisplayName("Reverse-first DFA optimization for end-anchored patterns")
  class ReverseFirstDfaTests {

    /** Helper to create a string of repeated characters. */
    private static String repeat(char ch, int count) {
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
   * Tests for the OnePass fast path with alternation patterns. The anchored OnePass fast path now
   * handles non-nullable alternation (e.g., GET|POST) directly, skipping the DFA sandwich. Nullable
   * alternation (zero-width vs consuming branches) still falls through to DFA+BitState.
   */
  @Nested
  @DisplayName("OnePass alternation fast path")
  class OnePassAlternationFastPath {

    @Test
    @DisplayName("HTTP pattern — anchored with non-nullable alternation uses OnePass")
    void httpPatternFullRequest() {
      Pattern p = Pattern.compile("^(?:GET|POST) +([^ ]+) HTTP");
      Matcher m =
          p.matcher(
              "GET /asdfhjasdhfasdlfhasdflkjasdfkljasdhflaskdjhflkajsdhflkajshfklasjdhfklasjdhfklashdflka"
                  + " HTTP/1.1");
      assertThat(m.find()).isTrue();
      assertThat(m.group(0))
          .isEqualTo(
              "GET /asdfhjasdhfasdlfhasdflkjasdfkljasdhflaskdjhflkajsdhflkajshfklasjdhfklasjdhfklashdflka"
                  + " HTTP");
      assertThat(m.group(1))
          .isEqualTo(
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

    @Test
    @DisplayName("$ before trailing newline stops first-match DFA search")
    void dollarBeforeTrailingNewlineStopsFirstMatchDfaSearch() {
      assertFirstFindMatchesJdk("^(?:\\n|\\n*)$", "\n\n");
      assertFirstFindMatchesJdk("^(?:\\R|\\R*)$", "\r\n\r\n");
      assertFirstFindMatchesJdk("^(?:\\u2028|\\u2028*)$", "\u2028\u2028");
    }
  }

  @Test
  @DisplayName("find() with empty branch before complex char class")
  void findEmptyBranchBeforeComplexCharClass() {
    Matcher m = Pattern.compile("|[\u0166&\u00bfA&&?;]+&?\udb7d\udda6+];]+&?&\u00e9v").matcher("");

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
    long largerPositiveNanos = medianRuntimeNanos(() -> scenario.accept(16));
    long nearMinimumNanos = medianRuntimeNanos(() -> scenario.accept(5));

    assertThat(nearMinimumNanos)
        .as(
            "%s near-minimum input should not be dramatically slower than a larger "
                + "positive input; near=%d ns, larger=%d ns",
            api, nearMinimumNanos, largerPositiveNanos)
        .isLessThan(largerPositiveNanos * 50);
  }

  private static void assertNoPerformanceCliff(
      String api, String nearMinimumInput, String largerPositiveInput, Consumer<String> scenario) {
    scenario.accept(nearMinimumInput);
    scenario.accept(largerPositiveInput);
    long largerPositiveNanos = medianRuntimeNanos(() -> scenario.accept(largerPositiveInput));
    long nearMinimumNanos = medianRuntimeNanos(() -> scenario.accept(nearMinimumInput));

    assertThat(nearMinimumNanos)
        .as(
            "%s near-minimum input should not be dramatically slower than a larger "
                + "positive input; near=%d ns, larger=%d ns",
            api, nearMinimumNanos, largerPositiveNanos)
        .isLessThan(largerPositiveNanos * 50);
  }

  private static long medianRuntimeNanos(Runnable task) {
    long[] samples = new long[5];
    for (int i = 0; i < samples.length; i++) {
      samples[i] = runtimeNanos(task);
    }
    java.util.Arrays.sort(samples);
    return samples[samples.length / 2];
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

  private static void assertZeroCountGroupBehavior(String regex, String input) {
    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regex).matcher(input);
    Matcher safere = Pattern.compile(regex).matcher(input);

    assertThat(safere.groupCount()).isEqualTo(jdk.groupCount());
    assertThat(safere.matches()).isEqualTo(jdk.matches());
    assertThat(safere.groupCount()).isEqualTo(jdk.groupCount());
    for (int group = 1; group <= jdk.groupCount(); group++) {
      assertThat(safere.group(group))
          .as("group(%d) for /%s/ on %s", group, regex, input)
          .isEqualTo(jdk.group(group));
      assertThat(safere.start(group))
          .as("start(%d) for /%s/ on %s", group, regex, input)
          .isEqualTo(jdk.start(group));
      assertThat(safere.end(group))
          .as("end(%d) for /%s/ on %s", group, regex, input)
          .isEqualTo(jdk.end(group));
    }
  }

  private static void assertFirstFindMatchesJdk(String regex, String input) {
    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regex).matcher(input);
    Matcher safere = Pattern.compile(regex).matcher(input);

    assertThat(safere.find()).isEqualTo(jdk.find());
    assertThat(safere.groupCount()).isEqualTo(jdk.groupCount());
    assertThat(safere.group()).isEqualTo(jdk.group());
    assertThat(safere.start()).isEqualTo(jdk.start());
    assertThat(safere.end()).isEqualTo(jdk.end());
    for (int group = 1; group <= jdk.groupCount(); group++) {
      assertThat(safere.group(group))
          .as("group(%d) for /%s/ on %s", group, regex, input)
          .isEqualTo(jdk.group(group));
      assertThat(safere.start(group))
          .as("start(%d) for /%s/ on %s", group, regex, input)
          .isEqualTo(jdk.start(group));
      assertThat(safere.end(group))
          .as("end(%d) for /%s/ on %s", group, regex, input)
          .isEqualTo(jdk.end(group));
    }
  }

  private static void assertFullMatchMatchesJdk(String regex, String input) {
    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regex).matcher(input);
    Matcher safere = Pattern.compile(regex).matcher(input);

    assertThat(safere.matches()).isEqualTo(jdk.matches());
    assertThat(safere.groupCount()).isEqualTo(jdk.groupCount());
    assertThat(safere.group()).isEqualTo(jdk.group());
    assertThat(safere.start()).isEqualTo(jdk.start());
    assertThat(safere.end()).isEqualTo(jdk.end());
    for (int group = 1; group <= jdk.groupCount(); group++) {
      assertThat(safere.group(group))
          .as("group(%d) for /%s/ on %s", group, regex, input)
          .isEqualTo(jdk.group(group));
      assertThat(safere.start(group))
          .as("start(%d) for /%s/ on %s", group, regex, input)
          .isEqualTo(jdk.start(group));
      assertThat(safere.end(group))
          .as("end(%d) for /%s/ on %s", group, regex, input)
          .isEqualTo(jdk.end(group));
    }
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

  private static void assertCompletesWithinPerformanceTimeout(Runnable task) {
    assertTimeoutPreemptively(PERFORMANCE_SCENARIO_TIMEOUT, task::run);
  }
}
