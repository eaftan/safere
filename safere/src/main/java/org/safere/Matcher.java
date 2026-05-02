// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * An engine that performs match operations on a {@linkplain CharSequence character sequence} by
 * interpreting a {@link Pattern}. This class is a drop-in replacement for
 * {@link java.util.regex.Matcher} backed by a linear-time matching engine.
 *
 * <p>Matching uses a two-phase engine cascade: the DFA quickly determines whether a match exists
 * (and where it ends), then the NFA extracts capture group positions. If the DFA exceeds its state
 * budget, the NFA handles the entire search.
 *
 * <p>A matcher is created from a pattern by invoking the pattern's {@link Pattern#matcher matcher}
 * method. Once created, a matcher can be used to perform three different kinds of match operations:
 *
 * <ul>
 *   <li>The {@link #matches matches} method attempts to match the entire input sequence against
 *       the pattern.
 *   <li>The {@link #lookingAt lookingAt} method attempts to match the input sequence, starting at
 *       the beginning, against the pattern.
 *   <li>The {@link #find find} method scans the input sequence looking for the next subsequence
 *       that matches the pattern.
 * </ul>
 */
public final class Matcher implements MatchResult {
  private sealed interface EngineResult permits NoMatchResult, FullMatchResult, DeferredMatchResult {
  }

  private record NoMatchResult() implements EngineResult {
  }

  private static final class FullMatchResult implements EngineResult {
    private final int[] groups;

    private FullMatchResult(int[] groups) {
      this.groups = groups;
    }

    private int[] groups() {
      return groups;
    }
  }

  private record DeferredMatchResult(
      int start, int end, int ncap, boolean groupZeroResolved, boolean endMatch)
      implements EngineResult {
  }

  private enum MatchOperation {
    MATCHES,
    LOOKING_AT,
    FIND
  }

  private enum ResultStatus {
    RESET_NO_ATTEMPT,
    MATCHED,
    FAILED
  }

  /**
   * Minimum text length for the reverse-first optimization on end-anchored patterns. For shorter
   * texts, the forward DFA is trivially fast and the one-time cost of lazily compiling the reverse
   * program and its DFA setup outweighs any scanning savings.
   */
  private static final int MIN_REVERSE_FIRST_LEN = 1024;

  /**
   * Maximum text length for the anchored OnePass fast path. For anchored patterns where the OnePass
   * DFA built successfully, skip the DFA sandwich entirely and use OnePass as the primary engine.
   * Matches C++ RE2's threshold (re2.cc line 838). For larger texts, the DFA's cached state
   * transitions are more efficient.
   */
  private static final int ONEPASS_ANCHORED_TEXT_LIMIT = 4096;

  /**
   * Maximum number of submatches (including group 0) for lazy fallback capture extraction.
   * Deferring captures saves work for find-all loops that only need group 0, but the first inner
   * group access has to rerun the submatch engine. Keep the optimization to small-capture
   * patterns so capture-heavy parsers do not pay that startup penalty.
   */
  private static final int MAX_LAZY_FALLBACK_SUBMATCHES = 3;

  /**
   * Maximum input length where skipping the forward DFA precheck can pay off for unreliable-start
   * patterns. On short inputs, BitState's complete search is cheap enough that running the DFA
   * first often duplicates work; on longer inputs, the DFA remains valuable as a fast no-match
   * filter.
   */
  private static final int DIRECT_FALLBACK_TEXT_LIMIT = 512;

  private Pattern parentPattern;
  private CharSequence inputSequence;
  private String text;
  private int[] groups;
  private boolean hasMatch;
  private ResultStatus resultStatus = ResultStatus.RESET_NO_ATTEMPT;
  private int searchFrom;
  private int appendPos;
  private boolean transparentBounds;
  private boolean anchoringBounds = true;
  private int regionStart;
  private int regionEnd;
  private boolean lastHitEnd;
  private boolean lastRequireEnd;
  private int modCount;

  /**
   * Cached BitState instance borrowed from the parent Pattern's thread-local cache, reused across
   * {@code find()} calls. Borrowed on first use, returned on final use within the Matcher's
   * lifetime.
   */
  private BitState cachedBitState;
  private boolean bitStateBorrowed;
  private int[] bitStateResult;

  /**
   * Whether all capture groups have been resolved. When the DFA sandwich determines match
   * boundaries (group 0), inner captures (groups 1+) are deferred until explicitly requested.
   * This avoids the expensive BitState/NFA submatch extraction in find-all loops that only
   * check match existence or read group 0.
   */
  private boolean capturesResolved = true;

  /**
   * Whether {@code groups[0..1]} are authoritative while inner captures are deferred. Some DFA
   * paths only narrow the candidate range, so group 0 must still be resolved by the submatch
   * engine before it is exposed.
   */
  private boolean groupZeroResolved = true;
  /** Stashed match boundaries for deferred capture resolution. */
  private int deferredMatchStart;
  private int deferredMatchEnd;
  private boolean deferredEndMatch;

  /**
   * Whether later fallback {@code find()} calls in this matcher should capture all groups eagerly.
   * Starts false so find-all loops that only need group 0 avoid capture extraction; flips true
   * after the caller asks for inner captures or a snapshot, which is a strong signal that future
   * matches will need captures too.
   */
  private boolean eagerFallbackCaptures;

  /**
   * Cached DFA references to avoid repeated ThreadLocal lookups in find-all loops. Populated on
   * first use and reused for subsequent calls within this Matcher's lifetime.
   */
  private Dfa cachedForwardDfa;
  private Dfa cachedReverseDfa;
  private boolean reverseDfaLookedUp;

  /**
   * Creates a new matcher that will match the given input against the given pattern.
   *
   * @param pattern the pattern to use
   * @param input the input character sequence
   */
  Matcher(Pattern pattern, CharSequence input) {
    this.parentPattern = pattern;
    this.inputSequence = input;
    this.text = charSequenceToString(input);
    this.regionEnd = text.length();
  }

  /**
   * Materializes a CharSequence into a String by reading through {@code charAt()}, so that custom
   * CharSequence implementations that don't override {@code toString()} work correctly.
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

  private static boolean requiresPikeNfaCaptureSemantics(Prog prog) {
    return prog.requiresPikeNfaCaptureSemantics() && prog.numCaptures() > 1;
  }

  private boolean applyEngineResult(EngineResult result) {
    switch (result) {
      case NoMatchResult ignored -> {
        applyFailedMatchResult();
      }
      case FullMatchResult full -> {
        applyFullMatchResult(full.groups());
      }
      case DeferredMatchResult deferred -> {
        applyDeferredMatchResult(deferred);
      }
    }
    return hasMatch;
  }

  private void applyFailedMatchResult() {
    groups = null;
    hasMatch = false;
    resultStatus = ResultStatus.FAILED;
    clearDeferredCaptureState();
  }

  private void applyFullMatchResult(int[] resultGroups) {
    groups = resultGroups;
    hasMatch = resultGroups != null;
    resultStatus = hasMatch ? ResultStatus.MATCHED : ResultStatus.FAILED;
    clearDeferredCaptureState();
  }

  private void applyDeferredMatchResult(DeferredMatchResult deferred) {
    groups = new int[2 * deferred.ncap()];
    Arrays.fill(groups, -1);
    groups[0] = deferred.start();
    groups[1] = deferred.end();
    deferredMatchStart = deferred.start();
    deferredMatchEnd = deferred.end();
    deferredEndMatch = deferred.endMatch();
    groupZeroResolved = deferred.groupZeroResolved();
    capturesResolved = deferred.groupZeroResolved() && deferred.ncap() <= 1;
    hasMatch = true;
    resultStatus = ResultStatus.MATCHED;
  }

  private void clearCurrentResult() {
    groups = null;
    hasMatch = false;
    resultStatus = ResultStatus.RESET_NO_ATTEMPT;
    clearDeferredCaptureState();
  }

  private void clearDeferredCaptureState() {
    capturesResolved = true;
    groupZeroResolved = true;
    deferredMatchStart = 0;
    deferredMatchEnd = 0;
    deferredEndMatch = false;
  }

  private void resetReplacementState() {
    appendPos = 0;
  }

  private void resetSearchStateForInputStart() {
    searchFrom = 0;
  }

  private void resetSearchStateForRegionStart() {
    searchFrom = regionStart;
  }

  private void resetStateForCurrentInput() {
    text = charSequenceToString(inputSequence);
    regionStart = 0;
    regionEnd = text.length();
    resetSearchStateForInputStart();
    resetReplacementState();
    clearCurrentResult();
    eagerFallbackCaptures = false;
  }

  private void resetStateForRegion(int start, int end) {
    regionStart = start;
    regionEnd = end;
    resetSearchStateForRegionStart();
    resetReplacementState();
    clearCurrentResult();
  }

  private void invalidatePatternCaches() {
    cachedForwardDfa = null;
    cachedReverseDfa = null;
    reverseDfaLookedUp = false;
    if (bitStateBorrowed && cachedBitState != null) {
      bitStateBorrowed = false;
      cachedBitState = null;
    }
    bitStateResult = null;
  }

  private void invalidateInputDependentCaches() {
    bitStateBorrowed = false;
    cachedBitState = null;
    bitStateResult = null;
  }

  private void preserveResultAcrossBoundsChange() {
    if (hasMatch && !capturesResolved) {
      resolveCaptures();
    }
    clearDeferredCaptureState();
  }

  private EnginePathOptions enginePathOptions() {
    return parentPattern.enginePathOptions();
  }

  private boolean semanticGuardsEnabled() {
    return enginePathOptions().semanticGuards();
  }

  private boolean canUsePikeEquivalentCaptures(Prog prog) {
    return !semanticGuardsEnabled() || !requiresPikeNfaCaptureSemantics(prog);
  }

  /** Returns the Pattern's thread-local cached forward DFA, caching it for reuse. */
  private Dfa dfa() {
    Dfa d = cachedForwardDfa;
    if (d == null) {
      d = parentPattern.forwardDfa();
      cachedForwardDfa = d;
    }
    return d;
  }

  /** Returns the Pattern's thread-local cached reverse DFA (or null), caching it for reuse. */
  private Dfa reverseDfa() {
    if (!reverseDfaLookedUp) {
      cachedReverseDfa = parentPattern.reverseDfa();
      reverseDfaLookedUp = true;
    }
    return cachedReverseDfa;
  }

  // ---------------------------------------------------------------------------
  // Core matching methods
  // ---------------------------------------------------------------------------

  /**
   * Fast path for {@code matches()} when the pattern is a single character class under a
   * quantifier (e.g., {@code [a-zA-Z]+}, {@code \d*}). Uses precomputed ASCII bitmaps for O(1)
   * per-character checks and falls back to binary search for non-ASCII code points.
   */
  private boolean charClassMatchFastPath(int[] ranges) {
    long b0 = parentPattern.charClassMatchBitmap0();
    long b1 = parentPattern.charClassMatchBitmap1();
    boolean allowEmpty = parentPattern.charClassMatchAllowEmpty();

    int len = text.length();
    if (len == 0) {
      if (allowEmpty) {
        return applyEngineResult(new FullMatchResult(new int[]{0, 0}));
      }
      return applyEngineResult(new NoMatchResult());
    }

    // Scan every code point.
    int i = 0;
    while (i < len) {
      int cp = text.codePointAt(i);
      if (cp < 64) {
        if ((b0 & (1L << cp)) == 0) {
          return applyEngineResult(new NoMatchResult());
        }
      } else if (cp < 128) {
        if ((b1 & (1L << (cp - 64))) == 0) {
          return applyEngineResult(new NoMatchResult());
        }
      } else {
        if (!binarySearchRanges(ranges, cp)) {
          return applyEngineResult(new NoMatchResult());
        }
      }
      i += Character.charCount(cp);
    }

    return applyEngineResult(new FullMatchResult(new int[]{0, len}));
  }

  /** Binary search through sorted [lo, hi] ranges to check if {@code cp} is in any range. */
  private static boolean binarySearchRanges(int[] ranges, int cp) {
    int lo = 0;
    int hi = ranges.length / 2 - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      int rangeLo = ranges[mid * 2];
      int rangeHi = ranges[mid * 2 + 1];
      if (cp < rangeLo) {
        hi = mid - 1;
      } else if (cp > rangeHi) {
        lo = mid + 1;
      } else {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if the replacement string contains no group references ({@code $}) or
   * escape sequences ({@code \}). When true, {@code replaceAll} can use a fast path that appends
   * the replacement string directly without per-character scanning.
   */
  private static boolean isSimpleReplacement(String replacement) {
    for (int i = 0; i < replacement.length(); i++) {
      char c = replacement.charAt(i);
      if (c == '$' || c == '\\') {
        return false;
      }
    }
    return true;
  }

  // ---------------------------------------------------------------------------
  // Compiled replacement template
  // ---------------------------------------------------------------------------

  /**
   * A pre-parsed segment of a replacement string. Segments are either literal text or group
   * references (numbered or named). Pre-parsing avoids per-match scanning, {@code parseInt},
   * and {@code substring} allocation.
   */
  private sealed interface ReplacementSegment {
    /** A literal text segment to be appended verbatim. */
    record Literal(String text) implements ReplacementSegment {}

    /** A numbered group reference ({@code $0}, {@code $1}, etc.). */
    record GroupRef(int groupNum) implements ReplacementSegment {}

    /** A named group reference ({@code ${name}}). */
    record NamedGroupRef(String name) implements ReplacementSegment {}
  }

  private record NumericGroupReference(int groupNum, int end) {}

  /**
   * Pre-parses a replacement string into a compiled template of segments. The template can be
   * applied repeatedly without re-scanning the replacement string.
   *
   * @param replacement the replacement string (may contain {@code $1}, {@code ${name}},
   *     {@code \\}, {@code \$})
   * @param maxGroup the highest legal capturing-group number, excluding group 0
   * @return an array of segments representing the compiled template
   * @throws IllegalArgumentException if the replacement string is malformed
   */
  private static ReplacementSegment[] compileReplacementTemplate(String replacement, int maxGroup) {
    // Fast path: no special characters → single literal segment.
    if (isSimpleReplacement(replacement)) {
      return new ReplacementSegment[]{new ReplacementSegment.Literal(replacement)};
    }

    java.util.List<ReplacementSegment> segments = new java.util.ArrayList<>();
    StringBuilder literal = new StringBuilder();
    int i = 0;

    while (i < replacement.length()) {
      char c = replacement.charAt(i);
      if (c == '\\') {
        i++;
        if (i >= replacement.length()) {
          throw new IllegalArgumentException("Trailing backslash in replacement string");
        }
        literal.append(replacement.charAt(i));
        i++;
      } else if (c == '$') {
        // Flush accumulated literal text.
        if (!literal.isEmpty()) {
          segments.add(new ReplacementSegment.Literal(literal.toString()));
          literal.setLength(0);
        }
        i++;
        if (i >= replacement.length()) {
          throw new IllegalArgumentException("Trailing dollar sign in replacement string");
        }
        if (replacement.charAt(i) == '{') {
          // Named group reference: ${name}
          i++;
          int nameStart = i;
          while (i < replacement.length() && replacement.charAt(i) != '}') {
            i++;
          }
          if (i >= replacement.length()) {
            throw new IllegalArgumentException("Missing closing '}' in replacement string");
          }
          segments.add(new ReplacementSegment.NamedGroupRef(
              replacement.substring(nameStart, i)));
          i++; // skip '}'
        } else if (Character.isDigit(replacement.charAt(i))) {
          // Numeric group reference: $0, $1, $12, etc.
          NumericGroupReference groupRef =
              parseNumericGroupReference(replacement, i, maxGroup);
          segments.add(new ReplacementSegment.GroupRef(groupRef.groupNum()));
          i = groupRef.end();
        } else {
          throw new IllegalArgumentException(
              "Invalid group reference in replacement string");
        }
      } else {
        literal.append(c);
        i++;
      }
    }
    // Flush any trailing literal.
    if (!literal.isEmpty()) {
      segments.add(new ReplacementSegment.Literal(literal.toString()));
    }
    return segments.toArray(new ReplacementSegment[0]);
  }

  private static NumericGroupReference parseNumericGroupReference(
      String replacement, int digitStart, int maxGroup) {
    int groupNum = replacement.charAt(digitStart) - '0';
    int i = digitStart + 1;
    while (i < replacement.length() && Character.isDigit(replacement.charAt(i))) {
      int nextGroupNum = groupNum * 10 + (replacement.charAt(i) - '0');
      if (nextGroupNum > maxGroup) {
        break;
      }
      groupNum = nextGroupNum;
      i++;
    }
    return new NumericGroupReference(groupNum, i);
  }

  /**
   * Applies a compiled replacement template to the current match, appending the result to
   * {@code sb}. Uses {@code sb.append(text, start, end)} for group values to avoid substring
   * allocation.
   *
   * <p>Captures must already be resolved before calling this method.
   */
  private void applyReplacementTemplate(StringBuilder sb, ReplacementSegment[] template) {
    resolveCaptures();
    for (ReplacementSegment seg : template) {
      switch (seg) {
        case ReplacementSegment.Literal(var t) -> sb.append(t);
        case ReplacementSegment.GroupRef(var g) -> {
          checkGroup(g);
          int start = groups[2 * g];
          int end = groups[2 * g + 1];
          if (start >= 0 && end >= 0) {
            sb.append(text, start, end);
          }
        }
        case ReplacementSegment.NamedGroupRef(var name) -> {
          String g = group(name);
          if (g != null) {
            sb.append(g);
          }
        }
      }
    }
  }

  /**
   * Attempts to match the entire input sequence against the pattern.
   *
   * @return {@code true} if the entire input sequence matches this matcher's pattern
   */
  public boolean matches() {
    modCount++;
    searchFrom = regionStart;

    // --- Region setup ---
    boolean regionActive = (regionStart != 0 || regionEnd != text.length());
    String savedText = text;
    boolean regionSubstituted = false;

    try {
      if (regionActive && !anchoringBounds && regionTextAnchorCannotMatch()) {
        return applyEngineResult(new NoMatchResult());
      }
      if (regionActive && transparentBounds) {
        return matchesTransparentRegion();
      }
      if (regionActive) {
        text = savedText.substring(regionStart, regionEnd);
        regionSubstituted = true;
      }
      return matchesCore();
    } finally {
      if (regionSubstituted) {
        text = savedText;
        if (groups != null) {
          for (int i = 0; i < groups.length; i++) {
            if (groups[i] >= 0) {
              groups[i] += regionStart;
            }
          }
        }
        if (hasMatch && !capturesResolved) {
          deferredMatchStart += regionStart;
          deferredMatchEnd += regionStart;
        }
      }
      updateEndState(MatchOperation.MATCHES);
    }
  }

  /** Core matches logic, operates on the (possibly substituted) {@code text} field. */
  private boolean matchesCore() {
    capturesResolved = true;
    groupZeroResolved = true;

    // Literal fast path: for fully literal patterns with no user capture groups.
    String literal = parentPattern.literalMatch();
    if (enginePathOptions().literalFastPaths()
        && literal != null
        && parentPattern.numGroups() == 0) {
      boolean matched;
      if (parentPattern.prefixFoldCase()) {
        matched = text.length() == literal.length()
            && text.regionMatches(true, 0, literal, 0, literal.length());
      } else {
        matched = text.equals(literal);
      }
      if (matched) {
        applyEngineResult(new FullMatchResult(new int[]{0, text.length()}));
      } else {
        applyEngineResult(new NoMatchResult());
      }
      return hasMatch;
    }

    // Character-class fast path: for patterns like [a-zA-Z]+, \d+, \w*, etc.
    int[] ccRanges = parentPattern.charClassMatchRanges();
    if (enginePathOptions().charClassMatchFastPaths() && ccRanges != null) {
      return charClassMatchFastPath(ccRanges);
    }

    Prog prog = parentPattern.prog();

    // Fast path: try one-pass engine (anchored, with captures, O(n) time).
    EnginePathOptions options = enginePathOptions();
    OnePass onePass = options.onePass() ? parentPattern.onePass() : null;
    if (onePass != null
        && canUsePikeEquivalentCaptures(prog)) {
      return applyEngineResult(new FullMatchResult(onePass.search(text, true, prog.numCaptures())));
    }

    // Medium path: use DFA to check if a full match exists.
    if (enginePathOptions().dfa()) {
      Dfa.SearchResult dfaResult = dfa().doSearch(text, true, true);
      if (dfaResult != null && !dfaResult.matched()) {
        return applyEngineResult(new NoMatchResult());
      }
      if (dfaResult != null && dfaResult.pos() != text.length()) {
        return applyEngineResult(new NoMatchResult());
      }
      if (dfaResult != null && prog.numLoopRegs() == 0) {
        return applyEngineResult(
            new DeferredMatchResult(0, text.length(), prog.numCaptures(), true, true));
      }
    }

    // Slow path: try BitState (faster than NFA for small texts), then NFA.
    int[] result = searchWithBitStateOrNfa(
        prog, text, 0, text.length(), text.length(), true, false, true, prog.numCaptures());
    // matches() requires the entire text to be consumed. With dollarAnchorEnd, the BitState
    // may accept a match ending before a trailing \n. In that case, fall back to the NFA
    // which uses longest-match mode for FULL_MATCH and finds the correct full-text match.
    if (result != null && result[1] != text.length()) {
      result = Nfa.search(
          prog, text, 0, text.length(),
          Nfa.Anchor.ANCHORED, Nfa.MatchKind.FULL_MATCH, prog.numCaptures());
    }
    return applyEngineResult(new FullMatchResult(result));
  }

  /**
   * Attempts to match the input sequence, starting at the beginning, against the pattern.
   *
   * <p>Like {@link #matches()}, this method always starts at the beginning of the input; unlike
   * that method, it does not require that the entire input sequence be matched.
   *
   * @return {@code true} if a prefix of the input sequence matches this matcher's pattern
   */
  public boolean lookingAt() {
    modCount++;
    searchFrom = regionStart;

    // --- Region setup ---
    boolean regionActive = (regionStart != 0 || regionEnd != text.length());
    String savedText = text;
    boolean regionSubstituted = false;

    try {
      if (regionActive && !anchoringBounds && regionTextAnchorCannotMatch()) {
        return applyEngineResult(new NoMatchResult());
      }
      if (regionActive && transparentBounds) {
        return lookingAtTransparentRegion();
      }
      if (regionActive) {
        text = savedText.substring(regionStart, regionEnd);
        regionSubstituted = true;
      }
      return lookingAtCore();
    } finally {
      if (regionSubstituted) {
        text = savedText;
        if (groups != null) {
          for (int i = 0; i < groups.length; i++) {
            if (groups[i] >= 0) {
              groups[i] += regionStart;
            }
          }
        }
        if (hasMatch && !capturesResolved) {
          deferredMatchStart += regionStart;
          deferredMatchEnd += regionStart;
        }
      }
      updateEndState(MatchOperation.LOOKING_AT);
    }
  }

  /** Core lookingAt logic, operates on the (possibly substituted) {@code text} field. */
  private boolean lookingAtCore() {
    capturesResolved = true;
    groupZeroResolved = true;

    // Literal fast path: for fully literal patterns with no user capture groups.
    String literal = parentPattern.literalMatch();
    if (enginePathOptions().literalFastPaths()
        && literal != null
        && parentPattern.numGroups() == 0) {
      boolean matched;
      if (parentPattern.prefixFoldCase()) {
        matched = text.length() >= literal.length()
            && text.regionMatches(true, 0, literal, 0, literal.length());
      } else {
        matched = text.startsWith(literal);
      }
      if (matched) {
        applyEngineResult(new FullMatchResult(new int[]{0, literal.length()}));
      } else {
        applyEngineResult(new NoMatchResult());
      }
      return hasMatch;
    }

    Prog prog = parentPattern.prog();

    // Fast path: try one-pass engine (anchored, with captures, O(n) time).
    if (enginePathOptions().onePass()
        && parentPattern.canOnePassPrimary()
        && canUsePikeEquivalentCaptures(prog)) {
      OnePass onePass = parentPattern.onePass();
      return applyEngineResult(
          new FullMatchResult(onePass.search(text, false, prog.numCaptures())));
    }

    // Medium path: use DFA to check if an anchored match exists.
    if (enginePathOptions().dfa()) {
      Dfa.SearchResult dfaResult = dfa().doSearch(text, true, false);
      if (dfaResult != null && !dfaResult.matched()) {
        return applyEngineResult(new NoMatchResult());
      }
    }

    // Slow path: try BitState (faster than NFA for small texts), then NFA.
    return applyEngineResult(
        new FullMatchResult(searchWithBitStateOrNfa(
            prog, text, 0, text.length(), text.length(), true, false, false,
            prog.numCaptures())));
  }

  /**
   * Attempts to find the next subsequence of the input sequence that matches the pattern.
   *
   * <p>This method starts at the beginning of the input on the first invocation, or at the
   * character after the end of the previous match on subsequent invocations. Empty matches cause
   * the search to advance by one character to avoid infinite loops.
   *
   * @return {@code true} if a subsequence of the input sequence matches this matcher's pattern
   */
  public boolean find() {
    modCount++;
    if (hasMatch) {
      // Only resolve deferred captures before advancing when group 0 itself is not authoritative.
      // If group 0 is already exact, find-all loops that never read inner captures should not pay
      // the capture-extraction cost for the previous match.
      if (!groupZeroResolved) {
        resolveCaptures();
      }
      searchFrom = groups[1];
      if (groups[0] == groups[1]) { // empty match
        if (searchFrom >= regionEnd) {
          applyEngineResult(new NoMatchResult());
          updateEndState(MatchOperation.FIND);
          return false;
        }
        searchFrom++;
      }
    }
    return doFind();
  }

  /**
   * Resets this matcher and then attempts to find the next subsequence of the input that matches
   * the pattern, starting at the specified index.
   *
   * @param start the index at which to start the search
   * @return {@code true} if a subsequence of the input starting at the given index matches this
   *     matcher's pattern
   * @throws IndexOutOfBoundsException if start is negative or greater than the length of the
   *     input
   */
  public boolean find(int start) {
    if (start < 0 || start > text.length()) {
      throw new IndexOutOfBoundsException(
          "start=" + start + ", length=" + text.length());
    }
    modCount++;
    reset();
    searchFrom = start;
    return doFind();
  }

  /**
   * Returns a stream of match results for each subsequence of the input sequence that matches the
   * pattern. The match results occur in the same order as the matching subsequences in the input.
   *
   * <p>Each match result is produced as if by {@link #toMatchResult()}.
   *
   * <p>This method does not reset this matcher. Matching starts on a call to
   * {@link Stream#findFirst()} or similar terminal operation, and continues from the current
   * position.
   *
   * @return a sequential stream of match results
   */
  public Stream<MatchResult> results() {
    Spliterator<MatchResult> spliterator =
        new Spliterators.AbstractSpliterator<>(Long.MAX_VALUE,
            Spliterator.ORDERED | Spliterator.NONNULL) {
          @Override
          public boolean tryAdvance(java.util.function.Consumer<? super MatchResult> action) {
            if (!find()) {
              return false;
            }
            int expectedModCount = modCount;
            action.accept(toMatchResult());
            checkConcurrentModification(expectedModCount);
            return true;
          }
        };
    return StreamSupport.stream(spliterator, false);
  }

  /** Runs the engine search from {@link #searchFrom} and stores the result. */
  private boolean doFind() {
    // --- Region setup: temporarily substitute text with the region substring ---
    boolean regionActive = (regionStart != 0 || regionEnd != text.length());
    String savedText = text;
    int savedSearchFrom = searchFrom;
    boolean regionSubstituted = false;

    try {
      if (regionActive && !anchoringBounds && regionTextAnchorCannotMatch()) {
        return applyEngineResult(new NoMatchResult());
      }
      if (regionActive && transparentBounds) {
        return doFindTransparentRegion();
      }
      if (regionActive) {
        text = savedText.substring(regionStart, regionEnd);
        searchFrom = Math.max(0, savedSearchFrom - regionStart);
        regionSubstituted = true;
      }
      return doFindCore(regionActive);
    } finally {
      if (regionSubstituted) {
        text = savedText;
        searchFrom = savedSearchFrom;
        if (groups != null) {
          for (int i = 0; i < groups.length; i++) {
            if (groups[i] >= 0) {
              groups[i] += regionStart;
            }
          }
        }
        if (hasMatch && !capturesResolved) {
          deferredMatchStart += regionStart;
          deferredMatchEnd += regionStart;
        }
      }
      updateEndState(MatchOperation.FIND);
    }
  }

  /**
   * Returns true when a stripped text anchor ({@code ^}, {@code \A}, {@code $}, {@code \Z}, or
   * {@code \z}) cannot be satisfied at this region boundary because anchoring bounds are disabled.
   */
  private boolean regionTextAnchorCannotMatch() {
    Prog prog = parentPattern.prog();
    if (prog.anchorStart() && regionStart != 0) {
      return true;
    }
    return prog.anchorEnd() && !regionEndCanSatisfyTextEnd(prog);
  }

  private boolean regionEndCanSatisfyTextEnd(Prog prog) {
    if (regionEnd == text.length()) {
      return true;
    }
    return prog.dollarAnchorEnd()
        && Nfa.isAtTrailingLineTerminator(text, regionEnd, prog.unixLines());
  }

  private boolean matchesTransparentRegion() {
    capturesResolved = true;
    groupZeroResolved = true;
    Prog prog = parentPattern.prog();
    return applyEngineResult(
        new FullMatchResult(searchWithBitStateOrNfa(
            prog, text, regionStart, regionStart, regionEnd,
            true, false, true, prog.numCaptures())));
  }

  private boolean lookingAtTransparentRegion() {
    capturesResolved = true;
    groupZeroResolved = true;
    Prog prog = parentPattern.prog();
    return applyEngineResult(
        new FullMatchResult(searchWithBitStateOrNfa(
            prog, text, regionStart, regionStart, regionEnd,
            true, false, false, prog.numCaptures())));
  }

  private boolean doFindTransparentRegion() {
    if (searchFrom > regionEnd) {
      return applyEngineResult(new NoMatchResult());
    }
    capturesResolved = true;
    groupZeroResolved = true;
    Prog prog = parentPattern.prog();
    return applyEngineResult(
        new FullMatchResult(searchWithBitStateOrNfa(
            prog, text, searchFrom, regionEnd, regionEnd,
            false, false, false, prog.numCaptures())));
  }

  /**
   * Core find logic. When {@code regionActive} is true, the DFA sandwich with deferred captures
   * is disabled because resolveCaptures() would run on the full text with different empty-width
   * assertion semantics than the substring the DFA saw.
   */
  private boolean doFindCore(boolean regionActive) {
    if (searchFrom > text.length()) {
      return applyEngineResult(new NoMatchResult());
    }

    // Reset deferred-capture state; DFA sandwich path may set it to false.
    capturesResolved = true;
    groupZeroResolved = true;

    // Literal fast path: for fully literal patterns with no user capture groups,
    // use String.indexOf() directly.
    String literal = parentPattern.literalMatch();
    EnginePathOptions options = enginePathOptions();
    if (options.literalFastPaths() && literal != null && parentPattern.numGroups() == 0) {
      int idx;
      if (parentPattern.prefixFoldCase()) {
        idx = indexOfIgnoreCase(text, literal, searchFrom);
      } else {
        idx = text.indexOf(literal, searchFrom);
      }
      if (idx < 0) {
        return applyEngineResult(new NoMatchResult());
      }
      return applyEngineResult(new FullMatchResult(new int[]{idx, idx + literal.length()}));
    }

    Prog prog = parentPattern.prog();

    Pattern.KeywordAlternation keywordAlternation = parentPattern.keywordAlternation();
    if (options.keywordAlternationFastPath() && !regionActive && keywordAlternation != null) {
      return findKeywordAlternation(keywordAlternation, searchFrom, prog.numCaptures());
    }

    // Anchored start: if the pattern requires a match at the beginning of the text (e.g., ^
    // without MULTILINE, or \A), there can be no match starting after position 0 (or regionStart
    // when a region is active). Return false immediately to avoid the DFA matching at every
    // position because the compiler strips the anchor into prog.anchorStart().
    if (prog.anchorStart() && searchFrom > 0) {
      return applyEngineResult(new NoMatchResult());
    }

    // Anchored OnePass fast path: for anchored OnePass-eligible patterns on small text, use
    // OnePass directly. OnePass is a single O(n) pass that finds both match bounds and captures,
    // avoiding the entire DFA construction and sandwich overhead. Works for any searchFrom
    // position — OnePass quickly returns null if ^ or \A constraints fail at a non-zero position.
    //
    // This path handles patterns with non-nullable alternation (e.g., ^(?:GET|POST) +([^ ]+)
    // HTTP). When all alternation branches must consume at least one character, OnePass's
    // longest-match semantics are equivalent to first-match. This matches C++ RE2's behavior
    // (re2.cc line 838): skip the DFA for small anchored text when OnePass is available.
    //
    // Skip for patterns with nullable alternation (a branch that can match zero characters):
    // OnePass's longest-match semantics prefer the consuming branch over the zero-width branch,
    // violating first-match alternation priority.
    //
    // The text size threshold (4096) matches C++ RE2. For larger texts, the DFA is more efficient.
    if (options.onePass()
        && prog.anchorStart()
        && (parentPattern.canOnePassPrimary()
            || (!options.semanticGuards() && parentPattern.onePass() != null))
        && (!options.semanticGuards() || !parentPattern.hasNullableAlternation())
        && canUsePikeEquivalentCaptures(prog)
        && text.length() <= ONEPASS_ANCHORED_TEXT_LIMIT) {
      return applyEngineResult(
          new FullMatchResult(parentPattern.onePass().search(
              text, searchFrom, text.length(), false, prog.numCaptures())));
    }

    // Prefix acceleration: if the pattern starts with a literal prefix, skip ahead to where
    // that prefix first appears instead of searching from the current position.
    int effectiveStart = searchFrom;
    String prefix = parentPattern.prefix();
    if (options.startAcceleration() && prefix != null) {
      int idx;
      if (parentPattern.prefixFoldCase()) {
        idx = indexOfIgnoreCase(text, prefix, searchFrom);
      } else {
        idx = text.indexOf(prefix, searchFrom);
      }
      if (idx < 0) {
        return applyEngineResult(new NoMatchResult());
      }
      effectiveStart = idx;
    }

    // Character-class prefix acceleration: when the pattern starts with a character class (and
    // no literal prefix exists), scan for the first character that could begin a match. This
    // avoids running the full engine on text regions where no match can start.
    boolean[] ccPrefixAscii = parentPattern.charClassPrefixAscii();
    if (options.startAcceleration() && ccPrefixAscii != null) {
      int idx = indexOfCharClass(text, ccPrefixAscii, searchFrom);
      if (idx < 0) {
        return applyEngineResult(new NoMatchResult());
      }
      effectiveStart = idx;
    }

    Pattern.StartAcceleration startAcceleration = parentPattern.startAcceleration();
    if (options.startAcceleration() && startAcceleration != null) {
      int idx = nextAcceleratedStart(text, startAcceleration, effectiveStart, prog.unixLines());
      if (idx < 0) {
        return applyEngineResult(new NoMatchResult());
      }
      effectiveStart = idx;
    }

    // OnePass primary path for small texts: for OnePass-eligible unanchored patterns on short
    // input, scan with OnePass directly. OnePass is faster than BitState — no visited bitmap
    // or job stack allocation, deterministic single-pass traversal per start position.
    // Skip for nullable alternation (see anchored path comment above).
    if (options.onePass()
        && (parentPattern.canOnePassPrimary()
            || (!options.semanticGuards() && parentPattern.onePass() != null))
        && text.length() <= 256
        && canUsePikeEquivalentCaptures(prog)
        && (!options.semanticGuards() || !parentPattern.hasNullableAlternation())) {
      int[] result = parentPattern.onePass().searchUnanchored(
          text, effectiveStart, text.length(), prog.numCaptures());
      if (result != null) {
        return applyEngineResult(new FullMatchResult(result));
      }
      return applyEngineResult(new NoMatchResult());
    }


    // Reverse-first optimization for end-anchored patterns: for patterns ending with $ or \z
    // that are NOT anchored at the start, run the reverse DFA from the end of the text first.
    // If the reverse DFA determines no match is possible at the end, we skip the O(n) forward
    // scan entirely. This makes end-anchored failing searches O(k) where k depends on the
    // pattern suffix length, matching C++ RE2's reverse DFA optimization.
    //
    // Only applied when text exceeds MIN_REVERSE_FIRST_LEN — for short texts, the forward DFA
    // is trivially fast and the cost of lazily compiling the reverse program and building its
    // DFA setup outweighs any scanning savings.
    //
    // A null result from the reverse DFA means the DFA budget was exceeded — in that case we
    // must fall through to the normal forward DFA path rather than returning false.
    if (options.dfa()
        && options.reverseDfa()
        && !regionActive
        && prog.anchorEnd()
        && !prog.anchorStart()
        && text.length() >= MIN_REVERSE_FIRST_LEN
        && (!options.semanticGuards() || parentPattern.dfaStartReliable())) {
      Dfa revDfa = reverseDfa();
      if (revDfa != null) {
        int textLen = text.length();
        boolean budgetExceeded = false;

        // Try reverse DFA from end of text (anchored at end position).
        Dfa.SearchResult revResult =
            revDfa.doSearchReverse(text, textLen, effectiveStart, true, true);
        int matchStart;
        if (revResult == null) {
          budgetExceeded = true;
          matchStart = -1;
        } else {
          matchStart = revResult.matched() ? revResult.pos() : -1;
        }

        // For $ (dollarAnchorEnd), also try before trailing line terminator. The $ anchor
        // can match before a trailing \n, \r\n, or other line terminator. The leftmost match
        // start may correspond to a match ending before the trailing terminator rather than
        // at textLen.
        if (!budgetExceeded && prog.dollarAnchorEnd()) {
          boolean ul = prog.unixLines();
          if (textLen > 0 && (ul ? text.charAt(textLen - 1) == '\n'
              : Nfa.isLineTerminator(text.charAt(textLen - 1)))) {
            // For \r\n, the trailing terminator starts at textLen-2 (before \r), not
            // textLen-1 (between \r and \n). Skip the textLen-1 check for \r\n.
            boolean isAtomicCrLf = !ul && textLen >= 2
                && text.charAt(textLen - 2) == '\r' && text.charAt(textLen - 1) == '\n';
            if (!isAtomicCrLf) {
              Dfa.SearchResult altRev =
                  revDfa.doSearchReverse(text, textLen - 1, effectiveStart, true, true);
              if (altRev == null) {
                budgetExceeded = true;
              } else if (altRev.matched()
                  && (matchStart < 0 || altRev.pos() < matchStart)) {
                matchStart = altRev.pos();
              }
            }
            // For \r\n, try position before \r.
            if (!budgetExceeded && isAtomicCrLf) {
              Dfa.SearchResult altRev2 =
                  revDfa.doSearchReverse(text, textLen - 2, effectiveStart, true, true);
              if (altRev2 == null) {
                budgetExceeded = true;
              } else if (altRev2.matched()
                  && (matchStart < 0 || altRev2.pos() < matchStart)) {
                matchStart = altRev2.pos();
              }
            }
          }
        }

        if (!budgetExceeded) {
          if (matchStart < 0) {
            // No match possible at end of text — fail immediately without forward scan.
            return applyEngineResult(new NoMatchResult());
          }

          // Reverse DFA found a match start. Run forward DFA from there (anchored, longest)
          // to find the actual match end.
          Dfa.SearchResult fwdAnchored = dfa().doSearch(text, matchStart, true, true);
          if (fwdAnchored != null && fwdAnchored.matched()) {
            int matchEnd = fwdAnchored.pos();
            return applyEngineResult(
                new DeferredMatchResult(
                    matchStart,
                    matchEnd,
                    prog.numCaptures(),
                    !options.semanticGuards() || parentPattern.dfaGroupZeroReliable(),
                    false));
          }
        }
        // DFA budget exceeded or forward DFA disagreed — fall through to normal path.
      }
    }

    boolean directFallback =
        !regionActive
            && !parentPattern.dfaStartReliable()
            && options.startAcceleration()
            && (prefix != null || ccPrefixAscii != null)
            && text.length() <= DIRECT_FALLBACK_TEXT_LIMIT
            && BitState.maxTextSize(prog) >= text.length();

    // Fast path: use cached DFA to check if a match exists in the remaining text.
    // Use longest=false for a quick existence check — this returns the earliest match end.
    // For short unreliable-start patterns that fit BitState, skip this precheck: a successful
    // precheck cannot produce reusable bounds and would fall through to the complete fallback
    // search anyway.
    Dfa.SearchResult fwdResult;
    if (!options.dfa() || directFallback) {
      fwdResult = null;
    } else {
      fwdResult = dfa().doSearch(text, effectiveStart, false, false);
      if (fwdResult != null && !fwdResult.matched()) {
        return applyEngineResult(new NoMatchResult());
      }
    }

    // Three-DFA sandwich (like RE2): forward DFA found earliest match end above. Now use the
    // reverse DFA to find match start, then a second forward DFA pass (anchored, longest) to find
    // the actual match end. With lazy capture extraction, the sandwich returns group(0) without
    // running BitState/NFA, making it worthwhile even on the first find() call.
    //
    // The reverse DFA is lazily constructed on first use and cached for subsequent calls.
    //
    // Skip when the forward DFA detected an empty match (earlyEnd == effectiveStart): the
    // sandwich uses longest-match for the final forward pass, which would incorrectly expand an
    // empty match into a longer one for nullable patterns like (|a)*.
    //
    // Skip the reverse DFA phase when the pattern is anchored at the start — the match start is
    // already known to be effectiveStart, so the reverse scan is unnecessary.
    //
    // Skip entirely when the DFA's match start is unreliable (patterns with lazy quantifiers,
    // anchors inside quantifiers, or alternation). Lazy quantifiers can make a non-leftmost match
    // end earlier, causing the DFA to find the wrong start. Alternation can have the same effect:
    // when alternatives match at different start positions with different endpoints, the forward
    // DFA's earliest-end result may come from a non-leftmost match, and the reverse DFA from that
    // endpoint cannot find the leftmost start. In those cases, fall through to the BitState/NFA
    // fallback which correctly handles all semantics.
    //
    // For patterns where the DFA start IS reliable but the end may be wrong (bounded repeats),
    // the sandwich still narrows the range — capturesResolved is set to false so
    // resolveCaptures() corrects the end position using the submatch engine.
    // Skip when a region is active — deferred capture resolution runs on the full text but the
    // DFA ran on the region substring, causing empty-width assertion mismatches at boundaries.
    if (options.dfa()
        && !regionActive
        && fwdResult != null
        && fwdResult.pos() > effectiveStart
        && (!options.semanticGuards() || parentPattern.dfaStartReliable())) {
      int earlyEnd = fwdResult.pos();

      if (prog.anchorStart()) {
        // Anchored: match start is effectiveStart. Run forward DFA (anchored, longest) to find
        // actual end, then defer inner captures until requested.
        Dfa.SearchResult fwdLongest = dfa().doSearch(text, effectiveStart, true, true);
        if (fwdLongest != null && fwdLongest.matched()) {
          int matchEnd = fwdLongest.pos();
          return applyEngineResult(
              new DeferredMatchResult(
                  effectiveStart,
                  matchEnd,
                  prog.numCaptures(),
                  !options.semanticGuards() || parentPattern.dfaGroupZeroReliable(),
                  false));
        }
      } else {
        Dfa revDfa = reverseDfa();
        if (revDfa != null) {
          // Step 2: Reverse DFA backward from earliest match end to find match start.
          Dfa.SearchResult revResult =
              revDfa.doSearchReverse(text, earlyEnd, effectiveStart, true, true);
          if (revResult != null && revResult.matched()) {
            int matchStart = revResult.pos();

            // For dollarAnchorEnd patterns, the forward DFA's earlyEnd is always textLen
            // (it can't return early). But the correct leftmost match may end before the
            // trailing line terminator. The reverse DFA from textLen only finds starts for
            // matches ending AT textLen, potentially missing an earlier-starting match that
            // ends before the trailing line terminator. Check all dollar positions.
            if (prog.dollarAnchorEnd() && earlyEnd == text.length()) {
              int len = text.length();
              boolean ul = prog.unixLines();
              // Try position before trailing line terminator.
              if (len > 0 && (ul ? text.charAt(len - 1) == '\n'
                  : Nfa.isLineTerminator(text.charAt(len - 1)))) {
                // For \r\n, the trailing terminator starts at len-2 (before \r), not
                // len-1 (between \r and \n). Skip the earlyEnd-1 check for \r\n.
                boolean isAtomicCrLf = !ul && len >= 2
                    && text.charAt(len - 2) == '\r' && text.charAt(len - 1) == '\n';
                if (!isAtomicCrLf) {
                  Dfa.SearchResult altRevResult =
                      revDfa.doSearchReverse(
                          text, earlyEnd - 1, effectiveStart, true, true);
                  if (altRevResult != null && altRevResult.matched()
                      && altRevResult.pos() < matchStart) {
                    matchStart = altRevResult.pos();
                  }
                }
                // For \r\n, try position before \r.
                if (isAtomicCrLf && earlyEnd - 2 >= effectiveStart) {
                  Dfa.SearchResult altRevResult2 =
                      revDfa.doSearchReverse(
                          text, earlyEnd - 2, effectiveStart, true, true);
                  if (altRevResult2 != null && altRevResult2.matched()
                      && altRevResult2.pos() < matchStart) {
                    matchStart = altRevResult2.pos();
                  }
                }
              }
            }

            // Step 3: Forward DFA anchored at matchStart with longest=true to find actual end.
            Dfa.SearchResult fwdLongest = dfa().doSearch(text, matchStart, true, true);
            if (fwdLongest != null && fwdLongest.matched()) {
              int matchEnd = fwdLongest.pos();
              // Step 4: Store group(0) boundaries, defer inner captures until requested.
              return applyEngineResult(
                  new DeferredMatchResult(
                      matchStart,
                      matchEnd,
                      prog.numCaptures(),
                      !options.semanticGuards() || parentPattern.dfaGroupZeroReliable(),
                      false));
            }
            // If anchored forward DFA fails, fall through to full search.
          }
          // If reverse DFA bails out, fall through to full search.
        }
      }
    }

    // Fallback: DFA bailed out or reverse DFA unavailable. Search only for group 0 first, then
    // defer inner capture extraction until group access. This keeps find-all loops that only need
    // match existence or group 0 from paying full capture-tracking cost on every match.
    //
    // Region searches keep eager capture extraction: deferred resolution runs on the full input,
    // while the fallback below sees the substituted region substring.
    boolean lazyFallbackCaptures =
        !regionActive
            && !eagerFallbackCaptures
            && options.lazyCaptureExtraction()
            && prog.numCaptures() <= MAX_LAZY_FALLBACK_SUBMATCHES;
    int nsubmatch = lazyFallbackCaptures ? 1 : prog.numCaptures();
    int[] result = searchWithBitStateOrNfa(
        prog, text, effectiveStart, text.length(), text.length(), false, false, false, nsubmatch);
    if (result == null) {
      return applyEngineResult(new NoMatchResult());
    }
    if (!lazyFallbackCaptures || prog.numCaptures() <= 1) {
      return applyEngineResult(new FullMatchResult(result));
    } else {
      return applyEngineResult(
          new DeferredMatchResult(result[0], result[1], prog.numCaptures(), true, false));
    }
  }

  private boolean findKeywordAlternation(
      Pattern.KeywordAlternation keywordAlternation, int startPos, int ncap) {
    for (int i = Math.max(0, startPos); i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch < 128 && keywordAlternation.firstAscii[asciiLower(ch)]
          && isWordBoundaryAt(i, keywordAlternation.unicodeWordBoundary)) {
        for (String keyword : keywordAlternation.keywords) {
          int end = i + keyword.length();
          if (end <= text.length()
              && text.regionMatches(true, i, keyword, 0, keyword.length())
              && isWordBoundaryAt(end, keywordAlternation.unicodeWordBoundary)) {
            int[] keywordGroups = new int[2 * ncap];
            Arrays.fill(keywordGroups, -1);
            keywordGroups[0] = i;
            keywordGroups[1] = end;
            if (keywordAlternation.captureGroup > 0) {
              int group = keywordAlternation.captureGroup;
              keywordGroups[2 * group] = i;
              keywordGroups[2 * group + 1] = end;
            }
            return applyEngineResult(new FullMatchResult(keywordGroups));
          }
        }
      }
      int cp = text.codePointAt(i);
      i += Character.charCount(cp) - 1;
    }
    return applyEngineResult(new NoMatchResult());
  }

  private boolean isWordBoundaryAt(int pos, boolean unicodeWordBoundary) {
    boolean prevWord =
        pos > 0 && isBoundaryWordChar(text.codePointBefore(pos), unicodeWordBoundary);
    boolean nextWord =
        pos < text.length() && isBoundaryWordChar(text.codePointAt(pos), unicodeWordBoundary);
    return prevWord != nextWord;
  }

  private static boolean isBoundaryWordChar(int cp, boolean unicodeWordBoundary) {
    return unicodeWordBoundary ? Nfa.isUnicodeWordChar(cp) : Nfa.isWordChar(cp);
  }

  private static int asciiLower(int ch) {
    return ('A' <= ch && ch <= 'Z') ? ch + ('a' - 'A') : ch;
  }

  /** Case-insensitive indexOf using Unicode case folding. */
  private static int indexOfIgnoreCase(String text, String prefix, int fromIndex) {
    int prefixLen = prefix.length();
    int limit = text.length() - prefixLen;
    for (int i = fromIndex; i <= limit; i++) {
      if (text.regionMatches(true, i, prefix, 0, prefixLen)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Scans {@code text} for the first character at or after {@code fromIndex} whose code point is
   * set in the ASCII bitmap. Returns the index, or {@code -1} if no matching character is found.
   * Non-ASCII characters are skipped (never match).
   */
  private static int indexOfCharClass(String text, boolean[] asciiMap, int fromIndex) {
    for (int i = fromIndex; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch < 128 && asciiMap[ch]) {
        return i;
      }
    }
    return -1;
  }

  private static int nextAcceleratedStart(
      String text, Pattern.StartAcceleration acceleration, int fromIndex, boolean unixLines) {
    int start = Math.max(0, fromIndex);
    for (int i = start; i < text.length(); i++) {
      if (matchesStartAcceleration(text, i, acceleration, unixLines)) {
        return i;
      }
      int cp = text.codePointAt(i);
      i += Character.charCount(cp) - 1;
    }
    return -1;
  }

  private static boolean matchesStartAcceleration(
      String text, int pos, Pattern.StartAcceleration acceleration, boolean unixLines) {
    boolean lineStart = isBeginLine(text, pos, unixLines);
    boolean asciiStart = matchesAsciiStart(text, pos, acceleration.asciiStart);
    if (acceleration.requireLineStart) {
      return lineStart && (acceleration.asciiStart == null || asciiStart);
    }
    return (acceleration.allowLineStart && lineStart) || asciiStart;
  }

  private static boolean matchesAsciiStart(String text, int pos, boolean[] asciiStart) {
    if (asciiStart == null || pos >= text.length()) {
      return false;
    }
    char ch = text.charAt(pos);
    return ch < 128 && asciiStart[ch];
  }

  private static boolean isBeginLine(String text, int pos, boolean unixLines) {
    if (pos == 0) {
      return !text.isEmpty();
    }
    if (pos >= text.length()) {
      return false;
    }
    char prev = text.charAt(pos - 1);
    if (unixLines) {
      return prev == '\n';
    }
    return prev == '\n'
        || prev == '\u0085'
        || prev == '\u2028'
        || prev == '\u2029'
        || (prev == '\r' && text.charAt(pos) != '\n');
  }
  /**
   * Tries BitState first (for small texts), falls back to NFA. This is the final capture-extraction
   * step after DFA/OnePass have been tried or are not applicable.
   *
   * @param prog the compiled program
   * @param text the full input text
   * @param startPos the char index at which to begin searching
   * @param searchLimit upper bound on match end position; the capture engines only try start
   *     positions up to this index. Use {@code text.length()} for unbounded search.
   * @param endPos upper bound on character consumption; engines will not read past this position.
   *     Use {@code text.length()} for unbounded search.
   * @param anchored whether the search is anchored at {@code startPos}
   * @param longest whether to find the longest match
   * @param endMatch whether the match must extend to {@code endPos}
   * @param nsubmatch number of submatch groups to track (including group 0)
   * @return submatch positions relative to {@code text}, or null if no match
   */
  private int[] searchWithBitStateOrNfa(Prog prog, String text, int startPos,
      int searchLimit, int endPos, boolean anchored, boolean longest, boolean endMatch,
      int nsubmatch) {
    // Try BitState if the full text is small enough for the visited bitmap. BitState is an
    // optimization; if capture-priority backtracking exceeds its work budget, fall back to the
    // Pike NFA below.
    int maxBitStateLen = BitState.maxTextSize(prog);
    boolean canUseBitState = enginePathOptions().bitState()
        && (!enginePathOptions().semanticGuards()
            || !prog.requiresPikeNfaCaptureSemantics()
            || nsubmatch <= 1);
    if (canUseBitState && maxBitStateLen >= 0 && text.length() <= maxBitStateLen) {
      boolean anchoredEffective = anchored || prog.anchorStart();
      boolean endMatchEffective = endMatch || prog.anchorEnd();
      int ncap = 2 * Math.max(nsubmatch, 1);
      // Borrow from Pattern's thread-local cache on first use.
      if (cachedBitState == null && !bitStateBorrowed) {
        cachedBitState = parentPattern.borrowBitState();
        bitStateBorrowed = true;
      }
      BitState bs =
          BitState.getOrCreate(cachedBitState, prog, text, endPos, ncap, longest,
              endMatchEffective);
      if (bitStateResult == null || bitStateResult.length < ncap) {
        bitStateResult = new int[ncap];
      }
      int[] result = bs.doSearch(startPos, searchLimit, anchoredEffective, bitStateResult);
      cachedBitState = bs;
      // Return to Pattern's cache for reuse by future Matchers.
      parentPattern.returnBitState(bs);
      if (!bs.budgetExceeded()) {
        // BitState is a complete engine — if it searched and found no match, NFA won't either.
        return result;
      }
    }

    // Fall back to the general NFA when BitState cannot be used or when capture semantics need
    // Pike NFA's priority model.
    Nfa.Anchor nfaAnchor = anchored ? Nfa.Anchor.ANCHORED : Nfa.Anchor.UNANCHORED;
    Nfa.MatchKind nfaKind;
    if (endMatch) {
      nfaKind = Nfa.MatchKind.FULL_MATCH;
    } else if (longest) {
      nfaKind = Nfa.MatchKind.LONGEST_MATCH;
    } else {
      nfaKind = Nfa.MatchKind.FIRST_MATCH;
    }
    return Nfa.search(
        prog, text, startPos, searchLimit, endPos, nfaAnchor, nfaKind, nsubmatch);
  }

  // ---------------------------------------------------------------------------
  // Group access (MatchResult implementation)
  // ---------------------------------------------------------------------------

  /**
   * Returns the number of capturing groups in this matcher's pattern, not counting the implicit
   * group 0 for the full match.
   *
   * @return the number of capturing groups
   */
  @Override
  public int groupCount() {
    return parentPattern.numGroups();
  }

  /**
   * Returns the input subsequence matched by the previous match (equivalent to
   * {@code group(0)}).
   *
   * @return the matched subsequence
   * @throws IllegalStateException if no match has yet been attempted, or if the previous match
   *     operation failed
   */
  @Override
  public String group() {
    return group(0);
  }

  /**
   * Returns the input subsequence captured by the given group during the previous match
   * operation.
   *
   * @param group the index of a capturing group in this matcher's pattern
   * @return the subsequence captured by the group, or {@code null} if the group did not
   *     participate in the match
   * @throws IllegalStateException if no match has yet been attempted, or if the previous match
   *     operation failed
   * @throws IndexOutOfBoundsException if there is no capturing group with the given index
   */
  @Override
  public String group(int group) {
    int s = start(group);
    int e = end(group);
    if (s == -1) {
      return null;
    }
    return text.substring(s, e);
  }

  /**
   * Returns the input subsequence captured by the given named group during the previous match.
   *
   * @param name the name of a named-capturing group in this matcher's pattern
   * @return the subsequence captured by the named group, or {@code null} if the group did not
   *     participate in the match
   * @throws IllegalStateException if no match has yet been attempted, or if the previous match
   *     operation failed
   * @throws IllegalArgumentException if there is no capturing group with the given name
   */
  @Override
  public String group(String name) {
    Objects.requireNonNull(name, "Group name");
    Integer idx = parentPattern.namedGroups().get(name);
    if (idx == null) {
      throw new IllegalArgumentException(
          "No group with name <" + name + ">");
    }
    return group(idx);
  }

  /**
   * Returns the start index of the previous match (equivalent to {@code start(0)}).
   *
   * @return the index of the first character matched
   * @throws IllegalStateException if no match has yet been attempted, or if the previous match
   *     operation failed
   */
  @Override
  public int start() {
    return start(0);
  }

  /**
   * Returns the start index of the subsequence captured by the given group.
   *
   * @param group the index of a capturing group
   * @return the start index, or {@code -1} if the group did not participate in the match
   * @throws IllegalStateException if no match has yet been attempted, or if the previous match
   *     operation failed
   * @throws IndexOutOfBoundsException if there is no capturing group with the given index
   */
  @Override
  public int start(int group) {
    checkMatch();
    checkGroup(group);
    if (group != 0 || !groupZeroResolved) {
      if (group != 0) {
        eagerFallbackCaptures = true;
      }
      resolveCaptures();
    }
    return groups[2 * group];
  }

  /**
   * Returns the offset after the last character of the previous match (equivalent to
   * {@code end(0)}).
   *
   * @return the offset after the last character matched
   * @throws IllegalStateException if no match has yet been attempted, or if the previous match
   *     operation failed
   */
  @Override
  public int end() {
    return end(0);
  }

  /**
   * Returns the offset after the last character of the subsequence captured by the given group.
   *
   * @param group the index of a capturing group
   * @return the offset after the last character, or {@code -1} if the group did not participate
   * @throws IllegalStateException if no match has yet been attempted, or if the previous match
   *     operation failed
   * @throws IndexOutOfBoundsException if there is no capturing group with the given index
   */
  @Override
  public int end(int group) {
    checkMatch();
    checkGroup(group);
    if (group != 0 || !groupZeroResolved) {
      if (group != 0) {
        eagerFallbackCaptures = true;
      }
      resolveCaptures();
    }
    return groups[2 * group + 1];
  }

  /**
   * Returns the start index of the subsequence captured by the given named group.
   *
   * @param name the name of a named-capturing group in this matcher's pattern
   * @return the start index, or {@code -1} if the group did not participate in the match
   * @throws IllegalStateException if no match has yet been attempted, or if the previous match
   *     operation failed
   * @throws IllegalArgumentException if there is no capturing group with the given name
   */
  @Override
  public int start(String name) {
    Objects.requireNonNull(name, "Group name");
    Integer idx = parentPattern.namedGroups().get(name);
    if (idx == null) {
      throw new IllegalArgumentException("No group with name <" + name + ">");
    }
    return start(idx);
  }

  /**
   * Returns the offset after the last character of the subsequence captured by the given named
   * group.
   *
   * @param name the name of a named-capturing group in this matcher's pattern
   * @return the offset after the last character, or {@code -1} if the group did not participate
   * @throws IllegalStateException if no match has yet been attempted, or if the previous match
   *     operation failed
   * @throws IllegalArgumentException if there is no capturing group with the given name
   */
  @Override
  public int end(String name) {
    Objects.requireNonNull(name, "Group name");
    Integer idx = parentPattern.namedGroups().get(name);
    if (idx == null) {
      throw new IllegalArgumentException("No group with name <" + name + ">");
    }
    return end(idx);
  }

  // ---------------------------------------------------------------------------
  // Replacement methods
  // ---------------------------------------------------------------------------

  /**
   * Returns a literal replacement {@code String} for the specified {@code String}. This method
   * produces a {@code String} that will work as a literal replacement {@code s} in the {@code
   * appendReplacement} method of the {@link Matcher} class. The {@code String} produced will match
   * the sequence of characters in {@code s} treated as a literal sequence. Slashes ({@code '\'})
   * and dollar signs ({@code '$'}) will be given no special meaning.
   *
   * @param s the string to be literalized
   * @return a literal string replacement
   */
  public static String quoteReplacement(String s) {
    return java.util.regex.Matcher.quoteReplacement(s);
  }

  /**
   * Replaces the first subsequence of the input that matches the pattern with the given
   * replacement string.
   *
   * @param replacement the replacement string
   * @return the string with the first match replaced
   */
  public String replaceFirst(String replacement) {
    reset();
    StringBuilder sb = new StringBuilder();
    if (find()) {
      appendReplacement(sb, replacement);
    }
    appendTail(sb);
    return sb.toString();
  }

  /**
   * Replaces the first subsequence of the input that matches the pattern with the result of
   * applying the given replacer function to the match result. The replacer function is called with
   * the match result of the first match.
   *
   * @param replacer a function that produces a replacement string from a match result
   * @return the string with the first match replaced
   * @throws NullPointerException if the replacer function is null
   */
  public String replaceFirst(Function<MatchResult, String> replacer) {
    if (replacer == null) {
      throw new NullPointerException("replacer");
    }
    reset();
    StringBuilder sb = new StringBuilder();
    if (find()) {
      int expectedModCount = modCount;
      String replacement = Objects.requireNonNull(replacer.apply(toMatchResult()));
      checkConcurrentModification(expectedModCount);
      appendReplacement(sb, replacement);
    }
    appendTail(sb);
    return sb.toString();
  }

  /**
   * Replaces every subsequence of the input that matches the pattern with the given replacement
   * string.
   *
   * @param replacement the replacement string
   * @return the string with all matches replaced
   */
  public String replaceAll(String replacement) {
    // Pre-compile the replacement template once, avoiding per-match parseInt/substring overhead.
    ReplacementSegment[] template = compileReplacementTemplate(replacement, groupCount());

    reset();
    StringBuilder sb = new StringBuilder();
    while (find()) {
      resolveCaptures();
      sb.append(text, appendPos, groups[0]);
      applyReplacementTemplate(sb, template);
      appendPos = groups[1];
    }
    appendTail(sb);
    return sb.toString();
  }

  /**
   * Replaces every subsequence of the input that matches the pattern with the result of applying
   * the given replacer function to the match result. The replacer function is called for each
   * match, and the result is used as the replacement string.
   *
   * @param replacer a function that produces a replacement string from a match result
   * @return the string with all matches replaced
   * @throws NullPointerException if the replacer function is null
   */
  public String replaceAll(Function<MatchResult, String> replacer) {
    if (replacer == null) {
      throw new NullPointerException("replacer");
    }
    reset();
    StringBuilder sb = new StringBuilder();
    while (find()) {
      int expectedModCount = modCount;
      String replacement = Objects.requireNonNull(replacer.apply(toMatchResult()));
      checkConcurrentModification(expectedModCount);
      appendReplacement(sb, replacement);
    }
    appendTail(sb);
    return sb.toString();
  }

  /**
   * Implements a non-terminal append-and-replace step. Appends the text between the previous
   * append position and the current match, followed by the processed replacement string.
   *
   * <p>The replacement string may contain references to captured groups: {@code $0}, {@code $1},
   * etc. for numbered groups, and {@code ${name}} for named groups. Use {@code \\} for a literal
   * backslash and {@code \$} for a literal dollar sign.
   *
   * @param sb the target string builder
   * @param replacement the replacement string
   * @return this matcher
   * @throws IllegalStateException if no match has yet been attempted, or if the previous match
   *     operation failed
   */
  public Matcher appendReplacement(StringBuilder sb, String replacement) {
    modCount++;
    checkMatch();
    sb.append(text, appendPos, start());
    appendReplacementBody(sb, replacement);
    appendPos = end();
    return this;
  }

  /**
   * Implements a terminal append-and-replace step. Appends the remaining input text after the
   * last match to the string builder.
   *
   * @param sb the target string builder
   * @return the string builder
   */
  public StringBuilder appendTail(StringBuilder sb) {
    sb.append(text, appendPos, text.length());
    return sb;
  }

  /**
   * Implements a non-terminal append-and-replace step using the legacy {@link StringBuffer} class.
   * This method behaves identically to {@link #appendReplacement(StringBuilder, String)}.
   *
   * @param sb the target string buffer
   * @param replacement the replacement string
   * @return this matcher
   * @throws IllegalStateException if no match has yet been attempted, or if the previous match
   *     operation failed
   */
  public Matcher appendReplacement(StringBuffer sb, String replacement) {
    modCount++;
    checkMatch();
    // Build into a temporary StringBuilder, then transfer to the StringBuffer.
    StringBuilder tmp = new StringBuilder();
    tmp.append(text, appendPos, start());
    appendReplacementBody(tmp, replacement);
    sb.append(tmp);
    appendPos = end();
    return this;
  }

  /**
   * Implements a terminal append-and-replace step using the legacy {@link StringBuffer} class.
   * Appends the remaining input text after the last match to the string buffer.
   *
   * @param sb the target string buffer
   * @return the string buffer
   */
  public StringBuffer appendTail(StringBuffer sb) {
    sb.append(text, appendPos, text.length());
    return sb;
  }

  // ---------------------------------------------------------------------------
  // State management
  // ---------------------------------------------------------------------------

  /**
   * Resets this matcher, discarding all match information and setting the search position to the
   * beginning of the input.
   *
   * @return this matcher
   */
  public Matcher reset() {
    modCount++;
    resetStateForCurrentInput();
    return this;
  }

  /**
   * Resets this matcher with a new input sequence.
   *
   * @param input the new input character sequence
   * @return this matcher
   */
  public Matcher reset(CharSequence input) {
    this.inputSequence = input;
    invalidateInputDependentCaches();
    return reset();
  }

  /**
   * Sets the limits of this matcher's region. The region is the part of the input sequence that
   * will be searched to find a match. Invoking this method resets the matcher and sets the region
   * to start at the character specified by the {@code start} parameter and end at the character
   * specified by the {@code end} parameter.
   *
   * @param start the index to start searching at (inclusive)
   * @param end the index to end searching at (exclusive)
   * @return this matcher
   * @throws IndexOutOfBoundsException if start or end is less than zero, if end is greater than
   *     the length of the input sequence, or if start is greater than end
   */
  public Matcher region(int start, int end) {
    if (start < 0 || start > text.length()) {
      throw new IndexOutOfBoundsException(
          "start=" + start + ", length=" + text.length());
    }
    if (end < 0 || end > text.length()) {
      throw new IndexOutOfBoundsException(
          "end=" + end + ", length=" + text.length());
    }
    if (start > end) {
      throw new IndexOutOfBoundsException("start=" + start + " > end=" + end);
    }
    modCount++;
    resetStateForRegion(start, end);
    return this;
  }

  /**
   * Reports the start index of this matcher's region. Searches by this matcher are limited to
   * finding matches within {@link #regionStart()} (inclusive) and {@link #regionEnd()} (exclusive).
   *
   * @return the starting point of this matcher's region
   */
  public int regionStart() {
    return regionStart;
  }

  /**
   * Reports the end index (exclusive) of this matcher's region. Searches by this matcher are
   * limited to finding matches within {@link #regionStart()} (inclusive) and
   * {@link #regionEnd()} (exclusive).
   *
   * @return the ending point of this matcher's region
   */
  public int regionEnd() {
    return regionEnd;
  }

  /**
   * Returns true if the end of input was hit by the search engine in the last match operation
   * performed by this matcher. When this method returns true, it is possible that more input would
   * have changed the result of the last search.
   *
   * @return true if the end of input was hit in the last match; false otherwise
   */
  public boolean hitEnd() {
    return lastHitEnd;
  }

  /**
   * Returns true if more input could change a positive match into a negative one. If this method
   * returns true, and a match was found, then more input could cause the match to be lost. If this
   * method returns false and a match was found, then more input might change the match but the
   * match won't be lost. If a match was not found, then requireEnd has no meaning.
   *
   * <p>This is a conservative approximation: it returns {@code true} when the pattern contains
   * {@code $}, {@code \Z}, or word-boundary assertions ({@code \b}, {@code \B}) and the last
   * match reached the end of the input region. Like the JDK, {@code \z} does not trigger
   * {@code requireEnd}.
   *
   * @return true if more input could change a positive match into a negative one
   */
  public boolean requireEnd() {
    return lastRequireEnd;
  }

  /**
   * Returns an unmodifiable map of named capturing groups to their 1-based group indices. This
   * method overrides the default {@link java.util.regex.MatchResult#namedGroups()} method which
   * throws {@link UnsupportedOperationException}.
   *
   * @return an unmodifiable map from group names to group numbers
   */
  @Override
  public Map<String, Integer> namedGroups() {
    return parentPattern.namedGroups();
  }

  /**
   * Returns the pattern that is interpreted by this matcher.
   *
   * @return the pattern for which this matcher was created
   */
  public Pattern pattern() {
    return parentPattern;
  }

  @Override
  public String toString() {
    String lastMatch = "";
    if (hasMatch && groups != null && groups[0] >= 0 && groups[1] >= groups[0]) {
      lastMatch = text.substring(groups[0], groups[1]);
    }
    return "org.safere.Matcher[pattern="
        + parentPattern.pattern()
        + " region="
        + regionStart
        + ","
        + regionEnd
        + " lastmatch="
        + lastMatch
        + "]";
  }

  /**
   * Changes the {@link Pattern} that this {@code Matcher} uses to find matches. This method causes
   * this matcher to lose information about the groups of the last match. The matcher's position in
   * the input is maintained.
   *
   * @param newPattern the new pattern used by this matcher
   * @return this matcher
   * @throws IllegalArgumentException if newPattern is null
   */
  public Matcher usePattern(Pattern newPattern) {
    if (newPattern == null) {
      throw new IllegalArgumentException("Pattern cannot be null");
    }
    modCount++;
    if (hasMatch && groups != null) {
      if (!groupZeroResolved) {
        resolveCaptures();
      }
      searchFrom = groups[1];
      if (groups[0] == groups[1] && searchFrom < regionEnd) {
        searchFrom++;
      }
    }
    this.parentPattern = newPattern;
    invalidatePatternCaches();
    clearCurrentResult();
    eagerFallbackCaptures = false;
    return this;
  }

  /**
   * Sets the transparency of region bounds for this matcher. Transparent bounds allow lookaround
   * assertions to see beyond the region boundaries. Since SafeRE does not support lookaround
   * assertions, this method stores the flag but it has no effect on matching behavior.
   *
   * @param b a boolean indicating whether to use transparent bounds
   * @return this matcher
   */
  public Matcher useTransparentBounds(boolean b) {
    preserveResultAcrossBoundsChange();
    transparentBounds = b;
    return this;
  }

  /**
   * Returns whether this matcher is using transparent bounds.
   *
   * @return {@code true} if this matcher is using transparent bounds, {@code false} otherwise
   */
  public boolean hasTransparentBounds() {
    return transparentBounds;
  }

  /**
   * Sets the anchoring of region bounds for this matcher. Anchoring bounds cause {@code ^} and
   * {@code $} to match at the region boundaries rather than at the start and end of the entire
   * input. This is the default behavior.
   *
   * @param b a boolean indicating whether to use anchoring bounds
   * @return this matcher
   */
  public Matcher useAnchoringBounds(boolean b) {
    preserveResultAcrossBoundsChange();
    anchoringBounds = b;
    return this;
  }

  /**
   * Returns whether this matcher is using anchoring bounds.
   *
   * @return {@code true} if this matcher is using anchoring bounds, {@code false} otherwise
   */
  public boolean hasAnchoringBounds() {
    return anchoringBounds;
  }

  /**
   * Returns the match state of this matcher as a {@link MatchResult}. The result is independent
   * of this matcher; subsequent operations on this matcher will not affect the returned result.
   *
   * @return a {@link MatchResult} with the state of this matcher
   * @throws IllegalStateException if no match has yet been attempted, or if the previous match
   *     operation failed
   */
  public MatchResult toMatchResult() {
    checkMatch();
    eagerFallbackCaptures = true;
    resolveCaptures();
    return new SnapshotMatchResult(groups.clone(), text, groupCount(), parentPattern.namedGroups());
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  /**
   * Resolves deferred capture groups. Called lazily when the user accesses any group
   * (e.g., {@code group(0)}, {@code start(1)}) or when a full snapshot is needed
   * ({@code toMatchResult()}). Runs the submatch engine (OnePass or BitState/NFA) anchored
   * at the DFA-determined match start, bounded by the DFA's match end. For {@code find()}, it does
   * not force the match to extend to that end; this allows alternation priority to determine the
   * actual match length (e.g., {@code (fo|foo)} matching "fo" rather than "foo"). For
   * {@code matches()}, the deferred search must still cover the whole input.
   */
  private void resolveCaptures() {
    if (capturesResolved) {
      return;
    }
    Prog prog = parentPattern.prog();
    // Search anchored at matchStart, bounded by matchEnd, to extract inner capture groups.
    // The DFA sandwich has already determined group(0) bounds; this pass fills in the inner
    // captures within that range.
    //
    // Prefer OnePass when available — it's a single deterministic pass with no bitmap or job
    // stack overhead. Skip for patterns with nullable alternation where OnePass's longest-match
    // semantics would pick the wrong branch (consuming over zero-width). For non-nullable
    // alternation (e.g., GET|POST), all branches must consume characters so longest-match
    // and first-match are equivalent.
    int[] result;
    if (enginePathOptions().onePass()
        && parentPattern.canOnePassSubmatch()
        && (!enginePathOptions().semanticGuards() || !parentPattern.hasNullableAlternation())
        && canUsePikeEquivalentCaptures(prog)) {
      result = parentPattern.onePass().search(
          text, deferredMatchStart, deferredMatchEnd, false, prog.numCaptures());
    } else {
      result = searchWithBitStateOrNfa(
          prog, text, deferredMatchStart, deferredMatchEnd, deferredMatchEnd,
          true, false, deferredEndMatch, prog.numCaptures());
    }
    if (result != null) {
      groups = result;
    }
    capturesResolved = true;
    groupZeroResolved = true;
  }

  private void checkMatch() {
    if (resultStatus != ResultStatus.MATCHED) {
      throw new IllegalStateException("No match found");
    }
  }

  private void checkGroup(int group) {
    if (group < 0 || group > groupCount()) {
      throw new IndexOutOfBoundsException(
          "No group " + group + " (groupCount=" + groupCount() + ")");
    }
  }

  private void checkConcurrentModification(int expectedModCount) {
    if (modCount != expectedModCount) {
      throw new ConcurrentModificationException();
    }
  }

  private void updateEndState(MatchOperation operation) {
    if (!hasMatch || groups == null) {
      lastHitEnd = operation == MatchOperation.FIND;
      lastRequireEnd = false;
      return;
    }
    boolean endedAtSensitiveEnd = matchEndsAtSensitiveEnd();
    lastRequireEnd = endedAtSensitiveEnd && parentPattern.hasEndConstraint();
    lastHitEnd = lastRequireEnd
        || (endedAtSensitiveEnd
            && (parentPattern.hasHitEndConstraint() || matchCanExtendAtEnd(operation)));
  }

  private boolean matchEndsAtSensitiveEnd() {
    if (groups[1] == regionEnd) {
      return true;
    }
    Prog prog = parentPattern.prog();
    return prog.dollarAnchorEnd()
        && Nfa.isAtTrailingLineTerminator(text, groups[1], prog.unixLines());
  }

  private boolean matchCanExtendAtEnd(MatchOperation operation) {
    java.util.List<String> samples = new java.util.ArrayList<>();
    collectTerminalRepeatSamples(parentPattern.ast(), samples);
    if (samples.isEmpty()) {
      return false;
    }
    int relativeStart = groups[0] - regionStart;
    int relativeEnd = groups[1] - regionStart;
    String regionText = text.substring(regionStart, regionEnd);
    Prog prog = parentPattern.prog();
    for (String sample : samples) {
      if (sample.isEmpty()) {
        continue;
      }
      String probeText = regionText + sample;
      int[] result = switch (operation) {
        case MATCHES -> Nfa.search(
            prog, probeText, 0, probeText.length(),
            Nfa.Anchor.ANCHORED, Nfa.MatchKind.FULL_MATCH, 1);
        case LOOKING_AT, FIND -> Nfa.search(
            prog, probeText, relativeStart, probeText.length(),
            Nfa.Anchor.ANCHORED, Nfa.MatchKind.LONGEST_MATCH, 1);
      };
      if (result != null && result[0] == relativeStart && result[1] > relativeEnd) {
        return true;
      }
    }
    return false;
  }

  private static void collectTerminalRepeatSamples(Regexp re, java.util.List<String> samples) {
    switch (re.op) {
      case STAR, PLUS -> addSample(re.subs.get(0), samples);
      case REPEAT -> {
        if (re.max < 0) {
          addSample(re.subs.get(0), samples);
        }
      }
      case NON_CAPTURE, CAPTURE -> collectTerminalRepeatSamples(re.subs.get(0), samples);
      case CONCAT -> {
        for (int i = re.subs.size() - 1; i >= 0; i--) {
          Regexp sub = re.subs.get(i);
          collectTerminalRepeatSamples(sub, samples);
          if (!Pattern.canMatchEmpty(sub)) {
            break;
          }
        }
      }
      case ALTERNATE -> {
        for (Regexp sub : re.subs) {
          collectTerminalRepeatSamples(sub, samples);
        }
      }
      default -> {}
    }
  }

  private static void addSample(Regexp re, java.util.List<String> samples) {
    String sample = sampleString(re);
    if (sample != null && !sample.isEmpty()) {
      samples.add(sample);
    }
  }

  private static String sampleString(Regexp re) {
    return switch (re.op) {
      case LITERAL -> new String(Character.toChars(re.rune));
      case LITERAL_STRING -> new String(re.runes, 0, re.runes.length);
      case CHAR_CLASS -> re.charClass.isEmpty()
          ? null
          : new String(Character.toChars(re.charClass.lo(0)));
      case ANY_CHAR -> "a";
      case NON_CAPTURE, CAPTURE -> sampleString(re.subs.get(0));
      case CONCAT -> {
        StringBuilder sb = new StringBuilder();
        for (Regexp sub : re.subs) {
          String sample = sampleString(sub);
          if (sample == null) {
            yield null;
          }
          sb.append(sample);
        }
        yield sb.toString();
      }
      case ALTERNATE -> {
        String sample = null;
        for (Regexp sub : re.subs) {
          sample = sampleString(sub);
          if (sample != null) {
            break;
          }
        }
        yield sample;
      }
      default -> null;
    };
  }

  /**
   * Processes a replacement string and appends the result to {@code sb}. Handles {@code $0},
   * {@code $1}, {@code ${name}}, {@code \\} (literal backslash), and {@code \$} (literal
   * dollar).
   */
  private void appendReplacementBody(StringBuilder sb, String replacement) {
    int i = 0;
    while (i < replacement.length()) {
      char c = replacement.charAt(i);
      if (c == '\\') {
        i++;
        if (i >= replacement.length()) {
          throw new IllegalArgumentException(
              "Trailing backslash in replacement string");
        }
        sb.append(replacement.charAt(i));
        i++;
      } else if (c == '$') {
        i++;
        if (i >= replacement.length()) {
          throw new IllegalArgumentException(
              "Trailing dollar sign in replacement string");
        }
        if (replacement.charAt(i) == '{') {
          // Named group reference: ${name}
          i++;
          int nameStart = i;
          while (i < replacement.length() && replacement.charAt(i) != '}') {
            i++;
          }
          if (i >= replacement.length()) {
            throw new IllegalArgumentException(
                "Missing closing '}' in replacement string");
          }
          String name = replacement.substring(nameStart, i);
          i++; // skip '}'
          String g = group(name);
          if (g != null) {
            sb.append(g);
          }
        } else if (Character.isDigit(replacement.charAt(i))) {
          // Numeric group reference: $0, $1, $12, etc.
          NumericGroupReference groupRef =
              parseNumericGroupReference(replacement, i, groupCount());
          int groupIdx = groupRef.groupNum();
          i = groupRef.end();
          String g = group(groupIdx);
          if (g != null) {
            sb.append(g);
          }
        } else {
          throw new IllegalArgumentException(
              "Invalid group reference in replacement string");
        }
      } else {
        sb.append(c);
        i++;
      }
    }
  }

  /** A snapshot of a match result, independent of the matcher that created it. */
  private static final class SnapshotMatchResult implements MatchResult {

    private final int[] groups;
    private final String text;
    private final int groupCount;
    private final Map<String, Integer> namedGroups;

    SnapshotMatchResult(
        int[] groups, String text, int groupCount, Map<String, Integer> namedGroups) {
      this.groups = groups;
      this.text = text;
      this.groupCount = groupCount;
      this.namedGroups = Collections.unmodifiableMap(namedGroups);
    }

    @Override
    public int start() {
      return start(0);
    }

    @Override
    public int start(int group) {
      validateGroup(group);
      return groups[2 * group];
    }

    @Override
    public int start(String name) {
      return start(groupIndex(name));
    }

    @Override
    public int end() {
      return end(0);
    }

    @Override
    public int end(int group) {
      validateGroup(group);
      return groups[2 * group + 1];
    }

    @Override
    public int end(String name) {
      return end(groupIndex(name));
    }

    @Override
    public String group() {
      return group(0);
    }

    @Override
    public String group(int group) {
      int s = start(group);
      int e = end(group);
      if (s == -1) {
        return null;
      }
      return text.substring(s, e);
    }

    @Override
    public String group(String name) {
      return group(groupIndex(name));
    }

    @Override
    public int groupCount() {
      return groupCount;
    }

    @Override
    public Map<String, Integer> namedGroups() {
      return namedGroups;
    }

    private int groupIndex(String name) {
      Objects.requireNonNull(name, "Group name");
      Integer idx = namedGroups.get(name);
      if (idx == null) {
        throw new IllegalArgumentException("No group with name <" + name + ">");
      }
      return idx;
    }

    private void validateGroup(int group) {
      if (group < 0 || group > groupCount) {
        throw new IndexOutOfBoundsException(
            "No group " + group + " (groupCount=" + groupCount + ")");
      }
    }
  }
}
