// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.PatternSyntaxException;

/**
 * A compiled set of regular expression patterns that can be matched against a text simultaneously.
 *
 * <p>Unlike {@link Pattern}, which compiles and matches one pattern at a time, {@code PatternSet}
 * compiles multiple patterns into a single automaton and tests all of them in a single pass over the
 * input text. This is useful for tasks such as URL routing, lexical analysis, or content filtering
 * where the input must be checked against many patterns.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * PatternSet.Builder builder = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
 * int id0 = builder.add("foo");
 * int id1 = builder.add("bar");
 * int id2 = builder.add("[0-9]+");
 * PatternSet set = builder.compile();
 *
 * List<Integer> matches = set.match("foobar123");
 * // matches contains id0, id1, id2
 * }</pre>
 *
 * <p>This class is modeled on RE2's {@code RE2::Set}. The compiled automaton uses the DFA engine
 * for fast matching. If the DFA exhausts its state budget, the match falls back to running each
 * pattern individually through the NFA.
 */
public final class PatternSet {

  /**
   * Anchor mode for the pattern set. Determines how patterns are matched against the text.
   */
  public enum Anchor {
    /** Patterns can match anywhere in the text. */
    UNANCHORED,

    /** Patterns must match starting at the beginning of the text. */
    ANCHOR_START,

    /** Patterns must match the entire text from start to end. */
    ANCHOR_BOTH
  }

  private final Anchor anchor;
  private final Prog prog;
  private final int size;
  private final List<String> patterns;
  private final int maxDfaStates;

  /** Parse flags for pattern set patterns: Perl-compatible without captures. */
  private static final int SET_PARSE_FLAGS = ParseFlags.LIKE_PERL | ParseFlags.NEVER_CAPTURE;

  /** Default maximum number of DFA states before falling back to NFA. */
  static final int DEFAULT_MAX_DFA_STATES = 10_000;

  private PatternSet(
      Anchor anchor, Prog prog, int size, List<String> patterns, int maxDfaStates) {
    this.anchor = anchor;
    this.prog = prog;
    this.size = size;
    this.patterns = patterns;
    this.maxDfaStates = maxDfaStates;
  }

  /**
   * Returns the number of patterns in this set.
   *
   * @return the number of patterns
   */
  public int size() {
    return size;
  }

  /**
   * Returns the pattern at the given index.
   *
   * @param index the pattern index (as returned by {@link Builder#add})
   * @return the pattern string
   * @throws IndexOutOfBoundsException if index is out of range
   */
  public String pattern(int index) {
    return patterns.get(index);
  }

  /**
   * Tests whether any pattern in this set matches the text.
   *
   * @param text the input text to match against
   * @return {@code true} if at least one pattern matches
   */
  public boolean matches(String text) {
    return !match(text).isEmpty();
  }

  /**
   * Matches the text against all patterns and returns the indices of the patterns that matched.
   *
   * <p>The returned list contains the 0-based indices (as returned by {@link Builder#add}) of all
   * patterns that matched the text. The list is sorted in ascending order. If no pattern matched,
   * an empty list is returned.
   *
   * @param text the input text to match against
   * @return a sorted list of indices of matching patterns (may be empty)
   */
  public List<Integer> match(String text) {
    boolean anchored = (anchor != Anchor.UNANCHORED);

    // Try DFA multi-match first.
    Dfa.ManyMatchResult dfaResult = Dfa.searchMany(prog, text, anchored, maxDfaStates);
    if (dfaResult != null) {
      if (!dfaResult.matched()) {
        return Collections.emptyList();
      }
      List<Integer> result = new ArrayList<>(dfaResult.matchIds().length);
      for (int id : dfaResult.matchIds()) {
        result.add(id);
      }
      Collections.sort(result);
      return Collections.unmodifiableList(result);
    }

    // DFA bailed out — fall back to running each pattern individually through NFA.
    return matchFallback(text);
  }

