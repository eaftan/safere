// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere.re2ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * A matcher for a {@link RE2FfmPattern} against an input string. This is a benchmark-oriented
 * wrapper — it supports enough of the {@code java.util.regex.Matcher} API to run the SafeRE
 * benchmark suite.
 *
 * <p>Match offsets from RE2 are in UTF-8 byte positions. This class converts them to Java char
 * (UTF-16) positions for the public API.
 */
public final class RE2FfmMatcher {

  private final RE2FfmPattern pattern;
  private String inputString;
  private byte[] inputUtf8;

  // Mapping from UTF-8 byte offset to Java char offset. Built lazily.
  private int[] byteToCharMap;

  // Match state: byte offsets from RE2. Length = 2 * (numGroups + 1).
  // [start0, end0, start1, end1, ...]
  private int[] matchByteOffsets;
  private boolean matched;

  // Current search position in UTF-8 bytes.
  private int searchBytePos;

  RE2FfmMatcher(RE2FfmPattern pattern, String input) {
    this.pattern = pattern;
    reset(input);
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
    this.byteToCharMap = null;
    this.matchByteOffsets = new int[2 * (pattern.numGroups() + 1)];
    this.matched = false;
    this.searchBytePos = 0;
    return this;
  }

  /** Resets this matcher to the beginning of the current input. */
  public RE2FfmMatcher reset() {
    this.matched = false;
    this.searchBytePos = 0;
    return this;
  }

