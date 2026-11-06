// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.re2ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A matcher for a {@link RE2FfmPattern} against an input string. This benchmark-oriented wrapper
 * exposes selected matcher operations while retaining RE2 match semantics.
 *
 * <p>Match offsets from RE2 are in UTF-8 byte positions. This class converts them to Java char
 * (UTF-16) positions for the public API.
 */
public final class RE2FfmMatcher {

  // The RE2::Anchor values accepted by re2_match.
  private static final int UNANCHORED = 0;
  private static final int ANCHOR_START = 1;
  private static final int ANCHOR_BOTH = 2;

  private final RE2FfmPattern pattern;
  private String inputString;
  private byte[] inputUtf8;

  private int[] charToByteMap;

  // Match state: byte offsets from RE2. Length = 2 * (numGroups + 1).
  // [start0, end0, start1, end1, ...]
  private int[] matchByteOffsets;
  private boolean matched;

  // Current search position in UTF-8 bytes.
  private int searchBytePos;
  private int regionStart;
  private int regionEnd;
  private int regionStartByte;
  private int regionEndByte;

  RE2FfmMatcher(RE2FfmPattern pattern, String input) {
    this.pattern = pattern;
    reset(input);
  }

  RE2FfmMatcher(RE2FfmPattern pattern, String input, byte[] inputUtf8) {
    this.pattern = pattern;
    this.inputString = input;
    this.inputUtf8 = inputUtf8;
    this.matchByteOffsets = new int[2 * (pattern.numGroups() + 1)];
    buildCharToByteMap(inputString, inputUtf8);
    reset();
  }

  /**
   * Resets this matcher with a new input string.
   *
   * @param input the new input text
   * @return this matcher
   */
  public RE2FfmMatcher reset(String input) {
    this.inputString = input;
    this.inputUtf8 = input.getBytes(StandardCharsets.UTF_8);
    buildCharToByteMap(inputString, inputUtf8);
    this.matchByteOffsets = new int[2 * (pattern.numGroups() + 1)];
    return reset();
  }

  /** Resets match state and restores the region to the entire current input. */
  public RE2FfmMatcher reset() {
    this.matched = false;
    this.searchBytePos = 0;
    this.regionStart = 0;
    this.regionEnd = inputString.length();
    this.regionStartByte = 0;
    this.regionEndByte = inputUtf8.length;
    return this;
  }

  /**
   * Sets an opaque, anchoring region using UTF-16 indices into the original input.
   *
   * <p>The start is inclusive and the end is exclusive. Captures retain absolute UTF-16 offsets.
   * Region boundaries act as input boundaries for RE2 anchors and word boundaries. This operation
   * discards the previous match and restarts searching at the region start. Bounds that split a
   * surrogate pair are unsupported; RE2 consumes UTF-8 code points.
   *
   * @param start the inclusive UTF-16 start
   * @param end the exclusive UTF-16 end
   * @return this matcher
   * @throws IndexOutOfBoundsException if the bounds are outside the input or start exceeds end
   * @throws IllegalArgumentException if either bound splits a surrogate pair
   */
  public RE2FfmMatcher region(int start, int end) {
    if (start < 0 || end < start || end > inputString.length()) {
      throw new IndexOutOfBoundsException("Invalid region: " + start + ", " + end);
    }
    checkCodePointBoundary(start);
    checkCodePointBoundary(end);
    regionStart = start;
    regionEnd = end;
    regionStartByte = charOffsetToByteOffset(start);
    regionEndByte = charOffsetToByteOffset(end);
    searchBytePos = regionStartByte;
    matched = false;
    return this;
  }

  /** Returns the inclusive UTF-16 region start. */
  public int regionStart() {
    return regionStart;
  }

  /** Returns the exclusive UTF-16 region end. */
  public int regionEnd() {
    return regionEnd;
  }

  /**
   * Attempts to match the entire region against the pattern.
   *
   * @return true if the entire region matches
   */
  public boolean matches() {
    return match(regionStartByte, ANCHOR_BOTH);
  }

  /**
   * Matches a prefix of the region using RE2 start anchoring, without scanning later positions.
   *
   * @return true if a prefix matches
   */
  public boolean lookingAt() {
    return match(regionStartByte, ANCHOR_START);
  }

  /**
   * Finds the next match within the region.
   *
   * <p>Successful match operations advance the next search to the match end. Empty matches advance
   * by one Unicode code point, retaining RE2's UTF-8 boundary semantics rather than Java's UTF-16
   * empty-match progression. Repeated {@code find()} calls after a failed search remain exhausted.
   * {@link #lookingAt()} and {@link #matches()} always restart at the region beginning.
   *
   * @return true if a match was found
   */
  public boolean find() {
    return match(searchBytePos, UNANCHORED);
  }

