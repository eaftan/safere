// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class RejectPrefilterTest {

  @Test
  void nullAndNoneDescriptorsProduceNullPrefilter() {
    assertThat(RejectPrefilter.create((MultiAnchorDescriptor) null)).isNull();
    assertThat(RejectPrefilter.create((MultiAnchorDescriptor.RejectPlan) null)).isNull();
    assertThat(RejectPrefilter.create(MultiAnchorDescriptor.RejectPlan.None.INSTANCE)).isNull();
  }

  @Test
  void literalRejectPrefilterRejectsMissingLiteral() {
    MultiAnchorDescriptor.RejectPlan plan =
        new MultiAnchorDescriptor.RejectPlan.RequiredLiteral("needle");

    RejectPrefilter prefilter = RejectPrefilter.create(plan);
    assertThat(prefilter).isInstanceOf(RejectPrefilter.Literal.class);
    RejectPrefilter.Literal lit = (RejectPrefilter.Literal) prefilter;
    assertThat(lit.strategy()).isEqualTo(MatchStrategy.LITERAL);
    assertThat(lit.literal()).isEqualTo("needle");
    assertThat(lit.utf8()).isEqualTo("needle".getBytes(UTF_8));
    assertThat(lit.failure()).isNotNull();
    assertThat(lit.shifts()).isNotNull();

    EnginePathOptions options = EnginePathOptions.allEnabled();

    // String input
    assertThat(prefilter.canReject("haystack with needle in it", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("haystack without keyword in it", null, 0, options)).isTrue();
    assertThat(prefilter.canReject("needle is at start", null, 7, options)).isTrue();

    // UTF-8 scanner
    Utf8InputScanner matchingScanner = utf8Scanner("haystack with needle in it");
    Utf8InputScanner nonMatchingScanner = utf8Scanner("haystack without keyword in it");
    assertThat(prefilter.canReject(null, matchingScanner, 0, options)).isFalse();
    assertThat(prefilter.canReject(null, nonMatchingScanner, 0, options)).isTrue();
    assertThat(prefilter.canReject(null, utf8Scanner("needle is at start"), 7, options)).isTrue();

    // Disabled option
    EnginePathOptions disabled = EnginePathOptions.builder().literalFastPaths(false).build();
    assertThat(prefilter.canReject("haystack without keyword in it", null, 0, disabled)).isFalse();
    assertThat(prefilter.canReject(null, nonMatchingScanner, 0, disabled)).isFalse();
  }

  @Test
  void charClassRejectPrefilterRejectsMissingClass() {
    // Digit class [0-9]
    int[] ranges = new int[] {'0', '9'};
    long b0 = 0x03FF000000000000L; // digits 0-9
    long b1 = 0L;
    CharClassScanInfo scanInfo = new CharClassScanInfo.AsciiRanges(ranges, b0, b1);

    MultiAnchorDescriptor.RejectPlan plan =
        new MultiAnchorDescriptor.RejectPlan.RequiredCharClass(scanInfo);

    RejectPrefilter prefilter = RejectPrefilter.create(plan);
    assertThat(prefilter).isInstanceOf(RejectPrefilter.CharClass.class);
    RejectPrefilter.CharClass cc = (RejectPrefilter.CharClass) prefilter;
    assertThat(cc.strategy()).isEqualTo(MatchStrategy.CHARACTER_CLASS);
    assertThat(cc.ranges()).isEqualTo(ranges);
    assertThat(cc.bitmap0()).isEqualTo(b0);
    assertThat(cc.bitmap1()).isEqualTo(b1);

    EnginePathOptions options = EnginePathOptions.allEnabled();

    assertThat(prefilter.canReject("item-42-test", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("item-no-digits", null, 0, options)).isTrue();

    Utf8InputScanner matchingScanner = utf8Scanner("item-42-test");
    Utf8InputScanner nonMatchingScanner = utf8Scanner("item-no-digits");
    assertThat(prefilter.canReject(null, matchingScanner, 0, options)).isFalse();
    assertThat(prefilter.canReject(null, nonMatchingScanner, 0, options)).isTrue();

    // Disabled option
    EnginePathOptions disabled = EnginePathOptions.builder().charClassMatchFastPaths(false).build();
    assertThat(prefilter.canReject("item-no-digits", null, 0, disabled)).isFalse();
    assertThat(prefilter.canReject(null, nonMatchingScanner, 0, disabled)).isFalse();
  }

  @Test
  void compositeRejectPrefilterRejectsIfAnyFilterRejects() {
    int[] ranges = new int[] {'0', '9'};
    long b0 = 0x03FF000000000000L;
    long b1 = 0L;
    CharClassScanInfo scanInfo = new CharClassScanInfo.AsciiRanges(ranges, b0, b1);

    MultiAnchorDescriptor.RejectPlan plan =
        new MultiAnchorDescriptor.RejectPlan.Composite(
            new MultiAnchorDescriptor.RejectPlan[] {
              new MultiAnchorDescriptor.RejectPlan.RequiredLiteral("token"),
              new MultiAnchorDescriptor.RejectPlan.RequiredCharClass(scanInfo)
            });
    RejectPrefilter prefilter = RejectPrefilter.create(plan);

    assertThat(prefilter).isInstanceOf(RejectPrefilter.Composite.class);
    RejectPrefilter.Composite composite = (RejectPrefilter.Composite) prefilter;
    assertThat(composite.filters()).hasSize(2);

    EnginePathOptions options = EnginePathOptions.allEnabled();

    // Has both token and digits -> not rejected
    assertThat(prefilter.canReject(null, utf8Scanner("prefix token 123 suffix"), 0, options))
        .isFalse();
    assertThat(
            prefilter.rejectionStrategy(null, utf8Scanner("prefix token 123 suffix"), 0, options))
        .isNull();

    // Missing token -> rejected by LITERAL
    assertThat(prefilter.canReject(null, utf8Scanner("prefix missing 123 suffix"), 0, options))
        .isTrue();
    assertThat(
            prefilter.rejectionStrategy(null, utf8Scanner("prefix missing 123 suffix"), 0, options))
        .isEqualTo(MatchStrategy.LITERAL);

    // Missing digits -> rejected by CHARACTER_CLASS
    assertThat(prefilter.canReject(null, utf8Scanner("prefix token no-digits suffix"), 0, options))
        .isTrue();
    assertThat(
            prefilter.rejectionStrategy(
                null, utf8Scanner("prefix token no-digits suffix"), 0, options))
        .isEqualTo(MatchStrategy.CHARACTER_CLASS);
  }

  @Test
  void rejectionStrategyReportsExactRejectingStrategy() {
    MultiAnchorDescriptor.RejectPlan plan =
        new MultiAnchorDescriptor.RejectPlan.RequiredLiteral("needle");
    RejectPrefilter prefilter = RejectPrefilter.create(plan);

    Utf8InputScanner scanner = utf8Scanner("no match here");
    MatchStrategy strategy =
        prefilter.rejectionStrategy(null, scanner, 0, EnginePathOptions.allEnabled());
    assertThat(strategy).isEqualTo(MatchStrategy.LITERAL);

    DiagnosticAccumulator accumulator = new DiagnosticAccumulator();
    accumulator.participate(strategy, StrategyRole.REJECT_PREFILTER);
    accumulator.boundary(strategy);

    Pattern pattern = Pattern.compile("needle");
    OperationDiagnostics event =
        accumulator.toEvent(
            pattern.descriptor(),
            MatchOperation.FIND,
            MatchOutcome.NO_MATCH,
            CaptureMode.NONE,
            scanner.length());
    assertThat(event.auxiliaryStrategies())
        .contains(new StrategyParticipation(MatchStrategy.LITERAL, StrategyRole.REJECT_PREFILTER));
    assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.LITERAL);
  }

  @Test
  void disjointLiteralsRejectPrefilterRejectsWhenAllMissing() {
    String[] literals = new String[] {"apple", "banana", "orange"};
    MultiAnchorDescriptor.RejectPlan plan =
        new MultiAnchorDescriptor.RejectPlan.DisjointLiterals(literals);

    RejectPrefilter prefilter = RejectPrefilter.create(plan);
    assertThat(prefilter).isInstanceOf(RejectPrefilter.DisjointLiterals.class);
    RejectPrefilter.DisjointLiterals d = (RejectPrefilter.DisjointLiterals) prefilter;
    assertThat(d.strategy()).isEqualTo(MatchStrategy.LITERAL);
    assertThat(d.literals()).isEqualTo(literals);
    assertThat(d.utf8Literals()).hasNumberOfRows(3);

    EnginePathOptions options = EnginePathOptions.allEnabled();

    // String input
    assertThat(prefilter.canReject("I like banana smoothie", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("I like apple pie", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("I like grape juice", null, 0, options)).isTrue();
    assertThat(prefilter.canReject("I like grape juice", null, 1, options)).isFalse();

    // UTF-8 input
    assertThat(prefilter.canReject(null, utf8Scanner("I like orange juice"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("I like apple pie"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("I like grape juice"), 0, options)).isTrue();
    assertThat(prefilter.canReject(null, utf8Scanner("I like grape juice"), 1, options)).isFalse();

    // Disabled option
    EnginePathOptions disabled = EnginePathOptions.builder().literalFastPaths(false).build();
    assertThat(prefilter.canReject("I like grape juice", null, 0, disabled)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("I like grape juice"), 0, disabled)).isFalse();
  }

  @Test
  void disjointLiteralsOmitsUnpairedSurrogateBranchesFromUtf8Table() {
    EnginePathOptions options = EnginePathOptions.allEnabled();

    String[] mixed = new String[] {"a\uD83D", "za\uD83D\uDE00", "banana"};
    RejectPrefilter.DisjointLiterals mixedFilter =
        (RejectPrefilter.DisjointLiterals)
            RejectPrefilter.create(new MultiAnchorDescriptor.RejectPlan.DisjointLiterals(mixed));
    assertThat(mixedFilter.utf8Literals()).hasNumberOfRows(2);
    // String.getBytes(UTF_8) turns lone surrogates into '?', so "a?" must still be rejected.
    assertThat(mixedFilter.canReject(null, utf8Scanner("a?x"), 0, options)).isTrue();
    assertThat(mixedFilter.canReject(null, utf8Scanner("prefix za\uD83D\uDE00"), 0, options))
        .isFalse();
    assertThat(mixedFilter.canReject(null, utf8Scanner("prefix banana"), 0, options)).isFalse();
    assertThat(mixedFilter.canReject("prefix a\uD83D", null, 0, options)).isFalse();

    // Malformed CESU-8 surrogate bytes decode as U+FFFD in Utf8InputScanner, so they cannot match
    // the unpaired-surrogate branch either.
    byte[] cesu8HighSurrogate = new byte[] {'a', (byte) 0xED, (byte) 0xA0, (byte) 0xBD};
    Utf8InputScanner cesu8Scanner = new Utf8InputScanner(cesu8HighSurrogate, 0, 4);
    assertThat(mixedFilter.canReject(null, cesu8Scanner, 0, options)).isTrue();
    Pattern pattern = Pattern.compile("(?:a\uD83D|za\uD83D\uDE00|banana)");
    assertThat(pattern.matcher(Utf8Input.trusted(cesu8HighSurrogate)).matches()).isFalse();
    assertThat(pattern.matcher(Utf8Input.trusted(cesu8HighSurrogate)).find()).isFalse();

    String[] allSurrogates = new String[] {"a\uD83D", "\uDE00b"};
    RejectPrefilter.DisjointLiterals allSurrogateFilter =
        (RejectPrefilter.DisjointLiterals)
            RejectPrefilter.create(
                new MultiAnchorDescriptor.RejectPlan.DisjointLiterals(allSurrogates));
    assertThat(allSurrogateFilter.utf8Literals()).isEmpty();
    assertThat(allSurrogateFilter.canReject(null, utf8Scanner("a? ?b"), 0, options)).isTrue();
    assertThat(allSurrogateFilter.canReject("xxa\uD83Dyy", null, 0, options)).isFalse();
  }

  @Test
  void withoutDisjointLiteralsPreservesRemainingFilters() {
    RejectPrefilter disjoint =
        RejectPrefilter.create(
            new MultiAnchorDescriptor.RejectPlan.DisjointLiterals(new String[] {"foo", "bar"}));
    RejectPrefilter literal =
        RejectPrefilter.create(new MultiAnchorDescriptor.RejectPlan.RequiredLiteral("foo"));
    RejectPrefilter suffix =
        RejectPrefilter.create(
            new MultiAnchorDescriptor.RejectPlan.EndAnchoredSuffix(
                new Pattern.SuffixInfo(".json", true, false)));

    assertThat(RejectPrefilter.withoutDisjointLiterals(null)).isNull();
    assertThat(RejectPrefilter.withoutDisjointLiterals(disjoint)).isNull();
    assertThat(RejectPrefilter.withoutDisjointLiterals(literal)).isSameAs(literal);

    RejectPrefilter compositeWithDisjoint =
        new RejectPrefilter.Composite(new RejectPrefilter[] {disjoint, suffix});
    assertThat(RejectPrefilter.withoutDisjointLiterals(compositeWithDisjoint)).isSameAs(suffix);

    RejectPrefilter compositeWithoutDisjoint =
        new RejectPrefilter.Composite(new RejectPrefilter[] {literal, suffix});
    assertThat(RejectPrefilter.withoutDisjointLiterals(compositeWithoutDisjoint))
        .isSameAs(compositeWithoutDisjoint);
  }

  @Test
  void endAnchoredSuffixRejectPrefilterRejectsMismatchedSuffix() {
    Pattern.SuffixInfo info = new Pattern.SuffixInfo(".json", true, false);
    MultiAnchorDescriptor.RejectPlan plan =
        new MultiAnchorDescriptor.RejectPlan.EndAnchoredSuffix(info);

    RejectPrefilter prefilter = RejectPrefilter.create(plan);
    assertThat(prefilter).isInstanceOf(RejectPrefilter.EndAnchoredSuffix.class);
    assertThat(prefilter.strategy()).isEqualTo(MatchStrategy.LITERAL);

    EnginePathOptions options = EnginePathOptions.allEnabled();

    // String input
    assertThat(prefilter.canReject("config.json", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("config.json\n", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("config.json\r\n", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("config.json\r", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("config.json\u0085", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("config.json\u2028", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("config.json\u2029", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("config.yaml", null, 0, options)).isTrue();
    assertThat(prefilter.canReject("config.yaml\n", null, 0, options)).isTrue();
    assertThat(prefilter.canReject("config.yaml\u0085", null, 0, options)).isTrue();

    // UTF-8 input
    assertThat(prefilter.canReject(null, utf8Scanner("config.json"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("config.json\n"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("config.json\r\n"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("config.json\r"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("config.json\u0085"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("config.json\u2028"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("config.json\u2029"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("config.yaml"), 0, options)).isTrue();
    assertThat(prefilter.canReject(null, utf8Scanner("config.yaml\n"), 0, options)).isTrue();
    assertThat(prefilter.canReject(null, utf8Scanner("config.yaml\u0085"), 0, options)).isTrue();

    // UNIX_LINES suffix prefilter
    Pattern.SuffixInfo unixInfo = new Pattern.SuffixInfo(".json", true, true);
    RejectPrefilter unixPrefilter =
        RejectPrefilter.create(new MultiAnchorDescriptor.RejectPlan.EndAnchoredSuffix(unixInfo));
    assertThat(unixPrefilter.canReject("config.json\n", null, 0, options)).isFalse();
    assertThat(unixPrefilter.canReject("config.json\r", null, 0, options)).isTrue();
    assertThat(unixPrefilter.canReject("config.json\u0085", null, 0, options)).isTrue();
    assertThat(unixPrefilter.canReject(null, utf8Scanner("config.json\n"), 0, options)).isFalse();
    assertThat(unixPrefilter.canReject(null, utf8Scanner("config.json\r"), 0, options)).isTrue();
    assertThat(unixPrefilter.canReject(null, utf8Scanner("config.json\u0085"), 0, options))
        .isTrue();

    // End-anchored rejection strategy
    Utf8InputScanner scanner = utf8Scanner("config.yaml");
    assertThat(prefilter.endAnchoredRejectionStrategy(scanner, 0, options))
        .isEqualTo(MatchStrategy.LITERAL);
  }

  @Test
  void endAnchoredCaseInsensitiveSuffixRejectPrefilterRejectsMismatchedSuffix() {
    Pattern.SuffixInfo info = new Pattern.SuffixInfo(".json", true, false, true);
    MultiAnchorDescriptor.RejectPlan plan =
        new MultiAnchorDescriptor.RejectPlan.EndAnchoredSuffix(info);

    RejectPrefilter prefilter = RejectPrefilter.create(plan);
    assertThat(prefilter).isInstanceOf(RejectPrefilter.EndAnchoredSuffix.class);
    assertThat(prefilter.strategy()).isEqualTo(MatchStrategy.LITERAL);

    EnginePathOptions options = EnginePathOptions.allEnabled();

    // String input
    assertThat(prefilter.canReject("config.json", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("config.JSON", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("config.JsOn\n", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("config.JSON\r\n", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("config.yaml", null, 0, options)).isTrue();
    assertThat(prefilter.canReject("config.YAML\n", null, 0, options)).isTrue();

    // UTF-8 input
    assertThat(prefilter.canReject(null, utf8Scanner("config.json"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("config.JSON"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("config.JsOn\n"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("config.JSON\r\n"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("config.yaml"), 0, options)).isTrue();
    assertThat(prefilter.canReject(null, utf8Scanner("config.YAML\n"), 0, options)).isTrue();
  }

  @Test
  void endAnchoredCharClassRejectPrefilterRejectsMismatchedInputs() {
    AsciiBitmap.Builder builder = new AsciiBitmap.Builder();
    builder.addRange('0', '9');
    Pattern.EndAnchoredCharClassInfo info =
        new Pattern.EndAnchoredCharClassInfo(builder.build(), true, false);
    MultiAnchorDescriptor.RejectPlan plan =
        new MultiAnchorDescriptor.RejectPlan.EndAnchoredCharClass(info);

    RejectPrefilter prefilter = RejectPrefilter.create(plan);
    assertThat(prefilter).isInstanceOf(RejectPrefilter.EndAnchoredCharClass.class);
    assertThat(prefilter.strategy()).isEqualTo(MatchStrategy.CHARACTER_CLASS);

    EnginePathOptions options = EnginePathOptions.allEnabled();

    // String input
    assertThat(prefilter.canReject("item123", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("item123\n", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("item123\r\n", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("item123\r", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("item123\u0085", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("item123\u2028", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("item123\u2029", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("item123abc", null, 0, options)).isTrue();
    assertThat(prefilter.canReject("item123abc\u0085", null, 0, options)).isTrue();

    // UTF-8 input
    assertThat(prefilter.canReject(null, utf8Scanner("item123"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("item123\n"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("item123\r\n"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("item123\r"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("item123\u0085"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("item123\u2028"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("item123\u2029"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("item123abc"), 0, options)).isTrue();
    assertThat(prefilter.canReject(null, utf8Scanner("item123abc\u0085"), 0, options)).isTrue();

    // UNIX_LINES char class prefilter
    Pattern.EndAnchoredCharClassInfo unixInfo =
        new Pattern.EndAnchoredCharClassInfo(builder.build(), true, true);
    RejectPrefilter unixPrefilter =
        RejectPrefilter.create(new MultiAnchorDescriptor.RejectPlan.EndAnchoredCharClass(unixInfo));
    assertThat(unixPrefilter.canReject("item123\n", null, 0, options)).isFalse();
    assertThat(unixPrefilter.canReject("item123\r", null, 0, options)).isTrue();
    assertThat(unixPrefilter.canReject("item123\u0085", null, 0, options)).isTrue();
    assertThat(unixPrefilter.canReject(null, utf8Scanner("item123\n"), 0, options)).isFalse();
    assertThat(unixPrefilter.canReject(null, utf8Scanner("item123\r"), 0, options)).isTrue();
    assertThat(unixPrefilter.canReject(null, utf8Scanner("item123\u0085"), 0, options)).isTrue();
  }

  private static Utf8InputScanner utf8Scanner(String text) {
    byte[] bytes = text.getBytes(UTF_8);
    return new Utf8InputScanner(bytes, 0, bytes.length);
  }

  @Test
  void requiredInfixLiteralRetainedWhenPrefixPresent() {
    Pattern p = Pattern.compile("(\\{Link:[^}]*?)<<!nav>>([^}]*?\\})");
    assertThat(p.prefix()).isEqualTo("{Link:");
    assertThat(p.rejectPlan()).isNotNull();
    assertThat(p.rejectPrefilter()).isNotNull();

    // Negative text with {Link:...} but missing <<!nav>> should reject in Tier 0
    String textNoNav = "{Link: home_page}{Link: about_page}{Link: contact_page}";
    assertThat(p.rejectPrefilter().canReject(textNoNav, null, 0, EnginePathOptions.allEnabled()))
        .isTrue();
    assertThat(p.find(Utf8Input.validated(textNoNav.getBytes(UTF_8)))).isFalse();

    // Positive text with <<!nav>> should match and capture properly
    String textWithNav = "{Link: home_page}{Link: section<<!nav>>item}";
    assertThat(p.rejectPrefilter().canReject(textWithNav, null, 0, EnginePathOptions.allEnabled()))
        .isFalse();
    Matcher m = p.matcher(textWithNav);
    assertThat(m.find()).isTrue();
    assertThat(m.group(1)).isEqualTo("{Link: section");
    assertThat(m.group(2)).isEqualTo("item}");
  }

  @Test
  void requiredLiteralPrefersDistinctCandidateOverPrefix() {
    Pattern p = Pattern.compile("(?s)<meta_start>.*?<meta_end>");
    assertThat(p.prefix()).isEqualTo("<meta_start>");
    // Even though "<meta_start>" is longer than "<meta_end>", extractRequiredLiteral
    // should skip the prefix and select "<meta_end>".
    assertThat(p.rejectPlan()).isNotNull();
    assertThat(p.rejectPrefilter()).isNotNull();

    String inputNoEnd = "<meta_start>Thinking process: analyzing query...\n".repeat(1_000);
    assertThat(p.rejectPrefilter().canReject(inputNoEnd, null, 0, EnginePathOptions.allEnabled()))
        .isTrue();
    assertThat(p.matcher(inputNoEnd).replaceAll("")).isEqualTo(inputNoEnd);
  }

  @Test
  void identicalPrefixAndRequiredLiteralDeduplicated() {
    Pattern p = Pattern.compile("abc[0-9]+");
    assertThat(p.prefix()).isEqualTo("abc");
    assertThat(p.rejectPlan()).isInstanceOf(MultiAnchorDescriptor.RejectPlan.None.class);
  }

  @Test
  void oneCharacterAsciiClassScansAsACharacterSearch() {
    CharClassScanInfo scanInfo =
        CharClassScanInfo.fromCharClass(new CharClassBuilder().addRune('#').build());
    assertThat(scanInfo).isInstanceOf(CharClassScanInfo.AsciiSmallSet.class);

    RejectPrefilter prefilter =
        RejectPrefilter.create(new MultiAnchorDescriptor.RejectPlan.RequiredCharClass(scanInfo));
    assertThat(prefilter).isInstanceOf(RejectPrefilter.CharClass.class);
    assertThat(((RejectPrefilter.CharClass) prefilter).singleAscii()).isEqualTo('#');

    EnginePathOptions options = EnginePathOptions.allEnabled();
    String hit = "x = 1  # noqa";
    String miss = "x = 1  no comment";

    // String.
    assertThat(prefilter.canReject(hit, null, 0, options)).isFalse();
    assertThat(prefilter.canReject(miss, null, 0, options)).isTrue();
    // searchFrom is respected: the only '#' is behind it.
    assertThat(prefilter.canReject(hit, null, hit.indexOf('#') + 1, options)).isTrue();

    // UTF-8.
    assertThat(prefilter.canReject(null, utf8Scanner(hit), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner(miss), 0, options)).isTrue();
    assertThat(prefilter.canReject(null, utf8Scanner(hit), hit.indexOf('#') + 1, options)).isTrue();

    // A '#' beyond the BMP-adjacent boundary is still found, and a non-ASCII
    // haystack does not confuse the character search.
    String wide = "\u00e9\u4e2d\ud83d\ude00#";
    assertThat(prefilter.canReject(wide, null, 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner(wide), 0, options)).isFalse();
    assertThat(prefilter.canReject("\u00e9\u4e2d\ud83d\ude00", null, 0, options)).isTrue();
    assertThat(prefilter.canReject(null, utf8Scanner("\u00e9\u4e2d\ud83d\ude00"), 0, options))
        .isTrue();
  }

  @Test
  @Tag("work-counter")
  void singleAsciiCharClassUtf8RejectStopsAtEarlyHitWithoutDecodingPrefix() {
    CharClassScanInfo scanInfo =
        CharClassScanInfo.fromCharClass(new CharClassBuilder().addRune('#').build());
    RejectPrefilter prefilter =
        RejectPrefilter.create(new MultiAnchorDescriptor.RejectPlan.RequiredCharClass(scanInfo));
    EnginePathOptions options = EnginePathOptions.allEnabled();

    Utf8InputScanner earlyHit = utf8Scanner("\u4E00#" + "\u4E00".repeat(10_000));
    long work = WorkCounter.countForTesting(() -> prefilter.canReject(null, earlyHit, 0, options));
    assertThat(work).isLessThan(16L);
  }

  @Test
  void multiCharacterAsciiClassKeepsTheClassScan() {
    CharClassScanInfo scanInfo =
        CharClassScanInfo.fromCharClass(new CharClassBuilder().addRune('#').addRune('%').build());
    assertThat(scanInfo).isInstanceOf(CharClassScanInfo.AsciiSmallSet.class);

    RejectPrefilter prefilter =
        RejectPrefilter.create(new MultiAnchorDescriptor.RejectPlan.RequiredCharClass(scanInfo));
    assertThat(((RejectPrefilter.CharClass) prefilter).singleAscii()).isEqualTo(-1);

    EnginePathOptions options = EnginePathOptions.allEnabled();
    assertThat(prefilter.canReject("a # b", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("a % b", null, 0, options)).isFalse();
    assertThat(prefilter.canReject("a + b", null, 0, options)).isTrue();
  }

  @Test
  void nonAsciiCharClassRejectsUtf8OnlyFromStart() {
    CharClassScanInfo scanInfo =
        CharClassScanInfo.fromCharClass(
            new CharClassBuilder().addRune(']').addRune('\uFF3D').build());
    RejectPrefilter prefilter =
        RejectPrefilter.create(new MultiAnchorDescriptor.RejectPlan.RequiredCharClass(scanInfo));
    EnginePathOptions options = EnginePathOptions.allEnabled();

    assertThat(prefilter.canReject(null, utf8Scanner("no brackets here"), 0, options)).isTrue();
    assertThat(prefilter.canReject(null, utf8Scanner("has \uFF3D here"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("has ] here"), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("\u00e9\u4E00 ascii then ] late"), 0, options))
        .isFalse();
    assertThat(prefilter.canReject(null, utf8Scanner("\u00e9\u4E00\uFF3B no closing"), 0, options))
        .isTrue();
    // Each member on either side of the first non-ASCII byte, including inputs long enough to
    // reach the word-at-a-time scans.
    for (int prefix : new int[] {0, 7, 8, 9, 300}) {
      String ascii = "x".repeat(prefix);
      assertThat(prefilter.canReject(null, utf8Scanner(ascii), 0, options)).isTrue();
      assertThat(prefilter.canReject(null, utf8Scanner(ascii + "]"), 0, options)).isFalse();
      assertThat(prefilter.canReject(null, utf8Scanner(ascii + "\u4E00" + ascii), 0, options))
          .isTrue();
      assertThat(prefilter.canReject(null, utf8Scanner(ascii + "]\u4E00"), 0, options)).isFalse();
      assertThat(prefilter.canReject(null, utf8Scanner(ascii + "\u4E00" + ascii + "]"), 0, options))
          .isFalse();
      assertThat(prefilter.canReject(null, utf8Scanner(ascii + "\uFF3D" + ascii), 0, options))
          .isFalse();
      assertThat(
              prefilter.canReject(
                  null, utf8Scanner(ascii + "\u4E00" + ascii + "\uFF3D"), 0, options))
          .isFalse();
    }
    // Non-ASCII UTF-8 class scans have no memo and use scalar decoding, so they only run once from
    // index 0.
    assertThat(prefilter.canReject(null, utf8Scanner("] tail without brackets"), 2, options))
        .isFalse();
  }

  @Test
  @Tag("work-counter")
  void mixedPairUtf8RejectStopsAtEarlyNonAsciiMember() {
    CharClassScanInfo scanInfo =
        CharClassScanInfo.fromCharClass(
            new CharClassBuilder().addRune(']').addRune('\uFF3D').build());
    RejectPrefilter prefilter =
        RejectPrefilter.create(new MultiAnchorDescriptor.RejectPlan.RequiredCharClass(scanInfo));
    EnginePathOptions options = EnginePathOptions.allEnabled();

    // When the non-ASCII member appears near the start of a long non-ASCII input that lacks the
    // ASCII member, the single-pass mixed-pair scan stops at that early member instead of scanning
    // the entire input for the absent ASCII member first.
    Utf8InputScanner earlyFullWidth = utf8Scanner("\u4E00\uFF3D" + "\u4E00".repeat(10_000));
    long work =
        WorkCounter.countForTesting(() -> prefilter.canReject(null, earlyFullWidth, 0, options));
    assertThat(work).isLessThan(64L);
  }

  @Test
  void twoMemberSmallSetRejectsOnlyFromInputStart() {
    CharClassScanInfo scanInfo =
        CharClassScanInfo.fromCharClass(
            new CharClassBuilder().addRune(']').addRune('\uFF3D').build());
    RejectPrefilter prefilter =
        RejectPrefilter.create(new MultiAnchorDescriptor.RejectPlan.RequiredCharClass(scanInfo));
    EnginePathOptions options = EnginePathOptions.allEnabled();

    // From the start of the input, each member is found on either side of the near-window edge
    // and at the end of the input.
    for (char member : new char[] {']', '\uFF3D'}) {
      for (int index : new int[] {0, 14, 78, 79, 80, 81, 200}) {
        String text = "x".repeat(index) + member + "x".repeat(20);
        assertThat(prefilter.canReject(text, null, 0, options))
            .as("member %s at %d", member, index)
            .isFalse();
      }
      String atEnd = "x".repeat(300) + member;
      assertThat(prefilter.canReject(atEnd, null, 0, options))
          .as("member %s at end", member)
          .isFalse();
    }
    for (int length : new int[] {0, 1, 79, 80, 81, 300}) {
      String text = "x".repeat(length);
      assertThat(prefilter.canReject(text, null, 0, options))
          .as("no member, length %d", length)
          .isTrue();
    }

    // Later find() positions are left to the start accelerator and DFA, even when no member
    // remains.
    String text = "x".repeat(40) + "]" + "x".repeat(40);
    for (int searchFrom = 1; searchFrom <= text.length(); searchFrom++) {
      assertThat(prefilter.canReject(text, null, searchFrom, options))
          .as("from %d", searchFrom)
          .isFalse();
    }
  }
}
