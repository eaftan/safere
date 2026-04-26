// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.crosscheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for the safere-crosscheck module. */
class CrosscheckTest {

  // ---------------------------------------------------------------------------
  // Pattern.compile
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Pattern.compile")
  class CompileTests {

    @Test
    @DisplayName("compiles a simple pattern on both engines")
    void simpleCompile() {
      Pattern p = Pattern.compile("abc");
      assertThat(p.pattern()).isEqualTo("abc");
      assertThat(p.flags()).isEqualTo(0);
    }

    @Test
    @DisplayName("compiles with flags")
    void compileWithFlags() {
      Pattern p = Pattern.compile("abc", Pattern.CASE_INSENSITIVE);
      assertThat(p.flags()).isEqualTo(Pattern.CASE_INSENSITIVE);
    }

    @Test
    @DisplayName("throws UnsupportedPatternException for backreference")
    void backreference() {
      assertThatThrownBy(() -> Pattern.compile("(a)\\1"))
          .isInstanceOf(UnsupportedPatternException.class)
          .hasCauseInstanceOf(PatternSyntaxException.class);
    }

    @Test
    @DisplayName("throws UnsupportedPatternException for lookahead")
    void lookahead() {
      assertThatThrownBy(() -> Pattern.compile("a(?=b)"))
          .isInstanceOf(UnsupportedPatternException.class);
    }

    @Test
    @DisplayName("throws PatternSyntaxException when both engines reject")
    void bothReject() {
      assertThatThrownBy(() -> Pattern.compile("["))
          .isInstanceOf(PatternSyntaxException.class)
          .isNotInstanceOf(UnsupportedPatternException.class);
    }
  }

  // ---------------------------------------------------------------------------
  // Pattern.matches
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Pattern.matches")
  class PatternMatchesTests {

    @Test
    @DisplayName("static matches returns true when both agree")
    void matchesTrue() {
      assertThat(Pattern.matches("\\d+", "123")).isTrue();
    }

    @Test
    @DisplayName("static matches returns false when both agree")
    void matchesFalse() {
      assertThat(Pattern.matches("\\d+", "abc")).isFalse();
    }
  }

  // ---------------------------------------------------------------------------
  // Matcher — core matching
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Matcher matching")
  class MatcherMatchingTests {

    @Test
    @DisplayName("matches() returns true for full match")
    void matchesFull() {
      Matcher m = Pattern.compile("\\w+").matcher("hello");
      assertThat(m.matches()).isTrue();
    }

    @Test
    @DisplayName("matches() returns false for partial")
    void matchesPartial() {
      Matcher m = Pattern.compile("\\d+").matcher("abc123");
      assertThat(m.matches()).isFalse();
    }

    @Test
    @DisplayName("lookingAt() matches prefix")
    void lookingAt() {
      Matcher m = Pattern.compile("\\d+").matcher("123abc");
      assertThat(m.lookingAt()).isTrue();
      assertThat(m.group()).isEqualTo("123");
    }

    @Test
    @DisplayName("find() iterates all matches")
    void findAll() {
      Matcher m = Pattern.compile("\\d+").matcher("a1b23c456");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("1");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("23");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("456");
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("find(int) starts at given index")
    void findFromIndex() {
      Matcher m = Pattern.compile("\\d+").matcher("a1b23c456");
      assertThat(m.find(3)).isTrue();
      assertThat(m.group()).isEqualTo("23");
    }
  }

  // ---------------------------------------------------------------------------
  // Matcher — groups
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Matcher groups")
  class MatcherGroupTests {

    @Test
    @DisplayName("capture groups match")
    void captureGroups() {
      Matcher m = Pattern.compile("(\\w+)@(\\w+)").matcher("user@host");
      assertThat(m.matches()).isTrue();
      assertThat(m.groupCount()).isEqualTo(2);
      assertThat(m.group(1)).isEqualTo("user");
      assertThat(m.group(2)).isEqualTo("host");
      assertThat(m.start(1)).isEqualTo(0);
      assertThat(m.end(1)).isEqualTo(4);
    }

    @Test
    @DisplayName("named groups match")
    void namedGroups() {
      Matcher m = Pattern.compile("(?<user>\\w+)@(?<host>\\w+)").matcher("foo@bar");
      assertThat(m.matches()).isTrue();
      assertThat(m.group("user")).isEqualTo("foo");
      assertThat(m.group("host")).isEqualTo("bar");
      assertThat(m.start("user")).isEqualTo(0);
      assertThat(m.end("host")).isEqualTo(7);
    }

    @Test
    @DisplayName("non-participating group returns null")
    void nonParticipating() {
      Matcher m = Pattern.compile("(a)|(b)").matcher("b");
      assertThat(m.matches()).isTrue();
      assertThat(m.group(1)).isNull();
      assertThat(m.group(2)).isEqualTo("b");
    }
  }

  // ---------------------------------------------------------------------------
  // Matcher — replace
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Matcher replace")
  class MatcherReplaceTests {

