// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * A compiled regular expression backed by a linear-time NFA engine. This class provides a drop-in
 * replacement for {@link java.util.regex.Pattern}.
 *
 * <p>Unlike {@code java.util.regex.Pattern}, this implementation guarantees linear-time matching
 * regardless of the pattern or input. Features that require exponential time (backreferences,
 * lookahead, lookbehind) are not supported and will be rejected at compile time.
 *
 * <p>Usage:
 *
 * <pre>
 *   Pattern p = Pattern.compile("(\\w+)@(\\w+)");
 *   Matcher m = p.matcher("user@host");
 *   if (m.matches()) {
 *     String user = m.group(1);
 *   }
 * </pre>
 */
public final class Pattern implements Serializable {

  private static final long serialVersionUID = 1L;

  /**
   * Enables Unix lines mode. In this mode, only {@code '\n'} is recognized as a line terminator.
   */
  public static final int UNIX_LINES = java.util.regex.Pattern.UNIX_LINES; // 1

  /**
   * Enables case-insensitive matching. By default, case-insensitive matching assumes only US-ASCII
   * characters. Unicode-aware case folding can be enabled with {@link #UNICODE_CASE}.
   */
  public static final int CASE_INSENSITIVE = java.util.regex.Pattern.CASE_INSENSITIVE; // 2

  /**
   * Permits whitespace and comments in the pattern. Whitespace is ignored, and comments starting
   * with {@code #} run to end-of-line.
   */
  public static final int COMMENTS = java.util.regex.Pattern.COMMENTS; // 4

  /**
   * Enables multiline mode. In multiline mode, {@code ^} and {@code $} match at the start and end
   * of each line, not just the start and end of the entire input.
   */
  public static final int MULTILINE = java.util.regex.Pattern.MULTILINE; // 8

  /**
   * Enables literal parsing of the pattern. Metacharacters and escape sequences have no special
   * meaning.
   */
  public static final int LITERAL = java.util.regex.Pattern.LITERAL; // 16

  /**
   * Enables dotall mode. In dotall mode, {@code .} matches any character including line
   * terminators.
   */
  public static final int DOTALL = java.util.regex.Pattern.DOTALL; // 32

  /**
   * Enables Unicode-aware case folding. When used with {@link #CASE_INSENSITIVE}, matching is done
   * in a manner consistent with the Unicode Standard.
   */
  public static final int UNICODE_CASE = java.util.regex.Pattern.UNICODE_CASE; // 64

  /**
   * Enables Unicode-aware character classes. When enabled, predefined character classes such as
   * {@code \w}, {@code \d}, and {@code \s} match Unicode characters instead of only ASCII.
   */
  public static final int UNICODE_CHARACTER_CLASS =
      java.util.regex.Pattern.UNICODE_CHARACTER_CLASS; // 256

  private final String pattern;
  private final int flags;
  private final transient Prog prog;
  private final transient Regexp ast;
  private final transient Map<String, Integer> namedGroups;
  private final transient String prefix;
  private final transient boolean prefixFoldCase;
  private final transient String literalMatch;
  private final transient boolean hasLazy;
  private final transient boolean hasAlternation;
  private final transient boolean hasNullableAlternation;
  private final transient boolean hasBoundedRepeat;
  private final transient boolean hasAnchorInQuant;
  private final transient boolean hasEndConstraint;
  private final transient boolean[] charClassPrefixAscii;
  private final transient StartAcceleration startAcceleration;
  private final transient KeywordAlternation keywordAlternation;

  /**
   * Precomputed character class data for the "repeated character class" fast path in
   * {@code matches()}. Non-null when the pattern is structurally {@code [class]+},
   * {@code [class]*}, {@code [class]{n,}}, or similar — a single character class quantified to
   * cover the entire string. Stored as a flat {@code [lo0, hi0, lo1, hi1, ...]} array of
   * inclusive Unicode code point ranges plus precomputed ASCII bitmaps for O(1) lookup.
   *
   * <p>When non-null, {@code matches()} can bypass the full engine cascade and use a tight
   * character-scanning loop instead.
   */
  private final transient int[] charClassMatchRanges;
  private final transient long charClassMatchBitmap0;
  private final transient long charClassMatchBitmap1;
  private final transient boolean charClassMatchAllowEmpty;

  /**
   * Lazily computed OnePass analysis results. Holds the OnePass automaton (if eligible) and derived
   * flags ({@code canOnePassFind}, {@code canOnePassSubmatch}). Computed on first access to avoid
   * paying the OnePass BFS cost at compile time.
   */
  private transient volatile OnePassAnalysis onePassAnalysis;

  /**
   * Lazily computed DFA equivalence-class setup for the forward program. Shared across all Matcher
   * instances. Computed on first access to avoid paying the boundary-scan cost at compile time.
   */
  private transient volatile Dfa.Setup forwardDfaSetup;

  /**
   * Reverse-compiled program for backward DFA matching. Lazily computed on first access to avoid
   * paying the compilation cost for patterns that never need it (e.g., anchored patterns, patterns
   * used only with {@code matches()} or {@code lookingAt()}).
   */
  private transient volatile Prog reverseProg;

  /** Lazily computed DFA setup for the reverse program. Computed alongside {@link #reverseProg}. */
  private transient volatile Dfa.Setup reverseDfaSetup;

  /**
   * Thread-local cached BitState instance. Shared across all Matchers created from this Pattern
   * within the same thread, enabling reuse even with the common {@code pattern.matcher(t).find()}
   * idiom where each call creates a new Matcher.
   */
  // Per-Pattern ThreadLocals are intentional: each Pattern caches its own DFA/BitState per thread,
  // so the warm state cache persists across the common pattern.matcher(t).find() idiom.
  @SuppressWarnings("ThreadLocalUsage")
  private transient final ThreadLocal<BitState> cachedBitState = new ThreadLocal<>();

  /**
   * Thread-local cached forward DFA. Shared across all Matchers created from this Pattern within
   * the same thread, so the DFA state cache persists across the common
   * {@code pattern.matcher(t).find()} idiom. The DFA's state cache is text-independent (keyed by
   * NFA instruction sets and flags), so it remains valid for any input text.
   */
  // Per-Pattern ThreadLocals are intentional; see cachedBitState above.
  @SuppressWarnings("ThreadLocalUsage")
  private transient final ThreadLocal<Dfa> cachedForwardDfa = new ThreadLocal<>();

  /**
   * Thread-local cached reverse DFA. Shared like the forward DFA, enabling the DFA sandwich to
   * run with a warm state cache across Matcher instances.
   */
  // Per-Pattern ThreadLocals are intentional; see cachedBitState above.
  @SuppressWarnings("ThreadLocalUsage")
  private transient final ThreadLocal<Dfa> cachedReverseDfa = new ThreadLocal<>();

  /** Holder for lazily computed OnePass analysis results. */
  private record OnePassAnalysis(
      OnePass onePass, boolean canPrimary, boolean canFind, boolean canSubmatch) {}

