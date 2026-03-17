// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

/**
 * An immutable character class, represented as a sorted, non-overlapping list of Unicode code point
 * ranges. Each range is an inclusive {@code [lo, hi]} pair.
 *
 * <p>Internally, ranges are stored in a flat {@code int[]} where even indices are lo values and odd
 * indices are hi values. This is compact and cache-friendly.
 *
 * <p>Use {@link CharClassBuilder} to construct a CharClass incrementally.
 */
final class CharClass {

  /** An empty character class that matches nothing. */
  public static final CharClass EMPTY = new CharClass(new int[0], 0);

  // Flat array: [lo0, hi0, lo1, hi1, ...]. Always sorted and non-overlapping.
  private final int[] ranges;
  private final int nrunes;

  CharClass(int[] ranges, int nrunes) {
    this.ranges = ranges;
    this.nrunes = nrunes;
  }

  /** Returns the number of ranges in this character class. */
  public int numRanges() {
    return ranges.length / 2;
  }

  /** Returns the total number of code points matched by this character class. */
  public int numRunes() {
    return nrunes;
  }

  /** Returns true if this character class contains no ranges. */
  public boolean isEmpty() {
    return ranges.length == 0;
  }

  /** Returns the lo value of the i-th range. */
  public int lo(int i) {
    return ranges[2 * i];
  }

  /** Returns the hi value of the i-th range. */
  public int hi(int i) {
    return ranges[2 * i + 1];
  }

  /** Returns true if this character class contains the given code point. */
  public boolean contains(int r) {
    // Binary search over ranges.
    int lo = 0;
    int hi = numRanges() - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (r < lo(mid)) {
        hi = mid - 1;
      } else if (r > hi(mid)) {
        lo = mid + 1;
      } else {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns a new CharClass that is the negation of this one (matches all valid Unicode code points
   * not in this class).
   */
  public CharClass negate() {
    return new CharClassBuilder(this).negate().build();
  }

  /** Returns the underlying flat ranges array. For internal use. */
  int[] flatRanges() {
    return ranges;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append('[');
    for (int i = 0; i < numRanges(); i++) {
      if (i > 0) {
        sb.append(' ');
      }
      int lo = lo(i);
      int hi = hi(i);
      if (lo == hi) {
        sb.append(String.format("0x%X", lo));
      } else {
        sb.append(String.format("0x%X-0x%X", lo, hi));
      }
    }
    sb.append(']');
    return sb.toString();
  }
}
