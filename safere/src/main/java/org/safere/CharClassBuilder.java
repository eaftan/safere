// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Arrays;

/**
 * A mutable builder for {@link CharClass}. Maintains a set of Unicode code point ranges and
 * supports incremental construction via {@link #addRange}, {@link #addCharClass}, and {@link
 * #negate}.
 *
 * <p>Ranges are stored in an array, each packed into a {@code long} as {@code lo << 32 | hi}. While
 * ranges arrive in ascending order, which is the common case when copying a class or a Unicode
 * table, each one is appended or merged into the last range directly. An out-of-order range is
 * appended unsorted, and the array is sorted and merged once, the next time an operation needs the
 * normalized form. This keeps construction O(n log n) for any insertion order.
 *
 * <p>Once construction is complete, call {@link #build()} to produce an immutable {@link
 * CharClass}.
 */
final class CharClassBuilder {

  private long[] ranges = new long[8];
  private int size;

  /**
   * Whether {@link #ranges} is sorted by {@code lo} with no overlapping or adjacent ranges. When
   * false, {@link #nrunes} is stale until {@link #normalize()} runs.
   */
  private boolean normalized = true;

  private int nrunes;

  /** Creates an empty builder. */
  public CharClassBuilder() {}

  /** Creates a builder initialized with the contents of the given CharClass. */
  public CharClassBuilder(CharClass cc) {
    addCharClass(cc);
  }

  private static long pack(int lo, int hi) {
    return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
  }

  private static int lo(long range) {
    return (int) (range >>> 32);
  }

  private static int hi(long range) {
    return (int) range;
  }

  private void append(int lo, int hi) {
    if (size == ranges.length) {
      ranges = Arrays.copyOf(ranges, size * 2);
    }
    ranges[size++] = pack(lo, hi);
  }

  /** Sorts the ranges and merges overlapping or adjacent ones. */
  private void normalize() {
    if (normalized) {
      return;
    }
    // Code points are non-negative, so sorting the packed values sorts by lo, then hi.
    Arrays.sort(ranges, 0, size);
    int out = 0;
    int count = 0;
    for (int i = 0; i < size; ) {
      int lo = lo(ranges[i]);
      int hi = hi(ranges[i]);
      for (i++; i < size && lo(ranges[i]) <= (long) hi + 1; i++) {
        hi = Math.max(hi, hi(ranges[i]));
      }
      ranges[out++] = pack(lo, hi);
      count += hi - lo + 1;
    }
    size = out;
    nrunes = count;
    normalized = true;
  }

  /**
   * Adds all code points in the inclusive range {@code [lo, hi]} to this builder.
   *
   * @return this builder, for chaining
   */
  public CharClassBuilder addRange(int lo, int hi) {
    if (lo > hi) {
      return this;
    }
    if (normalized && size > 0) {
      long last = ranges[size - 1];
      int lastLo = lo(last);
      int lastHi = hi(last);
      if (lo > (long) lastHi + 1) {
        append(lo, hi);
        nrunes += hi - lo + 1;
      } else if (lo >= lastLo) {
        if (hi > lastHi) {
          ranges[size - 1] = pack(lastLo, hi);
          nrunes += hi - lastHi;
        }
      } else {
        append(lo, hi);
        normalized = false;
      }
      return this;
    }
    if (size == 0) {
      append(lo, hi);
      nrunes = hi - lo + 1;
      normalized = true;
      return this;
    }
    append(lo, hi);
    return this;
  }

  /**
   * Adds a single code point to this builder.
   *
   * @return this builder, for chaining
   */
  public CharClassBuilder addRune(int r) {
    return addRange(r, r);
  }

  /**
   * Adds all ranges from the given CharClass to this builder.
   *
   * @return this builder, for chaining
   */
  public CharClassBuilder addCharClass(CharClass cc) {
    for (int i = 0; i < cc.numRanges(); i++) {
      addRange(cc.lo(i), cc.hi(i));
    }
    return this;
  }

  /**
   * Adds all ranges from the given builder to this builder.
   *
   * @return this builder, for chaining
   */
  public CharClassBuilder addCharClass(CharClassBuilder other) {
    other.normalize();
    for (int i = 0; i < other.size; i++) {
      addRange(lo(other.ranges[i]), hi(other.ranges[i]));
    }
    return this;
  }

