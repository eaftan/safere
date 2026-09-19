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
   * Sentinel {@link #singleAsciiChar} result meaning "this is not a one-character ASCII literal".
   */
  static final int NOT_SINGLE_ASCII = -1;

  /**
   * Adaptive-defeat tuning, read into {@code static final} slots so that the scan loop below sees
   * compile-time constants. Reading these through an object instead costs about four times the
   * runtime of the whole search on a candidate-dense input, for no change in behaviour.
   */
  private static final int STRIKE_BUDGET = AcceleratorPolicy.LITERAL.strikeBudget();

  private static final int MIN_DENSITY_STRIDE = AcceleratorPolicy.LITERAL.minDensityStride();

  /**
   * What one dense stride costs against {@link #STRIKE_BUDGET}.
   *
   * <p>The budget is a *net* count, and a net count alone answers the wrong question. An anchor
   * that is mostly sparse and occasionally clumps should keep going, which is what the repayment
   * below is for; an anchor that is uniformly dense has shown everything it is going to show within
   * a handful of observations, and charging it one unit at a time makes it pay fifteen more before
   * conceding. {@code (error:\[)[A-Z](\] code:500)} anchors on {@code ']'}, which recurs every 46
   * characters in a log line, so it strikes on every iteration, spends the whole budget, and then
   * scans the window again with {@link String#indexOf(String, int)} — a measured fixed cost of
   * about 100 ns on every call. At the default budget of 16 this charge concedes after four such
   * observations, while still leaving four sparse strides able to buy back one dense one.
   *
   * <p>Conceding that early is deliberate, because the two directions are not symmetric. A winning
   * anchor never charges a strike at all — it returns from the verification without ever reaching
   * the accounting — so no amount of budget buys a winner anything, while every unit of it is spent
   * by a loser. Conceding early costs at most a few characters of a scan that was already fast;
   * conceding late costs a verification storm.
   *
   * <p>Weighting the existing counter rather than adding a second one is not a stylistic choice. A
   * separate consecutive counter needs its own local, its own increment, its own test and its own
   * reset, which took {@code indexOf} from 201 bytecodes to 216 — and 219 is a size at which this
   * method has already been measured to stop being inlined, costing more than the fix recovers.
   * This is also why the charge is a literal rather than {@code STRIKE_BUDGET / 4}: the budget is
   * read from {@link AcceleratorPolicy} and so is not a compile-time constant, and a derived charge
   * compiles to a static read and an add where a literal one folds into the {@code iinc} that was
   * already there.
   */
  private static final int STRIKE_CHARGE = 4;

  /** Keeps the anchored loop's per-candidate verification cost bounded by a constant. */
  private static final int MAX_ANCHORED_LITERAL_LENGTH = MIN_DENSITY_STRIDE * 2;

  /**
   * Shortest remaining window worth anchoring.
   *
   * <p>The bound comes from the cost model. Anchoring saves about 0.1 ns per character scanned and
   * risks one wasted verification per strike, so it is worth attempting only once the window is
   * long enough for the saving to cover the budget. Short windows are not a corner case: a {@code
   * findAll} over a match-dense input issues one call per match, and those calls scan the gap
   * between neighbouring matches rather than the whole input.
   *
   * <p>It is expressed as the span a full budget covers at exactly the minimum stride, which is
   * conservative rather than exact: {@link #STRIKE_CHARGE} lets a uniformly dense anchor reach a
   * verdict four strides in, well inside this window. Lowering it to match would newly anchor every
   * window between the two, which is a much larger change than the strike rule and wants its own
   * measurement.
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
        if ((strikes += STRIKE_CHARGE) >= STRIKE_BUDGET) {
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

  /**
   * Returns the single ASCII character {@code literal} consists of, or {@link #NOT_SINGLE_ASCII}.
   *
   * <p>Callers hold the result in the compiled plan and branch on it, rather than asking here on
   * every call. {@link String#indexOf(String, int)} with a one-character needle is not the same
   * kernel as {@link String#indexOf(int, int)}: measured over the same 100,000-byte input on
   * x86_64, the string form runs at 0.098 ns/byte and the character form at 0.0155, a factor of
   * 6.3. Nothing between the accelerator and the intrinsic looks at the needle's length, so a
   * one-character needle does not reach the faster kernel by itself.
   *
   * <p>The test lives here, at plan time, because putting it in {@link #indexOfDirect} was measured
   * and rejected: it took that method from 8 bytecodes to 40 and charged every caller for a branch
   * only 8% of literals can take, including the anchored path that calls it as a fallback. On
   * aarch64, where the two kernels are the same speed to four digits and there is no win to offset
   * it, that cost alone moved two unrelated trials by 5–6%.
   *
   * <p>Declines on non-ASCII, where {@link String#indexOf(int, int)} matches by code point and so
   * is a different search, and — via the callers, which only hold this for case-sensitive literals
   * — on anything folded.
   */
  static int singleAsciiChar(String literal) {
    return literal != null && literal.length() == 1 && literal.charAt(0) < 128
        ? literal.charAt(0)
        : NOT_SINGLE_ASCII;
  }

  /**
   * Searches for a single character, charging the same work a one-character {@link #indexOfDirect}
   * would. The accounting has to agree: a search must not record different work depending on which
   * kernel the plan chose for it.
   *
   * <p>That is also why {@code fromIndex} is clamped here. A one-character literal never has an
   * anchor, so the path this replaces is {@link #indexOf} clamping and then delegating to {@link
   * #indexOfDirect}; charging against an unclamped {@code fromIndex} would overcount by exactly the
   * amount it was negative by.
   */
  static int indexOfChar(String text, int ch, int fromIndex) {
    int pos = Math.max(0, fromIndex);
    int idx = text.indexOf(ch, pos);
    if (WorkCounterConfig.ENABLED) {
      int scanned = idx >= 0 ? idx - pos + 1 : text.length() - pos;
      WorkCounter.record(Math.max(0, scanned));
    }
    return idx;
  }

  /**
   * Searches with whichever kernel the plan chose, given a {@code singleAsciiChar} from {@link
   * #singleAsciiChar}.
   *
   * <p>One method rather than the same conditional at both plan sites, and small enough that it
   * costs a caller nothing beyond the call it replaces. There are only two: the third caller of
   * {@link #indexOf}, {@code RejectPrefilter.Literal}, cannot reach this at all, because {@code
   * MultiAnchorCompiler.extractRequiredLiteral} will not return a literal shorter than two
   * characters.
   */
  static int indexOfPlanned(
      String text,
      String literal,
      int anchorOffset,
      char anchor,
      int singleAsciiChar,
      int fromIndex) {
    return singleAsciiChar == NOT_SINGLE_ASCII
        ? indexOf(text, literal, anchorOffset, anchor, fromIndex)
        : indexOfChar(text, singleAsciiChar, fromIndex);
  }

  private StringLiteralSearch() {}
}
