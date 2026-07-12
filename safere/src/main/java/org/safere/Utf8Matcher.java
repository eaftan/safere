// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import java.util.ConcurrentModificationException;
import java.util.Map;

/**
 * A stateful matcher over UTF-8 input whose reported positions are relative byte offsets.
 *
 * <p>The matcher retains its borrowed input and is not thread-safe. The caller must not mutate the
 * input storage while this matcher is in use. This API is provisional while the Trino integration
 * is validated.
 */
public final class Utf8Matcher {
  private final Pattern pattern;
  private final ArrayUtf8Input input;
  private final Matcher delegate;
  private int appendPosition;
  private boolean replacementFailed;
  private Utf8Input cachedReplacement;
  private Matcher.ReplacementSegment[] cachedTemplate;
  private int modCount;
  private boolean appending;

  Utf8Matcher(Pattern pattern, Utf8Input input) {
    this.pattern = requireNonNull(pattern, "pattern");
    this.input = (ArrayUtf8Input) requireNonNull(input, "input");
    delegate = new Matcher(pattern, this.input.scanner());
  }

  /**
   * Attempts to find the next subsequence matching this pattern.
   *
   * @return whether a match was found
   */
  public boolean find() {
    modCount++;
    return delegate.find();
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
      Matcher.ReplacementSegment[] template = replacementTemplate(replacement);
      validateNamedGroups(template);
      int[] bounds = captureBounds();
      appendRange(sink, appendPosition, bounds[0], expectedModCount);
      for (Matcher.ReplacementSegment segment : template) {
        switch (segment) {
          case Matcher.ReplacementSegment.Literal(var text) -> {
            byte[] bytes = text.getBytes(UTF_8);
            sink.append(bytes, 0, bytes.length);
            checkForConcurrentModification(expectedModCount);
          }
          case Matcher.ReplacementSegment.GroupRef(var group) ->
              appendGroup(sink, bounds, group, expectedModCount);
          case Matcher.ReplacementSegment.NamedGroupRef(var name) -> {
            int group = pattern.namedGroups().get(name);
            appendGroup(sink, bounds, group, expectedModCount);
          }
        }
      }
      appendPosition = bounds[1];
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

  private void appendGroup(Utf8Sink sink, int[] bounds, int group, int expectedModCount) {
    int start = bounds[group * 2];
    if (start >= 0) {
      appendRange(sink, start, bounds[group * 2 + 1], expectedModCount);
    }
  }

  private int[] captureBounds() {
    int[] bounds = new int[(groupCount() + 1) * 2];
    for (int group = 0; group <= groupCount(); group++) {
      bounds[group * 2] = start(group);
      bounds[group * 2 + 1] = end(group);
    }
    return bounds;
  }

  private void validateNamedGroups(Matcher.ReplacementSegment[] template) {
    Map<String, Integer> namedGroups = pattern.namedGroups();
    for (Matcher.ReplacementSegment segment : template) {
      if (segment instanceof Matcher.ReplacementSegment.NamedGroupRef(var name)
          && !namedGroups.containsKey(name)) {
        throw new IllegalArgumentException("No group with name <" + name + ">");
      }
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

  private void checkForConcurrentModification(int expectedModCount) {
    if (modCount != expectedModCount) {
      throw new ConcurrentModificationException();
    }
  }

  private Matcher.ReplacementSegment[] replacementTemplate(Utf8Input replacement) {
    if (replacement != cachedReplacement) {
      String text = ((ArrayUtf8Input) replacement).decode();
      cachedTemplate = Matcher.compileReplacementTemplate(text, groupCount());
      cachedReplacement = replacement;
    }
    return cachedTemplate;
  }
}