  /**
   * Adds all code points described by the given range table. Each row is {@code {lo, hi}}.
   *
   * @return this builder, for chaining
   */
  public CharClassBuilder addTable(int[][] table) {
    for (int[] row : table) {
      addRange(row[0], row[1]);
    }
    return this;
  }

  /**
   * Negates this character class in place, so it matches all Java string code points not previously
   * matched, and vice versa.
   *
   * @return this builder, for chaining
   */
  public CharClassBuilder negate() {
    normalize();
    long[] negated = new long[size + 1];
    int out = 0;
    int count = 0;
    int next = 0;
    for (int i = 0; i < size; i++) {
      int lo = lo(ranges[i]);
      if (next < lo) {
        negated[out++] = pack(next, lo - 1);
        count += lo - next;
      }
      next = hi(ranges[i]) + 1;
    }
    if (next <= Utils.MAX_RUNE) {
      negated[out++] = pack(next, Utils.MAX_RUNE);
      count += Utils.MAX_RUNE - next + 1;
    }
    ranges = negated.length >= 8 ? negated : Arrays.copyOf(negated, 8);
    size = out;
    nrunes = count;
    return this;
  }

  /**
   * Removes all code points in the inclusive range {@code [lo, hi]} from this builder.
   *
   * @return this builder, for chaining
   */
  public CharClassBuilder removeRange(int lo, int hi) {
    if (lo > hi) {
      return this;
    }
    normalize();
    // Removing a range from the middle of an existing range can split it in two.
    long[] result = new long[size + 1];
    int out = 0;
    int count = 0;
    for (int i = 0; i < size; i++) {
      int rlo = lo(ranges[i]);
      int rhi = hi(ranges[i]);
      if (rhi < lo || rlo > hi) {
        result[out++] = ranges[i];
        count += rhi - rlo + 1;
        continue;
      }
      if (rlo < lo) {
        result[out++] = pack(rlo, lo - 1);
        count += lo - rlo;
      }
      if (rhi > hi) {
        result[out++] = pack(hi + 1, rhi);
        count += rhi - hi;
      }
    }
    ranges = result.length >= 8 ? result : Arrays.copyOf(result, 8);
    size = out;
    nrunes = count;
    return this;
  }

  /** Returns true if this builder contains the given code point. */
  public boolean contains(int r) {
    normalize();
    int low = 0;
    int high = size - 1;
    while (low <= high) {
      int mid = (low + high) >>> 1;
      long range = ranges[mid];
      if (r < lo(range)) {
        high = mid - 1;
      } else if (r > hi(range)) {
        low = mid + 1;
      } else {
        return true;
      }
    }
    return false;
  }

  /** Returns the total number of code points in this builder. */
  public int numRunes() {
    normalize();
    return nrunes;
  }

  /** Returns true if this builder contains no ranges. */
  public boolean isEmpty() {
    return size == 0;
  }

  /**
   * Intersects this builder with another, keeping only code points present in both. Replaces the
   * contents of this builder with the intersection.
   *
   * @return this builder, for chaining
   */
  public CharClassBuilder intersect(CharClassBuilder other) {
    normalize();
    other.normalize();
    long[] result = new long[Math.max(8, size + other.size)];
    int out = 0;
    int count = 0;
    int a = 0;
    int b = 0;
    while (a < size && b < other.size) {
      long ra = ranges[a];
      long rb = other.ranges[b];
      int lo = Math.max(lo(ra), lo(rb));
      int hi = Math.min(hi(ra), hi(rb));
      if (lo <= hi) {
        result[out++] = pack(lo, hi);
        count += hi - lo + 1;
      }
      if (hi(ra) < hi(rb)) {
        a++;
      } else {
        b++;
      }
    }
    ranges = result;
    size = out;
    nrunes = count;
    return this;
  }

  /** Builds an immutable {@link CharClass} from the current state of this builder. */
  public CharClass build() {
    normalize();
    int[] flat = new int[size * 2];
    for (int i = 0; i < size; i++) {
      flat[2 * i] = lo(ranges[i]);
      flat[2 * i + 1] = hi(ranges[i]);
    }
    return new CharClass(flat, nrunes);
  }
}
