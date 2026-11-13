// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

public final class FindSequenceFuzzer {
  private static final List<String> LINE_TERMINATORS =
      List.of("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029");

  private static final List<String> INTERIOR_CLASS_PREFIXES =
      List.of(" ", "e", "[a-z]{2}", "[ \\t\\r\\n]{3}", " [é😀]", " [aé]{2}", " .", " .{2}", " \\X");

  private static final List<SearchStartContext> SEARCH_START_CONTEXTS =
      List.of(
          new SearchStartContext("\\w", "1", "b", "\\b", List.of("-", "!", "😀")),
          new SearchStartContext("x", "x", "x", "\\B", List.of("b", "c", "α")),
          new SearchStartContext("\\n", "\n", "\n", "(?m:^)", List.of("b", "c", "α")));

  private static final List<FailedSearchContext> FAILED_SEARCH_CONTEXTS =
      List.of(
          new FailedSearchContext("a?b", "b", "a"),
          new FailedSearchContext("(?:a|)b", "b", "a"),
          new FailedSearchContext("[+-]?[0-9]+", "1", "+"),
          new FailedSearchContext("(?:[+-]|)[0-9]+", "1", "+"),
          new FailedSearchContext("[é😀]?b", "b", "😀"),
          new FailedSearchContext("(?:[é😀]|)b", "b", "😀"),
          new FailedSearchContext("(?:ab)?c", "c", "ab"));

  @Test
  void deferredAssertionAlternativesPreserveSearchStartMatches() {
    for (SearchStartContext context : SEARCH_START_CONTEXTS) {
      for (String suffix : context.suffixes()) {
        for (String quantifier : List.of("?", "*", "{0,2}")) {
          for (boolean captures : List.of(false, true)) {
            for (boolean assertionFirst : List.of(false, true)) {
              for (String padding : List.of("", "zzzz")) {
                assertSearchStartAssertionFinds(
                    context, suffix, quantifier, captures, assertionFirst, padding);
              }
            }
          }
        }
      }
    }
  }

  @Test
  void validTerminalEmptyMatchIsNotExcluded() {
    var safeRe = org.safere.Pattern.compile("").matcher("");
    var jdk = java.util.regex.Pattern.compile("").matcher("");
    assertThat(jdk.find()).isTrue();
    var pair = new FuzzSupport.MatcherPair("", 0, "", safeRe, jdk);
    assertThatThrownBy(pair::hasMatch).isInstanceOf(AssertionError.class);
  }

  @Test
  void terminalEmptyFindInvalidatesMatchState() {
    for (String regex : List.of("x*", "(x?)", "", "$", "\\b")) {
      for (String input : List.of("", "x", "yxxy")) {
        for (boolean useRegion : List.of(false, true)) {
          FuzzSupport.MatcherPair matcher = FuzzSupport.compileOrSkip(regex, 0).matcher(input);
          if (useRegion) {
            matcher.region(0, input.length() / 2);
          }
          while (matcher.find()) {
            assertThat(matcher.hasMatch()).isTrue();
          }
          assertThat(matcher.hasMatch()).isFalse();
        }
      }
    }
  }

  @Test
  void deferredAssertionsPreserveFullMatchAlternatives() {
    for (String assertion : List.of("\\b", "\\B", "(?m:$)")) {
      for (String atom : List.of("a", "!", "\n", "α", "😀")) {
        String literal = java.util.regex.Pattern.quote(atom);
        String regex = "(?U)" + literal + "*(?:" + literal + literal + "|" + assertion + ")";
        FuzzSupport.compileOrSkip(regex, 0).matcher(atom.repeat(2)).matches();
      }
    }
  }

  @Test
  void reusedMultilineDollarKeepsCrLfAtomic() {
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip("\\W*(?m:$)", 0);
    pattern.matcher(" \na").find();
    FuzzSupport.MatcherPair matcher = pattern.matcher("xb\r\n" + "a".repeat(30));
    while (matcher.find()) {
      matcher.start();
      matcher.end();
    }
  }

