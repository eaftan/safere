// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("Tests the internal byte scanner directly")
class ByteSwarScanTest {
  @Test
  void tripleSearchPreservesLeftmostSelectionAcrossViewsBlocksAndTails() {
    byte[][] triples = {
      {'X', 'Z', '_'}, {'X', '_', 'Z'}, {'_', 'X', 'Z'}, {'a', 'c', 'e'}, {'!', 'A', 'z'}, {0, 2, 7}
    };
    int[] lengths = {0, 1, 7, 8, 9, 15, 16, 17, 31, 32, 63, 64, 65, 127, 128, 129, 255};
    for (byte[] triple : triples) {
      for (int offset = 0; offset < 16; offset++) {
        for (int length : lengths) {
          byte[] storage = new byte[offset + length + 16];
          Arrays.fill(storage, (byte) 0xC3);
          // Matching sentinels on both sides must never be visible through the view.
          Arrays.fill(storage, 0, offset, triple[0]);
          Arrays.fill(storage, offset + length, storage.length, triple[0]);
          assertThat(scan(storage, offset, length, triple, 0)).isEqualTo(-1);
          for (int hit = 0; hit < length; hit++) {
            storage[offset + hit] = triple[hit % triple.length];
            assertThat(scan(storage, offset, length, triple, 0)).isEqualTo(hit);
            assertThat(scan(storage, offset, length, triple, hit)).isEqualTo(hit);
            assertThat(scan(storage, offset, length, triple, hit + 1)).isEqualTo(-1);
            storage[offset + hit] = (byte) 0xC3;
          }
        }
      }
    }
  }

  @Test
  void asciiBlocksFallBackWithoutLosingLaterMatches() {
    byte[] triple = {'X', 'Z', '_'};
    for (int length : new int[] {127, 128, 129, 255, 4096}) {
      for (int falseHit : new int[] {0, 7, 8, 31, 32, 63}) {
        byte[] storage = new byte[length];
        Arrays.fill(storage, (byte) 'a');
        // An ASCII nonmember must hand off to the exact scan, not establish a match.
        storage[falseHit] = 'Y';
        assertThat(scan(storage, 0, length, triple, 0)).isEqualTo(-1);
        for (int hit = falseHit + 1; hit < length; hit++) {
          storage[hit] = 'Z';
          assertThat(scan(storage, 0, length, triple, 0)).isEqualTo(hit);
          storage[hit] = 'a';
        }
      }
    }
  }

  @Test
  void mixedBlocksRequireExactVerification() {
    byte[] triple = {'X', 'Z', '_'};
    byte[] storage = new byte[256];
    for (int i = 0; i < storage.length; i++) {
      // Every word contains ASCII bytes, but none of them is a member of the triple.
      storage[i] = (byte) ((i / Long.BYTES) % 2 == 0 ? 'H' : 'P');
    }
    assertThat(scan(storage, 0, storage.length, triple, 0)).isEqualTo(-1);
    storage[127] = '_';
    storage[255] = 'X';
    assertThat(scan(storage, 0, storage.length, triple, 0)).isEqualTo(127);
    assertThat(scan(storage, 0, storage.length, triple, 128)).isEqualTo(255);
  }

  @Test
  void tripleSearchAgreesWithScalarOracleForArbitraryBytesAndTriples() {
    Random random = new Random(0x5AFE_1185_8L);
    for (int trial = 0; trial < 10_000; trial++) {
      int offset = random.nextInt(16);
      int length = random.nextInt(1024);
      byte[] storage = new byte[offset + length + 16];
      random.nextBytes(storage);
      byte[] triple = new byte[3];
      random.nextBytes(triple);
      int start = random.nextInt(length + 1);
      int expected = -1;
      for (int pos = start; pos < length; pos++) {
        byte value = storage[offset + pos];
        if (value == triple[0] || value == triple[1] || value == triple[2]) {
          expected = pos;
          break;
        }
      }
      assertThat(scan(storage, offset, length, triple, start)).isEqualTo(expected);
    }
  }

  @Test
  void nonAsciiBlockSkipStopsBeforeTheFirstPossibleAsciiMatch() {
    for (int offset = 0; offset < 16; offset++) {
      for (int length : new int[] {128, 129, 255, 1024}) {
        byte[] storage = new byte[offset + length + 16];
        Arrays.fill(storage, (byte) 0xC3);
        Arrays.fill(storage, 0, offset, (byte) 'X');
        Arrays.fill(storage, offset + length, storage.length, (byte) 'X');
        int skipped = ByteSwarScan.skipNonAsciiBlocks(storage, offset, length, 0);
        assertThat(skipped).isBetween(length - 31, length);
        for (int hit = 0; hit < length; hit++) {
          storage[offset + hit] = 'X';
          skipped = ByteSwarScan.skipNonAsciiBlocks(storage, offset, length, 0);
          assertThat(skipped).isBetween(Math.max(0, hit - 31), hit);
          storage[offset + hit] = (byte) 0xC3;
        }
      }
    }
  }

  @Test
  void tripleWordSearchSelectsTheRealMemberBeforeBorrowArtifacts() {
    for (int target = 0; target < 256; target++) {
      byte first = (byte) target;
      byte second = (byte) (target ^ 2);
      byte third = (byte) (target ^ 4);
      for (int offset = 0; offset < Long.BYTES; offset++) {
        byte[] storage = new byte[offset + 64];
        Arrays.fill(storage, (byte) (target ^ 1));
        assertThat(ByteSwarScan.indexOfByteTriple(storage, offset, 64, first, second, third, 0))
            .isEqualTo(-1);
        assertThat(
                ByteSwarScan.indexOfByteTripleWithPair(
                    storage, offset, 64, first, second, third, 0, 2))
            .isEqualTo(-1);
        for (int hit = 0; hit < 64; hit++) {
          storage[offset + hit] = first;
          assertThat(ByteSwarScan.indexOfByteTriple(storage, offset, 64, first, second, third, 0))
              .as("target %s, offset %s, hit %s", target, offset, hit)
              .isEqualTo(hit);
          assertThat(
                  ByteSwarScan.indexOfByteTripleWithPair(
                      storage, offset, 64, first, second, third, 0, 2))
              .as("paired target %s, offset %s, hit %s", target, offset, hit)
              .isEqualTo(hit);
          storage[offset + hit] = (byte) (target ^ 1);
        }
      }
    }
  }

  private static int scan(byte[] storage, int offset, int length, byte[] triple, int start) {
    if ((triple[0] | triple[1] | triple[2]) >= 0) {
      return new Utf8InputScanner(storage, offset, length)
          .indexOfAsciiTriple(triple[0], triple[1], triple[2], start, length);
    }
    return ByteSwarScan.indexOfByteTriple(
        storage, offset, length, triple[0], triple[1], triple[2], start);
  }
}
