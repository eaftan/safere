// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * Exact-case literal search in a {@link String}, anchored on the literal's rarest byte.
 *
 * <p>{@link String#indexOf(String, int)} is intrinsified, but its multi-character form is several
 * times slower than its single-character form: on a 100 KB haystack a 3-character needle scans at
 * roughly 8 GB/s while {@code indexOf(char)} reaches roughly 50 GB/s. Searching for one rare
 * character of the literal and verifying the rest keeps the scan on the faster kernel and only pays
 * for verification where that character actually occurs.
 *
 * <p>This mirrors what the case-insensitive path already does. {@link
 * StringStartAccelerator.CaseInsensitiveLiteral} picks an anchor with {@link
 * RarityOracle#rarestAsciiOffset} and scans with {@link Matcher#indexOfIgnoreCase}; before this
 * class existed, the exact-case path — much the more common one — issued a bare {@code
 * text.indexOf(literal)}.
 *
 * <p>The trade is only profitable while the anchor is sparse. {@code indexOf(String)} is not a slow
 * scan that verification improves on; it is a fused filter and verify that emits no false positives
 * at all, so an anchor that occurs every few bytes loses to it badly. Three defences apply:
 *
 * <ul>
 *   <li>Windows too short for the saving to cover a wrong guess are not anchored at all.
 *   <li>A false candidate is rejected on the literal's last character before {@link
 *       String#startsWith} is called at all, which is what makes a false positive cheap enough to
 *       tolerate. The first character is left to {@code startsWith}, which compares it first.
 *   <li>Literals are anchored only while their bounded verification cost remains a constant factor
 *       of the candidate-spacing model.
 *   <li>Candidates arriving closer together than {@link AcceleratorPolicy#minDensityStride} are
 *       counted as strikes, and once the budget is spent the search reverts to {@code
 *       indexOf(String)} — the behaviour that predates anchoring — for the rest of the scan.
 * </ul>
 *
 * <p>The decision deliberately does not outlive a single call. Carrying it in an object shared
 * across the calls of one search was measured at roughly four times the cost of the entire search
 * on a candidate-dense input, without ever changing a decision, because the strike constants stop
 * being compile-time constants and the reference threads a field into the DFA's innermost loop.
 * Backing off across calls is already handled a layer up, by the quarantine in {@link Dfa} that
 * wraps these calls; what was missing, and what this class supplies, is protection within one.
 */
final class StringLiteralSearch {

  /** Sentinel {@code anchorOffset} meaning "search with {@link String#indexOf(String, int)}". */
  static final int NO_ANCHOR = -1;

  /**
   * Adaptive-defeat tuning, read into {@code static final} slots so that the scan loop below sees
   * compile-time constants. Reading these through an object instead costs about four times the
   * runtime of the whole search on a candidate-dense input, for no change in behaviour.
   */
  private static final int STRIKE_BUDGET = AcceleratorPolicy.LITERAL.strikeBudget();

  private static final int MIN_DENSITY_STRIDE = AcceleratorPolicy.LITERAL.minDensityStride();

  /** Keeps the anchored loop's per-candidate verification cost bounded by a constant. */
  private static final int MAX_ANCHORED_LITERAL_LENGTH = MIN_DENSITY_STRIDE * 2;

  /**
   * Shortest remaining window worth anchoring, which is the shortest window over which the strike
   * counter below can reach a sparse verdict at all: spending the whole budget at exactly the
   * minimum stride covers {@code strikeBudget * minDensityStride} characters, so over anything
   * shorter the density estimate has no power and the scan can only lose the gamble.
   *
   * <p>The same bound falls out of the cost model. Anchoring saves about 0.1 ns per character
   * scanned and risks one wasted verification per strike, so it is worth attempting only once the
   * window is long enough for the saving to cover the budget. Short windows are not a corner case:
   * a {@code findAll} over a match-dense input issues one call per match, and those calls scan the
   * gap between neighbouring matches rather than the whole input.
   *
   * <p>Package-private so that the differential tests can size their inputs past it; below it they
   * would exercise {@link String#indexOf(String, int)} against itself.
   */
  static final int MIN_ANCHORED_WINDOW = STRIKE_BUDGET * MIN_DENSITY_STRIDE;

  /**
   * Chooses the offset within {@code literal} to anchor the scan on, or {@link #NO_ANCHOR} if this
   * literal is better served by {@link String#indexOf(String, int)} directly.
   *
   * <p>Declines in four cases:
   *
   * <ul>
   *   <li>Literals shorter than two characters, where the anchor would be the literal itself, so
   *       verification could never reject a candidate.
   *   <li>Literals longer than {@link #MAX_ANCHORED_LITERAL_LENGTH}, so repeated full verification
   *       cannot make the added anchored work grow with both input and literal length.
   *   <li>Literals containing no ASCII character, where the rarity model has nothing to say.
   *   <li>Literals whose rarest character is still common enough to be a poisonous anchor, where
   *       verification would dominate the scan.
   * </ul>
   *
   * <p>This is a prior, not a prediction: the rarest character of a literal is frequently not the
   * rarest character of the haystack, and can even be the worst available choice. Runtime feedback
   * in {@link #indexOf} is what bounds the cost of getting it wrong.
   */
  static int anchorOffset(String literal) {
    if (literal == null || literal.length() < 2 || literal.length() > MAX_ANCHORED_LITERAL_LENGTH) {
      return NO_ANCHOR;
    }
    int offset = RarityOracle.rarestAsciiOffset(literal, literal.length());
    char anchor = literal.charAt(offset);
    if (anchor >= 128) {
      return NO_ANCHOR;
    }
    if (RarityOracle.byteRarity(anchor) <= RarityOracle.POISONOUS_ANCHOR_MAX_RARITY) {
      return NO_ANCHOR;
    }
    return offset;
  }

  /** Returns the anchor character for an offset returned by {@link #anchorOffset}. */
  static char anchorAt(String literal, int anchorOffset) {
    return anchorOffset == NO_ANCHOR ? '\0' : literal.charAt(anchorOffset);
  }

  /**
   * Returns the index of the first occurrence of {@code literal} at or after {@code fromIndex}, or
   * {@code -1}.
   *
   * <p>The two bail-outs share one condition and one call site on purpose. This method sits close
   * enough to the inlining size limit that a couple of bytecodes decide whether a caller inlines it
   * and constant-folds the anchor out of its descriptor; splitting the scan into its own method, or
   * giving each bail-out its own {@code return}, each cost about a third of the runtime on one
   * caller or the other. See the notes in the B1 write-up before restructuring this.
   *
   * @param anchorOffset an offset from {@link #anchorOffset}, or {@link #NO_ANCHOR}
   * @param anchor the character at {@code anchorOffset}, precomputed at compile time
   */
  static int indexOf(String text, String literal, int anchorOffset, char anchor, int fromIndex) {
    int pos = Math.max(0, fromIndex);
    int length = text.length();
    if (anchorOffset == NO_ANCHOR || length - pos < MIN_ANCHORED_WINDOW) {
      return indexOfDirect(text, literal, pos);
    }
    int literalLength = literal.length();
    int lastStart = length - literalLength;
    char lastChar = literal.charAt(literalLength - 1);
    int lastCharOffset = literalLength - 1;
    int strikes = 0;
    int lastCandidate = pos;

    while (pos <= lastStart) {
      int searchFrom = pos + anchorOffset;
      int hit = text.indexOf(anchor, searchFrom);
      if (WorkCounterConfig.ENABLED) {
        // The character at `hit` is inside the literal that the verification charge below already
        // accounts for, so it is not charged here as well. Counting it twice would make the work
        // recorded for a search depend on whether this method anchored or delegated, and the two
        // have to agree: `indexOfDirect` charges `idx - fromIndex + literalLength`, counting every
        // character it examines exactly once.
        WorkCounter.record(hit < 0 ? length - searchFrom : hit - searchFrom);
      }
      if (hit < 0) {
        return -1;
      }
      int candidate = hit - anchorOffset;
      if (candidate > lastStart) {
        return -1;
      }
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record(literalLength);
      }
      // Reject on the literal's last character before paying for startsWith. The anchor is by
      // construction the rarest character of the literal, which is often not the most selective one
      // for this particular haystack: 'val=200' anchors on '=' where 'v' would have rejected every
      // false candidate outright. The literal's first character is deliberately not checked here,
      // because startsWith compares it first anyway.
      if (text.charAt(candidate + lastCharOffset) == lastChar
          && text.startsWith(literal, candidate)) {
        return candidate;
      }
      int stride = candidate - lastCandidate;
      lastCandidate = candidate;
      pos = candidate + 1;
      if (stride < MIN_DENSITY_STRIDE) {
        if (++strikes >= STRIKE_BUDGET) {
          return indexOfDirect(text, literal, pos);
        }
      } else if (strikes > 0) {
        strikes--;
      }
    }
    return -1;
  }

  /** Unanchored search, with the work accounting the accelerators previously applied inline. */
  static int indexOfDirect(String text, String literal, int fromIndex) {
    int idx = text.indexOf(literal, fromIndex);
    if (WorkCounterConfig.ENABLED) {
      int scanned = idx >= 0 ? idx - fromIndex + literal.length() : text.length() - fromIndex;
      WorkCounter.record(Math.max(0, scanned));
    }
    return idx;
  }

  private StringLiteralSearch() {}
}
