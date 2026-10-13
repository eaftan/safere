// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Arrays;

/**
 * A resizable array of primitive {@code int} values to avoid boxing and object allocation overhead.
 */
final class IntArrayList {

  private int[] data;
  private int size;

  IntArrayList() {
    this(16);
  }

  IntArrayList(int capacity) {
    data = new int[Math.max(4, capacity)];
  }

  void add(int value) {
    if (size == data.length) {
      data = Arrays.copyOf(data, data.length * 2);
    }
    data[size++] = value;
  }

  int size() {
    return size;
  }

  int get(int index) {
    return data[index];
  }

  void clear() {
    size = 0;
  }

  boolean isEmpty() {
    return size == 0;
  }

  int removeLast() {
    return data[--size];
  }

  int[] toArray() {
    return Arrays.copyOf(data, size);
  }

  /**
   * Returns the distinct values in ascending order.
   *
   * <p>Callers often add values as a concatenation of a few long ascending runs (for example, the
   * range boundaries of each character class in a program). Those are merged pairwise in linear
   * passes, which is much cheaper than a general sort; {@code Arrays.sort} only looks for runs in
   * arrays with thousands of elements. Small inputs and inputs with many short runs are sorted
   * directly.
   */
  int[] toSortedUniqueArray() {
    if (size == 0) {
      return new int[0];
    }
    int[] a = data;
    int runs = 1;
    for (int i = 1; i < size; i++) {
      if (a[i] < a[i - 1]) {
        runs++;
      }
    }
    if (runs > 1) {
      if (size < MIN_MERGE_SIZE || runs > MAX_MERGED_RUNS) {
        Arrays.sort(a, 0, size);
      } else {
        a = mergeRuns(a, size, runs);
      }
    }
    int unique = 1;
    for (int i = 1; i < size; i++) {
      if (a[i] != a[unique - 1]) {
        a[unique++] = a[i];
      }
    }
    return Arrays.copyOf(a, unique);
  }

  /** Below this size, a general sort is as cheap as merging and allocates nothing. */
  private static final int MIN_MERGE_SIZE = 256;

  /** Above this many runs, a general sort is cheaper than repeated merge passes. */
  private static final int MAX_MERGED_RUNS = 64;

  /** Merges adjacent runs pairwise until one remains, and returns the array holding the result. */
  private static int[] mergeRuns(int[] a, int size, int runs) {
    int[] b = new int[size];
    int[] starts = new int[runs + 1];
    int run = 1;
    for (int i = 1; i < size; i++) {
      if (a[i] < a[i - 1]) {
        starts[run++] = i;
      }
    }
    starts[runs] = size;
    while (runs > 1) {
      int out = 0;
      int merged = 0;
      for (int r = 0; r < runs; r += 2) {
        int lo = starts[r];
        int mid = starts[Math.min(r + 1, runs)];
        int hi = starts[Math.min(r + 2, runs)];
        starts[merged++] = out;
        int i = lo;
        int j = mid;
        while (i < mid && j < hi) {
          b[out++] = a[i] <= a[j] ? a[i++] : a[j++];
        }
        while (i < mid) {
          b[out++] = a[i++];
        }
        while (j < hi) {
          b[out++] = a[j++];
        }
      }
      starts[merged] = size;
      runs = merged;
      int[] t = a;
      a = b;
      b = t;
    }
    return a;
  }
}
