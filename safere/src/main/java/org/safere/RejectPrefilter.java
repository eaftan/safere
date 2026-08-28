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
  record Literal(String literal, byte[] utf8, int[] failure, int[] shifts)
      implements RejectPrefilter {

    static Literal create(String literal) {
      byte[] utf8 = literal.getBytes(StandardCharsets.UTF_8);
      int[] failure = Pattern.literalFailure(utf8);
      int[] shifts = Pattern.literalShifts(utf8);
      return new Literal(literal, utf8, failure, shifts);
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
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(Math.max(0, text.length() - searchFrom));
        }
        return text.indexOf(literal, searchFrom) < 0;
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
  record CharClass(int[] ranges, long bitmap0, long bitmap1) implements RejectPrefilter {

    static CharClass create(CharClassScanInfo scanInfo) {
      return new CharClass(scanInfo.ranges(), scanInfo.bitmap0(), scanInfo.bitmap1());
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
      if (scanner != null) {
        return scanner.indexOfCodePointClass(ranges, bitmap0, bitmap1, searchFrom, scanner.length())
            < 0;
      }
      if (text != null) {
        return new StringInputScanner(text)
                .indexOfCodePointClass(ranges, bitmap0, bitmap1, searchFrom, text.length())
            < 0;
      }
      return false;
    }

    @Override
    public boolean canReject(Utf8InputScanner scanner, int searchFrom, EnginePathOptions options) {
      if (!options.charClassMatchFastPaths()) {
        return false;
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
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(Math.max(0, text.length() - searchFrom));
        }
        if (text.indexOf(literal, searchFrom) >= 0) {
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
