// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Dfa}.
 *
 * <p>Many tests verify that the DFA produces the same match/no-match result as the NFA. The DFA
 * only reports match boundaries (not capture groups), so tests focus on match detection and end
 * position.
 */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class DfaTest {

  private static final int FLAGS =
      ParseFlags.PERL_X | ParseFlags.PERL_CLASSES | ParseFlags.PERL_B | ParseFlags.UNICODE_GROUPS;

  /** Compiles a pattern and searches with the DFA (unanchored, first match). */
  private static Dfa.SearchResult search(String pattern, String text) {
    Regexp re = Parser.parse(pattern, FLAGS);
    Prog prog = Compiler.compile(re);
    return Dfa.search(prog, text, false, false);
  }

  /** Compiles a pattern and searches with the DFA (anchored, longest match = full match). */
  private static Dfa.SearchResult fullMatch(String pattern, String text) {
    Regexp re = Parser.parse(pattern, FLAGS);
    Prog prog = Compiler.compile(re);
    Dfa.SearchResult r = Dfa.search(prog, text, true, true);
    if (r != null && r.matched() && r.pos() != text.length()) {
      // Match didn't cover the entire text — not a full match.
      return new Dfa.SearchResult(false, r.pos());
    }
    return r;
  }

  /** Compiles a pattern and searches with the DFA (unanchored, longest match). */
  private static Dfa.SearchResult longestMatch(String pattern, String text) {
    Regexp re = Parser.parse(pattern, FLAGS);
    Prog prog = Compiler.compile(re);
    return Dfa.search(prog, text, false, true);
  }

  // ---------------------------------------------------------------------------
  // Basic matching
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Literals")
  class Literals {
    @Test
    void singleChar() {
      Dfa.SearchResult r = search("a", "a");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void multiChar() {
      Dfa.SearchResult r = search("abc", "xabcy");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void noMatch() {
      Dfa.SearchResult r = search("abc", "def");
      assertThat(r.matched()).isFalse();
    }

    @Test
    void emptyPattern() {
      Dfa.SearchResult r = search("", "hello");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void emptyText() {
      Dfa.SearchResult r = search("", "");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void atEnd() {
      Dfa.SearchResult r = search("xyz", "abcxyz");
      assertThat(r.matched()).isTrue();
      assertThat(r.pos()).isEqualTo(6);
    }
  }

  @Nested
  @DisplayName("Character classes")
  class CharClasses {
    @Test
    void nonAsciiClassCachePreservesLargeClassIds() {
      int rangeCount = 32_769;
      int firstCp = 0x10000;
      int[] ranges = new int[rangeCount * 2];
      for (int i = 0; i < rangeCount; i++) {
        int cp = firstCp + i * 2;
        ranges[i * 2] = cp;
        ranges[i * 2 + 1] = cp;
      }

      Prog prog = new Prog();
      prog.allocInst();
      int charClass = prog.allocInst();
      int match = prog.allocInst();
      prog.mutableInst(charClass).initCharClass(match, ranges);
      prog.mutableInst(match).initMatch(0);
      prog.setStart(charClass);
      prog.setStartUnanchored(charClass);
      prog.freeze();

      Dfa dfa = new Dfa(prog, 10_000, Dfa.buildSetup(prog), false);
      String text = Character.toString(firstCp + (rangeCount - 1) * 2);

      assertThat(dfa.doSearch(text, true, false).matched()).isTrue();
      assertThat(dfa.doSearch(text, true, false).matched()).isTrue();
    }

    @Test
    void digitClass() {
      Dfa.SearchResult r = search("\\d+", "abc123def");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void wordClass() {
      Dfa.SearchResult r = search("\\w+", "hello");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void range() {
      Dfa.SearchResult r = search("[a-z]+", "HELLO world");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void negatedClass() {
      Dfa.SearchResult r = search("[^0-9]+", "123abc456");
      assertThat(r.matched()).isTrue();
    }
  }

  @Nested
  @DisplayName("Quantifiers")
  class Quantifiers {
    @Test
    void star() {
      Dfa.SearchResult r = fullMatch("a*", "aaa");
      assertThat(r.matched()).isTrue();
      assertThat(r.pos()).isEqualTo(3);
    }

    @Test
    void plus() {
      Dfa.SearchResult r = fullMatch("a+", "aaa");
      assertThat(r.matched()).isTrue();
      assertThat(r.pos()).isEqualTo(3);
    }

    @Test
    void plusNoMatch() {
      Dfa.SearchResult r = fullMatch("a+", "");
      assertThat(r.matched()).isFalse();
    }

    @Test
    void quest() {
      Dfa.SearchResult r = fullMatch("a?", "a");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void questEmpty() {
      Dfa.SearchResult r = fullMatch("a?", "");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void repeat() {
      Dfa.SearchResult r = fullMatch("a{3}", "aaa");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void repeatRange() {
      Dfa.SearchResult r = fullMatch("a{2,4}", "aaa");
      assertThat(r.matched()).isTrue();
    }
  }

  @Nested
  @DisplayName("Alternation")
  class Alternation {
    @Test
    void firstAlt() {
      Dfa.SearchResult r = search("cat|dog", "I have a cat");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void secondAlt() {
      Dfa.SearchResult r = search("cat|dog", "I have a dog");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void noAlt() {
      Dfa.SearchResult r = search("cat|dog", "I have a bird");
      assertThat(r.matched()).isFalse();
    }
  }

  @Nested
  @DisplayName("Anchors")
  class Anchors {
    @Test
    void startAnchor() {
      Dfa.SearchResult r = search("^hello", "hello world");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void startAnchorFail() {
      Dfa.SearchResult r = search("^hello", "say hello");
      assertThat(r.matched()).isFalse();
    }

    @Test
    void endAnchor() {
      Dfa.SearchResult r = search("world$", "hello world");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void endAnchorFail() {
      Dfa.SearchResult r = search("world$", "world cup");
      assertThat(r.matched()).isFalse();
    }

    @Test
    void fullAnchor() {
      Dfa.SearchResult r = search("^abc$", "abc");
      assertThat(r.matched()).isTrue();
    }
  }

  @Nested
  @DisplayName("Dot")
  class Dot {
    @Test
    void dotMatchesChar() {
      Dfa.SearchResult r = fullMatch("a.c", "abc");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void dotDoesNotMatchNewline() {
      Dfa.SearchResult r = fullMatch("a.c", "a\nc");
      assertThat(r.matched()).isFalse();
    }

    @Test
    void dotPlus() {
      Dfa.SearchResult r = fullMatch(".+", "hello");
      assertThat(r.matched()).isTrue();
      assertThat(r.pos()).isEqualTo(5);
    }
  }

  @Nested
  @DisplayName("Word boundary")
  class WordBoundary {
    @Test
    void wordBoundaryMatch() {
      Dfa.SearchResult r = search("\\bfoo\\b", "foo bar");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void wordBoundaryNoMatch() {
      Dfa.SearchResult r = search("\\bfoo\\b", "foobar");
      assertThat(r.matched()).isFalse();
    }

    @Test
    void wordBoundaryInMiddle() {
      Dfa.SearchResult r = search("\\bbar\\b", "foo bar baz");
      assertThat(r.matched()).isTrue();
      assertThat(r.pos()).isEqualTo(7);
    }

    @Test
    void unicodeWordBoundaryStartStateCacheDistinguishesNextCharacterContext() {
      Regexp re = Parser.parse("\\b.", FLAGS | ParseFlags.UNICODE_CHAR_CLASS);
      Prog prog = Compiler.compile(re);
      Dfa dfa = new Dfa(prog, 10_000, Dfa.buildSetup(prog), false);

      Dfa.SearchResult boundary = dfa.doSearch("!\u00E9", 1, true, false);
      Dfa.SearchResult nonBoundary = dfa.doSearch("!!", 1, true, false);

      assertThat(boundary.matched()).isTrue();
      assertThat(boundary.pos()).isEqualTo(2);
      assertThat(nonBoundary.matched()).isFalse();
    }
  }

  @Nested
  @DisplayName("Match modes")
  class MatchModes {
    @Test
    void fullMatchSuccess() {
      Dfa.SearchResult r = fullMatch("abc", "abc");
      assertThat(r.matched()).isTrue();
      assertThat(r.pos()).isEqualTo(3);
    }

    @Test
    void fullMatchFailure() {
      Dfa.SearchResult r = fullMatch("abc", "abcd");
      assertThat(r.matched()).isFalse();
    }

    @Test
    void longestMatchGreedy() {
      Dfa.SearchResult r = longestMatch("a+", "aaa");
      assertThat(r.matched()).isTrue();
      assertThat(r.pos()).isEqualTo(3);
    }

    @Test
    void firstMatchShortest() {
      Dfa.SearchResult r = search("a+", "aaa");
      assertThat(r.matched()).isTrue();
      // First match should still match (may not return the shortest match
      // since DFA with .*? prefix finds leftmost).
    }
  }

  @Nested
  @DisplayName("Unicode")
  class Unicode {
    @Test
    void supplementaryPlane() {
      Dfa.SearchResult r = search(".", "😀");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void unicodeLetters() {
      Dfa.SearchResult r = search("[à-ÿ]+", "café");
      assertThat(r.matched()).isTrue();
    }
  }

  @Nested
  @DisplayName("Complex patterns")
  class Complex {
    @Test
    void emailLike() {
      Dfa.SearchResult r = search("[a-z]+@[a-z]+\\.[a-z]+", "user@example.com");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void ipAddress() {
      Dfa.SearchResult r = search("\\d+\\.\\d+\\.\\d+\\.\\d+", "ip is 192.168.1.1 here");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void alternationWithQuantifiers() {
      Dfa.SearchResult r = fullMatch("(ab|cd)+", "ababcdab");
      assertThat(r.matched()).isTrue();
    }
  }

  @Nested
  @DisplayName("State budget")
  class Budget {
    @Test
    void exceedBudgetReturnsNull() {
      // Alternation with many branches forces many DFA states.
      Regexp re = Parser.parse("(a|b)(c|d)(e|f)(g|h)(i|j)", FLAGS);
      Prog prog = Compiler.compile(re);
      // Budget of 2 is too small for this pattern.
      Dfa.SearchResult r = Dfa.search(prog, "acegi", false, false, 2);
      // Budget exceeded -- should return null to signal fallback to NFA.
      assertThat(r).isNull();
    }

    @Test
    void linearTimeGuarantee() {
      // Pathological for backtracking: a?^n a^n
      int n = 25;
      String pattern = "a?".repeat(n) + "a".repeat(n);
      String text = "a".repeat(n);
      Regexp re = Parser.parse(pattern, FLAGS);
      Prog prog = Compiler.compile(re);
      Dfa.SearchResult r = Dfa.search(prog, text, true, true);
      // May bail out (return null) due to state explosion, which is fine.
      // The important thing is it completes quickly, not exponentially.
      if (r != null) {
        assertThat(r.matched()).isTrue();
      }
    }
  }

  @Test
  void reverseDfaPruningCorrectness() {
    Pattern p = Pattern.compile("\\B([^a])*[^a][^a]");
    Dfa revDfa = p.reverseDfa();
    assertThat(revDfa).isNotNull();

    String text = "bbbb";
    Dfa.SearchResult r = revDfa.doSearchReverse(text, 4, 0, true, true);
    assertThat(r).isNotNull();
    assertThat(r.matched()).isTrue();
    assertThat(r.pos()).isEqualTo(1);
  }

  @Test
  void reverseDfaDeferredMatchCorrectness() {
    Pattern p = Pattern.compile("\\ba|a");
    Dfa revDfa = p.reverseDfa();
    assertThat(revDfa).isNotNull();

    String text = "a";
    Dfa.SearchResult r = revDfa.doSearchReverse(text, 1, 0, true, true);
    assertThat(r).isNotNull();
    assertThat(r.matched()).isTrue();
    assertThat(r.pos()).isEqualTo(0);

    String text2 = "aa";
    Dfa.SearchResult r2 = revDfa.doSearchReverse(text2, 2, 1, true, true);
    assertThat(r2).isNotNull();
    assertThat(r2.matched()).isTrue();
    assertThat(r2.pos()).isEqualTo(1);
  }

  @Test
  void reverseDfaMixedDeferredAndConsumingMatchPreservesDeferredStart() {
    Pattern p = Pattern.compile("(?:(?:\\ba?)|\\B|[^a])a?");
    Dfa revDfa = p.reverseDfa();
    assertThat(revDfa).isNotNull();

    Dfa.SearchResult r = revDfa.doSearchReverse("ba", 2, 1, true, true);

    assertThat(r).isNotNull();
    assertThat(r.matched()).isTrue();
    assertThat(r.pos()).isEqualTo(1);
  }

  @Test
  void reverseDfaAsciiFastPathPreservesAnchoredStartFilter() {
    Pattern p = Pattern.compile("^a");
    Dfa revDfa = p.reverseDfa();
    assertThat(revDfa).isNotNull();

    Dfa.SearchResult first = revDfa.doSearchReverse("xa", 2, 0, true, true);
    Dfa.SearchResult second = revDfa.doSearchReverse("xa", 2, 0, true, true);

    assertThat(first).isNotNull();
    assertThat(second).isNotNull();
    assertThat(first.matched()).isFalse();
    assertThat(second.matched()).isFalse();
  }

  @Test
  void reverseDfaAsciiFastPathPreservesDeferredMatchAmbiguity() {
    Pattern p = Pattern.compile("(?:\\B{1}|a).");
    Dfa revDfa = p.reverseDfa();
    assertThat(revDfa).isNotNull();

    Dfa.SearchResult first = revDfa.doSearchReverse("ab", 2, 0, true, true);
    Dfa.SearchResult second = revDfa.doSearchReverse("ab", 2, 0, true, true);

    assertThat(first).isNotNull();
    assertThat(second).isNotNull();
    assertThat(first.matched()).isTrue();
    assertThat(second.matched()).isTrue();
    assertThat(first.ambiguous()).isTrue();
    assertThat(second.ambiguous()).isTrue();
  }

  @Test
  void reverseDfaAsciiFastPathPreservesAcceptedAfterMatchAmbiguity() {
    Pattern p = Pattern.compile("^(?:\\B|a)b");
    Dfa revDfa = p.reverseDfa();
    assertThat(revDfa).isNotNull();

    Dfa.SearchResult first = revDfa.doSearchReverse("ab", 2, 0, true, true);
    Dfa.SearchResult second = revDfa.doSearchReverse("ab", 2, 0, true, true);

    assertThat(first).isNotNull();
    assertThat(second).isNotNull();
    assertThat(first.matched()).isTrue();
    assertThat(second.matched()).isTrue();
    assertThat(first.ambiguous()).isTrue();
    assertThat(second.ambiguous()).isTrue();
  }

  @Nested
  @DisplayName("Self-Loop Escape Acceleration")
  class SelfLoopEscapeAcceleration {
    @Test
    void quotedStringLongPayload() {
      String filler = "x".repeat(5000);
      String text = "prefix \"" + filler + "\" suffix";
      Dfa.SearchResult result = search("\"[^\"]*\"", text);
      assertThat(result).isNotNull();
      assertThat(result.matched()).isTrue();
      assertThat(result.pos()).isEqualTo(8 + 5000 + 1);
    }

    @Test
    void headerLineTerminatorLongPayload() {
      String filler = "a".repeat(10000);
      String text = "header:" + filler + "\nrest";
      Dfa.SearchResult result = search("header:[^\n]*\n", text);
      assertThat(result).isNotNull();
      assertThat(result.matched()).isTrue();
      assertThat(result.pos()).isEqualTo(7 + 10000 + 1);
    }

    @Test
    void htmlTagLongPayload() {
      String filler = "content".repeat(1000);
      String text = "before <div " + filler + "> after";
      Dfa.SearchResult result = search("<[^>]*>", text);
      assertThat(result).isNotNull();
      assertThat(result.matched()).isTrue();
      assertThat(result.pos()).isEqualTo(12 + 7000 + 1);
    }

    @Test
    void multiEscapeBytePair() {
      String filler = "data".repeat(1000);
      String text = "prefix " + filler + ",suffix";
      Dfa.SearchResult result = search("[^,\n]*,", text);
      assertThat(result).isNotNull();
      assertThat(result.matched()).isTrue();
      assertThat(result.pos()).isEqualTo(7 + 4000 + 1);
    }

    @Test
    void selfLoopNoEscapeFoundUntilEof() {
      String filler = "x".repeat(5000);
      String text = "start \"" + filler;
      Dfa.SearchResult result = search("\"[^\"]*\"", text);
      assertThat(result).isNotNull();
      assertThat(result.matched()).isFalse();
    }

    @Test
    void selfLoopStopsAtNonAsciiTransitions() {
      assertNonAsciiEscapeMatches("é");
      assertNonAsciiEscapeMatches("😀");
    }

    @Test
    void startAccelerationUsesOnlyTheActiveInputSubstrate() {
      Pattern pattern = Pattern.compile("(?m)^foo");
      Dfa dfa = pattern.forwardFirstMatchDfa();

      assertThat(dfa.hasStartAcceleration(new StringInputScanner("foo"))).isTrue();
      assertThat(dfa.hasStartAcceleration(new Utf8InputScanner("foo".getBytes(UTF_8)))).isFalse();
    }

    @Test
    void selfLoopUtf8ScannerEquivalence() {
      String filler = "x".repeat(2000);
      String text = "start \"" + filler + "\" end";
      byte[] bytes = text.getBytes(UTF_8);
      Regexp re = Parser.parse("\"[^\"]*\"", FLAGS);
      Prog prog = Compiler.compile(re);
      Utf8InputScanner scanner = new Utf8InputScanner(bytes, 0, bytes.length);
      Dfa dfa = new Dfa(prog, 1000, Dfa.buildSetup(prog), false);
      Dfa.SearchResult result = dfa.doSearch(scanner, 0, false, false);
      assertThat(result).isNotNull();
      assertThat(result.matched()).isTrue();
      assertThat(result.pos()).isEqualTo(7 + 2000 + 1);
    }

    private static void assertNonAsciiEscapeMatches(String escape) {
      String pattern = "A[\\x00-\\x21\\x23-\\x7F]*" + escape;
      String text = "A" + "x".repeat(1000) + escape;
      Pattern compiledPattern = Pattern.compile(pattern);
      assertThat(compiledPattern.matcher(text).find()).isTrue();
      assertThat(compiledPattern.find(Utf8Input.validated(text.getBytes(UTF_8)))).isTrue();

      Dfa.SearchResult stringResult = search(pattern, text);
      assertThat(stringResult).isNotNull();
      assertThat(stringResult.matched()).isTrue();

      byte[] bytes = text.getBytes(UTF_8);
      Regexp re = Parser.parse(pattern, FLAGS);
      Prog prog = Compiler.compile(re);
      Dfa.SearchResult utf8Result =
          new Dfa(prog, 1000, Dfa.buildSetup(prog), false)
              .doSearch(new Utf8InputScanner(bytes, 0, bytes.length), 0, false, false);
      assertThat(utf8Result).isNotNull();
      assertThat(utf8Result.matched()).isTrue();
    }
  }

  @Test
  void multilineAnchorAlternationRejectsFalseUtf8Candidate() {
    String regex = "(b|(?m:^a))cd[0-9]";
    String input = "x".repeat(100) + "0cb\r1bacd19c1__19x y_";
    EnginePathOptions enabled = EnginePathOptions.builder().startAcceleration(true).build();
    EnginePathOptions disabled = EnginePathOptions.builder().startAcceleration(false).build();
    Pattern accelerated = Pattern.compile(regex, 0, enabled);
    Pattern control = Pattern.compile(regex, 0, disabled);
    Utf8Input utf8 = Utf8Input.validated(input.getBytes(UTF_8));

    assertThat(control.find(utf8)).isFalse();
    assertThat(accelerated.find(utf8))
        .as("Accelerated DFA must not match (?m:^a) when 'a' is not after line terminator")
        .isEqualTo(control.find(utf8));
  }

  @Test
  void multilineAnchorAlternationRejectsFalseStringCandidate() {
    String regex = "(b|(?m:^a))cd[0-9]";
    String input = "x".repeat(100) + "0cb\r1bacd19c1__19x y_";
    EnginePathOptions enabled = EnginePathOptions.builder().startAcceleration(true).build();
    EnginePathOptions disabled = EnginePathOptions.builder().startAcceleration(false).build();
    Pattern accelerated = Pattern.compile(regex, 0, enabled);
    Pattern control = Pattern.compile(regex, 0, disabled);

    assertThat(control.matcher(input).find()).isFalse();
    assertThat(accelerated.matcher(input).find())
        .as("Accelerated DFA String search must match unaccelerated control")
        .isEqualTo(control.matcher(input).find());
  }

  @Test
  void wordBoundaryAnchorAlternationWithFalseCandidate() {
    String regex = "(b|\\ba)cd[0-9]";
    String input = "x".repeat(100) + "0cb_bacd19c1__19x y_";
    EnginePathOptions enabled = EnginePathOptions.builder().startAcceleration(true).build();
    EnginePathOptions disabled = EnginePathOptions.builder().startAcceleration(false).build();
    Pattern accelerated = Pattern.compile(regex, 0, enabled);
    Pattern control = Pattern.compile(regex, 0, disabled);

    assertThat(control.matcher(input).find()).isFalse();
    assertThat(accelerated.matcher(input).find()).isEqualTo(control.matcher(input).find());
  }

  @Test
  void startOfTextAnchorAlternationWithFalseCandidate() {
    String regex = "(b|\\Aa)cd[0-9]";
    String input = "x".repeat(100) + "0cb1bacd19c1__19x y_";
    EnginePathOptions enabled = EnginePathOptions.builder().startAcceleration(true).build();
    EnginePathOptions disabled = EnginePathOptions.builder().startAcceleration(false).build();
    Pattern accelerated = Pattern.compile(regex, 0, enabled);
    Pattern control = Pattern.compile(regex, 0, disabled);

    assertThat(control.matcher(input).find()).isFalse();
    assertThat(accelerated.matcher(input).find()).isEqualTo(control.matcher(input).find());
  }

  @Test
  void multilineAnchorTrueCandidateMatches() {
    String regex = "(b|(?m:^a))cd[0-9]";
    String input = "x".repeat(100) + "0cb\nacd19c1__19x y_";
    EnginePathOptions enabled = EnginePathOptions.builder().startAcceleration(true).build();
    Pattern accelerated = Pattern.compile(regex, 0, enabled);

    assertThat(accelerated.matcher(input).find()).isTrue();
    assertThat(accelerated.find(Utf8Input.validated(input.getBytes(UTF_8)))).isTrue();
  }

  @Test
  void automataDerivedStartStateAcceleratesOptionalWhitespaceBracket() {
    Pattern pattern = Pattern.compile("[ \\t]*\\[\\[.*?\\]\\]");
    String text = "x".repeat(1000) + "[[test]]" + "y".repeat(1000);
    assertThat(pattern.matcher(text).find()).isTrue();
    assertThat(pattern.find(Utf8Input.validated(text.getBytes(UTF_8)))).isTrue();
  }

  @Test
  void automataDerivedStartStateAcceleratesAlternations() {
    Regexp re = Parser.parse("apple|banana|cherry", FLAGS);
    Prog prog = Compiler.compile(re);
    Dfa dfa = new Dfa(prog, 1000, Dfa.buildSetup(prog), false);
    InputScanner scanner = new StringInputScanner("x".repeat(100));
    Dfa.State s = dfa.startState(scanner, 0, false);
    assertThat(s.accelerator).isNotNull();
    assertThat(s.accelerator).isInstanceOf(StateAccelerator.AsciiTripleEscape.class);

    Pattern pattern = Pattern.compile("apple|banana|cherry");
    String text = "x".repeat(500) + "banana" + "y".repeat(500);
    assertThat(pattern.matcher(text).find()).isTrue();
    assertThat(pattern.find(Utf8Input.validated(text.getBytes(UTF_8)))).isTrue();

    String noMatch = "x".repeat(1000);
    assertThat(pattern.matcher(noMatch).find()).isFalse();
    assertThat(pattern.find(Utf8Input.validated(noMatch.getBytes(UTF_8)))).isFalse();
  }

  @Test
  void automataDerivedStartStateAcceleratesMultiTokenAlternation() {
    Pattern pattern = Pattern.compile("(?:image-tokens|video-tokens|pdf-tokens)");
    String text = "x".repeat(500) + "video-tokens" + "y".repeat(500);
    assertThat(pattern.matcher(text).find()).isTrue();
    assertThat(pattern.find(Utf8Input.validated(text.getBytes(UTF_8)))).isTrue();

    String noMatch = "x".repeat(1000);
    assertThat(pattern.matcher(noMatch).find()).isFalse();
    assertThat(pattern.find(Utf8Input.validated(noMatch.getBytes(UTF_8)))).isFalse();
  }

  @Test
  void automataDerivedStartStateAcceleratesDisjointRangeAlternation() {
    Pattern pattern = Pattern.compile("[0-9]{3}|[a-z]{3}");
    String text = "---".repeat(100) + "123" + "---".repeat(100);
    assertThat(pattern.matcher(text).find()).isTrue();
    assertThat(pattern.find(Utf8Input.validated(text.getBytes(UTF_8)))).isTrue();

    String noMatch = "---".repeat(200);
    assertThat(pattern.matcher(noMatch).find()).isFalse();
    assertThat(pattern.find(Utf8Input.validated(noMatch.getBytes(UTF_8)))).isFalse();
  }

  @Test
  void wordBoundaryLongText() {
    String regex = "(?:\\w(?:\\b))";
    String input = "#".repeat(260) + "a";
    Pattern pattern = Pattern.compile(regex);
    assertThat(pattern.matcher(input).find()).isTrue();
    assertThat(pattern.find(Utf8Input.validated(input.getBytes(UTF_8)))).isTrue();
  }
}