  private Pattern(String pattern, int flags, Prog prog, Regexp ast,
      Map<String, Integer> namedGroups, String prefix, boolean prefixFoldCase,
      String literalMatch, boolean hasLazy, boolean hasAlternation,
      boolean hasNullableAlternation,
      boolean hasBoundedRepeat, boolean hasAnchorInQuant, boolean hasEndConstraint,
      boolean[] charClassPrefixAscii, StartAcceleration startAcceleration,
      KeywordAlternation keywordAlternation,
      int[] charClassMatchRanges, long charClassMatchBitmap0, long charClassMatchBitmap1,
      boolean charClassMatchAllowEmpty) {
    this.pattern = pattern;
    this.flags = flags;
    this.prog = prog;
    this.ast = ast;
    this.namedGroups = namedGroups;
    this.prefix = prefix;
    this.prefixFoldCase = prefixFoldCase;
    this.literalMatch = literalMatch;
    this.hasLazy = hasLazy;
    this.hasAlternation = hasAlternation;
    this.hasNullableAlternation = hasNullableAlternation;
    this.hasBoundedRepeat = hasBoundedRepeat;
    this.hasAnchorInQuant = hasAnchorInQuant;
    this.hasEndConstraint = hasEndConstraint;
    this.charClassPrefixAscii = charClassPrefixAscii;
    this.startAcceleration = startAcceleration;
    this.keywordAlternation = keywordAlternation;
    this.charClassMatchRanges = charClassMatchRanges;
    this.charClassMatchBitmap0 = charClassMatchBitmap0;
    this.charClassMatchBitmap1 = charClassMatchBitmap1;
    this.charClassMatchAllowEmpty = charClassMatchAllowEmpty;
  }

  /**
   * Compiles the given regular expression into a pattern with default flags.
   *
   * @param regex the expression to be compiled
   * @return the compiled pattern
   * @throws PatternSyntaxException if the expression's syntax is invalid
   */
  public static Pattern compile(String regex) {
    return compile(regex, 0);
  }

  /**
   * Compiles the given regular expression into a pattern with the given flags.
   *
   * @param regex the expression to be compiled
   * @param flags match flags, a bit mask of {@link #CASE_INSENSITIVE}, {@link #MULTILINE},
   *     {@link #DOTALL}, {@link #UNICODE_CHARACTER_CLASS}, {@link #LITERAL}, {@link #COMMENTS},
   *     {@link #UNIX_LINES}, and {@link #UNICODE_CASE}
   * @return the compiled pattern
   * @throws PatternSyntaxException if the expression's syntax is invalid
   * @throws IllegalArgumentException if the flags contain unsupported bits (e.g.,
   *     {@code CANON_EQ})
   */
  public static Pattern compile(String regex, int flags) {
    validateFlags(flags);
    int parseFlags = toParseFlags(flags);
    Regexp re = Parser.parse(regex, parseFlags);
    Prog compiled = Compiler.compile(re);
    compiled.setUnixLines((flags & UNIX_LINES) != 0);
    Map<String, Integer> named = extractNamedGroups(re);
    PrefixResult prefixResult = extractPrefix(re);
    String prefix = prefixResult.prefix();
    boolean prefixFoldCase = prefixResult.foldCase();
    String literalMatch = extractLiteralMatch(re);
    boolean hasLazy = hasLazyQuantifiers(re);
    boolean hasAlt = hasAlternation(re);
    boolean hasNullableAlt = hasAlt && hasNullableAlternation(re);
    boolean hasBounded = hasBoundedRepeat(re);
    boolean hasAnchorQuant = hasAnchorInQuantifier(re);
    boolean hasEndConst = hasEndConstraint(re);
    // Extract character-class prefix for acceleration when no literal prefix exists.
    boolean[] ccPrefixAscii = (prefix == null)
        ? extractCharClassPrefixAscii(re) : null;
    StartAcceleration startAcceleration =
        (prefix == null && ccPrefixAscii == null) ? extractStartAcceleration(re) : null;
    KeywordAlternation keywordAlternation = extractKeywordAlternation(re, flags);
    // Detect "repeated character class" pattern for matches() fast path.
    CharClassMatchInfo ccMatch = extractCharClassMatch(re);
    // OnePass analysis and DFA setup are deferred to first use (lazy initialization).
    return new Pattern(regex, flags, compiled, re, named, prefix, prefixFoldCase,
        literalMatch, hasLazy, hasAlt, hasNullableAlt, hasBounded, hasAnchorQuant,
        hasEndConst, ccPrefixAscii, startAcceleration, keywordAlternation,
        ccMatch != null ? ccMatch.ranges : null,
        ccMatch != null ? ccMatch.bitmap0 : 0,
        ccMatch != null ? ccMatch.bitmap1 : 0,
        ccMatch != null && ccMatch.allowEmpty);
  }

  /**
   * Compiles the given regular expression and attempts to match the given input against it. This is
   * equivalent to {@code Pattern.compile(regex).matcher(input).matches()}.
   *
   * @param regex the expression to be compiled
   * @param input the character sequence to be matched
   * @return {@code true} if the entire input matches the pattern
   * @throws PatternSyntaxException if the expression's syntax is invalid
   */
  public static boolean matches(String regex, CharSequence input) {
    return compile(regex).matcher(input).matches();
  }

  /**
   * Materializes a {@link CharSequence} by reading through {@code charAt()}, so custom
   * implementations that do not override {@code toString()} are handled correctly.
   */
  private static String charSequenceToString(CharSequence cs) {
    if (cs instanceof String s) {
      return s;
    }
    int len = cs.length();
    char[] chars = new char[len];
    for (int i = 0; i < len; i++) {
      chars[i] = cs.charAt(i);
    }
    return new String(chars);
  }