  private boolean match(int startByte, int anchor) {
    if (startByte > regionEndByte) {
      matched = false;
      return false;
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment textSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, inputUtf8);
      MemorySegment matchesSeg = arena.allocate(ValueLayout.JAVA_INT, matchByteOffsets.length);
      matched =
          Re2Shim.match(
              pattern.nativeHandle(),
              textSeg,
              inputUtf8.length,
              regionStartByte,
              regionEndByte,
              startByte,
              anchor,
              matchesSeg,
              pattern.numGroups());
      if (matched) {
        MemorySegment.copy(
            matchesSeg, ValueLayout.JAVA_INT, 0, matchByteOffsets, 0, matchByteOffsets.length);
        searchBytePos =
            matchByteOffsets[0] == matchByteOffsets[1]
                ? nextCodePointByteOffset(matchByteOffsets[1])
                : matchByteOffsets[1];
      } else {
        searchBytePos = regionEndByte + 1;
      }
    }
    return matched;
  }

  /**
   * Resets the matcher to the whole input and searches from the given UTF-16 position.
   *
   * @param start the char position to start searching from
   * @return true if a match was found
   * @throws IndexOutOfBoundsException if start is outside the input
   * @throws IllegalArgumentException if start splits a surrogate pair
   */
  public boolean find(int start) {
    if (start < 0 || start > inputString.length()) {
      throw new IndexOutOfBoundsException("Invalid start: " + start);
    }
    checkCodePointBoundary(start);
    reset();
    searchBytePos = charOffsetToByteOffset(start);
    return find();
  }

  /**
   * Returns the matched text for the given group.
   *
   * @param group the group index (0 for the whole match)
   * @return the matched text, or null if the group didn't participate in the match
   */
  public String group(int group) {
    checkMatch();
    int startByte = matchByteOffsets[2 * group];
    int endByte = matchByteOffsets[2 * group + 1];
    if (startByte < 0) {
      return null;
    }
    return new String(inputUtf8, startByte, endByte - startByte, StandardCharsets.UTF_8);
  }

  /**
   * Returns the entire matched text (group 0).
   *
   * @return the matched text
   */
  public String group() {
    return group(0);
  }

  /**
   * Returns the start char position of the given group.
   *
   * @param group the group index
   * @return the start position, or -1 if the group didn't participate
   */
  public int start(int group) {
    checkMatch();
    int startByte = matchByteOffsets[2 * group];
    if (startByte < 0) return -1;
    return byteOffsetToCharOffset(startByte);
  }

  /** Returns the start char position of the whole match. */
  public int start() {
    return start(0);
  }

  /**
   * Returns the end char position of the given group.
   *
   * @param group the group index
   * @return the end position, or -1 if the group didn't participate
   */
  public int end(int group) {
    checkMatch();
    int endByte = matchByteOffsets[2 * group + 1];
    if (endByte < 0) return -1;
    return byteOffsetToCharOffset(endByte);
  }

  /** Returns the end char position of the whole match. */
  public int end() {
    return end(0);
  }

  /** Returns the number of capturing groups in the pattern. */
  public int groupCount() {
    return pattern.numGroups();
  }

  /**
   * Replaces every match in the input with the given replacement string. Supports backreferences
   * ($1, $2, etc.) by converting them to RE2's \1, \2 syntax.
   *
   * @param replacement the replacement string
   * @return the result string
   */
  public String replaceAll(String replacement) {
    reset();
    String rewrite = translateToRE2Rewrite(replacement);
    if (rewrite != null) {
      byte[] rewriteBytes = rewrite.getBytes(StandardCharsets.UTF_8);
      String result =
          Re2Shim.replaceAll(pattern.nativeHandle(), inputUtf8, inputString, rewriteBytes).result();
      this.matched = false;
      this.searchBytePos = inputUtf8.length + 1;
      return result;
    }
    return replaceAllJava(replacement);
  }

  /**
   * Replaces the first match in the input with the given replacement string.
   *
   * @param replacement the replacement string
   * @return the result string
   */
  public String replaceFirst(String replacement) {
    reset();
    if (!find()) {
      return inputString;
    }
    StringBuilder sb = new StringBuilder();
    // Append text before the match.
    int matchStartChar = start();
    sb.append(inputString, 0, matchStartChar);

    // Build the replacement from the rewrite template.
    appendReplacement(sb, replacement);

    // Append text after the match.
    int matchEndChar = end();
    sb.append(inputString, matchEndChar, inputString.length());

    return sb.toString();
  }

  // --- Private helpers ---

  /** Expands Java-style numeric group references and escapes against the current match. */
  private void appendReplacement(StringBuilder sb, String replacement) {
    int maxGroup = pattern.numGroups();
    for (int i = 0; i < replacement.length(); i++) {
      char c = replacement.charAt(i);
      if (c == '\\') {
        if (++i >= replacement.length()) {
          throw new IllegalArgumentException("character to be escaped is missing");
        }
        sb.append(replacement.charAt(i));
      } else if (c == '$') {
        if (++i >= replacement.length() || !Character.isDigit(replacement.charAt(i))) {
          throw new IllegalArgumentException("Illegal group reference");
        }
        int groupNum = replacement.charAt(i) - '0';
        if (groupNum > maxGroup) {
          throw new IndexOutOfBoundsException("No group " + groupNum);
        }
        while (i + 1 < replacement.length() && Character.isDigit(replacement.charAt(i + 1))) {
          int candidate = groupNum * 10 + (replacement.charAt(i + 1) - '0');
          if (candidate > maxGroup) {
            break;
          }
          groupNum = candidate;
          i++;
        }
        String group = group(groupNum);
        if (group != null) {
          sb.append(group);
        }
      } else {
        sb.append(c);
      }
    }
  }

  private String translateToRE2Rewrite(String replacement) {
    int numGroups = pattern.numGroups();
    if (numGroups > 9) {
      return null;
    }
    if (replacement.indexOf('{') >= 0) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < replacement.length(); i++) {
      char c = replacement.charAt(i);
      if (c == '\\') {
        if (++i >= replacement.length()) {
          return null;
        }
        char next = replacement.charAt(i);
        if (next == '$') {
          sb.append('$');
        } else if (next == '\\') {
          sb.append("\\\\");
        } else {
          sb.append(next);
        }
      } else if (c == '$') {
        if (++i >= replacement.length()) {
          return null;
        }
        char next = replacement.charAt(i);
        if (next >= '0' && next <= '9') {
          if (i + 1 < replacement.length() && Character.isDigit(replacement.charAt(i + 1))) {
            return null;
          }
          int groupIndex = next - '0';
          if (groupIndex > numGroups) {
            return null;
          }
          sb.append('\\').append(next);
        } else {
          return null;
        }
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private String replaceAllJava(String replacement) {
    StringBuilder sb = new StringBuilder();
    int lastAppend = 0;
    while (find()) {
      int matchStartChar = start();
      sb.append(inputString, lastAppend, matchStartChar);
      appendReplacement(sb, replacement);
      lastAppend = end();
    }
    sb.append(inputString, lastAppend, inputString.length());
    return sb.toString();
  }

  private int nextCodePointByteOffset(int byteOffset) {
    if (byteOffset >= regionEndByte) {
      return regionEndByte + 1;
    }
    int charOffset = byteOffsetToCharOffset(byteOffset);
    int nextCharOffset = charOffset + Character.charCount(inputString.codePointAt(charOffset));
    return charOffsetToByteOffset(nextCharOffset);
  }

  private void buildCharToByteMap(String s, byte[] bytes) {
    if (bytes.length == s.length()) {
      boolean ok = true;
      for (int i = 0; i < bytes.length; i++) {
        if (bytes[i] == '?' && s.charAt(i) != '?') {
          ok = false;
          break;
        }
      }
      if (ok) {
        charToByteMap = null;
        return;
      }
    }

    charToByteMap = new int[s.length()];
    for (int byteI = 0, charI = 0; byteI < bytes.length; ) {
      byte b = bytes[byteI];
      int len;
      if (b >= 0) {
        charToByteMap[charI++] = byteI;
        len = 1;
      } else {
        len = Integer.numberOfLeadingZeros(~(b << 24));
        if (charI < charToByteMap.length) {
          charToByteMap[charI++] = byteI;
        }
        if (len == 4) {
          if (charI < charToByteMap.length) {
            charToByteMap[charI++] = byteI + 2;
          }
        }
      }
      byteI += len;
    }
  }

  int byteOffsetToCharOffset(int byteOffset) {
    if (charToByteMap == null) {
      return byteOffset;
    }
    if (byteOffset <= 0) {
      return 0;
    }
    if (byteOffset >= inputUtf8.length) {
      return inputString.length();
    }

    int i = Arrays.binarySearch(charToByteMap, byteOffset);
    if (i < 0) {
      i = -(i + 1); // insertion point (round up)
    }
    return i;
  }

  private int charOffsetToByteOffset(int charOffset) {
    if (charToByteMap == null) {
      return charOffset;
    }
    if (charOffset <= 0) {
      return 0;
    }
    if (charOffset >= inputString.length()) {
      return inputUtf8.length;
    }
    return charToByteMap[charOffset];
  }

  private void checkCodePointBoundary(int offset) {
    if (offset > 0
        && offset < inputString.length()
        && Character.isHighSurrogate(inputString.charAt(offset - 1))
        && Character.isLowSurrogate(inputString.charAt(offset))) {
      throw new IllegalArgumentException("UTF-16 offset splits a surrogate pair: " + offset);
    }
  }

  private void checkMatch() {
    if (!matched) {
      throw new IllegalStateException("No match found");
    }
  }
}
