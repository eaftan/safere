// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Random/fuzz testing of SafeRE against {@code java.util.regex}. Ported from RE2 C++
 * {@code random_test.cc}.
 *
 * <p>Generates random regular expressions and random strings, then verifies that SafeRE and
 * {@code java.util.regex} agree on match/no-match results and matched text. Uses fixed seeds for
 * reproducibility.
 */
@DisplayName("Random Fuzz Tests (ported from RE2 C++ random_test.cc)")
class RandomTest {

  private static final int REGEXP_SEED = 404;
  private static final int STRING_SEED = 200;
  private static final int REGEXP_COUNT = 200;
  private static final int STRING_COUNT = 100;

  /** Small egrep-style patterns with literal alphabet. */
  @Test
  void smallEgrepLiterals() {
    randomTest(5, 5, new String[] {"a", "b", "c", "."},
        EGREP_OPS, 15, "abc");
  }

  /** Bigger egrep-style patterns. */
  @Test
  void bigEgrepLiterals() {
    randomTest(8, 8, new String[] {"a", "b", "c", "."},
        EGREP_OPS, 15, "abc");
  }

  /** Patterns with capturing groups. */
  @Test
  void smallEgrepCaptures() {
    randomTest(5, 5, new String[] {"a", "(b)", "."},
        EGREP_OPS, 15, "abc");
  }

  /** Complex patterns with character classes, anchors, and quantifiers. */
  @Test
  void complicated() {
    String[] atoms = {
        ".", "\\d", "\\D", "\\s", "\\S", "\\w", "\\W",
        "a", "(a)", "b", "c", "-", "\\\\"
    };
    String[] ops = {
        "%s%s", "%s|%s", "%s*", "%s*?", "%s+", "%s+?", "%s?", "%s??",
        "%s{0}", "%s{0,}", "%s{1}", "%s{1,}", "%s{0,1}", "%s{0,2}",
        "%s{1,2}", "%s{2}", "%s{2,}", "%s{3,4}"
    };
    randomTest(8, 8, atoms, ops, 20, "abc123\t\n");
  }

  // -----------------------------------------------------------------------
  // Egrep operators: concatenation, alternation, and quantifiers.
  // -----------------------------------------------------------------------

  private static final String[] EGREP_OPS = {
      "%s%s", "%s|%s", "%s*", "%s*?", "%s+", "%s+?", "%s?", "%s??"
  };

  // -----------------------------------------------------------------------
  // Core test logic
  // -----------------------------------------------------------------------

  private void randomTest(int maxAtoms, int maxOps, String[] atoms, String[] ops,
      int maxStrLen, String strAlphabet) {
    Random regexpRng = new Random(REGEXP_SEED);
    Random stringRng = new Random(STRING_SEED);

    // Generate random strings to test against.
    List<String> testStrings = new ArrayList<>();
    testStrings.add(""); // always include empty string
    char[] alphaChars = strAlphabet.toCharArray();
    for (int i = 0; i < STRING_COUNT; i++) {
      int len = stringRng.nextInt(maxStrLen + 1);
      StringBuilder sb = new StringBuilder(len);
      for (int j = 0; j < len; j++) {
        sb.append(alphaChars[stringRng.nextInt(alphaChars.length)]);
      }
      testStrings.add(sb.toString());
    }

    int totalTests = 0;
    int skipped = 0;
    List<String> failures = new ArrayList<>();

    for (int i = 0; i < REGEXP_COUNT; i++) {
      String pattern = generateRandomRegexp(regexpRng, atoms, ops, maxAtoms, maxOps);

      // Try to compile with both engines.
      Pattern saferePattern;
      java.util.regex.Pattern jdkPattern;
      try {
        saferePattern = Pattern.compile(pattern);
      } catch (PatternSyntaxException e) {
        skipped++;
        continue;
      }
      try {
        jdkPattern = java.util.regex.Pattern.compile(pattern);
      } catch (java.util.regex.PatternSyntaxException e) {
        skipped++;
        continue;
      }

      // Test each string.
      for (String text : testStrings) {
        totalTests++;

        // Compare find() results.
        Matcher safereMatcher = saferePattern.matcher(text);
        java.util.regex.Matcher jdkMatcher = jdkPattern.matcher(text);

        boolean safereFound = safereMatcher.find();
        boolean jdkFound = jdkMatcher.find();

        if (safereFound != jdkFound) {
          failures.add(String.format(
              "find() disagree: pat=\"%s\" text=\"%s\" safere=%b jdk=%b",
              escape(pattern), escape(text), safereFound, jdkFound));
          if (failures.size() >= 50) {
            break;
          }
          continue;
        }

        if (safereFound) {
          String safereGroup = safereMatcher.group();
          String jdkGroup = jdkMatcher.group();
          if (!safereGroup.equals(jdkGroup)) {
            failures.add(String.format(
                "find() group disagree: pat=\"%s\" text=\"%s\" safere=\"%s\" jdk=\"%s\"",
                escape(pattern), escape(text), escape(safereGroup), escape(jdkGroup)));
            if (failures.size() >= 50) {
              break;
            }
          }
        }

        // Compare matches() results.
        totalTests++;
        boolean safereMatches = saferePattern.matcher(text).matches();
        boolean jdkMatches = jdkPattern.matcher(text).matches();
        if (safereMatches != jdkMatches) {
          failures.add(String.format(
              "matches() disagree: pat=\"%s\" text=\"%s\" safere=%b jdk=%b",
              escape(pattern), escape(text), safereMatches, jdkMatches));
          if (failures.size() >= 50) {
            break;
          }
        }
      }

      if (failures.size() >= 50) {
        break;
      }
    }

    System.err.printf(
        "Random: %,d patterns, %,d tests, %,d skipped, %,d failures%n",
        REGEXP_COUNT, totalTests, skipped, failures.size());

    if (!failures.isEmpty()) {
      int show = Math.min(failures.size(), 20);
      StringBuilder sb = new StringBuilder();
      sb.append(String.format("%d failures (showing first %d):%n", failures.size(), show));
      for (int i = 0; i < show; i++) {
        sb.append("  ").append(failures.get(i)).append("\n");
      }
      fail(sb.toString());
    }

    assertThat(totalTests).as("Should have run a meaningful number of tests").isGreaterThan(1000);
  }

  // -----------------------------------------------------------------------
  // Random regexp generation
  // -----------------------------------------------------------------------

  private static String generateRandomRegexp(Random rng, String[] atoms, String[] ops,
      int maxAtoms, int maxOps) {
    int numOps = rng.nextInt(maxOps) + 1;
    // Start with a random atom.
    String expr = atoms[rng.nextInt(atoms.length)];

    for (int i = 0; i < numOps; i++) {
      String op = ops[rng.nextInt(ops.length)];
      int placeholders = countPlaceholders(op);
      if (placeholders == 1) {
        expr = String.format(op, expr);
      } else if (placeholders == 2) {
        String other = atoms[rng.nextInt(atoms.length)];
        // Randomly decide which side gets the built-up expression.
        if (rng.nextBoolean()) {
          expr = String.format(op, expr, other);
        } else {
          expr = String.format(op, other, expr);
        }
      }
    }
    return expr;
  }

  private static int countPlaceholders(String op) {
    int count = 0;
    int idx = 0;
    while ((idx = op.indexOf("%s", idx)) >= 0) {
      count++;
      idx += 2;
    }
    return count;
  }

  private static String escape(String s) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c >= 0x20 && c < 0x7F) {
        sb.append(c);
      } else {
        sb.append(String.format("\\x%02x", (int) c));
      }
    }
    return sb.toString();
  }
}