  @Test
  void anchoredFailureAfterSuccessfulMatchRegression() {
    assertAnchoredContinuation("a", "a a", false, false);
    assertAnchoredContinuation("a*", "a!", true, false);
    assertAnchoredContinuation("a", "ba a", false, true);
    assertAnchoredContinuation("a*", "!a", false, false);
    assertAnchoredContinuation("^[ab]*", "!a😀b!", false, true);
    assertAnchoredContinuation("\\A(a?)", "!a", true, true);
  }

  private static void assertAnchoredContinuation(
      String regex, String input, boolean initialLookingAt, boolean attemptLookingAt) {
    FuzzSupport.MatcherPair matcher = FuzzSupport.compileOrSkip(regex, 0).matcher(input);
    if (initialLookingAt) {
      matcher.lookingAt();
    } else {
      matcher.find();
    }
    if (attemptLookingAt) {
      matcher.lookingAt();
    } else {
      matcher.matches();
    }
    matcher.find();
    matcher.find();
  }

  @Test
  void delimitedPrefixBeforeRequiredSuffixRegression() {
    FuzzSupport.CompiledPattern pattern =
        FuzzSupport.compileOrSkip("[^{']*(?:'[^']*'[^{']*)*\\{([^}]*)\\}", 0);

    pattern.matcher("Foo '{0}' Bar: {0}").find();
  }

  @Test
  void dfaBoundSemanticsRegressions() {
    assertFindSequence("$", "x".repeat(512) + "a\n");
    assertFindSequence("$", "x".repeat(512) + "a\r\n");
    assertFindSequence("$", "x".repeat(512) + "a\u2028");
    assertFindSequence("(?:\\B{1}|a).a?", "ab".repeat(300) + "c");
    assertFindSequence("(?:\\B{1}|a).a?$", "x".repeat(512) + "ab");
    assertFindSequence("(?:a+?|(?:[^x])*)$", "x".repeat(512) + "a\n");
    assertFindSequence("(?:a{2,}|(?:.|\\B){1,2}){1,2}", "baax");
  }

  @Test
  void unicodeBoundaryTransitionClassesRegression() {
    for (String word : List.of("α", "中", "\u0301", "\u0660", "\u200c")) {
      assertFindSequence("(?U).\\B", " " + word + "`\u180e");
      assertFindSequence("(?U)\\b.", "x".repeat(300) + " " + word + "`\u180e");
    }
  }

  @Test
  void nullableLoopFindsInLargeInputsPreservePriorityAndCaptures() {
    for (String atom : List.of("a", "α", "😀")) {
      String literal = java.util.regex.Pattern.quote(atom);
      for (int length : List.of(0, 4, 64, 200)) {
        String segment = "S" + atom.repeat(length) + "E";
        String input = segment + "x".repeat(32_000) + segment + "x".repeat(32_000);
        assertFindSequence("S((?:" + literal + "?)*)E", input);
        assertFindSequence("S((?:" + literal + "?)*?)E", input);
      }
    }
  }

  @Test
  void leadingExpansionDoesNotCrossSplitSurrogateFindStart() {
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip("[\\x{1F600}]+b", 0);

    pattern.matcher("😀bX😀b").find(1);
  }

  @Test
  void interiorClassCandidatesPreserveFindSequences() {
    for (String prefix : INTERIOR_CLASS_PREFIXES) {
      for (String atom : List.of("[0-9]", "[A-F0-9]")) {
        for (String quantifier : List.of("+", "{4,}", "{1,3}?")) {
          for (String noise :
              List.of("é😀 words 1111x 1x aa1234Y ".repeat(100), "1234567890".repeat(1_000))) {
            assertFindSequence(
                prefix + "(" + atom + quantifier + ")",
                noise + "   A1234 e1234 aa12345 😀1234 éé12345 x1234 aé12345");
          }
        }
      }
    }
  }

