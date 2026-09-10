// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.safere.MultiAnchorDescriptor.Anchor;
import org.safere.MultiAnchorDescriptor.Gap;
import org.safere.MultiAnchorDescriptor.GapKind;
import org.safere.MultiAnchorDescriptor.RejectPlan;
import org.safere.MultiAnchorDescriptor.StartPlan;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
@Isolated
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
            .segment(Gap.TEXT_START, "foo")
            .segment(GapKind.SINGLE_LINE_ANY_STAR, "bar")
            .trailingGap(Gap.EMPTY)
            .checkOrder(1, 0)
            .isStartAnchored(true)
            .isEndAnchored(true)
            .endAnchorWasDollar(true)
            .startPlan(new StartPlan.Literal("foo", false, null))
            .rejectPlan(
                new RejectPlan.EndAnchoredSuffix(new Pattern.SuffixInfo("bar", true, false, false)))
            .anchoredPrefix("foo")
            .anchoredCharClassPrefix(
                CharClassScanInfo.fromCharClass(new CharClassBuilder().addRune('f').build()))
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

  @Test
  @Tag("work-counter")
  void nestedConcatenationAnalysisScalesLinearly() {
    Regexp smaller = nestedConcatenation(1_000);
    Regexp larger = nestedConcatenation(2_000);
    MultiAnchorCompiler.analyze(smaller);
    MultiAnchorCompiler.analyze(larger);

    long smallerWork = analysisWork(smaller);
    long largerWork = analysisWork(larger);

    assertThat(smallerWork).isPositive();
    assertThat(largerWork)
        .withFailMessage("smallerWork=%s largerWork=%s", smallerWork, largerWork)
        .isLessThan(smallerWork * 3);
  }

  @Test
  @Tag("work-counter")
  void nestedAlternationAnalysisScalesLinearly() {
    Regexp smaller = nestedAlternation(1_000);
    Regexp larger = nestedAlternation(2_000);
    MultiAnchorCompiler.analyze(smaller);
    MultiAnchorCompiler.analyze(larger);

    long smallerWork = analysisWork(smaller);
    long largerWork = analysisWork(larger);

    assertThat(smallerWork).isPositive();
    assertThat(largerWork)
        .withFailMessage("smallerWork=%s largerWork=%s", smallerWork, largerWork)
        .isLessThan(smallerWork * 3);
  }

  @Test
  @Tag("work-counter")
  void nestedRequiredLiteralAnalysisScalesLinearly() {
    Regexp smaller = nestedRequiredLiteral(8_000);
    Regexp larger = nestedRequiredLiteral(16_000);
    MultiAnchorCompiler.analyze(smaller);
    MultiAnchorCompiler.analyze(larger);

    long smallerWork = analysisWork(smaller);
    long largerWork = analysisWork(larger);

    assertThat(smallerWork).isPositive();
    assertThat(largerWork)
        .withFailMessage("smallerWork=%s largerWork=%s", smallerWork, largerWork)
        .isLessThan(smallerWork * 3);
  }

  @Test
  void endRejectPlansRetainUnixLinesMode() {
    MultiAnchorDescriptor.RejectPlan suffix =
        Pattern.compile(".*needle$", Pattern.UNIX_LINES).rejectPlan();
    MultiAnchorDescriptor.RejectPlan characterClass =
        Pattern.compile(".*[0-9]$", Pattern.UNIX_LINES).rejectPlan();

    assertThat(endAnchoredSuffixes(suffix))
        .singleElement()
        .extracting(Pattern.SuffixInfo::unixLines)
        .isEqualTo(true);
    assertThat(endAnchoredCharacterClasses(characterClass))
        .singleElement()
        .extracting(Pattern.EndAnchoredCharClassInfo::unixLines)
        .isEqualTo(true);
  }

  private static List<Pattern.SuffixInfo> endAnchoredSuffixes(
      MultiAnchorDescriptor.RejectPlan plan) {
    return rejectPlans(plan)
        .filter(MultiAnchorDescriptor.RejectPlan.EndAnchoredSuffix.class::isInstance)
        .map(MultiAnchorDescriptor.RejectPlan.EndAnchoredSuffix.class::cast)
        .map(MultiAnchorDescriptor.RejectPlan.EndAnchoredSuffix::suffix)
        .toList();
  }

  private static List<Pattern.EndAnchoredCharClassInfo> endAnchoredCharacterClasses(
      MultiAnchorDescriptor.RejectPlan plan) {
    return rejectPlans(plan)
        .filter(MultiAnchorDescriptor.RejectPlan.EndAnchoredCharClass.class::isInstance)
        .map(MultiAnchorDescriptor.RejectPlan.EndAnchoredCharClass.class::cast)
        .map(MultiAnchorDescriptor.RejectPlan.EndAnchoredCharClass::charClass)
        .toList();
  }

  private static Stream<MultiAnchorDescriptor.RejectPlan> rejectPlans(
      MultiAnchorDescriptor.RejectPlan plan) {
    if (plan instanceof MultiAnchorDescriptor.RejectPlan.Composite composite) {
      return Arrays.stream(composite.plans());
    }
    return Stream.of(plan);
  }

  private static Regexp nestedConcatenation(int depth) {
    Regexp nested = Regexp.literal('z', 0);
    for (int index = 0; index < depth; index++) {
      nested =
          Regexp.concat(
              List.of(Regexp.literal('a', 0), Regexp.capture(nested, 0, index + 1, null)), 0);
    }
    return nested;
  }

  private static Regexp nestedAlternation(int depth) {
    Regexp nested = Regexp.literal(0x100, 0);
    for (int index = 0; index < depth; index++) {
      nested =
          Regexp.alternate(
              List.of(
                  Regexp.literal(0x102 + index * 2, 0), Regexp.capture(nested, 0, index + 1, null)),
              0);
    }
    return nested;
  }

  private static Regexp nestedRequiredLiteral(int size) {
    Regexp nested = Regexp.literalString("q".repeat(size).codePoints().toArray(), 0);
    for (int index = 0; index < size; index++) {
      nested =
          Regexp.concat(
              List.of(
                  Regexp.capture(nested, 0, index + 1, null),
                  Regexp.quest(Regexp.literal('x', 0), 0)),
              0);
    }
    return nested;
  }

  private static long analysisWork(Regexp regexp) {
    return WorkCounter.countForTesting(() -> MultiAnchorCompiler.analyze(regexp));
  }

  @Test
  void driverSelectionSelectsRarestAnchor() {
    Pattern p = Pattern.compile("error:\\[[A-Z]\\] code:500");
    MultiAnchorDescriptor desc = p.multiAnchor();
    assertThat(desc).isNotNull();
    assertThat(desc.checkOrder()).isNotEmpty();
    assertThat(desc.checkOrder()[0]).isNotEqualTo(0); // Rarest anchor is downstream
    // Rarest anchor is selected as driver for reverse candidate evaluation
    assertThat(desc.selectDriver(MultiAnchorDescriptor.InputDomain.STRING, true))
        .isEqualTo(desc.checkOrder()[0]);
    assertThat(desc.selectDriver(MultiAnchorDescriptor.InputDomain.UTF8, true))
        .isEqualTo(desc.checkOrder()[0]);
  }

  @Test
  void factorAlternationsWithSurroundingPrefixCoalescesLiteral() {
    Pattern p =
        Pattern.compile(
            "/(?:api/v1/checkout|api/v1/payment|api/v1/orders)/[0-9a-f]{8} status=[0-9]+");
    assertThat(p.multiAnchor()).isNotNull();
    assertThat(p.multiAnchor().startPlan()).isInstanceOf(StartPlan.Literal.class);
    StartPlan.Literal literal = (StartPlan.Literal) p.multiAnchor().startPlan();
    assertThat(literal.prefix()).isEqualTo("/api/v1/");
  }

  @Test
  void factorAlternationsStandalonePrefixExtractsCommonPrefix() {
    Pattern p =
        Pattern.compile("(?:https://api|https://stage|https://prod)\\.example\\.com/[a-z0-9]+");
    assertThat(p.multiAnchor()).isNotNull();
    assertThat(p.multiAnchor().startPlan()).isInstanceOf(StartPlan.Literal.class);
    StartPlan.Literal literal = (StartPlan.Literal) p.multiAnchor().startPlan();
    assertThat(literal.prefix()).isEqualTo("https://");
  }

  @Test
  void alternationFactoringIsStackSafeForDeepQuantifiers() {
    Regexp nested = Regexp.literal('a', 0);
    for (int index = 0; index < 100_000; index++) {
      RegexpOp op = index % 2 == 0 ? RegexpOp.QUEST : RegexpOp.PLUS;
      nested = Regexp.rawQuantifier(op, nested, 0);
    }

    Regexp input = nested;
    assertThatCode(() -> MultiAnchorCompiler.factorAlternations(input)).doesNotThrowAnyException();
  }

  @Test
  void homogeneousGapExtractionIsStackSafeForDeepQuantifiers() {
    assertThatCode(() -> Pattern.compile(deepHomogeneousGap("[ab]", 5_000)))
        .doesNotThrowAnyException();
    assertThatCode(() -> Pattern.compile(deepHomogeneousGap(".", 5_000)))
        .doesNotThrowAnyException();
  }

  @Test
  void poisonousSingleCharacterPrefixGatedFromLiteralStartPlan() {
    // Single space is poisonous; should not be emitted as StartPlan.Literal
    Pattern spacePattern = Pattern.compile(" [0-9]+");
    assertThat(spacePattern.startPlan())
        .isNotInstanceOf(MultiAnchorDescriptor.StartPlan.Literal.class);

    // Single 'e' is high-frequency / poisonous; should not be emitted as StartPlan.Literal
    Pattern ePattern = Pattern.compile("e[0-9]+");
    assertThat(ePattern.startPlan()).isNotInstanceOf(MultiAnchorDescriptor.StartPlan.Literal.class);

    // Single-character class matching space is unselective / poisonous
    Pattern spaceClassPattern = Pattern.compile("[ ]\\d+");
    assertThat(spaceClassPattern.charClassPrefix().isSelective()).isFalse();
    assertThat(spaceClassPattern.stringStartAccelerator()).isNull();

    // Multi-character prefix containing space is not poisonous (e.g. "  " or "id: ")
    Pattern multiSpace = Pattern.compile("  [0-9]+");
    assertThat(multiSpace.startPlan()).isInstanceOf(MultiAnchorDescriptor.StartPlan.Literal.class);
    assertThat(((MultiAnchorDescriptor.StartPlan.Literal) multiSpace.startPlan()).prefix())
        .isEqualTo("  ");

    Pattern exactUppercase = Pattern.compile("E[0-9]+");
    assertThat(exactUppercase.startPlan())
        .isInstanceOf(MultiAnchorDescriptor.StartPlan.Literal.class);
    assertThat(exactUppercase.stringStartAccelerator()).isNotNull();
    assertThat(exactUppercase.utf8StartAccelerator()).isNotNull();

    Pattern foldedCommonLetter = Pattern.compile("(?i:E)[0-9]+");
    assertThat(foldedCommonLetter.startPlan())
        .isInstanceOf(MultiAnchorDescriptor.StartPlan.None.class);
    assertThat(foldedCommonLetter.stringStartAccelerator()).isNull();
    assertThat(foldedCommonLetter.utf8StartAccelerator()).isNull();

    Pattern foldedRareLetter = Pattern.compile("(?i:Q)[0-9]+");
    assertThat(foldedRareLetter.startPlan())
        .isInstanceOf(MultiAnchorDescriptor.StartPlan.Literal.class);
    assertThat(foldedRareLetter.stringStartAccelerator()).isNotNull();
    assertThat(foldedRareLetter.utf8StartAccelerator()).isNotNull();

    Pattern latin1Singleton = Pattern.compile("é+(?:ab|cd)?");
    assertThat(latin1Singleton.startPlan())
        .isInstanceOf(MultiAnchorDescriptor.StartPlan.CharClass.class);
    assertThat(latin1Singleton.stringStartAccelerator()).isNotNull();
    assertThat(latin1Singleton.utf8StartAccelerator()).isNotNull();

    Pattern unicodeSingleton = Pattern.compile("Ā+(?:ab|cd)?");
    assertThat(unicodeSingleton.startPlan())
        .isInstanceOf(MultiAnchorDescriptor.StartPlan.CharClass.class);
    assertThat(unicodeSingleton.stringStartAccelerator()).isNotNull();
    assertThat(unicodeSingleton.utf8StartAccelerator()).isNotNull();
  }

  @Test
  void competitiveSelectivityPrefersFixedOffsetOverPoisonousOrWeakPrefix() {
    // Case 1: Poisonous leading space prefix eclipsed by selective fixed-offset literal
    Pattern p1 = Pattern.compile(" [0-9]{2}404_NOT_FOUND");
    assertThat(p1.startPlan()).isInstanceOf(MultiAnchorDescriptor.StartPlan.FixedOffset.class);
    MultiAnchorDescriptor.StartPlan.FixedOffset fo1 =
        (MultiAnchorDescriptor.StartPlan.FixedOffset) p1.startPlan();
    assertThat(fo1.fol().literal()).isEqualTo("404_NOT_FOUND");
    assertThat(fo1.fol().minOffset()).isEqualTo(3);

    // Case 2: Short 2-character weak prefix eclipsed by much more selective fixed-offset literal
    Pattern p2 = Pattern.compile("ab[0-9]{2}ERROR_CRITICAL_PAYLOAD");
    assertThat(p2.startPlan()).isInstanceOf(MultiAnchorDescriptor.StartPlan.FixedOffset.class);
    MultiAnchorDescriptor.StartPlan.FixedOffset fo2 =
        (MultiAnchorDescriptor.StartPlan.FixedOffset) p2.startPlan();
    assertThat(fo2.fol().literal()).isEqualTo("ERROR_CRITICAL_PAYLOAD");

    // Case 3: Long selective prefix retains priority over fixed-offset literal
    Pattern p3 = Pattern.compile("Content-Type:[0-9]{2}json");
    assertThat(p3.startPlan()).isInstanceOf(MultiAnchorDescriptor.StartPlan.Literal.class);
    assertThat(((MultiAnchorDescriptor.StartPlan.Literal) p3.startPlan()).prefix())
        .isEqualTo("Content-Type:");
  }

  @Test
  void fixedOffsetLiteralSubsumedFromRejectPlan() {
    // Single fixed-offset literal drives startPlan; should not be duplicated in rejectPlan
    Pattern p1 = Pattern.compile("[0-9]{2}404_NOT_FOUND");
    assertThat(p1.startPlan()).isInstanceOf(MultiAnchorDescriptor.StartPlan.FixedOffset.class);
    MultiAnchorDescriptor.StartPlan.FixedOffset fo =
        (MultiAnchorDescriptor.StartPlan.FixedOffset) p1.startPlan();
    assertThat(fo.fol().literal()).isEqualTo("404_NOT_FOUND");

    // The required literal "404_NOT_FOUND" is subsumed and excluded from rejectPlan
    Stream<MultiAnchorDescriptor.RejectPlan.RequiredLiteral> reqLits1 =
        rejectPlans(p1.rejectPlan())
            .filter(MultiAnchorDescriptor.RejectPlan.RequiredLiteral.class::isInstance)
            .map(MultiAnchorDescriptor.RejectPlan.RequiredLiteral.class::cast);
    assertThat(reqLits1).noneMatch(l -> l.literal().equals("404_NOT_FOUND"));

    // Multiple required literals: fixed-offset literal is excluded, but downstream required literal
    // is preserved
    Pattern p2 = Pattern.compile("[0-9]{2}404_NOT_FOUND.*EXCEPTION_LOG");
    assertThat(p2.startPlan()).isInstanceOf(MultiAnchorDescriptor.StartPlan.FixedOffset.class);
    Stream<MultiAnchorDescriptor.RejectPlan.RequiredLiteral> reqLits2 =
        rejectPlans(p2.rejectPlan())
            .filter(MultiAnchorDescriptor.RejectPlan.RequiredLiteral.class::isInstance)
            .map(MultiAnchorDescriptor.RejectPlan.RequiredLiteral.class::cast);
    assertThat(reqLits2).anyMatch(l -> l.literal().equals("EXCEPTION_LOG"));

    // Multiple literals in fixed-offset prefix: all prefix literals subsumed, none leaked to
    // rejectPlan
    Pattern p3 = Pattern.compile("[0-9]{2}____[a-z]zq[a-z]");
    assertThat(p3.startPlan()).isInstanceOf(MultiAnchorDescriptor.StartPlan.FixedOffset.class);
    Stream<MultiAnchorDescriptor.RejectPlan.RequiredLiteral> reqLits3 =
        rejectPlans(p3.rejectPlan())
            .filter(MultiAnchorDescriptor.RejectPlan.RequiredLiteral.class::isInstance)
            .map(MultiAnchorDescriptor.RejectPlan.RequiredLiteral.class::cast);
    assertThat(reqLits3).isEmpty();

    Pattern variableWidthBoundary = Pattern.compile("[0-9]{2}____(?:ALPHABET)+");
    Stream<MultiAnchorDescriptor.RejectPlan.RequiredLiteral> boundaryLiterals =
        rejectPlans(variableWidthBoundary.rejectPlan())
            .filter(MultiAnchorDescriptor.RejectPlan.RequiredLiteral.class::isInstance)
            .map(MultiAnchorDescriptor.RejectPlan.RequiredLiteral.class::cast);
    assertThat(boundaryLiterals)
        .extracting(MultiAnchorDescriptor.RejectPlan.RequiredLiteral::literal)
        .contains("ALPHABET");

    Pattern repeatedPrefixToken = Pattern.compile("[0-9]{2}zz.*zzRARE");
    Stream<MultiAnchorDescriptor.RejectPlan.RequiredLiteral> repeatedTokenLiterals =
        rejectPlans(repeatedPrefixToken.rejectPlan())
            .filter(MultiAnchorDescriptor.RejectPlan.RequiredLiteral.class::isInstance)
            .map(MultiAnchorDescriptor.RejectPlan.RequiredLiteral.class::cast);
    assertThat(repeatedTokenLiterals)
        .extracting(MultiAnchorDescriptor.RejectPlan.RequiredLiteral::literal)
        .contains("zzRARE");
  }

  private static String deepHomogeneousGap(String atom, int depth) {
    return "foo" + "(?:".repeat(depth) + atom + (")?" + atom).repeat(depth) + "bar";
  }
}
