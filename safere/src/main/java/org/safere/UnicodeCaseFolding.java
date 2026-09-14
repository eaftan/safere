// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Arrays;

/** Utilities for regex case-folded literal and range expansion. */
final class UnicodeCaseFolding {
  private static final int CASE_FOLD_NOT_FOUND = Integer.MIN_VALUE;

  private UnicodeCaseFolding() {}

  static int asciiFoldRune(int r) {
    return Ascii.toLowerCase(r);
  }

  static void addAsciiFoldedRange(CharClassBuilder ccb, int lo, int hi) {
    ccb.addRange(lo, hi);
    int upperLo = Math.max(lo, 'A');
    int upperHi = Math.min(hi, 'Z');
    if (upperLo <= upperHi) {
      ccb.addRange(Ascii.toLowerCase(upperLo), Ascii.toLowerCase(upperHi));
    }
    int lowerLo = Math.max(lo, 'a');
    int lowerHi = Math.min(hi, 'z');
    if (lowerLo <= lowerHi) {
      ccb.addRange(Ascii.toUpperCase(lowerLo), Ascii.toUpperCase(lowerHi));
    }
  }

  static void addUnicodeFoldedRange(CharClassBuilder ccb, int lo, int hi) {
    ccb.addRange(lo, hi);
    UnicodeCaseClosureIndex.addSourcesForTargetsInRange(ccb, lo, hi);
  }

  static boolean hasUnicodeCaseVariant(int cp) {
    return Character.toUpperCase(cp) != cp
        || Character.toLowerCase(cp) != cp
        || Character.toTitleCase(cp) != cp
        || Character.toLowerCase(Character.toUpperCase(cp)) != cp
        || Character.toUpperCase(Character.toLowerCase(cp)) != cp;
  }

  static int cycleFoldRune(int r) {
    int[][] cf = UnicodeTables.CASE_FOLD;
    int idx = lookupCaseFold(r);
    if (idx < 0) {
      return r;
    }
    return applyFold(cf[idx], r);
  }

  private static int lookupCaseFold(int r) {
    int[][] cf = UnicodeTables.CASE_FOLD;
    int lo = 0;
    int hi = cf.length - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (cf[mid][0] <= r && r <= cf[mid][1]) {
        return mid;
      }
      if (r < cf[mid][0]) {
        hi = mid - 1;
      } else {
        lo = mid + 1;
      }
    }
    return lo < cf.length ? -(lo + 1) : CASE_FOLD_NOT_FOUND;
  }

  private static int applyFold(int[] entry, int r) {
    int delta = entry[2];
    if (delta == UnicodeTables.EVEN_ODD_SKIP) {
      if ((r - entry[0]) % 2 != 0) return r;
      delta = UnicodeTables.EVEN_ODD;
    }
    if (delta == UnicodeTables.ODD_EVEN_SKIP) {
      if ((r - entry[0]) % 2 != 0) return r;
      delta = UnicodeTables.ODD_EVEN;
    }
    if (delta == UnicodeTables.EVEN_ODD) {
      return (r % 2 == 0) ? r + 1 : r - 1;
    }
    if (delta == UnicodeTables.ODD_EVEN) {
      return (r % 2 == 1) ? r + 1 : r - 1;
    }
    return r + delta;
  }

  private static final class UnicodeCaseClosureIndex {
    static final long[] TARGET_TO_SOURCE = buildTargetToSourcePairs();

    private static void addSourcesForTargetsInRange(CharClassBuilder ccb, int lo, int hi) {
      int index = lowerBoundTarget(lo);
      while (index < TARGET_TO_SOURCE.length) {
        long pair = TARGET_TO_SOURCE[index];
        int target = target(pair);
        if (target > hi) {
          return;
        }
        ccb.addRune(source(pair));
        index++;
      }
    }

    private static int lowerBoundTarget(int target) {
      long key = pack(target, 0);
      int lo = 0;
      int hi = TARGET_TO_SOURCE.length;
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (TARGET_TO_SOURCE[mid] < key) {
          lo = mid + 1;
        } else {
          hi = mid;
        }
      }
      return lo;
    }

    private static long[] buildTargetToSourcePairs() {
      // Build connected components of single-code-point casing and simple-fold links.
      // These temporary arrays are bounded by the Unicode universe, not the input length.
      int[] parent = new int[Utils.MAX_RUNE + 1];
      int[] next = new int[parent.length];
      Arrays.fill(next, -1);
      for (int cp = 0; cp < parent.length; cp++) {
        parent[cp] = cp;
      }
      for (int cp = 0; cp < parent.length; cp++) {
        union(parent, cp, Character.toUpperCase(cp));
        union(parent, cp, Character.toLowerCase(cp));
        union(parent, cp, Character.toTitleCase(cp));
        union(parent, cp, cycleFoldRune(cp));
      }
      // Each component is a linked list starting at its least code point.
      for (int cp = 0; cp < parent.length; cp++) {
        int root = find(parent, cp);
        if (root != cp) {
          next[cp] = next[root];
          next[root] = cp;
        }
      }
      LongArrayBuilder pairs = new LongArrayBuilder();
      for (int target = 0; target < parent.length; target++) {
        int root = find(parent, target);
        if (next[root] == -1) {
          continue;
        }
        for (int source = root; source != -1; source = next[source]) {
          if (source != target) {
            pairs.add(pack(target, source));
          }
        }
      }
      // Every member indexes every other member exactly once. One range lookup therefore
      // gives the complete symmetric/transitive closure, with no iterative parser expansion.
      long[] sorted = pairs.toArray();
      Arrays.sort(sorted);
      return sorted;
    }

    private static int find(int[] parent, int cp) {
      while (parent[cp] != cp) {
        parent[cp] = parent[parent[cp]];
        cp = parent[cp];
      }
      return cp;
    }

    private static void union(int[] parent, int a, int b) {
      int first = find(parent, a);
      int second = find(parent, b);
      parent[Math.max(first, second)] = Math.min(first, second);
    }

    private static long pack(int target, int source) {
      return ((long) target << Integer.SIZE) | Integer.toUnsignedLong(source);
    }

    private static int target(long pair) {
      return (int) (pair >>> Integer.SIZE);
    }

    private static int source(long pair) {
      return (int) pair;
    }
  }

  private static final class LongArrayBuilder {
    private long[] values = new long[4096];
    private int size;

    void add(long value) {
      if (size == values.length) {
        values = Arrays.copyOf(values, values.length * 2);
      }
      values[size++] = value;
    }

    long[] toArray() {
      return Arrays.copyOf(values, size);
    }
  }
}
