// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.util.Objects.requireNonNull;

import java.util.ConcurrentModificationException;

/**
 * A stateful matcher over UTF-8 input whose reported positions are relative byte offsets.
 *
 * <p>The matcher retains its borrowed input and is not thread-safe. The caller must not mutate the
 * input storage while this matcher is in use. Pattern and replacement behavior follows SafeRE's
 * documented Java-oriented semantics; coordinates are UTF-8 byte offsets.
 */
public final class Utf8Matcher {
  private final Pattern pattern;
  private final ArrayUtf8Input input;
  private final Matcher delegate;
  private int appendPosition;
  private boolean replacementFailed;
  private Utf8Input cachedReplacement;
  private Matcher.ReplacementTemplate cachedTemplate;
  private int modCount;
  private boolean appending;

  Utf8Matcher(Pattern pattern, Utf8Input input) {
    this.pattern = requireNonNull(pattern, "pattern");
    this.input = (ArrayUtf8Input) requireNonNull(input, "input");
    delegate = new Matcher(pattern, this.input.scanner());
  }

  /**
   * Attempts to match the entire input against this pattern.
   *
   * @return whether the entire input matches
   */
  public boolean matches() {
    beginMatchOperation();
    return delegate.matches();
  }

  /**
   * Attempts to match a prefix of the input against this pattern.
   *
   * @return whether an input prefix matches
   */
  public boolean lookingAt() {
    beginMatchOperation();
    return delegate.lookingAt();
  }

  /**
   * Attempts to find the next subsequence matching this pattern.
   *
   * @return whether a match was found
   */
  public boolean find() {
    beginMatchOperation();
    return delegate.find();
  }

  /**
   * Resets this matcher to search the whole retained input from its beginning.
   *
   * @return this matcher
   */
  public Utf8Matcher reset() {
    beginMatchOperation();
    delegate.reset();
    resetReplacementState();
    return this;
  }

  /**
   * Sets the byte-offset range searched by this matcher and resets its match state.
   *
   * <p>Both offsets must be UTF-8 code-point boundaries in the retained input.
   *
   * @param start relative byte offset at the start of the region
   * @param end relative byte offset after the end of the region
   * @return this matcher
   * @throws IndexOutOfBoundsException if the range is invalid or either offset is not a code-point
   *     boundary
   */
  public Utf8Matcher region(int start, int end) {
    if (start < 0 || end < start || end > input.length()) {
      throw new IndexOutOfBoundsException(
          "start=" + start + ", end=" + end + ", length=" + input.length());
    }
    Utf8InputScanner scanner = input.scanner();
    if (!scanner.isCodePointBoundary(start) || !scanner.isCodePointBoundary(end)) {
      throw new IndexOutOfBoundsException(
          "Region offsets must be UTF-8 code-point boundaries: start=" + start + ", end=" + end);
    }
    beginMatchOperation();
    delegate.region(start, end);
    resetReplacementState();
    return this;
  }

  /**
   * Returns the relative byte offset at the start of this matcher's region.
   *
   * @return the region start
   */
  public int regionStart() {
    return delegate.regionStart();
  }

  /**
   * Returns the relative byte offset after the end of this matcher's region.
   *
   * @return the region end
   */
  public int regionEnd() {
    return delegate.regionEnd();
  }

  /**
   * Returns the relative byte offset where the previous match started.
   *
   * @return the start of group zero
   * @throws IllegalStateException if there is no current successful match
   */
  public int start() {
    return delegate.start();
  }

  /**
   * Returns the relative byte offset where the specified group started.
   *
   * @param group capture group index
   * @return the group start, or {@code -1} if the group did not participate
   * @throws IllegalStateException if there is no current successful match
   * @throws IndexOutOfBoundsException if {@code group} is invalid
   */
  public int start(int group) {
    return delegate.start(group);
  }

  /**
   * Returns the relative byte offset after the previous match.
   *
   * @return the end of group zero
   * @throws IllegalStateException if there is no current successful match
   */
  public int end() {
    return delegate.end();
  }

  /**
   * Returns the relative byte offset after the specified group.
   *
   * @param group capture group index
   * @return the group end, or {@code -1} if the group did not participate
   * @throws IllegalStateException if there is no current successful match
   * @throws IndexOutOfBoundsException if {@code group} is invalid
   */
  public int end(int group) {
    return delegate.end(group);
  }

