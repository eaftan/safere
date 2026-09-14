// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("HotSpot allocation accounting checks SafeRE implementation behavior")
class SurrogateRegionAllocationScalingTest {
  @Test
  void splitSurrogateRegionDoesNotAllocateWithRegionLength() {
    AllocationTracker tracker = allocationTracker();
    long threadId = Thread.currentThread().threadId();
    Pattern pattern = Pattern.compile(".*");
    String small = "a".repeat(10_000) + "\uD83D\uDC4D";
    String large = "a".repeat(100_000) + "\uD83D\uDC4D";

    measure(tracker, threadId, pattern, small);
    measure(tracker, threadId, pattern, large);
    long smallAllocation = measure(tracker, threadId, pattern, small);
    long largeAllocation = measure(tracker, threadId, pattern, large);

    assertThat(largeAllocation - smallAllocation).isLessThan(32_768);
  }

  private static long measure(
      AllocationTracker tracker, long threadId, Pattern pattern, String input) {
    long before = tracker.allocatedBytes(threadId);
    assertThat(pattern.matcher(input).region(0, input.length() - 1).matches()).isTrue();
    return tracker.allocatedBytes(threadId) - before;
  }

  private static AllocationTracker allocationTracker() {
    try {
      Class<?> managementFactoryClass = Class.forName("java.lang.management.ManagementFactory");
      Object threadBean = managementFactoryClass.getMethod("getThreadMXBean").invoke(null);
      Class<?> allocationBeanClass = Class.forName("com.sun.management.ThreadMXBean");
      Assumptions.assumeTrue(allocationBeanClass.isInstance(threadBean));
      Method supported = allocationBeanClass.getMethod("isThreadAllocatedMemorySupported");
      Method enabled = allocationBeanClass.getMethod("isThreadAllocatedMemoryEnabled");
      Method enable =
          allocationBeanClass.getMethod("setThreadAllocatedMemoryEnabled", boolean.class);
      Method allocated = allocationBeanClass.getMethod("getThreadAllocatedBytes", long.class);
      Assumptions.assumeTrue((boolean) supported.invoke(threadBean));
      if (!(boolean) enabled.invoke(threadBean)) {
        enable.invoke(threadBean, true);
      }
      return new AllocationTracker(threadBean, allocated);
    } catch (ReflectiveOperationException e) {
      Assumptions.abort("thread allocation tracking is unavailable: " + e);
      throw new LinkageError(e.getMessage(), e);
    }
  }

  private record AllocationTracker(Object threadBean, Method allocatedBytesMethod) {
    long allocatedBytes(long threadId) {
      try {
        return (long) allocatedBytesMethod.invoke(threadBean, threadId);
      } catch (ReflectiveOperationException e) {
        throw new LinkageError(e.getMessage(), e);
      }
    }
  }
}
