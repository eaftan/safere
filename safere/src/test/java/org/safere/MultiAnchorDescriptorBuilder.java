// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.safere.MultiAnchorDescriptor.Anchor;
import org.safere.MultiAnchorDescriptor.Chain;
import org.safere.MultiAnchorDescriptor.Gap;
import org.safere.MultiAnchorDescriptor.GapKind;
import org.safere.MultiAnchorDescriptor.RejectPlan;
import org.safere.MultiAnchorDescriptor.Segment;
import org.safere.MultiAnchorDescriptor.StartPlan;

/**
 * Test-scoped fluent builder for constructing expected {@link MultiAnchorDescriptor} instances for
 * AssertJ recursive comparisons.
 */
final class MultiAnchorDescriptorBuilder {

  private final List<Segment> segments = new ArrayList<>();
  private Gap trailingGap = Gap.EMPTY;
  private int[] checkOrder = null;
  private Integer minTotalLength = null;
  private boolean isStartAnchored = false;
  private boolean isEndAnchored = false;
  private StartPlan startPlan = StartPlan.None.INSTANCE;
  private RejectPlan rejectPlan = RejectPlan.None.INSTANCE;

  static MultiAnchorDescriptorBuilder create() {
    return new MultiAnchorDescriptorBuilder();
  }

  MultiAnchorDescriptorBuilder segment(String literal) {
    return segment(Gap.EMPTY, Anchor.create(literal));
  }

  MultiAnchorDescriptorBuilder segment(GapKind gapKind, String literal) {
    Gap gap =
        switch (gapKind) {
          case EMPTY -> Gap.EMPTY;
          case WORD_BOUNDARY -> Gap.WORD_BOUNDARY;
          case NO_WORD_BOUNDARY -> Gap.NO_WORD_BOUNDARY;
          case LINE_START -> Gap.LINE_START;
          case LINE_END -> Gap.LINE_END;
          case ANY_STAR -> Gap.ANY_STAR_GREEDY;
          case SINGLE_LINE_ANY_STAR -> Gap.SINGLE_LINE_ANY_STAR_GREEDY;
          case BOUNDED_CLASS_REPEAT ->
              new Gap(
                  GapKind.BOUNDED_CLASS_REPEAT, 0, Integer.MAX_VALUE, null, null, null, null, true);
        };
    return segment(gap, Anchor.create(literal));
  }

  MultiAnchorDescriptorBuilder segment(Gap gap, String literal) {
    return segment(gap, Anchor.create(literal));
  }

  MultiAnchorDescriptorBuilder segment(Gap gap, Anchor anchor) {
    segments.add(
        new Segment(Objects.requireNonNull(gap, "gap"), Objects.requireNonNull(anchor, "anchor")));
    return this;
  }

  MultiAnchorDescriptorBuilder trailingGap(Gap gap) {
    this.trailingGap = Objects.requireNonNull(gap, "trailingGap");
    return this;
  }

  MultiAnchorDescriptorBuilder trailingGap(GapKind gapKind) {
    this.trailingGap =
        switch (gapKind) {
          case EMPTY -> Gap.EMPTY;
          case WORD_BOUNDARY -> Gap.WORD_BOUNDARY;
          case NO_WORD_BOUNDARY -> Gap.NO_WORD_BOUNDARY;
          case LINE_START -> Gap.LINE_START;
          case LINE_END -> Gap.LINE_END;
          case ANY_STAR -> Gap.ANY_STAR_GREEDY;
          case SINGLE_LINE_ANY_STAR -> Gap.SINGLE_LINE_ANY_STAR_GREEDY;
          case BOUNDED_CLASS_REPEAT ->
              new Gap(
                  GapKind.BOUNDED_CLASS_REPEAT, 0, Integer.MAX_VALUE, null, null, null, null, true);
        };
    return this;
  }

  MultiAnchorDescriptorBuilder checkOrder(int... checkOrder) {
    this.checkOrder = checkOrder;
    return this;
  }

  MultiAnchorDescriptorBuilder minTotalLength(int minTotalLength) {
    this.minTotalLength = minTotalLength;
    return this;
  }

  MultiAnchorDescriptorBuilder isStartAnchored(boolean isStartAnchored) {
    this.isStartAnchored = isStartAnchored;
    return this;
  }

  MultiAnchorDescriptorBuilder isEndAnchored(boolean isEndAnchored) {
    this.isEndAnchored = isEndAnchored;
    return this;
  }

  MultiAnchorDescriptorBuilder startPlan(StartPlan startPlan) {
    this.startPlan = Objects.requireNonNull(startPlan, "startPlan");
    return this;
  }

  MultiAnchorDescriptorBuilder rejectPlan(RejectPlan rejectPlan) {
    this.rejectPlan = Objects.requireNonNull(rejectPlan, "rejectPlan");
    return this;
  }

  MultiAnchorDescriptor build() {
    Segment[] segs = segments.toArray(new Segment[0]);
    int[] order = this.checkOrder != null ? this.checkOrder : defaultOrder(segs.length);
    int minLen =
        this.minTotalLength != null ? this.minTotalLength : computeMinLength(segs, trailingGap);

    return new MultiAnchorDescriptor(
        new Chain(segs, trailingGap, order, minLen, isStartAnchored, isEndAnchored),
        startPlan,
        rejectPlan);
  }

  private static int[] defaultOrder(int n) {
    int[] order = new int[n];
    for (int i = 0; i < n; i++) {
      order[i] = i;
    }
    return order;
  }

  private static int computeMinLength(Segment[] segs, Gap trailingGap) {
    int len = 0;
    for (Segment seg : segs) {
      len += seg.gap().minLength();
      len += seg.anchor().minLength();
    }
    if (trailingGap != null) {
      len += trailingGap.minLength();
    }
    return len;
  }
}
