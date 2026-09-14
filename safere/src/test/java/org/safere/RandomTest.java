// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Random/fuzz testing of SafeRE against {@code java.util.regex}. Ported from RE2 C++ {@code
 * random_test.cc}.
 *
 * <p>Generates random regular expressions and random strings, then verifies that SafeRE and {@code
 * java.util.regex} agree on match/no-match results and matched text. Uses fixed seeds for
 * reproducibility.
 */
@DisabledForCrosscheck("random differential fuzzing already compares SafeRE with java.util.regex")
@DisplayName("Random Fuzz Tests (ported from RE2 C++ random_test.cc)")
class RandomTest {

  private static final int REGEXP_SEED = 404;
  private static final int STRING_SEED = 200;
  private static final int REGEXP_COUNT = 200;
  private static final int STRING_COUNT = 100;

  /** Small egrep-style patterns with literal alphabet. */
  @Test
  void smallEgrepLiterals() {
    randomTest(5, new String[] {"a", "b", "c", "."}, EGREP_OPS, 15, "abc");
  }

  /** Bigger egrep-style patterns. */
  @Test
  void bigEgrepLiterals() {
    randomTest(8, new String[] {"a", "b", "c", "."}, EGREP_OPS, 15, "abc");
  }

  /** Patterns with capturing groups. */
  @Test
  void smallEgrepCaptures() {
    randomTest(5, new String[] {"a", "(b)", "."}, EGREP_OPS, 15, "abc");
  }

  /** Complex patterns with character classes, anchors, and quantifiers. */
  @Test
  void complicated() {
    String[] atoms = {
      ".",
      "\\d",
      "\\D",
      "\\s",
      "\\S",
      "\\w",
      "\\W",
      "[^\\n]",
      "[^\\n\\r]",
      "a",
      "(a)",
      "b",
      "c",
      "-",
      "\\\\"
    };
    String[] ops = {
      "%s%s", "%s|%s", "%s*", "%s*?", "%s+", "%s+?", "%s?", "%s??", "%s{0}", "%s{0,}", "%s{1}",
      "%s{1,}", "%s{0,1}", "%s{0,2}", "%s{1,2}", "%s{2}", "%s{2,}", "%s{3,4}"
    };
    // Every Java line terminator appears, so that a gap which wrongly treats one as a barrier
    // diverges from java.util.regex: \n, \r, \u0085 (NEL) and \u2028 (LS).
    randomTest(8, atoms, ops, 20, "abc123\t\n\r\u0085\u2028");
  }

