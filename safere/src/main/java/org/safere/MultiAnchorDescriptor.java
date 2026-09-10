// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable descriptor capturing pre-computed multi-anchor sequence metadata extracted from a
 * regular expression AST. Enables divide-and-conquer execution by pinning match positions around
 * fast SIMD anchors and verifying intermediate gaps.
 */
final class MultiAnchorDescriptor {

  private final Chain chain;
  private final StartPlan startPlan;
  private final RejectPlan rejectPlan;
  private final String anchoredPrefix;
  private final CharClassScanInfo anchoredCharClassPrefix;
  private final boolean executableChain;
  private final boolean executableUtf8Chain;

  public static final MultiAnchorDescriptor NONE =
      new MultiAnchorDescriptor(Chain.EMPTY, StartPlan.None.INSTANCE, RejectPlan.None.INSTANCE);

  MultiAnchorDescriptor(Chain chain, StartPlan startPlan, RejectPlan rejectPlan) {
    this(chain, startPlan, rejectPlan, null, null);
  }

  MultiAnchorDescriptor(
      Chain chain,
      StartPlan startPlan,
      RejectPlan rejectPlan,
      String anchoredPrefix,
      CharClassScanInfo anchoredCharClassPrefix) {
    this.chain = Objects.requireNonNull(chain, "chain");
    this.startPlan = Objects.requireNonNull(startPlan, "startPlan");
    this.rejectPlan = Objects.requireNonNull(rejectPlan, "rejectPlan");
    this.anchoredPrefix = anchoredPrefix;
    this.anchoredCharClassPrefix = anchoredCharClassPrefix;
    // Eligibility depends only on the compiled chain, whose segments are never mutated after
    // construction. Cache both input domains so dispatch and execution can check it in constant
    // time.
    this.executableChain = computeExecutableChain(chain);
    this.executableUtf8Chain = computeExecutableUtf8Chain(chain, executableChain);
  }

  Chain chain() {
    return chain;
  }

  StartPlan startPlan() {
    return startPlan;
  }

  RejectPlan rejectPlan() {
    return rejectPlan;
  }

  String anchoredPrefix() {
    return anchoredPrefix;
  }

  CharClassScanInfo anchoredCharClassPrefix() {
    return anchoredCharClassPrefix;
  }

  enum InputDomain {
    STRING,
    UTF8
  }