  @Test
  void scopedEndAnchorLineModes() {
    for (String anchor : List.of("$", "\\Z")) {
      for (String terminator : List.of("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029")) {
        for (String mode : List.of("d", "-d")) {
          for (int flags : new int[] {0, java.util.regex.Pattern.UNIX_LINES}) {
            FuzzSupport.CompiledPattern pattern =
                FuzzSupport.compileOrSkip("(?:foo|bar)(?" + mode + ":" + anchor + ")", flags);
            FuzzSupport.MatcherPair matcher = pattern.matcher("foo" + terminator);
            while (matcher.find()) {
              matcher.start();
              matcher.end();
              matcher.group();
            }
          }
        }
      }
    }
  }

  @Test
  void mixedCrLfAndStandaloneLfCacheRegressions() {
    for (String regex : List.of("(?-d:(?m:$))(?dm:$)", "(?dm:$)(?-d:(?m:$))")) {
      FuzzSupport.CompiledPattern pattern =
          FuzzSupport.compileOrSkip(regex, java.util.regex.Pattern.UNIX_LINES);
      for (String input : List.of("\r\na\na", "\na\r\na", "a\na", "\r\na\na")) {
        FuzzSupport.MatcherPair matcher = pattern.matcher(input);
        while (matcher.find()) {
          matcher.start();
          matcher.end();
        }
      }
    }
  }

  @Test
  void resetAndThreadHandoffKeepsNfaCachesSeparate() {
    for (String regex : List.of("\\X", "(\\X)", "\\X+", "\\X+?")) {
      assertResetHandoffCacheIsolation(regex, "aé😀", "e\u0301x");
    }
  }

