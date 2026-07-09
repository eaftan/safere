// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A lock-free, bounded, allocation-free object pool based on {@link AtomicReferenceArray}.
 *
 * <p>Avoids the ABA problem by performing direct CAS on array index slots instead of reusing
 * dynamic pointer chains.
 *
 * <p>This implementation is structurally similar to lock-free pool patterns used in
 * high-performance network buffers, but specialized for regex engine instances.
 *
 * <p>Alternatives considered:
 *
 * <ul>
 *   <li>{@code ReentrantLock}: Guarding access with a lock introduces synchronization overhead and
 *       context switches under contention.
 *   <li>{@code ConcurrentLinkedQueue}: Unbounded, and using a separate AtomicInteger to bound it
 *       would create a bottleneck.
 *   <li>Carrier-thread-locals: Not exposed by public JDK APIs. Hashing thread IDs across a bounded
 *       array acts as a lightweight, virtual-thread-safe approximation.
 * </ul>
 */
final class ArrayPool<T> {
  private final AtomicReferenceArray<T> array;
  private final Supplier<T> supplier;
  private final Consumer<T> cleaner;
  private final int mask;

  ArrayPool(int limit, Supplier<T> supplier, Consumer<T> cleaner) {
    if ((limit & (limit - 1)) != 0) {
      throw new IllegalArgumentException("limit must be a power of two");
    }
    this.array = new AtomicReferenceArray<>(limit);
    this.supplier = supplier;
    this.cleaner = cleaner;
    this.mask = limit - 1;

    // Eagerly pre-warm slot 0 to prevent first-request latency spikes (cold starts)
    this.array.set(0, supplier.get());
  }

  T acquire() {
    int len = array.length();
    // Start searching from a thread-specific slot to reduce CAS contention.
    int startIdx = (int) (Thread.currentThread().threadId() & mask);
    for (int i = 0; i < len; i++) {
      int idx = (startIdx + i) & mask;
      T item = array.get(idx);
      if (item != null && array.compareAndSet(idx, item, null)) {
        return item;
      }
    }
    return supplier.get();
  }

  void release(T item) {
    if (cleaner != null) {
      cleaner.accept(item);
    }
    int len = array.length();
    // Start searching from a thread-specific slot to reduce CAS contention.
    int startIdx = (int) (Thread.currentThread().threadId() & mask);
    for (int i = 0; i < len; i++) {
      int idx = (startIdx + i) & mask;
      if (array.compareAndSet(idx, null, item)) {
        return;
      }
    }
    // Pool is full; discard the item to let GC reclaim it
  }
}
