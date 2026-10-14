// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Checks that no search entry point does much more work than {@link Matcher#find()} on the same
 * pattern and input.
 *
 * <p>Each entry point has its own sequence of strategies. A strategy added to one sequence and not
 * the others leaves the others correct but slow, which no functional test notices. This compares
 * {@link WorkCounter} units instead of strategy names, so an entry point may answer with a
 * different engine as long as it doesn't cost more.
 */
@DisabledForCrosscheck("WorkCounter is an internal SafeRE API")
@Tag("work-counter")
class EntryPointWorkParityTest {

  private static final String FILLER = "the quick brown fox jumps over the lazy dog ";
  private static final int[] SIZES = {2_000, 20_000};

  /** An entry point may do this many times the reference's work, plus {@link #SLACK} units. */
  private static final int FACTOR = 4;

  private static final int SLACK = 100;

  /**
   * Pattern and the suffix appended to the filler; an empty suffix gives an input with no match.
   */
  private record Scenario(String regex, String tail) {
    @Override
    public String toString() {
      return "/" + regex + "/" + (tail.isEmpty() ? " no match" : " + '" + tail + "'");
    }
  }

  private static final List<Scenario> SCENARIOS =
      List.of(
          new Scenario("hello", ""),
          new Scenario("hello", "hello"),
          new Scenario("[0-9]", ""),
          new Scenario("[0-9]+", ""),
          new Scenario("(?i)\\b(?:foo|bar|baz)\\b", ""),
          new Scenario("(?i)\\b(?:foo|bar|baz)\\b", " bar"),
          new Scenario("apple|banana|cherry", ""),
          new Scenario("(?:foo|fax)$", ""),
          new Scenario("(?:foo|fax)$", " foo"),
          new Scenario("\\s*(?:foo|fax)\\z", ""),
          new Scenario("\\s*(?:foo|fax)\\z", " foo"),
          new Scenario("(?i)\\s*(?:foo|fax)$", ""),
          new Scenario("^GET [^ ]+", ""),
          new Scenario("\\s*# noqa", ""),
          new Scenario("\\s*# noqa", "  # noqa"),
          new Scenario("foo.*bar.*[0-9]$", ""),
          new Scenario("a*[bc]d", ""),
          new Scenario("é[ab]+c", ""),
          new Scenario("é[ab]+c", "éabc"),
          new Scenario("(x?)*Q", ""),
          new Scenario("[a-z]+foo$", ""));

  /** Entry points that stop at the first match, compared with one {@link Matcher#find()}. */
  private static final Map<String, BiConsumer<Pattern, String>> FIRST_MATCH =
      Map.of(
          "Utf8Matcher.find",
          (p, s) -> consume(p.matcher(utf8(s)).find()),
          "Pattern.find(Utf8Input)",
          (p, s) -> consume(p.find(utf8(s))),
          "asPredicate",
          (p, s) -> consume(p.asPredicate().test(s)),
          "replaceFirst",
          (p, s) -> consume(p.matcher(s).replaceFirst("X")));

  /** Entry points that visit every match, compared with a {@link Matcher#find()} loop. */
  private static final Map<String, BiConsumer<Pattern, String>> ALL_MATCHES =
      Map.of(
          "replaceAll",
          (p, s) -> consume(p.matcher(s).replaceAll("X")),
          "split",
          (p, s) -> consume(p.split(s)));

  /**
   * Known gaps, keyed by {@code scenario + " " + entry point}. Each is expected to exceed the
   * bound, so fixing one fails the test until its entry is removed.
   */
  private static final Map<String, String> KNOWN_GAPS = knownGaps();

  private static Map<String, String> knownGaps() {
    String rejectFirst =
        "replaceImpl and findSplitPositions run the whole-input reject prefilter before the"
            + " end-anchored search, which find() has run first since #969";
    Map<String, String> gaps = new HashMap<>();
    for (String scenario :
        List.of(
            "/(?:foo|fax)$/ no match",
            "/(?:foo|fax)$/ + ' foo'",
            "/\\s*(?:foo|fax)\\z/ no match",
            "/\\s*(?:foo|fax)\\z/ + ' foo'")) {
      for (String entryPoint : List.of("replaceFirst", "replaceAll", "split")) {
        gaps.put(scenario + " " + entryPoint, rejectFirst);
      }
    }
    gaps.put(
        "/(?i)\\s*(?:foo|fax)$/ no match split",
        "findNextMatchPacked verifies leading-expansion candidates with a forward scan and has no"
            + " end-anchored search");
    gaps.put(
        "/(?i)\\b(?:foo|bar|baz)\\b/ + ' bar' split",
        "findNextMatchPacked has no KeywordAlternation fast path");
    return gaps;
  }

  @Test
  void noEntryPointDoesMuchMoreWorkThanFind() {
    List<String> failures = new ArrayList<>();
    for (Scenario scenario : SCENARIOS) {
      Pattern pattern = Pattern.compile(scenario.regex());
      for (int size : SIZES) {
        String input = FILLER.repeat(size / FILLER.length()) + scenario.tail();
        long find = work(() -> consume(pattern.matcher(input).find()));
        long findAll = work(() -> findAll(pattern, input));
        FIRST_MATCH.forEach(
            (name, op) -> check(failures, scenario, size, name, find, pattern, input, op));
        ALL_MATCHES.forEach(
            (name, op) -> check(failures, scenario, size, name, findAll, pattern, input, op));
      }
    }
    assertThat(failures).isEmpty();
  }

  private static void check(
      List<String> failures,
      Scenario scenario,
      int size,
      String entryPoint,
      long reference,
      Pattern pattern,
      String input,
      BiConsumer<Pattern, String> op) {
    long actual = work(() -> op.accept(pattern, input));
    boolean withinBound = actual <= FACTOR * reference + SLACK;
    String key = scenario + " " + entryPoint;
    String gap = KNOWN_GAPS.get(key);
    if (gap == null && !withinBound) {
      failures.add(
          String.format(
              "%s at %d chars: %d units against %d for find()", key, size, actual, reference));
    } else if (gap != null && withinBound && size == SIZES[SIZES.length - 1]) {
      failures.add(
          String.format(
              "%s at %d chars is now within bounds (%d units against %d); remove its known gap: %s",
              key, size, actual, reference, gap));
    }
  }

  private static long work(Runnable task) {
    task.run(); // Build lazily prepared engines outside the measurement.
    return WorkCounter.countForTesting(task);
  }

  private static void findAll(Pattern pattern, String input) {
    Matcher matcher = pattern.matcher(input);
    while (matcher.find()) {
      // Visit every match.
    }
  }

  private static Utf8Input utf8(String s) {
    return Utf8Input.trusted(s.getBytes(UTF_8));
  }

  private static void consume(Object unused) {}
}