    @Test
    @DisplayName("replaceAll replaces all occurrences")
    void replaceAll() {
      Matcher m = Pattern.compile("\\d").matcher("a1b2c3");
      assertThat(m.replaceAll("X")).isEqualTo("aXbXcX");
    }

    @Test
    @DisplayName("replaceFirst replaces first occurrence")
    void replaceFirst() {
      Matcher m = Pattern.compile("\\d").matcher("a1b2c3");
      assertThat(m.replaceFirst("X")).isEqualTo("aXb2c3");
    }

    @Test
    @DisplayName("replaceAll with group reference")
    void replaceAllGroupRef() {
      Matcher m = Pattern.compile("(\\w+)").matcher("hello world");
      assertThat(m.replaceAll("[$1]")).isEqualTo("[hello] [world]");
    }

    @Test
    @DisplayName("appendReplacement/appendTail")
    void appendReplacementTail() {
      Matcher m = Pattern.compile("\\d+").matcher("a1b2c3");
      StringBuilder sb = new StringBuilder();
      while (m.find()) {
        m.appendReplacement(sb, "X");
      }
      m.appendTail(sb);
      assertThat(sb.toString()).isEqualTo("aXbXcX");
    }

    @Test
    @DisplayName("appendReplacement/appendTail with direct StringBuilder mutation")
    void appendReplacementTailWithDirectStringBuilderMutation() {
      Matcher m = Pattern.compile("\\{\\{(.+?)\\}\\}").matcher("{{one}};{{two}};tail");
      StringBuilder sb = new StringBuilder("prefix:");
      while (m.find()) {
        String group = m.group(1);
        m.appendReplacement(sb, "");
        sb.append(group);
      }
      m.appendTail(sb);
      assertThat(sb.toString()).isEqualTo("prefix:one;two;tail");
    }

    @Test
    @DisplayName("appendReplacement/appendTail with direct StringBuffer mutation")
    void appendReplacementTailWithDirectStringBufferMutation() {
      Matcher m = Pattern.compile("\\{\\{(.+?)\\}\\}").matcher("{{one}};{{two}};tail");
      StringBuffer sb = new StringBuffer("prefix:");
      while (m.find()) {
        String group = m.group(1);
        m.appendReplacement(sb, "");
        sb.append(group);
      }
      m.appendTail(sb);
      assertThat(sb.toString()).isEqualTo("prefix:one;two;tail");
    }
  }

  // ---------------------------------------------------------------------------
  // Pattern — split
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Pattern split")
  class PatternSplitTests {

    @Test
    @DisplayName("split with default limit")
    void splitDefault() {
      Pattern p = Pattern.compile(",");
      assertThat(p.split("a,b,c")).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("split with limit")
    void splitWithLimit() {
      Pattern p = Pattern.compile(",");
      assertThat(p.split("a,b,c", 2)).containsExactly("a", "b,c");
    }

    @Test
    @DisplayName("split trailing empty strings")
    void splitTrailingEmpty() {
      Pattern p = Pattern.compile(",");
      assertThat(p.split("a,b,")).containsExactly("a", "b");
    }

    @Test
    @DisplayName("splitWithDelimiters")
    void splitWithDelimiters() {
      Pattern p = Pattern.compile(",");
      assertThat(p.splitWithDelimiters("a,b,c", -1)).containsExactly("a", ",", "b", ",", "c");
    }
  }

  // ---------------------------------------------------------------------------
  // Matcher — state
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Matcher state")
  class MatcherStateTests {

    @Test
    @DisplayName("reset allows re-matching")
    void reset() {
      Matcher m = Pattern.compile("\\d+").matcher("123");
      assertThat(m.matches()).isTrue();
      m.reset();
      assertThat(m.matches()).isTrue();
    }

    @Test
    @DisplayName("reset(CharSequence) changes input")
    void resetWithInput() {
      Matcher m = Pattern.compile("\\d+").matcher("abc");
      assertThat(m.matches()).isFalse();
      m.reset("123");
      assertThat(m.matches()).isTrue();
    }

    @Test
    @DisplayName("region constrains matching")
    void region() {
      Matcher m = Pattern.compile("\\d+").matcher("abc123def");
      m.region(3, 6);
      assertThat(m.regionStart()).isEqualTo(3);
      assertThat(m.regionEnd()).isEqualTo(6);
      assertThat(m.matches()).isTrue();
      assertThat(m.group()).isEqualTo("123");
    }

    @Test
    @DisplayName("transparent bounds default and toggle")
    void transparentBounds() {
      Matcher m = Pattern.compile("\\d+").matcher("123");
      assertThat(m.hasTransparentBounds()).isFalse();
      m.useTransparentBounds(true);
      assertThat(m.hasTransparentBounds()).isTrue();
    }

