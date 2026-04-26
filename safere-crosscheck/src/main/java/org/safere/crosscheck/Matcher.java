// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.crosscheck;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.regex.MatchResult;

/**
 * A crosscheck wrapper that delegates every matcher operation to both SafeRE and
 * {@code java.util.regex}, comparing results and throwing {@link CrosscheckException} on
 * divergence.
 *
 * <p>This class has the same API as {@link org.safere.Matcher} and
 * {@link java.util.regex.Matcher}, so switching to crosscheck mode requires only changing the
 * import. Every method call is recorded by a {@link TraceRecorder}; the trace is included in any
 * {@link CrosscheckException} and can be retrieved via {@link #getTrace()}.
 *
 * <p>Like {@link java.util.regex.Matcher}, this class is not thread-safe.
 */
public final class Matcher implements MatchResult {

  private final Pattern crosscheckPattern;
  private final org.safere.Matcher safereMatcher;
  private final java.util.regex.Matcher jdkMatcher;
  private final TraceRecorder trace = new TraceRecorder();

  Matcher(Pattern pattern, CharSequence input) {
    this.crosscheckPattern = pattern;
    this.safereMatcher = pattern.saferePattern().matcher(input);
    this.jdkMatcher = pattern.jdkPattern().matcher(input);
  }

  // ---------------------------------------------------------------------------
  // Matching
  // ---------------------------------------------------------------------------

  /** Attempts to match the entire input against the pattern. */
  public boolean matches() {
    boolean sr = safereMatcher.matches();
    boolean jr = jdkMatcher.matches();
    checkBoolean("matches", "", sr, jr);
    return sr;
  }

  /** Attempts to match the input, starting at the beginning, against the pattern. */
  public boolean lookingAt() {
    boolean sr = safereMatcher.lookingAt();
    boolean jr = jdkMatcher.lookingAt();
    checkBoolean("lookingAt", "", sr, jr);
    return sr;
  }

  /** Finds the next subsequence that matches the pattern. */
  public boolean find() {
    boolean sr = safereMatcher.find();
    boolean jr = jdkMatcher.find();
    checkBoolean("find", "", sr, jr);
    if (sr && jr) {
      checkMatchState("find");
    }
    return sr;
  }

  /** Resets and finds starting at the given index. */
  public boolean find(int start) {
    boolean sr = safereMatcher.find(start);
    boolean jr = jdkMatcher.find(start);
    checkBoolean("find", String.valueOf(start), sr, jr);
    if (sr && jr) {
      checkMatchState("find(" + start + ")");
    }
    return sr;
  }

  // ---------------------------------------------------------------------------
  // Groups
  // ---------------------------------------------------------------------------

  /** Returns the number of capturing groups. */
  @Override
  public int groupCount() {
    int sr = safereMatcher.groupCount();
    int jr = jdkMatcher.groupCount();
    checkEqual("groupCount", "", sr, jr);
    return sr;
  }

  /** Returns the input subsequence matched by the previous match (group 0). */
  @Override
  public String group() {
    String sr = safereMatcher.group();
    String jr = jdkMatcher.group();
    checkEqual("group", "", sr, jr);
    return sr;
  }

  /** Returns the input subsequence captured by the given group. */
  @Override
  public String group(int group) {
    String sr = safereMatcher.group(group);
    String jr = jdkMatcher.group(group);
    checkEqual("group", String.valueOf(group), sr, jr);
    return sr;
  }

  /** Returns the input subsequence captured by the given named group. */
  public String group(String name) {
    String sr = safereMatcher.group(name);
    String jr = jdkMatcher.group(name);
    checkEqual("group", quote(name), sr, jr);
    return sr;
  }

  /** Returns the start index of the previous match (group 0). */
  @Override
  public int start() {
    int sr = safereMatcher.start();
    int jr = jdkMatcher.start();
    checkEqual("start", "", sr, jr);
    return sr;
  }

  /** Returns the start index of the given group. */
  @Override
  public int start(int group) {
    int sr = safereMatcher.start(group);
    int jr = jdkMatcher.start(group);
    checkEqual("start", String.valueOf(group), sr, jr);
    return sr;
  }

  /** Returns the start index of the given named group. */
  public int start(String name) {
    int sr = safereMatcher.start(name);
    int jr = jdkMatcher.start(name);
    checkEqual("start", quote(name), sr, jr);
    return sr;
  }

  /** Returns the end index (exclusive) of the previous match (group 0). */
  @Override
  public int end() {
    int sr = safereMatcher.end();
    int jr = jdkMatcher.end();
    checkEqual("end", "", sr, jr);
    return sr;
  }

  /** Returns the end index (exclusive) of the given group. */
  @Override
  public int end(int group) {
    int sr = safereMatcher.end(group);
    int jr = jdkMatcher.end(group);
    checkEqual("end", String.valueOf(group), sr, jr);
    return sr;
  }

