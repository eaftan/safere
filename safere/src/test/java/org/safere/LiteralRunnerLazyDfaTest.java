// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Patterns whose default {@code find()} runner is a literal runner build the forward DFA on first
 * use. Each test compiles a fresh pattern and starts with an operation other than String {@code
 * find()}, so that operation is the one that builds the DFA.
 */
@DisabledForCrosscheck("compares the optimized path with the JDK")
class LiteralRunnerLazyDfaTest {
  private static final List<String> PATTERNS =
      List.of("needle", "apple|banana|cherry", "foo|barfoo", "red|green|blue");

  private static final List<String> TEXTS =
      List.of(
          "",
          "needle",
          "apple",
          "barfoo",
          "abc",
          "xxneedlexx",
          "x".repeat(5_000) + "banana" + "x".repeat(100) + "foo",
          "needl",
          "ab",
          "xx red green blue");

  @Test
  void patternsUseLiteralRunners() {
    for (String regex : PATTERNS) {
      assertThat(Pattern.compile(regex).preparedMatchRunner(false))
          .as(regex)
          .isInstanceOfAny(
              Matcher.LiteralPreparedRunner.class, Matcher.LiteralAlternationPreparedRunner.class);
    }
  }

  @Test
  void firstOperationMatches() {
    for (String regex : PATTERNS) {
      for (String text : TEXTS) {
        assertThat(Pattern.compile(regex).matcher(text).matches())
            .as("%s matches %s", regex, text)
            .isEqualTo(java.util.regex.Pattern.compile(regex).matcher(text).matches());
      }
    }
  }

  @Test
  void firstOperationLookingAt() {
    for (String regex : PATTERNS) {
      for (String text : TEXTS) {
        Matcher actual = Pattern.compile(regex).matcher(text);
        java.util.regex.Matcher expected = java.util.regex.Pattern.compile(regex).matcher(text);
        assertThat(actual.lookingAt())
            .as("%s lookingAt %s", regex, text)
            .isEqualTo(expected.lookingAt());
        if (expected.lookingAt()) {
          assertThat(actual.end()).isEqualTo(expected.end());
        }
      }
    }
  }

  @Test
  void firstOperationUtf8() {
    for (String regex : PATTERNS) {
      for (String text : TEXTS) {
        byte[] bytes = text.getBytes(UTF_8);
        java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);
        assertThat(Pattern.compile(regex).find(Utf8Input.trusted(bytes)))
            .as("%s find %s", regex, text)
            .isEqualTo(jdk.matcher(text).find());
        assertThat(Pattern.compile(regex).matcher(Utf8Input.trusted(bytes)).matches())
            .as("%s matches %s", regex, text)
            .isEqualTo(jdk.matcher(text).matches());

        Utf8Matcher actual = Pattern.compile(regex).matcher(Utf8Input.trusted(bytes));
        java.util.regex.Matcher expected = jdk.matcher(text);
        while (expected.find()) {
          assertThat(actual.find()).as("%s find %s", regex, text).isTrue();
          // Every test text is ASCII, so byte offsets equal char offsets.
          assertThat(actual.start()).isEqualTo(expected.start());
          assertThat(actual.end()).isEqualTo(expected.end());
        }
        assertThat(actual.find()).as("%s find %s", regex, text).isFalse();
      }
    }
  }

  @Test
  void concurrentFirstUse() throws Exception {
    String text = "x".repeat(1_000) + "cherry";
    int threads = 8;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    try {
      for (int round = 0; round < 50; round++) {
        Pattern pattern = Pattern.compile("apple|banana|cherry");
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
          results.add(
              executor.submit(
                  () -> {
                    start.await();
                    return !pattern.matcher(text).matches()
                        && !pattern.matcher(text).lookingAt()
                        && pattern.matcher("cherry").matches()
                        && pattern.find(Utf8Input.trusted(text.getBytes(UTF_8)));
                  }));
        }
        start.countDown();
        for (Future<Boolean> result : results) {
          assertThat(result.get()).isTrue();
        }
      }
    } finally {
      executor.shutdownNow();
    }
  }
}
