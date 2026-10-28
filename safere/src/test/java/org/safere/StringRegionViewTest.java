// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StringRegionViewTest {
  @Test
  @DisabledForCrosscheck("Allocation scaling checks SafeRE region implementation behavior")
  void advancingRegionsAllocateLinearly() {
    AllocationTracker tracker = allocationTracker();
    long threadId = Thread.currentThread().threadId();
    Pattern pattern = Pattern.compile("\\w+|\\s+");
    String small = "word 123 ".repeat(256);
    String large = small.repeat(4);
    for (int i = 0; i < 8; i++) {
      tokenize(pattern, small);
      tokenize(pattern, large);
    }
    long smallBytes = measure(tracker, threadId, () -> tokenize(pattern, small));
    long largeBytes = measure(tracker, threadId, () -> tokenize(pattern, large));
    assertThat(largeBytes).isLessThan(smallBytes * 6);
  }

  @ParameterizedTest
  @ValueSource(strings = {"matches", "lookingAt", "find"})
  @DisabledForCrosscheck("Allocation scaling checks SafeRE region implementation behavior")
  void shortAttemptsDoNotAllocateWithRegionLength(String operation) {
    AllocationTracker tracker = allocationTracker();
    long threadId = Thread.currentThread().threadId();
    Pattern pattern = Pattern.compile("z.*");
    String small = "a".repeat(1024);
    String large = "a".repeat(100_000);
    Runnable smallAttempts = () -> shortAttempts(pattern, small, operation);
    Runnable largeAttempts = () -> shortAttempts(pattern, large, operation);
    for (int i = 0; i < 8; i++) {
      smallAttempts.run();
      largeAttempts.run();
    }
    long smallBytes = measure(tracker, threadId, smallAttempts);
    long largeBytes = measure(tracker, threadId, largeAttempts);
    assertThat(largeBytes - smallBytes).isLessThan(8192);
  }

  private static void shortAttempts(Pattern pattern, String input, String operation) {
    Matcher matcher = pattern.matcher(input);
    for (int start = 1; start <= 32; start++) {
      matcher.region(start, input.length());
      boolean found =
          switch (operation) {
            case "matches" -> matcher.matches();
            case "lookingAt" -> matcher.lookingAt();
            default -> matcher.find();
          };
      if (found) {
        throw new AssertionError("unexpected match");
      }
    }
  }

  private static void tokenize(Pattern pattern, String input) {
    Matcher matcher = pattern.matcher(input);
    for (int pos = 0; pos < input.length(); ) {
      matcher.region(pos, input.length());
      if (!matcher.lookingAt() || matcher.end() <= pos) {
        throw new AssertionError("tokenizer failed at " + pos);
      }
      pos = matcher.end();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "a",
        "abc|ab",
        "abc|xyz",
        "(?i:abc)",
        "(?i:k)",
        "(?i:i)",
        "(?iu:k)",
        "[a-z]+",
        "(a+)(b*)",
        "(a+?)?",
        "^a+$",
        "^a+b$",
        "^a+[0-9]$",
        "(?i:^a+b$)",
        "^a+b\\z",
        "^a+b\\Z",
        "(?d:^a+b$)",
        "(?U:\\b[a-z]+\\b)",
        "\\X",
        "\\x{1f600}",
        ".",
        ""
      })
  @DisabledForCrosscheck("Opaque substring semantics intentionally differ from JDK region behavior")
  void changingRegionsAgreeWithIndependentSubstrings(String regex) {
    Pattern pattern = Pattern.compile(regex);
    for (String input :
        List.of(
            "!aaabb!",
            "!aaaab!",
            "!aaaab\n!",
            "!aaa1!",
            "!abc!",
            "!ABC!",
            "!Kıİſ!",
            "x😀y",
            "!a\u0301!",
            "!\r\n!")) {
      Matcher actual = pattern.matcher(input);
      for (int start = 0; start <= input.length(); start++) {
        for (int end = start; end <= input.length(); end++) {
          Matcher expected = pattern.matcher(input.substring(start, end));
          for (int op = 0; op < 3; op++) {
            actual.region(start, end);
            expected.reset();
            boolean found;
            do {
              found =
                  switch (op) {
                    case 0 -> expected.matches();
                    case 1 -> expected.lookingAt();
                    default -> expected.find();
                  };
              boolean actualFound =
                  switch (op) {
                    case 0 -> actual.matches();
                    case 1 -> actual.lookingAt();
                    default -> actual.find();
                  };
              assertThat(actualFound)
                  .as("%s input=%s region=%s:%s op=%s", regex, input, start, end, op)
                  .isEqualTo(found);
              if (found) {
                for (int group = 0; group <= expected.groupCount(); group++) {
                  int expectedStart = expected.start(group);
                  int expectedEnd = expected.end(group);
                  assertThat(actual.start(group))
                      .isEqualTo(expectedStart < 0 ? -1 : start + expectedStart);
                  assertThat(actual.end(group))
                      .isEqualTo(expectedEnd < 0 ? -1 : start + expectedEnd);
                  assertThat(actual.group(group)).isEqualTo(expected.group(group));
                }
              }
            } while (op == 2 && found);
          }
        }
      }
    }
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