  @SuppressWarnings("ArrayRecordComponent")
  record Chain(
      Segment[] segments,
      Gap trailingGap,
      int[] checkOrder,
      int driverIndex,
      boolean isUpstreamBounded,
      int minTotalLength,
      boolean isStartAnchored,
      boolean isEndAnchored,
      boolean endAnchorWasDollar,
      boolean endAnchorUnixLines,
      int stringDriverIndex,
      int utf8VectorDriverIndex,
      int utf8ScalarDriverIndex) {

    public static final Chain EMPTY =
        new Chain(
            new Segment[0],
            Gap.EMPTY,
            new int[0],
            0,
            false,
            0,
            false,
            false,
            false,
            false,
            0,
            0,
            0);

    public Chain {
      Objects.requireNonNull(segments, "segments");
      Objects.requireNonNull(trailingGap, "trailingGap");
      Objects.requireNonNull(checkOrder, "checkOrder");
    }

    Chain(
        Segment[] segments,
        Gap trailingGap,
        int[] checkOrder,
        int driverIndex,
        boolean isUpstreamBounded,
        int minTotalLength,
        boolean isStartAnchored,
        boolean isEndAnchored,
        boolean endAnchorWasDollar,
        boolean endAnchorUnixLines) {
      this(
          segments,
          trailingGap,
          checkOrder,
          driverIndex,
          isUpstreamBounded,
          minTotalLength,
          isStartAnchored,
          isEndAnchored,
          endAnchorWasDollar,
          endAnchorUnixLines,
          computeDriverIndices(segments, checkOrder));
    }

    Chain(
        Segment[] segments,
        Gap trailingGap,
        int[] checkOrder,
        int driverIndex,
        boolean isUpstreamBounded,
        int minTotalLength,
        boolean isStartAnchored,
        boolean isEndAnchored) {
      this(
          segments,
          trailingGap,
          checkOrder,
          driverIndex,
          isUpstreamBounded,
          minTotalLength,
          isStartAnchored,
          isEndAnchored,
          false,
          false);
    }

    private Chain(
        Segment[] segments,
        Gap trailingGap,
        int[] checkOrder,
        int driverIndex,
        boolean isUpstreamBounded,
        int minTotalLength,
        boolean isStartAnchored,
        boolean isEndAnchored,
        boolean endAnchorWasDollar,
        boolean endAnchorUnixLines,
        DriverIndices drivers) {
      this(
          segments,
          trailingGap,
          checkOrder,
          driverIndex,
          isUpstreamBounded,
          minTotalLength,
          isStartAnchored,
          isEndAnchored,
          endAnchorWasDollar,
          endAnchorUnixLines,
          drivers.stringIndex(),
          drivers.utf8VectorIndex(),
          drivers.utf8ScalarIndex());
    }

    Chain(
        Segment[] segments,
        Gap trailingGap,
        int[] checkOrder,
        int minTotalLength,
        boolean isStartAnchored,
        boolean isEndAnchored,
        boolean endAnchorWasDollar,
        boolean endAnchorUnixLines) {
      this(
          segments,
          trailingGap,
          checkOrder,
          computeDefaultDriverIndex(segments, checkOrder),
          computeIsUpstreamBounded(segments, computeDefaultDriverIndex(segments, checkOrder)),
          minTotalLength,
          isStartAnchored,
          isEndAnchored,
          endAnchorWasDollar,
          endAnchorUnixLines);
    }

    Chain(
        Segment[] segments,
        Gap trailingGap,
        int[] checkOrder,
        int minTotalLength,
        boolean isStartAnchored,
        boolean isEndAnchored) {
      this(
          segments,
          trailingGap,
          checkOrder,
          minTotalLength,
          isStartAnchored,
          isEndAnchored,
          false,
          false);
    }

    public int selectDriver(InputDomain domain, boolean vectorAvailable) {
      if (domain == InputDomain.STRING) {
        return stringDriverIndex;
      }
      return vectorAvailable ? utf8VectorDriverIndex : utf8ScalarDriverIndex;
    }

    private static DriverIndices computeDriverIndices(Segment[] segments, int[] checkOrder) {
      if (checkOrder == null
          || checkOrder.length == 0
          || segments == null
          || segments.length == 0) {
        return new DriverIndices(0, 0, 0);
      }

      int maxFixedDriver = 0;
      while (maxFixedDriver + 1 < segments.length) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        if (!segments[maxFixedDriver + 1].gap().isExecutorFixedGap()) {
          break;
        }
        maxFixedDriver++;
      }

      int stringDriver = 0;
      int utf8VectorDriver = 0;
      int utf8ScalarDriver = 0;
      boolean foundString = false;
      boolean foundUtf8Vector = false;
      boolean foundUtf8Scalar = false;
      for (int candidate : checkOrder) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        if (candidate < 0 || candidate >= segments.length || candidate > maxFixedDriver) {
          continue;
        }
        Anchor anchor = segments[candidate].anchor();
        if (!foundString && anchor.isHardwareAccelerated(InputDomain.STRING)) {
          stringDriver = candidate;
          foundString = true;
        }
        if (!foundUtf8Vector
            && (anchor.isHardwareAccelerated(InputDomain.UTF8) || anchor.minLength() >= 1)) {
          utf8VectorDriver = candidate;
          foundUtf8Vector = true;
        }
        if (!foundUtf8Scalar && anchor.isHardwareAccelerated(InputDomain.UTF8)) {
          utf8ScalarDriver = candidate;
          foundUtf8Scalar = true;
        }
        if (foundString && foundUtf8Vector && foundUtf8Scalar) {
          break;
        }
      }
      return new DriverIndices(stringDriver, utf8VectorDriver, utf8ScalarDriver);
    }

    private record DriverIndices(int stringIndex, int utf8VectorIndex, int utf8ScalarIndex) {}

    private static int computeDefaultDriverIndex(Segment[] segments, int[] checkOrder) {
      if (segments == null || segments.length == 0) {
        return 0;
      }
      if (checkOrder != null && checkOrder.length > 0) {
        int rarest = checkOrder[0];
        if (rarest >= 0 && rarest < segments.length) {
          return rarest;
        }
      }
      return 0;
    }

    private static boolean computeIsUpstreamBounded(Segment[] segments, int driverIdx) {
      if (segments == null || segments.length == 0 || driverIdx <= 0) {
        return true;
      }
      for (int i = 0; i <= driverIdx; i++) {
        Gap g = segments[i].gap();
        if (g.maxLength() == Integer.MAX_VALUE || g.kind() == GapKind.ANY_STAR) {
          return false;
        }
      }
      return true;
    }
  }

  sealed interface StartPlan {
    record None() implements StartPlan {
      static final None INSTANCE = new None();
    }

    record Literal(String prefix, boolean foldCase, ClassHashChain classHashChain)
        implements StartPlan {
      public Literal {
        Objects.requireNonNull(prefix, "prefix");
      }
    }

    record CharClass(CharClassScanInfo scanInfo) implements StartPlan {
      public CharClass {
        Objects.requireNonNull(scanInfo, "scanInfo");
      }
    }

    record FixedOffset(Pattern.FixedOffsetLiteral fol, CharClassScanInfo leadingClass)
        implements StartPlan {
      public FixedOffset {
        Objects.requireNonNull(fol, "fol");
      }
    }

    @SuppressWarnings("ArrayRecordComponent")
    record MultiLiteral(String[] literals, CharClassScanInfo fallbackClass) implements StartPlan {
      public MultiLiteral {
        Objects.requireNonNull(literals, "literals");
      }
    }

    record LeadingExpansion(
        CharClassScanInfo leadingClass, int minRepetition, int maxRepetition, StartPlan innerPlan)
        implements StartPlan {
      public LeadingExpansion {
        Objects.requireNonNull(leadingClass, "leadingClass");
        Objects.requireNonNull(innerPlan, "innerPlan");
      }
    }

    record LineAnchor(Pattern.StartAcceleration acceleration) implements StartPlan {
      public LineAnchor {
        Objects.requireNonNull(acceleration, "acceleration");
      }
    }
  }

  sealed interface RejectPlan {
    record None() implements RejectPlan {
      static final None INSTANCE = new None();
    }

    record RequiredLiteral(String literal) implements RejectPlan {
      public RequiredLiteral {
        Objects.requireNonNull(literal, "literal");
      }
    }

    record RequiredCharClass(CharClassScanInfo scanInfo) implements RejectPlan {
      public RequiredCharClass {
        Objects.requireNonNull(scanInfo, "scanInfo");
      }
    }

    @SuppressWarnings("ArrayRecordComponent")
    record DisjointLiterals(String[] literals) implements RejectPlan {
      public DisjointLiterals {
        Objects.requireNonNull(literals, "literals");
      }
    }

    record EndAnchoredSuffix(Pattern.SuffixInfo suffix) implements RejectPlan {
      public EndAnchoredSuffix {
        Objects.requireNonNull(suffix, "suffix");
      }
    }

    record EndAnchoredCharClass(Pattern.EndAnchoredCharClassInfo charClass) implements RejectPlan {
      public EndAnchoredCharClass {
        Objects.requireNonNull(charClass, "charClass");
      }
    }

    @SuppressWarnings("ArrayRecordComponent")
    record Composite(RejectPlan[] plans) implements RejectPlan {
      public Composite {
        Objects.requireNonNull(plans, "plans");
      }
    }
  }

  record Segment(Gap gap, Anchor anchor) {
    Segment {
      Objects.requireNonNull(gap, "gap");
      Objects.requireNonNull(anchor, "anchor");
    }
  }

  MultiAnchorDescriptor(
      Segment[] segments,
      Gap trailingGap,
      int[] checkOrder,
      int minTotalLength,
      boolean isStartAnchored,
      boolean isEndAnchored,
      boolean endAnchorWasDollar,
      boolean endAnchorUnixLines) {
    this(
        new Chain(
            segments,
            trailingGap,
            checkOrder,
            minTotalLength,
            isStartAnchored,
            isEndAnchored,
            endAnchorWasDollar,
            endAnchorUnixLines),
        StartPlan.None.INSTANCE,
        RejectPlan.None.INSTANCE);
  }

  MultiAnchorDescriptor(
      Segment[] segments,
      Gap trailingGap,
      int[] checkOrder,
      int minTotalLength,
      boolean isStartAnchored,
      boolean isEndAnchored) {
    this(
        segments,
        trailingGap,
        checkOrder,
        minTotalLength,
        isStartAnchored,
        isEndAnchored,
        false,
        false);
  }

  Segment[] segments() {
    return chain.segments();
  }

  Gap trailingGap() {
    return chain.trailingGap();
  }

  int[] checkOrder() {
    return chain.checkOrder();
  }

  int selectDriver(InputDomain domain, boolean vectorAvailable) {
    return chain.selectDriver(domain, vectorAvailable);
  }

  int minTotalLength() {
    return chain.minTotalLength();
  }

  boolean isEndAnchored() {
    return chain.isEndAnchored();
  }

  boolean endAnchorWasDollar() {
    return chain.endAnchorWasDollar();
  }

  boolean endAnchorUnixLines() {
    return chain.endAnchorUnixLines();
  }

  boolean hasRejectionFilter() {
    return !(rejectPlan instanceof RejectPlan.None);
  }

  boolean hasStartAcceleration() {
    return !(startPlan instanceof StartPlan.None) || isReverseAnchor();
  }

  String prefix() {
    if (chain.isStartAnchored()) {
      return null;
    }
    return startPlan instanceof StartPlan.Literal lit ? lit.prefix() : null;
  }

  boolean prefixFoldCase() {
    if (chain.isStartAnchored()) {
      return false;
    }
    return startPlan instanceof StartPlan.Literal lit && lit.foldCase();
  }

  CharClassScanInfo charClassPrefix() {
    if (chain.isStartAnchored()) {
      return null;
    }
    return startPlan instanceof StartPlan.CharClass cc ? cc.scanInfo() : null;
  }

  boolean isReverseAnchor() {
    return (chain.isEndAnchored()
            || chain.trailingGap().kind() == GapKind.LINE_END
            || chain.trailingGap().kind() == GapKind.EMPTY)
        && chain.segments().length > 0
        && chain.segments()[0].gap().maxLength() == Integer.MAX_VALUE
        && (chain.segments()[0].gap().kind() == GapKind.ANY_STAR
            || chain.segments()[0].gap().kind() == GapKind.SINGLE_LINE_ANY_STAR);
  }

  boolean isExecutableChain() {
    return executableChain;
  }

  private static boolean computeExecutableChain(Chain chain) {
    int n = chain.segments().length;
    if (n < 2 || !isExecutableLeadingGap(chain.segments()[0].gap())) {
      return false;
    }
    for (int i = 0; i < n; i++) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      Segment segment = chain.segments()[i];
      if (!isExecutableAnchor(segment.anchor())) {
        return false;
      }
      if (i > 0 && !isExecutableInteriorGap(segment.gap(), chain.isEndAnchored())) {
        return false;
      }
      if (segment.gap().isExecutorGuardedGap()
          && (i != n - 1 || !isInfallibleTrailingGap(chain.trailingGap()))) {
        return false;
      }
    }
    return chain.isEndAnchored()
        ? isExecutableEndAnchoredTrailingGap(chain.trailingGap())
        : isExecutableTrailingGap(chain.trailingGap());
  }

  private static boolean isExecutableAnchor(Anchor anchor) {
    return anchor instanceof Anchor.Single || anchor instanceof Anchor.CharClass;
  }

  private static boolean isExecutableLeadingGap(Gap gap) {
    return switch (gap.kind()) {
      case EMPTY, TEXT_START, ANY_STAR, SINGLE_LINE_ANY_STAR -> true;
      case BOUNDED_CLASS_REPEAT ->
          gap.scanInfo() != null || gap.charClass() != null || gap.isExecutorGuardedGap();
      case COMPOUND_SEQUENCE -> gap.isExecutorFixedGap();
      case TEXT_END, WORD_BOUNDARY, NO_WORD_BOUNDARY, LINE_START, LINE_END -> false;
    };
  }

  private static boolean isExecutableInteriorGap(Gap gap, boolean isEndAnchored) {
    if (isEndAnchored
        && (gap.kind() == GapKind.ANY_STAR || gap.kind() == GapKind.SINGLE_LINE_ANY_STAR)) {
      return false;
    }
    return isExecutableInteriorGap(gap);
  }

  private static boolean isExecutableInteriorGap(Gap gap) {
    return switch (gap.kind()) {
      case EMPTY, ANY_STAR, SINGLE_LINE_ANY_STAR -> true;
      case BOUNDED_CLASS_REPEAT ->
          gap.scanInfo() != null || gap.charClass() != null || gap.isExecutorGuardedGap();
      case COMPOUND_SEQUENCE -> gap.isExecutorFixedGap();
      case TEXT_START, TEXT_END, WORD_BOUNDARY, NO_WORD_BOUNDARY, LINE_START, LINE_END -> false;
    };
  }

  private static boolean isExecutableEndAnchoredTrailingGap(Gap gap) {
    return switch (gap.kind()) {
      case EMPTY, TEXT_END -> true;
      case BOUNDED_CLASS_REPEAT ->
          gap.scanInfo() != null || gap.charClass() != null || gap.isExecutorGuardedGap();
      case COMPOUND_SEQUENCE -> gap.isExecutorFixedGap();
      case ANY_STAR,
          SINGLE_LINE_ANY_STAR,
          TEXT_START,
          WORD_BOUNDARY,
          NO_WORD_BOUNDARY,
          LINE_START,
          LINE_END ->
          false;
    };
  }

  private static boolean isExecutableTrailingGap(Gap gap) {
    if (gap.minLength() == 0 && !gap.isGreedy()) {
      return true;
    }
    return switch (gap.kind()) {
      case EMPTY, TEXT_END, ANY_STAR, SINGLE_LINE_ANY_STAR -> true;
      case BOUNDED_CLASS_REPEAT ->
          gap.scanInfo() != null || gap.charClass() != null || gap.isExecutorGuardedGap();
      case COMPOUND_SEQUENCE -> gap.isExecutorFixedGap();
      case TEXT_START, WORD_BOUNDARY, NO_WORD_BOUNDARY, LINE_START, LINE_END -> false;
    };
  }

  private static boolean isInfallibleTrailingGap(Gap gap) {
    return gap.kind() == GapKind.EMPTY || gap.isExecutorGuardedGap();
  }

  boolean isExecutableUtf8Chain() {
    return executableUtf8Chain;
  }

  private static boolean computeExecutableUtf8Chain(Chain chain, boolean executableChain) {
    if (!executableChain) {
      return false;
    }
    for (Segment segment : chain.segments()) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      switch (segment.anchor()) {
        case Anchor.Single single -> {
          if (hasUnpairedSurrogate(single.literal())) {
            return false;
          }
          if (single.foldCase() && !isAscii(single.literal())) {
            return false;
          }
        }
        case Anchor.Alternation alt -> {
          if (alt.foldCase()) {
            for (String lit : alt.literals()) {
              if (!isAscii(lit)) {
                return false;
              }
            }
          }
        }
        case Anchor.CharClass unusedCc -> {}
      }
    }
    return true;
  }

  private static boolean hasUnpairedSurrogate(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
          return true;
        }
        i++;
      } else if (Character.isLowSurrogate(c)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isAscii(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) > 0x7f) {
        return false;
      }
    }
    return true;
  }

  enum GapKind {
    /** Zero-width gap (adjacent anchors or no leading/trailing gap). */
    EMPTY,
    /** Zero-width text start assertion (\A or ^ in single-line mode). */
    TEXT_START,
    /** Zero-width line start assertion (^ or (?m)^). */
    LINE_START,
    /** Zero-width text end assertion (\z). */
    TEXT_END,
    /** Zero-width line end assertion ($ or (?m)$). */
    LINE_END,
    /** Zero-width word boundary assertion (\b). */
    WORD_BOUNDARY,
    /** Zero-width non-word boundary assertion (\B). */
    NO_WORD_BOUNDARY,
    /** Unbounded arbitrary characters ({@code .*} in DOTALL mode). */
    ANY_STAR,
    /** Unbounded single-line characters ({@code .*} in non-DOTALL mode or {@code [^\n]*}). */
    SINGLE_LINE_ANY_STAR,
    /** Bounded or unbounded character class repetition (e.g. {@code \s+}, {@code \d{1,4}}). */
    BOUNDED_CLASS_REPEAT,
    /** Heterogeneous fixed-width sequence of character class constraints. */
    COMPOUND_SEQUENCE
  }

  @SuppressWarnings("ArrayRecordComponent")
  record Gap(
      GapKind kind,
      int minLength,
      int maxLength,
      int[] discreteOffsets,
      AsciiBitmap charClass,
      int[] charClassRanges,
      CharClassScanInfo scanInfo,
      boolean isGreedy,
      byte[] guardBytes,
      boolean isPureComplement,
      CharClassScanInfo[] classSequence) {
    static final Gap EMPTY =
        new Gap(GapKind.EMPTY, 0, 0, null, null, null, null, true, null, false);
    static final Gap TEXT_START =
        new Gap(GapKind.TEXT_START, 0, 0, null, null, null, null, true, null, false);
    static final Gap TEXT_END =
        new Gap(GapKind.TEXT_END, 0, 0, null, null, null, null, true, null, false);
    static final Gap WORD_BOUNDARY =
        new Gap(GapKind.WORD_BOUNDARY, 0, 0, null, null, null, null, true, null, false);
    static final Gap NO_WORD_BOUNDARY =
        new Gap(GapKind.NO_WORD_BOUNDARY, 0, 0, null, null, null, null, true, null, false);
    static final Gap LINE_START =
        new Gap(GapKind.LINE_START, 0, 0, null, null, null, null, true, null, false);
    static final Gap LINE_END =
        new Gap(GapKind.LINE_END, 0, 0, null, null, null, null, true, null, false);
    static final Gap ANY_STAR_GREEDY =
        new Gap(GapKind.ANY_STAR, 0, Integer.MAX_VALUE, null, null, null, null, true, null, false);
    static final Gap ANY_STAR_LAZY =
        new Gap(GapKind.ANY_STAR, 0, Integer.MAX_VALUE, null, null, null, null, false, null, false);
    static final Gap SINGLE_LINE_ANY_STAR_GREEDY =
        new Gap(
            GapKind.SINGLE_LINE_ANY_STAR,
            0,
            Integer.MAX_VALUE,
            null,
            null,
            null,
            null,
            true,
            new byte[] {'\n', '\r'},
            true);
    static final Gap SINGLE_LINE_ANY_STAR_LAZY =
        new Gap(
            GapKind.SINGLE_LINE_ANY_STAR,
            0,
            Integer.MAX_VALUE,
            null,
            null,
            null,
            null,
            false,
            new byte[] {'\n', '\r'},
            true);
    static final Gap SINGLE_LINE_ANY_STAR_UNIX_GREEDY =
        new Gap(
            GapKind.SINGLE_LINE_ANY_STAR,
            0,
            Integer.MAX_VALUE,
            null,
            null,
            null,
            null,
            true,
            new byte[] {'\n'},
            true);
    static final Gap SINGLE_LINE_ANY_STAR_UNIX_LAZY =
        new Gap(
            GapKind.SINGLE_LINE_ANY_STAR,
            0,
            Integer.MAX_VALUE,
            null,
            null,
            null,
            null,
            false,
            new byte[] {'\n'},
            true);

    boolean isFixed() {
      return minLength == maxLength;
    }

    boolean isExecutorFixedGap() {
      return kind == GapKind.EMPTY
          || (kind == GapKind.BOUNDED_CLASS_REPEAT && isFixed() && scanInfo != null)
          || (kind == GapKind.COMPOUND_SEQUENCE && isFixed() && classSequence != null);
    }

    boolean isExecutorGuardedGap() {
      return kind == GapKind.BOUNDED_CLASS_REPEAT
          && minLength == 0
          && guardBytes != null
          && isPureComplement;
    }

    int findFirstGuardByte(String text, int from, int to) {
      return GapScanner.findFirstGuardByte(guardBytes, text, from, to);
    }

    int findFirstGuardByte(Utf8InputScanner scanner, int from, int to) {
      return GapScanner.findFirstGuardByte(guardBytes, scanner, from, to);
    }

    int findLastGuardByte(String text, int minLimit, int fromIndex) {
      return GapScanner.findLastGuardByte(guardBytes, text, minLimit, fromIndex);
    }

    int findLastGuardByte(Utf8InputScanner scanner, int minLimit, int fromIndex) {
      return GapScanner.findLastGuardByte(guardBytes, scanner, minLimit, fromIndex);
    }

    private int boundedCodePointEnd(String text, int fromPos, int maxPos) {
      if (maxLength == Integer.MAX_VALUE) {
        return maxPos;
      }
      int cur = fromPos;
      for (int count = 0; count < maxLength && cur < maxPos; count++) {
        int width = Character.charCount(text.codePointAt(cur));
        if (cur + width > maxPos) {
          break;
        }
        cur += width;
      }
      return cur;
    }

    int guardedSearchEnd(String text, int fromPos, int maxPos) {
      return boundedCodePointEnd(text, fromPos, maxPos);
    }

    int guardedSearchEnd(Utf8InputScanner scanner, int fromPos, int maxPos) {
      return boundedCodePointEnd(scanner, fromPos, maxPos);
    }

    boolean endsAtCodePointBoundary(String text, int position) {
      return position <= 0
          || position >= text.length()
          || !Character.isLowSurrogate(text.charAt(position))
          || !Character.isHighSurrogate(text.charAt(position - 1));
    }

    private int boundedCodePointEnd(Utf8InputScanner scanner, int fromPos, int maxPos) {
      if (maxLength == Integer.MAX_VALUE) {
        return maxPos;
      }
      int cur = fromPos;
      for (int count = 0; count < maxLength && cur < maxPos; count++) {
        long decoded = scanner.decodeForward(cur);
        int next = InputScanner.position(decoded);
        if (next > maxPos) {
          break;
        }
        cur = next;
      }
      return cur;
    }

    int scanClassEnd(String text, int fromPos, int maxPos) {
      return GapScanner.scanClassEnd(this, text, fromPos, maxPos);
    }

    int scanClassEnd(Utf8InputScanner scanner, int fromPos, int maxPos) {
      return GapScanner.scanClassEnd(this, scanner, fromPos, maxPos);
    }

    int scanClassStart(String text, int minLimit, int curAnchorStart) {
      return GapScanner.scanClassStart(this, text, minLimit, curAnchorStart);
    }

    int scanClassStart(Utf8InputScanner scanner, int minLimit, int curAnchorStart) {
      return GapScanner.scanClassStart(this, scanner, minLimit, curAnchorStart);
    }

    int matchExecutorFixedForward(String text, int fromPos, int maxPos) {
      return GapScanner.matchExecutorFixedForward(this, text, fromPos, maxPos);
    }

    int matchExecutorFixedForward(Utf8InputScanner scanner, int fromPos, int maxPos) {
      return GapScanner.matchExecutorFixedForward(this, scanner, fromPos, maxPos);
    }

    Gap(GapKind kind, int minLength, int maxLength, AsciiBitmap charClass, boolean isGreedy) {
      this(
          kind,
          minLength,
          maxLength,
          null,
          charClass,
          charClass != null ? charClass.toRanges() : null,
          charClass != null ? CharClassScanInfo.fromAsciiBitmap(charClass) : null,
          isGreedy);
    }

    Gap(
        GapKind kind,
        int minLength,
        int maxLength,
        AsciiBitmap charClass,
        CharClassScanInfo scanInfo,
        boolean isGreedy) {
      this(
          kind,
          minLength,
          maxLength,
          null,
          charClass,
          charClass != null ? charClass.toRanges() : (scanInfo != null ? scanInfo.ranges() : null),
          scanInfo,
          isGreedy);
    }

    Gap(
        GapKind kind,
        int minLength,
        int maxLength,
        int[] discreteOffsets,
        AsciiBitmap charClass,
        int[] charClassRanges,
        CharClassScanInfo scanInfo,
        boolean isGreedy) {
      this(
          kind,
          minLength,
          maxLength,
          discreteOffsets,
          charClass,
          charClassRanges,
          scanInfo,
          isGreedy,
          extractGuardBytes(kind, charClass, scanInfo),
          isPureComplement(
              kind, charClass, scanInfo, extractGuardBytes(kind, charClass, scanInfo)));
    }

    Gap(
        GapKind kind,
        int minLength,
        int maxLength,
        int[] discreteOffsets,
        AsciiBitmap charClass,
        CharClassScanInfo scanInfo,
        boolean isGreedy) {
      this(
          kind,
          minLength,
          maxLength,
          discreteOffsets,
          charClass,
          charClass != null ? charClass.toRanges() : (scanInfo != null ? scanInfo.ranges() : null),
          scanInfo,
          isGreedy);
    }

    Gap(
        GapKind kind,
        int minLength,
        int maxLength,
        int[] discreteOffsets,
        AsciiBitmap charClass,
        int[] charClassRanges,
        CharClassScanInfo scanInfo,
        boolean isGreedy,
        byte[] guardBytes,
        boolean isPureComplement) {
      this(
          kind,
          minLength,
          maxLength,
          discreteOffsets,
          charClass,
          charClassRanges,
          scanInfo,
          isGreedy,
          guardBytes,
          isPureComplement,
          null);
    }

    static Gap compoundSequence(CharClassScanInfo[] seq) {
      return new Gap(
          GapKind.COMPOUND_SEQUENCE,
          seq.length,
          seq.length,
          null,
          null,
          null,
          null,
          true,
          null,
          false,
          seq);
    }

    static byte[] extractGuardBytes(
        GapKind kind, AsciiBitmap charClass, CharClassScanInfo scanInfo) {
      if (kind == GapKind.SINGLE_LINE_ANY_STAR) {
        return new byte[] {'\n', '\r'};
      }
      if (kind != GapKind.BOUNDED_CLASS_REPEAT) {
        return null;
      }
      long b0;
      long b1;
      if (scanInfo != null) {
        b0 = scanInfo.bitmap0();
        b1 = scanInfo.bitmap1();
      } else if (charClass != null) {
        b0 = charClass.bitmap0();
        b1 = charClass.bitmap1();
      } else {
        return null;
      }
      int count = Long.bitCount(b0) + Long.bitCount(b1);
      int missing = 128 - count;
      if (missing >= 1 && missing <= 3) {
        byte[] guards = new byte[missing];
        int idx = 0;
        for (int i = 0; i < 64; i++) {
          if ((b0 & (1L << i)) == 0) {
            guards[idx++] = (byte) i;
          }
        }
        for (int i = 0; i < 64; i++) {
          if ((b1 & (1L << i)) == 0) {
            guards[idx++] = (byte) (i + 64);
          }
        }
        if (missing == 2) {
          if (RarityOracle.exactByteRarity(guards[0] & 0xFF)
              > RarityOracle.exactByteRarity(guards[1] & 0xFF)) {
            byte tmp = guards[0];
            guards[0] = guards[1];
            guards[1] = tmp;
          }
        } else if (missing == 3) {
          if (RarityOracle.exactByteRarity(guards[0] & 0xFF)
              > RarityOracle.exactByteRarity(guards[1] & 0xFF)) {
            byte tmp = guards[0];
            guards[0] = guards[1];
            guards[1] = tmp;
          }
          if (RarityOracle.exactByteRarity(guards[1] & 0xFF)
              > RarityOracle.exactByteRarity(guards[2] & 0xFF)) {
            byte tmp = guards[1];
            guards[1] = guards[2];
            guards[2] = tmp;
          }
          if (RarityOracle.exactByteRarity(guards[0] & 0xFF)
              > RarityOracle.exactByteRarity(guards[1] & 0xFF)) {
            byte tmp = guards[0];
            guards[0] = guards[1];
            guards[1] = tmp;
          }
        }
        return guards;
      }
      return null;
    }

    static boolean isPureComplement(
        GapKind kind, AsciiBitmap charClass, CharClassScanInfo scanInfo, byte[] guardBytes) {
      if (guardBytes == null) {
        return false;
      }
      if (kind == GapKind.SINGLE_LINE_ANY_STAR) {
        return true;
      }
      if (kind != GapKind.BOUNDED_CLASS_REPEAT) {
        return false;
      }
      int[] ranges =
          scanInfo != null ? scanInfo.ranges() : (charClass != null ? charClass.toRanges() : null);
      if (ranges == null || ranges.length == 0) {
        return false;
      }
      int numRanges = ranges.length / 2;
      int lastHi = ranges[ranges.length - 1];
      if (lastHi < 0x10FFFF) {
        return false;
      }
      for (int i = 0; i < numRanges; i++) {
        int lo = ranges[i * 2];
        int hi = ranges[i * 2 + 1];
        if (hi >= 128) {
          if (lo > 128) {
            return false;
          }
          int curHi = hi;
          for (int j = i + 1; j < numRanges; j++) {
            int nextLo = ranges[j * 2];
            int nextHi = ranges[j * 2 + 1];
            if (nextLo > curHi + 1) {
              return false;
            }
            curHi = nextHi;
          }
          return curHi >= 0x10FFFF;
        }
      }
      return false;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof Gap other)) {
        return false;
      }
      return kind == other.kind
          && minLength == other.minLength
          && maxLength == other.maxLength
          && isGreedy == other.isGreedy
          && isPureComplement == other.isPureComplement
          && Arrays.equals(discreteOffsets, other.discreteOffsets)
          && Objects.equals(charClass, other.charClass)
          && Arrays.equals(charClassRanges, other.charClassRanges)
          && Objects.equals(scanInfo, other.scanInfo)
          && Arrays.equals(guardBytes, other.guardBytes);
    }

    @Override
    public int hashCode() {
      int result =
          Objects.hash(kind, minLength, maxLength, charClass, scanInfo, isGreedy, isPureComplement);
      result = 31 * result + Arrays.hashCode(discreteOffsets);
      result = 31 * result + Arrays.hashCode(charClassRanges);
      result = 31 * result + Arrays.hashCode(guardBytes);
      return result;
    }

    boolean matchesSlice(String text, int from, int to) {
      return GapScanner.matchesSlice(this, text, from, to);
    }

    boolean matchesSlice(Utf8InputScanner scanner, int from, int to) {
      return GapScanner.matchesSlice(this, scanner, from, to);
    }

    int expandLeading(String text, int anchorPos, int minPos) {
      return GapScanner.expandLeading(this, text, anchorPos, minPos);
    }

    int expandLeading(Utf8InputScanner scanner, int anchorPos, int minPos) {
      return GapScanner.expandLeading(this, scanner, anchorPos, minPos);
    }

    int expandTrailing(String text, int fromPos, int maxPos) {
      return GapScanner.expandTrailing(this, text, fromPos, maxPos);
    }

    int expandTrailing(Utf8InputScanner scanner, int fromPos, int maxPos) {
      return GapScanner.expandTrailing(this, scanner, fromPos, maxPos);
    }
  }

  sealed interface Anchor permits Anchor.Single, Anchor.Alternation, Anchor.CharClass {
    default int selectivityScore() {
      return RarityOracle.literalSelectivityScore(primaryLiteral());
    }

    static Anchor create(String literal) {
      return Single.create(literal, false);
    }

    static Anchor create(String[] literals, boolean foldCase) {
      return Alternation.create(literals, foldCase);
    }

    int minLength();

    int maxLength();

    boolean foldCase();

    boolean isHardwareAccelerated(InputDomain domain);

    default String literal() {
      return primaryLiteral();
    }

    String primaryLiteral();

    int findNext(String text, int fromIndex);

    int findNext(Utf8InputScanner scanner, int fromIndex);

    default int findNextWithin(String text, int fromIndex, int toIndex) {
      if (fromIndex > toIndex) {
        return -1;
      }
      int idx = findNext(text, fromIndex);
      return idx >= 0 && idx <= toIndex ? idx : -1;
    }

    default int findNextWithin(Utf8InputScanner scanner, int fromIndex, int toIndex) {
      if (fromIndex > toIndex) {
        return -1;
      }
      int idx = findNext(scanner, fromIndex);
      return idx >= 0 && idx <= toIndex ? idx : -1;
    }

    default int lastIndexOf(String text, int fromIndex, int toIndex) {
      int upper = Math.min(toIndex, text.length() - minLength());
      if (fromIndex > upper || fromIndex < 0) {
        return -1;
      }
      for (int i = upper; i >= fromIndex; i--) {
        if (startsWith(text, i)) {
          return i;
        }
      }
      return -1;
    }

    default int lastIndexOf(Utf8InputScanner scanner, int fromIndex, int toIndex) {
      int upper = Math.min(toIndex, scanner.length() - minLength());
      if (fromIndex > upper || fromIndex < 0) {
        return -1;
      }
      for (int i = upper; i >= fromIndex; i--) {
        if (startsWith(scanner, i)) {
          return i;
        }
      }
      return -1;
    }

    boolean startsWith(String text, int pos);

    boolean startsWith(Utf8InputScanner scanner, int pos);

    int lengthAt(String text, int pos);

    int lengthAt(Utf8InputScanner scanner, int pos);

    @SuppressWarnings("ArrayRecordComponent")
    record Single(
        String literal,
        boolean foldCase,
        byte[] literalUtf8,
        int[] failure,
        int[] shifts,
        int anchorOffset,
        char anchorLowChar,
        char anchorHighChar,
        byte anchorLowByte,
        byte anchorHighByte,
        ClassHashChain classHashChain)
        implements Anchor {

      static Single create(String literal) {
        return create(literal, false);
      }

      static Single create(String literal, boolean foldCase) {
        Objects.requireNonNull(literal);
        byte[] utf8 = literal.getBytes(StandardCharsets.UTF_8);
        if (!foldCase) {
          int[] failure = Pattern.literalFailure(utf8);
          int[] shifts = Pattern.literalShifts(utf8);
          return new Single(
              literal, false, utf8, failure, shifts, 0, '\0', '\0', (byte) 0, (byte) 0, null);
        }
        int[] failure = Ascii.ignoreCaseFailure(literal);
        int anchorOffset = RarityOracle.rarestAsciiOffset(literal, literal.length(), true);
        char anchor = literal.charAt(anchorOffset);
        char anchorLow = Ascii.toLowerCase(anchor);
        char anchorHigh = Ascii.toUpperCase(anchor);
        ClassHashChain classHashChain =
            literal.length() >= 4 ? ClassHashChain.compileCaseInsensitive(literal) : null;
        return new Single(
            literal,
            true,
            utf8,
            failure,
            null,
            anchorOffset,
            anchorLow,
            anchorHigh,
            (byte) anchorLow,
            (byte) anchorHigh,
            classHashChain);
      }

      @Override
      public boolean isHardwareAccelerated(InputDomain domain) {
        return true;
      }

      @Override
      public int selectivityScore() {
        return RarityOracle.literalSelectivityScore(literal, foldCase);
      }

      @Override
      public int minLength() {
        return literal.length();
      }

      @Override
      public int maxLength() {
        return literal.length();
      }

      @Override
      public String primaryLiteral() {
        return literal;
      }

      @Override
      public int findNext(String text, int fromIndex) {
        int position = fromIndex;
        while (position <= text.length() - literal.length()) {
          int candidate =
              foldCase
                  ? Matcher.indexOfIgnoreCase(
                      text,
                      literal,
                      anchorOffset,
                      anchorLowChar,
                      anchorHighChar,
                      classHashChain,
                      position)
                  : text.indexOf(literal, position);
          if (candidate < 0 || hasCodePointBoundaries(text, candidate)) {
            return candidate;
          }
          position = candidate + 1;
        }
        return -1;
      }

      @Override
      public int findNext(Utf8InputScanner scanner, int fromIndex) {
        if (foldCase) {
          return scanner.indexOfIgnoreCase(
              literal, failure, anchorOffset, anchorLowByte, anchorHighByte, fromIndex);
        }
        return scanner.indexOf(literalUtf8, failure, shifts, fromIndex);
      }

      @Override
      public int findNextWithin(String text, int fromIndex, int toIndex) {
        if (fromIndex > toIndex || fromIndex + literal.length() > text.length()) {
          return -1;
        }
        int maxStart = Math.min(toIndex, text.length() - literal.length());
        if (fromIndex > maxStart) {
          return -1;
        }
        if (!foldCase) {
          int endBound = Math.min(text.length(), maxStart + literal.length());
          int position = fromIndex;
          while (position <= maxStart) {
            int candidate = text.indexOf(literal, position, endBound);
            if (candidate < 0 || hasCodePointBoundaries(text, candidate)) {
              return candidate;
            }
            position = candidate + 1;
          }
          return -1;
        }
        for (int i = fromIndex; i <= maxStart; i++) {
          if (WorkCounterConfig.ENABLED) {
            WorkCounter.record();
          }
          if (startsWith(text, i)) {
            return i;
          }
        }
        return -1;
      }

      @Override
      public int findNextWithin(Utf8InputScanner scanner, int fromIndex, int toIndex) {
        if (fromIndex > toIndex || fromIndex + literalUtf8.length > scanner.length()) {
          return -1;
        }
        int maxStart = Math.min(toIndex, scanner.length() - literalUtf8.length);
        if (fromIndex > maxStart) {
          return -1;
        }
        return foldCase
            ? scanner.indexOfIgnoreCaseWithin(literal, failure, fromIndex, maxStart)
            : scanner.indexOfWithin(literalUtf8, failure, fromIndex, maxStart);
      }

      @Override
      public int lastIndexOf(String text, int fromIndex, int toIndex) {
        if (fromIndex > toIndex || fromIndex + literal.length() > text.length()) {
          return -1;
        }
        int maxStart = Math.min(toIndex, text.length() - literal.length());
        if (fromIndex > maxStart) {
          return -1;
        }
        if (foldCase) {
          for (int i = maxStart; i >= fromIndex; i--) {
            if (WorkCounterConfig.ENABLED) {
              WorkCounter.record();
            }
            if (startsWith(text, i)) {
              return i;
            }
          }
          return -1;
        }
        int endBound = Math.min(text.length(), maxStart + literal.length());
        int first = text.indexOf(literal, fromIndex, endBound);
        if (first < 0) {
          if (WorkCounterConfig.ENABLED) {
            WorkCounter.record(Math.max(1, maxStart - fromIndex + 1));
          }
          return -1;
        }
        int last = -1;
        for (int candidate = first;
            candidate >= 0;
            candidate = text.indexOf(literal, candidate + 1, endBound)) {
          if (hasCodePointBoundaries(text, candidate)) {
            last = candidate;
          }
        }
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(Math.max(1, maxStart - last + 1));
        }
        return last;
      }

      @Override
      public int lastIndexOf(Utf8InputScanner scanner, int fromIndex, int toIndex) {
        if (fromIndex > toIndex || fromIndex + literalUtf8.length > scanner.length()) {
          return -1;
        }
        int maxStart = Math.min(toIndex, scanner.length() - literalUtf8.length);
        if (fromIndex > maxStart) {
          return -1;
        }
        if (foldCase) {
          for (int i = maxStart; i >= fromIndex; i--) {
            if (startsWith(scanner, i)) {
              return i;
            }
          }
          return -1;
        }
        int firstByte = literalUtf8[0] & 0xFF;
        int p = maxStart;
        while (p >= fromIndex) {
          int nextP = scanner.lastIndexOfAscii(firstByte, p, fromIndex);
          if (nextP < fromIndex) {
            return -1;
          }
          if (startsWith(scanner, nextP)) {
            return nextP;
          }
          p = nextP - 1;
        }
        return -1;
      }

      @Override
      public boolean startsWith(String text, int pos) {
        if (pos < 0 || pos + literal.length() > text.length()) {
          return false;
        }
        return hasCodePointBoundaries(text, pos)
            && (foldCase
                ? Ascii.regionMatchesIgnoreCase(text, pos, literal, literal.length())
                : text.startsWith(literal, pos));
      }

      private boolean hasCodePointBoundaries(String text, int pos) {
        return isCodePointBoundary(text, pos) && isCodePointBoundary(text, pos + literal.length());
      }

      private static boolean isCodePointBoundary(String text, int pos) {
        return pos <= 0
            || pos >= text.length()
            || !Character.isLowSurrogate(text.charAt(pos))
            || !Character.isHighSurrogate(text.charAt(pos - 1));
      }

      @Override
      public boolean startsWith(Utf8InputScanner scanner, int pos) {
        return scanner.startsWith(literalUtf8, pos, foldCase);
      }

      @Override
      public int lengthAt(String text, int pos) {
        return startsWith(text, pos) ? literal.length() : -1;
      }

      @Override
      public int lengthAt(Utf8InputScanner scanner, int pos) {
        return startsWith(scanner, pos) ? literalUtf8.length : -1;
      }
    }

    @SuppressWarnings("ArrayRecordComponent")
    record Alternation(
        String[] literals,
        byte[][] literalsUtf8,
        boolean foldCase,
        int minLength,
        int maxLength,
        MultiLiteralInfo multiLiteral,
        TeddyModel teddyModel,
        // Per-literal case-insensitive search state, precomputed once here instead of on every
        // findNext() probe: Matcher.indexOfIgnoreCase's 3-arg convenience overload otherwise
        // recomputes the rarest-ASCII-char anchor and rebuilds the ClassHashChain (a ~1KB table
        // plus Unicode fold expansion) from scratch on every call. Null when !foldCase.
        int[] anchorOffsets,
        char[] anchorLows,
        char[] anchorHighs,
        ClassHashChain[] classHashChains)
        implements Anchor {

      static Alternation create(String[] literals, boolean foldCase) {
        Objects.requireNonNull(literals);
        if (literals.length < 2) {
          throw new IllegalArgumentException("Alternation requires at least 2 literals");
        }
        int min = Integer.MAX_VALUE;
        int max = 0;
        byte[][] utf8 = new byte[literals.length][];
        for (int i = 0; i < literals.length; i++) {
          String lit = literals[i];
          utf8[i] = lit.getBytes(StandardCharsets.UTF_8);
          min = Math.min(min, lit.length());
          max = Math.max(max, lit.length());
        }

        MultiLiteralInfo multiLit = !foldCase ? MultiLiteralInfo.create(literals) : null;
        TeddyModel teddy = !foldCase ? TeddyModel.compileForSelectedProvider(literals) : null;

        int[] anchorOffsets = null;
        char[] anchorLows = null;
        char[] anchorHighs = null;
        ClassHashChain[] classHashChains = null;
        if (foldCase) {
          anchorOffsets = new int[literals.length];
          anchorLows = new char[literals.length];
          anchorHighs = new char[literals.length];
          classHashChains = new ClassHashChain[literals.length];
          for (int i = 0; i < literals.length; i++) {
            String lit = literals[i];
            int len = lit.length();
            if (len == 0) {
              continue; // indexOfIgnoreCase short-circuits on empty prefixes; anchor unused.
            }
            int anchorOffset = len == 1 ? 0 : RarityOracle.rarestAsciiOffset(lit, len, true);
            char anchor = lit.charAt(anchorOffset);
            anchorOffsets[i] = anchorOffset;
            anchorLows[i] = Ascii.toLowerCase(anchor);
            anchorHighs[i] = Ascii.toUpperCase(anchor);
            classHashChains[i] = ClassHashChain.compileCaseInsensitive(lit);
          }
        }

        return new Alternation(
            literals.clone(),
            utf8,
            foldCase,
            min,
            max,
            multiLit,
            teddy,
            anchorOffsets,
            anchorLows,
            anchorHighs,
            classHashChains);
      }

      @Override
      public boolean isHardwareAccelerated(InputDomain domain) {
        if (domain == InputDomain.UTF8) {
          return !foldCase && (teddyModel != null || multiLiteral != null);
        }
        return false;
      }

      @Override
      public int selectivityScore() {
        if (teddyModel != null || multiLiteral != null) {
          return 80;
        }
        int minScore = Integer.MAX_VALUE;
        for (String lit : literals) {
          minScore = Math.min(minScore, RarityOracle.literalSelectivityScore(lit, foldCase));
        }
        return minScore == Integer.MAX_VALUE ? 0 : minScore;
      }

      @Override
      public String primaryLiteral() {
        return literals[0];
      }

      private int indexOfLiteral(String text, int i, int fromIndex) {
        return foldCase
            ? Matcher.indexOfIgnoreCase(
                text,
                literals[i],
                anchorOffsets[i],
                anchorLows[i],
                anchorHighs[i],
                classHashChains[i],
                fromIndex)
            : text.indexOf(literals[i], fromIndex);
      }

      @Override
      public int findNext(String text, int fromIndex) {
        if (literals.length == 2) {
          int p0 = indexOfLiteral(text, 0, fromIndex);
          if (p0 == fromIndex) {
            return p0;
          }
          int p1 = indexOfLiteral(text, 1, fromIndex);
          if (p0 < 0) {
            return p1;
          }
          if (p1 < 0) {
            return p0;
          }
          return Math.min(p0, p1);
        }
        int bestPos = Integer.MAX_VALUE;
        for (int i = 0; i < literals.length; i++) {
          int pos = indexOfLiteral(text, i, fromIndex);
          if (pos >= 0 && pos < bestPos) {
            bestPos = pos;
            if (bestPos == fromIndex) {
              return bestPos;
            }
          }
        }
        return bestPos == Integer.MAX_VALUE ? -1 : bestPos;
      }

      @Override
      public int findNext(Utf8InputScanner scanner, int fromIndex) {
        if (!foldCase) {
          if (teddyModel != null && VectorScanProviders.teddyProviderAvailable()) {
            VectorScanProvider provider =
                VectorScanProviders.providerForTeddyLength(scanner.length());
            if (provider != null) {
              int idx =
                  provider.indexOfTeddy(
                      scanner.bytes(), scanner.offset(), scanner.length(), teddyModel, fromIndex);
              if (idx != VectorScanProvider.UNSUPPORTED) {
                return idx;
              }
            }
          }
          if (multiLiteral != null) {
            VectorScanProvider provider =
                VectorScanProviders.providerForMultiLiteralLength(scanner.length());
            if (provider != null) {
              int idx =
                  provider.indexOfMultiLiteral(
                      scanner.bytes(),
                      scanner.offset(),
                      scanner.length(),
                      multiLiteral.literals(),
                      multiLiteral.anchorChars(),
                      multiLiteral.anchorOffsets(),
                      multiLiteral.anchorRanges(),
                      multiLiteral.minLength(),
                      teddyModel,
                      fromIndex);
              if (idx != VectorScanProvider.UNSUPPORTED) {
                return idx;
              }
            }
          }
        }

        int len = scanner.length();
        for (int pos = fromIndex; pos <= len - minLength; pos++) {
          for (int i = 0; i < literalsUtf8.length; i++) {
            if (scanner.startsWith(literalsUtf8[i], pos, foldCase)) {
              return pos;
            }
          }
        }
        return -1;
      }

      @Override
      public boolean startsWith(String text, int pos) {
        if (pos < 0 || pos + minLength > text.length()) {
          return false;
        }
        for (String lit : literals) {
          if (pos + lit.length() <= text.length()) {
            boolean match =
                foldCase
                    ? Ascii.regionMatchesIgnoreCase(text, pos, lit, lit.length())
                    : text.startsWith(lit, pos);
            if (match) {
              return true;
            }
          }
        }
        return false;
      }

      @Override
      public boolean startsWith(Utf8InputScanner scanner, int pos) {
        if (pos < 0 || pos + minLength > scanner.length()) {
          return false;
        }
        for (byte[] litUtf8 : literalsUtf8) {
          if (scanner.startsWith(litUtf8, pos, foldCase)) {
            return true;
          }
        }
        return false;
      }

      @Override
      public int lengthAt(String text, int pos) {
        if (pos < 0 || pos + minLength > text.length()) {
          return -1;
        }
        for (String lit : literals) {
          if (pos + lit.length() <= text.length()) {
            boolean match =
                foldCase
                    ? Ascii.regionMatchesIgnoreCase(text, pos, lit, lit.length())
                    : text.startsWith(lit, pos);
            if (match) {
              return lit.length();
            }
          }
        }
        return -1;
      }

      @Override
      public int lengthAt(Utf8InputScanner scanner, int pos) {
        if (pos < 0 || pos + minLength > scanner.length()) {
          return -1;
        }
        for (byte[] litUtf8 : literalsUtf8) {
          if (scanner.startsWith(litUtf8, pos, foldCase)) {
            return litUtf8.length;
          }
        }
        return -1;
      }
    }

    // Ranges share compiled class metadata; array value equality is not used.
    @SuppressWarnings("ArrayRecordComponent")
    record CharClass(AsciiBitmap bitmap, int[] ranges, CharClassScanInfo scanInfo)
        implements Anchor {

      static CharClass create(CharClassScanInfo scanInfo) {
        if (scanInfo == null) {
          return null;
        }
        AsciiBitmap bitmap =
            scanInfo.isAscii() ? new AsciiBitmap(scanInfo.bitmap0(), scanInfo.bitmap1()) : null;
        int[] ranges = scanInfo.ranges();
        return new CharClass(bitmap, ranges, scanInfo);
      }

      @Override
      public boolean isHardwareAccelerated(InputDomain domain) {
        if (domain == InputDomain.UTF8) {
          return bitmap != null || (scanInfo != null && scanInfo.isAscii());
        }
        return bitmap != null && bitmap.cardinality() <= 64;
      }

      @Override
      public int selectivityScore() {
        if (bitmap != null) {
          return Math.max(1, 128 - bitmap.cardinality());
        }
        if (scanInfo != null && scanInfo.ranges() != null) {
          int count = 0;
          for (int i = 0; i < scanInfo.ranges().length; i += 2) {
            count += (scanInfo.ranges()[i + 1] - scanInfo.ranges()[i] + 1);
          }
          return Math.max(1, 128 - Math.min(120, count / 100));
        }
        return 1;
      }

      @Override
      public int minLength() {
        return 1;
      }

      @Override
      public int maxLength() {
        return 1;
      }

      @Override
      public boolean foldCase() {
        return false;
      }

      @Override
      public String primaryLiteral() {
        return null;
      }

      @Override
      public int findNext(String text, int fromIndex) {
        int len = text.length();
        for (int i = Math.max(0, fromIndex); i < len; ) {
          int cp = text.codePointAt(i);
          if (scanInfo != null) {
            if (scanInfo.contains(cp)) {
              return i;
            }
          } else if (cp < 128 && bitmap != null && bitmap.containsAscii(cp)) {
            return i;
          }
          i += Character.charCount(cp);
        }
        return -1;
      }

      @Override
      public int findNextWithin(String text, int fromIndex, int toIndex) {
        int limit = Math.min(text.length(), toIndex + 1);
        for (int i = Math.max(0, fromIndex); i < limit; ) {
          int cp = text.codePointAt(i);
          if ((scanInfo != null && scanInfo.contains(cp))
              || (scanInfo == null && cp < 128 && bitmap != null && bitmap.containsAscii(cp))) {
            return i;
          }
          i += Character.charCount(cp);
        }
        return -1;
      }

      @Override
      public int findNext(Utf8InputScanner scanner, int fromIndex) {
        if (scanInfo != null) {
          return scanner.indexOfCodePointClass(
              scanInfo.ranges(),
              scanInfo.bitmap0(),
              scanInfo.bitmap1(),
              fromIndex,
              scanner.length());
        }
        int len = scanner.length();
        for (int i = Math.max(0, fromIndex); i < len; i++) {
          int c = scanner.asciiAt(i);
          if (c >= 0 && bitmap != null && bitmap.containsAscii(c)) {
            return i;
          }
        }
        return -1;
      }

      @Override
      public int findNextWithin(Utf8InputScanner scanner, int fromIndex, int toIndex) {
        if (scanInfo != null) {
          return scanner.indexOfCodePointClass(
              scanInfo.ranges(),
              scanInfo.bitmap0(),
              scanInfo.bitmap1(),
              fromIndex,
              Math.min(scanner.length(), toIndex + 1));
        }
        int limit = Math.min(scanner.length(), toIndex + 1);
        for (int i = Math.max(0, fromIndex); i < limit; i++) {
          int c = scanner.asciiAt(i);
          if (c >= 0 && bitmap != null && bitmap.containsAscii(c)) {
            return i;
          }
        }
        return -1;
      }

      @Override
      public boolean startsWith(String text, int pos) {
        if (pos >= 0 && pos < text.length()) {
          int cp = text.codePointAt(pos);
          if (scanInfo != null) {
            return scanInfo.contains(cp);
          }
          return cp < 128 && bitmap != null && bitmap.containsAscii(cp);
        }
        return false;
      }

      @Override
      public boolean startsWith(Utf8InputScanner scanner, int pos) {
        if (pos >= 0 && pos < scanner.length()) {
          long decoded = scanner.decodeForward(pos);
          int cp = InputScanner.codePoint(decoded);
          if (scanInfo != null) {
            return scanInfo.contains(cp);
          }
          return cp < 128 && bitmap != null && bitmap.containsAscii(cp);
        }
        return false;
      }

      @Override
      public int lengthAt(String text, int pos) {
        if (pos >= 0 && pos < text.length()) {
          int cp = text.codePointAt(pos);
          if (scanInfo != null
              ? scanInfo.contains(cp)
              : (cp < 128 && bitmap != null && bitmap.containsAscii(cp))) {
            return Character.charCount(cp);
          }
        }
        return -1;
      }

      @Override
      public int lengthAt(Utf8InputScanner scanner, int pos) {
        if (pos >= 0 && pos < scanner.length()) {
          long decoded = scanner.decodeForward(pos);
          int cp = InputScanner.codePoint(decoded);
          if (scanInfo != null
              ? scanInfo.contains(cp)
              : (cp < 128 && bitmap != null && bitmap.containsAscii(cp))) {
            return InputScanner.position(decoded) - pos;
          }
        }
        return -1;
      }
    }
  }
}
