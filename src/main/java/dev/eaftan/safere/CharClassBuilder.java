// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

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
public final class CharClassBuilder {

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

    // Find all existing ranges that overlap or are adjacent to [lo, hi].
    // A range [a, b] overlaps/is adjacent to [lo, hi] if a <= hi+1 && b >= lo-1.
    int mergedLo = lo;
    int mergedHi = hi;

    Iterator<Range> it = ranges.iterator();
    while (it.hasNext()) {
      Range r = it.next();
      if (r.hi + 1 < mergedLo) {
        continue; // r is entirely before the new range
      }
      if (r.lo > mergedHi + 1) {
        break; // r is entirely after; since sorted, all subsequent will be too
      }
      // r overlaps or is adjacent — merge
      mergedLo = Math.min(mergedLo, r.lo);
      mergedHi = Math.max(mergedHi, r.hi);
      nrunes -= (r.hi - r.lo + 1);
      it.remove();
    }

    ranges.add(new Range(mergedLo, mergedHi));
    nrunes += (mergedHi - mergedLo + 1);
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
    for (int[] row : table) {
      addRange(row[0], row[1]);
    }
    return this;
  }

  /**
   * Negates this character class in place, so it matches all valid Unicode code points not
   * previously matched, and vice versa.
   *
   * @return this builder, for chaining
   */
  public CharClassBuilder negate() {
    TreeSet<Range> negated = new TreeSet<>();
    int negatedRunes = 0;
    int next = 0;

    for (Range r : ranges) {
      // Skip surrogates: gap from 0xD800 to 0xDFFF.
      int lo = r.lo;
      int hi = r.hi;

      if (next < lo) {
        // Add gap [next, lo-1], but skip surrogates.
        addGapSkippingSurrogates(negated, next, lo - 1);
      }
      next = hi + 1;
    }

    if (next <= Utils.MAX_RUNE) {
      addGapSkippingSurrogates(negated, next, Utils.MAX_RUNE);
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

  private static void addGapSkippingSurrogates(TreeSet<Range> dest, int lo, int hi) {
    if (lo > hi) {
      return;
    }
    // Split around surrogate range [0xD800, 0xDFFF].
    if (hi < Utils.MIN_SURROGATE || lo > Utils.MAX_SURROGATE) {
      dest.add(new Range(lo, hi));
    } else {
      if (lo < Utils.MIN_SURROGATE) {
        dest.add(new Range(lo, Utils.MIN_SURROGATE - 1));
      }
      if (hi > Utils.MAX_SURROGATE) {
        dest.add(new Range(Utils.MAX_SURROGATE + 1, hi));
      }
    }
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

    Iterator<Range> it = ranges.iterator();
    TreeSet<Range> toAdd = new TreeSet<>();

    while (it.hasNext()) {
      Range r = it.next();
      if (r.hi < lo) {
        continue; // entirely before
      }
      if (r.lo > hi) {
        break; // entirely after
      }
      // r overlaps [lo, hi] — remove it and possibly add back the non-overlapping parts.
      it.remove();
      nrunes -= (r.hi - r.lo + 1);

      if (r.lo < lo) {
        toAdd.add(new Range(r.lo, lo - 1));
        nrunes += (lo - 1 - r.lo + 1);
      }
      if (r.hi > hi) {
        toAdd.add(new Range(hi + 1, r.hi));
        nrunes += (r.hi - hi - 1 + 1);
      }
    }

    ranges.addAll(toAdd);
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