  /**
   * The same style of pattern over inputs long enough to reach the wide scan kernels.
   *
   * <p>Every other method here caps input at 20 characters, which is too short for any wide kernel
   * to be dispatched: {@code IncubatorVectorScanProvider} requires a 1024-byte window for CLASS,
   * IGNORE_CASE and TEDDY, and {@code ByteSwarScan.MIN_FILTER_LENGTH} is 64. Measured with {@link
   * ScanAudit} over the UTF-8 domain, the short corpus produces 1,066 dispatches across only {@code
   * BYTE} and {@code PAIR}, with a maximum window of 30 bytes and <em>zero</em> on the vector path.
   * This arm produces 41,960 dispatches with a maximum window of 2,313 bytes, reaching {@code
   * CLASS} and {@code MULTI_LITERAL} as well, and takes the vector path 18,218 times. {@code TEDDY}
   * and {@code IGNORE_CASE} are still unreached; those need case-insensitive and
   * multi-literal-alternation patterns that this generator does not produce.
   *
   * <p>Fewer, larger strings keep the runtime in line with the other methods.
   */
  @Test
  void complicatedLongInput() {
    String[] atoms = {".", "\\d", "\\s", "\\w", "[^\\n]", "a", "(a)", "b", "c", "-"};
    String[] ops = {"%s%s", "%s|%s", "%s*", "%s+", "%s?", "%s{2}", "%s{1,2}", "%s{3,4}"};
    randomTest(6, atoms, ops, 2048, "abc123\t\n\r\u0085\u2028", 8);
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

  private static void randomTest(
      int maxOps, String[] atoms, String[] ops, int maxStrLen, String strAlphabet) {
    randomTest(maxOps, atoms, ops, maxStrLen, strAlphabet, STRING_COUNT);
  }

  private static void randomTest(
      int maxOps,
      String[] atoms,
      String[] ops,
      int maxStrLen,
      String strAlphabet,
      int stringCount) {
    Random regexpRng = new Random(REGEXP_SEED);
    Random stringRng = new Random(STRING_SEED);

    // Generate random strings to test against.
    List<String> testStrings = new ArrayList<>();
    testStrings.add(""); // always include empty string
    char[] alphaChars = strAlphabet.toCharArray();
    for (int i = 0; i < stringCount; i++) {
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
      String pattern = generateRandomRegexp(regexpRng, atoms, ops, maxOps);

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
      } catch (PatternSyntaxException e) {
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
          failures.add(
              String.format(
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
            failures.add(
                String.format(
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
          failures.add(
              String.format(
                  "matches() disagree: pat=\"%s\" text=\"%s\" safere=%b jdk=%b",
                  escape(pattern), escape(text), safereMatches, jdkMatches));
          if (failures.size() >= 50) {
            break;
          }
        }

        // Run the same pattern and text through the UTF-8 domain. The String and UTF-8 paths
        // are mirrored implementations -- StringStartAccelerator/Utf8StartAccelerator,
        // StringInputScanner/Utf8InputScanner, and both RejectPrefilter domains -- and the
        // contract in UTF8.md is that only the input representation and match coordinates
        // change. The String side is the oracle here rather than the JDK, because the JDK has
        // no byte-domain API; the loop above has already pinned the String side to the JDK.
        totalTests++;
        String utf8Mismatch = compareUtf8Domain(saferePattern, text);
        if (utf8Mismatch != null) {
          failures.add(
              String.format(
                  "String/UTF-8 disagree: pat=\"%s\" text=\"%s\" %s",
                  escape(pattern), escape(text), utf8Mismatch));
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
  // String versus UTF-8 domain comparison
  // -----------------------------------------------------------------------

  /**
   * Runs {@code pattern} over {@code text} in both the String and UTF-8 domains and returns a
   * description of the first disagreement, or {@code null} if they agree.
   *
   * <p>Compares the whole {@code find()} sequence and every capture group, not just the first
   * match: a byte-domain implementation can agree on the first match and still drift on iteration,
   * on how it advances past an empty match, or on group bounds.
   */
  private static String compareUtf8Domain(Pattern pattern, String text) {
    int[] byteOffsets = byteOffsets(text);
    byte[] encoded = text.getBytes(UTF_8);

    // Fresh matchers: matches() leaves a matcher positioned at the end of the match, so reusing
    // one for the find() loop below would silently skip the first match.
    if (pattern.matcher(text).matches()
        != pattern.matcher(Utf8Input.validated(encoded)).matches()) {
      return "matches() differs";
    }
    if (pattern.matcher(text).lookingAt()
        != pattern.matcher(Utf8Input.validated(encoded)).lookingAt()) {
      return "lookingAt() differs";
    }

    Matcher chars = pattern.matcher(text);
    Utf8Matcher bytes = pattern.matcher(Utf8Input.validated(encoded));

    for (int n = 0; ; n++) {
      boolean charsFound = chars.find();
      boolean bytesFound = bytes.find();
      if (charsFound != bytesFound) {
        return String.format("find() #%d: string=%b utf8=%b", n, charsFound, bytesFound);
      }
      if (!charsFound) {
        return null;
      }
      if (chars.groupCount() != bytes.groupCount()) {
        return String.format(
            "groupCount() #%d: string=%d utf8=%d", n, chars.groupCount(), bytes.groupCount());
      }
      for (int group = 0; group <= chars.groupCount(); group++) {
        String mismatch =
            compareBounds(
                n,
                group,
                byteOffsets,
                chars.start(group),
                chars.end(group),
                bytes.start(group),
                bytes.end(group));
        if (mismatch != null) {
          return mismatch;
        }
      }
    }
  }

  private static String compareBounds(
      int match,
      int group,
      int[] byteOffsets,
      int charStart,
      int charEnd,
      int byteStart,
      int byteEnd) {
    // An unset group must be unset in both domains.
    if (charStart < 0 || charEnd < 0) {
      if (byteStart >= 0 || byteEnd >= 0) {
        return String.format(
            "match #%d group %d: string unset, utf8=[%d,%d)", match, group, byteStart, byteEnd);
      }
      return null;
    }
    int expectedStart = byteOffsets[charStart];
    int expectedEnd = byteOffsets[charEnd];
    if (byteStart != expectedStart || byteEnd != expectedEnd) {
      return String.format(
          "match #%d group %d: string=[%d,%d) -> expected utf8=[%d,%d) but got [%d,%d)",
          match, group, charStart, charEnd, expectedStart, expectedEnd, byteStart, byteEnd);
    }
    return null;
  }

  /**
   * Maps each UTF-16 index in {@code text} to the number of UTF-8 bytes preceding it, so String
   * coordinates can be compared against byte coordinates.
   *
   * <p>Both halves of a surrogate pair map to the pair's starting byte offset; there is no legal
   * match boundary between them. Unpaired surrogates cannot occur here because every alphabet these
   * tests draw from is BMP-only.
   */
  private static int[] byteOffsets(String text) {
    int length = text.length();
    int[] offsets = new int[length + 1];
    int total = 0;
    int i = 0;
    while (i < length) {
      int codePoint = text.codePointAt(i);
      int charCount = Character.charCount(codePoint);
      offsets[i] = total;
      if (charCount == 2) {
        offsets[i + 1] = total;
      }
      total += utf8Length(codePoint);
      i += charCount;
    }
    offsets[length] = total;
    return offsets;
  }

  private static int utf8Length(int codePoint) {
    if (codePoint < 0x80) {
      return 1;
    }
    if (codePoint < 0x800) {
      return 2;
    }
    if (codePoint < 0x10000) {
      return 3;
    }
    return 4;
  }

  // -----------------------------------------------------------------------
  // Random regexp generation
  // -----------------------------------------------------------------------

  private static String generateRandomRegexp(Random rng, String[] atoms, String[] ops, int maxOps) {
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
