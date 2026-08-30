// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Immutable descriptor capturing pre-computed multi-anchor sequence metadata extracted from a
 * regular expression AST. Enables divide-and-conquer execution by pinning match positions around
 * fast SIMD anchors and verifying intermediate gaps.
 */
@SuppressWarnings("ArrayRecordComponent")
record MultiAnchorDescriptor(
    Chain chain,
    StartPlan startPlan,
    RejectPlan rejectPlan,
    String anchoredPrefix,
    CharClassScanInfo anchoredCharClassPrefix) {

  public static final MultiAnchorDescriptor NONE =
      new MultiAnchorDescriptor(Chain.EMPTY, StartPlan.None.INSTANCE, RejectPlan.None.INSTANCE);

  MultiAnchorDescriptor(Chain chain, StartPlan startPlan, RejectPlan rejectPlan) {
    this(chain, startPlan, rejectPlan, null, null);
  }

  public MultiAnchorDescriptor {
    Objects.requireNonNull(chain, "chain");
    Objects.requireNonNull(startPlan, "startPlan");
    Objects.requireNonNull(rejectPlan, "rejectPlan");
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
      boolean isEndAnchored) {

    public static final Chain EMPTY =
        new Chain(new Segment[0], Gap.EMPTY, new int[0], 0, false, 0, false, false);

    public Chain {
      Objects.requireNonNull(segments, "segments");
      Objects.requireNonNull(trailingGap, "trailingGap");
      Objects.requireNonNull(checkOrder, "checkOrder");
    }

    Chain(Segment[] segments, Gap trailingGap, int minTotalLength, boolean isStartAnchored) {
      this(
          segments,
          trailingGap,
          defaultOrder(segments.length),
          minTotalLength,
          isStartAnchored,
          false);
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
          computeDefaultDriverIndex(segments, checkOrder),
          computeIsUpstreamBounded(segments, computeDefaultDriverIndex(segments, checkOrder)),
          minTotalLength,
          isStartAnchored,
          isEndAnchored);
    }

    public int selectDriver(InputDomain domain, boolean vectorAvailable) {
      if (checkOrder == null
          || checkOrder.length == 0
          || segments == null
          || segments.length == 0) {
        return 0;
      }
      for (int candidate : checkOrder) {
        if (candidate >= 0 && candidate < segments.length) {
          if (candidate > 0 && hasUpstreamGreedyUnboundedGap(segments, candidate)) {
            continue;
          }
          Anchor a = segments[candidate].anchor();
          if (domain == InputDomain.UTF8) {
            if (a.isHardwareAccelerated(InputDomain.UTF8)
                || (vectorAvailable && a.minLength() >= 1)) {
              return candidate;
            }
          } else if (domain == InputDomain.STRING) {
            if (a.isHardwareAccelerated(InputDomain.STRING)) {
              return candidate;
            }
          }
        }
      }
      return 0;
    }

    private static boolean hasUpstreamGreedyUnboundedGap(Segment[] segments, int driverIdx) {
      for (int i = 1; i <= driverIdx; i++) {
        Gap g = segments[i].gap();
        if (g.isGreedy()
            && (g.kind() == GapKind.ANY_STAR || g.kind() == GapKind.SINGLE_LINE_ANY_STAR)) {
          return true;
        }
      }
      return false;
    }

    public boolean isUpstreamBoundedFor(int driverIdx) {
      return computeIsUpstreamBounded(segments, driverIdx);
    }

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
      boolean isEndAnchored) {
    this(
        new Chain(
            segments, trailingGap, checkOrder, minTotalLength, isStartAnchored, isEndAnchored),
        StartPlan.None.INSTANCE,
        RejectPlan.None.INSTANCE);
  }

  MultiAnchorDescriptor(
      Segment[] segments, Gap trailingGap, int minTotalLength, boolean isStartAnchored) {
    this(
        new Chain(
            segments,
            trailingGap,
            defaultOrder(segments.length),
            minTotalLength,
            isStartAnchored,
            false),
        StartPlan.None.INSTANCE,
        RejectPlan.None.INSTANCE);
  }

  private static int[] defaultOrder(int len) {
    int[] order = new int[len];
    for (int i = 0; i < len; i++) {
      order[i] = i;
    }
    return order;
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

  int driverIndex() {
    return chain.driverIndex();
  }

  int selectDriver(InputDomain domain, boolean vectorAvailable) {
    return chain.selectDriver(domain, vectorAvailable);
  }

  boolean isUpstreamBounded() {
    return chain.isUpstreamBounded();
  }

  boolean isUpstreamBoundedFor(int driverIdx) {
    return chain.isUpstreamBoundedFor(driverIdx);
  }

  int minTotalLength() {
    return chain.minTotalLength();
  }

  boolean isStartAnchored() {
    return chain.isStartAnchored();
  }

  boolean isEndAnchored() {
    return chain.isEndAnchored();
  }

  int numSegments() {
    return chain.segments().length;
  }

  Segment firstSegment() {
    return chain.segments()[0];
  }

  Segment trailingSegment() {
    return chain.segments()[chain.segments().length - 1];
  }

  Gap leadingGap() {
    return chain.segments().length > 0 ? chain.segments()[0].gap() : chain.trailingGap();
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

  Gap gapBetween(int leftAnchorIndex, int rightAnchorIndex) {
    if (rightAnchorIndex != leftAnchorIndex + 1) {
      throw new IllegalArgumentException(
          "rightAnchorIndex ("
              + rightAnchorIndex
              + ") must equal leftAnchorIndex + 1 ("
              + (leftAnchorIndex + 1)
              + ")");
    }
    return chain.segments()[rightAnchorIndex].gap();
  }

  Anchor primaryAnchor() {
    return chain.segments()[chain.checkOrder()[0]].anchor();
  }

  boolean isSingle() {
    return chain.segments().length == 1;
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

  enum QuantifierFlavor {
    EXACT,
    GREEDY_UNBOUNDED,
    GREEDY_BOUNDED,
    RELUCTANT_UNBOUNDED,
    RELUCTANT_BOUNDED,
    POSSESSIVE;

    boolean isGreedy() {
      return this == GREEDY_UNBOUNDED || this == GREEDY_BOUNDED;
    }

    boolean isReluctant() {
      return this == RELUCTANT_UNBOUNDED || this == RELUCTANT_BOUNDED;
    }

    boolean isExact() {
      return this == EXACT;
    }
  }

  boolean isExecutableChain() {
    int n = chain.segments().length;
    if (n < 1 || chain.isEndAnchored() || !isExecutableLeadingGap(chain.segments()[0].gap())) {
      return false;
    }
    if (n == 1
        && chain.segments()[0].gap().kind() == GapKind.EMPTY
        && chain.trailingGap().kind() == GapKind.EMPTY) {
      return false;
    }
    for (int i = 0; i < n; i++) {
      Segment segment = chain.segments()[i];
      if (!isExecutableAnchor(segment.anchor())) {
        return false;
      }
      if (i > 0 && !isExecutableInteriorGap(segment.gap())) {
        return false;
      }
    }
    return isExecutableTrailingGap(chain.trailingGap());
  }

  private static boolean isExecutableAnchor(Anchor anchor) {
    return anchor instanceof Anchor.Single || anchor instanceof Anchor.CharClass;
  }

  private static boolean isExecutableLeadingGap(Gap gap) {
    return switch (gap.kind()) {
      case EMPTY, TEXT_START, ANY_STAR, SINGLE_LINE_ANY_STAR -> true;
      case BOUNDED_CLASS_REPEAT -> gap.scanInfo() != null || gap.charClass() != null;
      case TEXT_END, LINE_START, LINE_END, WORD_BOUNDARY, NO_WORD_BOUNDARY -> false;
    };
  }

  private static boolean isExecutableInteriorGap(Gap gap) {
    return switch (gap.kind()) {
      case EMPTY -> true;
      case BOUNDED_CLASS_REPEAT -> gap.scanInfo() != null || gap.charClass() != null;
      case ANY_STAR,
          SINGLE_LINE_ANY_STAR,
          TEXT_START,
          TEXT_END,
          LINE_START,
          LINE_END,
          WORD_BOUNDARY,
          NO_WORD_BOUNDARY ->
          false;
    };
  }

  private static boolean isExecutableTrailingGap(Gap gap) {
    if (gap.minLength() == 0 && !gap.isGreedy()) {
      return true;
    }
    return switch (gap.kind()) {
      case EMPTY, TEXT_END, ANY_STAR, SINGLE_LINE_ANY_STAR -> true;
      case BOUNDED_CLASS_REPEAT -> gap.scanInfo() != null || gap.charClass() != null;
      case TEXT_START, LINE_START, LINE_END, WORD_BOUNDARY, NO_WORD_BOUNDARY -> false;
    };
  }

  boolean isExecutableUtf8Chain() {
    if (!isExecutableChain()) {
      return false;
    }
    for (Segment segment : chain.segments()) {
      switch (segment.anchor()) {
        case Anchor.Single single -> {
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
    BOUNDED_CLASS_REPEAT
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
      boolean isGreedy) {
    static final Gap EMPTY = new Gap(GapKind.EMPTY, 0, 0, null, null, null, null, true);
    static final Gap TEXT_START = new Gap(GapKind.TEXT_START, 0, 0, null, null, null, null, true);
    static final Gap TEXT_END = new Gap(GapKind.TEXT_END, 0, 0, null, null, null, null, true);
    static final Gap WORD_BOUNDARY =
        new Gap(GapKind.WORD_BOUNDARY, 0, 0, null, null, null, null, true);
    static final Gap NO_WORD_BOUNDARY =
        new Gap(GapKind.NO_WORD_BOUNDARY, 0, 0, null, null, null, null, true);
    static final Gap LINE_START = new Gap(GapKind.LINE_START, 0, 0, null, null, null, null, true);
    static final Gap LINE_END = new Gap(GapKind.LINE_END, 0, 0, null, null, null, null, true);
    static final Gap ANY_STAR_GREEDY =
        new Gap(GapKind.ANY_STAR, 0, Integer.MAX_VALUE, null, null, null, null, true);
    static final Gap ANY_STAR_LAZY =
        new Gap(GapKind.ANY_STAR, 0, Integer.MAX_VALUE, null, null, null, null, false);
    static final Gap SINGLE_LINE_ANY_STAR_GREEDY =
        new Gap(GapKind.SINGLE_LINE_ANY_STAR, 0, Integer.MAX_VALUE, null, null, null, null, true);
    static final Gap SINGLE_LINE_ANY_STAR_LAZY =
        new Gap(GapKind.SINGLE_LINE_ANY_STAR, 0, Integer.MAX_VALUE, null, null, null, null, false);

    boolean isFixed() {
      return minLength == maxLength;
    }

    QuantifierFlavor quantifierFlavor() {
      if (isFixed() || equals(EMPTY)) {
        return QuantifierFlavor.EXACT;
      }
      if (isGreedy) {
        return maxLength == Integer.MAX_VALUE
            ? QuantifierFlavor.GREEDY_UNBOUNDED
            : QuantifierFlavor.GREEDY_BOUNDED;
      } else {
        return maxLength == Integer.MAX_VALUE
            ? QuantifierFlavor.RELUCTANT_UNBOUNDED
            : QuantifierFlavor.RELUCTANT_BOUNDED;
      }
    }

    boolean isExecutorFixedGap() {
      return equals(EMPTY)
          || (kind == GapKind.BOUNDED_CLASS_REPEAT && isFixed() && scanInfo != null);
    }

    int scanClassEnd(String text, int fromPos, int maxPos) {
      if (kind == GapKind.BOUNDED_CLASS_REPEAT) {
        int limit = Math.min(maxPos, maxLength == Integer.MAX_VALUE ? maxPos : fromPos + maxLength);
        int cur = fromPos;
        while (cur < limit) {
          int cp = text.codePointAt(cur);
          if (scanInfo != null && !scanInfo.contains(cp)) {
            break;
          }
          cur += Character.charCount(cp);
        }
        return cur;
      }
      if (kind == GapKind.SINGLE_LINE_ANY_STAR) {
        int limit = Math.min(maxPos, maxLength == Integer.MAX_VALUE ? maxPos : fromPos + maxLength);
        int cur = fromPos;
        while (cur < limit) {
          int cp = text.codePointAt(cur);
          if (Nfa.isLineTerminator(cp)) {
            break;
          }
          cur += Character.charCount(cp);
        }
        return cur;
      }
      if (maxLength != Integer.MAX_VALUE) {
        return Math.min(maxPos, fromPos + maxLength);
      }
      return maxPos;
    }

    int scanClassEnd(Utf8InputScanner scanner, int fromPos, int maxPos) {
      if (kind == GapKind.BOUNDED_CLASS_REPEAT) {
        int limit = Math.min(maxPos, maxLength == Integer.MAX_VALUE ? maxPos : fromPos + maxLength);
        int cur = fromPos;
        while (cur < limit) {
          long decoded = scanner.decodeForward(cur);
          int cp = InputScanner.codePoint(decoded);
          int nextPos = InputScanner.position(decoded);
          if (scanInfo != null && !scanInfo.contains(cp)) {
            break;
          }
          cur = nextPos;
        }
        return cur;
      }
      if (kind == GapKind.SINGLE_LINE_ANY_STAR) {
        int limit = Math.min(maxPos, maxLength == Integer.MAX_VALUE ? maxPos : fromPos + maxLength);
        int cur = fromPos;
        while (cur < limit) {
          long decoded = scanner.decodeForward(cur);
          int cp = InputScanner.codePoint(decoded);
          if (Nfa.isLineTerminator(cp)) {
            break;
          }
          cur = InputScanner.position(decoded);
        }
        return cur;
      }
      if (maxLength != Integer.MAX_VALUE) {
        return Math.min(maxPos, fromPos + maxLength);
      }
      return maxPos;
    }

    int matchExecutorFixedForward(String text, int fromPos, int maxPos) {
      if (equals(EMPTY)) {
        return fromPos;
      }
      if (!isExecutorFixedGap()) {
        return -1;
      }
      int cur = fromPos;
      for (int count = 0; count < minLength; count++) {
        if (cur >= maxPos) {
          return -1;
        }
        int cp = text.codePointAt(cur);
        if (scanInfo != null && !scanInfo.contains(cp)) {
          return -1;
        }
        cur += Character.charCount(cp);
      }
      return cur <= maxPos ? cur : -1;
    }

    int matchExecutorFixedForward(Utf8InputScanner scanner, int fromPos, int maxPos) {
      if (equals(EMPTY)) {
        return fromPos;
      }
      if (!isExecutorFixedGap()) {
        return -1;
      }
      int cur = fromPos;
      for (int count = 0; count < minLength; count++) {
        if (cur >= maxPos) {
          return -1;
        }
        long decoded = scanner.decodeForward(cur);
        int cp = InputScanner.codePoint(decoded);
        if (scanInfo != null && !scanInfo.contains(cp)) {
          return -1;
        }
        cur = InputScanner.position(decoded);
      }
      return cur <= maxPos ? cur : -1;
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

    private static boolean isAsciiWord(int ch) {
      return (ch >= 'a' && ch <= 'z')
          || (ch >= 'A' && ch <= 'Z')
          || (ch >= '0' && ch <= '9')
          || ch == '_';
    }

    private static boolean isWordBoundary(String text, int pos) {
      boolean prev = pos > 0 && isAsciiWord(text.charAt(pos - 1));
      boolean next = pos < text.length() && isAsciiWord(text.charAt(pos));
      return prev != next;
    }

    private static boolean isWordBoundary(Utf8InputScanner scanner, int pos) {
      boolean prev = pos > 0 && isAsciiWord(scanner.asciiAt(pos - 1));
      boolean next = pos < scanner.length() && isAsciiWord(scanner.asciiAt(pos));
      return prev != next;
    }

    private static boolean isLineStart(String text, int pos) {
      return pos == 0 || text.charAt(pos - 1) == '\n';
    }

    private static boolean isLineStart(Utf8InputScanner scanner, int pos) {
      return pos == 0 || scanner.asciiAt(pos - 1) == '\n';
    }

    private static boolean isLineEnd(String text, int pos) {
      return pos == text.length()
          || text.charAt(pos) == '\n'
          || (text.charAt(pos) == '\r'
              && (pos + 1 == text.length() || text.charAt(pos + 1) == '\n'));
    }

    private static boolean isLineEnd(Utf8InputScanner scanner, int pos) {
      return pos == scanner.length()
          || scanner.asciiAt(pos) == '\n'
          || (scanner.asciiAt(pos) == '\r'
              && (pos + 1 == scanner.length() || scanner.asciiAt(pos + 1) == '\n'));
    }

    boolean matchesSlice(String text, int from, int to) {
      if (from > to) {
        return false;
      }
      int len = to - from;
      return switch (kind) {
        case EMPTY -> len == 0;
        case TEXT_START -> len == 0 && from == 0;
        case TEXT_END -> len == 0 && from == text.length();
        case WORD_BOUNDARY -> len == 0 && isWordBoundary(text, from);
        case NO_WORD_BOUNDARY -> len == 0 && !isWordBoundary(text, from);
        case LINE_START -> len == 0 && isLineStart(text, from);
        case LINE_END -> len == 0 && isLineEnd(text, from);
        case ANY_STAR -> len >= minLength && len <= maxLength;
        case SINGLE_LINE_ANY_STAR -> {
          int count = 0;
          for (int i = from; i < to; ) {
            int cp = text.codePointAt(i);
            if (Nfa.isLineTerminator(cp)) {
              yield false;
            }
            count++;
            i += Character.charCount(cp);
          }
          yield count >= minLength && count <= maxLength;
        }
        case BOUNDED_CLASS_REPEAT -> {
          int count = 0;
          for (int i = from; i < to; ) {
            int cp = text.codePointAt(i);
            if (scanInfo != null) {
              if (!scanInfo.contains(cp)) {
                yield false;
              }
            } else if (charClass != null) {
              if (!charClass.contains(cp)) {
                yield false;
              }
            }
            count++;
            i += Character.charCount(cp);
          }
          yield count >= minLength && count <= maxLength;
        }
      };
    }

    boolean matchesSlice(Utf8InputScanner scanner, int from, int to) {
      if (from > to) {
        return false;
      }
      int len = to - from;
      return switch (kind) {
        case EMPTY -> len == 0;
        case TEXT_START -> len == 0 && from == 0;
        case TEXT_END -> len == 0 && from == scanner.length();
        case WORD_BOUNDARY -> len == 0 && isWordBoundary(scanner, from);
        case NO_WORD_BOUNDARY -> len == 0 && !isWordBoundary(scanner, from);
        case LINE_START -> len == 0 && isLineStart(scanner, from);
        case LINE_END -> len == 0 && isLineEnd(scanner, from);
        case ANY_STAR -> len >= minLength && len <= maxLength;
        case SINGLE_LINE_ANY_STAR -> {
          int count = 0;
          for (int i = from; i < to; ) {
            long decoded = scanner.decodeForward(i);
            int cp = InputScanner.codePoint(decoded);
            if (Nfa.isLineTerminator(cp)) {
              yield false;
            }
            count++;
            i = InputScanner.position(decoded);
          }
          yield count >= minLength && count <= maxLength;
        }
        case BOUNDED_CLASS_REPEAT -> {
          int count = 0;
          for (int i = from; i < to; ) {
            long decoded = scanner.decodeForward(i);
            int cp = InputScanner.codePoint(decoded);
            if (scanInfo != null) {
              if (!scanInfo.contains(cp)) {
                yield false;
              }
            } else if (charClass != null) {
              if (!charClass.contains(cp)) {
                yield false;
              }
            }
            count++;
            i = InputScanner.position(decoded);
          }
          yield count >= minLength && count <= maxLength;
        }
      };
    }

    int expandLeading(String text, int anchorPos, int minPos) {
      return switch (kind) {
        case EMPTY -> anchorPos;
        case TEXT_START -> anchorPos == 0 ? 0 : -1;
        case TEXT_END -> anchorPos == text.length() ? anchorPos : -1;
        case WORD_BOUNDARY -> isWordBoundary(text, anchorPos) ? anchorPos : -1;
        case NO_WORD_BOUNDARY -> !isWordBoundary(text, anchorPos) ? anchorPos : -1;
        case LINE_START -> isLineStart(text, anchorPos) ? anchorPos : -1;
        case LINE_END -> isLineEnd(text, anchorPos) ? anchorPos : -1;
        case BOUNDED_CLASS_REPEAT -> {
          int count = 0;
          int cur = anchorPos;
          int minMatchPos = -1;
          if (minLength == 0) {
            minMatchPos = cur;
          }
          while (count < maxLength && cur > minPos) {
            int cp = text.codePointBefore(cur);
            int prevPos = cur - Character.charCount(cp);
            if (prevPos < minPos) {
              break;
            }
            if (scanInfo != null) {
              if (!scanInfo.contains(cp)) {
                break;
              }
            } else if (charClass != null) {
              if (!charClass.contains(cp)) {
                break;
              }
            }
            cur = prevPos;
            count++;
            if (count == minLength) {
              minMatchPos = cur;
            }
          }
          if (count < minLength) {
            yield -1;
          }
          yield isGreedy ? cur : minMatchPos;
        }
        case SINGLE_LINE_ANY_STAR -> {
          if (!isGreedy) {
            yield anchorPos - minLength >= minPos ? anchorPos - minLength : -1;
          }
          int cur = anchorPos;
          while (cur > minPos) {
            int cp = text.codePointBefore(cur);
            if (Nfa.isLineTerminator(cp)) {
              break;
            }
            cur -= Character.charCount(cp);
          }
          yield (anchorPos - cur >= minLength) ? cur : -1;
        }
        case ANY_STAR -> {
          if (!isGreedy) {
            yield anchorPos - minLength >= minPos ? anchorPos - minLength : -1;
          }
          yield (anchorPos - minPos >= minLength) ? minPos : -1;
        }
      };
    }

    int expandLeading(Utf8InputScanner scanner, int anchorPos, int minPos) {
      return switch (kind) {
        case EMPTY -> anchorPos;
        case TEXT_START -> anchorPos == 0 ? 0 : -1;
        case TEXT_END -> anchorPos == scanner.length() ? anchorPos : -1;
        case WORD_BOUNDARY -> isWordBoundary(scanner, anchorPos) ? anchorPos : -1;
        case NO_WORD_BOUNDARY -> !isWordBoundary(scanner, anchorPos) ? anchorPos : -1;
        case LINE_START -> isLineStart(scanner, anchorPos) ? anchorPos : -1;
        case LINE_END -> isLineEnd(scanner, anchorPos) ? anchorPos : -1;
        case BOUNDED_CLASS_REPEAT -> {
          int count = 0;
          int cur = anchorPos;
          int minMatchPos = -1;
          if (minLength == 0) {
            minMatchPos = cur;
          }
          while (count < maxLength && cur > minPos) {
            long decoded = scanner.decodeBackward(cur);
            int cp = InputScanner.codePoint(decoded);
            int prevPos = InputScanner.position(decoded);
            if (prevPos < minPos) {
              break;
            }
            if (scanInfo != null) {
              if (!scanInfo.contains(cp)) {
                break;
              }
            } else if (charClass != null) {
              if (!charClass.contains(cp)) {
                break;
              }
            }
            cur = prevPos;
            count++;
            if (count == minLength) {
              minMatchPos = cur;
            }
          }
          if (count < minLength) {
            yield -1;
          }
          yield isGreedy ? cur : minMatchPos;
        }
        case SINGLE_LINE_ANY_STAR -> {
          if (!isGreedy) {
            yield anchorPos - minLength >= minPos ? anchorPos - minLength : -1;
          }
          int cur = anchorPos;
          while (cur > minPos) {
            long decoded = scanner.decodeBackward(cur);
            int cp = InputScanner.codePoint(decoded);
            if (Nfa.isLineTerminator(cp)) {
              break;
            }
            cur = InputScanner.position(decoded);
          }
          yield (anchorPos - cur >= minLength) ? cur : -1;
        }
        case ANY_STAR -> {
          if (!isGreedy) {
            yield anchorPos - minLength >= minPos ? anchorPos - minLength : -1;
          }
          yield (anchorPos - minPos >= minLength) ? minPos : -1;
        }
      };
    }

    int expandTrailing(String text, int fromPos, int maxPos) {
      return switch (kind) {
        case EMPTY -> fromPos;
        case TEXT_START -> fromPos == 0 ? 0 : -1;
        case TEXT_END -> fromPos == text.length() ? fromPos : -1;
        case WORD_BOUNDARY -> isWordBoundary(text, fromPos) ? fromPos : -1;
        case NO_WORD_BOUNDARY -> !isWordBoundary(text, fromPos) ? fromPos : -1;
        case LINE_START -> isLineStart(text, fromPos) ? fromPos : -1;
        case LINE_END -> isLineEnd(text, fromPos) ? fromPos : -1;
        case BOUNDED_CLASS_REPEAT -> {
          int count = 0;
          int cur = fromPos;
          int minMatchPos = -1;
          if (minLength == 0) {
            minMatchPos = cur;
          }
          while (count < maxLength && cur < maxPos) {
            int cp = text.codePointAt(cur);
            if (scanInfo != null) {
              if (!scanInfo.contains(cp)) {
                break;
              }
            } else if (charClass != null) {
              if (!charClass.contains(cp)) {
                break;
              }
            }
            cur += Character.charCount(cp);
            count++;
            if (count == minLength) {
              minMatchPos = cur;
            }
          }
          if (count < minLength) {
            yield -1;
          }
          yield isGreedy ? cur : minMatchPos;
        }
        case SINGLE_LINE_ANY_STAR -> {
          if (!isGreedy) {
            yield fromPos + minLength <= maxPos ? fromPos + minLength : -1;
          }
          int nl = text.indexOf('\n', fromPos);
          int end = (nl >= fromPos && nl <= maxPos) ? nl : maxPos;
          yield (end - fromPos >= minLength) ? end : -1;
        }
        case ANY_STAR -> {
          if (!isGreedy) {
            yield fromPos + minLength <= maxPos ? fromPos + minLength : -1;
          }
          yield (maxPos - fromPos >= minLength) ? maxPos : -1;
        }
      };
    }

    int expandTrailing(Utf8InputScanner scanner, int fromPos, int maxPos) {
      return switch (kind) {
        case EMPTY -> fromPos;
        case TEXT_START -> fromPos == 0 ? 0 : -1;
        case TEXT_END -> fromPos == scanner.length() ? fromPos : -1;
        case WORD_BOUNDARY -> isWordBoundary(scanner, fromPos) ? fromPos : -1;
        case NO_WORD_BOUNDARY -> !isWordBoundary(scanner, fromPos) ? fromPos : -1;
        case LINE_START -> isLineStart(scanner, fromPos) ? fromPos : -1;
        case LINE_END -> isLineEnd(scanner, fromPos) ? fromPos : -1;
        case BOUNDED_CLASS_REPEAT -> {
          int count = 0;
          int cur = fromPos;
          int minMatchPos = -1;
          if (minLength == 0) {
            minMatchPos = cur;
          }
          while (count < maxLength && cur < maxPos) {
            long decoded = scanner.decodeForward(cur);
            int cp = InputScanner.codePoint(decoded);
            int nextPos = InputScanner.position(decoded);
            if (scanInfo != null) {
              if (!scanInfo.contains(cp)) {
                break;
              }
            } else if (charClass != null) {
              if (!charClass.contains(cp)) {
                break;
              }
            }
            cur = nextPos;
            count++;
            if (count == minLength) {
              minMatchPos = cur;
            }
          }
          if (count < minLength) {
            yield -1;
          }
          yield isGreedy ? cur : minMatchPos;
        }
        case SINGLE_LINE_ANY_STAR -> {
          if (!isGreedy) {
            yield fromPos + minLength <= maxPos ? fromPos + minLength : -1;
          }
          int nl = scanner.indexOfAscii('\n', fromPos, maxPos);
          int end = (nl >= fromPos && nl <= maxPos) ? nl : maxPos;
          yield (end - fromPos >= minLength) ? end : -1;
        }
        case ANY_STAR -> {
          if (!isGreedy) {
            yield fromPos + minLength <= maxPos ? fromPos + minLength : -1;
          }
          yield (maxPos - fromPos >= minLength) ? maxPos : -1;
        }
      };
    }
  }

  sealed interface Anchor permits Anchor.Single, Anchor.Alternation, Anchor.CharClass {
    default int selectivityScore() {
      return RarityOracle.literalSelectivityScore(primaryLiteral());
    }

    static Anchor create(String literal) {
      return Single.create(literal, false);
    }

    static Anchor create(String literal, boolean foldCase) {
      return Single.create(literal, foldCase);
    }

    static Anchor create(String[] literals, boolean foldCase) {
      return Alternation.create(literals, foldCase);
    }

    static Anchor create(AsciiBitmap bitmap) {
      return CharClass.create(bitmap);
    }

    int minLength();

    int maxLength();

    boolean foldCase();

    boolean isHardwareAccelerated(InputDomain domain);

    default String literal() {
      return primaryLiteral();
    }

    default CharClassScanInfo scanInfo() {
      return null;
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

    int matchForward(String text, int pos);

    int matchForward(Utf8InputScanner scanner, int pos);

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
        byte anchorHighByte)
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
              literal, false, utf8, failure, shifts, 0, '\0', '\0', (byte) 0, (byte) 0);
        }
        int[] failure = Ascii.ignoreCaseFailure(literal);
        int anchorOffset = RarityOracle.rarestAsciiOffset(literal, literal.length());
        char anchor = literal.charAt(anchorOffset);
        char anchorLow = Ascii.toLowerCase(anchor);
        char anchorHigh = Ascii.toUpperCase(anchor);
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
            (byte) anchorHigh);
      }

      @Override
      public boolean isHardwareAccelerated(InputDomain domain) {
        return true;
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
        if (foldCase) {
          return Matcher.indexOfIgnoreCase(
              text, literal, anchorOffset, anchorLowChar, anchorHighChar, fromIndex);
        }
        return text.indexOf(literal, fromIndex);
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
      public int findNextWithin(Utf8InputScanner scanner, int fromIndex, int toIndex) {
        if (fromIndex > toIndex || fromIndex + literalUtf8.length > scanner.length()) {
          return -1;
        }
        int maxStart = Math.min(toIndex, scanner.length() - literalUtf8.length);
        if (fromIndex > maxStart) {
          return -1;
        }
        if (maxStart - fromIndex <= 64) {
          for (int i = fromIndex; i <= maxStart; i++) {
            if (startsWith(scanner, i)) {
              return i;
            }
          }
          return -1;
        }
        int idx = findNext(scanner, fromIndex);
        return idx >= 0 && idx <= maxStart ? idx : -1;
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
            if (startsWith(text, i)) {
              return i;
            }
          }
          return -1;
        }
        int idx = text.lastIndexOf(literal, maxStart);
        return idx >= fromIndex ? idx : -1;
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
        return foldCase
            ? Ascii.regionMatchesIgnoreCase(text, pos, literal, literal.length())
            : text.startsWith(literal, pos);
      }

      @Override
      public boolean startsWith(Utf8InputScanner scanner, int pos) {
        return scanner.startsWith(literalUtf8, pos, foldCase);
      }

      @Override
      public int matchForward(String text, int pos) {
        return startsWith(text, pos) ? pos + literal.length() : -1;
      }

      @Override
      public int matchForward(Utf8InputScanner scanner, int pos) {
        return startsWith(scanner, pos) ? pos + literalUtf8.length : -1;
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
        TeddyModel teddyModel)
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

        return new Alternation(literals.clone(), utf8, foldCase, min, max, multiLit, teddy);
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
          minScore = Math.min(minScore, RarityOracle.literalSelectivityScore(lit));
        }
        return minScore == Integer.MAX_VALUE ? 0 : minScore;
      }

      @Override
      public String primaryLiteral() {
        return literals[0];
      }

      @Override
      public CharClassScanInfo scanInfo() {
        if (foldCase) {
          return null;
        }
        CharClassBuilder builder = new CharClassBuilder();
        for (String lit : literals) {
          if (!lit.isEmpty()) {
            builder.addRune(lit.codePointAt(0));
          }
        }
        org.safere.CharClass cc = builder.build();
        return cc.isEmpty() ? null : CharClassScanInfo.fromCharClass(cc);
      }

      @Override
      public int findNext(String text, int fromIndex) {
        if (literals.length == 2) {
          String lit0 = literals[0];
          String lit1 = literals[1];
          int p0 =
              foldCase
                  ? Matcher.indexOfIgnoreCase(text, lit0, fromIndex)
                  : text.indexOf(lit0, fromIndex);
          if (p0 == fromIndex) {
            return p0;
          }
          int p1 =
              foldCase
                  ? Matcher.indexOfIgnoreCase(text, lit1, fromIndex)
                  : text.indexOf(lit1, fromIndex);
          if (p0 < 0) {
            return p1;
          }
          if (p1 < 0) {
            return p0;
          }
          return Math.min(p0, p1);
        }
        int bestPos = Integer.MAX_VALUE;
        for (String lit : literals) {
          int pos =
              foldCase
                  ? Matcher.indexOfIgnoreCase(text, lit, fromIndex)
                  : text.indexOf(lit, fromIndex);
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
      public int matchForward(String text, int pos) {
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
              return pos + lit.length();
            }
          }
        }
        return -1;
      }

      @Override
      public int matchForward(Utf8InputScanner scanner, int pos) {
        if (pos < 0 || pos + minLength > scanner.length()) {
          return -1;
        }
        for (byte[] litUtf8 : literalsUtf8) {
          if (scanner.startsWith(litUtf8, pos, foldCase)) {
            return pos + litUtf8.length;
          }
        }
        return -1;
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

    record CharClass(AsciiBitmap bitmap, int[] ranges, CharClassScanInfo scanInfo)
        implements Anchor {
      static CharClass create(AsciiBitmap bitmap) {
        int[] ranges = bitmap.toRanges();
        CharClassScanInfo scanInfo =
            ranges.length <= 8 ? CharClassScanInfo.fromAsciiBitmap(bitmap) : null;
        return new CharClass(bitmap, ranges, scanInfo);
      }

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
      public int matchForward(String text, int pos) {
        if (pos >= 0 && pos < text.length()) {
          int cp = text.codePointAt(pos);
          if (scanInfo != null
              ? scanInfo.contains(cp)
              : (cp < 128 && bitmap != null && bitmap.containsAscii(cp))) {
            return pos + Character.charCount(cp);
          }
        }
        return -1;
      }

      @Override
      public int matchForward(Utf8InputScanner scanner, int pos) {
        if (pos >= 0 && pos < scanner.length()) {
          long decoded = scanner.decodeForward(pos);
          int cp = InputScanner.codePoint(decoded);
          if (scanInfo != null
              ? scanInfo.contains(cp)
              : (cp < 128 && bitmap != null && bitmap.containsAscii(cp))) {
            return InputScanner.position(decoded);
          }
        }
        return -1;
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