  /**
   * Fallback matching that runs each pattern individually through the NFA. Used when the DFA
   * exceeds its state budget.
   */
  private List<Integer> matchFallback(String text) {
    List<Integer> result = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      Pattern p = Pattern.compile(patterns.get(i));
      Matcher m = p.matcher(text);
      boolean matched = switch (anchor) {
        case UNANCHORED -> m.find();
        case ANCHOR_START -> m.lookingAt();
        case ANCHOR_BOTH -> m.matches();
      };
      if (matched) {
        result.add(i);
      }
    }
    return Collections.unmodifiableList(result);
  }

  /**
   * Builder for constructing a {@link PatternSet}. Patterns are added one at a time, then the set
   * is compiled into a single automaton.
   *
   * <p>Once {@link #compile()} is called, no more patterns can be added.
   */
  public static final class Builder {
    private final Anchor anchor;
    private final List<String> patterns = new ArrayList<>();
    private boolean compiled;

    /**
     * Creates a new builder with the given anchor mode.
     *
     * @param anchor the anchor mode for matching
     */
    public Builder(Anchor anchor) {
      this.anchor = anchor;
    }

    /**
     * Adds a pattern to the set.
     *
     * @param pattern the regular expression pattern
     * @return the 0-based index assigned to this pattern
     * @throws PatternSyntaxException if the pattern is invalid
     * @throws IllegalStateException if {@link #compile()} has already been called
     */
    public int add(String pattern) {
      if (compiled) {
        throw new IllegalStateException("Cannot add patterns after compile()");
      }
      // Validate the pattern by parsing it.
      int parseFlags = SET_PARSE_FLAGS;
      Parser.parse(pattern, parseFlags);

      int index = patterns.size();
      patterns.add(pattern);
      return index;
    }

    /**
     * Compiles all added patterns into a single automaton.
     *
     * @return the compiled {@link PatternSet}
     * @throws IllegalStateException if no patterns have been added or if already compiled
     */
    public PatternSet compile() {
      if (compiled) {
        throw new IllegalStateException("compile() has already been called");
      }
      if (patterns.isEmpty()) {
        throw new IllegalStateException("No patterns have been added");
      }
      compiled = true;

      int parseFlags = SET_PARSE_FLAGS;
      int size = patterns.size();

      // Build tagged regexps: each pattern is concatenated with a HAVE_MATCH marker.
      List<Regexp> tagged = new ArrayList<>(size);
      for (int i = 0; i < size; i++) {
        Regexp re = Parser.parse(patterns.get(i), parseFlags);
        Regexp haveMatch = Regexp.haveMatch(i, parseFlags);
        tagged.add(Regexp.concat(List.of(re, haveMatch), parseFlags));
      }

      // Combine all tagged patterns into a single alternation.
      Regexp combined;
      if (size == 1) {
        combined = tagged.getFirst();
      } else {
        combined = Regexp.alternate(tagged, parseFlags);
      }

      // Compile the combined regexp.
      Prog prog = Compiler.compile(combined);

      // Adjust anchoring based on the requested anchor mode.
      if (anchor == Anchor.ANCHOR_START || anchor == Anchor.ANCHOR_BOTH) {
        prog.setAnchorStart(true);
      }
      if (anchor == Anchor.ANCHOR_BOTH) {
        prog.setAnchorEnd(true);
      }

      return new PatternSet(
          anchor, prog, size, List.copyOf(patterns), DEFAULT_MAX_DFA_STATES);
    }

    /**
     * Compiles all added patterns with a custom DFA state budget. Package-private for testing.
     *
     * @param maxDfaStates maximum number of DFA states before falling back to NFA
     * @return the compiled {@link PatternSet}
     */
    PatternSet compile(int maxDfaStates) {
      if (compiled) {
        throw new IllegalStateException("compile() has already been called");
      }
      if (patterns.isEmpty()) {
        throw new IllegalStateException("No patterns have been added");
      }
      compiled = true;

      int parseFlags = SET_PARSE_FLAGS;
      int size = patterns.size();

      List<Regexp> tagged = new ArrayList<>(size);
      for (int i = 0; i < size; i++) {
        Regexp re = Parser.parse(patterns.get(i), parseFlags);
        Regexp haveMatch = Regexp.haveMatch(i, parseFlags);
        tagged.add(Regexp.concat(List.of(re, haveMatch), parseFlags));
      }

      Regexp combined;
      if (size == 1) {
        combined = tagged.getFirst();
      } else {
        combined = Regexp.alternate(tagged, parseFlags);
      }

      Prog prog = Compiler.compile(combined);

      if (anchor == Anchor.ANCHOR_START || anchor == Anchor.ANCHOR_BOTH) {
        prog.setAnchorStart(true);
      }
      if (anchor == Anchor.ANCHOR_BOTH) {
        prog.setAnchorEnd(true);
      }

      return new PatternSet(anchor, prog, size, List.copyOf(patterns), maxDfaStates);
    }
  }
}
