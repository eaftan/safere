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

  /** Returns whether the input starting from {@code searchFrom} can be rejected. */
  boolean canReject(InputScanner scanner, String text, int searchFrom, EnginePathOptions options);

  /** Returns the strategy that rejected the input, or {@code null} if it cannot be rejected. */
  default MatchStrategy rejectionStrategy(
      InputScanner scanner, String text, int searchFrom, EnginePathOptions options) {
    return canReject(scanner, text, searchFrom, options) ? strategy() : null;
  }

  /** Returns whether the UTF-8 input starting from {@code searchFrom} can be rejected. */
  boolean canReject(Utf8InputScanner scanner, int searchFrom, EnginePathOptions options);

  default boolean canRejectWithDiagnostics(
      Utf8InputScanner scanner,
      int searchFrom,
      EnginePathOptions options,
      DiagnosticAccumulator diagnostics) {
    if (canReject(scanner, searchFrom, options)) {
      diagnostics.participate(strategy(), StrategyRole.REJECT_PREFILTER);
      diagnostics.boundary(strategy());
      return true;
    }
    return false;
  }

  MatchStrategy strategy();

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
      case MultiAnchorDescriptor.RejectPlan.None unusedNone -> null;
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
        InputScanner scanner, String text, int searchFrom, EnginePathOptions options) {
      if (!options.literalFastPaths()) {
        return false;
      }
      if (scanner instanceof Utf8InputScanner utf8Scanner) {
        return utf8Scanner.indexOf(utf8, failure, shifts, searchFrom) < 0;
      }
      if (text != null) {
        return StringLiteralSearch.indexOf(text, literal, anchorOffset, anchor, searchFrom) < 0;
      }
      return false;
    }

    @Override
    public boolean canReject(Utf8InputScanner scanner, int searchFrom, EnginePathOptions options) {
      if (!options.literalFastPaths()) {
        return false;
      }
      return scanner.indexOf(utf8, failure, shifts, searchFrom) < 0;
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
      int[] nonAsciiRanges)
      implements RejectPrefilter {

    /**
     * Chars {@link #rejectsSmall} probes before the per-member searches, chosen on {@code
     * citationScrubberFullWidth}, where the second member often occurs within a few chars of the
     * search start while the first is absent.
     */
    private static final int REJECT_PROBE_CHARS = 16;

    static CharClass create(CharClassScanInfo scanInfo) {
      char[] small = smallChars(scanInfo);
      return new CharClass(
          scanInfo.ranges(),
          scanInfo.bitmap0(),
          scanInfo.bitmap1(),
          singleAscii(scanInfo),
          small,
          nonAsciiRangesOfMixedPair(small));
    }

    /**
     * Returns the range of the non-ASCII member of a small set with one ASCII and one non-ASCII
     * member, such as {@code [\]\uFF3D]}, or {@code null} for every other class. Members are
     * sorted, so the ASCII one comes first.
     */
    private static int[] nonAsciiRangesOfMixedPair(char[] small) {
      return small != null && small.length == 2 && small[0] < 0x80 && small[1] >= 0x80
          ? new int[] {small[1], small[1]}
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
     * Returns the one or two members of a small class, or {@code null}. Each member is searched
     * with the intrinsified {@link String#indexOf(int, int)}; see {@link #rejectsSmall}.
     */
    private static char[] smallChars(CharClassScanInfo scanInfo) {
      return scanInfo instanceof CharClassScanInfo.SmallSet smallSet
              && smallSet.chars() != null
              && smallSet.chars().length <= 2
          ? smallSet.chars()
          : null;
    }

    @Override
    public boolean canReject(
        InputScanner scanner, String text, int searchFrom, EnginePathOptions options) {
      if (!options.charClassMatchFastPaths()) {
        return false;
      }
      if (scanner instanceof Utf8InputScanner utf8Scanner) {
        return canReject(utf8Scanner, searchFrom, options);
      }
      if (smallChars != null && (scanner instanceof StringInputScanner || text != null)) {
        return rejectsSmall(scanner, text, searchFrom);
      }
      if (scanner != null) {
        return indexOf(scanner, searchFrom, scanner.length()) < 0;
      }
      if (text != null) {
        return indexOf(new StringInputScanner(text), searchFrom, text.length()) < 0;
      }
      return false;
    }

    /**
     * Returns whether no member of {@link #smallChars} occurs at or after {@code searchFrom}.
     *
     * <p>A single member needs no memo: every match contains an occurrence of it, so the next
     * {@code find()} starts past the occurrence this scan stopped at. With two members that holds
     * only for the nearer one, and rescanning for the farther one on every call is quadratic, so
     * later searches go through the scanner's memo. The first search, from the start of the input,
     * has nothing to reuse; it is also the only search a one-shot {@code replaceAll} or {@code
     * find} on non-matching input makes, so it skips the memo. Both are preceded by a short {@link
     * StringInputScanner#probeEither probe}, so a member near {@code searchFrom} is found without
     * searching the whole text for the other.
     */
    private boolean rejectsSmall(InputScanner scanner, String text, int searchFrom) {
      char c0 = smallChars[0];
      if (!(scanner instanceof StringInputScanner s)) {
        return text.indexOf(c0, searchFrom) < 0
            && (smallChars.length == 1 || text.indexOf(smallChars[1], searchFrom) < 0);
      }
      if (smallChars.length == 1) {
        return s.indexOfChar(c0, searchFrom) < 0;
      }
      char c1 = smallChars[1];
      int probe = s.probeEither(c0, c1, searchFrom, REJECT_PROBE_CHARS);
      if (probe >= 0) {
        return false;
      }
      int rest = ~probe;
      if (searchFrom > 0) {
        return s.memoizedIndexOf(c0, rest) < 0 && s.memoizedIndexOf(c1, rest) < 0;
      }
      return s.indexOfChar(c0, rest) < 0 && s.indexOfChar(c1, rest) < 0;
    }

    private int indexOf(InputScanner scanner, int searchFrom, int limit) {
      return singleAscii >= 0
          ? scanner.indexOfAscii(singleAscii, searchFrom, limit)
          : scanner.indexOfCodePointClass(ranges, bitmap0, bitmap1, searchFrom, limit);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A class with a non-ASCII member is only checked from the start of the input. The UTF-8
     * scanner has no memo, so repeating the check from every {@code find()} position would rescan
     * the rest of the input each time.
     *
     * <p>A mixed pair such as {@code [\]\uFF3D]} is checked as two searches: the ASCII member with
     * {@link Utf8InputScanner#indexOfAscii}, and the non-ASCII member with {@link
     * Utf8InputScanner#indexOfNonAsciiClass}, which skips ASCII bytes eight at a time. The general
     * {@link Utf8InputScanner#indexOfCodePointClass} would decode every code point instead.
     */
    @Override
    public boolean canReject(Utf8InputScanner scanner, int searchFrom, EnginePathOptions options) {
      if (!options.charClassMatchFastPaths()
          || (searchFrom > 0 && ranges[ranges.length - 1] >= 0x80)) {
        return false;
      }
      if (nonAsciiRanges != null) {
        return scanner.indexOfAscii(smallChars[0], searchFrom, scanner.length()) < 0
            && scanner.indexOfNonAsciiClass(nonAsciiRanges, searchFrom, scanner.length()) < 0;
      }
      return scanner.indexOfCodePointClass(ranges, bitmap0, bitmap1, searchFrom, scanner.length())
          < 0;
    }

    @Override
    public MatchStrategy strategy() {
      return MatchStrategy.CHARACTER_CLASS;
    }
  }

  @SuppressWarnings("ArrayRecordComponent")
  record DisjointLiterals(String[] literals) implements RejectPrefilter {

    static DisjointLiterals create(String[] literals) {
      if (literals == null || literals.length == 0) {
        return null;
      }
      return new DisjointLiterals(literals);
    }

    @Override
    public boolean canReject(
        InputScanner scanner, String text, int searchFrom, EnginePathOptions options) {
      if (!options.literalFastPaths() || text == null || searchFrom > 0) {
        return false;
      }
      for (String literal : literals) {
        int idx = text.indexOf(literal, searchFrom);
        if (WorkCounterConfig.ENABLED) {
          int scanned = idx >= 0 ? idx - searchFrom + literal.length() : text.length() - searchFrom;
          WorkCounter.record(Math.max(0, scanned));
        }
        if (idx >= 0) {
          return false;
        }
      }
      return true;
    }

    @Override
    public boolean canReject(Utf8InputScanner scanner, int searchFrom, EnginePathOptions options) {
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
        InputScanner scanner, String text, int searchFrom, EnginePathOptions options) {
      if (!options.literalFastPaths()) {
        return false;
      }
      if (scanner instanceof Utf8InputScanner utf8Scanner) {
        return !utf8Scanner.endsWith(suffixUtf8, wasDollar, unixLines, foldCase);
      }
      if (text != null) {
        return !endsWith(text, suffix, wasDollar, unixLines, foldCase);
      }
      return false;
    }

    @Override
    public boolean canReject(Utf8InputScanner scanner, int searchFrom, EnginePathOptions options) {
      if (!options.literalFastPaths()) {
        return false;
      }
      return !scanner.endsWith(suffixUtf8, wasDollar, unixLines, foldCase);
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
        InputScanner scanner, String text, int searchFrom, EnginePathOptions options) {
      if (!options.charClassMatchFastPaths()) {
        return false;
      }
      if (scanner instanceof Utf8InputScanner utf8Scanner) {
        return canReject(utf8Scanner, searchFrom, options);
      }
      if (text != null) {
        return canReject(text);
      }
      return false;
    }

    @Override
    public boolean canReject(Utf8InputScanner scanner, int searchFrom, EnginePathOptions options) {
      if (!options.charClassMatchFastPaths()) {
        return false;
      }
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
        InputScanner scanner, String text, int searchFrom, EnginePathOptions options) {
      for (RejectPrefilter filter : filters) {
        if (filter.canReject(scanner, text, searchFrom, options)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public MatchStrategy rejectionStrategy(
        InputScanner scanner, String text, int searchFrom, EnginePathOptions options) {
      for (RejectPrefilter filter : filters) {
        MatchStrategy strategy = filter.rejectionStrategy(scanner, text, searchFrom, options);
        if (strategy != null) {
          return strategy;
        }
      }
      return null;
    }

    @Override
    public boolean canReject(Utf8InputScanner scanner, int searchFrom, EnginePathOptions options) {
      for (RejectPrefilter filter : filters) {
        if (filter.canReject(scanner, searchFrom, options)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public boolean canRejectWithDiagnostics(
        Utf8InputScanner scanner,
        int searchFrom,
        EnginePathOptions options,
        DiagnosticAccumulator diagnostics) {
      for (RejectPrefilter filter : filters) {
        if (filter.canRejectWithDiagnostics(scanner, searchFrom, options, diagnostics)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public MatchStrategy strategy() {
      return filters[0].strategy();
    }
  }
}
