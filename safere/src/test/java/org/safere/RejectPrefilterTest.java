// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

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
    assertThat(prefilter.canReject(null, "haystack with needle in it", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "haystack without keyword in it", 0, options)).isTrue();
    assertThat(prefilter.canReject(null, "needle is at start", 7, options)).isTrue();

    // UTF-8 scanner
    Utf8InputScanner matchingScanner = utf8Scanner("haystack with needle in it");
    Utf8InputScanner nonMatchingScanner = utf8Scanner("haystack without keyword in it");
    assertThat(prefilter.canReject(matchingScanner, 0, options)).isFalse();
    assertThat(prefilter.canReject(nonMatchingScanner, 0, options)).isTrue();

    // Disabled option
    EnginePathOptions disabled = EnginePathOptions.builder().literalFastPaths(false).build();
    assertThat(prefilter.canReject(nonMatchingScanner, 0, disabled)).isFalse();
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

    Utf8InputScanner matchingScanner = utf8Scanner("item-42-test");
    Utf8InputScanner nonMatchingScanner = utf8Scanner("item-no-digits");
    assertThat(prefilter.canReject(matchingScanner, 0, options)).isFalse();
    assertThat(prefilter.canReject(nonMatchingScanner, 0, options)).isTrue();

    // Disabled option
    EnginePathOptions disabled = EnginePathOptions.builder().charClassMatchFastPaths(false).build();
    assertThat(prefilter.canReject(nonMatchingScanner, 0, disabled)).isFalse();
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
    assertThat(prefilter.canReject(utf8Scanner("prefix token 123 suffix"), 0, options)).isFalse();

    // Missing token -> rejected
    assertThat(prefilter.canReject(utf8Scanner("prefix missing 123 suffix"), 0, options)).isTrue();

    // Missing digits -> rejected
    assertThat(prefilter.canReject(utf8Scanner("prefix token no-digits suffix"), 0, options))
        .isTrue();
  }

  @Test
  void diagnosticsAccumulateOnRejection() {
    MultiAnchorDescriptor.RejectPlan plan =
        new MultiAnchorDescriptor.RejectPlan.RequiredLiteral("needle");
    RejectPrefilter prefilter = RejectPrefilter.create(plan);

    DiagnosticAccumulator accumulator = new DiagnosticAccumulator();
    Utf8InputScanner scanner = utf8Scanner("no match here");

    boolean rejected =
        prefilter.canRejectWithDiagnostics(scanner, 0, EnginePathOptions.allEnabled(), accumulator);
    assertThat(rejected).isTrue();

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

    EnginePathOptions options = EnginePathOptions.allEnabled();

    // String input
    assertThat(prefilter.canReject(null, "I like banana smoothie", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "I like apple pie", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "I like grape juice", 0, options)).isTrue();

    // UTF-8 input (disjoint literals prefilter is String-only to avoid redundant UTF-8 scans)
    assertThat(prefilter.canReject(utf8Scanner("I like orange juice"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("I like grape juice"), 0, options)).isFalse();

    // Disabled option
    EnginePathOptions disabled = EnginePathOptions.builder().literalFastPaths(false).build();
    assertThat(prefilter.canReject(null, "I like grape juice", 0, disabled)).isFalse();
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
    assertThat(prefilter.canReject(null, "config.json", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "config.json\n", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "config.json\r\n", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "config.json\r", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "config.json\u0085", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "config.json\u2028", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "config.json\u2029", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "config.yaml", 0, options)).isTrue();
    assertThat(prefilter.canReject(null, "config.yaml\n", 0, options)).isTrue();
    assertThat(prefilter.canReject(null, "config.yaml\u0085", 0, options)).isTrue();

    // UTF-8 input
    assertThat(prefilter.canReject(utf8Scanner("config.json"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("config.json\n"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("config.json\r\n"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("config.json\r"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("config.json\u0085"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("config.json\u2028"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("config.json\u2029"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("config.yaml"), 0, options)).isTrue();
    assertThat(prefilter.canReject(utf8Scanner("config.yaml\n"), 0, options)).isTrue();
    assertThat(prefilter.canReject(utf8Scanner("config.yaml\u0085"), 0, options)).isTrue();

    // UNIX_LINES suffix prefilter
    Pattern.SuffixInfo unixInfo = new Pattern.SuffixInfo(".json", true, true);
    RejectPrefilter unixPrefilter =
        RejectPrefilter.create(new MultiAnchorDescriptor.RejectPlan.EndAnchoredSuffix(unixInfo));
    assertThat(unixPrefilter.canReject(null, "config.json\n", 0, options)).isFalse();
    assertThat(unixPrefilter.canReject(null, "config.json\r", 0, options)).isTrue();
    assertThat(unixPrefilter.canReject(null, "config.json\u0085", 0, options)).isTrue();
    assertThat(unixPrefilter.canReject(utf8Scanner("config.json\n"), 0, options)).isFalse();
    assertThat(unixPrefilter.canReject(utf8Scanner("config.json\r"), 0, options)).isTrue();
    assertThat(unixPrefilter.canReject(utf8Scanner("config.json\u0085"), 0, options)).isTrue();

    // Diagnostics
    DiagnosticAccumulator accumulator = new DiagnosticAccumulator();
    Utf8InputScanner scanner = utf8Scanner("config.yaml");
    boolean rejected = prefilter.canRejectWithDiagnostics(scanner, 0, options, accumulator);
    assertThat(rejected).isTrue();

    Pattern pattern = Pattern.compile(".*\\.json$");
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
  void endAnchoredCaseInsensitiveSuffixRejectPrefilterRejectsMismatchedSuffix() {
    Pattern.SuffixInfo info = new Pattern.SuffixInfo(".json", true, false, true);
    MultiAnchorDescriptor.RejectPlan plan =
        new MultiAnchorDescriptor.RejectPlan.EndAnchoredSuffix(info);

    RejectPrefilter prefilter = RejectPrefilter.create(plan);
    assertThat(prefilter).isInstanceOf(RejectPrefilter.EndAnchoredSuffix.class);
    assertThat(prefilter.strategy()).isEqualTo(MatchStrategy.LITERAL);

    EnginePathOptions options = EnginePathOptions.allEnabled();

    // String input
    assertThat(prefilter.canReject(null, "config.json", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "config.JSON", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "config.JsOn\n", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "config.JSON\r\n", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "config.yaml", 0, options)).isTrue();
    assertThat(prefilter.canReject(null, "config.YAML\n", 0, options)).isTrue();

    // UTF-8 input
    assertThat(prefilter.canReject(utf8Scanner("config.json"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("config.JSON"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("config.JsOn\n"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("config.JSON\r\n"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("config.yaml"), 0, options)).isTrue();
    assertThat(prefilter.canReject(utf8Scanner("config.YAML\n"), 0, options)).isTrue();
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
    assertThat(prefilter.canReject(null, "item123", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "item123\n", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "item123\r\n", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "item123\r", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "item123\u0085", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "item123\u2028", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "item123\u2029", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "item123abc", 0, options)).isTrue();
    assertThat(prefilter.canReject(null, "item123abc\u0085", 0, options)).isTrue();

    // UTF-8 input
    assertThat(prefilter.canReject(utf8Scanner("item123"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("item123\n"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("item123\r\n"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("item123\r"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("item123\u0085"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("item123\u2028"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("item123\u2029"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("item123abc"), 0, options)).isTrue();
    assertThat(prefilter.canReject(utf8Scanner("item123abc\u0085"), 0, options)).isTrue();

    // UNIX_LINES char class prefilter
    Pattern.EndAnchoredCharClassInfo unixInfo =
        new Pattern.EndAnchoredCharClassInfo(builder.build(), true, true);
    RejectPrefilter unixPrefilter =
        RejectPrefilter.create(new MultiAnchorDescriptor.RejectPlan.EndAnchoredCharClass(unixInfo));
    assertThat(unixPrefilter.canReject(null, "item123\n", 0, options)).isFalse();
    assertThat(unixPrefilter.canReject(null, "item123\r", 0, options)).isTrue();
    assertThat(unixPrefilter.canReject(null, "item123\u0085", 0, options)).isTrue();
    assertThat(unixPrefilter.canReject(utf8Scanner("item123\n"), 0, options)).isFalse();
    assertThat(unixPrefilter.canReject(utf8Scanner("item123\r"), 0, options)).isTrue();
    assertThat(unixPrefilter.canReject(utf8Scanner("item123\u0085"), 0, options)).isTrue();
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
    assertThat(p.rejectPrefilter().canReject(null, textNoNav, 0, EnginePathOptions.allEnabled()))
        .isTrue();
    assertThat(p.find(Utf8Input.validated(textNoNav.getBytes(UTF_8)))).isFalse();

    // Positive text with <<!nav>> should match and capture properly
    String textWithNav = "{Link: home_page}{Link: section<<!nav>>item}";
    assertThat(p.rejectPrefilter().canReject(null, textWithNav, 0, EnginePathOptions.allEnabled()))
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
    assertThat(p.rejectPrefilter().canReject(null, inputNoEnd, 0, EnginePathOptions.allEnabled()))
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

    // String, via the text argument.
    assertThat(prefilter.canReject(null, hit, 0, options)).isFalse();
    assertThat(prefilter.canReject(null, miss, 0, options)).isTrue();
    // searchFrom is respected: the only '#' is behind it.
    assertThat(prefilter.canReject(null, hit, hit.indexOf('#') + 1, options)).isTrue();

    // String, via the scanner argument.
    assertThat(prefilter.canReject(new StringInputScanner(hit), null, 0, options)).isFalse();
    assertThat(prefilter.canReject(new StringInputScanner(miss), null, 0, options)).isTrue();

    // UTF-8.
    assertThat(prefilter.canReject(utf8Scanner(hit), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner(miss), 0, options)).isTrue();

    // A '#' beyond the BMP-adjacent boundary is still found, and a non-ASCII
    // haystack does not confuse the character search.
    String wide = "\u00e9\u4e2d\ud83d\ude00#";
    assertThat(prefilter.canReject(null, wide, 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner(wide), 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "\u00e9\u4e2d\ud83d\ude00", 0, options)).isTrue();
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
    assertThat(prefilter.canReject(null, "a # b", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "a % b", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "a + b", 0, options)).isTrue();
  }

  @Test
  void nonAsciiCharClassRejectsUtf8OnlyFromStart() {
    CharClassScanInfo scanInfo =
        CharClassScanInfo.fromCharClass(
            new CharClassBuilder().addRune(']').addRune('\uFF3D').build());
    RejectPrefilter prefilter =
        RejectPrefilter.create(new MultiAnchorDescriptor.RejectPlan.RequiredCharClass(scanInfo));
    EnginePathOptions options = EnginePathOptions.allEnabled();

    assertThat(prefilter.canReject(utf8Scanner("no brackets here"), 0, options)).isTrue();
    assertThat(prefilter.canReject(utf8Scanner("has \uFF3D here"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("has ] here"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("\u00e9\u4E00 ascii then ] late"), 0, options))
        .isFalse();
    assertThat(prefilter.canReject(utf8Scanner("\u00e9\u4E00\uFF3B no closing"), 0, options))
        .isTrue();
    // Non-ASCII UTF-8 class scans have no memo and use scalar decoding, so they only run once from
    // index 0.
    assertThat(prefilter.canReject(utf8Scanner("] tail without brackets"), 2, options)).isFalse();
  }

  @Test
  void twoMemberSmallSetRejectsAcrossProbeWindowEdge() {
    CharClassScanInfo scanInfo =
        CharClassScanInfo.fromCharClass(
            new CharClassBuilder().addRune(']').addRune('\uFF3D').build());
    RejectPrefilter prefilter =
        RejectPrefilter.create(new MultiAnchorDescriptor.RejectPlan.RequiredCharClass(scanInfo));
    EnginePathOptions options = EnginePathOptions.allEnabled();

    for (char member : new char[] {']', '\uFF3D'}) {
      for (int searchFrom : new int[] {0, 3}) {
        for (int offset = 14; offset <= 18; offset++) {
          String text = "x".repeat(searchFrom + offset) + member + "x".repeat(20);
          assertThat(prefilter.canReject(new StringInputScanner(text), text, searchFrom, options))
              .as("member %s at %d from %d", member, searchFrom + offset, searchFrom)
              .isFalse();
          assertThat(
                  prefilter.canReject(
                      new StringInputScanner(text), text, searchFrom + offset + 1, options))
              .as("member %s at %d from %d", member, searchFrom + offset, searchFrom + offset + 1)
              .isTrue();
        }
      }
    }

    // One scanner across increasing positions, as a find() loop does: the probe and the memo must
    // agree about the only member, at index 40.
    String text = "x".repeat(40) + "]" + "x".repeat(40);
    StringInputScanner shared = new StringInputScanner(text);
    for (int searchFrom = 1; searchFrom <= 40; searchFrom++) {
      assertThat(prefilter.canReject(shared, text, searchFrom, options))
          .as("from %d", searchFrom)
          .isFalse();
    }
    assertThat(prefilter.canReject(shared, text, 41, options)).isTrue();
  }
}
