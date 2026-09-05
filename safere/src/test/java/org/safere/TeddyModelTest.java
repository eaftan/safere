// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("implementation test uses package-private Teddy and Vector provider APIs")
class TeddyModelTest {

  @Test
  void compilationFollowsVectorProviderAvailability() {
    TeddyModel model = TeddyModel.compileForSelectedProvider(new String[] {"INFO", "WARN"});

    if (!VectorScanProviders.teddyProviderAvailable()) {
      assertThat(model).isNull();
    } else {
      assertThat(model).isNotNull();
      assertThat(VectorScanProviders.providerForLength(64)).isNull();
      assertThat(VectorScanProviders.providerForTeddyLength(64)).isNull();
      assertThat(VectorScanProviders.providerForTeddyLength(256)).isNull();
      assertThat(VectorScanProviders.providerForLength(1024)).isNotNull();
      assertThat(VectorScanProviders.providerForTeddyLength(1024)).isNotNull();
    }
  }

  @Test
  void singleGroupCompilationCappedAt32Literals() {
    String[] lits32 = new String[32];
    for (int i = 0; i < 32; i++) {
      lits32[i] = String.format("%c%c_%02d", 'a' + (i % 26), 'a' + ((i / 26) % 26), i);
    }
    TeddyModel model32 = TeddyModel.compile(lits32, 64);
    assertThat(model32).isNotNull();
    assertThat(model32.literals()).hasSize(32);

    String[] lits33 = new String[33];
    for (int i = 0; i < 33; i++) {
      lits33[i] = String.format("%c%c_%02d", 'a' + (i % 26), 'a' + ((i / 26) % 26), i);
    }
    assertThat(TeddyModel.compile(lits33, 64)).isNull();
  }

  @Test
  void singleGroupTeddyVectorScanMatches() {
    if (!VectorScanProviders.teddyProviderAvailable()) {
      return;
    }
    String[] lits = new String[20];
    for (int i = 0; i < 20; i++) {
      lits[i] = String.format("%c%c_%03d", 'a' + (i % 26), 'a' + ((i / 26) % 26), i);
    }
    TeddyModel model = TeddyModel.compile(lits, 64);
    assertThat(model).isNotNull();

    // Test match (index 5)
    String target0 = lits[5];
    String text0 = "padding_noise ".repeat(100) + target0 + " trailing_padding".repeat(100);
    byte[] bytes0 = text0.getBytes(StandardCharsets.UTF_8);
    int expected0 = text0.indexOf(target0);
    int found0 = TeddyVectorScan.indexOfTeddyUtf8(bytes0, 0, bytes0.length, model, 0);
    assertThat(found0).isEqualTo(expected0);

    // Test match (index 18)
    String target1 = lits[18];
    String text1 = "padding_noise ".repeat(100) + target1 + " trailing_padding".repeat(100);
    byte[] bytes1 = text1.getBytes(StandardCharsets.UTF_8);
    int expected1 = text1.indexOf(target1);
    int found1 = TeddyVectorScan.indexOfTeddyUtf8(bytes1, 0, bytes1.length, model, 0);
    assertThat(found1).isEqualTo(expected1);

    // Test no match
    String textNone = "padding_noise ".repeat(200);
    byte[] bytesNone = textNone.getBytes(StandardCharsets.UTF_8);
    int foundNone = TeddyVectorScan.indexOfTeddyUtf8(bytesNone, 0, bytesNone.length, model, 0);
    assertThat(foundNone).isEqualTo(-1);
  }

  @Test
  void teddyPreservesLeftmostFirstPrefixOrder() {
    if (!VectorScanProviders.teddyProviderAvailable()) {
      return;
    }
    String[] lits = {"b", "ba", "abc", "abcd"};
    TeddyModel model = TeddyModel.compile(lits, 64);
    assertThat(model).isNotNull();

    // In "ba", alternative "b" matches at 0 and "ba" matches at 0.
    // Leftmost match starting at 0 must be found.
    String text = "padding_noise ".repeat(100) + "ba" + " trailing_padding".repeat(100);
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    int expected = text.indexOf("ba");
    int found = TeddyVectorScan.indexOfTeddyUtf8(bytes, 0, bytes.length, model, 0);
    assertThat(found).isEqualTo(expected);
  }

  @Test
  void sharedPrefixHeadersMatchCorrectly() {
    if (!VectorScanProviders.teddyProviderAvailable()) {
      return;
    }
    String[] headers = {
      "Accept-Charset",
      "Accept-Encoding",
      "Accept-Language",
      "Content-Disposition",
      "Content-Encoding",
      "Content-Length",
      "Content-Type",
      "If-Match",
      "If-Modified-Since",
      "If-None-Match"
    };
    TeddyModel model = TeddyModel.compile(headers, 64);
    assertThat(model).isNotNull();

    for (String header : headers) {
      String text =
          "noise_prefix_pad ".repeat(50) + header + ": value\r\n" + "trailing_noise ".repeat(50);
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      int expected = text.indexOf(header);
      int found = TeddyVectorScan.indexOfTeddyUtf8(bytes, 0, bytes.length, model, 0);
      assertThat(found).isEqualTo(expected);
    }
  }

  @Test
  void adversarialCandidateNoiseExhaustsWorkLimitAndAborts() {
    if (!VectorScanProviders.teddyProviderAvailable()) {
      return;
    }
    // Patterns that share a 3-byte prefix: "aaa0", "aaa1", ... "aaa7"
    String[] lits = new String[8];
    for (int i = 0; i < 8; i++) {
      lits[i] = "aaa" + i;
    }
    TeddyModel model = TeddyModel.compile(lits, 64);
    assertThat(model).isNotNull();

    // 10,000 repetitions of false prefix "aaax"
    String noise = "aaax".repeat(10_000);
    byte[] noiseBytes = noise.getBytes(StandardCharsets.UTF_8);

    // Vector scan should hit WorkLimit exhaustion and return UNSUPPORTED
    int res = TeddyVectorScan.indexOfTeddyUtf8(noiseBytes, 0, noiseBytes.length, model, 0);
    assertThat(res).isEqualTo(VectorScanProvider.UNSUPPORTED);

    // Full Matcher should still correctly report false or locate a late match via DFA fallback
    String withLateMatch = noise + "aaa5";
    Matcher m = Pattern.compile("aaa0|aaa1|aaa2|aaa3|aaa4|aaa5|aaa6|aaa7").matcher(withLateMatch);
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(noise.length());
    assertThat(m.group()).isEqualTo("aaa5");
  }
}