  /** Returns the end index (exclusive) of the given named group. */
  public int end(String name) {
    int sr = safereMatcher.end(name);
    int jr = jdkMatcher.end(name);
    checkEqual("end", quote(name), sr, jr);
    return sr;
  }

  // ---------------------------------------------------------------------------
  // Replacement
  // ---------------------------------------------------------------------------

  /** Replaces the first match with the given replacement string. */
  public String replaceFirst(String replacement) {
    String sr = safereMatcher.replaceFirst(replacement);
    String jr = jdkMatcher.replaceFirst(replacement);
    checkEqual("replaceFirst", quote(replacement), sr, jr);
    return sr;
  }

  /** Replaces all matches with the given replacement string. */
  public String replaceAll(String replacement) {
    String sr = safereMatcher.replaceAll(replacement);
    String jr = jdkMatcher.replaceAll(replacement);
    checkEqual("replaceAll", quote(replacement), sr, jr);
    return sr;
  }

  /**
   * Implements a non-terminal append-and-replace step, applying to both engines and comparing the
   * appended fragment.
   *
   * <p>Note: the caller's {@code StringBuilder} receives SafeRE's output.
   */
  public Matcher appendReplacement(StringBuilder sb, String replacement) {
    StringBuilder safeSb = new StringBuilder();
    StringBuilder jdkSb = new StringBuilder();
    safereMatcher.appendReplacement(safeSb, replacement);
    jdkMatcher.appendReplacement(jdkSb, replacement);
    String sr = safeSb.toString();
    String jr = jdkSb.toString();
    checkEqual("appendReplacement", quote(replacement), sr, jr);
    sb.append(sr);
    return this;
  }

  /**
   * Implements a terminal append step. Compares the tail fragment from both engines.
   *
   * @throws CrosscheckException if the appended tail fragments differ
   */
  public StringBuilder appendTail(StringBuilder sb) {
    StringBuilder safeSb = new StringBuilder();
    StringBuilder jdkSb = new StringBuilder();
    safereMatcher.appendTail(safeSb);
    jdkMatcher.appendTail(jdkSb);
    String sr = safeSb.toString();
    String jr = jdkSb.toString();
    checkEqual("appendTail", "", sr, jr);
    sb.append(sr);
    return sb;
  }

  /** Implements a non-terminal append-and-replace step using {@link StringBuffer}. */
  public Matcher appendReplacement(StringBuffer sb, String replacement) {
    StringBuffer safeSbuf = new StringBuffer();
    StringBuffer jdkSbuf = new StringBuffer();
    safereMatcher.appendReplacement(safeSbuf, replacement);
    jdkMatcher.appendReplacement(jdkSbuf, replacement);
    String sr = safeSbuf.toString();
    String jr = jdkSbuf.toString();
    checkEqual("appendReplacement", quote(replacement), sr, jr);
    sb.append(sr);
    return this;
  }

  /** Implements a terminal append step using {@link StringBuffer}. */
  public StringBuffer appendTail(StringBuffer sb) {
    StringBuffer safeSbuf = new StringBuffer();
    StringBuffer jdkSbuf = new StringBuffer();
    safereMatcher.appendTail(safeSbuf);
    jdkMatcher.appendTail(jdkSbuf);
    String sr = safeSbuf.toString();
    String jr = jdkSbuf.toString();
    checkEqual("appendTail", "", sr, jr);
    sb.append(sr);
    return sb;
  }

  /** Returns a literal replacement string. */
  public static String quoteReplacement(String s) {
    return java.util.regex.Matcher.quoteReplacement(s);
  }

  // ---------------------------------------------------------------------------
  // State management
  // ---------------------------------------------------------------------------

  /** Resets this matcher. */
  public Matcher reset() {
    safereMatcher.reset();
    jdkMatcher.reset();
    trace.recordMatch("reset", "", "void");
    return this;
  }

  /** Resets this matcher with a new input. */
  public Matcher reset(CharSequence input) {
    safereMatcher.reset(input);
    jdkMatcher.reset(input);
    trace.recordMatch("reset", quote(input), "void");
    return this;
  }

  /** Sets the region of the input for matching. */
  public Matcher region(int start, int end) {
    safereMatcher.region(start, end);
    jdkMatcher.region(start, end);
    trace.recordMatch("region", start + ", " + end, "void");
    return this;
  }

  /** Returns the start of the matching region. */
  public int regionStart() {
    int sr = safereMatcher.regionStart();
    int jr = jdkMatcher.regionStart();
    checkEqual("regionStart", "", sr, jr);
    return sr;
  }

  /** Returns the end of the matching region. */
  public int regionEnd() {
    int sr = safereMatcher.regionEnd();
    int jr = jdkMatcher.regionEnd();
    checkEqual("regionEnd", "", sr, jr);
    return sr;
  }

