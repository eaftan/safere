// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import java.util.Arrays;
import java.util.regex.MatchResult;

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

  private final Pattern parentPattern;
  private String text;
  private int[] groups;
  private boolean hasMatch;
  private int searchFrom;
  private int appendPos;

  /**
   * Cached BitState instance borrowed from the parent Pattern's thread-local cache, reused across
   * {@code find()} calls. Borrowed on first use, returned on final use within the Matcher's
   * lifetime.
   */
  private BitState cachedBitState;
  private boolean bitStateBorrowed;

  /**
   * Whether all capture groups have been resolved. When the DFA sandwich determines match
   * boundaries (group 0), inner captures (groups 1+) are deferred until explicitly requested.
   * This avoids the expensive BitState/NFA submatch extraction in find-all loops that only
   * check match existence or read group 0.
   */
  private boolean capturesResolved = true;

  /** Stashed match boundaries for deferred capture resolution. */
  private int deferredMatchStart;
  private int deferredMatchEnd;

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
    this.text = input.toString();
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
        groups = new int[]{0, 0};
        return true;
      }
      return false;
    }

    // Scan every code point.
    int i = 0;
    while (i < len) {
      int cp = text.codePointAt(i);
      if (cp < 64) {
        if ((b0 & (1L << cp)) == 0) {
          return false;
        }
      } else if (cp < 128) {
        if ((b1 & (1L << (cp - 64))) == 0) {
          return false;
        }
      } else {
        if (!binarySearchRanges(ranges, cp)) {
          return false;
        }
      }
      i += Character.charCount(cp);
    }

    groups = new int[]{0, len};
    return true;
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

  /**
   * Single-pass replaceAll for patterns that are a single character class under a {@code +}
   * quantifier (e.g., {@code \d+}, {@code [a-zA-Z]+}). Scans the text once, identifying runs of
   * matching characters and replacing each run with the replacement string. Completely bypasses
   * all regex engines.
   */
  private String charClassReplaceAll(int[] ranges, String replacement) {
    long b0 = parentPattern.charClassMatchBitmap0();
    long b1 = parentPattern.charClassMatchBitmap1();
    int len = text.length();

    StringBuilder sb = new StringBuilder(len);
    int copyFrom = 0;
    int i = 0;

    while (i < len) {
      int cp = text.codePointAt(i);
      if (charClassContains(b0, b1, ranges, cp)) {
        // Found start of a match — append preceding non-match text.
        sb.append(text, copyFrom, i);
        // Skip past the entire run of matching characters.
        do {
          i += Character.charCount(cp);
          if (i >= len) {
            break;
          }
          cp = text.codePointAt(i);
        } while (charClassContains(b0, b1, ranges, cp));
        sb.append(replacement);
        copyFrom = i;
      } else {
        i += Character.charCount(cp);
      }
    }
    sb.append(text, copyFrom, len);
    return sb.toString();
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

  /**
   * Pre-parses a replacement string into a compiled template of segments. The template can be
   * applied repeatedly without re-scanning the replacement string.
   *
   * @param replacement the replacement string (may contain {@code $1}, {@code ${name}},
   *     {@code \\}, {@code \$})
   * @return an array of segments representing the compiled template
   * @throws IllegalArgumentException if the replacement string is malformed
   */
  private static ReplacementSegment[] compileReplacementTemplate(String replacement) {
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
          int groupNum = 0;
          while (i < replacement.length() && Character.isDigit(replacement.charAt(i))) {
            groupNum = groupNum * 10 + (replacement.charAt(i) - '0');
            i++;
          }
          segments.add(new ReplacementSegment.GroupRef(groupNum));
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

  /**
   * Applies a compiled replacement template to the current match, appending the result to
   * {@code sb}. Uses {@code sb.append(text, start, end)} for group values to avoid substring
   * allocation.
   *
   * <p>Captures must already be resolved before calling this method.
   */
  private void applyReplacementTemplate(StringBuilder sb, ReplacementSegment[] template) {
    for (ReplacementSegment seg : template) {
      switch (seg) {
        case ReplacementSegment.Literal(var t) -> sb.append(t);
        case ReplacementSegment.GroupRef(var g) -> {
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

  /** Tests whether a code point belongs to a character class defined by bitmaps and ranges. */
  private static boolean charClassContains(long b0, long b1, int[] ranges, int cp) {
    if (cp < 64) {
      return (b0 & (1L << cp)) != 0;
    } else if (cp < 128) {
      return (b1 & (1L << (cp - 64))) != 0;
    } else {
      return binarySearchRanges(ranges, cp);
    }
  }

  /**
   * Attempts to match the entire input sequence against the pattern.
   *
   * @return {@code true} if the entire input sequence matches this matcher's pattern
   */
  public boolean matches() {
    searchFrom = 0;

    // Literal fast path: for fully literal patterns with no user capture groups.
    String literal = parentPattern.literalMatch();
    if (literal != null && parentPattern.numGroups() == 0) {
      boolean matched;
      if (parentPattern.prefixFoldCase()) {
        matched = text.length() == literal.length()
            && text.regionMatches(true, 0, literal, 0, literal.length());
      } else {
        matched = text.equals(literal);
      }
      if (matched) {
        groups = new int[]{0, text.length()};
        hasMatch = true;
      } else {
        hasMatch = false;
      }
      return hasMatch;
    }

    // Character-class fast path: for patterns like [a-zA-Z]+, \d+, \w*, etc.
    int[] ccRanges = parentPattern.charClassMatchRanges();
    if (ccRanges != null) {
      hasMatch = charClassMatchFastPath(ccRanges);
      return hasMatch;
    }

    Prog prog = parentPattern.prog();

    // Fast path: try one-pass engine (anchored, with captures, O(n) time).
    OnePass onePass = parentPattern.onePass();
    if (onePass != null) {
      groups = onePass.search(text, true, prog.numCaptures());
      hasMatch = (groups != null);
      return hasMatch;
    }

    // Medium path: use DFA to check if a full match exists.
    {
      Dfa.SearchResult dfaResult = dfa().doSearch(text, true, true);
      if (dfaResult != null && !dfaResult.matched()) {
        hasMatch = false;
        return false;
      }
      if (dfaResult != null && dfaResult.pos() != text.length()) {
        hasMatch = false;
        return false;
      }
    }

    // Slow path: try BitState (faster than NFA for small texts), then NFA.
    groups = searchWithBitStateOrNfa(
        prog, text, 0, text.length(), text.length(), true, false, true, prog.numCaptures());
    hasMatch = (groups != null);
    return hasMatch;
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
    searchFrom = 0;

    // Literal fast path: for fully literal patterns with no user capture groups.
    String literal = parentPattern.literalMatch();
    if (literal != null && parentPattern.numGroups() == 0) {
      boolean matched;
      if (parentPattern.prefixFoldCase()) {
        matched = text.length() >= literal.length()
            && text.regionMatches(true, 0, literal, 0, literal.length());
      } else {
        matched = text.startsWith(literal);
      }
      if (matched) {
        groups = new int[]{0, literal.length()};
        hasMatch = true;
      } else {
        hasMatch = false;
      }
      return hasMatch;
    }

    Prog prog = parentPattern.prog();

    // Fast path: try one-pass engine (anchored, with captures, O(n) time).
    OnePass onePass = parentPattern.onePass();
    if (onePass != null) {
      groups = onePass.search(text, false, prog.numCaptures());
      hasMatch = (groups != null);
      return hasMatch;
    }

    // Medium path: use DFA to check if an anchored match exists.
    {
      Dfa.SearchResult dfaResult = dfa().doSearch(text, true, false);
      if (dfaResult != null && !dfaResult.matched()) {
        hasMatch = false;
        return false;
      }
    }

    // Slow path: try BitState (faster than NFA for small texts), then NFA.
    groups = searchWithBitStateOrNfa(
        prog, text, 0, text.length(), text.length(), true, false, false, prog.numCaptures());
    hasMatch = (groups != null);
    return hasMatch;
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
    if (hasMatch) {
      // Resolve deferred captures so groups[0] and groups[1] reflect the correct
      // match boundaries (the DFA sandwich uses longest-match, which may differ
      // from RE2's leftmost-first semantics for lazy quantifiers and ambiguous alternation).
      resolveCaptures();
      searchFrom = groups[1];
      if (groups[0] == groups[1]) { // empty match
        if (searchFrom >= text.length()) {
          hasMatch = false;
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
    hasMatch = false;
    searchFrom = start;
    return doFind();
  }

  /** Runs the engine search from {@link #searchFrom} and stores the result. */
  private boolean doFind() {
    if (searchFrom > text.length()) {
      hasMatch = false;
      return false;
    }

    // Reset deferred-capture state; DFA sandwich path may set it to false.
    capturesResolved = true;

    // Literal fast path: for fully literal patterns with no user capture groups,
    // use String.indexOf() directly.
    String literal = parentPattern.literalMatch();
    if (literal != null && parentPattern.numGroups() == 0) {
      int idx;
      if (parentPattern.prefixFoldCase()) {
        idx = indexOfIgnoreCase(text, literal, searchFrom);
      } else {
        idx = text.indexOf(literal, searchFrom);
      }
      if (idx < 0) {
        hasMatch = false;
        return false;
      }
      groups = new int[]{idx, idx + literal.length()};
      hasMatch = true;
      return true;
    }

    Prog prog = parentPattern.prog();

    // Anchored OnePass fast path: for anchored OnePass-eligible patterns, use OnePass directly.
    // OnePass is a single O(n) pass that finds both match bounds and captures, avoiding the
    // entire DFA construction and sandwich overhead. Works for any searchFrom position — OnePass
    // quickly returns null if ^ or \A constraints fail at a non-zero position.
    if (prog.anchorStart() && parentPattern.canOnePassPrimary()) {
      groups = parentPattern.onePass().search(text, searchFrom, text.length(), false,
          prog.numCaptures());
      hasMatch = (groups != null);
      return hasMatch;
    }

    // Prefix acceleration: if the pattern starts with a literal prefix, skip ahead to where
    // that prefix first appears instead of searching from the current position.
    int effectiveStart = searchFrom;
    String prefix = parentPattern.prefix();
    if (prefix != null) {
      int idx;
      if (parentPattern.prefixFoldCase()) {
        idx = indexOfIgnoreCase(text, prefix, searchFrom);
      } else {
        idx = text.indexOf(prefix, searchFrom);
      }
      if (idx < 0) {
        hasMatch = false;
        return false;
      }
      effectiveStart = idx;
    }

    // Character-class prefix acceleration: when the pattern starts with a character class (and
    // no literal prefix exists), scan for the first character that could begin a match. This
    // avoids running the full engine on text regions where no match can start.
    boolean[] ccPrefixAscii = parentPattern.charClassPrefixAscii();
    if (ccPrefixAscii != null) {
      int idx = indexOfCharClass(text, ccPrefixAscii, searchFrom);
      if (idx < 0) {
        hasMatch = false;
        return false;
      }
      effectiveStart = idx;
    }

    // OnePass primary path for small texts: for OnePass-eligible unanchored patterns on short
    // input, scan with OnePass directly. OnePass is faster than BitState — no visited bitmap
    // or job stack allocation, deterministic single-pass traversal per start position.
    if (parentPattern.canOnePassPrimary() && text.length() <= 256) {
      int[] result = parentPattern.onePass().searchUnanchored(
          text, effectiveStart, text.length(), prog.numCaptures());
      if (result != null) {
        groups = result;
        hasMatch = true;
        return true;
      }
      hasMatch = false;
      return false;
    }


    // Fast path: use cached DFA to check if a match exists in the remaining text.
    // Use longest=false for a quick existence check — this returns the earliest match end.
    Dfa.SearchResult fwdResult;
    {
      fwdResult = dfa().doSearch(text, effectiveStart, false, false);
      if (fwdResult != null && !fwdResult.matched()) {
        hasMatch = false;
        return false;
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
    // Skip entirely when the DFA's match start is unreliable (patterns with lazy quantifiers
    // or anchors inside quantifiers). Lazy quantifiers can make a non-leftmost match end earlier,
    // causing the DFA to find the wrong start. In those cases, fall through to the BitState/NFA
    // fallback which correctly handles all semantics.
    //
    // For patterns where the DFA start IS reliable but the end may be wrong (alternation, bounded
    // repeats), the sandwich still narrows the range — capturesResolved is set to false so
    // resolveCaptures() corrects the end position using the submatch engine.
    if (fwdResult != null
        && fwdResult.pos() > effectiveStart
        && parentPattern.dfaStartReliable()) {
      int earlyEnd = fwdResult.pos();

      if (prog.anchorStart()) {
        // Anchored: match start is effectiveStart. Run forward DFA (anchored, longest) to find
        // actual end, then defer inner captures until requested.
        Dfa.SearchResult fwdLongest = dfa().doSearch(text, effectiveStart, true, true);
        if (fwdLongest != null && fwdLongest.matched()) {
          int matchEnd = fwdLongest.pos();
          int nc = prog.numCaptures();
          groups = new int[2 * nc];
          Arrays.fill(groups, -1);
          groups[0] = effectiveStart;
          groups[1] = matchEnd;
          deferredMatchStart = effectiveStart;
          deferredMatchEnd = matchEnd;
          capturesResolved = (nc <= 1) && parentPattern.dfaGroupZeroReliable();
          hasMatch = true;
          return true;
        }
      } else {
        Dfa revDfa = reverseDfa();
        if (revDfa != null) {
          // Step 2: Reverse DFA backward from earliest match end to find match start.
          Dfa.SearchResult revResult =
              revDfa.doSearchReverse(text, earlyEnd, effectiveStart, true, true);
          if (revResult != null && revResult.matched()) {
            int matchStart = revResult.pos();
            // Step 3: Forward DFA anchored at matchStart with longest=true to find actual end.
            Dfa.SearchResult fwdLongest = dfa().doSearch(text, matchStart, true, true);
            if (fwdLongest != null && fwdLongest.matched()) {
              int matchEnd = fwdLongest.pos();
              // Step 4: Store group(0) boundaries, defer inner captures until requested.
              int nc = prog.numCaptures();
              groups = new int[2 * nc];
              Arrays.fill(groups, -1);
              groups[0] = matchStart;
              groups[1] = matchEnd;
              deferredMatchStart = matchStart;
              deferredMatchEnd = matchEnd;
              capturesResolved = (nc <= 1) && parentPattern.dfaGroupZeroReliable();
              hasMatch = true;
              return true;
            }
            // If anchored forward DFA fails, fall through to full search.
          }
          // If reverse DFA bails out, fall through to full search.
        }
      }
    }

    // Fallback: DFA bailed out or reverse DFA unavailable — extract captures with
    // BitState/NFA on the full text from effectiveStart. Pass the full text (not a substring)
    // to avoid O(n) string copy and position-adjustment overhead.
    int[] result = searchWithBitStateOrNfa(
        prog, text, effectiveStart, text.length(), text.length(), false, false, false,
        prog.numCaptures());
    if (result == null) {
      hasMatch = false;
      return false;
    }
    groups = result;
    hasMatch = true;
    return true;
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
    // Try BitState if the full text is small enough for the visited bitmap.
    int maxBitStateLen = BitState.maxTextSize(prog);
    if (maxBitStateLen >= 0 && text.length() <= maxBitStateLen) {
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
      int[] result = bs.doSearch(startPos, searchLimit, anchoredEffective);
      cachedBitState = bs;
      // Return to Pattern's cache for reuse by future Matchers.
      parentPattern.returnBitState(bs);
      // BitState is a complete engine — if it searched and found no match, NFA won't either.
      return result;
    }

    // Fall back to general NFA (only for texts too large for BitState).
    Nfa.Anchor nfaAnchor = anchored ? Nfa.Anchor.ANCHORED : Nfa.Anchor.UNANCHORED;
    Nfa.MatchKind nfaKind;
    if (endMatch) {
      nfaKind = Nfa.MatchKind.FULL_MATCH;
    } else if (longest) {
      nfaKind = Nfa.MatchKind.LONGEST_MATCH;
    } else {
      nfaKind = Nfa.MatchKind.FIRST_MATCH;
    }
    return Nfa.search(prog, text, startPos, searchLimit, nfaAnchor, nfaKind, nsubmatch);
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
  public String group(String name) {
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
    resolveCaptures();
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
    resolveCaptures();
    return groups[2 * group + 1];
  }

  // ---------------------------------------------------------------------------
  // Replacement methods
  // ---------------------------------------------------------------------------

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
   * Replaces every subsequence of the input that matches the pattern with the given replacement
   * string.
   *
   * @param replacement the replacement string
   * @return the string with all matches replaced
   */
  public String replaceAll(String replacement) {
    // Character-class fast path: for patterns like \d+, [a-zA-Z]+, etc. with simple
    // replacement strings (no group references), scan the text in a single pass.
    int[] ccRanges = parentPattern.charClassMatchRanges();
    if (ccRanges != null && !parentPattern.charClassMatchAllowEmpty()
        && isSimpleReplacement(replacement)) {
      return charClassReplaceAll(ccRanges, replacement);
    }

    // Pre-compile the replacement template once, avoiding per-match parseInt/substring overhead.
    ReplacementSegment[] template = compileReplacementTemplate(replacement);

    reset();
    Prog prog = parentPattern.prog();

    // Direct BitState path: when captures will always be needed (group references in the
    // replacement) and the text fits BitState, skip the DFA sandwich entirely. Instead of
    // DFA forward + reverse + anchored (3 passes) then BitState for captures (1 pass), run
    // BitState once per match for combined find + capture. For short text with dense matches,
    // this eliminates ~12% overhead from the per-match DFA engine setup.
    if (templateHasGroupRefs(template)
        && !parentPattern.canOnePassPrimary()
        && BitState.maxTextSize(prog) >= text.length()) {
      return replaceAllDirectBitState(template, prog);
    }

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
   * Returns {@code true} if the compiled template contains any group references (numbered or
   * named). When true, captures will be accessed for every match, making the direct BitState path
   * worthwhile.
   */
  private static boolean templateHasGroupRefs(ReplacementSegment[] template) {
    for (ReplacementSegment seg : template) {
      if (seg instanceof ReplacementSegment.GroupRef
          || seg instanceof ReplacementSegment.NamedGroupRef) {
        return true;
      }
    }
    return false;
  }

  /**
   * Specialized replaceAll loop that uses BitState directly for find + capture in a single pass,
   * bypassing the DFA sandwich. Falls back to prefix acceleration when available.
   */
  private String replaceAllDirectBitState(ReplacementSegment[] template, Prog prog) {
    StringBuilder sb = new StringBuilder();
    int ncap = prog.numCaptures();
    int pos = 0;
    int appPos = 0;

    while (pos <= text.length()) {
      int effectiveStart = pos;

      // Apply prefix acceleration if available.
      String prefix = parentPattern.prefix();
      if (prefix != null) {
        int idx;
        if (parentPattern.prefixFoldCase()) {
          idx = indexOfIgnoreCase(text, prefix, pos);
        } else {
          idx = text.indexOf(prefix, pos);
        }
        if (idx < 0) {
          break;
        }
        effectiveStart = idx;
      }

      // Apply char-class prefix acceleration if available.
      boolean[] ccPrefixAscii = parentPattern.charClassPrefixAscii();
      if (ccPrefixAscii != null) {
        int idx = indexOfCharClass(text, ccPrefixAscii, pos);
        if (idx < 0) {
          break;
        }
        effectiveStart = idx;
      }

      int[] result = searchWithBitStateOrNfa(
          prog, text, effectiveStart, text.length(), text.length(),
          false, false, false, ncap);
      if (result == null) {
        break;
      }
      groups = result;
      capturesResolved = true;
      hasMatch = true;
      sb.append(text, appPos, groups[0]);
      applyReplacementTemplate(sb, template);
      appPos = groups[1];

      // Advance past the match; handle empty matches by advancing one character.
      if (groups[1] == groups[0]) {
        if (groups[0] < text.length()) {
          sb.append(text, groups[0], groups[0] + Character.charCount(text.codePointAt(groups[0])));
          appPos = groups[0] + Character.charCount(text.codePointAt(groups[0]));
        }
        pos = groups[0] + 1;
      } else {
        pos = groups[1];
      }
    }
    sb.append(text, appPos, text.length());
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
    searchFrom = 0;
    appendPos = 0;
    hasMatch = false;
    groups = null;
    capturesResolved = true;
    return this;
  }

  /**
   * Resets this matcher with a new input sequence.
   *
   * @param input the new input character sequence
   * @return this matcher
   */
  public Matcher reset(CharSequence input) {
    this.text = input.toString();
    return reset();
  }

  /**
   * Returns the pattern that is interpreted by this matcher.
   *
   * @return the pattern for which this matcher was created
   */
  public Pattern pattern() {
    return parentPattern;
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
    resolveCaptures();
    return new SnapshotMatchResult(groups.clone(), text, groupCount());
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  /**
   * Resolves deferred capture groups. Called lazily when the user accesses any group
   * (e.g., {@code group(0)}, {@code start(1)}) or when a full snapshot is needed
   * ({@code toMatchResult()}). Runs the submatch engine (OnePass or BitState/NFA) anchored
   * at the DFA-determined match start, bounded by the DFA's match end, but without forcing
   * the match to extend to that end. This allows alternation priority to determine the actual
   * match length (e.g., {@code (fo|foo)} matching "fo" rather than "foo").
   */
  private void resolveCaptures() {
    if (capturesResolved) {
      return;
    }
    Prog prog = parentPattern.prog();
    // Search anchored at matchStart, bounded by matchEnd, but endMatch=false so alternation
    // priority determines the actual match length rather than the DFA's longest-match end.
    int[] result;
    if (parentPattern.canOnePassSubmatch()) {
      result = parentPattern.onePass().search(
          text, deferredMatchStart, deferredMatchEnd, false, prog.numCaptures());
    } else {
      result = searchWithBitStateOrNfa(
          prog, text, deferredMatchStart, deferredMatchEnd, deferredMatchEnd,
          true, false, false, prog.numCaptures());
    }
    if (result != null) {
      groups = result;
    }
    capturesResolved = true;
  }

  private void checkMatch() {
    if (!hasMatch) {
      throw new IllegalStateException("No match found");
    }
  }

  private void checkGroup(int group) {
    if (group < 0 || group > groupCount()) {
      throw new IndexOutOfBoundsException(
          "No group " + group + " (groupCount=" + groupCount() + ")");
    }
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
          int digitStart = i;
          while (i < replacement.length()
              && Character.isDigit(replacement.charAt(i))) {
            i++;
          }
          int groupIdx = Integer.parseInt(
              replacement.substring(digitStart, i));
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

    SnapshotMatchResult(int[] groups, String text, int groupCount) {
      this.groups = groups;
      this.text = text;
      this.groupCount = groupCount;
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
    public int end() {
      return end(0);
    }

    @Override
    public int end(int group) {
      validateGroup(group);
      return groups[2 * group + 1];
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
    public int groupCount() {
      return groupCount;
    }

    private void validateGroup(int group) {
      if (group < 0 || group > groupCount) {
        throw new IndexOutOfBoundsException(
            "No group " + group + " (groupCount=" + groupCount + ")");
      }
    }
  }
}