  /**
   * Returns the number of capturing groups in the pattern.
   *
   * @return the capture group count
   */
  public int groupCount() {
    return delegate.groupCount();
  }

  /**
   * Appends the unmatched prefix and expanded replacement for the current match.
   *
   * @param sink synchronous destination for output ranges
   * @param replacement UTF-8 replacement using SafeRE's String replacement syntax
   * @return this matcher
   * @throws IllegalStateException if no match is current or replacement previously failed
   * @throws ConcurrentModificationException if a sink callback advances this matcher
   */
  public Utf8Matcher appendReplacement(Utf8Sink sink, Utf8Input replacement) {
    requireNonNull(sink, "sink");
    requireNonNull(replacement, "replacement");
    if (replacementFailed) {
      throw new IllegalStateException("Replacement failed");
    }
    beginAppend();
    int expectedModCount = modCount;
    try {
      delegate.checkMatch();
      ArrayUtf8Input utf8Replacement = (ArrayUtf8Input) replacement;
      Matcher.ReplacementTemplate template = replacementTemplate(utf8Replacement);
      if (template != null && template.needsCaptures()) {
        delegate.resolveReplacementCaptures();
      }
      int matchStart = delegate.start();
      int matchEnd = delegate.end();
      appendRange(sink, appendPosition, matchStart, expectedModCount);
      if (template == null) {
        utf8Replacement.appendRange(sink, 0, utf8Replacement.length());
        checkForConcurrentModification(expectedModCount);
      } else {
        for (Matcher.ReplacementSegment segment : template.segments()) {
          switch (segment) {
            case Matcher.ReplacementSegment.Literal literal -> {
              byte[] bytes = literal.utf8();
              sink.append(bytes, 0, bytes.length);
              checkForConcurrentModification(expectedModCount);
            }
            case Matcher.ReplacementSegment.GroupRef(var group) -> {
              int start = group == 0 ? matchStart : delegate.groupStart(group);
              if (start >= 0) {
                int end = group == 0 ? matchEnd : delegate.groupEnd(group);
                appendRange(sink, start, end, expectedModCount);
              }
            }
          }
        }
      }
      appendPosition = matchEnd;
      return this;
    } catch (RuntimeException | Error e) {
      replacementFailed = true;
      throw e;
    } finally {
      appending = false;
    }
  }

  /**
   * Appends the part of the input following the last replacement.
   *
   * @param sink synchronous destination for the remaining input
   * @throws IllegalStateException if replacement previously failed
   * @throws ConcurrentModificationException if a sink callback advances this matcher
   */
  public void appendTail(Utf8Sink sink) {
    requireNonNull(sink, "sink");
    if (replacementFailed) {
      throw new IllegalStateException("Replacement failed");
    }
    beginAppend();
    int expectedModCount = modCount;
    try {
      appendRange(sink, appendPosition, input.length(), expectedModCount);
      appendPosition = input.length();
    } catch (RuntimeException | Error e) {
      replacementFailed = true;
      throw e;
    } finally {
      appending = false;
    }
  }

  private void appendRange(Utf8Sink sink, int start, int end, int expectedModCount) {
    input.appendRange(sink, start, end);
    checkForConcurrentModification(expectedModCount);
  }

  private void beginAppend() {
    if (appending) {
      throw new ConcurrentModificationException();
    }
    appending = true;
  }

  private void beginMatchOperation() {
    if (appending) {
      throw new ConcurrentModificationException();
    }
    modCount++;
  }

  private void resetReplacementState() {
    appendPosition = 0;
    replacementFailed = false;
  }

  private void checkForConcurrentModification(int expectedModCount) {
    if (modCount != expectedModCount) {
      throw new ConcurrentModificationException();
    }
  }

  private Matcher.ReplacementTemplate replacementTemplate(ArrayUtf8Input replacement) {
    if (replacement != cachedReplacement) {
      cachedTemplate =
          Matcher.isSimpleReplacement(replacement)
              ? null
              : Matcher.compileUtf8ReplacementTemplate(
                  replacement, pattern.numGroups(), pattern.namedGroups());
      cachedReplacement = replacement;
    }
    return cachedTemplate;
  }
}
