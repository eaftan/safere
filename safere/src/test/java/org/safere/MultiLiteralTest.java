// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class MultiLiteralTest {

  @Test
  void patternMetadataFollowsVectorProviderAvailability() {
    Pattern pattern = Pattern.compile("apple|banana|cherry");

    if (!VectorScanProviders.multiLiteralProviderAvailable()) {
      assertThat(pattern.multiLiteral()).isNull();
    } else {
      assertThat(pattern.multiLiteral()).isNotNull();
      assertThat(VectorScanProviders.providerForLength(64)).isNull();
      assertThat(VectorScanProviders.providerForMultiLiteralLength(64)).isNotNull();
      assertThat(VectorScanProviders.providerForLength(1024)).isNotNull();
    }
  }

  @Test
  void testMultiLiteralInfoCreation() {
    String[] lits = {"apple", "banana", "cherry"};
    MultiLiteralInfo info = MultiLiteralInfo.create(lits);
    assertThat(info).isNotNull();
    assertThat(info.literals()).containsExactly("apple", "banana", "cherry");
    assertThat(info.minLength()).isEqualTo(5);
    assertThat(info.anchorChars()).containsExactly('a', 'b', 'c');
    assertThat(info.anchorOffsets()).containsExactly(0, 0, 0);

    assertThat(MultiLiteralInfo.create(new String[] {"single"})).isNull();
    assertThat(MultiLiteralInfo.create(new String[] {"1", "2", "3", "4", "5"})).isNull();
    assertThat(MultiLiteralInfo.create(new String[] {"valid", "café"})).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"foo|bar", "foo|bar|baz", "cat|dog|bird|fish"})
  void testBasicMatching(String regex) {
    Pattern pattern = Pattern.compile(regex);

    String[] keywords = regex.split("\\|");
    for (String kw : keywords) {
      String haystack = "prefix " + kw + " suffix";
      byte[] bytes = haystack.getBytes(UTF_8);
      Utf8Matcher m = pattern.matcher(Utf8Input.validated(bytes));
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(7);
      assertThat(m.end()).isEqualTo(7 + kw.length());
      assertThat(new String(bytes, m.start(), m.end() - m.start(), UTF_8)).isEqualTo(kw);
    }

    String noMatch = "completely unrelated text with no hits at all";
    Utf8Matcher mNoMatch = pattern.matcher(Utf8Input.validated(noMatch.getBytes(UTF_8)));
    assertThat(mNoMatch.find()).isFalse();
  }

  @Test
  void testFivePlusLiteralsFallbackToCharClass() {
    Pattern pattern = Pattern.compile("apple|banana|cherry|date|fig");
    assertThat(pattern.multiLiteral()).isNull();

    String haystack = "prefix date suffix";
    Utf8Matcher m = pattern.matcher(Utf8Input.validated(haystack.getBytes(UTF_8)));
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(7);
    assertThat(m.end()).isEqualTo(11);
  }

  @Test
  void testLongHaystackWithMultipleMatches() {
    Pattern pattern = Pattern.compile("apple|banana|cherry|durian");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      sb.append("some padding text ");
    }
    int pos1 = sb.length();
    sb.append("cherry ");
    for (int i = 0; i < 300; i++) {
      sb.append("more padding text ");
    }
    int pos2 = sb.length();
    sb.append("banana ");
    for (int i = 0; i < 200; i++) {
      sb.append("final padding text ");
    }

    byte[] bytes = sb.toString().getBytes(UTF_8);
    Utf8Matcher m = pattern.matcher(Utf8Input.validated(bytes));
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(pos1);
    assertThat(new String(bytes, m.start(), m.end() - m.start(), UTF_8)).isEqualTo("cherry");

    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(pos2);
    assertThat(new String(bytes, m.start(), m.end() - m.start(), UTF_8)).isEqualTo("banana");

    assertThat(m.find()).isFalse();
  }

  @Test
  void testFuzzEquivalenceWithJavaRegex() {
    String[] keywords = {"alpha", "beta", "gamma", "delta", "zeta"};
    String regex = String.join("|", keywords);
    Pattern safere = Pattern.compile(regex);
    java.util.regex.Pattern jre = java.util.regex.Pattern.compile(regex);

    Random rnd = new Random(42);
    char[] alphabet = "abcdefghijklmnopqrstuvwxyz ".toCharArray();

    for (int trial = 0; trial < 100; trial++) {
      int len = 64 + rnd.nextInt(500);
      char[] chars = new char[len];
      for (int i = 0; i < len; i++) {
        chars[i] = alphabet[rnd.nextInt(alphabet.length)];
      }
      if (rnd.nextBoolean()) {
        String kw = keywords[rnd.nextInt(keywords.length)];
        int insertPos = rnd.nextInt(Math.max(1, len - kw.length()));
        for (int i = 0; i < kw.length(); i++) {
          chars[insertPos + i] = kw.charAt(i);
        }
      }
      String text = new String(chars);
      byte[] bytes = text.getBytes(UTF_8);

      Utf8Matcher sm = safere.matcher(Utf8Input.validated(bytes));
      java.util.regex.Matcher jm = jre.matcher(text);

      boolean sFound = sm.find();
      boolean jFound = jm.find();
      assertThat(sFound).as("text: %s", text).isEqualTo(jFound);
      if (sFound) {
        assertThat(sm.start()).isEqualTo(jm.start());
        assertThat(sm.end()).isEqualTo(jm.end());
      }
    }
  }
}
