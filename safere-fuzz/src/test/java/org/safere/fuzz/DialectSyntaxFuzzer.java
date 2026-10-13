// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;
import java.util.Objects;
import org.safere.Matcher;
import org.safere.Pattern;

public final class DialectSyntaxFuzzer {

  private static final String[] CONTEXT_PREFIXES = {"", "^", "(?:", "a|", "[", "[^"};
  private static final String[] DIALECT_FRAGMENTS = {
    "(?P=name)",
    "(?'name'a)",
    "\\g{name}",
    "\\g1",
    "\\p{Braille}",
    "\\P{Braille}",
    "\\p{Latin}",
    "\\p{^Braille}",
    "\\p{InBraille}",
    "\\p{IsLatin}",
    "\\p{IsWhite_Space}",
    "\\P{IsWhite_Space}",
    "[:lower:]",
    "[:alpha:]",
    "[:digit:]",
    "[:^space:]",
    "[.ch.]",
    "[=a=]",
    "\\C",
    "\\R",
    "\\X",
    "\\b{g}",
    "\\K"
  };
  private static final String[] CONTEXT_SUFFIXES = {"", "$", ")", "b", "]", "&&[a]]"};
  private static final List<String> INPUTS =
      List.of(
          "", "a", "b", "a\u0300", "\r\n", "Braille", "Latin", ":", "[", "]", "l", "o", "w", "e",
          "r", "\u001B", "\u001C", "\u001D", "\u001E", "\u001F", "\u0084", "\u0085", "\u0086",
          "\u00A0", "\u2028", "\u2029", "\u3000");
  private static final String[] REGRESSION_REGEXES = {
    "\\p{Braille}",
    "\\p{Latin}",
    "\\p{IsWhite_Space}",
    "\\P{IsWhite_Space}",
    "[\\p{IsWhite_Space}&&[\\p{Cc}\\p{Z}]]",
    "[[:lower:]]",
    "[[:^space:]]",
    "a\\b{g}\\u0300",
    "\\r\\b{g}\\n",
    "\\b{g}a\\u0300\\b{g}",
    "\\Q{?\\E",
    "{?",
    "a{,2}",
    "a{2,1}"
  };
  private static final String[] SAFE_RE_EXTENSION_REGEXES = {"(?P<name>a)", "(?P<test_group>a)"};
  private static final String[] HEX_DIGIT_CONTEXTS = {
    "%s", "[%s]", "[^%s]", "[%s&&[\\p{Nd}]]", "(?:%s)+", "(?U)%s"
  };
  private static final String HEX_DIGIT_RANGES =
      "0-9A-Fa-f\\uFF10-\\uFF19\\uFF21-\\uFF26\\uFF41-\\uFF46";
  private static final int[] HEX_DIGIT_INPUTS = {
    0x2F, 0x30, 0x39, 0x3A, 0x40, 0x41, 0x46, 0x47, 0x60, 0x61, 0x66, 0x67, 0xFF0F, 0xFF10, 0xFF19,
    0xFF1A, 0xFF20, 0xFF21, 0xFF26, 0xFF27, 0xFF40, 0xFF41, 0xFF46, 0xFF47, 0x0660, 0x0966, 0x1D7CE
  };

  @FuzzTest(maxDuration = "30s")
  void dialectSyntax(FuzzedDataProvider data) {
    fuzzerTestOneInput(data);
  }

  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
    for (String regex : SAFE_RE_EXTENSION_REGEXES) {
      assertSafeRePythonNamedGroupExtension(regex);
    }
    for (String regex : REGRESSION_REGEXES) {
      FuzzSupport.assertFullMatchesJdk(regex, 0, INPUTS);
    }

    String prefix = data.pickValue(CONTEXT_PREFIXES);
    String suffix = data.pickValue(CONTEXT_SUFFIXES);
    if (prefix.startsWith("[") && !suffix.contains("]")) {
      suffix = suffix + "]";
    }
    if (!prefix.startsWith("[") && suffix.startsWith("]")) {
      suffix = "";
    }
    if (prefix.equals("(?:") && !suffix.startsWith(")")) {
      suffix = ")" + suffix;
    }

    String regex = prefix + data.pickValue(DIALECT_FRAGMENTS) + suffix;
    FuzzSupport.assertFullMatchesJdk(regex, FuzzSupport.consumeParserFlags(data), INPUTS);
    assertUnicodeHexDigit(data);
  }

  private static void assertUnicodeHexDigit(FuzzedDataProvider data) {
    // Issue #985: use explicit Unicode ranges instead of JDK's broader IsHex_Digit property.
    boolean negated = data.consumeBoolean();
    String context = data.pickValue(HEX_DIGIT_CONTEXTS);
    String regex = context.formatted(negated ? "\\P{IsHex_Digit}" : "\\p{IsHex_Digit}");
    String reference = context.formatted("[" + (negated ? "^" : "") + HEX_DIGIT_RANGES + "]");
    FuzzSupport.CompiledPattern pattern =
        new FuzzSupport.CompiledPattern(
            regex, 0, Pattern.compile(regex), java.util.regex.Pattern.compile(reference));
    for (int cp : HEX_DIGIT_INPUTS) {
      pattern.matcher(Character.toString(cp)).matches();
    }
    int cp = data.consumeInt(0, Character.MAX_CODE_POINT);
    pattern.matcher(Character.toString(cp)).matches();
    String input = FuzzSupport.consumeUnicodeHeavyString(data, 128);
    FuzzSupport.MatcherPair matcher = pattern.matcher(input);
    while (matcher.find()) {
      // Compare the full match sequence, including quantified property runs.
    }
  }

  private static void assertSafeRePythonNamedGroupExtension(String regex) {
    Pattern pattern = Pattern.compile(regex);
    Matcher matcher = pattern.matcher("a");
    if (!matcher.matches()) {
      throw new AssertionError("SafeRE Python-style named group did not match: " + regex);
    }
    if (!Objects.equals(matcher.group(1), "a")) {
      throw new AssertionError("SafeRE Python-style named group captured wrong text: " + regex);
    }
  }
}
