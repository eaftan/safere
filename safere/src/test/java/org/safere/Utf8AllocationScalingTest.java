// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("HotSpot allocation accounting checks SafeRE implementation behavior")
class Utf8AllocationScalingTest {
  @Test
  void booleanSearchAndRepeatedFindDoNotAllocateWithInputSize() {
    AllocationTracker tracker = allocationTracker();
    long threadId = Thread.currentThread().threadId();
    Pattern absent = Pattern.compile("not-present$");
    Pattern repeated = Pattern.compile("z");
    Utf8Input small = Utf8Input.trusted("a".repeat(10_000).getBytes(UTF_8));
    Utf8Input large = Utf8Input.trusted("a".repeat(100_000).getBytes(UTF_8));

    measure(tracker, threadId, () -> absent.find(small));
    measure(tracker, threadId, () -> absent.find(large));
    long smallBoolean = measure(tracker, threadId, () -> absent.find(small));
    long largeBoolean = measure(tracker, threadId, () -> absent.find(large));
    long smallRepeated = measure(tracker, threadId, () -> findAll(repeated, small));
    long largeRepeated = measure(tracker, threadId, () -> findAll(repeated, large));

    assertThat(largeBoolean - smallBoolean).isLessThan(32_768);
    assertThat(largeRepeated - smallRepeated).isLessThan(32_768);
  }

  private static void findAll(Pattern pattern, Utf8Input input) {
    Utf8Matcher matcher = pattern.matcher(input);
    while (matcher.find()) {}
  }

  private static long measure(AllocationTracker tracker, long threadId, Runnable operation) {
    long before = tracker.allocatedBytes(threadId);
    operation.run();
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
