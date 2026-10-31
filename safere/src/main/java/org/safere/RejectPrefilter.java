// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.safere.Pattern.EndAnchoredCharClassInfo;
import org.safere.Pattern.SuffixInfo;

/**
 * Whole-input rejection filter (Tier 0 acceleration).
 *
 * <p>Rejects match attempts in O(1) / fast linear scan before invoking automata when mandatory
 * tokens or character classes are absent anywhere in the input.
 */
sealed interface RejectPrefilter
    permits RejectPrefilter.Literal,
        RejectPrefilter.CharClass,
        RejectPrefilter.DisjointLiterals,
        RejectPrefilter.EndAnchoredSuffix,
        RejectPrefilter.EndAnchoredCharClass,
        RejectPrefilter.Composite {

  /**
   * Returns whether the input starting from {@code searchFrom} can be rejected. Callers pass either
   * a non-null {@code text} or a non-null {@code utf8}.
   */
  boolean canReject(String text, Utf8InputScanner utf8, int searchFrom, EnginePathOptions options);

  /** Returns the strategy that rejected the input, or {@code null} if it cannot be rejected. */
  default MatchStrategy rejectionStrategy(
      String text, Utf8InputScanner utf8, int searchFrom, EnginePathOptions options) {
    return canReject(text, utf8, searchFrom, options) ? strategy() : null;
  }

  MatchStrategy strategy();

  /** Checks only terminal filters, whose work is bounded by the pattern rather than the input. */
  default MatchStrategy endAnchoredRejectionStrategy(
      InputScanner scanner, int searchFrom, EnginePathOptions options) {
    String text = scanner instanceof StringInputScanner stringScanner ? stringScanner.text() : null;
    Utf8InputScanner utf8 = scanner instanceof Utf8InputScanner utf8Scanner ? utf8Scanner : null;
    if (this instanceof EndAnchoredSuffix || this instanceof EndAnchoredCharClass) {
      return rejectionStrategy(text, utf8, searchFrom, options);
    }
    if (this instanceof Composite composite) {
      // Composite construction flattens the filters, so this remains an iterative bounded walk.
      for (RejectPrefilter filter : composite.filters()) {
        if (filter instanceof EndAnchoredSuffix || filter instanceof EndAnchoredCharClass) {
          MatchStrategy rejection = filter.rejectionStrategy(text, utf8, searchFrom, options);
          if (rejection != null) {
            return rejection;
          }
        }
      }
    }
    return null;
  }

  /**
   * Drops {@link DisjointLiterals} when UTF-8 start acceleration is already active, so {@code
   * find()} does not add multi-literal full-input scans ahead of a single-pass start accelerator.
   */
  static RejectPrefilter withoutDisjointLiterals(RejectPrefilter filter) {
    if (filter instanceof DisjointLiterals) {
      return null;
    }
    if (filter instanceof Composite(RejectPrefilter[] filters)) {
      List<RejectPrefilter> kept = null;
      for (int i = 0; i < filters.length; i++) {
        if (filters[i] instanceof DisjointLiterals) {
          if (kept == null) {
            kept = new ArrayList<>(filters.length - 1);
            for (int j = 0; j < i; j++) {
              kept.add(filters[j]);
            }
          }
        } else if (kept != null) {
          kept.add(filters[i]);
        }
      }
      if (kept == null) {
        return filter;
      }
      if (kept.isEmpty()) {
        return null;
      }
      return kept.size() == 1
          ? kept.getFirst()
          : new Composite(kept.toArray(RejectPrefilter[]::new));
    }
    return filter;
  }

  static RejectPrefilter create(MultiAnchorDescriptor descriptor) {
    if (descriptor == null) {
      return null;
    }
    return create(descriptor.rejectPlan());
  }

  static RejectPrefilter create(MultiAnchorDescriptor.RejectPlan plan) {
    if (plan == null || plan instanceof MultiAnchorDescriptor.RejectPlan.None) {
      return null;
    }
    return switch (plan) {
      case MultiAnchorDescriptor.RejectPlan.None ignored -> null;
      case MultiAnchorDescriptor.RejectPlan.RequiredLiteral l -> Literal.create(l.literal());
      case MultiAnchorDescriptor.RejectPlan.RequiredCharClass c -> CharClass.create(c.scanInfo());
      case MultiAnchorDescriptor.RejectPlan.DisjointLiterals d ->
          DisjointLiterals.create(d.literals());
      case MultiAnchorDescriptor.RejectPlan.EndAnchoredSuffix s ->
          EndAnchoredSuffix.create(s.suffix());
      case MultiAnchorDescriptor.RejectPlan.EndAnchoredCharClass ecc ->
          EndAnchoredCharClass.create(ecc.charClass());
      case MultiAnchorDescriptor.RejectPlan.Composite comp -> {
        List<RejectPrefilter> list = new ArrayList<>();
        for (MultiAnchorDescriptor.RejectPlan p : comp.plans()) {
          RejectPrefilter filter = create(p);
          if (filter != null) {
            if (filter instanceof Composite subComp) {
              list.addAll(List.of(subComp.filters()));
            } else {
              list.add(filter);
            }
          }
        }
        if (list.isEmpty()) {
          yield null;
        }
        if (list.size() == 1) {
          yield list.get(0);
        }
        yield new Composite(list.toArray(RejectPrefilter[]::new));
      }
    };
  }

  @SuppressWarnings("ArrayRecordComponent")
  record Literal(
      String literal, byte[] utf8, int[] failure, int[] shifts, int anchorOffset, char anchor)
      implements RejectPrefilter {

    static Literal create(String literal) {
      byte[] utf8 = literal.getBytes(StandardCharsets.UTF_8);
      int[] failure = Pattern.literalFailure(utf8);
      int[] shifts = Pattern.literalShifts(utf8);
      int anchorOffset = StringLiteralSearch.anchorOffset(literal);
      return new Literal(
          literal,
          utf8,
          failure,
          shifts,
          anchorOffset,
          StringLiteralSearch.anchorAt(literal, anchorOffset));
    }

    @Override
    public boolean canReject(
        String text, Utf8InputScanner utf8, int searchFrom, EnginePathOptions options) {
      if (!options.literalFastPaths()) {
        return false;
      }
      return text != null ? canReject(text, searchFrom) : canReject(utf8, searchFrom);
    }

    private boolean canReject(String text, int searchFrom) {
      return StringLiteralSearch.indexOf(text, literal, anchorOffset, anchor, searchFrom) < 0;
    }

    private boolean canReject(Utf8InputScanner utf8, int searchFrom) {
      return utf8 != null && utf8.indexOf(this.utf8, failure, shifts, searchFrom) < 0;
    }

    @Override
    public MatchStrategy strategy() {
      return MatchStrategy.LITERAL;
    }
  }

  @SuppressWarnings("ArrayRecordComponent")
  record CharClass(
      int[] ranges,
      long bitmap0,
      long bitmap1,
      int singleAscii,
      char[] smallChars,
      byte[] nonAsciiUtf8)
      implements RejectPrefilter {

    static CharClass create(CharClassScanInfo scanInfo) {
      char[] small = smallChars(scanInfo);
      return new CharClass(
          scanInfo.ranges(),
          scanInfo.bitmap0(),
          scanInfo.bitmap1(),
          singleAscii(scanInfo),
          small,
          nonAsciiUtf8OfMixedPair(small));
    }

    /**
     * Returns the UTF-8 encoding of the non-ASCII member of a small set with one ASCII and one
     * non-ASCII member, such as {@code [\]\uFF3D]}, or {@code null} for every other class. Members
     * are sorted, so the ASCII one comes first. Small-set members are never surrogates.
     */
    private static byte[] nonAsciiUtf8OfMixedPair(char[] small) {
      return small != null && small.length == 2 && small[0] < 0x80 && small[1] >= 0x80
          ? String.valueOf(small[1]).getBytes(StandardCharsets.UTF_8)
          : null;
    }

    /**
     * Returns the sole member of a one-character ASCII class, or {@code -1} for every other class.
     *
     * <p>A one-character reject class is a character search, not a class scan. {@link
     * InputScanner#indexOfAscii} reaches {@link String#indexOf(int, int)}, which is intrinsified,
     * whereas {@link InputScanner#indexOfCodePointClass} walks {@code codePointAt} and {@code
     * charCount} per character. {@link CharClassScanInfo.AsciiSmallSet} already records its
     * enumerated members; this reads that back so the distinction survives construction.
     */
    private static int singleAscii(CharClassScanInfo scanInfo) {
      return scanInfo instanceof CharClassScanInfo.AsciiSmallSet smallSet
              && smallSet.chars() != null
              && smallSet.chars().length == 1
          ? smallSet.chars()[0]
          : -1;
    }

    /**
     * Returns the members of a two-member small class, or {@code null}. On the {@code String} path
     * they are searched with {@link #rejectsSmall}. A single ASCII member is already one {@code
     * String.indexOf} through {@link #singleAscii}, and routing it through the windowed search
     * instead cost 9% on {@code bracketCitation.match}. A three-member set would cost three
     * intrinsic passes over a gap, which has not been measured against the class scan.
     */
    private static char[] smallChars(CharClassScanInfo scanInfo) {
      return scanInfo instanceof CharClassScanInfo.SmallSet smallSet && smallSet.chars().length == 2
          ? smallSet.chars()
          : null;
    }

    @Override
    public boolean canReject(
        String text, Utf8InputScanner utf8, int searchFrom, EnginePathOptions options) {
      if (!options.charClassMatchFastPaths()) {
        return false;
      }
      if (text != null) {
        if (smallChars != null) {
          return rejectsSmall(text, searchFrom);
        }
        int length = text.length();
        return (singleAscii >= 0
                ? StringInputScanner.indexOfAscii(text, singleAscii, searchFrom, length)
                : StringInputScanner.indexOfCodePointClass(
                    text, ranges, bitmap0, bitmap1, searchFrom, length))
            < 0;
      }
      return utf8 != null && canRejectUtf8(utf8, searchFrom);
    }

    /**
     * Chars the first {@code find()} searches for every member before searching the rest of the
     * input one member at a time, so a member near the start is found without first scanning the
     * whole input for one that is absent.
     */
    private static final int FIRST_FIND_NEAR_WINDOW = 80;

    /**
     * Returns whether no member of {@link #smallChars} occurs in the input, checked only from the
     * start of the input.
     *
     * <p>The check covers a short window for every member, then searches each member over the rest
     * of the input. That costs at most one pass per member per search sequence, and on input with
     * no member it rejects in as few intrinsic calls as possible. Later {@code find()} calls do not
     * reject: the start accelerator and DFA already bound the work for the rest of the input, and
     * re-searching an absent member from every {@code find()} position cost 2x on input with sparse
     * matches, even through bounded windows.
     */
    private boolean rejectsSmall(String haystack, int searchFrom) {
      if (searchFrom > 0) {
        return false;
      }
      int length = haystack.length();
      int nearEnd = Math.min(length, FIRST_FIND_NEAR_WINDOW);
      for (char member : smallChars) {
        int index = haystack.indexOf(member, 0, nearEnd);
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(index >= 0 ? index + 1 : nearEnd);
        }
        if (index >= 0) {
          return false;
        }
      }
      for (char member : smallChars) {
        int index = haystack.indexOf(member, nearEnd);
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(index >= 0 ? index - nearEnd + 1 : length - nearEnd);
        }
        if (index >= 0) {
          return false;
        }
      }
      return true;
    }

    /**
     * Checks UTF-8 rejection.
     *
     * <p>A class with a non-ASCII member is only checked from the start of the input. The UTF-8
     * scanner has no memo, so repeating the check from every {@code find()} position would rescan
     * the rest of the input each time.
     *
     * <p>A mixed pair such as {@code [\]\uFF3D]} is first checked with one search for the ASCII
     * member or any non-ASCII byte. On ASCII text that one pass decides the check, as it did when
     * the class was searched as code points. Only from the first non-ASCII byte on is the class
     * checked as two searches: the ASCII member with {@link Utf8InputScanner#indexOfAscii}, and the
     * non-ASCII member as a byte sequence with {@link Utf8InputScanner#indexOfUtf8Sequence}. Both
     * use the byte search kernel. Decoding code points instead, as {@link
     * Utf8InputScanner#indexOfCodePointClass} does, costs several nanoseconds per character on text
     * with no ASCII to skip, such as CJK.
     */
    private boolean canRejectUtf8(Utf8InputScanner scanner, int searchFrom) {
      if (searchFrom > 0 && ranges[ranges.length - 1] >= 0x80) {
        return false;
      }
      int length = scanner.length();
      if (nonAsciiUtf8 != null) {
        int ascii = smallChars[0];
        int first = scanner.indexOfAsciiOrNonAscii(ascii, searchFrom, length);
        if (first < 0) {
          return true;
        }
        if (scanner.asciiAt(first) == ascii) {
          return false;
        }
        return scanner.indexOfMixedPair(ascii, nonAsciiUtf8, first, length) < 0;
      }
      if (singleAscii >= 0) {
        return scanner.indexOfAscii(singleAscii, searchFrom, length) < 0;
      }
      return scanner.indexOfCodePointClass(ranges, bitmap0, bitmap1, searchFrom, length) < 0;
    }

    @Override
    public MatchStrategy strategy() {
      return MatchStrategy.CHARACTER_CLASS;
    }
  }

  @SuppressWarnings("ArrayRecordComponent")
  record DisjointLiterals(
      String[] literals, byte[][] utf8Literals, int[][] failures, int[][] shifts)
      implements RejectPrefilter {

    static DisjointLiterals create(String[] literals) {
      if (literals == null || literals.length == 0) {
        return null;
      }
      int utf8Count = 0;
      for (String literal : literals) {
        if (!hasUnpairedSurrogate(literal)) {
          utf8Count++;
        }
      }
      byte[][] utf8Literals = new byte[utf8Count][];
      int[][] failures = new int[utf8Count][];
      int[][] shifts = new int[utf8Count][];
      int idx = 0;
      for (String literal : literals) {
        if (!hasUnpairedSurrogate(literal)) {
          byte[] utf8 = literal.getBytes(StandardCharsets.UTF_8);
          utf8Literals[idx] = utf8;
          failures[idx] = Pattern.literalFailure(utf8);
          shifts[idx] = Pattern.literalShifts(utf8);
          idx++;
        }
      }
      return new DisjointLiterals(literals, utf8Literals, failures, shifts);
    }

    private static boolean hasUnpairedSurrogate(String s) {
      int len = s.length();
      for (int i = 0; i < len; i++) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) {
          if (i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
            i++;
          } else {
            return true;
          }
        } else if (Character.isLowSurrogate(c)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public boolean canReject(
        String text, Utf8InputScanner utf8, int searchFrom, EnginePathOptions options) {
      if (!options.literalFastPaths() || searchFrom > 0) {
        return false;
      }
      if (text != null) {
        for (String literal : literals) {
          int idx = text.indexOf(literal);
          if (WorkCounterConfig.ENABLED) {
            int scanned = idx >= 0 ? idx + literal.length() : text.length();
            WorkCounter.record(scanned);
          }
          if (idx >= 0) {
            return false;
          }
        }
        return true;
      }
      if (utf8 != null) {
        for (int i = 0; i < utf8Literals.length; i++) {
          if (utf8.indexOf(utf8Literals[i], failures[i], shifts[i], 0) >= 0) {
            return false;
          }
        }
        return true;
      }
      return false;
    }

    @Override
    public MatchStrategy strategy() {
      return MatchStrategy.LITERAL;
    }
  }

  @SuppressWarnings("ArrayRecordComponent")
  record EndAnchoredSuffix(
      String suffix, byte[] suffixUtf8, boolean wasDollar, boolean unixLines, boolean foldCase)
      implements RejectPrefilter {

    static EndAnchoredSuffix create(SuffixInfo info) {
      if (info == null || info.suffix() == null || info.suffix().isEmpty()) {
        return null;
      }
      byte[] utf8 = info.suffix().getBytes(StandardCharsets.UTF_8);
      return new EndAnchoredSuffix(
          info.suffix(), utf8, info.wasDollar(), info.unixLines(), info.foldCase());
    }

    @Override
    public boolean canReject(
        String text, Utf8InputScanner utf8, int searchFrom, EnginePathOptions options) {
      if (!options.literalFastPaths()) {
        return false;
      }
      if (text != null) {
        return !endsWith(text, suffix, wasDollar, unixLines, foldCase);
      }
      if (utf8 != null) {
        return !utf8.endsWith(suffixUtf8, wasDollar, unixLines, foldCase);
      }
      return false;
    }

    @Override
    public MatchStrategy strategy() {
      return MatchStrategy.LITERAL;
    }

    private static boolean endsWith(
        String text, String suffix, boolean wasDollar, boolean unixLines, boolean foldCase) {
      int suffixLen = suffix.length();
      int textLen = text.length();
      if (textLen >= suffixLen
          && (foldCase
              ? text.regionMatches(true, textLen - suffixLen, suffix, 0, suffixLen)
              : text.endsWith(suffix))) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(suffixLen);
        }
        return true;
      }
      if (!wasDollar || text.isEmpty()) {
        return false;
      }
      int trailingStart = StringInputScanner.trailingLineTerminatorStart(text, unixLines, textLen);
      if (trailingStart >= suffixLen
          && (foldCase
              ? text.regionMatches(true, trailingStart - suffixLen, suffix, 0, suffixLen)
              : text.startsWith(suffix, trailingStart - suffixLen))) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(suffixLen);
        }
        return true;
      }
      return false;
    }
  }

  record EndAnchoredCharClass(AsciiBitmap bitmap, boolean wasDollar, boolean unixLines)
      implements RejectPrefilter {
    static EndAnchoredCharClass create(EndAnchoredCharClassInfo info) {
      if (info == null || info.bitmap() == null) {
        return null;
      }
      return new EndAnchoredCharClass(info.bitmap(), info.wasDollar(), info.unixLines());
    }

    @Override
    public boolean canReject(
        String text, Utf8InputScanner utf8, int searchFrom, EnginePathOptions options) {
      if (!options.charClassMatchFastPaths()) {
        return false;
      }
      if (text != null) {
        return canReject(text);
      }
      if (utf8 != null) {
        return canReject(utf8);
      }
      return false;
    }

    private boolean canReject(Utf8InputScanner scanner) {
      int len = scanner.length();
      if (len == 0) {
        return true;
      }
      int ascii = scanner.asciiAt(len - 1);
      if (bitmap.contains(ascii)) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(1);
        }
        return false;
      }
      if (!wasDollar) {
        return true;
      }
      int prevPos = scanner.trailingLineTerminatorStart(unixLines, len);
      if (prevPos > 0) {
        int prevAscii = scanner.asciiAt(prevPos - 1);
        if (bitmap.contains(prevAscii)) {
          if (WorkCounterConfig.ENABLED) {
            WorkCounter.record(1);
          }
          return false;
        }
      }
      return true;
    }

    private boolean canReject(String text) {
      int len = text.length();
      if (len == 0) {
        return true;
      }
      char last = text.charAt(len - 1);
      if (bitmap.contains(last)) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(1);
        }
        return false;
      }
      if (!wasDollar) {
        return true;
      }
      int prevPos = StringInputScanner.trailingLineTerminatorStart(text, unixLines, len);
      if (prevPos > 0) {
        char prev = text.charAt(prevPos - 1);
        if (bitmap.contains(prev)) {
          if (WorkCounterConfig.ENABLED) {
            WorkCounter.record(1);
          }
          return false;
        }
      }
      return true;
    }

    @Override
    public MatchStrategy strategy() {
      return MatchStrategy.CHARACTER_CLASS;
    }
  }

  @SuppressWarnings("ArrayRecordComponent")
  record Composite(RejectPrefilter[] filters) implements RejectPrefilter {
    @Override
    public boolean canReject(
        String text, Utf8InputScanner utf8, int searchFrom, EnginePathOptions options) {
      for (RejectPrefilter filter : filters) {
        if (filter.canReject(text, utf8, searchFrom, options)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public MatchStrategy rejectionStrategy(
        String text, Utf8InputScanner utf8, int searchFrom, EnginePathOptions options) {
      for (RejectPrefilter filter : filters) {
        MatchStrategy strategy = filter.rejectionStrategy(text, utf8, searchFrom, options);
        if (strategy != null) {
          return strategy;
        }
      }
      return null;
    }

    @Override
    public MatchStrategy strategy() {
      return filters[0].strategy();
    }
  }
}