    @Test
    @DisplayName("anchoring bounds default and toggle")
    void anchoringBounds() {
      Matcher m = Pattern.compile("\\d+").matcher("123");
      assertThat(m.hasAnchoringBounds()).isTrue();
      m.useAnchoringBounds(false);
      assertThat(m.hasAnchoringBounds()).isFalse();
    }
  }

  // ---------------------------------------------------------------------------
  // Trace recording
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Trace recording")
  class TraceTests {

    @Test
    @DisplayName("trace captures all API calls")
    void traceCapture() {
      Matcher m = Pattern.compile("\\d+").matcher("a1b2");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("1");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("2");
      assertThat(m.find()).isFalse();

      TraceRecorder trace = m.getTrace();
      assertThat(trace.getEntries()).isNotEmpty();
      assertThat(trace.getEntries()).allMatch(TraceRecorder.TraceEntry::matched);
    }

    @Test
    @DisplayName("trace format produces readable output")
    void traceFormat() {
      Matcher m = Pattern.compile("\\w+").matcher("hello");
      m.matches();

      String formatted = m.getTrace().format();
      assertThat(formatted).contains("API call trace");
      assertThat(formatted).contains("matches");
    }

    @Test
    @DisplayName("trace can be cleared")
    void traceClear() {
      Matcher m = Pattern.compile("\\w+").matcher("hello");
      m.matches();
      assertThat(m.getTrace().getEntries()).isNotEmpty();
      m.getTrace().clear();
      assertThat(m.getTrace().getEntries()).isEmpty();
    }
  }

  // ---------------------------------------------------------------------------
  // CrosscheckException on known divergence
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("CrosscheckException on known divergence")
  class DivergenceTests {

    @Test
    @DisplayName("nested repetition with captures: (?:(a))*$ on 'ab' triggers divergence")
    void nestedRepetitionCaptureDivergence() {
      // Known divergence (issue #52): JDK's backtracking engine leaks captures from failed
      // starting positions in patterns with captures inside zero-or-more repetition anchored
      // by $. For (?:(a))*$ on "ab", both engines find the empty match at position 2 (end
      // of string). SafeRE correctly reports group(1)=null (the * matched zero times), but
      // JDK leaks group(1)="a" from the failed attempt at position 0.
      Pattern p = Pattern.compile("(?:(a))*$");
      Matcher m = p.matcher("ab");

      // The only match is the empty match at the end of the string.
      // SafeRE: group(1) = null (correct — * matched zero times at this position)
      // JDK:    group(1) = "a" (leaked from failed attempt at earlier position)
      assertThatThrownBy(() -> m.find())
          .isInstanceOf(CrosscheckException.class)
          .satisfies(ex -> {
            CrosscheckException ce = (CrosscheckException) ex;
            assertThat(ce.trace()).contains("DIVERGENCE");
          });
    }
  }

  // ---------------------------------------------------------------------------
  // Other
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Other")
  class OtherTests {

    @Test
    @DisplayName("pattern() returns the crosscheck pattern")
    void patternAccessor() {
      Pattern p = Pattern.compile("abc");
      Matcher m = p.matcher("abc");
      assertThat(m.pattern()).isSameAs(p);
    }

    @Test
    @DisplayName("toString() returns the regex string")
    void toStringTest() {
      Pattern p = Pattern.compile("abc");
      assertThat(p.toString()).isEqualTo("abc");
    }

    @Test
    @DisplayName("quote() escapes metacharacters")
    void quoteTest() {
      String quoted = Pattern.quote("a.b");
      Pattern p = Pattern.compile(quoted);
      Matcher m = p.matcher("a.b");
      assertThat(m.matches()).isTrue();
      m.reset("axb");
      assertThat(m.matches()).isFalse();
    }

    @Test
    @DisplayName("quoteReplacement() escapes replacement chars")
    void quoteReplacement() {
      assertThat(Matcher.quoteReplacement("$1")).isEqualTo("\\$1");
    }

    @Test
    @DisplayName("toMatchResult() returns snapshot")
    void toMatchResult() {
      Matcher m = Pattern.compile("(\\d+)").matcher("abc123def");
      assertThat(m.find()).isTrue();
      java.util.regex.MatchResult mr = m.toMatchResult();
      assertThat(mr.group()).isEqualTo("123");
      assertThat(mr.group(1)).isEqualTo("123");
      assertThat(mr.start()).isEqualTo(3);
      assertThat(mr.end()).isEqualTo(6);
    }

    @Test
    @DisplayName("namedGroups() returns group mapping")
    void namedGroupsMap() {
      Pattern p = Pattern.compile("(?<year>\\d{4})-(?<month>\\d{2})");
      assertThat(p.namedGroups()).containsEntry("year", 1);
      assertThat(p.namedGroups()).containsEntry("month", 2);
    }

    @Test
    @DisplayName("hitEnd after partial match")
    void hitEnd() {
      Matcher m = Pattern.compile("\\d+").matcher("123");
      m.matches();
      // Both engines should agree on hitEnd
      m.hitEnd();
    }
  }
}
