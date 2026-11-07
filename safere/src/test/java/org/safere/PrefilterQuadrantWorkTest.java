// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/// Checks the work of first-match searches for patterns with both a start literal and a required
/// literal, on inputs where each literal is present or absent.
///
/// Such patterns can scan for either literal first. Scanning for the required literal first rejects
/// inputs that lack it in one pass, but on an input that has it the start literal is scanned for
/// afterwards, so a short input is read twice (#1115). Scanning for the start literal first avoids
/// that, but is only safe when its scan is about as fast as the required literal's. Each ordering
/// is best in some quadrant, so a change that helps the quadrant its author measured can double the
/// work in another one. This test covers all four for each kind of start plan.
@DisabledForCrosscheck("Uses SafeRE's internal work instrumentation")
@Tag("work-counter")
class PrefilterQuadrantWorkTest {

  /// Contains no character of any literal below that matters for these patterns.
  private static final String FILLER = "a quick brown fox jumps past the lazy dog; ";

  private static final int[] SIZES = {64, 400, 16_384};

  /// Inputs shorter than this may scan for a competitive start literal first.
  private static final int START_FIRST_LIMIT = Pattern.MAX_LITERAL_START_FIRST_LENGTH;

  /// Allowance for verifying candidates and for the scans' fixed costs.
  private static final int SLACK = 32;

  /// Most work a search may do when the match starts at the beginning of the input.
  private static final int EARLY_HIT_BOUND = 64;

  /// A pattern with a start literal and a different required literal.
  enum Shape {
    /// An exact start literal that is as selective as the required literal, as in #1115.
    COMPETITIVE_START(
        "status=3\\d\\d took=(\\d+)ms", "status=3xx ", " took=", "status=301 took=12ms", true, 1),
    /// A start literal anchored on a common character: the required literal is scanned first.
    WEAK_START("er\\d+<<!nav>>\\d+", "ner ", "<<!nav>>", "er12<<!nav>>3", false, 1),
    /// A case-insensitive start literal, whose scan is slower than an exact one and does about
    /// two units of work per char on a String: the required literal is scanned first.
    CASE_FOLDED_START(
        "(?i:status=3\\d\\d) took=(\\d+)ms",
        "STATUS=3xx ",
        " took=",
        "Status=301 took=12ms",
        false,
        2);

    final String regex;

    /// Text containing the start literal but not a match.
    final String start;

    /// The required literal, which the pattern uses as a reject prefilter.
    final String required;

    /// The shortest match.
    final String match;

    /// Whether short inputs scan for the start literal first.
    final boolean startFirst;

    /// An upper bound on the work per input unit of scanning for the start literal.
    final int startScanCost;

    Shape(
        String regex,
        String start,
        String required,
        String match,
        boolean startFirst,
        int startScanCost) {
      this.regex = regex;
      this.start = start;
      this.required = required;
      this.match = match;
      this.startFirst = startFirst;
      this.startScanCost = startScanCost;
    }
  }

  private record Case(String name, String input, long bound) {}

  private static final Map<String, BiConsumer<Pattern, String>> ENTRY_POINTS = entryPoints();

  private static Map<String, BiConsumer<Pattern, String>> entryPoints() {
    Map<String, BiConsumer<Pattern, String>> entryPoints = new LinkedHashMap<>();
    entryPoints.put("Matcher.find", (p, s) -> consume(p.matcher(s).find()));
    entryPoints.put("Utf8Matcher.find", (p, s) -> consume(p.matcher(utf8(s)).find()));
    entryPoints.put("Pattern.find(Utf8Input)", (p, s) -> consume(p.find(utf8(s))));
    entryPoints.put("asPredicate", (p, s) -> consume(p.asPredicate().test(s)));
    return entryPoints;
  }

  @ParameterizedTest
  @EnumSource(Shape.class)
  void searchesScanOnceForEachLiteralTheyNeed(Shape shape) {
    Pattern pattern = Pattern.compile(shape.regex);
    assertThat(pattern.findRejectPrefilter()).isInstanceOf(RejectPrefilter.Literal.class);
    assertThat(pattern.stringStartAccelerator()).isNotNull();
    assertThat(pattern.utf8StartAccelerator()).isNotNull();

    List<String> failures = new ArrayList<>();
    for (int size : SIZES) {
      // Without the required literal, rejecting the input is a single pass.
      long rejectBound = (long) size + SLACK;
      // With it, the required literal's scan is followed by the start literal's, except on short
      // inputs for a start literal that is scanned first.
      long scanBound =
          (shape.startFirst && size < START_FIRST_LIMIT ? size : size * (1L + shape.startScanCost))
              + SLACK;
      List<Case> cases =
          List.of(
              new Case("neither literal", filler(size), rejectBound),
              new Case("start literal only", dense(size, shape.start, ""), rejectBound),
              new Case(
                  "required literal only",
                  filler(size - shape.required.length()) + shape.required,
                  scanBound),
              new Case(
                  "both literals, no match", dense(size, shape.start, shape.required), scanBound),
              new Case(
                  "match at end", filler(size - shape.match.length()) + shape.match, scanBound),
              new Case(
                  "match at start",
                  shape.match + filler(size - shape.match.length()),
                  EARLY_HIT_BOUND));
      for (Case c : cases) {
        ENTRY_POINTS.forEach(
            (name, entryPoint) -> {
              long work = work(() -> entryPoint.accept(pattern, c.input()));
              if (work > c.bound()) {
                failures.add(
                    String.format(
                        "%s, %s, %d chars: %d units, bound %d",
                        name, c.name(), size, work, c.bound()));
              }
            });
      }
    }
    assertThat(failures).as("/%s/", shape.regex).isEmpty();
  }

  private static String filler(int size) {
    return FILLER.repeat(size / FILLER.length() + 1).substring(0, size);
  }

  /// Returns `size` chars of filler with `piece` at every repetition, ending with `tail`.
  private static String dense(int size, String piece, String tail) {
    String body = (piece + FILLER).repeat(size / FILLER.length() + 1);
    return body.substring(0, size - tail.length()) + tail;
  }

  private static long work(Runnable task) {
    task.run(); // Build lazily prepared engines outside the measurement.
    return WorkCounter.countForTesting(task);
  }

  private static Utf8Input utf8(String s) {
    return Utf8Input.trusted(s.getBytes(UTF_8));
  }

  private static void consume(Object unused) {}
}
