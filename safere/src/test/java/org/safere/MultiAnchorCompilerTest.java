// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.safere.MultiAnchorDescriptor.Anchor;
import org.safere.MultiAnchorDescriptor.Gap;
import org.safere.MultiAnchorDescriptor.GapKind;
import org.safere.MultiAnchorDescriptor.RejectPlan;
import org.safere.MultiAnchorDescriptor.StartPlan;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class MultiAnchorCompilerTest {

  @Test
  void nullAstReturnsNull() {
    assertThat(MultiAnchorCompiler.compile(null, 0)).isNull();
    assertThat(MultiAnchorCompiler.extractStartPlan(null))
        .isInstanceOf(MultiAnchorDescriptor.StartPlan.None.class);
    assertThat(MultiAnchorCompiler.extractRejectPlan(null, 0, null, false, null))
        .isInstanceOf(MultiAnchorDescriptor.RejectPlan.None.class);
  }

  @Test
  void simplePrefixCompiledCorrectly() {
    Regexp ast = Parser.parse("hello.*world", Pattern.toParseFlags(0));
    MultiAnchorDescriptor actual = MultiAnchorCompiler.compile(ast, 0);

    MultiAnchorDescriptor expected =
        MultiAnchorDescriptorBuilder.create()
            .segment("hello")
            .segment(GapKind.SINGLE_LINE_ANY_STAR, "world")
            .checkOrder(1, 0)
            .startPlan(new StartPlan.Literal("hello", false, null))
            .rejectPlan(new RejectPlan.RequiredLiteral("world"))
            .build();

    assertThat(actual).usingRecursiveComparison().isEqualTo(expected);
  }

  @Test
  void multiAnchorChainExtracted() {
    Regexp ast = Parser.parse("foo.*bar.*baz", Pattern.toParseFlags(0));
    MultiAnchorDescriptor actual = MultiAnchorCompiler.compile(ast, 0);

    MultiAnchorDescriptor expected =
        MultiAnchorDescriptorBuilder.create()
            .segment("foo")
            .segment(GapKind.SINGLE_LINE_ANY_STAR, "bar")
            .segment(GapKind.SINGLE_LINE_ANY_STAR, "baz")
            .checkOrder(2, 1, 0)
            .startPlan(new StartPlan.Literal("foo", false, null))
            .rejectPlan(new RejectPlan.RequiredLiteral("baz"))
            .build();

    assertThat(actual).usingRecursiveComparison().isEqualTo(expected);
  }

  @Test
  void dotallMultiAnchorChainExtracted() {
    Regexp ast = Parser.parse("(?s)foo.*bar.*baz", Pattern.toParseFlags(0));
    MultiAnchorDescriptor actual = MultiAnchorCompiler.compile(ast, 0);

    MultiAnchorDescriptor expected =
        MultiAnchorDescriptorBuilder.create()
            .segment("foo")
            .segment(GapKind.ANY_STAR, "bar")
            .segment(GapKind.ANY_STAR, "baz")
            .checkOrder(2, 1, 0)
            .startPlan(new StartPlan.Literal("foo", false, null))
            .rejectPlan(new RejectPlan.RequiredLiteral("baz"))
            .build();

    assertThat(actual).usingRecursiveComparison().isEqualTo(expected);
  }

  @Test
  void boundedCharacterClassRepeatGapStructure() {
    Regexp ast = Parser.parse("AAA\\s{1,4}BBB\\d+CCC", Pattern.toParseFlags(0));
    MultiAnchorDescriptor actual = MultiAnchorCompiler.compile(ast, 0);

    assertThat(actual).isNotNull();
    assertThat(actual.segments()).hasSize(3);
    assertThat(actual.segments()[0].gap().kind()).isEqualTo(GapKind.EMPTY);
    assertThat(actual.segments()[0].anchor().literal()).isEqualTo("AAA");

    assertThat(actual.segments()[1].gap().kind()).isEqualTo(GapKind.BOUNDED_CLASS_REPEAT);
    assertThat(actual.segments()[1].gap().minLength()).isEqualTo(1);
    assertThat(actual.segments()[1].gap().maxLength()).isEqualTo(4);
    assertThat(actual.segments()[1].anchor().literal()).isEqualTo("BBB");

    assertThat(actual.segments()[2].gap().kind()).isEqualTo(GapKind.BOUNDED_CLASS_REPEAT);
    assertThat(actual.segments()[2].gap().minLength()).isEqualTo(1);
    assertThat(actual.segments()[2].gap().maxLength()).isEqualTo(Integer.MAX_VALUE);
    assertThat(actual.segments()[2].anchor().literal()).isEqualTo("CCC");
  }

  @Test
  void boundaryAnchoredPatternStructure() {
    Regexp textBoundaryAst = Parser.parse("^foo.*bar$", Pattern.toParseFlags(0));
    MultiAnchorDescriptor actualText = MultiAnchorCompiler.compile(textBoundaryAst, 0);

    MultiAnchorDescriptor expectedText =
        MultiAnchorDescriptorBuilder.create()
            .segment(Gap.EMPTY, "foo")
            .segment(GapKind.SINGLE_LINE_ANY_STAR, "bar")
            .trailingGap(Gap.EMPTY)
            .checkOrder(1, 0)
            .isStartAnchored(true)
            .isEndAnchored(true)
            .startPlan(StartPlan.None.INSTANCE)
            .rejectPlan(
                new RejectPlan.EndAnchoredSuffix(new Pattern.SuffixInfo("bar", true, false, false)))
            .build();

    assertThat(actualText).usingRecursiveComparison().isEqualTo(expectedText);

    Regexp wordBoundaryAst = Parser.parse("\\bfoo.*bar", Pattern.toParseFlags(0));
    MultiAnchorDescriptor actualWord = MultiAnchorCompiler.compile(wordBoundaryAst, 0);

    MultiAnchorDescriptor expectedWord =
        MultiAnchorDescriptorBuilder.create()
            .segment(Gap.WORD_BOUNDARY, "foo")
            .segment(GapKind.SINGLE_LINE_ANY_STAR, "bar")
            .checkOrder(1, 0)
            .startPlan(new StartPlan.Literal("foo", false, null))
            .rejectPlan(new RejectPlan.RequiredLiteral("bar"))
            .build();

    assertThat(actualWord).usingRecursiveComparison().isEqualTo(expectedWord);
  }

  @Test
  void caseFoldedAnchorsStructure() {
    Regexp ast = Parser.parse("(?i)foo.*bar", Pattern.toParseFlags(0));
    MultiAnchorDescriptor actual = MultiAnchorCompiler.compile(ast, 0);

    MultiAnchorDescriptor expected =
        MultiAnchorDescriptorBuilder.create()
            .segment(Gap.EMPTY, Anchor.Single.create("foo", true))
            .segment(Gap.SINGLE_LINE_ANY_STAR_GREEDY, Anchor.Single.create("bar", true))
            .checkOrder(1, 0)
            .startPlan(new StartPlan.Literal("foo", true, null))
            .rejectPlan(new RejectPlan.RequiredLiteral("bar"))
            .build();

    assertThat(actual).usingRecursiveComparison().isEqualTo(expected);
  }

  @Test
  void fixedOffsetLiteralExtracted() {
    Regexp ast = Parser.parse("[0-9]{4}-[0-9]{2}-target", Pattern.toParseFlags(0));
    MultiAnchorDescriptor.StartPlan start = MultiAnchorCompiler.extractStartPlan(ast);

    assertThat(start).isInstanceOf(MultiAnchorDescriptor.StartPlan.FixedOffset.class);
    MultiAnchorDescriptor.StartPlan.FixedOffset fo =
        (MultiAnchorDescriptor.StartPlan.FixedOffset) start;
    assertThat(fo.fol()).isNotNull();
    assertThat(fo.fol().literal()).isEqualTo("-target");
    assertThat(fo.fol().minOffset()).isEqualTo(7);
    assertThat(fo.fol().maxOffset()).isEqualTo(7);
  }

  @Test
  void rejectDescriptorRequiredLiteral() {
    Regexp ast = Parser.parse(".*(important_keyword).*", Pattern.toParseFlags(0));
    MultiAnchorDescriptor.RejectPlan reject =
        MultiAnchorCompiler.extractRejectPlan(ast, 0, null, false, null);

    assertThat(reject).isNotNull();
    MultiAnchorDescriptor.RejectPlan.RequiredLiteral lit = null;
    if (reject instanceof MultiAnchorDescriptor.RejectPlan.RequiredLiteral l) {
      lit = l;
    } else if (reject instanceof MultiAnchorDescriptor.RejectPlan.Composite comp) {
      for (MultiAnchorDescriptor.RejectPlan p : comp.plans()) {
        if (p instanceof MultiAnchorDescriptor.RejectPlan.RequiredLiteral l) {
          lit = l;
          break;
        }
      }
    }
    assertThat(lit).isNotNull();
    assertThat(lit.literal()).isEqualTo("important_keyword");
  }

  @Test
  void rejectDescriptorEndAnchoredSuffix() {
    Regexp ast = Parser.parse(".*\\.json$", Pattern.toParseFlags(0));
    MultiAnchorDescriptor.RejectPlan reject =
        MultiAnchorCompiler.extractRejectPlan(ast, 0, null, false, null);

    assertThat(reject).isNotNull();
    MultiAnchorDescriptor.RejectPlan.EndAnchoredSuffix s = null;
    if (reject instanceof MultiAnchorDescriptor.RejectPlan.EndAnchoredSuffix suf) {
      s = suf;
    } else if (reject instanceof MultiAnchorDescriptor.RejectPlan.Composite comp) {
      for (MultiAnchorDescriptor.RejectPlan p : comp.plans()) {
        if (p instanceof MultiAnchorDescriptor.RejectPlan.EndAnchoredSuffix suf) {
          s = suf;
          break;
        }
      }
    }
    assertThat(s).isNotNull();
    assertThat(s.suffix()).isNotNull();
    assertThat(s.suffix().suffix()).isEqualTo(".json");
  }

  @Test
  void rejectDescriptorRequiredCharClass() {
    Regexp ast = Parser.parse(".*\\d+.*", Pattern.toParseFlags(0));
    MultiAnchorDescriptor.RejectPlan reject =
        MultiAnchorCompiler.extractRejectPlan(ast, 0, null, false, null);

    assertThat(reject).isInstanceOf(MultiAnchorDescriptor.RejectPlan.RequiredCharClass.class);
  }

  @Test
  void rejectDescriptorDisjointRequiredLiterals() {
    Regexp ast = Parser.parse("(apple.*|banana.*|cherry.*)", Pattern.toParseFlags(0));
    MultiAnchorDescriptor.RejectPlan reject =
        MultiAnchorCompiler.extractRejectPlan(ast, 0, null, false, null);

    assertThat(reject).isInstanceOf(MultiAnchorDescriptor.RejectPlan.DisjointLiterals.class);
    MultiAnchorDescriptor.RejectPlan.DisjointLiterals d =
        (MultiAnchorDescriptor.RejectPlan.DisjointLiterals) reject;
    assertThat(d.literals()).containsExactly("apple", "banana", "cherry");
  }
}
