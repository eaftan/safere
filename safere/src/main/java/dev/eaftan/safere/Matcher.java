// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

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

  private static final int DEFAULT_MAX_DFA_STATES = 10_000;

  private final Pattern parentPattern;
  private String text;
  private int[] groups;
  private boolean hasMatch;
  private int searchFrom;
  private int appendPos;

  /**
   * Cached DFA instance, lazily initialized on first use. The DFA state graph is a property of the
   * compiled program (not the input text), so it persists across {@code reset()} and multiple
   * {@code find()}/{@code matches()} calls. This avoids rebuilding the equivalence class boundaries
   * and state cache on every search.
   */
  private Dfa dfa;

  /**
   * Cached reverse DFA instance for match-start bounding, lazily initialized. Construction is
   * deferred until the second {@code find()} call to avoid penalizing single-find workloads.
   */
  private Dfa reverseDfa;

  /** Number of {@code doFind()} calls made so far. Used to gate reverse DFA construction. */
  private int findCallCount;

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

  /** Returns the cached DFA instance, creating it on first use. */
  private Dfa dfa() {
    if (dfa == null) {
      dfa = new Dfa(parentPattern.prog(), DEFAULT_MAX_DFA_STATES,
          parentPattern.forwardDfaSetup());
    }
    return dfa;
  }

  /** Returns the cached reverse DFA instance, creating it on first use. */
  private Dfa reverseDfa() {
    if (reverseDfa == null && parentPattern.reverseProg() != null) {
      reverseDfa = new Dfa(parentPattern.reverseProg(), DEFAULT_MAX_DFA_STATES,
          parentPattern.reverseDfaSetup());
    }
    return reverseDfa;
  }

  // ---------------------------------------------------------------------------
  // Core matching methods
  // ---------------------------------------------------------------------------

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
        prog, text, 0, text.length(), true, false, true, prog.numCaptures());
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
        prog, text, 0, text.length(), true, false, false, prog.numCaptures());
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
      searchFrom = end();
      if (start() == end()) {
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
      findCallCount++;
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
      findCallCount++;
      if (result != null) {
        groups = result;
        hasMatch = true;
        return true;
      }
      hasMatch = false;
      return false;
    }

    // Skip DFA+sandwich for small texts: when the input is short enough for BitState, use it
    // directly for all find() calls. On the first call this avoids ~500ns DFA construction; on
    // subsequent calls it avoids the three-DFA sandwich overhead (reverse DFA + second forward DFA
    // + substring). For texts ≤256 chars, BitState's O(n×m) cost is comparable to the sandwich's
    // fixed overhead, and the simpler single-pass search is faster overall.
    if (text.length() <= 256) {
      int maxBitStateLen = BitState.maxTextSize(prog);
      if (maxBitStateLen >= 0 && text.length() <= maxBitStateLen) {
        int[] result = searchWithBitStateOrNfa(
            prog, text, effectiveStart, text.length(), false, false, false, prog.numCaptures());
        findCallCount++;
        if (result == null) {
          hasMatch = false;
          return false;
        }
        groups = result;
        hasMatch = true;
        return true;
      }
    }

    // Fast path: use cached DFA to check if a match exists in the remaining text.
    // Use longest=false for a quick existence check — this returns the earliest match end.
    Dfa.SearchResult fwdResult;
    {
      fwdResult = dfa().doSearch(text, effectiveStart, false, false);
      if (fwdResult != null && !fwdResult.matched()) {
        findCallCount++;
        hasMatch = false;
        return false;
      }
    }

    // Three-DFA sandwich (like RE2): forward DFA found earliest match end above. Now use the
    // reverse DFA to find match start, then a second forward DFA pass (anchored, longest) to find
    // the actual match end. This lets NFA/BitState run on a tight [start, end] range.
    //
    // Only attempt after the first find() call to avoid penalizing single-find workloads with
    // cold reverse DFA construction costs (~3000ns). The reverse DFA is lazily constructed on the
    // second call and cached for all subsequent calls.
    //
    // Skip when the forward DFA detected an empty match (earlyEnd == effectiveStart): the
    // sandwich uses longest-match for the final forward pass, which would incorrectly expand an
    // empty match into a longer one for nullable patterns like (|a)*.
    //
    // Skip the reverse DFA phase when the pattern is anchored at the start — the match start is
    // already known to be effectiveStart, so the reverse scan is unnecessary.
    if (fwdResult != null && findCallCount > 0
        && fwdResult.pos() > effectiveStart) {
      int earlyEnd = fwdResult.pos();

      if (prog.anchorStart()) {
        // Anchored: match start is effectiveStart. Run forward DFA (anchored, longest) to find
        // actual end, then extract captures on the tight range.
        Dfa.SearchResult fwdLongest = dfa().doSearch(text, effectiveStart, true, true);
        if (fwdLongest != null && fwdLongest.matched()) {
          int matchEnd = fwdLongest.pos();
          int[] result = searchSubmatch(
              prog, text, effectiveStart, matchEnd, prog.numCaptures());
          if (result != null) {
            groups = result;
            findCallCount++;
            hasMatch = true;
            return true;
          }
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
              // Step 4: Extract captures on tight [matchStart, matchEnd] range.
              int[] result = searchSubmatch(
                  prog, text, matchStart, matchEnd, prog.numCaptures());
              if (result != null) {
                groups = result;
                findCallCount++;
                hasMatch = true;
                return true;
              }
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
        prog, text, effectiveStart, text.length(), false, false, false, prog.numCaptures());
    findCallCount++;
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
   * Extracts submatch groups from a known match range. Creates a substring of the match range and
   * tries OnePass first (single-pass, O(n)), then falls back to BitState/NFA. Positions in the
   * returned array are adjusted to be relative to the original text.
   *
   * <p>Following C++ RE2's engine priority (re2.cc:885–897): OnePass > BitState > NFA.
   *
   * @param prog the compiled program
   * @param text the full input text
   * @param matchStart start of the known match range in {@code text}
   * @param matchEnd end of the known match range in {@code text}
   * @param nsubmatch number of submatch groups to track (including group 0)
   * @return submatch positions relative to {@code text}, or null if no match
   */
  private int[] searchSubmatch(Prog prog, String text, int matchStart, int matchEnd,
      int nsubmatch) {
    if (parentPattern.canOnePassSubmatch()) {
      // OnePass with offset bounds — no substring allocation needed. The endMatch=true
      // constraint ensures the match reaches exactly matchEnd within the bounded range.
      return parentPattern.onePass().search(text, matchStart, matchEnd, true, nsubmatch);
    }
    // Non-OnePass: BitState/NFA still need a substring since they don't support endPos bounds.
    String searchText = text.substring(matchStart, matchEnd);
    int[] result = searchWithBitStateOrNfa(
        prog, searchText, 0, searchText.length(), true, false, true, nsubmatch);
    if (result == null) {
      return null;
    }
    // Adjust positions from substring-relative to text-relative.
    for (int i = 0; i < result.length; i++) {
      if (result[i] != -1) {
        result[i] += matchStart;
      }
    }
    return result;
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
   * @param anchored whether the search is anchored at {@code startPos}
   * @param longest whether to find the longest match
   * @param endMatch whether the match must extend to the end of the text
   * @param nsubmatch number of submatch groups to track (including group 0)
   * @return submatch positions relative to {@code text}, or null if no match
   */
  private static int[] searchWithBitStateOrNfa(Prog prog, String text, int startPos,
      int searchLimit, boolean anchored, boolean longest, boolean endMatch, int nsubmatch) {
    // Try BitState if the text is small enough.
    int maxBitStateLen = BitState.maxTextSize(prog);
    if (maxBitStateLen >= 0 && text.length() <= maxBitStateLen) {
      int[] result =
          BitState.search(prog, text, startPos, searchLimit, anchored, longest, endMatch,
              nsubmatch);
      if (result != null) {
        return result;
      }
    }

    // Fall back to general NFA.
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
    reset();
    StringBuilder sb = new StringBuilder();
    while (find()) {
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
    return new SnapshotMatchResult(groups.clone(), text, groupCount());
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

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
