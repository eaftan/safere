// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
    MultiAnchorDescriptor descriptor = MultiAnchorCompiler.compile(ast, 0);

    assertThat(descriptor).isNotNull();
    assertThat(descriptor.segments()).isNotEmpty();
    assertThat(descriptor.segments()[0].anchor().literal()).isEqualTo("hello");

    MultiAnchorDescriptor.StartPlan start = MultiAnchorCompiler.extractStartPlan(ast);
    assertThat(start).isInstanceOf(MultiAnchorDescriptor.StartPlan.Literal.class);
    MultiAnchorDescriptor.StartPlan.Literal lit = (MultiAnchorDescriptor.StartPlan.Literal) start;
    assertThat(lit.prefix()).isEqualTo("hello");
    assertThat(lit.foldCase()).isFalse();
  }

  @Test
  void multiAnchorChainExtracted() {
    Regexp ast = Parser.parse("foo.*bar.*baz", Pattern.toParseFlags(0));
    MultiAnchorDescriptor descriptor = MultiAnchorCompiler.compile(ast, 0);

    assertThat(descriptor).isNotNull();
    assertThat(descriptor.segments()).hasSize(3);
    assertThat(descriptor.segments()[0].anchor().literal()).isEqualTo("foo");
    assertThat(descriptor.segments()[1].anchor().literal()).isEqualTo("bar");
    assertThat(descriptor.segments()[2].anchor().literal()).isEqualTo("baz");
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