  /** Returns whether this matcher uses transparent bounds. */
  public boolean hasTransparentBounds() {
    boolean sr = safereMatcher.hasTransparentBounds();
    boolean jr = jdkMatcher.hasTransparentBounds();
    checkBoolean("hasTransparentBounds", "", sr, jr);
    return sr;
  }

  /** Sets whether this matcher uses transparent bounds. */
  public Matcher useTransparentBounds(boolean b) {
    safereMatcher.useTransparentBounds(b);
    jdkMatcher.useTransparentBounds(b);
    trace.recordMatch("useTransparentBounds", String.valueOf(b), "void");
    return this;
  }

  /** Returns whether this matcher uses anchoring bounds. */
  public boolean hasAnchoringBounds() {
    boolean sr = safereMatcher.hasAnchoringBounds();
    boolean jr = jdkMatcher.hasAnchoringBounds();
    checkBoolean("hasAnchoringBounds", "", sr, jr);
    return sr;
  }

  /** Sets whether this matcher uses anchoring bounds. */
  public Matcher useAnchoringBounds(boolean b) {
    safereMatcher.useAnchoringBounds(b);
    jdkMatcher.useAnchoringBounds(b);
    trace.recordMatch("useAnchoringBounds", String.valueOf(b), "void");
    return this;
  }

  /** Returns whether the last match hit the end of input. */
  public boolean hitEnd() {
    boolean sr = safereMatcher.hitEnd();
    boolean jr = jdkMatcher.hitEnd();
    checkBoolean("hitEnd", "", sr, jr);
    return sr;
  }

  /** Returns whether more input could change a positive match into a negative one. */
  public boolean requireEnd() {
    boolean sr = safereMatcher.requireEnd();
    boolean jr = jdkMatcher.requireEnd();
    checkBoolean("requireEnd", "", sr, jr);
    return sr;
  }

  /** Returns the crosscheck pattern associated with this matcher. */
  public Pattern pattern() {
    return crosscheckPattern;
  }

  /** Returns the named groups from the pattern. */
  public Map<String, Integer> namedGroups() {
    return crosscheckPattern.namedGroups();
  }

  /** Returns a snapshot of the current match result. */
  public MatchResult toMatchResult() {
    // Return SafeRE's result — the match state has already been crosschecked.
    return safereMatcher.toMatchResult();
  }

  // ---------------------------------------------------------------------------
  // Trace access
  // ---------------------------------------------------------------------------

  /** Returns the trace recorder for this matcher. */
  public TraceRecorder getTrace() {
    return trace;
  }

  // ---------------------------------------------------------------------------
  // Internal helpers
  // ---------------------------------------------------------------------------

  /**
   * After a successful find/matches/lookingAt on both engines, verify that the match positions
   * and groups agree.
   */
  private void checkMatchState(String context) {
    int srStart = safereMatcher.start();
    int jrStart = jdkMatcher.start();
    if (srStart != jrStart) {
      trace.recordDivergence(context + ".start", "", srStart, jrStart);
      throwDivergence(context + " → start()", "", srStart, jrStart);
    }

    int srEnd = safereMatcher.end();
    int jrEnd = jdkMatcher.end();
    if (srEnd != jrEnd) {
      trace.recordDivergence(context + ".end", "", srEnd, jrEnd);
      throwDivergence(context + " → end()", "", srEnd, jrEnd);
    }

    int srGroupCount = safereMatcher.groupCount();
    int jrGroupCount = jdkMatcher.groupCount();
    if (srGroupCount != jrGroupCount) {
      trace.recordDivergence(context + ".groupCount", "", srGroupCount, jrGroupCount);
      throwDivergence(context + " → groupCount()", "", srGroupCount, jrGroupCount);
    }

    for (int i = 1; i <= srGroupCount; i++) {
      String srGroup = safereMatcher.group(i);
      String jrGroup = jdkMatcher.group(i);
      if (!Objects.equals(srGroup, jrGroup)) {
        trace.recordDivergence(context + ".group", String.valueOf(i), srGroup, jrGroup);
        throwDivergence(context + " → group(" + i + ")", "", srGroup, jrGroup);
      }
    }

    trace.recordMatch(context, "", "start=" + srStart + ", end=" + srEnd);
  }

  private void checkBoolean(String method, String args, boolean sr, boolean jr) {
    if (sr != jr) {
      trace.recordDivergence(method, args, sr, jr);
      throwDivergence(method, args, sr, jr);
    }
    trace.recordMatch(method, args, sr);
  }

  private void checkEqual(String method, String args, Object sr, Object jr) {
    if (!Objects.equals(sr, jr)) {
      trace.recordDivergence(method, args, sr, jr);
      throwDivergence(method, args, sr, jr);
    }
    trace.recordMatch(method, args, sr);
  }

  private void throwDivergence(String method, String args, Object sr, Object jr) {
    throw new CrosscheckException(
        method,
        args,
        Objects.toString(sr),
        Objects.toString(jr),
        trace.format());
  }

  private static String quote(CharSequence s) {
    return "\"" + s + "\"";
  }
}