  /**
   * Attempts to match the entire input against the pattern.
   *
   * @return true if the entire input matches
   */
  public boolean matches() {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment textSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, inputUtf8);
      matched = Re2Shim.fullMatch(pattern.nativeHandle(), textSeg, inputUtf8.length);
      if (matched) {
        // For full match, group 0 spans the entire input.
        matchByteOffsets[0] = 0;
        matchByteOffsets[1] = inputUtf8.length;
        // Capture groups need a real find call.
        if (pattern.numGroups() > 0) {
          MemorySegment matchesSeg =
              arena.allocate(ValueLayout.JAVA_INT, matchByteOffsets.length);
          Re2Shim.find(
              pattern.nativeHandle(),
              textSeg,
              inputUtf8.length,
              0,
              matchesSeg,
              pattern.numGroups());
          MemorySegment.copy(
              matchesSeg,
              ValueLayout.JAVA_INT,
              0,
              matchByteOffsets,
              0,
              matchByteOffsets.length);
        }
      }
    }
    return matched;
  }

  /**
   * Finds the next match in the input, starting from the current position.
   *
   * @return true if a match was found
   */
  public boolean find() {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment textSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, inputUtf8);
      MemorySegment matchesSeg = arena.allocate(ValueLayout.JAVA_INT, matchByteOffsets.length);
      matched =
          Re2Shim.find(
              pattern.nativeHandle(),
              textSeg,
              inputUtf8.length,
              searchBytePos,
              matchesSeg,
              pattern.numGroups());
      if (matched) {
        MemorySegment.copy(
            matchesSeg, ValueLayout.JAVA_INT, 0, matchByteOffsets, 0, matchByteOffsets.length);
        // Advance past this match for the next find() call.
        // If the match was empty, advance by one byte to avoid infinite loop.
        if (matchByteOffsets[1] == matchByteOffsets[0]) {
          searchBytePos = matchByteOffsets[1] + 1;
        } else {
          searchBytePos = matchByteOffsets[1];
        }
      }
    }
    return matched;
  }

  /**
   * Finds the next match starting at the given char position.
   *
   * @param start the char position to start searching from
   * @return true if a match was found
   */
  public boolean find(int start) {
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
    // Convert Java-style $N backreferences to RE2-style \N.
    String re2Rewrite = convertReplacement(replacement);
    byte[] rewriteUtf8 = re2Rewrite.getBytes(StandardCharsets.UTF_8);

    // Start with a buffer 2x the input size.
    int outCap = Math.max(inputUtf8.length * 2, 256);
    while (true) {
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment textSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, inputUtf8);
        MemorySegment rewriteSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, rewriteUtf8);
        MemorySegment outBuf = arena.allocate(outCap);
        MemorySegment outLenSeg = arena.allocate(ValueLayout.JAVA_INT);

        int result =
            Re2Shim.replaceAll(
                pattern.nativeHandle(),
                textSeg,
                inputUtf8.length,
                rewriteSeg,
                rewriteUtf8.length,
                outBuf,
                outCap,
                outLenSeg);

        if (result >= 0) {
          int outLen = outLenSeg.get(ValueLayout.JAVA_INT, 0);
          byte[] resultBytes = outBuf.asSlice(0, outLen).toArray(ValueLayout.JAVA_BYTE);
          return new String(resultBytes, StandardCharsets.UTF_8);
        }
        // Buffer too small; grow and retry.
        int needed = outLenSeg.get(ValueLayout.JAVA_INT, 0);
        outCap = needed + needed / 2;
      }
    }
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
    String re2Rewrite = convertReplacement(replacement);

    StringBuilder sb = new StringBuilder();
    // Append text before the match.
    int matchStartChar = start();
    sb.append(inputString, 0, matchStartChar);

    // Build the replacement from the rewrite template.
    appendRewrite(sb, re2Rewrite);

    // Append text after the match.
    int matchEndChar = end();
    sb.append(inputString, matchEndChar, inputString.length());

    return sb.toString();
  }

  // --- Private helpers ---

  /** Convert Java $N backreferences to RE2 \N. */
  private static String convertReplacement(String replacement) {
    StringBuilder sb = new StringBuilder(replacement.length());
    for (int i = 0; i < replacement.length(); i++) {
      char c = replacement.charAt(i);
      if (c == '$' && i + 1 < replacement.length() && Character.isDigit(replacement.charAt(i + 1))) {
        sb.append('\\');
        i++;
        // Copy all consecutive digits.
        while (i < replacement.length() && Character.isDigit(replacement.charAt(i))) {
          sb.append(replacement.charAt(i));
          i++;
        }
        i--; // Back up for the outer loop increment.
      } else if (c == '\\') {
        // Escape backslashes for RE2.
        sb.append("\\\\");
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /** Expand RE2-style \N backreferences in the rewrite string against the current match. */
  private void appendRewrite(StringBuilder sb, String rewrite) {
    for (int i = 0; i < rewrite.length(); i++) {
      char c = rewrite.charAt(i);
      if (c == '\\' && i + 1 < rewrite.length()) {
        char next = rewrite.charAt(i + 1);
        if (Character.isDigit(next)) {
          // Parse group number.
          int groupNum = 0;
          i++;
          while (i < rewrite.length() && Character.isDigit(rewrite.charAt(i))) {
            groupNum = groupNum * 10 + (rewrite.charAt(i) - '0');
            i++;
          }
          i--; // Back up for the outer loop increment.
          String g = group(groupNum);
          if (g != null) {
            sb.append(g);
          }
        } else if (next == '\\') {
          sb.append('\\');
          i++;
        } else {
          sb.append(c);
        }
      } else {
        sb.append(c);
      }
    }
  }

  /** Lazily build the byte-to-char offset mapping. */
  private int[] getByteToCharMap() {
    if (byteToCharMap == null) {
      // Build a mapping from byte offset -> char offset.
      byteToCharMap = new int[inputUtf8.length + 1];
      int charIdx = 0;
      int byteIdx = 0;
      while (byteIdx < inputUtf8.length) {
        byteToCharMap[byteIdx] = charIdx;
        // Determine UTF-8 byte length of this code point.
        int b = inputUtf8[byteIdx] & 0xFF;
        int seqLen;
        if (b < 0x80) {
          seqLen = 1;
        } else if (b < 0xE0) {
          seqLen = 2;
        } else if (b < 0xF0) {
          seqLen = 3;
        } else {
          seqLen = 4;
        }
        // Fill intermediate byte offsets to point at the same char.
        for (int j = 1; j < seqLen && byteIdx + j < inputUtf8.length; j++) {
          byteToCharMap[byteIdx + j] = charIdx;
        }
        byteIdx += seqLen;
        // Supplementary code points (4-byte UTF-8) use 2 chars in UTF-16.
        charIdx += (seqLen == 4) ? 2 : 1;
      }
      byteToCharMap[inputUtf8.length] = charIdx;
    }
    return byteToCharMap;
  }

  private int byteOffsetToCharOffset(int byteOffset) {
    return getByteToCharMap()[byteOffset];
  }

  private int charOffsetToByteOffset(int charOffset) {
    // Walk the byte-to-char map to find the byte offset.
    int[] map = getByteToCharMap();
    if (charOffset == 0) return 0;
    for (int i = 0; i <= inputUtf8.length; i++) {
      if (map[i] == charOffset) return i;
    }
    return inputUtf8.length;
  }

  private void checkMatch() {
    if (!matched) {
      throw new IllegalStateException("No match found");
    }
  }
}