  /**
   * Returns a literal pattern string for the specified string. Metacharacters and escape sequences
   * in the returned string will have no special meaning.
   *
   * @param s the string to be literalized
   * @return a literal pattern string
   */
  public static String quote(String s) {
    // Use \Q...\E quoting. If the string contains \E, split around it.
    if (!s.contains("\\E")) {
      return "\\Q" + s + "\\E";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("\\Q");
    int last = 0;
    int idx;
    while ((idx = s.indexOf("\\E", last)) != -1) {
      sb.append(s, last, idx);
      sb.append("\\E\\\\E\\Q");
      last = idx + 2;
    }
    sb.append(s, last, s.length());
    sb.append("\\E");
    return sb.toString();
  }

  /**
   * Creates a matcher that will match the given input against this pattern.
   *
   * @param input the character sequence to be matched
   * @return a new matcher for this pattern
   */
  public Matcher matcher(CharSequence input) {
    return new Matcher(this, input);
  }

  /**
   * Returns the match flags specified when this pattern was compiled.
   *
   * @return the match flags
   */
  public int flags() {
    return flags;
  }

  /**
   * Returns the regular expression from which this pattern was compiled.
   *
   * @return the source of this pattern
   */
  public String pattern() {
    return pattern;
  }

  /**
   * Splits the given input around matches of this pattern. Trailing empty strings are discarded.
   *
   * @param input the character sequence to be split
   * @return the array of strings computed by splitting the input around matches of this pattern
   */
  public String[] split(CharSequence input) {
    return split(input, 0);
  }

  /**
   * Splits the given input around matches of this pattern.
   *
   * <p>The {@code limit} parameter controls the number of times the pattern is applied:
   * <ul>
   *   <li>If {@code limit > 0}, the pattern is applied at most {@code limit - 1} times, and the
   *       resulting array will have at most {@code limit} entries.
   *   <li>If {@code limit == 0}, the pattern is applied as many times as possible, and trailing
   *       empty strings are discarded.
   *   <li>If {@code limit < 0}, the pattern is applied as many times as possible, and trailing
   *       empty strings are retained.
   * </ul>
   *
   * @param input the character sequence to be split
   * @param limit the result threshold
   * @return the array of strings computed by splitting the input around matches of this pattern
   */
  public String[] split(CharSequence input, int limit) {
    String text = charSequenceToString(input);
    Matcher m = matcher(text);
    List<String> parts = new ArrayList<>();
    int last = 0;

    while (m.find()) {
      if (limit > 0 && parts.size() >= limit - 1) {
        break;
      }
      // JDK 8+: a zero-width match at the beginning of the input never produces
      // a leading empty substring.
      if (last == 0 && m.start() == 0 && m.end() == 0) {
        continue;
      }
      parts.add(text.substring(last, m.start()));
      last = m.end();
    }
    // If no match advanced the position, return the entire input as a single element.
    // This matches JDK behavior: an input that was never actually split is returned as-is,
    // bypassing trailing-empty-string removal.
    if (last == 0) {
      return new String[] {text};
    }

    parts.add(text.substring(last));

    // limit == 0: remove trailing empty strings.
    if (limit == 0) {
      int end = parts.size();
      while (end > 0 && parts.get(end - 1).isEmpty()) {
        end--;
      }
      parts = parts.subList(0, end);
    }

    return parts.toArray(new String[0]);
  }

  /**
   * Splits the given input around matches of this pattern, returning both the substrings between
   * matches and the matching delimiters, interleaved. The resulting array alternates between
   * substrings and delimiters: {@code [substring, delimiter, substring, delimiter, ...,
   * substring]}.
   *
   * <p>This is equivalent to {@code splitWithDelimiters(input, 0)}.
   *
   * @param input the character sequence to be split
   * @return the array of strings computed by splitting the input around matches of this pattern,
   *     with the matching delimiters interleaved
   * @since 21
   */
  public String[] splitWithDelimiters(CharSequence input) {
    return splitWithDelimiters(input, 0);
  }

  /**
   * Splits the given input around matches of this pattern, returning both the substrings between
   * matches and the matching delimiters, interleaved.
   *
   * <p>The {@code limit} parameter controls the number of times the pattern is applied:
   * <ul>
   *   <li>If {@code limit > 0}, the pattern is applied at most {@code limit - 1} times, and the
   *       resulting array will have at most {@code 2 * limit - 1} entries.
   *   <li>If {@code limit == 0}, the pattern is applied as many times as possible, and trailing
   *       empty strings are discarded.
   *   <li>If {@code limit < 0}, the pattern is applied as many times as possible, and trailing
   *       empty strings are retained.
   * </ul>
   *
   * @param input the character sequence to be split
   * @param limit the result threshold
   * @return the array of strings computed by splitting the input around matches of this pattern,
   *     with the matching delimiters interleaved
   * @since 21
   */
  public String[] splitWithDelimiters(CharSequence input, int limit) {
    String text = charSequenceToString(input);
    Matcher m = matcher(text);
    List<String> parts = new ArrayList<>();
    int last = 0;

    while (m.find()) {
      if (limit > 0 && parts.size() >= 2 * limit - 2) {
        break;
      }
      // JDK 8+: a zero-width match at the beginning of the input never produces
      // a leading empty substring or empty delimiter.
      if (last == 0 && m.start() == 0 && m.end() == 0) {
        continue;
      }
      parts.add(text.substring(last, m.start()));
      parts.add(text.substring(m.start(), m.end()));
      last = m.end();
    }
    // If no match advanced the position, return the entire input as a single element.
    if (last == 0) {
      return new String[] {text};
    }

    parts.add(text.substring(last));

    // limit == 0: remove trailing empty strings.
    if (limit == 0) {
      int end = parts.size();
      while (end > 0 && parts.get(end - 1).isEmpty()) {
        end--;
      }
      parts = parts.subList(0, end);
    }

    return parts.toArray(new String[0]);
  }

  /**
   * Creates a stream of strings split from the given input sequence around matches of this pattern.
   * The stream contains the same strings that {@link #split(CharSequence)} would return, produced
   * lazily.
   *
   * @param input the character sequence to be split
   * @return a sequential stream of strings computed by splitting the input around matches of this
   *     pattern
   */
  public Stream<String> splitAsStream(CharSequence input) {
    return Arrays.stream(split(input, 0));
  }

  /**
   * Creates a predicate that tests if this pattern is found in a given input string. The predicate
   * behaves as if calling {@code matcher(input).find()}.
   *
   * @return a predicate for partial matching
   */
  public Predicate<String> asPredicate() {
    return input -> matcher(input).find();
  }

  /**
   * Creates a predicate that tests if this pattern matches a given input string in its entirety.
   * The predicate behaves as if calling {@code matcher(input).matches()}.
   *
   * @return a predicate for full matching
   */
  public Predicate<String> asMatchPredicate() {
    return input -> matcher(input).matches();
  }

  @Override
  public String toString() {
    return pattern;
  }

  // ---------------------------------------------------------------------------
  // Package-private accessors for Matcher
  // ---------------------------------------------------------------------------

  /** Returns the compiled program. */
  Prog prog() {
    return prog;
  }

  /** Returns the thread-local cached BitState, or null if none has been cached yet. */
  BitState borrowBitState() {
    BitState bs = cachedBitState.get();
    cachedBitState.set(null); // take ownership
    return bs;
  }

  /** Returns a BitState to the thread-local cache for reuse by future Matchers. */
  void returnBitState(BitState bs) {
    cachedBitState.set(bs);
  }

  /** Maximum number of DFA states before the DFA bails out. */
  static final int MAX_DFA_STATES = 10_000;

  /**
   * Returns the thread-local cached forward DFA, creating it on first access. The DFA state cache
   * persists across Matcher instances, so repeated {@code pattern.matcher(t).find()} calls benefit
   * from warm DFA transitions.
   */
  Dfa forwardDfa() {
    Dfa dfa = cachedForwardDfa.get();
    if (dfa == null) {
      dfa = new Dfa(prog, MAX_DFA_STATES, forwardDfaSetup());
      cachedForwardDfa.set(dfa);
    }
    return dfa;
  }

  /**
   * Returns the thread-local cached reverse DFA, creating it on first access. Triggers lazy
   * compilation of the reverse program if needed.
   */
  Dfa reverseDfa() {
    Dfa dfa = cachedReverseDfa.get();
    if (dfa == null) {
      Prog rp = reverseProg();
      if (rp != null) {
        dfa = new Dfa(rp, MAX_DFA_STATES, reverseDfaSetup());
        cachedReverseDfa.set(dfa);
      }
    }
    return dfa;
  }

  /**
   * Returns the lazily computed OnePass analysis results. Thread-safe via volatile: benign data race
   * at worst computes twice, but the result is the same since all inputs are immutable.
   */
  private OnePassAnalysis onePassAnalysis() {
    OnePassAnalysis analysis = onePassAnalysis;
    if (analysis == null) {
      OnePass op = OnePass.build(prog);
      // OnePass can be used as the primary matching engine (bypassing DFA entirely) when the
      // pattern is non-nullable and has no lazy quantifiers. Nullable patterns (e.g., a*|c.)
      // must be excluded because OnePass returns leftmost-longest semantics, which disagrees
      // with JDK's leftmost-first (biased) semantics for nullable alternations.
      boolean canPrimary = op != null
          && op.search("", false, 0) == null
          && !hasLazy;
      // canFind is canPrimary restricted to anchored patterns (legacy flag).
      boolean canFind = canPrimary && prog.anchorStart();
      // OnePass can be used for the sandwich submatch extraction step (anchored, endMatch=true)
      // when captures need to be extracted from a known match range. Nullable patterns are safe
      // here because match bounds are already known. Lazy quantifiers are excluded because
      // OnePass returns leftmost-longest capture group boundaries, which differs from
      // leftmost-first semantics for lazy groups.
      boolean canSubmatch = op != null && !hasLazy;
      analysis = new OnePassAnalysis(op, canPrimary, canFind, canSubmatch);
      onePassAnalysis = analysis;
    }
    return analysis;
  }

  /** Returns the one-pass automaton, or {@code null} if the pattern is not one-pass. */
  OnePass onePass() {
    return onePassAnalysis().onePass();
  }

  /**
   * Returns whether OnePass can be used as the primary matching engine, bypassing the DFA
   * entirely. This is true when the pattern is OnePass-eligible, non-nullable, and has no lazy
   * quantifiers. The non-nullable restriction prevents leftmost-first ambiguity bugs where a
   * nullable alternative (e.g., {@code a*} in {@code a*|c.}) would incorrectly lose to a longer
   * alternative under OnePass's longest-match semantics.
   */
  boolean canOnePassPrimary() {
    return onePassAnalysis().canPrimary();
  }

  /**
   * Returns whether OnePass can be used directly in {@code find()} for anchored patterns. This is
   * {@link #canOnePassPrimary()} restricted to patterns anchored at the start.
   */
  boolean canOnePassFind() {
    return onePassAnalysis().canFind();
  }

  /**
   * Returns whether OnePass can be used for submatch extraction in the sandwich path. This is true
   * when the pattern is OnePass-eligible and has no lazy quantifiers. Nullable patterns are safe
   * here because match bounds are already determined by the DFA.
   */
  boolean canOnePassSubmatch() {
    return onePassAnalysis().canSubmatch();
  }

  /**
   * Returns {@code true} when the DFA's leftmost-longest group(0) boundaries are guaranteed to
   * match RE2's leftmost-first semantics. The DFA uses POSIX leftmost-longest matching which can
   * disagree with Perl/RE2 leftmost-first semantics in three cases:
   *
   * <ol>
   *   <li>Lazy quantifiers: prefer shortest match, but the DFA gives longest.
   *   <li>Alternation: the DFA picks the longest branch, but RE2 picks the first matching branch.
   *   <li>Bounded repetitions ({@code a{3,4}}): nested inside quantifiers, the DFA may find a
   *       globally longer match by choosing fewer characters per iteration, while RE2 greedily
   *       maximizes each iteration.
   * </ol>
   *
   * <p>When this returns {@code false}, the DFA sandwich is skipped and the submatch engine
   * (BitState/NFA) determines the correct match boundaries.
   */
  boolean dfaGroupZeroReliable() {
    return !hasLazy && !hasAlternation && !hasBoundedRepeat && !hasAnchorInQuant
        && prog.numLoopRegs() == 0;
  }

  /**
   * Returns {@code true} if the pattern contains alternation ({@code |}). Used by the Matcher to
   * skip OnePass primary for find() — OnePass always uses longest-match semantics, which can pick
   * the wrong alternative when a zero-width branch competes with a consuming branch.
   */
  boolean hasAlternation() {
    return hasAlternation;
  }

  /**
   * Returns {@code true} if the pattern contains an alternation where at least one branch can
   * match zero characters. This is the specific case where OnePass's longest-match semantics
   * produce incorrect results: a zero-width branch (assertion, nullable repetition) loses to
   * a consuming branch under longest-match, but should win under first-match (leftmost-first).
   *
   * <p>When this returns {@code false}, alternations are safe for OnePass because all branches
   * must consume at least one character, making longest-match and first-match equivalent.
   */
  boolean hasNullableAlternation() {
    return hasNullableAlternation;
  }

  /**
   * Returns {@code true} when the DFA sandwich correctly identifies the leftmost match
   * <em>start</em> position, even if the match end may be wrong. This is a weaker guarantee than
   * {@link #dfaGroupZeroReliable()}: the sandwich can narrow the search range for the submatch
   * engine, but captures must still be resolved (with {@code endMatch=false}) to determine the
   * correct match end.
   *
   * <p>The DFA start is reliable when the leftmost-starting match is the only match ending at the
   * forward DFA's earliest end. This fails for:
   *
   * <ul>
   *   <li>Lazy quantifiers: {@code .+?X} starting at position 0 may end later than a fixed match
   *       starting at position 1.
   *   <li>Bounded repetitions: an optional bounded prefix like {@code (?:a{1,3})?a{3}} can match
   *       the same text with multiple starts ending at the same position, and the reverse DFA may
   *       return the later start.
   *   <li>Anchors inside quantifiers: the reverse DFA mishandles position-dependent assertions.
   *   <li>Alternation: when alternatives can match at different start positions with different
   *       endpoints, the forward DFA's earliest-end result may come from a non-leftmost match.
   *       For example, {@code (bcd|abcde)} on text containing "abcde" — the forward DFA returns
   *       the end of the "bcd" match (which ends earlier) instead of the "abcde" match (which
   *       starts earlier). The reverse DFA from that wrong endpoint cannot find the leftmost start.
   * </ul>
   */
  boolean dfaStartReliable() {
    return !hasLazy && !hasBoundedRepeat && !hasAnchorInQuant && !hasAlternation;
  }

  /**
   * Returns the literal prefix for this pattern, or {@code null} if the pattern has no fixed
   * literal prefix. Used for prefix acceleration in {@link Matcher#doFind()}.
   */
  String prefix() {
    return prefix;
  }

  /** Returns whether the prefix should be matched case-insensitively. */
  boolean prefixFoldCase() {
    return prefixFoldCase;
  }

  /**
   * Returns a {@code boolean[128]} ASCII bitmap of the character-class prefix, or {@code null} if
   * the pattern has no character-class prefix. Used for prefix acceleration in
   * {@link Matcher#doFind()} when no literal prefix exists.
   */
  boolean[] charClassPrefixAscii() {
    return charClassPrefixAscii;
  }

  /** Returns conservative start-position acceleration data, or {@code null} if unavailable. */
  StartAcceleration startAcceleration() {
    return startAcceleration;
  }

  /** Returns case-insensitive keyword-alternation fast-path data, or {@code null}. */
  KeywordAlternation keywordAlternation() {
    return keywordAlternation;
  }

  /**
   * Returns the precomputed ranges for the character-class-match fast path, or {@code null} if
   * the pattern is not a simple repeated character class. When non-null, {@code matches()} can
   * use a tight scanning loop instead of the full engine cascade.
   */
  int[] charClassMatchRanges() {
    return charClassMatchRanges;
  }

  /** ASCII bitmap (code points 0–63) for the character-class-match fast path. */
  long charClassMatchBitmap0() {
    return charClassMatchBitmap0;
  }

  /** ASCII bitmap (code points 64–127) for the character-class-match fast path. */
  long charClassMatchBitmap1() {
    return charClassMatchBitmap1;
  }

  /** Whether the character-class-match fast path allows empty input (from {@code *} or {@code ?}). */
  boolean charClassMatchAllowEmpty() {
    return charClassMatchAllowEmpty;
  }

  /**
   * Returns the reverse-compiled program for backward DFA matching. The reverse program is compiled
   * lazily on first access, since many patterns never need it (anchored patterns, patterns used
   * only with {@code matches()} or {@code lookingAt()}, single-find workloads).
   *
   * <p>Thread-safe via volatile: benign data race at worst compiles twice, but {@link Prog} is
   * effectively immutable once constructed.
   */
  Prog reverseProg() {
    Prog rp = reverseProg;
    if (rp == null) {
      rp = Compiler.compile(ast, true);
      reverseDfaSetup = Dfa.buildSetup(rp);
      reverseProg = rp;
    }
    return rp;
  }

  /**
   * Returns the DFA equivalence-class setup for the forward program. Lazily computed on first
   * access so that compile time is not penalized for patterns that may not need DFA matching.
   * Thread-safe via volatile: benign data race at worst computes twice.
   */
  Dfa.Setup forwardDfaSetup() {
    Dfa.Setup setup = forwardDfaSetup;
    if (setup == null) {
      setup = Dfa.buildSetup(prog);
      forwardDfaSetup = setup;
    }
    return setup;
  }

  /**
   * Returns the pre-computed DFA setup for the reverse program. Triggers lazy compilation of the
   * reverse program if not already done.
   */
  Dfa.Setup reverseDfaSetup() {
    reverseProg(); // ensure reverse prog and its setup are computed
    return reverseDfaSetup;
  }

  /**
   * Returns the full literal string for patterns that are entirely literal (no metacharacters),
   * or {@code null} if the pattern is not fully literal. For case-insensitive patterns, returns
   * the lowercase version.
   */
  String literalMatch() {
    return literalMatch;
  }

  /** Returns {@code true} if this pattern is a simple literal with no metacharacters. */
  boolean isLiteral() {
    return literalMatch != null;
  }

  /** Returns the parsed AST. */
  Regexp ast() {
    return ast;
  }

  /**
   * Returns an unmodifiable map of named capturing groups to their 1-based group indices.
   *
   * <p>If the pattern has no named capturing groups, an empty map is returned.
   *
   * @return an unmodifiable map from group names to group numbers
   * @since 20
   */
  public Map<String, Integer> namedGroups() {
    return namedGroups;
  }

  /**
   * Returns the number of capturing groups in this pattern, not counting the implicit group 0 for
   * the full match.
   */
  int numGroups() {
    return prog.numCaptures() - 1;
  }

  /**
   * Returns {@code true} if the pattern contains end-of-input or end-of-line assertions
   * ({@code $}, {@code \z}, {@code \b}, {@code \B}). Used by {@link Matcher#requireEnd()} to
   * conservatively determine whether more input could invalidate a positive match.
   */
  boolean hasEndConstraint() {
    return hasEndConstraint;
  }

  // ---------------------------------------------------------------------------
  // Flag mapping
  // ---------------------------------------------------------------------------

  /** The set of all flag bits we support. */
  private static final int SUPPORTED_FLAGS =
      UNIX_LINES | CASE_INSENSITIVE | COMMENTS | MULTILINE
          | LITERAL | DOTALL | UNICODE_CASE | UNICODE_CHARACTER_CLASS;

  /** Validates that no unsupported flag bits are set. */
  private static void validateFlags(int flags) {
    int unsupported = flags & ~SUPPORTED_FLAGS;
    if (unsupported != 0) {
      throw new IllegalArgumentException(
          "Unsupported flags: 0x" + Integer.toHexString(unsupported)
              + ". CANON_EQ is not supported by SafeRE.");
    }
  }

  /**
   * Converts {@code java.util.regex.Pattern} flags to internal {@link ParseFlags}.
   *
   * <p>The baseline is {@link ParseFlags#LIKE_PERL}, which includes {@code ONE_LINE} (single-line
   * mode where {@code ^} and {@code $} match only at the start/end of the entire input).
   */
  private static int toParseFlags(int flags) {
    // Start with LIKE_PERL as the baseline.
    int pf = ParseFlags.LIKE_PERL;

    if ((flags & CASE_INSENSITIVE) != 0) {
      pf |= ParseFlags.FOLD_CASE;
    }
    if ((flags & MULTILINE) != 0) {
      // Multiline mode: ^ and $ match at line boundaries.
      // Remove ONE_LINE so that ^ and $ are per-line.
      pf &= ~ParseFlags.ONE_LINE;
    }
    if ((flags & DOTALL) != 0) {
      pf |= ParseFlags.DOT_NL;
    }
    if ((flags & LITERAL) != 0) {
      pf |= ParseFlags.LITERAL;
    }
    if ((flags & COMMENTS) != 0) {
      pf |= ParseFlags.COMMENTS;
    }
    if ((flags & UNICODE_CASE) != 0) {
      pf |= ParseFlags.UNICODE_GROUPS;
    }
    if ((flags & UNICODE_CHARACTER_CLASS) != 0) {
      pf |= ParseFlags.UNICODE_GROUPS | ParseFlags.UNICODE_CHAR_CLASS;
    }
    if ((flags & UNIX_LINES) != 0) {
      pf |= ParseFlags.UNIX_LINES;
    }

    return pf;
  }

  // ---------------------------------------------------------------------------
  // Named group extraction
  // ---------------------------------------------------------------------------

  /** Walks the AST to extract named capture groups and their 1-based indices. */
  private static Map<String, Integer> extractNamedGroups(Regexp re) {
    Map<String, Integer> map = new HashMap<>();
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      if (node.op == RegexpOp.CAPTURE && node.name != null) {
        map.put(node.name, node.cap);
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(sub);
        }
      }
    }
    return Collections.unmodifiableMap(map);
  }

  /**
   * Returns {@code true} if the AST contains any lazy (non-greedy) quantifiers ({@code +?},
   * {@code *?}, {@code ??}, or {@code {n,m}?}). OnePass does not respect lazy vs greedy semantics
   * for overall match boundaries, so patterns with lazy quantifiers must use the DFA pipeline in
   * {@code find()}.
   */
  private static boolean hasLazyQuantifiers(Regexp re) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      if (node.nonGreedy()) {
        return true;
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(sub);
        }
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if the AST contains any explicit alternation ({@link RegexpOp#ALTERNATE}).
   * Patterns with alternation may have branches of different match lengths, causing the DFA's
   * leftmost-longest match to disagree with RE2's leftmost-first alternation priority. When this
   * flag is set, the submatch engine must resolve the correct group(0) boundaries.
   */
  private static boolean hasAlternation(Regexp re) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      if (node.op == RegexpOp.ALTERNATE) {
        return true;
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(sub);
        }
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if the AST contains an alternation where at least one branch can match
   * zero characters (is "nullable"). This detects the case where OnePass's longest-match semantics
   * differ from first-match: a zero-width branch (assertion, empty match, nullable repetition)
   * competing with a consuming branch. For alternations where all branches must consume at least
   * one character (e.g., {@code GET|POST}), OnePass gives correct results.
   */
  private static boolean hasNullableAlternation(Regexp re) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      if (node.op == RegexpOp.ALTERNATE && node.subs != null) {
        for (Regexp branch : node.subs) {
          if (canMatchEmpty(branch)) {
            return true;
          }
        }
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(sub);
        }
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if the given regexp can match the empty string. Used to detect nullable
   * alternation branches where OnePass's longest-match semantics may differ from first-match.
   */
  private static boolean canMatchEmpty(Regexp re) {
    return switch (re.op) {
      case EMPTY_MATCH, BEGIN_LINE, END_LINE, BEGIN_TEXT, END_TEXT, WORD_BOUNDARY,
           NO_WORD_BOUNDARY -> true;
      case STAR, QUEST -> true; // min=0
      case REPEAT -> re.min == 0;
      case PLUS -> re.subs != null && !re.subs.isEmpty() && canMatchEmpty(re.subs.get(0));
      case CAPTURE -> re.subs != null && !re.subs.isEmpty() && canMatchEmpty(re.subs.get(0));
      case CONCAT -> {
        if (re.subs == null) {
          yield true;
        }
        for (Regexp sub : re.subs) {
          if (!canMatchEmpty(sub)) {
            yield false;
          }
        }
        yield true;
      }
      case ALTERNATE -> {
        if (re.subs == null) {
          yield true;
        }
        for (Regexp sub : re.subs) {
          if (canMatchEmpty(sub)) {
            yield true;
          }
        }
        yield false;
      }
      default ->
        // LITERAL, LITERAL_STRING, CHAR_CLASS, ANY_CHAR, etc. — must consume at least one char.
        false;
    };
  }

  /**
   * Returns {@code true} if the AST contains a bounded repetition ({@code a{3,4}}, {@code a?}).
   * Bounded repetitions have min &lt; max with a finite max, creating ambiguity: greedy matching
   * maximizes each iteration while the DFA maximizes the overall match. For example,
   * {@code (?:a{3,4})+} on "aaaaaa": greedy gives 4 (one iteration), DFA gives 6 (two iterations
   * of 3).
   */
  private static boolean hasBoundedRepeat(Regexp re) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      if ((node.op == RegexpOp.REPEAT || node.op == RegexpOp.QUEST)
          && node.min < node.max && node.max > 0) {
        return true;
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(sub);
        }
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if the AST contains positional assertions that the JDK's
   * {@code Matcher.requireEnd()} tracks: {@code $} ({@link RegexpOp#END_LINE} in multiline, or
   * {@link RegexpOp#END_TEXT} with {@link ParseFlags#WAS_DOLLAR} in non-multiline), {@code \b}
   * ({@link RegexpOp#WORD_BOUNDARY}), or {@code \B} ({@link RegexpOp#NO_WORD_BOUNDARY}).
   *
   * <p>Note: {@code \z} ({@link RegexpOp#END_TEXT} without WAS_DOLLAR) is intentionally excluded
   * because the JDK does not set {@code requireEnd} for it.
   */
  private static boolean hasEndConstraint(Regexp re) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      switch (node.op) {
        case END_LINE, WORD_BOUNDARY, NO_WORD_BOUNDARY -> { return true; }
        case END_TEXT -> {
          if ((node.flags & ParseFlags.WAS_DOLLAR) != 0) {
            return true;
          }
        }
        default -> {}
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(sub);
        }
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if the AST contains an anchor ({@code ^}, {@code $}, {@code \A},
   * {@code \z}, {@code \b}, {@code \B}) inside a quantifier ({@code *}, {@code +}, {@code ?},
   * {@code {n,m}}). The DFA sandwich's reverse pass cannot correctly handle anchors inside
   * repeats — the reverse program translates {@code ^} into an end-of-text assertion that may
   * match at a different position than the forward program's start-of-text assertion.
   */
  private static boolean hasAnchorInQuantifier(Regexp re) {
    record Entry(Regexp node, boolean insideQuant) {}
    // Walk the AST, tracking whether we're inside a quantifier.
    Deque<Entry> stack = new ArrayDeque<>();
    stack.push(new Entry(re, false));
    while (!stack.isEmpty()) {
      Entry entry = stack.pop();
      Regexp node = entry.node();
      boolean insideQuant = entry.insideQuant();
      if (insideQuant) {
        switch (node.op) {
          case BEGIN_LINE, END_LINE, BEGIN_TEXT, END_TEXT, WORD_BOUNDARY, NO_WORD_BOUNDARY ->
              { return true; }
          default -> {}
        }
      }
      boolean childInsideQuant = insideQuant;
      switch (node.op) {
        case STAR, PLUS, QUEST, REPEAT -> childInsideQuant = true;
        default -> {}
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(new Entry(sub, childInsideQuant));
        }
      }
    }
    return false;
  }

  /** Result of prefix extraction: a literal string prefix and whether it is case-folded. */
  private record PrefixResult(String prefix, boolean foldCase) {}

  /**
   * Conservative start-position accelerator.
   *
   * <p>Every match must start at a multiline {@code ^} position, optionally with the first
   * consumed ASCII character in {@code asciiStart}. The accelerator only advances the initial
   * search position before handing off to the normal linear engine pipeline.
   */
  static final class StartAcceleration {
    final boolean requireLineStart;
    final boolean allowLineStart;
    final boolean[] asciiStart;

    StartAcceleration(boolean requireLineStart, boolean allowLineStart, boolean[] asciiStart) {
      this.requireLineStart = requireLineStart;
      this.allowLineStart = allowLineStart;
      this.asciiStart = asciiStart;
    }
  }

  /** Fast-path data for {@code (?i)\b(keyword|...)\b}. */
  static final class KeywordAlternation {
    final String[] keywords;
    final boolean[] firstAscii;
    final int captureGroup;
    final boolean unicodeWordBoundary;

    KeywordAlternation(
        String[] keywords, boolean[] firstAscii, int captureGroup, boolean unicodeWordBoundary) {
      this.keywords = keywords;
      this.firstAscii = firstAscii;
      this.captureGroup = captureGroup;
      this.unicodeWordBoundary = unicodeWordBoundary;
    }
  }

  /**
   * Extracts a literal prefix from the simplified AST for prefix acceleration. Returns a {@link
   * PrefixResult} containing the literal string that every match must start with (or {@code null}
   * if no fixed prefix exists) and whether the prefix is case-folded.
   *
   * <p>This looks for patterns that begin with literal characters (possibly inside a CONCAT or
   * CAPTURE). The prefix is used by {@link Matcher#doFind()} to skip ahead using {@link
   * String#indexOf} before running the full engine.
   */
  private static PrefixResult extractPrefix(Regexp re) {
    Regexp node = re;

    // See through leading captures and concat wrappers.
    while (node != null) {
      if (node.op == RegexpOp.CAPTURE) {
        node = node.sub();
        continue;
      }
      if (node.op == RegexpOp.CONCAT && node.nsub() > 0) {
        node = node.subs.getFirst();
        continue;
      }
      break;
    }
    if (node == null) {
      return new PrefixResult(null, false);
    }

    // Check for literal or literal string.
    boolean foldCase = (node.flags & ParseFlags.FOLD_CASE) != 0;
    StringBuilder sb = new StringBuilder();
    if (node.op == RegexpOp.LITERAL) {
      sb.appendCodePoint(node.rune);
    } else if (node.op == RegexpOp.LITERAL_STRING && node.runes != null) {
      for (int r : node.runes) {
        sb.appendCodePoint(r);
      }
    } else {
      return new PrefixResult(null, false);
    }

    if (sb.isEmpty()) {
      return new PrefixResult(null, false);
    }

    String prefix = foldCase ? sb.toString().toLowerCase(Locale.ROOT) : sb.toString();
    return new PrefixResult(prefix, foldCase);
  }

  /**
   * Extracts a character-class prefix bitmap for ASCII acceleration. Walks the AST (through CAPTURE
   * and CONCAT wrappers) to find a required character class at the start of the pattern. If found
   * and the class contains only ASCII code points, returns a {@code boolean[128]} bitmap where
   * {@code true} entries indicate matching code points. This allows {@link Matcher#doFind()} to
   * skip ahead to positions where the first character could start a match, avoiding unnecessary
   * engine invocations.
   *
   * <p>Handles bare {@link RegexpOp#CHAR_CLASS}, {@link RegexpOp#PLUS} and
   * {@link RegexpOp#REPEAT} (with {@code min >= 1}) wrapping a character class, since these all
   * require at least one character from the class.
   *
   * @return a {@code boolean[128]} ASCII bitmap, or {@code null} if no suitable prefix exists
   */
  private static boolean[] extractCharClassPrefixAscii(Regexp re) {
    Regexp node = re;

    // See through leading captures and concat wrappers.
    while (node != null) {
      if (node.op == RegexpOp.CAPTURE) {
        node = node.sub();
        continue;
      }
      if (node.op == RegexpOp.CONCAT && node.nsub() > 0) {
        node = node.subs.getFirst();
        continue;
      }
      break;
    }
    if (node == null) {
      return null;
    }

    // See through required quantifiers (PLUS, REPEAT with min >= 1).
    if (node.op == RegexpOp.PLUS
        || (node.op == RegexpOp.REPEAT && node.min >= 1)) {
      node = node.sub();
    }

    if (node.op != RegexpOp.CHAR_CLASS || node.charClass == null) {
      return null;
    }

    CharClass cc = node.charClass;
    if (cc.isEmpty()) {
      return null;
    }

    // Only accelerate ASCII-only character classes.
    for (int i = 0; i < cc.numRanges(); i++) {
      if (cc.hi(i) >= 128) {
        return null;
      }
    }

    boolean[] bitmap = new boolean[128];
    for (int i = 0; i < cc.numRanges(); i++) {
      for (int cp = cc.lo(i); cp <= cc.hi(i); cp++) {
        bitmap[cp] = true;
      }
    }
    return bitmap;
  }

  private static StartAcceleration extractStartAcceleration(Regexp re) {
    Regexp node = unwrapCaptures(re);
    if (node == null) {
      return null;
    }

    if (node.op == RegexpOp.CONCAT && node.nsub() > 0) {
      Regexp first = unwrapCaptures(node.subs.get(0));
      if (first != null && first.op == RegexpOp.BEGIN_LINE) {
        boolean[] requiredStart = null;
        if (node.nsub() > 1) {
          requiredStart = requiredFirstAscii(node.subs.get(1));
        }
        return new StartAcceleration(true, false, requiredStart);
      }
      return null;
    }

    if (node.op == RegexpOp.BEGIN_LINE) {
      return new StartAcceleration(true, false, null);
    }
    return null;
  }

  private static KeywordAlternation extractKeywordAlternation(Regexp re, int patternFlags) {
    if ((patternFlags & UNICODE_CASE) != 0) {
      return null;
    }

    Regexp node = unwrapImplicitCapture(re);
    if (node == null || node.op != RegexpOp.CONCAT || node.nsub() != 3) {
      return null;
    }
    Regexp before = unwrapImplicitCapture(node.subs.get(0));
    Regexp middle = unwrapImplicitCapture(node.subs.get(1));
    Regexp after = unwrapImplicitCapture(node.subs.get(2));
    if (before == null || before.op != RegexpOp.WORD_BOUNDARY
        || after == null || after.op != RegexpOp.WORD_BOUNDARY) {
      return null;
    }

    int captureGroup = -1;
    if (middle != null && middle.op == RegexpOp.CAPTURE && middle.cap > 0) {
      captureGroup = middle.cap;
      middle = unwrapImplicitCapture(middle.sub());
    }
    if (middle == null || middle.op != RegexpOp.ALTERNATE || middle.subs == null
        || middle.subs.isEmpty()) {
      return null;
    }
    if (hasOtherUserCaptures(middle, captureGroup)) {
      return null;
    }

    String[] keywords = new String[middle.subs.size()];
    boolean[] firstAscii = new boolean[128];
    for (int i = 0; i < middle.subs.size(); i++) {
      String keyword = extractAsciiCaseInsensitiveLiteral(middle.subs.get(i));
      if (keyword == null || keyword.isEmpty()) {
        return null;
      }
      keywords[i] = keyword;
      firstAscii[keyword.charAt(0)] = true;
    }
    boolean unicodeWordBoundary = (before.flags & ParseFlags.UNICODE_CHAR_CLASS) != 0;
    return new KeywordAlternation(keywords, firstAscii, captureGroup, unicodeWordBoundary);
  }

  private static boolean hasOtherUserCaptures(Regexp re, int allowedCapture) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      if (node.op == RegexpOp.CAPTURE && node.cap > 0 && node.cap != allowedCapture) {
        return true;
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(sub);
        }
      }
    }
    return false;
  }

  private static Regexp unwrapImplicitCapture(Regexp re) {
    Regexp node = re;
    while (node != null && node.op == RegexpOp.CAPTURE && node.cap == 0) {
      node = node.sub();
    }
    return node;
  }

  private static String extractAsciiCaseInsensitiveLiteral(Regexp re) {
    Regexp node = unwrapImplicitCapture(re);
    if (node == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    if (!appendAsciiCaseInsensitiveLiteral(node, sb)) {
      return null;
    }
    return sb.toString();
  }

  private static boolean appendAsciiCaseInsensitiveLiteral(Regexp node, StringBuilder sb) {
    node = unwrapImplicitCapture(node);
    if (node == null) {
      return false;
    }
    switch (node.op) {
      case CONCAT -> {
        if (node.subs == null || node.subs.isEmpty()) {
          return false;
        }
        for (Regexp sub : node.subs) {
          if (!appendAsciiCaseInsensitiveLiteral(sub, sb)) {
            return false;
          }
        }
        return true;
      }
      case LITERAL -> {
        int cp = node.rune;
        if (cp < 0 || cp >= 128 || !isAsciiLiteralKeywordChar(cp)) {
          return false;
        }
        if ((node.flags & ParseFlags.FOLD_CASE) == 0) {
          return false;
        }
        sb.append((char) asciiLower(cp));
        return true;
      }
      case LITERAL_STRING -> {
        if (node.runes == null || node.runes.length == 0) {
          return false;
        }
        if ((node.flags & ParseFlags.FOLD_CASE) == 0) {
          return false;
        }
        for (int cp : node.runes) {
          if (cp < 0 || cp >= 128 || !isAsciiLiteralKeywordChar(cp)) {
            return false;
          }
          sb.append((char) asciiLower(cp));
        }
        return true;
      }
      case CHAR_CLASS -> {
        int cp = asciiFoldedLiteralChar(node.charClass);
        if (cp < 0) {
          return false;
        }
        sb.append((char) cp);
        return true;
      }
      default -> {
        return false;
      }
    }
  }

  private static boolean isAsciiLiteralKeywordChar(int cp) {
    return ('A' <= cp && cp <= 'Z') || ('a' <= cp && cp <= 'z')
        || ('0' <= cp && cp <= '9') || cp == '_';
  }

  private static int asciiLower(int cp) {
    return ('A' <= cp && cp <= 'Z') ? cp + ('a' - 'A') : cp;
  }

  private static int asciiFoldedLiteralChar(CharClass cc) {
    if (cc == null || cc.numRunes() != 2) {
      return -1;
    }
    for (int cp = 'a'; cp <= 'z'; cp++) {
      if (cc.contains(cp) && cc.contains(cp - ('a' - 'A'))) {
        return cp;
      }
    }
    return -1;
  }

  private static Regexp firstMeaningfulNode(Regexp re) {
    Regexp node = unwrapCaptures(re);
    if (node == null) {
      return null;
    }
    if (node.op == RegexpOp.CONCAT && node.nsub() > 0) {
      return unwrapCaptures(node.subs.get(0));
    }
    return node;
  }

  private static Regexp unwrapCaptures(Regexp re) {
    Regexp node = re;
    while (node != null && node.op == RegexpOp.CAPTURE) {
      node = node.sub();
    }
    return node;
  }

  private static boolean[] requiredFirstAscii(Regexp re) {
    Regexp node = firstMeaningfulNode(re);
    if (node == null) {
      return null;
    }
    if (node.op == RegexpOp.PLUS
        || (node.op == RegexpOp.REPEAT && node.min >= 1)) {
      node = firstMeaningfulNode(node.sub());
    }
    if (node == null) {
      return null;
    }

    boolean[] bitmap = new boolean[128];
    if (node.op == RegexpOp.LITERAL) {
      if ((node.flags & ParseFlags.FOLD_CASE) != 0 || node.rune >= 128) {
        return null;
      }
      bitmap[node.rune] = true;
      return bitmap;
    }
    if (node.op == RegexpOp.LITERAL_STRING && node.runes != null && node.runes.length > 0) {
      if ((node.flags & ParseFlags.FOLD_CASE) != 0 || node.runes[0] >= 128) {
        return null;
      }
      bitmap[node.runes[0]] = true;
      return bitmap;
    }
    if (node.op == RegexpOp.CHAR_CLASS && node.charClass != null) {
      CharClass cc = node.charClass;
      if (cc.isEmpty()) {
        return null;
      }
      for (int i = 0; i < cc.numRanges(); i++) {
        if (cc.hi(i) >= 128) {
          return null;
        }
      }
      for (int i = 0; i < cc.numRanges(); i++) {
        for (int cp = cc.lo(i); cp <= cc.hi(i); cp++) {
          bitmap[cp] = true;
        }
      }
      return bitmap;
    }
    return null;
  }

  /** Holds precomputed data for the character-class-match fast path. */
  // TODO(#98): Replace int[] with Guava ImmutableIntArray to get proper value semantics.
  @SuppressWarnings("ArrayRecordComponent")
  private record CharClassMatchInfo(
      int[] ranges, long bitmap0, long bitmap1, boolean allowEmpty) {}

  /**
   * Detects patterns that are structurally a single character class under a quantifier covering
   * the entire string — e.g., {@code [a-zA-Z]+}, {@code \d*}, {@code \w{1,}}, {@code [0-9]+}.
   * When detected, {@code matches()} can use a tight character-scanning loop with precomputed
   * bitmaps instead of the full engine cascade.
   *
   * <p>Sees through the implicit group-0 CAPTURE wrapper. Returns {@code null} if the pattern
   * has any user capture groups (the fast path only produces group 0).
   */
  private static CharClassMatchInfo extractCharClassMatch(Regexp re) {
    Regexp node = re;

    // Unwrap implicit group-0 capture.
    if (node.op == RegexpOp.CAPTURE && node.cap == 0) {
      node = node.sub();
    }

    // Must be a quantifier: PLUS (min=1), STAR (min=0), or REPEAT (min >= 0, max = -1).
    boolean allowEmpty;
    switch (node.op) {
      case PLUS -> allowEmpty = false;
      case STAR -> allowEmpty = true;
      case REPEAT -> {
        if (node.max != -1) {
          return null; // bounded repeat like {3,5} — not a "cover entire string" pattern
        }
        if (node.min > 1) {
          return null; // {2,} or higher — would need code point counting; not worth optimizing
        }
        allowEmpty = (node.min == 0);
      }
      default -> {
        return null;
      }
    }

    Regexp inner = node.sub();

    // The quantified element must be a character class.
    if (inner.op != RegexpOp.CHAR_CLASS || inner.charClass == null) {
      return null;
    }

    // Reject if the original pattern has user capture groups — the fast path only produces
    // group 0, so it can't provide group(1) etc.
    if (hasUserCaptures(re)) {
      return null;
    }

    CharClass cc = inner.charClass;
    if (cc.isEmpty()) {
      return null;
    }

    // Build flat ranges array and precompute ASCII bitmaps.
    int numRanges = cc.numRanges();
    int[] ranges = new int[numRanges * 2];
    long b0 = 0;
    long b1 = 0;
    for (int i = 0; i < numRanges; i++) {
      int lo = cc.lo(i);
      int hi = cc.hi(i);
      ranges[i * 2] = lo;
      ranges[i * 2 + 1] = hi;
      int start = Math.max(lo, 0);
      int end = Math.min(hi, 127);
      for (int cp = start; cp <= end; cp++) {
        if (cp < 64) {
          b0 |= 1L << cp;
        } else {
          b1 |= 1L << (cp - 64);
        }
      }
    }
    return new CharClassMatchInfo(ranges, b0, b1, allowEmpty);
  }

  /**
   * Returns {@code true} if the AST contains any CAPTURE node with {@code cap > 0} (user capture
   * groups, not the implicit group 0).
   */
  private static boolean hasUserCaptures(Regexp re) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      if (node.op == RegexpOp.CAPTURE && node.cap > 0) {
        return true;
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(sub);
        }
      }
    }
    return false;
  }

  /**
   * Extracts a literal match string from the AST if the pattern is entirely literal (no
   * metacharacters, no quantifiers, no alternation). Sees through CAPTURE wrappers (group 0 is
   * implicit) and handles LITERAL, LITERAL_STRING, and CONCAT of literals.
   *
   * @return the literal string to match, or {@code null} if the pattern is not fully literal
   */
  private static String extractLiteralMatch(Regexp re) {
    Regexp node = re;

    // Unwrap outer CAPTURE (group 0).
    while (node != null && node.op == RegexpOp.CAPTURE) {
      node = node.sub();
    }
    if (node == null) {
      return null;
    }

    boolean foldCase = (node.flags & ParseFlags.FOLD_CASE) != 0;
    StringBuilder sb = new StringBuilder();

    switch (node.op) {
      case LITERAL -> sb.appendCodePoint(node.rune);
      case LITERAL_STRING -> {
        if (node.runes != null) {
          for (int r : node.runes) {
            sb.appendCodePoint(r);
          }
        }
      }
      case CONCAT -> {
        for (Regexp child : node.subs) {
          // Each child must be LITERAL or LITERAL_STRING (not wrapped in CAPTURE etc.)
          Regexp c = child;
          while (c != null && c.op == RegexpOp.CAPTURE) {
            c = c.sub();
          }
          if (c == null) {
            return null;
          }
          boolean childFoldCase = (c.flags & ParseFlags.FOLD_CASE) != 0;
          if (childFoldCase != foldCase) {
            return null;
          }
          if (c.op == RegexpOp.LITERAL) {
            sb.appendCodePoint(c.rune);
          } else if (c.op == RegexpOp.LITERAL_STRING && c.runes != null) {
            for (int r : c.runes) {
              sb.appendCodePoint(r);
            }
          } else {
            return null;
          }
        }
      }
      case EMPTY_MATCH -> {
        // Empty pattern matches empty string.
        return "";
      }
      default -> {
        return null;
      }
    }

    if (sb.isEmpty()) {
      return "";
    }
    return foldCase ? sb.toString().toLowerCase(Locale.ROOT) : sb.toString();
  }

  /** Deserialization: recompile the pattern from the stored string and flags. */
  private Object readResolve() {
    return compile(pattern, flags);
  }
}
