// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("tests internal ASCII width proofs")
class MultiAnchorWidthAnalysisTest {
  @Test
  void consumingAtomsWithoutAsciiWidthProofsInvalidateConcatenatedWidths() {
    for (RegexpOp op : List.of(RegexpOp.ANY_CHAR, RegexpOp.ANY_BYTE, RegexpOp.GRAPHEME_CLUSTER)) {
      Regexp atom = new Regexp(op, 0);
      Regexp concat =
          Regexp.concat(List.of(Regexp.literal(' ', 0), atom, Regexp.literal('x', 0)), 0);
      assertThat(MultiAnchorCompiler.analyze(concat))
          .as(op.toString())
          .extracting("width.minWidth", "width.maxWidth")
          .containsExactly(-1, -1);
    }
  }

  @Test
  void knownZeroWidthAssertionsPreserveExactAsciiWidths() {
    for (RegexpOp op :
        List.of(
            RegexpOp.EMPTY_MATCH,
            RegexpOp.BEGIN_TEXT,
            RegexpOp.END_TEXT,
            RegexpOp.BEGIN_LINE,
            RegexpOp.END_LINE,
            RegexpOp.WORD_BOUNDARY,
            RegexpOp.NO_WORD_BOUNDARY)) {
      Regexp concat =
          Regexp.concat(
              List.of(Regexp.literal(' ', 0), new Regexp(op, 0), Regexp.literal('x', 0)), 0);
      assertThat(MultiAnchorCompiler.analyze(concat))
          .as(op.toString())
          .extracting("width.minWidth", "width.maxWidth")
          .containsExactly(2, 2);
    }
  }
}
