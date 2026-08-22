// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * An engine that performs match operations on a {@linkplain CharSequence character sequence} by
 * interpreting a {@link Pattern}. This class is a drop-in replacement for {@link
 * java.util.regex.Matcher} backed by a linear-time matching engine.
 *
 * <p>Matching uses a two-phase engine cascade: the DFA quickly determines whether a match exists
 * (and where it ends), then the NFA extracts capture group positions. If the DFA exceeds its state
 * budget, the NFA handles the entire search.
 *
 * <p>A matcher is created from a pattern by invoking the pattern's {@link Pattern#matcher matcher}
 * method. Once created, a matcher can be used to perform three different kinds of match operations:
 *
 * <ul>
 *   <li>The {@link #matches matches} method attempts to match the entire input sequence against the
 *       pattern.
 *   <li>The {@link #lookingAt lookingAt} method attempts to match the input sequence, starting at
 *       the beginning, against the pattern.
 *   <li>The {@link #find find} method scans the input sequence looking for the next subsequence
 *       that matches the pattern.
 * </ul>
 */
public final class Matcher implements MatchResult {

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

  /** Maximum text length for anchored OnePass when inner captures are not required. */
  static final int ONEPASS_TEXT_LIMIT_NO_CAPTURES = 256;

  /** Maximum text length for anchored OnePass when inner captures are required. */
  static final int ONEPASS_TEXT_LIMIT_WITH_CAPTURES = 65536;

  /**
   * Returns the maximum input text length eligible for anchored OnePass execution based on whether
   * inner capture extraction is required.
   *
   * <ul>
   *   <li><b>No inner captures (&lt;= 256 bytes)</b>: For short strings, OnePass avoids the lazy
   *       DFA initialization overhead (~50-80 ns). For larger texts without captures (including
   *       literal/group-0 replacements or boolean-only matching on patterns without groups),
   *       Forward DFA's direct transition table loop and SIMD vector acceleration are 3x-10x+
   *       faster.
   *   <li><b>With inner captures (&lt;= 65536 bytes)</b>: OnePass extracts capture group boundaries
   *       in a single linear pass, outperforming the multi-pass DFA + BitState/NFA submatch
   *       extraction sandwich (2x-28x speedup) and avoiding the Java NFA heap allocation cliff at
   *       &gt;= 16 KB.
   * </ul>
   *
   * <p><b>Tradeoff Note (pattern.matcher(input).matches() -&gt; group()):</b> In Java's stateful
   * Matcher API, matches() is called before the engine knows whether the caller will subsequently
   * call group(i). If a pattern contains capturing groups (prog.numCaptures() &gt; 0), defaulting
   * to the 64 KB limit ensures one-shot matchers (e.g. pattern.matcher(input).matches() -&gt;
   * group(1)) record captures in a single pass without paying the severe (2.6x) multi-pass fallback
   * submatch penalty on 256 B - 64 KB inputs.
   */
  static int onePassTextLimit(boolean requiresInnerCaptures) {
    return requiresInnerCaptures
        ? ONEPASS_TEXT_LIMIT_WITH_CAPTURES
        : ONEPASS_TEXT_LIMIT_NO_CAPTURES;
  }

  /**
   * Returns the OnePass text limit for standard Matcher operations based on whether the pattern
   * declares capturing groups.
   */
  int onePassTextLimit() {
    return onePassTextLimit(parentPattern.numGroups() > 0);
  }

  /**
   * Maximum number of submatches (including group 0) for lazy fallback capture extraction.
   * Deferring captures saves work for find-all loops that only need group 0, but the first inner
   * group access has to rerun the submatch engine. Keep the optimization to small-capture patterns
   * so capture-heavy parsers do not pay that startup penalty.
   */
  private static final int MAX_LAZY_FALLBACK_SUBMATCHES = 3;

  /**
   * Maximum input length for adapting match operations to observed inner-capture demand. On small
   * inputs BitState can produce the boolean result and all capture bounds in one bounded pass;
   * larger inputs retain the DFA's scanning advantage.
   */
  private static final int CAPTURE_DEMAND_TEXT_LIMIT = 512;

  private Pattern parentPattern;
  private CharSequence inputSequence;
  private String text;
  private InputScanner textScanner;
  private int[] groups;
  private int[] matchOffsets;
  private boolean hasMatch;
  private ResultStatus resultStatus = ResultStatus.RESET_NO_ATTEMPT;
  private int searchFrom;
  private int appendPos;
  private boolean transparentBounds;
  private boolean anchoringBounds = true;
  private int regionStart;
  private int regionEnd;
  private boolean fullTextRegionContext;
  private boolean findExhaustedAfterTerminalEmptyMatch;
  private int modCount;
  private DiagnosticOperation diagnosticOperation;
  private boolean diagnosticCaptureSearch;

  private record DiagnosticOperation(
      SafeReMatchDiagnostics listener,
      MatchOperation operation,
      DiagnosticAccumulator accumulator) {}

  private DiagnosticOperation beginDiagnostics(MatchOperation operation) {
    if (diagnosticOperation != null) {
      return null;
    }
    SafeReMatchDiagnostics listener = Pattern.diagnostics();
    if (!SafeReMatchDiagnostics.isEnabled(listener)) {
      return null;
    }
    DiagnosticOperation started =
        new DiagnosticOperation(listener, operation, new DiagnosticAccumulator());
    diagnosticOperation = started;
    return started;
  }

  private void abortDiagnostics(DiagnosticOperation operation) {
    if (operation != null) {
      diagnosticOperation = null;
    }
  }

  private void completeDiagnostics(DiagnosticOperation operation, int matchCount) {
    if (operation == null) {
      return;
    }
    DiagnosticAccumulator accumulator = operation.accumulator();
    accumulator.matchCount(matchCount);
    MatchOutcome outcome = matchCount == 0 ? MatchOutcome.NO_MATCH : MatchOutcome.MATCH;
    CaptureMode captureMode;
    if (matchCount == 0 || parentPattern.numGroups() == 0) {
      captureMode = CaptureMode.NONE;
    } else {
      captureMode = accumulator.captured() ? CaptureMode.EAGER : CaptureMode.DEFERRED;
    }
    OperationDiagnostics event =
        accumulator.toEvent(
            parentPattern.descriptor(),
            operation.operation(),
            outcome,
            captureMode,
            getTextLength());
    diagnosticOperation = null;
    operation.listener().onOperationCompleted(event);
  }

  private DiagnosticAccumulator diagnosticsAccumulator() {
    return diagnosticOperation == null ? null : diagnosticOperation.accumulator();
  }

  private void diagnosticBoundary(MatchStrategy strategy) {
    DiagnosticAccumulator accumulator = diagnosticsAccumulator();
    if (accumulator != null) {
      accumulator.boundary(strategy);
    }
  }

  private void diagnosticExact(MatchStrategy strategy) {
    DiagnosticAccumulator accumulator = diagnosticsAccumulator();
    if (accumulator != null) {
      if (diagnosticCaptureSearch) {
        accumulator.capture(strategy);
      } else {
        accumulator.boundary(strategy);
      }
    }
  }

  private void diagnosticBoundaryOverride(MatchStrategy strategy) {
    DiagnosticAccumulator accumulator = diagnosticsAccumulator();
    if (accumulator != null) {
      accumulator.replaceBoundary(strategy);
    }
  }

  private void diagnosticCapture(MatchStrategy strategy) {
    DiagnosticAccumulator accumulator = diagnosticsAccumulator();
    if (accumulator != null) {
      accumulator.capture(strategy);
    }
  }

  private void diagnosticParticipation(MatchStrategy strategy, StrategyRole role) {
    DiagnosticAccumulator accumulator = diagnosticsAccumulator();
    if (accumulator != null) {
      accumulator.participate(strategy, role);
    }
  }

  private void diagnosticDecision(
      MatchStrategy strategy, StrategyDisposition disposition, StrategyReason reason) {
    DiagnosticAccumulator accumulator = diagnosticsAccumulator();
    if (accumulator != null) {
      accumulator.decision(strategy, disposition, reason);
    }
  }

  private Dfa.SearchResult searchForwardDfa(
      Dfa dfa, InputScanner scanner, boolean anchored, boolean longest) {
    return searchForwardDfa(dfa, scanner, 0, anchored, longest);
  }

  private Dfa.SearchResult searchForwardDfa(
      Dfa dfa, InputScanner scanner, int startPos, boolean anchored, boolean longest) {
    Dfa.SearchResult result = dfa.doSearch(scanner, startPos, anchored, longest);
    DiagnosticOperation activeDiagnostics = diagnosticOperation;
    if (activeDiagnostics != null) {
      activeDiagnostics.accumulator().incrementForwardDfaSearchCount();
      diagnosticDfaBudget(result);
    }
    return result;
  }

  private Dfa.SearchResult searchReverseDfa(
      Dfa dfa,
      InputScanner scanner,
      int endPos,
      int startLimit,
      boolean anchored,
      boolean longest) {
    Dfa.SearchResult result = dfa.doSearchReverse(scanner, endPos, startLimit, anchored, longest);
    DiagnosticOperation activeDiagnostics = diagnosticOperation;
    if (activeDiagnostics != null) {
      activeDiagnostics.accumulator().incrementReverseDfaSearchCount();
      diagnosticDfaBudget(result);
    }
    return result;
  }

  private void diagnosticDfaBudget(Dfa.SearchResult result) {
    if (result == null) {
      diagnosticDecision(
          MatchStrategy.DFA, StrategyDisposition.FALLBACK, StrategyReason.DFA_BUDGET_EXCEEDED);
    }
  }

  private void diagnosticIncrementMatchCount() {
    DiagnosticAccumulator accumulator = diagnosticsAccumulator();
    if (accumulator != null) {
      accumulator.incrementMatchCount();
    }
  }

  private int diagnosticMatchCount() {
    DiagnosticAccumulator accumulator = diagnosticsAccumulator();
    return accumulator == null ? 0 : accumulator.matchCount();
  }

  /**
   * Cached BitState instance borrowed from the parent Pattern's thread-local cache, reused across
   * {@code find()} calls. Borrowed on first use, returned on final use within the Matcher's
   * lifetime.
   */
  private BitState cachedBitState;

  private boolean bitStateBorrowed;
  private int[] bitStateResult;
  private int[] onePassScratchCap;

  /** Cached Nfa instance borrowed from the parent Pattern's thread-local cache. */
  private Nfa cachedNfa;

  private boolean nfaBorrowed;

  /**
   * Whether all capture groups have been resolved. When the DFA sandwich determines match
   * boundaries (group 0), inner captures (groups 1+) are deferred until explicitly requested. This
   * avoids the expensive BitState/NFA submatch extraction in find-all loops that only check match
   * existence or read group 0.
   */
  private boolean capturesResolved = true;

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
  private Dfa cachedForwardFirstMatchDfa;

  private Dfa cachedForwardLongestMatchDfa;

  private Dfa cachedReverseDfa;
  private boolean reverseDfaLookedUp;
  private String graphemeContextText;
  private GraphemeSupport.Context graphemeContext;

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
    this.textScanner = null;
    this.regionEnd = text.length();
    this.groups = new int[2 * pattern.prog().numCaptures()];
  }

  Matcher(Pattern pattern, Utf8InputScanner input) {
    this.parentPattern = pattern;
    this.textScanner = input;
    this.regionEnd = input.length();
    this.groups = new int[2 * pattern.prog().numCaptures()];
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

  private boolean applyFailedMatchResult() {
    hasMatch = false;
    resultStatus = ResultStatus.FAILED;
    clearDeferredCaptureState();
    return false;
  }

  @SuppressWarnings("ReferenceEquality")
  private boolean applyFullMatchResult(int[] resultGroups) {
    findExhaustedAfterTerminalEmptyMatch = false;
    hasMatch = resultGroups != null;
    if (hasMatch && resultGroups != this.groups) {
      System.arraycopy(resultGroups, 0, this.groups, 0, resultGroups.length);
    }
    resultStatus = hasMatch ? ResultStatus.MATCHED : ResultStatus.FAILED;
    clearDeferredCaptureState();
    return hasMatch;
  }

  private boolean applyGroupZeroMatchResult(int start, int end) {
    findExhaustedAfterTerminalEmptyMatch = false;
    groups[0] = start;
    groups[1] = end;
    if (groups.length > 2) {
      Arrays.fill(groups, 2, groups.length, -1);
    }
    deferredMatchStart = start;
    deferredMatchEnd = end;
    deferredEndMatch = false;
    capturesResolved = true;
    groupZeroResolved = true;
    hasMatch = true;
    resultStatus = ResultStatus.MATCHED;
    return true;
  }

  private boolean applyDeferredMatchResult(
      int start, int end, int ncap, boolean groupZeroResolved, boolean endMatch) {
    findExhaustedAfterTerminalEmptyMatch = false;
    Arrays.fill(groups, -1);
    groups[0] = start;
    groups[1] = end;
    deferredMatchStart = start;
    deferredMatchEnd = end;
    deferredEndMatch = endMatch;
    capturesResolved = ncap <= 1;
    this.groupZeroResolved = groupZeroResolved;
    hasMatch = true;
    resultStatus = ResultStatus.MATCHED;
    return true;
  }

  private void clearCurrentResult() {
    findExhaustedAfterTerminalEmptyMatch = false;
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
    if (inputSequence != null) {
      if (!(inputSequence instanceof String)) {
        textScanner = null;
        graphemeContextText = null;
        graphemeContext = null;
      }
      text = charSequenceToString(inputSequence);
    }
    regionStart = 0;
    regionEnd = getTextLength();
    resetSearchStateForInputStart();
    resetReplacementState();
    clearCurrentResult();
  }

  private void resetStateForRegion(int start, int end) {
    regionStart = start;
    regionEnd = end;
    resetSearchStateForRegionStart();
    resetReplacementState();
    clearCurrentResult();
  }

  private void invalidatePatternCaches() {
    cachedForwardFirstMatchDfa = null;
    cachedForwardLongestMatchDfa = null;
    cachedReverseDfa = null;
    reverseDfaLookedUp = false;
    if (bitStateBorrowed && cachedBitState != null) {
      bitStateBorrowed = false;
      cachedBitState = null;
    }
    if (nfaBorrowed && cachedNfa != null) {
      nfaBorrowed = false;
      cachedNfa = null;
    }
    bitStateResult = null;
    graphemeContextText = null;
    graphemeContext = null;
  }

  private void invalidateInputDependentCaches() {
    textScanner = null;
    bitStateBorrowed = false;
    cachedBitState = null;
    bitStateResult = null;
    nfaBorrowed = false;
    cachedNfa = null;
    graphemeContextText = null;
    graphemeContext = null;
  }

  private void preserveResultAcrossBoundsChange() {
    if (hasMatch && !capturesResolved) {
      resolveCaptures();
    }
    clearDeferredCaptureState();
  }

  private boolean cannotMatchLength(int availableLength) {
    if (diagnosticOperation != null) {
      return false;
    }
    int min = parentPattern.matchDescriptor().minMatchLength();
    return min > 0 && availableLength < min;
  }

  private EnginePathOptions enginePathOptions() {
    return parentPattern.enginePathOptions();
  }

  private void recordInnerCaptureDemand() {
    if (!eagerFallbackCaptures) {
      parentPattern.recordInnerCaptureAccess();
      eagerFallbackCaptures = true;
    }
  }

  private boolean shouldPreferCaptureEngine(Prog prog, InputScanner scanner) {
    return parentPattern.innerCapturesObserved()
        && scanner.length() <= CAPTURE_DEMAND_TEXT_LIMIT
        && enginePathOptions().bitState()
        && !fullTextRegionContext
        && !prog.hasGraphemeSemantics()
        && BitState.maxTextSize(prog) >= scanner.length();
  }

  /** Returns the Pattern's thread-local cached forward DFA, caching it for reuse. */
  private Dfa dfa(boolean longest) {
    if (longest) {
      Dfa d = cachedForwardLongestMatchDfa;
      if (d == null) {
        d = parentPattern.forwardLongestMatchDfa();
        cachedForwardLongestMatchDfa = d;
      }
      return d;
    } else {
      Dfa d = cachedForwardFirstMatchDfa;
      if (d == null) {
        d = parentPattern.forwardFirstMatchDfa();
        cachedForwardFirstMatchDfa = d;
      }
      return d;
    }
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
   * Fast path for {@code matches()} when the pattern is a single character class under a quantifier
   * (e.g., {@code [a-zA-Z]+}, {@code \d*}). Uses precomputed ASCII bitmaps for O(1) per-character
   * checks and falls back to binary search for non-ASCII code points.
   */
  private boolean charClassMatchFastPath(Pattern.CharClassMatchInfo matchInfo) {
    long b0 = matchInfo.bitmap0();
    long b1 = matchInfo.bitmap1();
    boolean allowEmpty = matchInfo.allowEmpty();

    int len = text.length();
    if (len == 0) {
      if (allowEmpty) {
        return applyFullMatchResult(new int[] {0, 0});
      }
      return applyFailedMatchResult();
    }

    int[] ranges = matchInfo.ranges();
    // Scan every code point.
    int i = 0;
    while (i < len) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      int cp = text.codePointAt(i);
      if (cp < 64) {
        if ((b0 & (1L << cp)) == 0) {
          return applyFailedMatchResult();
        }
      } else if (cp < 128) {
        if ((b1 & (1L << (cp - 64))) == 0) {
          return applyFailedMatchResult();
        }
      } else {
        if (!binarySearchRanges(ranges, cp)) {
          return applyFailedMatchResult();
        }
      }
      i += Character.charCount(cp);
    }

    return applyFullMatchResult(new int[] {0, len});
  }

  /**
   * Fast path for {@code find()} when the pattern is exactly one character class. Scans code points
   * directly and returns the first matching code point as group 0.
   */
  private boolean singleCharClassFindFastPath(CharClassScanInfo scanInfo, int fromIndex) {
    if (scanInfo.isAscii()) {
      int idx = activeScanner().indexOfCharClass(scanInfo, fromIndex);
      if (idx >= 0) {
        return applyFullMatchResult(new int[] {idx, idx + 1});
      }
      return applyFailedMatchResult();
    }
    int idx =
        activeScanner()
            .indexOfCodePointClass(
                scanInfo.ranges(),
                scanInfo.bitmap0(),
                scanInfo.bitmap1(),
                fromIndex,
                activeScanner().length());
    if (idx >= 0) {
      int end =
          text != null
              ? idx + Character.charCount(text.codePointAt(idx))
              : InputScanner.position(activeScanner().decodeForward(idx));
      return applyFullMatchResult(new int[] {idx, end});
    }
    return applyFailedMatchResult();
  }

  /**
   * Checks if the remaining input from {@code offset} is a prefix of the literal pattern but
   * shorter than it, meaning more input could potentially result in a match.
   */
  private boolean isPartialLiteralMatch(String literal, int offset) {
    int remainingLen = text.length() - offset;
    if (remainingLen >= literal.length()) {
      return false;
    }
    return literalRegionMatches(literal, offset, remainingLen);
  }

  private boolean literalRegionMatches(String literal, int offset, int length) {
    if (parentPattern.literalFoldCase() || parentPattern.prefixFoldCase()) {
      return Ascii.regionMatchesIgnoreCase(text, offset, literal, length);
    }
    return text.regionMatches(false, offset, literal, 0, length);
  }

  private static boolean charClassContains(int[] ranges, long b0, long b1, int cp) {
    if (cp < 64) {
      return (b0 & (1L << cp)) != 0;
    }
    if (cp < 128) {
      return (b1 & (1L << (cp - 64))) != 0;
    }
    return binarySearchRanges(ranges, cp);
  }

  /** Binary search through sorted [lo, hi] ranges to check if {@code cp} is in any range. */
  static boolean binarySearchRanges(int[] ranges, int cp) {
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
   * references (numbered or named). Pre-parsing avoids per-match scanning, {@code parseInt}, and
   * {@code substring} allocation.
   */
  sealed interface ReplacementSegment {
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
   * @param replacement the replacement string (may contain {@code $1}, {@code ${name}}, {@code \\},
   *     {@code \$})
   * @param maxGroup the highest legal capturing-group number, excluding group 0
   * @return an array of segments representing the compiled template
   * @throws IllegalArgumentException if the replacement string is malformed
   */
  static ReplacementSegment[] compileReplacementTemplate(String replacement, int maxGroup) {
    // Fast path: no special characters → single literal segment.
    if (isSimpleReplacement(replacement)) {
      return new ReplacementSegment[] {new ReplacementSegment.Literal(replacement)};
    }

    List<ReplacementSegment> segments = new ArrayList<>();
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
          segments.add(new ReplacementSegment.NamedGroupRef(replacement.substring(nameStart, i)));
          i++; // skip '}'
        } else if (Character.isDigit(replacement.charAt(i))) {
          // Numeric group reference: $0, $1, $12, etc.
          NumericGroupReference groupRef = parseNumericGroupReference(replacement, i, maxGroup);
          segments.add(new ReplacementSegment.GroupRef(groupRef.groupNum()));
          i = groupRef.end();
        } else {
          throw new IllegalArgumentException("Invalid group reference in replacement string");
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

  private static boolean templateNeedsCaptures(ReplacementSegment[] template) {
    for (ReplacementSegment seg : template) {
      if (seg instanceof ReplacementSegment.GroupRef gRef) {
        if (gRef.groupNum() > 0) {
          return true;
        }
      } else if (seg instanceof ReplacementSegment.NamedGroupRef) {
        return true;
      }
    }
    return false;
  }

  /**
   * Applies a compiled replacement template to the current match, appending the result to {@code
   * sb}. Uses {@code sb.append(text, start, end)} for group values to avoid substring allocation.
   *
   * <p>Captures must already be resolved before calling this method.
   */
  private void applyReplacementTemplate(StringBuilder sb, ReplacementSegment[] template) {
    if (templateNeedsCaptures(template)) {
      if (!capturesResolved) {
        recordInnerCaptureDemand();
      }
      resolveCaptures();
    }
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
    DiagnosticOperation operation = beginDiagnostics(MatchOperation.MATCHES);
    if (operation == null) {
      return matchesImpl();
    }
    try {
      boolean matched = matchesImpl();
      completeDiagnostics(operation, matched ? 1 : 0);
      return matched;
    } catch (RuntimeException | Error e) {
      abortDiagnostics(operation);
      throw e;
    }
  }

  private boolean matchesImpl() {
    modCount++;
    findExhaustedAfterTerminalEmptyMatch = false;
    searchFrom = regionStart;

    if (cannotMatchLength(regionEnd - regionStart)) {
      return applyFailedMatchResult();
    }

    // --- Region setup ---
    boolean regionActive = (regionStart != 0 || regionEnd != getTextLength());
    String savedText = text;
    InputScanner savedTextScanner = textScanner;
    boolean regionSubstituted = false;

    try {
      if (regionActive && !anchoringBounds && regionTextAnchorCannotMatch()) {
        return applyFailedMatchResult();
      }
      if (needsFullTextRegionContext(regionActive, parentPattern.prog())) {
        return matchesTransparentRegion();
      }
      if (regionActive) {
        if (text != null) {
          text = savedText.substring(regionStart, regionEnd);
          textScanner = null;
        } else {
          textScanner = ((Utf8InputScanner) savedTextScanner).slice(regionStart, regionEnd);
        }
        regionSubstituted = true;
      }
      return parentPattern.preparedMatchRunner(regionActive).matches(this);
    } finally {
      if (regionSubstituted) {
        resolveCapturesBeforeRestoringRegion();
        text = savedText;
        textScanner = savedTextScanner;
        if (hasMatch) {
          for (int i = 0; i < groups.length; i++) {
            if (groups[i] >= 0) {
              groups[i] += regionStart;
            }
          }
        }
      }
      fullTextRegionContext = false;
    }
  }

  private boolean prefixOrCharClassCannotMatch(int searchFrom) {
    if (parentPattern.prefix() != null && !parentPattern.prefixFoldCase()) {
      if (text != null) {
        if (!text.startsWith(parentPattern.prefix(), searchFrom)) {
          if (WorkCounterConfig.ENABLED) {
            WorkCounter.record(parentPattern.prefix().length());
          }
          return true;
        }
      } else if (textScanner instanceof Utf8InputScanner utf8Scanner) {
        if (!utf8Scanner.startsWith(parentPattern.prefixUtf8(), searchFrom)) {
          return true;
        }
      }
    } else if (parentPattern.charClassPrefix() != null) {
      CharClassScanInfo cc = parentPattern.charClassPrefix();
      if (text != null) {
        if (searchFrom >= text.length()) {
          return true;
        }
        int cp = text.codePointAt(searchFrom);
        if (!cc.contains(cp)) {
          if (WorkCounterConfig.ENABLED) {
            WorkCounter.record(1);
          }
          return true;
        }
      } else if (textScanner instanceof Utf8InputScanner utf8Scanner) {
        if (searchFrom >= utf8Scanner.length()) {
          return true;
        }
        int cp = utf8Scanner.codePointAt(searchFrom);
        if (!cc.contains(cp)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean anchoredPrefixOrCharClassCannotMatch(int searchFrom) {
    if (parentPattern.anchoredPrefix() != null) {
      if (text != null) {
        if (!text.startsWith(parentPattern.anchoredPrefix(), searchFrom)) {
          if (WorkCounterConfig.ENABLED) {
            WorkCounter.record(parentPattern.anchoredPrefix().length());
          }
          return true;
        }
      } else if (textScanner instanceof Utf8InputScanner utf8Scanner) {
        if (!utf8Scanner.startsWith(parentPattern.anchoredPrefixUtf8(), searchFrom)) {
          return true;
        }
      }
    } else if (parentPattern.anchoredCharClassPrefix() != null) {
      CharClassScanInfo cc = parentPattern.anchoredCharClassPrefix();
      if (text != null) {
        if (searchFrom >= text.length()) {
          return true;
        }
        int cp = text.codePointAt(searchFrom);
        if (!cc.contains(cp)) {
          if (WorkCounterConfig.ENABLED) {
            WorkCounter.record(1);
          }
          return true;
        }
      } else if (textScanner instanceof Utf8InputScanner utf8Scanner) {
        if (searchFrom >= utf8Scanner.length()) {
          return true;
        }
        int cp = utf8Scanner.codePointAt(searchFrom);
        if (!cc.contains(cp)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Core matches fallback logic, operates on the (possibly substituted) {@code text} field. */
  private boolean matchesCore() {
    capturesResolved = true;

    if (prefixOrCharClassCannotMatch(0) || anchoredPrefixOrCharClassCannotMatch(0)) {
      MatchStrategy strat =
          parentPattern.prefix() != null || parentPattern.anchoredPrefix() != null
              ? MatchStrategy.LITERAL
              : MatchStrategy.CHARACTER_CLASS;
      diagnosticParticipation(strat, StrategyRole.REJECT_PREFILTER);
      diagnosticBoundary(strat);
      return applyFailedMatchResult();
    }

    RejectPrefilter rejectPrefilter = parentPattern.rejectPrefilter();
    MatchStrategy rejectionStrategy = null;
    if (rejectPrefilter != null && text != null) {
      rejectionStrategy =
          rejectPrefilter instanceof RejectPrefilter.Composite composite
              ? composite.rejectionStrategy(activeScanner(), text, 0, enginePathOptions())
              : rejectPrefilter.canReject(activeScanner(), text, 0, enginePathOptions())
                  ? rejectPrefilter.strategy()
                  : null;
    }
    if (rejectionStrategy != null) {
      diagnosticParticipation(rejectionStrategy, StrategyRole.REJECT_PREFILTER);
      diagnosticBoundary(rejectionStrategy);
      return applyFailedMatchResult();
    }

    Prog prog = parentPattern.prog();
    InputScanner scanner = activeScanner();
    boolean preferCaptureEngine = shouldPreferCaptureEngine(prog, scanner);
    // Medium path: use DFA to check if a full match exists.
    if (!preferCaptureEngine
        && enginePathOptions().dfa()
        && dfaSupportsProgram(parentPattern.flatDfaProg())) {
      diagnosticParticipation(MatchStrategy.DFA, StrategyRole.REJECT_PREFILTER);
      Dfa.SearchResult dfaResult = searchForwardDfa(dfa(true), scanner, true, true);
      if (dfaResult != null && !dfaResult.matched()) {
        diagnosticBoundary(MatchStrategy.DFA);
        return applyFailedMatchResult();
      }
      if (dfaResult != null && dfaResult.pos() != scanner.length()) {
        diagnosticBoundary(MatchStrategy.DFA);
        return applyFailedMatchResult();
      }
      if (dfaResult != null && prog.numLoopRegs() == 0) {
        diagnosticBoundary(MatchStrategy.DFA);
        return applyDeferredMatchResult(0, scanner.length(), prog.numCaptures(), true, true);
      }
      if (dfaResult != null && dfaResult.matched() && prog.numLoopRegs() > 0) {
        diagnosticDecision(
            MatchStrategy.DFA,
            StrategyDisposition.BYPASSED,
            StrategyReason.EXACT_NULLABLE_LOOP_SEMANTICS_REQUIRED);
      }
      if (dfaResult == null) {
        diagnosticDecision(
            MatchStrategy.DFA, StrategyDisposition.FALLBACK, StrategyReason.DFA_BUDGET_EXCEEDED);
      }
    }

    // Slow path: try BitState (faster than NFA for small texts), then NFA.
    int[] result =
        searchWithBitStateOrNfa(
            prog,
            scanner,
            0,
            scanner.length(),
            scanner.length(),
            scanner.length(),
            true,
            false,
            true,
            prog.numCaptures(),
            false,
            this.groups);
    // matches() requires the entire text to be consumed. With dollarAnchorEnd, the BitState
    // may accept a match ending before a trailing \n. In that case, fall back to the NFA
    // which uses longest-match mode for FULL_MATCH and finds the correct full-text match.
    if (result != null && result[1] != scanner.length()) {
      diagnosticBoundaryOverride(MatchStrategy.NFA);
      int[] nfaResult =
          searchNfa(
              prog,
              0,
              scanner.length(),
              scanner.length(),
              scanner.length(),
              prog.numCaptures(),
              Nfa.Anchor.ANCHORED,
              Nfa.MatchKind.FULL_MATCH,
              false,
              this.groups);
      result = nfaResult;
    }
    return applyFullMatchResult(result);
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
    DiagnosticOperation operation = beginDiagnostics(MatchOperation.LOOKING_AT);
    try {
      boolean matched = lookingAtImpl();
      completeDiagnostics(operation, matched ? 1 : 0);
      return matched;
    } catch (RuntimeException | Error e) {
      abortDiagnostics(operation);
      throw e;
    }
  }

  private boolean lookingAtImpl() {
    modCount++;
    findExhaustedAfterTerminalEmptyMatch = false;
    searchFrom = regionStart;

    if (cannotMatchLength(regionEnd - regionStart)) {
      return applyFailedMatchResult();
    }

    // --- Region setup ---
    boolean regionActive = (regionStart != 0 || regionEnd != getTextLength());
    String savedText = text;
    InputScanner savedTextScanner = textScanner;
    boolean regionSubstituted = false;

    try {
      if (regionActive && !anchoringBounds && regionTextAnchorCannotMatch()) {
        return applyFailedMatchResult();
      }
      if (needsFullTextRegionContext(regionActive, parentPattern.prog())) {
        return lookingAtTransparentRegion();
      }
      if (regionActive) {
        if (text != null) {
          text = savedText.substring(regionStart, regionEnd);
          textScanner = null;
        } else {
          textScanner = ((Utf8InputScanner) savedTextScanner).slice(regionStart, regionEnd);
        }
        regionSubstituted = true;
      }
      return parentPattern.preparedMatchRunner(regionActive).lookingAt(this);
    } finally {
      if (regionSubstituted) {
        resolveCapturesBeforeRestoringRegion();
        text = savedText;
        textScanner = savedTextScanner;
        if (hasMatch) {
          for (int i = 0; i < groups.length; i++) {
            if (groups[i] >= 0) {
              groups[i] += regionStart;
            }
          }
        }
      }
      fullTextRegionContext = false;
    }
  }

  /** Core lookingAt fallback logic, operates on the (possibly substituted) {@code text} field. */
  private boolean lookingAtCore() {
    capturesResolved = true;

    if (prefixOrCharClassCannotMatch(0) || anchoredPrefixOrCharClassCannotMatch(0)) {
      MatchStrategy strat =
          parentPattern.prefix() != null || parentPattern.anchoredPrefix() != null
              ? MatchStrategy.LITERAL
              : MatchStrategy.CHARACTER_CLASS;
      diagnosticParticipation(strat, StrategyRole.REJECT_PREFILTER);
      diagnosticBoundary(strat);
      return applyFailedMatchResult();
    }

    Prog prog = parentPattern.prog();
    InputScanner scanner = activeScanner();
    // Medium path: use DFA to check if an anchored match exists.
    if (enginePathOptions().dfa() && dfaSupportsProgram(parentPattern.flatDfaProg())) {
      diagnosticParticipation(MatchStrategy.DFA, StrategyRole.REJECT_PREFILTER);
      Dfa.SearchResult dfaResult = searchForwardDfa(dfa(false), scanner, true, false);
      if (dfaResult != null && !dfaResult.matched()) {
        diagnosticBoundary(MatchStrategy.DFA);
        return applyFailedMatchResult();
      }
      if (dfaResult == null) {
        diagnosticDecision(
            MatchStrategy.DFA, StrategyDisposition.FALLBACK, StrategyReason.DFA_BUDGET_EXCEEDED);
      }
      if (dfaResult != null && dfaResult.matched() && prog.numLoopRegs() > 0) {
        diagnosticDecision(
            MatchStrategy.DFA,
            StrategyDisposition.BYPASSED,
            StrategyReason.EXACT_NULLABLE_LOOP_SEMANTICS_REQUIRED);
      }
    }

    // Slow path: try BitState (faster than NFA for small texts), then NFA.
    int[] result =
        searchWithBitStateOrNfa(
            prog,
            scanner,
            0,
            scanner.length(),
            scanner.length(),
            scanner.length(),
            true,
            false,
            false,
            prog.numCaptures(),
            false,
            this.groups);
    return applyFullMatchResult(result);
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
    DiagnosticOperation operation = beginDiagnostics(MatchOperation.FIND);
    try {
      boolean matched = findImpl();
      completeDiagnostics(operation, matched ? 1 : 0);
      return matched;
    } catch (RuntimeException | Error e) {
      abortDiagnostics(operation);
      throw e;
    }
  }

  private boolean findImpl() {
    modCount++;
    if (findExhaustedAfterTerminalEmptyMatch) {
      applyFailedMatchResult();
      return false;
    }
    if (hasMatch && !advanceSearchPositionAfterPreviousMatch()) {
      return false;
    }
    return doFind();
  }

  private boolean advanceSearchPositionAfterPreviousMatch() {
    if (!groupZeroResolved) {
      resolveCaptures();
    }
    searchFrom = groups[1];
    if (groups[0] == groups[1]) {
      if (searchFrom >= regionEnd) {
        applyFailedMatchResult();
        findExhaustedAfterTerminalEmptyMatch = true;
        searchFrom = regionEnd + 1;
        return false;
      }
      if (text == null) {
        searchFrom = InputScanner.position(activeScanner().decodeForward(searchFrom));
      } else {
        searchFrom++;
      }
    } else if (parentPattern.hasInternalGraphemeClusterBoundary()
        && searchFrom < regionEnd
        && endedAfterCrLf(searchFrom)) {
      searchFrom++;
    }
    return true;
  }

  private boolean endedAfterCrLf(int pos) {
    if (pos < 2) {
      return false;
    }
    InputScanner scanner = activeScanner();
    return scanner.asciiAt(pos - 2) == '\r' && scanner.asciiAt(pos - 1) == '\n';
  }

  private GraphemeSupport.Context graphemeContext() {
    if (graphemeContext == null || !Objects.equals(graphemeContextText, text)) {
      graphemeContextText = text;
      graphemeContext =
          GraphemeSupport.Context.create(
              activeScanner(), parentPattern.prog().hasGraphemeSemantics());
    }
    return graphemeContext;
  }

  private GraphemeSupport.Context graphemeContextFor(Prog prog) {
    return prog.hasGraphemeSemantics() ? graphemeContext() : null;
  }

  private int getTextLength() {
    return text != null ? text.length() : textScanner.length();
  }

  /**
   * Returns whether DFA paths implement every instruction and boundary predicate in {@code prog}.
   */
  private static boolean dfaSupportsProgram(Prog prog) {
    return prog != null && !prog.hasGraphemeSemantics() && prog.numLoopRegs() == 0;
  }

  private boolean canUseForwardDfa() {
    return enginePathOptions().dfa() && dfaSupportsProgram(parentPattern.flatDfaProg());
  }

  private boolean canUseReverseDfa() {
    return enginePathOptions().dfa()
        && enginePathOptions().reverseDfa()
        && parentPattern.canUseReverseDfa()
        && dfaSupportsProgram(parentPattern.flatReverseDfaProg());
  }

  /**
   * Resets this matcher and then attempts to find the next subsequence of the input that matches
   * the pattern, starting at the specified index.
   *
   * @param start the index at which to start the search
   * @return {@code true} if a subsequence of the input starting at the given index matches this
   *     matcher's pattern
   * @throws IndexOutOfBoundsException if start is negative or greater than the length of the input
   */
  public boolean find(int start) {
    DiagnosticOperation operation = beginDiagnostics(MatchOperation.FIND);
    try {
      boolean matched = findFromImpl(start);
      completeDiagnostics(operation, matched ? 1 : 0);
      return matched;
    } catch (RuntimeException | Error e) {
      abortDiagnostics(operation);
      throw e;
    }
  }

  private boolean findFromImpl(int start) {
    int len = getTextLength();
    if (start < 0 || start > len) {
      throw new IndexOutOfBoundsException("start=" + start + ", length=" + len);
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
   * <p>This method does not reset this matcher. Matching starts on a call to {@link
   * Stream#findFirst()} or similar terminal operation, and continues from the current position.
   *
   * @return a sequential stream of match results
   */
  public Stream<MatchResult> results() {
    Spliterator<MatchResult> spliterator =
        new Spliterators.AbstractSpliterator<>(
            Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {
          @Override
          public boolean tryAdvance(Consumer<? super MatchResult> action) {
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
    if (cannotMatchLength(getTextLength() - searchFrom)) {
      return applyFailedMatchResult();
    }
    boolean regionActive = (regionStart != 0 || regionEnd != getTextLength());
    if (regionActive) {
      return doFindRegion(regionActive);
    }
    if (parentPattern.prog().anchorStart()) {
      if (searchFrom > 0) {
        return applyFailedMatchResult();
      }
      if (anchoredPrefixOrCharClassCannotMatch(0)) {
        MatchStrategy strategy =
            parentPattern.anchoredPrefix() != null
                ? MatchStrategy.LITERAL
                : MatchStrategy.CHARACTER_CLASS;
        diagnosticParticipation(strategy, StrategyRole.REJECT_PREFILTER);
        diagnosticBoundary(strategy);
        return applyFailedMatchResult();
      }
    }
    return parentPattern.preparedMatchRunner(false).find(this, false);
  }

  private boolean doFindRegion(boolean regionActive) {
    if (cannotMatchLength(regionEnd - searchFrom)) {
      return applyFailedMatchResult();
    }
    // --- Region setup: temporarily substitute text with the region substring ---
    String savedText = text;
    InputScanner savedTextScanner = textScanner;
    int savedSearchFrom = searchFrom;
    boolean regionSubstituted = false;

    try {
      if (regionActive && !anchoringBounds && regionTextAnchorCannotMatch()) {
        return applyFailedMatchResult();
      }
      if (needsFullTextRegionContext(regionActive, parentPattern.prog())) {
        return doFindTransparentRegion();
      }
      if (regionActive) {
        if (text != null) {
          text = savedText.substring(regionStart, regionEnd);
          textScanner = null;
        } else {
          textScanner = ((Utf8InputScanner) savedTextScanner).slice(regionStart, regionEnd);
        }
        searchFrom = Math.max(0, savedSearchFrom - regionStart);
        regionSubstituted = true;
      }
      return parentPattern.preparedMatchRunner(regionActive).find(this, regionActive);
    } finally {
      if (regionSubstituted) {
        resolveCapturesBeforeRestoringRegion();
        text = savedText;
        textScanner = savedTextScanner;
        searchFrom = savedSearchFrom;
        if (hasMatch) {
          for (int i = 0; i < groups.length; i++) {
            if (groups[i] >= 0) {
              groups[i] += regionStart;
            }
          }
        }
      }
      fullTextRegionContext = false;
    }
  }

  private boolean needsFullTextRegionContext(boolean regionActive, Prog prog) {
    return regionActive
        && ((!anchoringBounds && prog.hasTextAnchor())
            || transparentBounds
            || regionEndsInsideSurrogatePair()
            || prog.hasGraphemeSemantics());
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
    int textLength = getTextLength();
    if (regionEnd == textLength) {
      return true;
    }
    if (prog.hasGraphemeSemantics() && regionEndsInsideSurrogatePair()) {
      return true;
    }
    if (!prog.dollarAnchorEnd()) {
      return false;
    }
    int dollarEndPos = activeScanner().trailingLineTerminatorStart(prog.unixLines(), textLength);
    return dollarEndPos >= regionStart && dollarEndPos <= regionEnd;
  }

  private boolean regionEndsInsideSurrogatePair() {
    if (regionEnd <= 0 || regionEnd >= getTextLength()) {
      return false;
    }
    return !activeScanner().isCodePointBoundary(regionEnd);
  }

  private int graphemeConsumeEndPos(Prog prog, int endPos) {
    if (!prog.hasGraphemeClusterInstruction()
        || endPos <= 0
        || endPos >= getTextLength()
        || activeScanner().isCodePointBoundary(endPos)) {
      return endPos;
    }
    // \X may complete the scalar that starts inside the region; ordinary atoms and full-match
    // checks still use endPos.
    return endPos + 1;
  }

  private boolean matchesTransparentRegion() {
    capturesResolved = true;
    fullTextRegionContext = true;
    Prog prog = parentPattern.prog();
    int graphemeConsumeEndPos = graphemeConsumeEndPos(prog, regionEnd);
    int[] result =
        searchWithBitStateOrNfa(
            prog,
            activeScanner(),
            regionStart,
            regionStart,
            regionEnd,
            graphemeConsumeEndPos,
            true,
            false,
            true,
            prog.numCaptures(),
            false,
            this.groups);
    return applyFullMatchResult(result);
  }

  private boolean lookingAtTransparentRegion() {
    capturesResolved = true;
    fullTextRegionContext = true;
    Prog prog = parentPattern.prog();
    int graphemeConsumeEndPos = graphemeConsumeEndPos(prog, regionEnd);
    int[] result =
        searchWithBitStateOrNfa(
            prog,
            activeScanner(),
            regionStart,
            regionStart,
            regionEnd,
            graphemeConsumeEndPos,
            true,
            false,
            false,
            prog.numCaptures(),
            false,
            this.groups);
    return applyFullMatchResult(result);
  }

  private boolean doFindTransparentRegion() {
    if (searchFrom > regionEnd) {
      return applyFailedMatchResult();
    }
    capturesResolved = true;
    fullTextRegionContext = true;
    Prog prog = parentPattern.prog();
    if (prog.anchorStart() && searchFrom > regionStart) {
      return applyFailedMatchResult();
    }
    int graphemeConsumeEndPos = graphemeConsumeEndPos(prog, regionEnd);
    int[] result =
        searchWithBitStateOrNfa(
            prog,
            activeScanner(),
            searchFrom,
            regionEnd,
            regionEnd,
            graphemeConsumeEndPos,
            false,
            false,
            false,
            prog.numCaptures(),
            false,
            this.groups);
    return applyFullMatchResult(result);
  }

  /**
   * Core find logic. When {@code regionActive} is true, the DFA sandwich with deferred captures is
   * disabled because resolveCaptures() would run on the full text with different empty-width
   * assertion semantics than the substring the DFA saw.
   */
  private boolean doFindCore(boolean regionActive) {
    InputScanner scanner = activeScanner();
    if (searchFrom > scanner.length()) {
      return applyFailedMatchResult();
    }

    // Reset deferred-capture state; DFA sandwich path may set it to false.
    capturesResolved = true;

    Prog prog = parentPattern.prog();
    EnginePathOptions options = enginePathOptions();
    // Anchored start: if the pattern requires a match at the beginning of the text (e.g., ^
    // without MULTILINE, or \A), there can be no match starting after position 0 (or regionStart
    // when a region is active). Return false immediately to avoid the DFA matching at every
    // position because the compiler strips the anchor into prog.anchorStart().
    if (prog.anchorStart()) {
      if (searchFrom > 0) {
        return applyFailedMatchResult();
      }
      if (anchoredPrefixOrCharClassCannotMatch(searchFrom)) {
        MatchStrategy strat =
            parentPattern.anchoredPrefix() != null
                ? MatchStrategy.LITERAL
                : MatchStrategy.CHARACTER_CLASS;
        diagnosticParticipation(strat, StrategyRole.REJECT_PREFILTER);
        diagnosticBoundary(strat);
        return applyFailedMatchResult();
      }
    }

    RejectPrefilter rejectPrefilter = parentPattern.rejectPrefilter();
    if (rejectPrefilter != null && (text != null || scanner instanceof Utf8InputScanner)) {
      MatchStrategy rejectionStrategy =
          rejectPrefilter instanceof RejectPrefilter.Composite composite
              ? composite.rejectionStrategy(scanner, text, searchFrom, options)
              : rejectPrefilter.canReject(scanner, text, searchFrom, options)
                  ? rejectPrefilter.strategy()
                  : null;
      if (rejectionStrategy != null) {
        diagnosticParticipation(rejectionStrategy, StrategyRole.REJECT_PREFILTER);
        diagnosticBoundary(rejectionStrategy);
        return applyFailedMatchResult();
      }
    }

    // Prefix acceleration: if the pattern has a start accelerator (literal, fixed-offset,
    // character-class, or line-anchor), skip ahead to candidate match positions.
    int effectiveStart = searchFrom;
    boolean literalPrefixCandidateStart = false;
    if (options.startAcceleration() && !prog.anchorStart()) {
      if (scanner instanceof Utf8InputScanner utf8Scanner) {
        Utf8StartAccelerator accelerator = parentPattern.utf8StartAccelerator();
        if (accelerator != null) {
          AcceleratorPolicy policy = accelerator.policy();
          MatchStrategy strategy = policy.strategy();
          if (strategy != null) {
            diagnosticParticipation(strategy, StrategyRole.START_ACCELERATION);
          }
          int idx = accelerator.findCandidate(utf8Scanner, searchFrom);
          if (idx < 0) {
            if (strategy != null) {
              diagnosticBoundary(strategy);
            }
            return applyFailedMatchResult();
          }
          effectiveStart = idx;
          literalPrefixCandidateStart = policy.isExactMatchCandidate();
        }
      } else if (text != null) {
        StringStartAccelerator accelerator = parentPattern.stringStartAccelerator();
        if (accelerator != null) {
          AcceleratorPolicy policy = accelerator.policy();
          MatchStrategy strategy = policy.strategy();
          if (strategy != null) {
            diagnosticParticipation(strategy, StrategyRole.START_ACCELERATION);
          }
          int idx = accelerator.findCandidate(text, searchFrom, prog.unixLines());
          if (idx < 0) {
            if (strategy != null) {
              diagnosticBoundary(strategy);
            }
            return applyFailedMatchResult();
          }
          effectiveStart = idx;
          literalPrefixCandidateStart = policy.isExactMatchCandidate();
        }
      }
    }

    // Do not use OnePass as an unanchored find() producer. Even when a pattern is OnePass-eligible
    // for anchored matching, trying deterministic anchored matches at successive positions can
    // miss the leftmost start when a greedy leading repetition overlaps a later required literal.
    // The DFA/BitState/NFA pipeline below preserves leftmost find() semantics and remains linear.

    // Once callers have demonstrated that they consume inner captures, use the capture-aware
    // engine directly for bounded small inputs. This avoids finding group 0 with the DFA and then
    // replaying the same range through BitState on every successful find().
    if (shouldPreferCaptureEngine(prog, scanner)) {
      int[] result =
          searchWithBitStateOrNfa(
              prog,
              scanner,
              effectiveStart,
              scanner.length(),
              scanner.length(),
              scanner.length(),
              false,
              false,
              false,
              prog.numCaptures(),
              false,
              this.groups);
      return applyFullMatchResult(result);
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
    if (!regionActive
        && prog.anchorEnd()
        && scanner.length() >= MIN_REVERSE_FIRST_LEN
        && canUseReverseDfa()) {
      Dfa revDfa = reverseDfa();
      if (revDfa != null) {
        diagnosticParticipation(MatchStrategy.DFA, StrategyRole.REJECT_PREFILTER);
        int textLen = scanner.length();
        boolean budgetExceeded = false;

        // Try reverse DFA from end of text (anchored at end position).
        Dfa.SearchResult revResult =
            searchReverseDfa(revDfa, scanner, textLen, effectiveStart, true, true);
        int matchStart;
        boolean matchStartAmbiguous;
        if (revResult == null) {
          budgetExceeded = true;
          matchStart = -1;
          matchStartAmbiguous = false;
        } else {
          matchStart =
              revResult.matched() && revResult.pos() >= effectiveStart ? revResult.pos() : -1;
          matchStartAmbiguous = matchStart >= 0 && revResult.ambiguous();
        }

        // For $ (dollarAnchorEnd), also try before trailing line terminator. The $ anchor
        // can match before a trailing \n, \r\n, or other line terminator. The leftmost match
        // start may correspond to a match ending before the trailing terminator rather than
        // at textLen.
        if (!budgetExceeded && prog.dollarAnchorEnd()) {
          boolean ul = prog.unixLines();
          if (textLen > 0
              && (ul
                  ? scanner.codePointBefore(textLen) == '\n'
                  : Nfa.isLineTerminator(scanner.codePointBefore(textLen)))) {
            // For \r\n, the trailing terminator starts at textLen-2 (before \r), not
            // textLen-1 (between \r and \n). Skip the textLen-1 check for \r\n.
            boolean isAtomicCrLf =
                !ul
                    && textLen >= 2
                    && scanner.asciiAt(textLen - 2) == '\r'
                    && scanner.asciiAt(textLen - 1) == '\n';
            if (!isAtomicCrLf) {
              Dfa.SearchResult altRev =
                  searchReverseDfa(revDfa, scanner, textLen - 1, effectiveStart, true, true);
              if (altRev == null) {
                budgetExceeded = true;
              } else if (altRev.matched()
                  && altRev.pos() >= effectiveStart
                  && (matchStart < 0 || altRev.pos() < matchStart)) {
                matchStart = altRev.pos();
                matchStartAmbiguous = altRev.ambiguous();
              }
            }
            // For \r\n, try position before \r.
            if (!budgetExceeded && isAtomicCrLf) {
              Dfa.SearchResult altRev2 =
                  searchReverseDfa(revDfa, scanner, textLen - 2, effectiveStart, true, true);
              if (altRev2 == null) {
                budgetExceeded = true;
              } else if (altRev2.matched()
                  && altRev2.pos() >= effectiveStart
                  && (matchStart < 0 || altRev2.pos() < matchStart)) {
                matchStart = altRev2.pos();
                matchStartAmbiguous = altRev2.ambiguous();
              }
            }
          }
        }

        if (!budgetExceeded) {
          if (matchStart < 0) {
            // No match possible at end of text — fail immediately without forward scan.
            diagnosticBoundary(MatchStrategy.DFA);
            return applyFailedMatchResult();
          }
          if (matchStartAmbiguous) {
            // The reverse DFA can prove that a suffix match exists, but not which accepted
            // candidate supplies the leftmost start. Fall through to the normal engine path.
            diagnosticDecision(
                MatchStrategy.DFA,
                StrategyDisposition.BYPASSED,
                StrategyReason.AUTHORITATIVE_BOUNDS_REQUIRED);
          } else {
            // Reverse DFA found a match start. It proposes the left edge only; resolve the public
            // group(0) end with the exact engine anchored at that start so lazy alternatives and
            // dollar-before-terminator semantics remain leftmost-first.
            int[] exact =
                searchWithBitStateOrNfa(
                    prog, scanner, matchStart, matchStart, textLen, true, false, false, 1);
            if (exact != null) {
              int matchEnd = exact[1];
              return applyDeferredMatchResult(
                  matchStart, matchEnd, prog.numCaptures(), true, false);
            }
          }
        }
        if (budgetExceeded) {
          diagnosticDecision(
              MatchStrategy.DFA, StrategyDisposition.FALLBACK, StrategyReason.DFA_BUDGET_EXCEEDED);
        }
        // DFA budget exceeded or forward DFA disagreed — fall through to normal path.
      }
    }

    // Fast path: use cached DFA to check if a match exists in the remaining text.
    // Use longest=false for a quick existence check — this returns the earliest match end.
    Dfa.SearchResult fwdResult;
    if (!options.dfa() || !dfaSupportsProgram(parentPattern.flatDfaProg())) {
      fwdResult = null;
    } else {
      diagnosticParticipation(MatchStrategy.DFA, StrategyRole.REJECT_PREFILTER);
      fwdResult = searchForwardDfa(dfa(false), scanner, effectiveStart, prog.anchorStart(), false);
      if (fwdResult != null && !fwdResult.matched()) {
        diagnosticBoundary(MatchStrategy.DFA);
        return applyFailedMatchResult();
      }
      if (fwdResult == null) {
        diagnosticDecision(
            MatchStrategy.DFA, StrategyDisposition.FALLBACK, StrategyReason.DFA_BUDGET_EXCEEDED);
      }
    }

    // DFA sandwich (like RE2): the forward DFA found the earliest match end above. Now use the
    // reverse DFA to find the corresponding match start. For programs whose group-0 bounds are
    // reliable, that pair is authoritative: another anchored forward pass would only rediscover
    // the same earliest end. With lazy capture extraction, the sandwich returns group(0) without
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
    // When the DFA's match start is unreliable (patterns with lazy quantifiers, bounded repeats,
    // anchors inside quantifiers, or alternation), the reverse DFA result is only authoritative if
    // it proves the match starts at effectiveStart. Lazy quantifiers can make a non-leftmost match
    // end earlier, causing the DFA to find the wrong start. Alternation can have the same effect:
    // when alternatives match at different start positions with different endpoints, the forward
    // DFA's earliest-end result may come from a non-leftmost match, and the reverse DFA from that
    // wrong endpoint cannot find the leftmost start. In those cases, fall through to the
    // BitState/NFA fallback which correctly handles all semantics.
    //
    // When the accepted start is authoritative, the sandwich still treats group(0) as deferred for
    // programs whose end may be wrong; resolveCaptures() corrects the end position using the
    // submatch engine.
    // Skip when a region is active — deferred capture resolution runs on the full text but the
    // DFA ran on the region substring, causing empty-width assertion mismatches at boundaries.
    if (options.dfa()
        && !regionActive
        && parentPattern.dfaGroupZeroReliable()
        && fwdResult != null
        && fwdResult.pos() > effectiveStart) {
      int earlyEnd = fwdResult.pos();

      if (prog.anchorStart()) {
        // Anchored: match start is effectiveStart. Since it is start-anchored, the match end
        // is guaranteed to be earlyEnd. Avoid redundant third DFA search.
        diagnosticBoundary(MatchStrategy.DFA);
        return applyDeferredMatchResult(
            effectiveStart,
            earlyEnd,
            prog.numCaptures(),
            parentPattern.dfaGroupZeroReliable(),
            false);
      } else {
        if (literalPrefixCandidateStart) {
          // A literal prefix occurrence is a candidate match start, even when the prefix is
          // preceded by zero-width assertions. Verify that candidate directly; if it matches,
          // return it. Otherwise, fall through to the reverse DFA search below to find the correct
          // start.
          diagnosticParticipation(MatchStrategy.DFA, StrategyRole.CANDIDATE_VERIFICATION);
          Dfa.SearchResult fwdFirst =
              searchForwardDfa(dfa(false), scanner, effectiveStart, true, false);
          if (fwdFirst != null && fwdFirst.matched()) {
            int matchEnd = fwdFirst.pos();
            diagnosticBoundary(MatchStrategy.DFA);
            return applyDeferredMatchResult(
                effectiveStart,
                matchEnd,
                prog.numCaptures(),
                parentPattern.dfaGroupZeroReliable(),
                false);
          }
        }
        if (canUseReverseDfa()) {
          Dfa revDfa = reverseDfa();
          if (revDfa != null) {
            diagnosticParticipation(MatchStrategy.DFA, StrategyRole.CANDIDATE_VERIFICATION);
            // Step 2: Reverse DFA backward from earliest match end to find match start.
            Dfa.SearchResult revResult =
                searchReverseDfa(revDfa, scanner, earlyEnd, effectiveStart, true, true);
            if (revResult != null && revResult.matched()) {
              int matchStart = revResult.pos();
              boolean reliableStart = !revResult.ambiguous();

              // For dollarAnchorEnd patterns, the forward DFA's earlyEnd is always textLen
              // (it can't return early). But the correct leftmost match may end before the
              // trailing line terminator. The reverse DFA from textLen only finds starts for
              // matches ending AT textLen, potentially missing an earlier-starting match that
              // ends before the trailing line terminator. Check all dollar positions.
              if (prog.dollarAnchorEnd() && earlyEnd == scanner.length()) {
                int len = scanner.length();
                boolean ul = prog.unixLines();
                // Try position before trailing line terminator.
                if (len > 0
                    && (ul
                        ? scanner.codePointBefore(len) == '\n'
                        : Nfa.isLineTerminator(scanner.codePointBefore(len)))) {
                  // For \r\n, the trailing terminator starts at len-2 (before \r), not
                  // len-1 (between \r and \n). Skip the earlyEnd-1 check for \r\n.
                  boolean isAtomicCrLf =
                      !ul
                          && len >= 2
                          && scanner.asciiAt(len - 2) == '\r'
                          && scanner.asciiAt(len - 1) == '\n';
                  if (!isAtomicCrLf) {
                    Dfa.SearchResult altRevResult =
                        searchReverseDfa(revDfa, scanner, earlyEnd - 1, effectiveStart, true, true);
                    if (altRevResult != null
                        && altRevResult.matched()
                        && altRevResult.pos() < matchStart) {
                      matchStart = altRevResult.pos();
                      reliableStart = !altRevResult.ambiguous();
                    }
                  }
                  // For \r\n, try position before \r.
                  if (isAtomicCrLf && earlyEnd - 2 >= effectiveStart) {
                    Dfa.SearchResult altRevResult2 =
                        searchReverseDfa(revDfa, scanner, earlyEnd - 2, effectiveStart, true, true);
                    if (altRevResult2 != null
                        && altRevResult2.matched()
                        && altRevResult2.pos() < matchStart) {
                      matchStart = altRevResult2.pos();
                      reliableStart = !altRevResult2.ambiguous();
                    }
                  }
                }
              }

              if (!reliableStart) {
                diagnosticDecision(
                    MatchStrategy.DFA,
                    StrategyDisposition.BYPASSED,
                    StrategyReason.AUTHORITATIVE_BOUNDS_REQUIRED);
                matchStart = -1;
              }

              if (matchStart >= 0) {
                if (prog.hasTextAnchor()) {
                  Dfa.SearchResult exactEnd =
                      searchForwardDfa(dfa(false), scanner, matchStart, true, false);
                  if (exactEnd == null || !exactEnd.matched()) {
                    matchStart = -1;
                  } else {
                    diagnosticBoundary(MatchStrategy.DFA);
                    return applyDeferredMatchResult(
                        matchStart,
                        exactEnd.pos(),
                        prog.numCaptures(),
                        parentPattern.dfaGroupZeroReliable(),
                        false);
                  }
                }
              }
              if (matchStart >= 0) {
                diagnosticBoundary(MatchStrategy.DFA);
                return applyDeferredMatchResult(
                    matchStart,
                    earlyEnd,
                    prog.numCaptures(),
                    parentPattern.dfaGroupZeroReliable(),
                    false);
              }
            }
            // If reverse DFA bails out, fall through to full search.
          }
        }
      }
    }

    if (fwdResult != null && fwdResult.matched() && prog.numLoopRegs() > 0) {
      diagnosticDecision(
          MatchStrategy.DFA,
          StrategyDisposition.BYPASSED,
          StrategyReason.EXACT_NULLABLE_LOOP_SEMANTICS_REQUIRED);
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
            && !prog.hasGraphemeSemantics()
            && options.lazyCaptureExtraction()
            && prog.numCaptures() <= MAX_LAZY_FALLBACK_SUBMATCHES;
    int nsubmatch = lazyFallbackCaptures ? 1 : prog.numCaptures();
    int[] result =
        searchWithBitStateOrNfa(
            prog,
            scanner,
            effectiveStart,
            scanner.length(),
            scanner.length(),
            scanner.length(),
            false,
            false,
            false,
            nsubmatch,
            false,
            this.groups);
    if (result == null) {
      return applyFailedMatchResult();
    }
    if (!lazyFallbackCaptures || prog.numCaptures() <= 1) {
      return applyFullMatchResult(result);
    } else {
      return applyDeferredMatchResult(result[0], result[1], prog.numCaptures(), true, false);
    }
  }

  private boolean matchUtf8KeywordAlternationAt(
      Pattern.KeywordAlternation keywordAlternation, int pos, int ncap) {
    InputScanner scanner = activeScanner();
    if (pos >= scanner.length()) {
      return applyFailedMatchResult();
    }
    long match = keywordAlternation.matchAt(scanner, pos);
    if (match < 0) {
      return applyFailedMatchResult();
    }
    int keywordStart = Pattern.KeywordAlternation.matchStart(match);
    int keywordEnd = Pattern.KeywordAlternation.matchEnd(match);
    int[] keywordGroups = new int[2 * ncap];
    Arrays.fill(keywordGroups, -1);
    keywordGroups[0] = keywordAlternation.greedyWholeInput ? pos : keywordStart;
    keywordGroups[1] = keywordAlternation.greedyWholeInput ? scanner.length() : keywordEnd;
    if (keywordAlternation.captureGroup > 0) {
      int group = keywordAlternation.captureGroup;
      keywordGroups[2 * group] = keywordStart;
      keywordGroups[2 * group + 1] = keywordEnd;
    }
    return applyFullMatchResult(keywordGroups);
  }

  private boolean matchKeywordAlternationAt(
      Pattern.KeywordAlternation keywordAlternation, int pos, int ncap) {
    if (pos >= text.length()) {
      return applyFailedMatchResult();
    }
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.record();
    }
    char ch = text.charAt(pos);
    if (ch < 128
        && keywordAlternation.firstAsciiTable[Ascii.toLowerCase(ch)]
        && isWordBoundaryAt(pos, keywordAlternation.unicodeWordBoundary)) {
      for (String keyword : keywordAlternation.keywords) {
        int end = pos + keyword.length();
        if (end <= text.length()
            && Ascii.regionMatchesIgnoreCase(text, pos, keyword, keyword.length())
            && isWordBoundaryAt(end, keywordAlternation.unicodeWordBoundary)) {
          int[] keywordGroups = new int[2 * ncap];
          Arrays.fill(keywordGroups, -1);
          keywordGroups[0] = pos;
          keywordGroups[1] = keywordAlternation.greedyWholeInput ? text.length() : end;
          if (keywordAlternation.captureGroup > 0) {
            int group = keywordAlternation.captureGroup;
            keywordGroups[2 * group] = pos;
            keywordGroups[2 * group + 1] = end;
          }
          return applyFullMatchResult(keywordGroups);
        }
      }
    }
    return applyFailedMatchResult();
  }

  private boolean findUtf8KeywordAlternation(
      Pattern.KeywordAlternation keywordAlternation, int startPos, int ncap) {
    InputScanner scanner = activeScanner();
    int matchStart = Math.max(0, startPos);
    long match = keywordAlternation.find(scanner, matchStart);
    if (match < 0) {
      return applyFailedMatchResult();
    }
    int keywordStart = Pattern.KeywordAlternation.matchStart(match);
    int keywordEnd = Pattern.KeywordAlternation.matchEnd(match);
    int[] keywordGroups = new int[2 * ncap];
    Arrays.fill(keywordGroups, -1);
    keywordGroups[0] = keywordAlternation.greedyWholeInput ? matchStart : keywordStart;
    keywordGroups[1] = keywordAlternation.greedyWholeInput ? scanner.length() : keywordEnd;
    if (keywordAlternation.captureGroup > 0) {
      int group = keywordAlternation.captureGroup;
      keywordGroups[2 * group] = keywordStart;
      keywordGroups[2 * group + 1] = keywordEnd;
    }
    return applyFullMatchResult(keywordGroups);
  }

  private boolean findKeywordAlternation(
      Pattern.KeywordAlternation keywordAlternation, int startPos, int ncap) {
    if (keywordAlternation.greedyWholeInput) {
      return findGreedyWholeInputKeywordAlternation(keywordAlternation, startPos, ncap);
    }
    for (int i = Math.max(0, startPos); i < text.length(); i++) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      char ch = text.charAt(i);
      if (ch < 128
          && keywordAlternation.firstAsciiTable[Ascii.toLowerCase(ch)]
          && isWordBoundaryAt(i, keywordAlternation.unicodeWordBoundary)) {
        for (String keyword : keywordAlternation.keywords) {
          int end = i + keyword.length();
          if (end <= text.length()
              && Ascii.regionMatchesIgnoreCase(text, i, keyword, keyword.length())
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
            return applyFullMatchResult(keywordGroups);
          }
        }
      }
      int cp = text.codePointAt(i);
      i += Character.charCount(cp) - 1;
    }
    return applyFailedMatchResult();
  }

  private boolean findGreedyWholeInputKeywordAlternation(
      Pattern.KeywordAlternation keywordAlternation, int startPos, int ncap) {
    int matchStart = Math.max(0, startPos);
    for (int i = text.length() - 1; i >= matchStart; i--) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      char ch = text.charAt(i);
      if (ch < 128
          && keywordAlternation.firstAsciiTable[Ascii.toLowerCase(ch)]
          && isWordBoundaryAt(i, keywordAlternation.unicodeWordBoundary)) {
        for (String keyword : keywordAlternation.keywords) {
          int end = i + keyword.length();
          if (end <= text.length()
              && Ascii.regionMatchesIgnoreCase(text, i, keyword, keyword.length())
              && isWordBoundaryAt(end, keywordAlternation.unicodeWordBoundary)) {
            int[] keywordGroups = new int[2 * ncap];
            Arrays.fill(keywordGroups, -1);
            keywordGroups[0] = matchStart;
            keywordGroups[1] = text.length();
            if (keywordAlternation.captureGroup > 0) {
              int group = keywordAlternation.captureGroup;
              keywordGroups[2 * group] = i;
              keywordGroups[2 * group + 1] = end;
            }
            return applyFullMatchResult(keywordGroups);
          }
        }
      }
    }
    return applyFailedMatchResult();
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

  /** ASCII case-insensitive indexOf for Java's default CASE_INSENSITIVE semantics. */
  static int indexOfIgnoreCase(String text, String prefix, int fromIndex) {
    int prefixLen = prefix.length();
    if (prefixLen == 0) {
      return Math.min(Math.max(0, fromIndex), text.length());
    }
    if (prefixLen == 1) {
      return Ascii.indexOfIgnoreCase(text, prefix.charAt(0), fromIndex);
    }
    int anchorOffset = RarityOracle.rarestAsciiOffset(prefix, prefixLen);
    char anchor = prefix.charAt(anchorOffset);
    char low = Ascii.toLowerCase(anchor);
    char high = Ascii.toUpperCase(anchor);
    int[] failure = Ascii.ignoreCaseFailure(prefix);
    return indexOfIgnoreCase(text, prefix, failure, anchorOffset, low, high, fromIndex);
  }

  static int indexOfIgnoreCase(
      String text,
      String prefix,
      int[] failure,
      int anchorOffset,
      char low,
      char high,
      int fromIndex) {
    int prefixLen = prefix.length();
    if (prefixLen == 0) {
      return Math.min(Math.max(0, fromIndex), text.length());
    }
    if (prefixLen == 1) {
      return Ascii.indexOfIgnoreCase(text, low, high, fromIndex);
    }
    int length = text.length();
    int pos = Math.max(0, fromIndex);
    long verificationWork = 0;
    long workLimit = -1;
    boolean hasLow = true;
    boolean hasHigh = (low != high);
    int nextLow = -1;
    int nextHigh = -1;
    int startFrom = anchorOffset == 0 ? 1 : 0;

    while (pos <= length - prefixLen) {
      // Short scalar search for nearby anchor (handles whitespace / short delimiters without
      // indexOf overhead)
      int scalarLimit = Math.min(length - prefixLen + 1, pos + 32);
      for (; pos < scalarLimit; pos++) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        char c = text.charAt(pos + anchorOffset);
        if (hasHigh ? (c | 0x20) == low : c == low) {
          if (Ascii.regionMatchesIgnoreCase(text, pos, prefix, startFrom, prefixLen)) {
            return pos;
          }
          verificationWork += prefixLen;
          if (workLimit < 0) {
            workLimit = WorkLimit.forRemaining(length - pos);
          }
          if (WorkLimit.isExhausted(verificationWork, workLimit)) {
            return Ascii.indexOfLinearIgnoreCase(text, prefix, failure, pos + 1);
          }
        }
      }

      if (pos > length - prefixLen) {
        return -1;
      }

      if (workLimit < 0) {
        workLimit = WorkLimit.forRemaining(length - pos);
      }

      int searchFrom = pos + anchorOffset;
      if (nextLow >= 0 && nextLow < searchFrom) {
        nextLow = -1;
      }
      if (nextHigh >= 0 && nextHigh < searchFrom) {
        nextHigh = -1;
      }
      if (hasLow && nextLow < 0) {
        nextLow = text.indexOf(low, searchFrom);
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(nextLow < 0 ? length - searchFrom : nextLow - searchFrom + 1);
        }
        if (nextLow < 0) {
          hasLow = false;
        }
      }

      if (hasHigh && nextHigh < 0 && nextLow != searchFrom) {
        nextHigh = text.indexOf(high, searchFrom);
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(nextHigh < 0 ? length - searchFrom : nextHigh - searchFrom + 1);
        }
        if (nextHigh < 0) {
          hasHigh = false;
        }
      }

      int nextAnchor = Ascii.minNonNegative(nextLow, nextHigh);
      if (nextAnchor < 0) {
        return -1;
      }
      if (nextLow == nextAnchor) {
        nextLow = -1;
      }
      if (nextHigh == nextAnchor) {
        nextHigh = -1;
      }
      int candidatePos = nextAnchor - anchorOffset;
      if (WorkLimit.candidateInBounds(candidatePos, pos, length, prefixLen)) {
        if (Ascii.regionMatchesIgnoreCase(text, candidatePos, prefix, prefixLen)) {
          return candidatePos;
        }
        verificationWork += prefixLen;
        if (WorkLimit.isExhausted(verificationWork, workLimit)) {
          return Ascii.indexOfLinearIgnoreCase(text, prefix, failure, candidatePos + 1);
        }
      }
      pos = candidatePos + 1;
    }
    return -1;
  }

  /**
   * Tries BitState first (for small texts), falls back to NFA. This is the final capture-extraction
   * step after DFA/OnePass have been tried or are not applicable.
   *
   * @param prog the compiled program
   * @param text the full input scanner
   * @param startPos the input index at which to begin searching
   * @param searchLimit upper bound on positions where new candidate starts are tried. Use {@code
   *     text.length()} for unbounded search.
   * @param endPos logical match end and ordinary-atom consumption limit. Use {@code text.length()}
   *     for unbounded search.
   * @param anchored whether the search is anchored at {@code startPos}
   * @param longest whether to find the longest match
   * @param endMatch whether the match must extend to {@code endPos}
   * @param nsubmatch number of submatch groups to track (including group 0)
   * @return submatch positions relative to {@code text}, or null if no match
   */
  private int[] searchWithBitStateOrNfa(
      Prog prog,
      InputScanner text,
      int startPos,
      int searchLimit,
      int endPos,
      boolean anchored,
      boolean longest,
      boolean endMatch,
      int nsubmatch) {
    return searchWithBitStateOrNfa(
        prog,
        text,
        startPos,
        searchLimit,
        endPos,
        endPos,
        anchored,
        longest,
        endMatch,
        nsubmatch,
        false,
        null);
  }

  private int[] searchWithBitStateOrNfa(
      Prog prog,
      String text,
      int startPos,
      int searchLimit,
      int endPos,
      int graphemeConsumeEndPos,
      boolean anchored,
      boolean longest,
      boolean endMatch,
      int nsubmatch,
      boolean preserveOuterEmptyContext,
      int[] reuseGroups) {
    return searchWithBitStateOrNfa(
        prog,
        new StringInputScanner(text),
        startPos,
        searchLimit,
        endPos,
        graphemeConsumeEndPos,
        anchored,
        longest,
        endMatch,
        nsubmatch,
        preserveOuterEmptyContext,
        reuseGroups);
  }

  @SuppressWarnings("ReferenceEquality")
  private int[] searchWithBitStateOrNfa(
      Prog prog,
      InputScanner text,
      int startPos,
      int searchLimit,
      int endPos,
      int graphemeConsumeEndPos,
      boolean anchored,
      boolean longest,
      boolean endMatch,
      int nsubmatch,
      boolean preserveOuterEmptyContext,
      int[] reuseGroups) {
    // Try BitState if the full text is small enough for the visited bitmap. BitState is an
    // optimization; if capture-priority backtracking exceeds its work budget, fall back to the
    // Pike NFA below.
    int maxBitStateLen = BitState.maxTextSize(prog);
    boolean canUseBitState =
        enginePathOptions().bitState()
            && !fullTextRegionContext
            && !(prog.hasGraphemeSemantics() && !anchored)
            && !prog.hasGraphemeSemantics();
    int searchRange = endPos - startPos;
    if (canUseBitState && maxBitStateLen >= 0 && searchRange <= maxBitStateLen) {
      boolean anchoredEffective = anchored || prog.anchorStart();
      boolean endMatchEffective = endMatch || prog.anchorEnd();
      int ncap = 2 * Math.max(nsubmatch, 1);
      // Borrow from Pattern's thread-local cache on first use.
      if (cachedBitState == null && !bitStateBorrowed) {
        cachedBitState = parentPattern.borrowBitState();
        bitStateBorrowed = true;
      }
      BitState bs =
          BitState.getOrCreate(
              cachedBitState, prog, text, startPos, endPos, ncap, longest, endMatchEffective);
      int[] destBuf =
          reuseGroups != null && reuseGroups.length >= ncap ? reuseGroups : bitStateResult;
      if (destBuf == null || destBuf.length < ncap) {
        destBuf = new int[ncap];
      }
      if (destBuf != reuseGroups) {
        bitStateResult = destBuf;
      }
      int[] result = bs.doSearch(startPos, searchLimit, anchoredEffective, destBuf);
      cachedBitState = bs;
      // Return to Pattern's cache for reuse by future Matchers.
      parentPattern.returnBitState(bs);
      if (!bs.budgetExceeded()) {
        diagnosticExact(MatchStrategy.BIT_STATE);
        // BitState is a complete engine — if it searched and found no match, NFA won't either.
        if (result == null) {}
        return result;
      }
      diagnosticDecision(
          MatchStrategy.BIT_STATE,
          StrategyDisposition.FALLBACK,
          StrategyReason.WORK_BUDGET_EXCEEDED);
    } else if (canUseBitState && maxBitStateLen >= 0 && searchRange > maxBitStateLen) {
      diagnosticDecision(
          MatchStrategy.BIT_STATE, StrategyDisposition.BYPASSED, StrategyReason.INPUT_TOO_LARGE);
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
    diagnosticExact(MatchStrategy.NFA);
    return searchNfa(
        prog,
        startPos,
        searchLimit,
        endPos,
        graphemeConsumeEndPos,
        nsubmatch,
        nfaAnchor,
        nfaKind,
        preserveOuterEmptyContext,
        reuseGroups);
  }

  private int[] searchNfa(
      Prog prog,
      int startPos,
      int searchLimit,
      int endPos,
      int graphemeConsumeEndPos,
      int nsubmatch,
      Nfa.Anchor nfaAnchor,
      Nfa.MatchKind nfaKind,
      boolean preserveOuterEmptyContext,
      int[] reuseGroups) {
    InputScanner scanner = activeScanner();
    if (prog.start() == 0) {
      return null;
    }
    boolean anchored = (nfaAnchor == Nfa.Anchor.ANCHORED) || prog.anchorStart();
    boolean longestMode = (nfaKind != Nfa.MatchKind.FIRST_MATCH);
    boolean endmatch = prog.anchorEnd();

    if (nfaKind == Nfa.MatchKind.FULL_MATCH) {
      anchored = true;
      endmatch = true;
      if (nsubmatch == 0) {
        nsubmatch = 1;
      }
    }

    // We always need at least capture[0..1] to track the match boundaries.
    int ncapture = 2 * Math.max(nsubmatch, 1);

    boolean graphemeRegionContext = fullTextRegionContext && prog.hasGraphemeSemantics();
    int consumeRegionStart = graphemeRegionContext && !transparentBounds ? regionStart : 0;

    boolean useOuterEmptyContext = fullTextRegionContext || preserveOuterEmptyContext;
    int emptyContextEnd = preserveOuterEmptyContext ? regionEnd : endPos;
    int boundaryRegionStart = useOuterEmptyContext && !transparentBounds ? regionStart : 0;
    int boundaryEndPos =
        useOuterEmptyContext && !transparentBounds ? emptyContextEnd : scanner.length();
    int anchorEndPos =
        useOuterEmptyContext && !anchoringBounds && prog.anchorEnd()
            ? scanner.length()
            : emptyContextEnd;
    int emptyAnchorStartPos = useOuterEmptyContext && anchoringBounds ? regionStart : 0;
    int emptyAnchorEndPos =
        useOuterEmptyContext && !anchoringBounds ? scanner.length() : emptyContextEnd;

    EngineContext context =
        EngineContext.create(
            prog,
            scanner,
            startPos,
            searchLimit,
            endPos,
            graphemeConsumeEndPos,
            consumeRegionStart,
            boundaryRegionStart,
            boundaryEndPos,
            anchorEndPos,
            emptyAnchorStartPos,
            emptyAnchorEndPos,
            graphemeContextFor(prog));

    if (cachedNfa == null && !nfaBorrowed) {
      cachedNfa = parentPattern.borrowNfa();
      nfaBorrowed = true;
    }

    Nfa nfa = Nfa.getOrCreate(cachedNfa, prog, context, ncapture, longestMode, endmatch);
    int[] result = nfa.runSearch(anchored, nfaKind, nsubmatch, endPos, reuseGroups);
    cachedNfa = nfa;
    parentPattern.returnNfa(nfa);

    return result;
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
   * Returns the input subsequence matched by the previous match (equivalent to {@code group(0)}).
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
   * Returns the input subsequence captured by the given group during the previous match operation.
   *
   * @param group the index of a capturing group in this matcher's pattern
   * @return the subsequence captured by the group, or {@code null} if the group did not participate
   *     in the match
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
      throw new IllegalArgumentException("No group with name <" + name + ">");
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
        if (!capturesResolved) {
          recordInnerCaptureDemand();
        } else {
          eagerFallbackCaptures = true;
        }
      }
      resolveCaptures();
    }
    return groups[2 * group];
  }

  /**
   * Returns the offset after the last character of the previous match (equivalent to {@code
   * end(0)}).
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
        if (!capturesResolved) {
          recordInnerCaptureDemand();
        } else {
          eagerFallbackCaptures = true;
        }
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
   * Returns {@code true} if this matcher has a match.
   *
   * @return {@code true} if this matcher has a match, {@code false} otherwise
   * @since 20
   */
  @Override
  public boolean hasMatch() {
    return hasMatch;
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
   * Replaces the first subsequence of the input that matches the pattern with the given replacement
   * string.
   *
   * @param replacement the replacement string
   * @return the string with the first match replaced
   */
  public String replaceFirst(String replacement) {
    DiagnosticOperation operation = beginDiagnostics(MatchOperation.REPLACE_FIRST);
    try {
      String result = replaceFirstImpl(replacement);
      completeDiagnostics(operation, diagnosticMatchCount());
      return result;
    } catch (RuntimeException | Error e) {
      abortDiagnostics(operation);
      throw e;
    }
  }

  private String replaceFirstImpl(String replacement) {
    return replaceImpl(replacement, 1);
  }

  private String replaceImpl(String replacement, int limit) {
    Objects.requireNonNull(replacement, "replacement");
    reset();
    LazyTemplate template = new LazyTemplate(replacement, groupCount());
    String literalResult = literalReplaceFastPath(template, limit);
    if (literalResult != null) {
      return literalResult;
    }

    String fastResult = charClassReplaceFastPath(template, limit);
    if (fastResult != null) {
      return fastResult;
    }

    String anchoredOnePassResult = replaceAnchoredOnePass(template, limit > 1);
    if (anchoredOnePassResult != null) {
      return anchoredOnePassResult;
    }

    String result = replaceDfaOptimized(template, limit);
    if (result != null) {
      return result;
    }

    if (replacement.indexOf('$') >= 0) {
      eagerFallbackCaptures = true;
    }

    if (!find()) {
      return text;
    }
    if (template.needsCaptures()) {
      parentPattern.recordInnerCaptureAccess();
    }
    diagnosticIncrementMatchCount();
    StringBuilder sb = new StringBuilder(text.length());
    if (limit == 1) {
      appendReplacement(sb, replacement);
      appendTail(sb);
      return sb.toString();
    }
    ReplacementSegment[] compiledTemplate = template.get();
    boolean needsCaptures = template.needsCaptures();
    do {
      if (needsCaptures && !groupZeroResolved) {
        resolveCaptures();
      }
      int matchStart = groupZeroResolved ? groups[0] : deferredMatchStart;
      int matchEnd = groupZeroResolved ? groups[1] : deferredMatchEnd;
      sb.append(text, appendPos, matchStart);
      if (needsCaptures) {
        applyReplacementTemplate(sb, compiledTemplate);
      } else {
        groups[0] = matchStart;
        groups[1] = matchEnd;
        applyReplacementTemplate(sb, compiledTemplate);
      }
      appendPos = matchEnd;
    } while (findAndRecordReplacementMatch());
    appendTail(sb);
    return sb.toString();
  }

  private String replaceAnchoredOnePass(LazyTemplate template, boolean replaceAll) {
    boolean regionActive = (regionStart != 0 || regionEnd != text.length());
    boolean isFullAnchored =
        parentPattern.prog().anchorStart()
            && (parentPattern.prog().anchorEnd() || parentPattern.prog().dollarAnchorEnd());

    boolean requiresCaptures = template.needsCaptures();
    if (!enginePathOptions().onePass()
        || !parentPattern.canOnePassFind()
        || !isFullAnchored
        || text.length() > onePassTextLimit(requiresCaptures)
        || regionActive
        || searchFrom != 0) {
      return null;
    }

    int numCaptures = parentPattern.prog().numCaptures();
    if (groups == null || groups.length < 2 * numCaptures) {
      groups = new int[2 * numCaptures];
    }
    Arrays.fill(groups, -1);

    diagnosticParticipation(MatchStrategy.ONE_PASS, StrategyRole.CANDIDATE_VERIFICATION);
    if (parentPattern.numGroups() > 0) {
      diagnosticCapture(MatchStrategy.ONE_PASS);
    }

    InputScanner scanner = activeScanner();
    int[] result =
        parentPattern.onePass().search(scanner, 0, text.length(), false, numCaptures, groups);

    if (result == null) {
      diagnosticBoundary(MatchStrategy.ONE_PASS);
      applyFailedMatchResult();
      return text;
    }
    diagnosticBoundary(MatchStrategy.ONE_PASS);
    diagnosticIncrementMatchCount();

    int matchStart = result[0];
    int matchEnd = result[1];
    applyFullMatchResult(result);

    StringBuilder sb = new StringBuilder(text.length());
    sb.append(text, 0, matchStart);
    applyReplacementTemplate(sb, template.get());
    sb.append(text, matchEnd, text.length());
    appendPos = matchEnd;
    if (replaceAll) {
      applyFailedMatchResult();
      if (matchStart == matchEnd) {
        searchFrom = matchEnd < regionEnd ? matchEnd + 1 : regionEnd + 1;
        findExhaustedAfterTerminalEmptyMatch = matchEnd >= regionEnd;
      } else {
        searchFrom = matchEnd;
      }
    }
    return sb.toString();
  }

  private String replaceDfaOptimized(LazyTemplate template, int limit) {
    boolean regionActive = (regionStart != 0 || regionEnd != text.length());
    if (!canUseForwardDfa()
        || !parentPattern.dfaGroupZeroReliable()
        // dollarAnchorEnd is safe if start-anchored because we skip the reverse DFA scan.
        || (parentPattern.prog().dollarAnchorEnd() && !parentPattern.prog().anchorStart())
        || parentPattern.literalMatch() != null
        || parentPattern.hasNullableAlternation()
        || regionActive) {
      return null;
    }
    diagnosticParticipation(MatchStrategy.DFA, StrategyRole.CANDIDATE_VERIFICATION);

    boolean isStartAnchored = parentPattern.prog().anchorStart();
    String prefix = parentPattern.prefix();
    boolean foldCase = parentPattern.prefixFoldCase();
    boolean hasStartAcceleration =
        enginePathOptions().startAcceleration() && prefix != null && !isStartAnchored;
    int startPos = searchFrom;
    if (hasStartAcceleration) {
      diagnosticParticipation(MatchStrategy.LITERAL, StrategyRole.START_ACCELERATION);
      int firstIdx =
          foldCase ? indexOfIgnoreCase(text, prefix, searchFrom) : text.indexOf(prefix, searchFrom);
      if (firstIdx < 0) {
        diagnosticBoundary(MatchStrategy.LITERAL);
        return text;
      }
      startPos = firstIdx;
    }

    Dfa fwdDfa = dfa(false);
    if (fwdDfa == null) {
      return null;
    }

    DfaMatchCursor cursor = new DfaMatchCursor(startPos);
    int matchResult =
        findNextDfaMatch(fwdDfa, isStartAnchored, prefix, foldCase, hasStartAcceleration, cursor);

    if (matchResult < 0) {
      return null;
    }
    if (matchResult == 0) {
      diagnosticBoundary(MatchStrategy.DFA);
      return text;
    }
    diagnosticBoundary(MatchStrategy.DFA);

    Prog prog = parentPattern.prog();
    int numCaptures = prog.numCaptures();
    if (groups == null || groups.length < 2 * numCaptures) {
      groups = new int[2 * numCaptures];
    }
    applyDeferredMatchResult(matchOffsets[0], matchOffsets[1], numCaptures, true, false);

    ReplacementSegment[] compiledTemplate = template.get();

    boolean needsCaptures = template.needsCaptures();
    if (needsCaptures) {
      parentPattern.recordInnerCaptureAccess();
    }
    boolean useOnePass =
        needsCaptures
            && enginePathOptions().onePass()
            && parentPattern.canOnePassSubmatch()
            && !parentPattern.hasNullableAlternation();

    int textLen = text.length();
    StringBuilder sb = new StringBuilder(textLen);
    int builderAppendPos = searchFrom;
    int firstMatchStart = -1;
    int firstMatchEnd = -1;
    int matchesFound = 0;

    while (matchResult == 1 && matchesFound < limit) {
      int matchStart = matchOffsets[0];
      int matchEnd = matchOffsets[1];
      if (matchesFound == 0) {
        firstMatchStart = matchStart;
        firstMatchEnd = matchEnd;
      }

      groups[0] = matchStart;
      groups[1] = matchEnd;
      if (needsCaptures) {
        Arrays.fill(groups, 2, groups.length, -1);
        int[] resultGroups;
        if (useOnePass) {
          diagnosticCapture(MatchStrategy.ONE_PASS);
          resultGroups =
              parentPattern
                  .onePass()
                  .search(text, matchStart, matchEnd, false, numCaptures, groups);
        } else {
          boolean savedCaptureSearch = diagnosticCaptureSearch;
          diagnosticCaptureSearch = true;
          try {
            resultGroups =
                searchWithBitStateOrNfa(
                    prog,
                    text,
                    matchStart,
                    matchEnd,
                    matchEnd,
                    matchEnd,
                    true,
                    false,
                    false,
                    numCaptures,
                    true,
                    groups);
          } finally {
            diagnosticCaptureSearch = savedCaptureSearch;
          }
        }
        if (resultGroups != null) {
          System.arraycopy(resultGroups, 0, groups, 0, groups.length);
        }
        capturesResolved = true;
        groupZeroResolved = true;
      }

      sb.append(text, builderAppendPos, matchStart);
      this.resultStatus = ResultStatus.MATCHED;
      applyReplacementTemplate(sb, compiledTemplate);
      builderAppendPos = matchEnd;

      cursor.pos = matchEnd;
      matchesFound++;
      if (matchStart == matchEnd) {
        if (cursor.pos >= regionEnd) {
          findExhaustedAfterTerminalEmptyMatch = true;
          break;
        }
        cursor.pos++;
      }
      if (matchesFound < limit) {
        matchResult =
            findNextDfaMatch(
                fwdDfa, isStartAnchored, prefix, foldCase, hasStartAcceleration, cursor);
        if (matchResult < 0) {
          return null;
        }
      }
    }
    sb.append(text, builderAppendPos, textLen);

    if (limit == 1) {
      applyDeferredMatchResult(
          firstMatchStart, firstMatchEnd, parentPattern.prog().numCaptures(), true, false);
      this.appendPos = firstMatchEnd;
    } else {
      applyFailedMatchResult();
      this.appendPos = textLen;
      this.searchFrom = textLen;
    }
    diagnosticBoundary(MatchStrategy.DFA);
    DiagnosticAccumulator accumulator = diagnosticsAccumulator();
    if (accumulator != null) {
      accumulator.matchCount(matchesFound);
    }
    return sb.toString();
  }

  private int findNextDfaMatch(
      Dfa fwdDfa,
      boolean isStartAnchored,
      String prefix,
      boolean foldCase,
      boolean hasStartAcceleration,
      DfaMatchCursor cursor) {
    InputScanner scanner = activeScanner();
    int textLen = scanner.length();
    int pos = cursor.pos;

    if (matchOffsets == null) {
      matchOffsets = new int[2];
    }

    while (pos <= textLen) {
      if (parentPattern.prog().anchorStart() && pos > 0) {
        break;
      }
      if (hasStartAcceleration && pos < textLen && text != null) {
        int idx = foldCase ? indexOfIgnoreCase(text, prefix, pos) : text.indexOf(prefix, pos);
        if (idx < 0) {
          break;
        }
        pos = idx;
      }

      Dfa.SearchResult fwdResult = searchForwardDfa(fwdDfa, scanner, pos, isStartAnchored, false);
      if (fwdResult == null || !fwdResult.matched()) {
        break;
      }

      int earlyEnd = fwdResult.pos();
      if (earlyEnd <= pos) {
        matchOffsets[0] = pos;
        matchOffsets[1] = pos;
        return 1;
      }

      int matchStart;
      int matchEnd;
      if (isStartAnchored) {
        matchStart = pos;
        matchEnd = earlyEnd;
      } else if (hasStartAcceleration) {
        Dfa.SearchResult fwdFirst = searchForwardDfa(fwdDfa, scanner, pos, true, false);
        if (fwdFirst != null && fwdFirst.matched()) {
          matchStart = pos;
          matchEnd = fwdFirst.pos();
        } else {
          if (cursor.revDfa == null) {
            if (!canUseReverseDfa()) {
              return -1;
            }
            cursor.revDfa = reverseDfa();
            if (cursor.revDfa == null) {
              return -1;
            }
          }
          Dfa.SearchResult revResult =
              searchReverseDfa(cursor.revDfa, scanner, earlyEnd, pos, true, true);
          if (revResult == null || !revResult.matched() || revResult.ambiguous()) {
            if (revResult != null && revResult.ambiguous()) {
              diagnosticDecision(
                  MatchStrategy.DFA,
                  StrategyDisposition.BYPASSED,
                  StrategyReason.AUTHORITATIVE_BOUNDS_REQUIRED);
            }
            return -1;
          }
          matchStart = revResult.pos();
          matchEnd = earlyEnd;
        }
      } else {
        if (cursor.revDfa == null) {
          if (!canUseReverseDfa()) {
            return -1;
          }
          cursor.revDfa = reverseDfa();
          if (cursor.revDfa == null) {
            return -1;
          }
        }
        Dfa.SearchResult revResult =
            searchReverseDfa(cursor.revDfa, scanner, earlyEnd, pos, true, true);
        if (revResult == null || !revResult.matched() || revResult.ambiguous()) {
          if (revResult != null && revResult.ambiguous()) {
            diagnosticDecision(
                MatchStrategy.DFA,
                StrategyDisposition.BYPASSED,
                StrategyReason.AUTHORITATIVE_BOUNDS_REQUIRED);
          }
          return -1;
        }
        matchStart = revResult.pos();
        matchEnd = earlyEnd;
      }

      matchOffsets[0] = matchStart;
      matchOffsets[1] = matchEnd;
      return 1;
    }
    return 0;
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
    DiagnosticOperation operation = beginDiagnostics(MatchOperation.REPLACE_FIRST);
    try {
      String result = replaceFirstFunctionImpl(replacer);
      completeDiagnostics(operation, diagnosticMatchCount());
      return result;
    } catch (RuntimeException | Error e) {
      abortDiagnostics(operation);
      throw e;
    }
  }

  private String replaceFirstFunctionImpl(Function<MatchResult, String> replacer) {
    requireNonNull(replacer, "replacer");
    reset();
    if (!find()) {
      return text;
    }
    diagnosticIncrementMatchCount();
    StringBuilder sb = new StringBuilder(text.length());
    int expectedModCount = modCount;
    String replacement = Objects.requireNonNull(replacer.apply(toMatchResult()));
    checkConcurrentModification(expectedModCount);
    appendReplacement(sb, replacement);
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
    DiagnosticOperation operation = beginDiagnostics(MatchOperation.REPLACE_ALL);
    if (operation == null) {
      return replaceAllImpl(replacement);
    }
    try {
      String result = replaceAllImpl(replacement);
      completeDiagnostics(operation, diagnosticMatchCount());
      return result;
    } catch (RuntimeException | Error e) {
      abortDiagnostics(operation);
      throw e;
    }
  }

  private String replaceAllImpl(String replacement) {
    return replaceImpl(replacement, Integer.MAX_VALUE);
  }

  private boolean findAndRecordReplacementMatch() {
    boolean found = find();
    if (found) {
      diagnosticIncrementMatchCount();
    }
    return found;
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
    DiagnosticOperation operation = beginDiagnostics(MatchOperation.REPLACE_ALL);
    try {
      String result = replaceAllFunctionImpl(replacer);
      completeDiagnostics(operation, diagnosticMatchCount());
      return result;
    } catch (RuntimeException | Error e) {
      abortDiagnostics(operation);
      throw e;
    }
  }

  private String replaceAllFunctionImpl(Function<MatchResult, String> replacer) {
    requireNonNull(replacer, "replacer");
    reset();
    if (!find()) {
      return text;
    }
    diagnosticIncrementMatchCount();
    StringBuilder sb = new StringBuilder(text.length());
    do {
      int expectedModCount = modCount;
      String replacement = Objects.requireNonNull(replacer.apply(toMatchResult()));
      checkConcurrentModification(expectedModCount);
      appendReplacement(sb, replacement);
    } while (findAndRecordReplacementMatch());
    appendTail(sb);
    return sb.toString();
  }

  private String literalReplaceFastPath(LazyTemplate template, int limit) {
    if (!enginePathOptions().literalFastPaths()) {
      return null;
    }

    String literal = parentPattern.literalMatch();
    if (literal == null || literal.isEmpty() || text == null) {
      return null;
    }

    String replacement = template.replacement;
    boolean simpleReplacement = isSimpleReplacement(replacement);
    if (!simpleReplacement && template.needsCaptures()) {
      return null; // Cannot handle replacements that reference inner captures yet
    }

    DiagnosticOperation activeDiagnostics = diagnosticOperation;
    DiagnosticAccumulator accumulator =
        activeDiagnostics == null ? null : activeDiagnostics.accumulator();
    if (accumulator != null) {
      accumulator.participate(MatchStrategy.LITERAL, StrategyRole.CANDIDATE_VERIFICATION);
    }

    boolean isStartAnchored = parentPattern.prog().anchorStart();
    if (isStartAnchored) {
      if (searchFrom > 0 || !literalRegionMatches(literal, 0, literal.length())) {
        if (accumulator != null) {
          accumulator.boundary(MatchStrategy.LITERAL);
        }
        applyFailedMatchResult();
        return text;
      }
      int matchStart = 0;
      int matchEnd = literal.length();
      StringBuilder sb = new StringBuilder(text.length());
      if (!simpleReplacement) {
        applyGroupZeroMatchResult(matchStart, matchEnd);
        ReplacementSegment[] compiledTemplate = template.get();
        groups[0] = matchStart;
        groups[1] = matchEnd;
        applyReplacementTemplate(sb, compiledTemplate);
      } else {
        sb.append(replacement);
      }
      int appendPosition = matchEnd;
      this.appendPos = appendPosition;
      sb.append(text, appendPosition, text.length());
      if (limit == 1) {
        if (groupCount() == 0) {
          applyGroupZeroMatchResult(matchStart, matchEnd);
        } else {
          applyDeferredMatchResult(
              matchStart, matchEnd, parentPattern.prog().numCaptures(), true, false);
          resolveCaptures();
        }
      } else {
        this.searchFrom = regionEnd;
        applyFailedMatchResult();
      }
      if (accumulator != null) {
        accumulator.boundary(MatchStrategy.LITERAL);
        accumulator.matchCount(1);
      }
      return sb.toString();
    }

    boolean foldCase = parentPattern.literalFoldCase();
    int anchorOffset = 0;
    char anchorLow = 0;
    char anchorHigh = 0;
    int[] failure = null;
    if (foldCase) {
      PreparedMatchRunner runner = parentPattern.preparedMatchRunner(false);
      if (runner instanceof LiteralPreparedRunner literalRunner) {
        anchorOffset = literalRunner.anchorOffset();
        anchorLow = literalRunner.anchorLow();
        anchorHigh = literalRunner.anchorHigh();
        failure = literalRunner.ignoreCaseFailure();
      } else {
        int literalLen = literal.length();
        if (literalLen == 1) {
          anchorOffset = 0;
          char c = literal.charAt(0);
          anchorLow = Ascii.toLowerCase(c);
          anchorHigh = Ascii.toUpperCase(c);
          failure = null;
        } else {
          anchorOffset = RarityOracle.rarestAsciiOffset(literal, literalLen);
          char c = literal.charAt(anchorOffset);
          anchorLow = Ascii.toLowerCase(c);
          anchorHigh = Ascii.toUpperCase(c);
          failure = Ascii.ignoreCaseFailure(literal);
        }
      }
    }

    StringBuilder sb = null;
    int appendPosition = 0;
    int searchFrom = 0;
    int matchStart =
        foldCase
            ? indexOfIgnoreCase(
                text, literal, failure, anchorOffset, anchorLow, anchorHigh, searchFrom)
            : indexOfReplacementLiteral(literal, searchFrom);
    if (matchStart == -1) {
      if (accumulator != null) {
        accumulator.boundary(MatchStrategy.LITERAL);
      }
      applyFailedMatchResult();
      return text;
    }
    int matchesFound = 0;

    int firstMatchStart = -1;
    int firstMatchEnd = -1;

    ReplacementSegment[] compiledTemplate = null;

    do {
      if (sb == null) {
        sb = new StringBuilder(text.length());
      }
      sb.append(text, appendPosition, matchStart);

      if (matchesFound == 0) {
        firstMatchStart = matchStart;
        firstMatchEnd = matchStart + literal.length();
        if (!simpleReplacement) {
          applyDeferredMatchResult(
              firstMatchStart, firstMatchEnd, parentPattern.prog().numCaptures(), true, false);
          compiledTemplate = template.get();
        }
      }

      if (simpleReplacement) {
        sb.append(replacement);
      } else {
        groups[0] = matchStart;
        groups[1] = matchStart + literal.length();
        applyReplacementTemplate(sb, compiledTemplate);
      }

      appendPosition = matchStart + literal.length();
      searchFrom = appendPosition;
      matchesFound++;
      if (matchesFound >= limit) {
        break;
      }
      matchStart =
          foldCase
              ? indexOfIgnoreCase(
                  text, literal, failure, anchorOffset, anchorLow, anchorHigh, searchFrom)
              : indexOfReplacementLiteral(literal, searchFrom);
    } while (matchStart != -1);

    if (sb == null) {
      if (accumulator != null) {
        accumulator.boundary(MatchStrategy.LITERAL);
      }
      applyFailedMatchResult();
      return text;
    }

    this.appendPos = appendPosition;
    sb.append(text, appendPosition, text.length());

    if (limit == 1) {
      if (groupCount() == 0) {
        applyGroupZeroMatchResult(firstMatchStart, firstMatchEnd);
      } else {
        applyDeferredMatchResult(
            firstMatchStart, firstMatchEnd, parentPattern.prog().numCaptures(), true, false);
      }
    } else {
      this.searchFrom = regionEnd;
      applyFailedMatchResult();
    }

    if (accumulator != null) {
      accumulator.boundary(MatchStrategy.LITERAL);
      accumulator.matchCount(matchesFound);
    }

    return sb.toString();
  }

  private int indexOfReplacementLiteral(String literal, int fromIndex) {
    int matchStart = text.indexOf(literal, fromIndex);
    if (WorkCounterConfig.ENABLED) {
      int examinedEnd = matchStart >= 0 ? matchStart + literal.length() : text.length();
      WorkCounter.record(Math.max(0, examinedEnd - fromIndex));
    }
    return matchStart;
  }

  private String charClassReplaceFastPath(LazyTemplate template, int limit) {
    Pattern.CharClassMatchInfo ccMatch = parentPattern.matchDescriptor().charClassMatch();
    if (!enginePathOptions().charClassReplacementFastPath()
        || ccMatch == null
        || parentPattern.hasLazyQuantifiers()) {
      return null;
    }
    DiagnosticOperation activeDiagnostics = diagnosticOperation;
    DiagnosticAccumulator accumulator =
        activeDiagnostics == null ? null : activeDiagnostics.accumulator();
    if (accumulator != null) {
      accumulator.participate(MatchStrategy.CHARACTER_CLASS, StrategyRole.CANDIDATE_VERIFICATION);
    }
    if (ccMatch.allowEmpty()) {
      return nullableCharClassReplaceFastPath(template, limit, ccMatch);
    }
    String repText = null;

    int textLen = text.length();
    int pos = searchFrom;
    int appendPos = searchFrom;
    int matchesFound = 0;
    StringBuilder sb = null;

    int[] ranges = ccMatch.ranges();
    long b0 = ccMatch.bitmap0();
    long b1 = ccMatch.bitmap1();

    int firstMatchStart = -1;
    int firstMatchEnd = -1;

    while (pos < textLen && matchesFound < limit) {
      int matchStart = -1;
      while (pos < textLen) {
        int cp = text.codePointAt(pos);
        if (charClassContains(ranges, b0, b1, cp)) {
          matchStart = pos;
          break;
        }
        pos += Character.charCount(cp);
      }

      if (matchStart == -1) {
        break;
      }

      pos += Character.charCount(text.codePointAt(pos));
      while (pos < textLen) {
        int cp = text.codePointAt(pos);
        if (!charClassContains(ranges, b0, b1, cp)) {
          break;
        }
        pos += Character.charCount(cp);
      }
      int matchEnd = pos;

      if (matchesFound == 0) {
        firstMatchStart = matchStart;
        firstMatchEnd = matchEnd;
        ReplacementSegment[] compiledTemplate;
        try {
          compiledTemplate = template.get();
        } catch (IllegalArgumentException e) {
          applyFullMatchResult(new int[] {firstMatchStart, firstMatchEnd});
          throw e;
        }
        if (compiledTemplate.length != 1
            || !(compiledTemplate[0] instanceof ReplacementSegment.Literal literalSeg)) {
          clearCurrentResult();
          return null;
        }
        applyFullMatchResult(new int[] {firstMatchStart, firstMatchEnd});
        repText = literalSeg.text();
      }

      if (sb == null) {
        sb = new StringBuilder(textLen);
      }
      sb.append(text, appendPos, matchStart);
      sb.append(repText);
      appendPos = matchEnd;
      matchesFound++;
    }

    if (sb == null) {
      if (accumulator != null) {
        accumulator.boundary(MatchStrategy.CHARACTER_CLASS);
      }
      applyFailedMatchResult();
      return text;
    }

    this.appendPos = appendPos;
    sb.append(text, appendPos, textLen);

    if (limit == 1) {
      applyFullMatchResult(new int[] {firstMatchStart, firstMatchEnd});
    } else {
      searchFrom = regionEnd;
      applyFailedMatchResult();
    }

    if (accumulator != null) {
      accumulator.boundary(MatchStrategy.CHARACTER_CLASS);
      accumulator.matchCount(matchesFound);
    }

    return sb.toString();
  }

  private String nullableCharClassReplaceFastPath(
      LazyTemplate template, int limit, Pattern.CharClassMatchInfo ccMatch) {
    int[] ranges = ccMatch.ranges();
    long b0 = ccMatch.bitmap0();
    long b1 = ccMatch.bitmap1();
    int textLen = text.length();
    int firstMatchEnd = 0;
    while (firstMatchEnd < textLen) {
      int cp = text.codePointAt(firstMatchEnd);
      if (!charClassContains(ranges, b0, b1, cp)) {
        break;
      }
      firstMatchEnd += Character.charCount(cp);
    }

    applyFullMatchResult(new int[] {0, firstMatchEnd});
    ReplacementSegment[] compiledTemplate = template.get();
    if (compiledTemplate.length != 1
        || !(compiledTemplate[0] instanceof ReplacementSegment.Literal literalSeg)) {
      clearCurrentResult();
      return null;
    }
    String replacement = literalSeg.text();
    if (limit == 1) {
      StringBuilder result = new StringBuilder(textLen + replacement.length());
      result.append(replacement);
      result.append(text, firstMatchEnd, textLen);
      appendPos = firstMatchEnd;
      return result.toString();
    }

    StringBuilder result = new StringBuilder(textLen + replacement.length());
    int pos = 0;
    while (pos < textLen) {
      int runEnd = pos;
      while (runEnd < textLen) {
        int cp = text.codePointAt(runEnd);
        if (!charClassContains(ranges, b0, b1, cp)) {
          break;
        }
        runEnd += Character.charCount(cp);
      }
      result.append(replacement);
      if (runEnd > pos) {
        pos = runEnd;
      } else {
        // Matcher.find() advances a terminal empty match by one UTF-16 position, matching the JDK
        // Matcher contract even when that position lies between surrogate halves.
        result.append(text.charAt(pos));
        pos++;
      }
    }
    result.append(replacement);

    appendPos = textLen;
    searchFrom = regionEnd + 1;
    applyFailedMatchResult();
    findExhaustedAfterTerminalEmptyMatch = true;
    return result.toString();
  }

  /**
   * Implements a non-terminal append-and-replace step. Appends the text between the previous append
   * position and the current match, followed by the processed replacement string.
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
   * Implements a terminal append-and-replace step. Appends the remaining input text after the last
   * match to the string builder.
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
   * @throws IndexOutOfBoundsException if start or end is less than zero, if end is greater than the
   *     length of the input sequence, or if start is greater than end
   */
  public Matcher region(int start, int end) {
    int len = getTextLength();
    if (start < 0 || start > len) {
      throw new IndexOutOfBoundsException("start=" + start + ", length=" + len);
    }
    if (end < 0 || end > len) {
      throw new IndexOutOfBoundsException("end=" + end + ", length=" + len);
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
   * limited to finding matches within {@link #regionStart()} (inclusive) and {@link #regionEnd()}
   * (exclusive).
   *
   * @return the ending point of this matcher's region
   */
  public int regionEnd() {
    return regionEnd;
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
    if (hasMatch && groups[0] >= 0 && groups[1] >= groups[0]) {
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
    if (hasMatch) {
      if (!groupZeroResolved) {
        resolveCaptures();
      }
      int prevStart = groups[0];
      int prevEnd = groups[1];
      searchFrom = prevEnd;
      if (prevStart == prevEnd && searchFrom < regionEnd) {
        searchFrom++;
      }
      this.parentPattern = newPattern;
      this.groups = new int[2 * newPattern.prog().numCaptures()];
      Arrays.fill(this.groups, -1);
      this.groups[0] = prevStart;
      this.groups[1] = prevEnd;
      clearDeferredCaptureState();
    } else {
      this.parentPattern = newPattern;
      this.groups = new int[2 * newPattern.prog().numCaptures()];
      clearCurrentResult();
    }
    invalidatePatternCaches();
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
   * Returns the match state of this matcher as a {@link MatchResult}. The result is independent of
   * this matcher; subsequent operations on this matcher will not affect the returned result.
   *
   * @return a {@link MatchResult} with the state of this matcher
   * @throws IllegalStateException if no match has yet been attempted, or if the previous match
   *     operation failed
   */
  public MatchResult toMatchResult() {
    if (parentPattern.numGroups() > 0 && !capturesResolved) {
      recordInnerCaptureDemand();
    } else {
      eagerFallbackCaptures = true;
    }
    if (hasMatch) {
      resolveCaptures();
    }
    return new SnapshotMatchResult(
        hasMatch ? groups.clone() : null,
        text,
        groupCount(),
        parentPattern.namedGroups(),
        hasMatch);
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  /**
   * Resolves deferred capture groups. Called lazily when the user accesses any group (e.g., {@code
   * group(0)}, {@code start(1)}) or when a full snapshot is needed ({@code toMatchResult()}). Runs
   * the submatch engine (OnePass or BitState/NFA) anchored at the DFA-determined match start,
   * bounded by the DFA's match end. For {@code find()}, it does not force the match to extend to
   * that end; this allows alternation priority to determine the actual match length (e.g., {@code
   * (fo|foo)} matching "fo" rather than "foo"). For {@code matches()}, the deferred search must
   * still cover the whole input.
   */
  @SuppressWarnings("ReferenceEquality")
  private void resolveCaptures() {
    if (capturesResolved) {
      return;
    }
    Prog prog = parentPattern.prog();
    InputScanner scanner = activeScanner();
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
        && !parentPattern.hasNullableAlternation()) {
      diagnosticCapture(MatchStrategy.ONE_PASS);
      int ncap = 2 * Math.max(prog.numCaptures(), 1);
      if (onePassScratchCap == null || onePassScratchCap.length < ncap) {
        onePassScratchCap = new int[ncap];
      }
      result =
          parentPattern
              .onePass()
              .search(
                  scanner,
                  deferredMatchStart,
                  deferredMatchEnd,
                  true,
                  prog.numCaptures(),
                  groups,
                  onePassScratchCap);
    } else {
      boolean savedCaptureSearch = diagnosticCaptureSearch;
      diagnosticCaptureSearch = true;
      try {
        result =
            searchWithBitStateOrNfa(
                prog,
                scanner,
                deferredMatchStart,
                deferredMatchEnd,
                deferredMatchEnd,
                deferredMatchEnd,
                true,
                false,
                deferredEndMatch,
                prog.numCaptures(),
                true,
                groups);
      } finally {
        diagnosticCaptureSearch = savedCaptureSearch;
      }
    }
    if (result != null && result != groups) {
      groups = result;
    }
    capturesResolved = true;
    groupZeroResolved = true;
  }

  private void resolveCapturesBeforeRestoringRegion() {
    // Deferred captures must be replayed against the same opaque region view that produced the
    // match. Restoring the full input first would change empty-width assertion semantics at the
    // region boundaries.
    if (hasMatch && !capturesResolved) {
      resolveCaptures();
    }
  }

  private InputScanner activeScanner() {
    if (textScanner == null) {
      textScanner = new StringInputScanner(text);
    }
    return textScanner;
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

  /**
   * Processes a replacement string and appends the result to {@code sb}. Handles {@code $0}, {@code
   * $1}, {@code ${name}}, {@code \\} (literal backslash), and {@code \$} (literal dollar).
   */
  private void appendReplacementBody(StringBuilder sb, String replacement) {
    if (isSimpleReplacement(replacement)) {
      sb.append(replacement);
      return;
    }
    int i = 0;
    while (i < replacement.length()) {
      char c = replacement.charAt(i);
      if (c == '\\') {
        i++;
        if (i >= replacement.length()) {
          throw new IllegalArgumentException("Trailing backslash in replacement string");
        }
        sb.append(replacement.charAt(i));
        i++;
      } else if (c == '$') {
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
          String name = replacement.substring(nameStart, i);
          i++; // skip '}'
          Integer idx = parentPattern.namedGroups().get(name);
          if (idx == null) {
            throw new IllegalArgumentException("No group with name <" + name + ">");
          }
          int g = idx;
          int start = start(g);
          int end = end(g);
          if (start >= 0 && end >= 0) {
            sb.append(text, start, end);
          }
        } else if (Character.isDigit(replacement.charAt(i))) {
          // Numeric group reference: $0, $1, $12, etc.
          NumericGroupReference groupRef = parseNumericGroupReference(replacement, i, groupCount());
          int groupIdx = groupRef.groupNum();
          i = groupRef.end();
          int start = start(groupIdx);
          int end = end(groupIdx);
          if (start >= 0 && end >= 0) {
            sb.append(text, start, end);
          }
        } else {
          throw new IllegalArgumentException("Invalid group reference in replacement string");
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
    private final boolean hasMatch;

    SnapshotMatchResult(
        int[] groups,
        String text,
        int groupCount,
        Map<String, Integer> namedGroups,
        boolean hasMatch) {
      this.groups = groups;
      this.text = text;
      this.groupCount = groupCount;
      this.namedGroups = Collections.unmodifiableMap(namedGroups);
      this.hasMatch = hasMatch;
    }

    @Override
    public boolean hasMatch() {
      return hasMatch;
    }

    @Override
    public int start() {
      return start(0);
    }

    @Override
    public int start(int group) {
      checkMatch();
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
      checkMatch();
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
      checkMatch();
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

    private void checkMatch() {
      if (!hasMatch) {
        throw new IllegalStateException("No match found");
      }
    }
  }

  private static final class LazyTemplate {
    private final String replacement;
    private final int maxGroup;
    private ReplacementSegment[] value;
    private Boolean needsCaptures;

    LazyTemplate(String replacement, int maxGroup) {
      this.replacement = replacement;
      this.maxGroup = maxGroup;
    }

    boolean needsCaptures() {
      if (needsCaptures == null) {
        needsCaptures = computeNeedsCaptures();
      }
      return needsCaptures;
    }

    private boolean computeNeedsCaptures() {
      if (replacement == null) {
        return false;
      }
      int len = replacement.length();
      int i = 0;
      while (i < len) {
        char c = replacement.charAt(i);
        if (c == '\\') {
          i += 2;
        } else if (c == '$') {
          i++;
          if (i >= len) {
            continue;
          }
          char next = replacement.charAt(i);
          if (next == '{') {
            return true;
          } else if (next >= '0' && next <= '9') {
            NumericGroupReference groupRef = parseNumericGroupReference(replacement, i, maxGroup);
            if (groupRef.groupNum() > 0) {
              return true;
            }
            i = groupRef.end();
          } else {
            i++;
          }
        } else {
          i++;
        }
      }
      return false;
    }

    ReplacementSegment[] get() {
      if (value == null) {
        value = compileReplacementTemplate(replacement, maxGroup);
      }
      return value;
    }
  }

  private static final class DfaMatchCursor {
    private int pos;
    private Dfa revDfa;

    DfaMatchCursor(int pos) {
      this.pos = pos;
    }
  }

  static final class SplitBuffer {
    /** Alternating (start, end) match positions. */
    int[] array = new int[32];

    int size = 0;

    void add(int start, int end) {
      if (size + 2 > array.length) {
        array = Arrays.copyOf(array, array.length * 2);
      }
      array[size++] = start;
      array[size++] = end;
    }
  }

  int findSplitPositions(int limit, SplitBuffer buffer) {
    int last = 0;
    int searchFrom = 0;
    int textLen = text.length();

    while (searchFrom <= textLen) {
      long packed = findNextMatchPacked(searchFrom);
      if (packed == -1L) {
        break;
      }
      int start = unpackStart(packed);
      int end = unpackEnd(packed);

      if (limit > 0 && (buffer.size / 2) >= limit - 1) {
        break;
      }

      if (last == 0 && start == 0 && end == 0) {
        searchFrom = 1;
        continue;
      }

      buffer.add(start, end);
      last = end;
      if (start == end) {
        searchFrom = end + 1;
      } else if (parentPattern.hasInternalGraphemeClusterBoundary()
          && end < textLen
          && endedAfterCrLf(end)) {
        searchFrom = end + 1;
      } else {
        searchFrom = end;
      }
    }
    return buffer.size / 2;
  }

  private long findNextMatchPacked(int fromIndex) {
    InputScanner scanner = activeScanner();
    if (fromIndex > scanner.length()) {
      return -1L;
    }
    Prog prog = parentPattern.prog();
    if (prog.anchorStart() && fromIndex > 0) {
      return -1L;
    }
    EnginePathOptions options = enginePathOptions();

    // Literal fast path
    String literal = parentPattern.literalMatch();
    if (options.literalFastPaths() && literal != null) {
      int idx;
      if (prog.anchorStart()) {
        if (parentPattern.literalFoldCase()) {
          idx = Ascii.regionMatchesIgnoreCase(text, 0, literal, literal.length()) ? 0 : -1;
        } else {
          idx = text.startsWith(literal) ? 0 : -1;
        }
      } else {
        idx =
            parentPattern.literalFoldCase()
                ? indexOfIgnoreCase(text, literal, fromIndex)
                : text.indexOf(literal, fromIndex);
      }
      if (idx < 0) {
        return -1L;
      }
      return packPositions(idx, idx + literal.length());
    }

    // Char class fast path
    CharClassScanInfo singleCharClass = parentPattern.matchDescriptor().singleCharClass();
    if (options.charClassMatchFastPaths() && singleCharClass != null) {
      if (prog.anchorStart() && fromIndex > 0) {
        return -1L;
      }
      if (prog.anchorStart()) {
        if (scanner.length() == 0) {
          return -1L;
        }
        int cp = scanner.codePointAt(0);
        if (singleCharClass.contains(cp)) {
          return packPositions(0, Character.charCount(cp));
        }
        return -1L;
      }
      if (singleCharClass.isAscii()) {
        int idx = scanner.indexOfCharClass(singleCharClass, fromIndex);
        if (idx < 0) {
          return -1L;
        }
        return packPositions(idx, idx + 1);
      }
      int idx =
          scanner.indexOfCodePointClass(
              singleCharClass.ranges(),
              singleCharClass.bitmap0(),
              singleCharClass.bitmap1(),
              fromIndex,
              scanner.length());
      if (idx < 0) {
        return -1L;
      }
      int cp = text.codePointAt(idx);
      return packPositions(idx, idx + Character.charCount(cp));
    }

    int effectiveStart = fromIndex;
    if (options.startAcceleration() && text != null && !prog.anchorStart()) {
      StringStartAccelerator accelerator = parentPattern.stringStartAccelerator();
      if (accelerator != null) {
        int idx = accelerator.findCandidate(text, fromIndex, prog.unixLines());
        if (idx < 0) {
          return -1L;
        }
        effectiveStart = idx;
      }
    }

    Dfa.SearchResult fwdResult = null;
    if (canUseForwardDfa()) {
      fwdResult = dfa(false).doSearch(scanner, effectiveStart, prog.anchorStart(), false);
      if (fwdResult != null && !fwdResult.matched()) {
        return -1L;
      }
    }

    if (options.dfa()
        && parentPattern.dfaGroupZeroReliable()
        && fwdResult != null
        && fwdResult.pos() > effectiveStart) {
      int earlyEnd = fwdResult.pos();

      if (prog.anchorStart()) {
        Dfa.SearchResult fwdFirst = dfa(false).doSearch(scanner, effectiveStart, true, false);
        if (fwdFirst != null && fwdFirst.matched()) {
          return packPositions(effectiveStart, fwdFirst.pos());
        }
      } else if (canUseReverseDfa()) {
        Dfa revDfa = reverseDfa();
        if (revDfa != null) {
          Dfa.SearchResult revResult =
              revDfa.doSearchReverse(scanner, earlyEnd, effectiveStart, true, true);
          if (revResult != null && revResult.matched() && !revResult.ambiguous()) {
            int matchStart = revResult.pos();
            Dfa.SearchResult fwdFirst = dfa(false).doSearch(scanner, matchStart, true, false);
            if (fwdFirst != null && fwdFirst.matched()) {
              return packPositions(matchStart, fwdFirst.pos());
            }
          }
        }
      }
    }

    int nsubmatch = 1;
    int[] result =
        searchWithBitStateOrNfa(
            prog,
            scanner,
            effectiveStart,
            scanner.length(),
            scanner.length(),
            false,
            false,
            false,
            nsubmatch);
    if (result == null) {
      return -1L;
    }
    return packPositions(result[0], result[1]);
  }

  private static long packPositions(int start, int end) {
    return ((long) start << 32) | (end & 0xFFFFFFFFL);
  }

  private static int unpackStart(long packed) {
    return (int) (packed >>> 32);
  }

  private static int unpackEnd(long packed) {
    return (int) packed;
  }

  // ---------------------------------------------------------------------------
  // Prepared Match Runner (Fast Path Dispatch & Setup Elimination)
  // ---------------------------------------------------------------------------

  sealed interface PreparedMatchRunner
      permits LiteralPreparedRunner,
          SingleCharClassPreparedRunner,
          KeywordAlternationPreparedRunner,
          OnePassAnchoredPreparedRunner,
          FallbackPreparedRunner {
    boolean find(Matcher matcher, boolean regionActive);

    boolean matches(Matcher matcher);

    boolean lookingAt(Matcher matcher);
  }

  static final class LiteralPreparedRunner implements PreparedMatchRunner {
    private final String literal;
    private final boolean foldCase;
    private final byte[] literalUtf8;
    private final int[] failure;
    private final int[] shifts;
    private final int anchorOffset;
    private final char anchorLow;
    private final char anchorHigh;
    private final int[] ignoreCaseFailure;
    private final int matchLengthChars;
    private final int matchLengthBytes;
    private final boolean isStartAnchored;
    private final PreparedMatchRunner fallback;

    LiteralPreparedRunner(
        String literal,
        boolean foldCase,
        byte[] literalUtf8,
        int[] failure,
        int[] shifts,
        boolean isStartAnchored,
        PreparedMatchRunner fallback) {
      this.literal = literal;
      this.foldCase = foldCase;
      this.literalUtf8 = literalUtf8;
      this.failure = failure;
      this.shifts = shifts;
      int literalLen = literal != null ? literal.length() : 0;
      if (foldCase && literalLen > 0) {
        if (literalLen == 1) {
          this.anchorOffset = 0;
          char c = literal.charAt(0);
          this.anchorLow = Ascii.toLowerCase(c);
          this.anchorHigh = Ascii.toUpperCase(c);
          this.ignoreCaseFailure = null;
        } else {
          this.anchorOffset = RarityOracle.rarestAsciiOffset(literal, literalLen);
          char c = literal.charAt(this.anchorOffset);
          this.anchorLow = Ascii.toLowerCase(c);
          this.anchorHigh = Ascii.toUpperCase(c);
          this.ignoreCaseFailure = Ascii.ignoreCaseFailure(literal);
        }
      } else {
        this.anchorOffset = 0;
        this.anchorLow = 0;
        this.anchorHigh = 0;
        this.ignoreCaseFailure = null;
      }
      this.matchLengthChars = literalLen;
      this.matchLengthBytes = literalUtf8 != null ? literalUtf8.length : 0;
      this.isStartAnchored = isStartAnchored;
      this.fallback = fallback;
    }

    int anchorOffset() {
      return anchorOffset;
    }

    char anchorLow() {
      return anchorLow;
    }

    char anchorHigh() {
      return anchorHigh;
    }

    int[] ignoreCaseFailure() {
      return ignoreCaseFailure;
    }

    @Override
    public boolean find(Matcher matcher, boolean regionActive) {
      if (isStartAnchored && matcher.searchFrom > 0) {
        return matcher.applyFailedMatchResult();
      }
      if (isStartAnchored) {
        return matchLiteral(matcher, false);
      }
      if (foldCase && matcher.text == null) {
        return fallback.find(matcher, regionActive);
      }
      matcher.diagnosticBoundary(MatchStrategy.LITERAL);
      int idx;
      int matchLength;
      if (foldCase) {
        idx =
            indexOfIgnoreCase(
                matcher.text,
                literal,
                ignoreCaseFailure,
                anchorOffset,
                anchorLow,
                anchorHigh,
                matcher.searchFrom);
        matchLength = matchLengthChars;
      } else if (matcher.activeScanner() instanceof Utf8InputScanner utf8Scanner) {
        idx = utf8Scanner.indexOf(literalUtf8, failure, shifts, matcher.searchFrom);
        matchLength = matchLengthBytes;
      } else if (matcher.text != null) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(Math.max(0, matcher.text.length() - matcher.searchFrom));
        }
        idx = matcher.text.indexOf(literal, matcher.searchFrom);
        matchLength = matchLengthChars;
      } else {
        return matcher.doFindCore(regionActive);
      }
      if (idx < 0) {
        matcher.diagnosticBoundary(MatchStrategy.LITERAL);
        return matcher.applyFailedMatchResult();
      }
      return matcher.applyGroupZeroMatchResult(idx, idx + matchLength);
    }

    @Override
    public boolean matches(Matcher matcher) {
      return matchLiteral(matcher, true);
    }

    @Override
    public boolean lookingAt(Matcher matcher) {
      return matchLiteral(matcher, false);
    }

    private boolean matchLiteral(Matcher matcher, boolean fullMatch) {
      if (isStartAnchored && matcher.searchFrom > 0) {
        return matcher.applyFailedMatchResult();
      }
      if (foldCase && matcher.text == null) {
        return fullMatch ? fallback.matches(matcher) : fallback.lookingAt(matcher);
      }
      matcher.capturesResolved = true;
      if (matcher.text != null) {
        matcher.diagnosticBoundary(MatchStrategy.LITERAL);
        boolean matched;
        if (foldCase) {
          matched =
              (fullMatch
                      ? matcher.text.length() == matchLengthChars
                      : matcher.text.length() >= matchLengthChars)
                  && matcher.literalRegionMatches(literal, 0, matchLengthChars);
        } else {
          matched = fullMatch ? matcher.text.equals(literal) : matcher.text.startsWith(literal);
        }
        if (matched) {
          matcher.applyFullMatchResult(
              new int[] {0, fullMatch ? matcher.text.length() : matchLengthChars});
        } else {
          if (matcher.isPartialLiteralMatch(literal, 0)) {}
          matcher.applyFailedMatchResult();
        }
        return matcher.hasMatch;
      } else if (matcher.activeScanner() instanceof Utf8InputScanner utf8Scanner) {
        matcher.diagnosticBoundary(MatchStrategy.LITERAL);
        boolean matched;
        if (literalUtf8 != null) {
          matched =
              (fullMatch
                      ? utf8Scanner.length() == matchLengthBytes
                      : utf8Scanner.length() >= matchLengthBytes)
                  && utf8Scanner.startsWith(literalUtf8, 0);
        } else {
          matched = literal.isEmpty() && (!fullMatch || utf8Scanner.length() == 0);
        }
        if (matched) {
          matcher.applyFullMatchResult(
              new int[] {0, fullMatch ? utf8Scanner.length() : matchLengthBytes});
        } else {
          matcher.applyFailedMatchResult();
        }
        return matcher.hasMatch;
      }
      return fullMatch ? matcher.matchesCore() : matcher.lookingAtCore();
    }
  }

  static final class SingleCharClassPreparedRunner implements PreparedMatchRunner {
    private final CharClassScanInfo singleCharClass;
    private final Pattern.CharClassMatchInfo charClassMatch;
    private final boolean isStartAnchored;

    SingleCharClassPreparedRunner(
        CharClassScanInfo singleCharClass,
        Pattern.CharClassMatchInfo charClassMatch,
        boolean isStartAnchored) {
      this.singleCharClass = singleCharClass;
      this.charClassMatch = charClassMatch;
      this.isStartAnchored = isStartAnchored;
    }

    @Override
    public boolean find(Matcher matcher, boolean regionActive) {
      if (isStartAnchored && matcher.searchFrom > 0) {
        return matcher.applyFailedMatchResult();
      }
      if (singleCharClass == null) {
        return matcher.doFindCore(regionActive);
      }
      if (isStartAnchored) {
        return matchSingleCharClass(matcher, false);
      }
      matcher.diagnosticBoundary(MatchStrategy.CHARACTER_CLASS);
      return matcher.singleCharClassFindFastPath(singleCharClass, matcher.searchFrom);
    }

    @Override
    public boolean matches(Matcher matcher) {
      return matchSingleCharClass(matcher, true);
    }

    @Override
    public boolean lookingAt(Matcher matcher) {
      return matchSingleCharClass(matcher, false);
    }

    private boolean matchSingleCharClass(Matcher matcher, boolean fullMatch) {
      if (isStartAnchored && matcher.searchFrom > 0) {
        return matcher.applyFailedMatchResult();
      }
      matcher.capturesResolved = true;
      if (charClassMatch != null && fullMatch && matcher.text != null) {
        matcher.diagnosticBoundary(MatchStrategy.CHARACTER_CLASS);
        return matcher.charClassMatchFastPath(charClassMatch);
      }
      if (singleCharClass != null) {
        matcher.diagnosticBoundary(MatchStrategy.CHARACTER_CLASS);
        if (matcher.text != null) {
          int len = matcher.text.length();
          if (len >= 1) {
            char c0 = matcher.text.charAt(0);
            if (len >= 2 && Character.isSurrogatePair(c0, matcher.text.charAt(1))) {
              int cp = matcher.text.codePointAt(0);
              if ((!fullMatch || len == 2) && singleCharClass.contains(cp)) {
                return matcher.applyFullMatchResult(new int[] {0, 2});
              }
            } else {
              int cp = c0;
              if ((!fullMatch || len == 1) && singleCharClass.contains(cp)) {
                return matcher.applyFullMatchResult(new int[] {0, 1});
              }
            }
          }
          return matcher.applyFailedMatchResult();
        } else if (matcher.activeScanner() instanceof Utf8InputScanner utf8Scanner) {
          int len = utf8Scanner.length();
          if (len > 0) {
            long decoded = utf8Scanner.decodeForward(0);
            int cp = InputScanner.codePoint(decoded);
            int nextPos = InputScanner.position(decoded);
            if ((!fullMatch || len == nextPos) && singleCharClass.contains(cp)) {
              return matcher.applyFullMatchResult(new int[] {0, nextPos});
            }
          }
          return matcher.applyFailedMatchResult();
        }
      }
      return fullMatch ? matcher.matchesCore() : matcher.lookingAtCore();
    }
  }

  static final class KeywordAlternationPreparedRunner implements PreparedMatchRunner {
    private final Pattern.KeywordAlternation keywordAlternation;
    private final int numCaptures;
    private final boolean isStartAnchored;

    KeywordAlternationPreparedRunner(
        Pattern.KeywordAlternation keywordAlternation, int numCaptures, boolean isStartAnchored) {
      this.keywordAlternation = keywordAlternation;
      this.numCaptures = numCaptures;
      this.isStartAnchored = isStartAnchored;
    }

    @Override
    public boolean find(Matcher matcher, boolean regionActive) {
      if (isStartAnchored && matcher.searchFrom > 0) {
        return matcher.applyFailedMatchResult();
      }
      if (isStartAnchored) {
        matcher.diagnosticBoundary(MatchStrategy.KEYWORD);
        if (keywordAlternation.captureGroup > 0) {
          matcher.diagnosticCapture(MatchStrategy.KEYWORD);
        }
        return matcher.text != null
            ? matcher.matchKeywordAlternationAt(keywordAlternation, 0, numCaptures)
            : matcher.matchUtf8KeywordAlternationAt(keywordAlternation, 0, numCaptures);
      }
      matcher.diagnosticBoundary(MatchStrategy.KEYWORD);
      if (keywordAlternation.captureGroup > 0) {
        matcher.diagnosticCapture(MatchStrategy.KEYWORD);
      }
      return matcher.text != null
          ? matcher.findKeywordAlternation(keywordAlternation, matcher.searchFrom, numCaptures)
          : matcher.findUtf8KeywordAlternation(keywordAlternation, matcher.searchFrom, numCaptures);
    }

    @Override
    public boolean matches(Matcher matcher) {
      return matcher.matchesCore();
    }

    @Override
    public boolean lookingAt(Matcher matcher) {
      return matcher.lookingAtCore();
    }
  }

  static final class OnePassAnchoredPreparedRunner implements PreparedMatchRunner {
    private final int numCaptures;

    OnePassAnchoredPreparedRunner(int numCaptures) {
      this.numCaptures = numCaptures;
    }

    @Override
    public boolean find(Matcher matcher, boolean regionActive) {
      if (!matcher.parentPattern.canOnePassFind()
          || matcher.activeScanner().length() > matcher.onePassTextLimit()) {
        return matcher.doFindCore(regionActive);
      }
      if (matcher.searchFrom > 0 || matcher.anchoredPrefixOrCharClassCannotMatch(0)) {
        return matcher.applyFailedMatchResult();
      }
      matcher.diagnosticBoundary(MatchStrategy.ONE_PASS);
      if (matcher.parentPattern.numGroups() > 0) {
        matcher.diagnosticCapture(MatchStrategy.ONE_PASS);
      }
      int[] result =
          matcher
              .parentPattern
              .onePass()
              .search(
                  matcher.activeScanner(),
                  0,
                  matcher.activeScanner().length(),
                  false,
                  numCaptures,
                  matcher.groups);
      return matcher.applyFullMatchResult(result);
    }

    @Override
    public boolean matches(Matcher matcher) {
      if (matcher.activeScanner().length() > matcher.onePassTextLimit()) {
        return matcher.matchesCore();
      }
      matcher.capturesResolved = true;
      Pattern pattern = matcher.parentPattern;
      Prog prog = pattern.prog();
      OnePass onePass = pattern.onePass();
      if (onePass != null && !prog.hasGraphemeSemantics() && !pattern.hasNullableAlternation()) {
        matcher.diagnosticBoundary(MatchStrategy.ONE_PASS);
        if (pattern.numGroups() > 0) {
          matcher.diagnosticCapture(MatchStrategy.ONE_PASS);
        }
        int[] result =
            matcher.text != null
                ? onePass.search(matcher.text, true, numCaptures, matcher.groups)
                : onePass.search(matcher.activeScanner(), true, numCaptures, matcher.groups);
        return matcher.applyFullMatchResult(result);
      }
      return matcher.matchesCore();
    }

    @Override
    public boolean lookingAt(Matcher matcher) {
      if (matcher.activeScanner().length() > matcher.onePassTextLimit()) {
        return matcher.lookingAtCore();
      }
      matcher.capturesResolved = true;
      Pattern pattern = matcher.parentPattern;
      Prog prog = pattern.prog();
      if (pattern.canOnePassPrimary() && !prog.hasGraphemeSemantics()) {
        matcher.diagnosticBoundary(MatchStrategy.ONE_PASS);
        if (pattern.numGroups() > 0) {
          matcher.diagnosticCapture(MatchStrategy.ONE_PASS);
        }
        OnePass onePass = pattern.onePass();
        int[] result =
            matcher.text != null
                ? onePass.search(matcher.text, false, numCaptures, matcher.groups)
                : onePass.search(matcher.activeScanner(), false, numCaptures, matcher.groups);
        return matcher.applyFullMatchResult(result);
      }
      return matcher.lookingAtCore();
    }
  }

  static final class FallbackPreparedRunner implements PreparedMatchRunner {
    static final FallbackPreparedRunner INSTANCE = new FallbackPreparedRunner();

    @Override
    public boolean find(Matcher matcher, boolean regionActive) {
      return matcher.doFindCore(regionActive);
    }

    @Override
    public boolean matches(Matcher matcher) {
      return matcher.matchesCore();
    }

    @Override
    public boolean lookingAt(Matcher matcher) {
      return matcher.lookingAtCore();
    }
  }
}
