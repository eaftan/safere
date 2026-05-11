// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Iterator;
import java.util.TreeSet;

/**
 * A mutable builder for {@link CharClass}. Maintains a sorted, non-overlapping set of Unicode code
 * point ranges and supports incremental construction via {@link #addRange}, {@link #addCharClass},
 * and {@link #negate}.
 *
 * <p>Once construction is complete, call {@link #build()} to produce an immutable {@link
 * CharClass}.
 */
final class CharClassBuilder {

  /**
   * A single inclusive range of Unicode code points. Ranges are ordered by lo, then by hi. Adjacent
   * or overlapping ranges are merged during insertion.
   */
  private record Range(int lo, int hi) implements Comparable<Range> {
    @Override
    public int compareTo(Range other) {
      if (this.lo != other.lo) {
        return Integer.compare(this.lo, other.lo);
      }
      return Integer.compare(this.hi, other.hi);
    }
  }

  private final TreeSet<Range> ranges = new TreeSet<>();
  private int nrunes;

  /** Creates an empty builder. */
  public CharClassBuilder() {}

  /** Creates a builder initialized with the contents of the given CharClass. */
  public CharClassBuilder(CharClass cc) {
    for (int i = 0; i < cc.numRanges(); i++) {
      ranges.add(new Range(cc.lo(i), cc.hi(i)));
    }
    nrunes = cc.numRunes();
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

    int mergedLo = lo;
    int mergedHi = hi;

    Range r = ranges.floor(new Range(lo, Integer.MAX_VALUE));
    if (r == null || isBefore(r, lo)) {
      r = ranges.ceiling(new Range(lo, Integer.MIN_VALUE));
    }

    while (r != null && !isAfter(r, mergedHi)) {
      Range next = ranges.higher(r);
      mergedLo = Math.min(mergedLo, r.lo);
      mergedHi = Math.max(mergedHi, r.hi);
      nrunes -= (r.hi - r.lo + 1);
      ranges.remove(r);
      r = next;
    }

    ranges.add(new Range(mergedLo, mergedHi));
    nrunes += (mergedHi - mergedLo + 1);
    return this;
  }

  private static boolean isBefore(Range range, int lo) {
    return (long) range.hi + 1 < lo;
  }

  private static boolean isAfter(Range range, int hi) {
    return range.lo > (long) hi + 1;
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
    for (Range r : other.ranges) {
      addRange(r.lo, r.hi);
    }
    return this;
  }

  /**
   * Adds all code points described by the given range table. Each row is {@code {lo, hi}}.
   *
   * @return this builder, for chaining
   */
  public CharClassBuilder addTable(int[][] table) {
    if (ranges.isEmpty() && canAppendTableDirectly(table)) {
      for (int[] row : table) {
        ranges.add(new Range(row[0], row[1]));
        nrunes += row[1] - row[0] + 1;
      }
      return this;
    }
    for (int[] row : table) {
      addRange(row[0], row[1]);
    }
    return this;
  }

  private static boolean canAppendTableDirectly(int[][] table) {
    int previousHi = -2;
    for (int[] row : table) {
      if (row.length != 2 || row[0] > row[1] || row[0] <= (long) previousHi + 1) {
        return false;
      }
      previousHi = row[1];
    }
    return true;
  }

  /**
   * Negates this character class in place, so it matches all Java string code points not previously
   * matched, and vice versa.
   *
   * @return this builder, for chaining
   */
  public CharClassBuilder negate() {
    TreeSet<Range> negated = new TreeSet<>();
    int next = 0;

    for (Range r : ranges) {
      if (next < r.lo) {
        negated.add(new Range(next, r.lo - 1));
      }
      next = r.hi + 1;
    }

    if (next <= Utils.MAX_RUNE) {
      negated.add(new Range(next, Utils.MAX_RUNE));
    }

    ranges.clear();
    ranges.addAll(negated);

    // Recount runes.
    nrunes = 0;
    for (Range r : ranges) {
      nrunes += (r.hi - r.lo + 1);
    }
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

    Range r = ranges.floor(new Range(lo, Integer.MAX_VALUE));
    if (r == null || r.hi < lo) {
      r = ranges.ceiling(new Range(lo, Integer.MIN_VALUE));
    }

    while (r != null && r.lo <= hi) {
      Range next = ranges.higher(r);
      // r overlaps [lo, hi] — remove it and possibly add back the non-overlapping parts.
      ranges.remove(r);
      nrunes -= (r.hi - r.lo + 1);

      if (r.lo < lo) {
        ranges.add(new Range(r.lo, lo - 1));
        nrunes += (lo - 1 - r.lo + 1);
      }
      if (r.hi > hi) {
        ranges.add(new Range(hi + 1, r.hi));
        nrunes += (r.hi - hi - 1 + 1);
      }
      r = next;
    }

    return this;
  }

  /** Returns true if this builder contains the given code point. */
  public boolean contains(int r) {
    // Use MAX_VALUE for hi so that floor finds any range whose lo <= r.
    Range probe = new Range(r, Integer.MAX_VALUE);
    Range floor = ranges.floor(probe);
    return floor != null && r >= floor.lo && r <= floor.hi;
  }

  /** Returns the total number of code points in this builder. */
  public int numRunes() {
    return nrunes;
  }

  /** Returns true if this builder contains no ranges. */
  public boolean isEmpty() {
    return ranges.isEmpty();
  }

  /**
   * Intersects this builder with another, keeping only code points present in both. Replaces the
   * contents of this builder with the intersection.
   *
   * @return this builder, for chaining
   */
  public CharClassBuilder intersect(CharClassBuilder other) {
    TreeSet<Range> result = new TreeSet<>();
    int ncount = 0;
    Iterator<Range> itA = ranges.iterator();
    Iterator<Range> itB = other.ranges.iterator();
    Range a = itA.hasNext() ? itA.next() : null;
    Range b = itB.hasNext() ? itB.next() : null;
    while (a != null && b != null) {
      int lo = Math.max(a.lo, b.lo);
      int hi = Math.min(a.hi, b.hi);
      if (lo <= hi) {
        result.add(new Range(lo, hi));
        ncount += (hi - lo + 1);
      }
      if (a.hi < b.hi) {
        a = itA.hasNext() ? itA.next() : null;
      } else {
        b = itB.hasNext() ? itB.next() : null;
      }
    }
    ranges.clear();
    ranges.addAll(result);
    nrunes = ncount;
    return this;
  }

  /** Builds an immutable {@link CharClass} from the current state of this builder. */
  public CharClass build() {
    int[] flat = new int[ranges.size() * 2];
    int i = 0;
    for (Range r : ranges) {
      flat[i++] = r.lo;
      flat[i++] = r.hi;
    }
    return new CharClass(flat, nrunes);
  }
}