  private static void assertResetHandoffCacheIsolation(
      String regex, String warmInput, String newInput) {
    try {
      var pattern = org.safere.Pattern.compile(regex);
      var transferred = pattern.matcher(warmInput);
      assertThat(transferred.find()).isTrue();
      transferred.reset(newInput);
      var retained = pattern.matcher(warmInput);
      assertThat(retained.find()).isTrue();
      // Check the ownership invariant deterministically rather than racing the two engines.
      Field engine = org.safere.Matcher.class.getDeclaredField("cachedNfa");
      engine.setAccessible(true);
      Object retainedEngine = engine.get(retained);
      assertThat(retainedEngine).isNotNull();
      try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
        Object transferredEngine =
            worker
                .submit(
                    () -> {
                      assertThat(transferred.find()).isTrue();
                      var oracle = java.util.regex.Pattern.compile(regex).matcher(newInput);
                      assertThat(oracle.find()).isTrue();
                      assertThat(transferred.start()).isEqualTo(oracle.start());
                      assertThat(transferred.end()).isEqualTo(oracle.end());
                      assertThat(transferred.group()).isEqualTo(oracle.group());
                      return engine.get(transferred);
                    })
                .get();
        assertThat(transferredEngine).isNotNull().isNotSameAs(retainedEngine);
      }
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new AssertionError("Reset/handoff cache isolation failed", e);
    }
  }

  @Test
  void previousMatchBoundariesComposeWithTokensAndMatcherState() {
    for (String regex :
        List.of("\\G", "\\G([a-z]+),?", "\\Ga|b", "a*\\G", "(\\G(a?))*", "\\G|b", "\\G?+a")) {
      for (String input : List.of("", "ab,aa,!tail", "ba!ba", "é😀,ab")) {
        var compiled = FuzzSupport.compileOrSkip(regex, 0);
        var matcher = compiled.matcher(input);
        for (int attempt = 0; attempt < 8; attempt++) {
          matcher.find();
        }
        matcher.reset();
        matcher.lookingAt();
        matcher.matches();
        matcher.find();
        matcher.reset();
        matcher.region(input.length() / 2, input.length());
        matcher.find();
      }
    }
  }

  @Test
  void replacementFastPathsRetainPreviousMatchBoundary() {
    for (String regex : List.of("a", "[a]+", "(a)", "a|b", "a*", "[a-z]*")) {
      assertReplacementBoundary(regex, "aba!", "X");
    }
  }

  private static void assertReplacementBoundary(String regex, String input, String replacement) {
    var safe = org.safere.Pattern.compile(regex).matcher(input);
    var jdk = java.util.regex.Pattern.compile(regex).matcher(input);
    assertThat(safe.replaceAll(replacement)).isEqualTo(jdk.replaceAll(replacement));
    safe.usePattern(org.safere.Pattern.compile("\\G!"));
    jdk.usePattern(java.util.regex.Pattern.compile("\\G!"));
    for (int attempt = 0; attempt < 3; attempt++) {
      boolean matched = jdk.find();
      assertThat(safe.find())
          .as("replaceAll;usePattern;find /%s/ on %s", regex, input)
          .isEqualTo(matched);
      if (matched) {
        assertThat(safe.start()).isEqualTo(jdk.start());
        assertThat(safe.end()).isEqualTo(jdk.end());
      }
    }
  }

  @Test
  void failedPartialSearchesRetainSuccessfulBoundary() {
    for (FailedSearchContext context : FAILED_SEARCH_CONTEXTS) {
      for (String padding : List.of("", "!", "é😀 ")) {
        for (boolean region : List.of(false, true)) {
          assertFailedSearchBoundary(context, padding, 3, region);
        }
      }
    }
  }

  private record FailedSearchContext(String regex, String token, String partial) {}

  private static void assertFailedSearchBoundary(
      FailedSearchContext context, String padding, int repeats, boolean region) {
    String input = padding + context.token() + context.partial().repeat(repeats);
    var safe = org.safere.Pattern.compile(context.regex()).matcher(input);
    var jdk = java.util.regex.Pattern.compile(context.regex()).matcher(input);
    if (region) {
      safe.region(padding.length(), input.length());
      jdk.region(padding.length(), input.length());
    }
    int previousEnd = padding.length() + context.token().length();
    assertThat(safe.find()).isTrue();
    assertThat(jdk.find()).isTrue();
    assertThat(safe.end()).isEqualTo(previousEnd);
    assertThat(jdk.end()).isEqualTo(previousEnd);
    assertThat(safe.find()).isFalse();
    assertThat(jdk.find()).isFalse();
    // The successful end, not the end of an operand in the failed attempt, is the model oracle.
    safe.usePattern(
        org.safere.Pattern.compile("\\G" + java.util.regex.Pattern.quote(context.partial())));
    assertThat(safe.find()).as("/%s/ on %s", context.regex(), input).isTrue();
    assertThat(safe.start()).isEqualTo(previousEnd);
    assertThat(safe.end()).isEqualTo(previousEnd + context.partial().length());
  }

  @Test
  void nullablePreviousMatchLoopsRetainFinalEmptyCaptures() {
    for (String token : List.of("a", "é", "😀", "ab")) {
      for (String quantifier : List.of("*", "+", "{1,3}")) {
        for (boolean transparent : List.of(false, true)) {
          assertNullableBoundaryCaptures(token, "é😀", 3, quantifier, transparent);
        }
      }
    }
  }

  private static void assertNullableBoundaryCaptures(
      String token, String padding, int depth, String quantifier, boolean transparent) {
    String literal = java.util.regex.Pattern.quote(token);
    String regex = "(?:" + literal + ")?\\G";
    for (int level = 0; level < depth; level++) {
      regex = "(" + regex + ")" + quantifier;
    }
    String input = padding + token + "!";
    int start = padding.length();
    int end = start + token.length();
    var safe =
        org.safere.Pattern.compile(literal)
            .matcher(input)
            .region(start, end)
            .useTransparentBounds(transparent);
    var jdk =
        java.util.regex.Pattern.compile(literal)
            .matcher(input)
            .region(start, end)
            .useTransparentBounds(transparent);
    assertThat(safe.find()).isTrue();
    assertThat(jdk.find()).isTrue();
    safe.usePattern(org.safere.Pattern.compile(regex));
    jdk.usePattern(java.util.regex.Pattern.compile(regex));
    assertThat(safe.lookingAt()).isTrue();
    assertThat(jdk.lookingAt()).isTrue();
    for (int group = 0; group <= jdk.groupCount(); group++) {
      assertThat(safe.start(group)).as("/%s/ group %s", regex, group).isEqualTo(jdk.start(group));
      assertThat(safe.end(group)).isEqualTo(jdk.end(group));
      assertThat(safe.group(group)).isEqualTo(jdk.group(group));
    }
  }

  @FuzzTest(maxDuration = "30s")
  void sequence(FuzzedDataProvider data) {
    fuzzerTestOneInput(data);
  }

  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
    String regex;
    int flags;
    String input;
    boolean splitSurrogateFindStart = false;
    boolean warmLineEndCache = false;
    String warmInput = null;
    boolean previousMatchGrammar = false;
    switch (data.consumeInt(0, 22)) {
      case 0 -> {
        regex = nestedCapturingGroups(data.consumeInt(0, 512)) + "*";
        flags = 0;
        input = data.consumeBoolean() ? "a" : "a!";
      }
      case 1 -> {
        String suffix = data.consumeBoolean() ? "\\{([^}]*)\\}" : "END";
        regex = "[^']*(?:'[^']*'[^']*)*" + suffix;
        flags = 0;
        input = data.consumeBoolean() ? "Foo '{0}' Bar: {0}" : "prefix 'not END' suffix END";
      }
      case 2 -> {
        regex = data.consumeString(256);
        flags = FuzzSupport.consumeFlags(data);
        input = data.consumeString(2048);
      }
      case 3 -> {
        regex = "$";
        flags = 0;
        String terminator =
            data.pickValue(List.of("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029"));
        input = data.consumeString(64) + "a" + terminator;
      }
      case 4 -> {
        regex = "(?:\\B{1}|a).a?";
        flags = 0;
        input = "ab".repeat(data.consumeInt(0, 700)) + data.consumeString(2);
      }
      case 5 -> {
        regex = "(?:a+?|(?:[^x])*)$";
        flags = 0;
        input = "x".repeat(data.consumeInt(0, 700)) + data.consumeString(4);
      }
      case 6 -> {
        regex = "(?:a{2,}|(?:.|\\B){1,2}){1,2}";
        flags = 0;
        input = data.consumeBoolean() ? "baax" : data.consumeString(16);
      }
      case 7 -> {
        String quantifier = data.pickValue(List.of("*", "+", "{1,2}"));
        regex = "[\\x{1F600}]" + quantifier + "b";
        flags = 0;
        input = "😀b" + data.consumeString(16) + "😀b";
        splitSurrogateFindStart = true;
      }
      case 8 -> {
        String anchor = data.pickValue(List.of("$", "\\Z", "(?m:$)"));
        String localMode = data.consumeBoolean() ? "d" : "-d";
        String scopedAnchor = "(?" + localMode + ":" + anchor + ")";
        String body = data.pickValue(List.of("foo", "(?:foo|bar)", "(foo|bar)", "[a-z]+"));
        regex = body + scopedAnchor + (data.consumeBoolean() ? anchor : "");
        flags = data.consumeBoolean() ? java.util.regex.Pattern.UNIX_LINES : 0;
        String terminator =
            data.pickValue(List.of("", "\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029"));
        input = "x".repeat(data.consumeInt(0, 2048)) + "foo" + terminator;
      }
      case 9 -> {
        regex =
            data.pickValue(
                List.of(
                    "(?-d:(?m:$))(?dm:$)",
                    "(?dm:$)(?-d:(?m:$))",
                    "((?-d:(?m:$)))(?dm:$)",
                    "(?-d:(?m:^))(?dm:^)[ab]"));
        flags = data.consumeBoolean() ? java.util.regex.Pattern.UNIX_LINES : 0;
        StringBuilder mixedLines = new StringBuilder("x".repeat(data.consumeInt(0, 600)));
        for (int i = data.consumeInt(1, 16); i > 0; i--) {
          mixedLines.append(data.pickValue(List.of("\r\na", "\na", "\rb", "\u0085a", "b")));
        }
        input = mixedLines.toString();
        warmLineEndCache = true;
      }
      case 10 -> {
        String atom = data.pickValue(List.of("a", "😀", "[ab]", "(a|b)"));
        String anchor = data.consumeBoolean() ? "^" : "";
        regex = anchor + atom + data.pickValue(List.of("", "*", "+", "{1,3}"));
        flags = 0;
        String prefix = data.consumeBoolean() ? "!" : "";
        input = prefix + "a😀b".repeat(data.consumeInt(1, 200)) + "!";
        assertAnchoredContinuation(regex, input, data.consumeBoolean(), data.consumeBoolean());
      }
      case 12 -> {
        String anchor = data.pickValue(List.of("^", "\\A", "(?m)^"));
        String body = data.pickValue(List.of("[ab]*", "(a?)", "(a|b)*", "(?:😀)*"));
        regex = anchor + body;
        flags = 0;
        input = "!" + data.consumeString(64);
        FuzzSupport.MatcherPair matcher = FuzzSupport.compileOrSkip(regex, flags).matcher(input);
        if (data.consumeBoolean()) {
          matcher.find(data.consumeInt(0, input.length()));
        } else if (data.consumeBoolean()) {
          matcher.find();
        } else {
          matcher.lookingAt();
        }
        for (int i = 0, steps = data.consumeInt(3, 8); i < steps; i++) {
          matcher.find();
        }
      }
      case 11 -> {
        String atom = data.pickValue(List.of("\\W", "\\s", "[\\r\\n ]"));
        String quantifier = data.pickValue(List.of("*", "+", "?", "*?", "{0,3}"));
        regex = atom + quantifier + "(?m:$)";
        flags = 0;
        warmInput = " ".repeat(data.consumeInt(1, 8)) + data.pickValue(LINE_TERMINATORS) + "a";
        input = "xb" + data.pickValue(LINE_TERMINATORS) + "a".repeat(data.consumeInt(1, 700));
      }
      case 13 -> {
        String boundary = data.pickValue(List.of("\\b", "\\B"));
        String atom = data.pickValue(List.of(".", "(.)", "[\\s\\S]"));
        regex = "(?U)" + (data.consumeBoolean() ? atom + boundary : boundary + atom);
        flags = 0;
        String word = data.pickValue(List.of("α", "中", "\u0301", "\u0660", "\u200c"));
        String nonWord = data.pickValue(List.of("`", "\u180e", "!"));
        input = "x".repeat(data.consumeInt(0, 512)) + " " + word + nonWord + nonWord;
      }
      case 14 -> {
        String atom = data.pickValue(List.of("a", "!", "α", "😀", "\n", "\r\n"));
        String literal = java.util.regex.Pattern.quote(atom);
        String assertion = data.pickValue(List.of("\\b", "\\B", "(?m:$)"));
        String consuming = literal.repeat(data.consumeInt(1, 4));
        String alternatives =
            data.consumeBoolean() ? assertion + "|" + consuming : consuming + "|" + assertion;
        regex =
            "(?:"
                + literal
                + ")"
                + data.pickValue(List.of("*", "*?", "+", "{0,3}"))
                + "(?:"
                + alternatives
                + ")";
        flags = data.consumeBoolean() ? java.util.regex.Pattern.UNICODE_CHARACTER_CLASS : 0;
        input = atom.repeat(data.consumeInt(0, 600)) + (data.consumeBoolean() ? "x" : "");
        FuzzSupport.compileOrSkip(regex, flags).matcher(input).matches();
      }
      case 15 -> {
        String atom = data.pickValue(List.of("a", "α", "😀"));
        String literal = java.util.regex.Pattern.quote(atom);
        String body = data.pickValue(List.of(literal + "?", "(" + literal + "?)", literal + "|"));
        regex = "S((?:" + body + ")" + data.pickValue(List.of("*", "*?", "+")) + ")E";
        flags = 0;
        String segment = "S" + atom.repeat(data.consumeInt(0, 200)) + "E";
        String gap = "x".repeat(data.consumeInt(32_000, 64_000));
        input =
            data.consumeBoolean()
                ? segment + gap + segment + gap
                : "S" + atom.repeat(data.consumeInt(0, 200)) + "Z" + gap + segment + gap;
      }
      case 16 -> {
        String prefix = data.pickValue(INTERIOR_CLASS_PREFIXES);
        String atom = data.pickValue(List.of("[0-9]", "[A-F0-9]", "[A-F]"));
        String quantifier = data.pickValue(List.of("+", "{4,}", "{1,3}?"));
        regex = prefix + "(" + atom + quantifier + ")" + data.pickValue(List.of("", "Z", "$"));
        flags = 0;
        String unit = data.pickValue(List.of("1111x", " 1x", "aa1234Y", "é😀1111x", "1234567890"));
        input =
            unit.repeat(data.consumeInt(0, 1_000))
                + "   A1234Z e1234Z aa12345Z 😀1234Z éé12345Z x1234Z aé12345Z";
      }
      case 17 -> {
        String grapheme = data.pickValue(List.of("\\X", "(\\X)", "(?:\\X)"));
        String quantifier = data.pickValue(List.of("", "+", "+?", "{1,3}"));
        String warm = "a" + data.consumeString(16);
        String next =
            data.pickValue(List.of("é", "😀", "e\u0301", "\r\n")) + data.consumeString(16);
        assertResetHandoffCacheIsolation(grapheme + quantifier, warm, next);
        return;
      }
      case 18 -> {
        SearchStartContext context = data.pickValue(SEARCH_START_CONTEXTS);
        assertSearchStartAssertionFinds(
            context,
            data.pickValue(context.suffixes()),
            data.pickValue(List.of("?", "*", "{0,2}")),
            data.consumeBoolean(),
            data.consumeBoolean(),
            "z".repeat(data.consumeInt(0, 32)));
        return;
      }
      case 19 -> {
        previousMatchGrammar = true;
        String atom = data.pickValue(List.of("a", "[a-z]", ".", "[éα😀]"));
        String quantified = atom + data.pickValue(List.of("", "?", "+", "*", "{0,2}", "+?"));
        String assertion = data.pickValue(List.of("\\G", "(\\G)", "(?:\\G){2}", "\\G?+"));
        regex =
            data.pickValue(
                List.of(
                    assertion + "(" + quantified + "),?",
                    "(" + quantified + ")" + assertion,
                    "(?:" + assertion + quantified + "|b)",
                    "(?:" + assertion + quantified + ")*"));
        flags = FuzzSupport.consumeFlags(data);
        input =
            data.pickValue(List.of("ab,", "é😀,", "aa!", "bα,")).repeat(data.consumeInt(0, 32))
                + data.consumeString(16);
      }
      case 20 -> {
        assertReplacementBoundary(
            data.pickValue(List.of("a", "[a]+", "(a)", "a|b", "a*", "[a-z]*")),
            data.pickValue(List.of("ab", "a!", "ba")).repeat(data.consumeInt(0, 64)) + "!",
            data.pickValue(List.of("X", "", "replacement")));
        return;
      }
      case 21 -> {
        assertFailedSearchBoundary(
            data.pickValue(FAILED_SEARCH_CONTEXTS),
            data.pickValue(List.of("", "!", "é😀 ")),
            data.consumeInt(1, 32),
            data.consumeBoolean());
        return;
      }
      case 22 -> {
        assertNullableBoundaryCaptures(
            data.pickValue(List.of("a", "é", "😀", "ab")),
            data.pickValue(List.of("", "z", "é😀")),
            data.consumeInt(1, 4),
            data.pickValue(List.of("*", "+", "{1,3}")),
            data.consumeBoolean());
        return;
      }
      default -> throw new AssertionError();
    }
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, flags);
    if (pattern == null) {
      return;
    }
    if (warmLineEndCache) {
      FuzzSupport.MatcherPair warmup = pattern.matcher(data.consumeBoolean() ? "\r\na" : "a\na");
      while (warmup.find()) {
        warmup.start();
        warmup.end();
      }
    }
    if (warmInput != null) {
      pattern.matcher(warmInput).find();
    }
    if (splitSurrogateFindStart) {
      pattern.matcher(input).find(1);
    }

    FuzzSupport.MatcherPair findWalker = pattern.matcher(input);
    int maxFinds = Math.min(input.length() + 2, 64);
    for (int i = 0; i < maxFinds; i++) {
      boolean found = findWalker.find();
      if (!found) {
        break;
      }
    }

    FuzzSupport.MatcherPair matcher = pattern.matcher(input);
    boolean canReadGroups = false;
    int steps = data.consumeInt(1, 32);
    for (int i = 0; i < steps; i++) {
      int action = data.consumeInt(0, 10);
      switch (action) {
        case 0 -> canReadGroups = matcher.matches();
        case 1 -> canReadGroups = matcher.lookingAt();
        case 2 -> canReadGroups = matcher.find();
        case 3 -> canReadGroups = matcher.find(FuzzSupport.consumeIndex(data, input));
        case 4 -> {
          matcher.reset();
          canReadGroups = false;
        }
        case 5 -> {
          input = data.consumeString(2048);
          matcher.reset(input);
          canReadGroups = false;
        }
        case 6 -> matcher.groupCount();
        case 7 -> {
          if (canReadGroups) {
            matcher.group();
            matcher.start();
            matcher.end();
            int group = data.consumeInt(0, matcher.groupCount());
            matcher.group(group);
            matcher.start(group);
            matcher.end(group);
          }
        }
        case 8 -> {
          int[] region = FuzzSupport.consumeRegion(data, input);
          matcher.region(region[0], region[1]);
          canReadGroups = false;
        }
        case 9 -> {
          matcher.useAnchoringBounds(data.consumeBoolean());
          matcher.useTransparentBounds(data.consumeBoolean());
        }
        case 10 -> matcher.hasMatch();
        default -> throw new AssertionError();
      }
      if (previousMatchGrammar && action <= 3 && !canReadGroups) {
        // Failed optional operands can corrupt the JDK's saved end (#1164). Compare the
        // whole failure, then reset the generated differential domain before its next operation.
        // Independent model sequences retain failures and assert the SafeRE boundary.
        matcher.reset();
      }
    }
  }

  private record SearchStartContext(
      String atom, String leadingText, String consuming, String assertion, List<String> suffixes) {}

  private static void assertSearchStartAssertionFinds(
      SearchStartContext context,
      String suffix,
      String quantifier,
      boolean captures,
      boolean assertionFirst,
      String padding) {
    String group = captures ? "(" : "(?:";
    String consuming = java.util.regex.Pattern.quote(context.consuming());
    String asserted = context.assertion() + java.util.regex.Pattern.quote(suffix);
    String alternatives = assertionFirst ? asserted + "|" + consuming : consuming + "|" + asserted;
    String regex = group + context.atom() + quantifier + ")" + group + alternatives + ")?";
    String input = padding + context.leadingText() + context.consuming() + suffix;
    int searchStart =
        padding.length() + context.leadingText().length() + context.consuming().length();
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, 0);

    // The shared matcher pair checks all captures after each successful find, warming the pattern.
    pattern.matcher(input).find(searchStart);
    FuzzSupport.MatcherPair walker = pattern.matcher(input);
    for (int i = 0; i < input.length() + 2; i++) {
      if (!walker.find()) {
        break;
      }
    }
    for (boolean transparent : List.of(false, true)) {
      FuzzSupport.MatcherPair matcher = pattern.matcher(input);
      matcher.region(searchStart, input.length());
      matcher.useTransparentBounds(transparent);
      matcher.find();
    }
  }

  private static String nestedCapturingGroups(int depth) {
    return "(".repeat(depth) + "a" + ")".repeat(depth);
  }

  private static void assertFindSequence(String regex, String input) {
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, 0);
    pattern.matcher(input).find();
    FuzzSupport.MatcherPair matcher = pattern.matcher(input);
    int maxFinds = Math.min(input.length() + 2, 128);
    for (int i = 0; i < maxFinds; i++) {
      if (!matcher.find()) {
        return;
      }
    }
  }
}
