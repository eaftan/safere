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

  private final Pattern parentPattern;
  private String text;
  private int[] groups;
  private boolean hasMatch;
  private int searchFrom;
  private int appendPos;

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
    Prog prog = parentPattern.prog();

    // Fast path: try one-pass engine (anchored, with captures, O(n) time).
    OnePass onePass = parentPattern.onePass();
    if (onePass != null) {
      groups = onePass.search(text, true, prog.numCaptures());
      hasMatch = (groups != null);
      return hasMatch;
    }

    // Medium path: use DFA to check if a full match exists.
    Dfa.SearchResult dfaResult = Dfa.search(prog, text, true, true);
    if (dfaResult != null && !dfaResult.matched()) {
      hasMatch = false;
      return false;
    }
    if (dfaResult != null && dfaResult.pos() != text.length()) {
      hasMatch = false;
      return false;
    }

    // Slow path: try BitState (faster than NFA for small texts), then NFA.
    groups = searchWithBitStateOrNfa(
        prog, text, true, false, true, prog.numCaptures());
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
    Prog prog = parentPattern.prog();

    // Fast path: try one-pass engine (anchored, with captures, O(n) time).
    OnePass onePass = parentPattern.onePass();
    if (onePass != null) {
      groups = onePass.search(text, false, prog.numCaptures());
      hasMatch = (groups != null);
      return hasMatch;
    }

    // Medium path: use DFA to check if an anchored match exists.
    Dfa.SearchResult dfaResult = Dfa.search(prog, text, true, false);
    if (dfaResult != null && !dfaResult.matched()) {
      hasMatch = false;
      return false;
    }

    // Slow path: try BitState (faster than NFA for small texts), then NFA.
    groups = searchWithBitStateOrNfa(
        prog, text, true, false, false, prog.numCaptures());
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
    Prog prog = parentPattern.prog();
    String searchText = text.substring(searchFrom);

    // Fast path: use DFA to check if a match exists in the remaining text.
    Dfa.SearchResult dfaResult = Dfa.search(prog, searchText, false, false);
    if (dfaResult != null && !dfaResult.matched()) {
      hasMatch = false;
      return false;
    }

    // DFA says match (or bailed out) — try BitState, then NFA for captures.
    int[] result = searchWithBitStateOrNfa(
        prog, searchText, false, false, false, prog.numCaptures());
    if (result == null) {
      hasMatch = false;
      return false;
    }
    // Adjust positions to be relative to the original text.
    groups = new int[result.length];
    for (int i = 0; i < result.length; i++) {
      groups[i] = (result[i] == -1) ? -1 : result[i] + searchFrom;
    }
    hasMatch = true;
    return true;
  }

  /**
   * Tries BitState first (for small texts), falls back to NFA. This is the final capture-extraction
   * step after DFA/OnePass have been tried or are not applicable.
   */
  private static int[] searchWithBitStateOrNfa(Prog prog, String text, boolean anchored,
      boolean longest, boolean endMatch, int nsubmatch) {
    // Try BitState if the text is small enough.
    int maxBitStateLen = BitState.maxTextSize(prog);
    if (maxBitStateLen >= 0 && text.length() <= maxBitStateLen) {
      int[] result = BitState.search(prog, text, anchored, longest, endMatch, nsubmatch);
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
    return Nfa.search(prog, text, nfaAnchor, nfaKind, nsubmatch);
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
