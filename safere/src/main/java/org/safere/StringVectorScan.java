// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.nativeOrder;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_16;
import static jdk.incubator.vector.VectorOperators.EQ;
import static org.safere.Ascii.regionMatchesIgnoreCase;
import static org.safere.Ascii.toLowerCase;
import static org.safere.Ascii.toUpperCase;

import java.util.Arrays;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;

/**
 * Adapter implementing vector-accelerated scans over {@link String} instances via reflective
 * zero-copy byte array access.
 */
final class StringVectorScan {
  private static final VectorSpecies<Byte> BYTE_SPECIES = ByteVector.SPECIES_PREFERRED;
  private static final VectorSpecies<Short> SHORT_SPECIES = ShortVector.SPECIES_PREFERRED;

  static int indexOfAsciiClass(String text, int[] ranges, int start) {
    if (StringSupport.isLatin1(text) && Swar.supportsAsciiRanges(ranges, 4)) {
      return ByteVectorScan.indexOfAsciiClass(
          StringSupport.value(text), 0, text.length(), ranges, start);
    }
    if (StringSupport.isUtf16(text) && Swar.supportsAsciiRanges(ranges, 4)) {
      return indexOfUtf16Class(text, ranges, start, text.length());
    }
    if (text.length() - start >= StringChunkBuffer.MIN_CHUNK_THRESHOLD) {
      return indexOfCharClassChunked(text, ranges, start, text.length());
    }
    return VectorScanProvider.UNSUPPORTED;
  }

  static int indexOfCharClass(String text, int[] ranges, int start) {
    return indexOfCharClass(text, ranges, start, text.length());
  }

  static int indexOfCharClass(String text, int[] ranges, int start, int limit) {
    if (StringSupport.isLatin1(text)) {
      int[] clamped = clampRangesForLatin1(ranges);
      if (clamped != null) {
        return ByteVectorScan.indexOfAsciiClass(
            StringSupport.value(text), 0, Math.min(limit, text.length()), clamped, start);
      }
      return VectorScanProvider.UNSUPPORTED;
    }
    if (StringSupport.isUtf16(text)) {
      return indexOfUtf16Class(text, ranges, start, limit);
    }
    if (limit - start >= StringChunkBuffer.MIN_CHUNK_THRESHOLD) {
      return indexOfCharClassChunked(text, ranges, start, limit);
    }
    return VectorScanProvider.UNSUPPORTED;
  }

  static int indexOfIgnoreCase(String text, String prefix, int start) {
    if (StringSupport.hasAccess()) {
      if (StringSupport.compatibleWith(text, ISO_8859_1)) {
        return indexOfIgnoreCaseLatin1(text, prefix, start);
      }
      if (StringSupport.compatibleWith(text, UTF_16)) {
        return indexOfIgnoreCaseUtf16(text, prefix, start);
      }
    }
    // Fall back to Matcher.indexOfIgnoreCase which leverages JVM intrinsic String.indexOf(char)
    return VectorScanProvider.UNSUPPORTED;
  }

  static int indexOfAsciiPair(String text, int c1, int c2, int fromIndex, int limit) {
    if (StringSupport.isLatin1(text)) {
      return indexOfAsciiPairLatin1(text, c1, c2, fromIndex, limit);
    }
    if (StringSupport.isUtf16(text) && nativeOrder() != BIG_ENDIAN) {
      return indexOfAsciiPairUtf16(text, c1, c2, fromIndex, limit);
    }
    if (limit - fromIndex >= StringChunkBuffer.MIN_CHUNK_THRESHOLD) {
      return indexOfAsciiPairChunked(text, c1, c2, fromIndex, limit);
    }
    return VectorScanProvider.UNSUPPORTED;
  }

  static int indexOfAsciiTriple(String text, int c1, int c2, int c3, int fromIndex, int limit) {
    if (StringSupport.isLatin1(text)) {
      return indexOfAsciiTripleLatin1(text, c1, c2, c3, fromIndex, limit);
    }
    if (StringSupport.isUtf16(text) && nativeOrder() != BIG_ENDIAN) {
      return indexOfAsciiTripleUtf16(text, c1, c2, c3, fromIndex, limit);
    }
    if (limit - fromIndex >= StringChunkBuffer.MIN_CHUNK_THRESHOLD) {
      return indexOfAsciiTripleChunked(text, c1, c2, c3, fromIndex, limit);
    }
    return VectorScanProvider.UNSUPPORTED;
  }

  private static int indexOfAsciiPairLatin1(String text, int c1, int c2, int fromIndex, int limit) {
    int scanLimit = Math.min(limit, text.length());
    int pos = Math.max(0, fromIndex);
    int vectorLen = BYTE_SPECIES.length();
    int vecLimit = scanLimit - vectorLen;

    ByteVector v1 = ByteVector.broadcast(BYTE_SPECIES, (byte) c1);
    ByteVector v2 = ByteVector.broadcast(BYTE_SPECIES, (byte) c2);

    for (; pos <= vecLimit; pos += vectorLen) {
      ByteVector inputVec = StringSupport.byteVectorFromString(BYTE_SPECIES, text, pos);
      VectorMask<Byte> matchMask = inputVec.compare(EQ, v1).or(inputVec.compare(EQ, v2));
      if (matchMask.anyTrue()) {
        int bit = matchMask.firstTrue();
        int found = pos + bit;
        return found < scanLimit ? found : -1;
      }
    }
    for (; pos < scanLimit; pos++) {
      char c = text.charAt(pos);
      if (c == c1 || c == c2) {
        return pos;
      }
    }
    return -1;
  }

  private static int indexOfAsciiPairUtf16(String text, int c1, int c2, int fromIndex, int limit) {
    int scanLimit = Math.min(limit, text.length());
    int pos = Math.max(0, fromIndex);
    int vectorLen = SHORT_SPECIES.length();
    int vecLimit = scanLimit - vectorLen;

    ShortVector v1 = ShortVector.broadcast(SHORT_SPECIES, (short) c1);
    ShortVector v2 = ShortVector.broadcast(SHORT_SPECIES, (short) c2);

    for (; pos <= vecLimit; pos += vectorLen) {
      ShortVector inputVec = StringSupport.shortVectorFromString(SHORT_SPECIES, text, pos);
      VectorMask<Short> matchMask = inputVec.compare(EQ, v1).or(inputVec.compare(EQ, v2));
      if (matchMask.anyTrue()) {
        int bit = matchMask.firstTrue();
        int found = pos + bit;
        return found < scanLimit ? found : -1;
      }
    }
    for (; pos < scanLimit; pos++) {
      char c = text.charAt(pos);
      if (c == c1 || c == c2) {
        return pos;
      }
    }
    return -1;
  }

  private static int indexOfAsciiTripleLatin1(
      String text, int c1, int c2, int c3, int fromIndex, int limit) {
    int scanLimit = Math.min(limit, text.length());
    int pos = Math.max(0, fromIndex);
    int vectorLen = BYTE_SPECIES.length();
    int vecLimit = scanLimit - vectorLen;

    ByteVector v1 = ByteVector.broadcast(BYTE_SPECIES, (byte) c1);
    ByteVector v2 = ByteVector.broadcast(BYTE_SPECIES, (byte) c2);
    ByteVector v3 = ByteVector.broadcast(BYTE_SPECIES, (byte) c3);

    for (; pos <= vecLimit; pos += vectorLen) {
      ByteVector inputVec = StringSupport.byteVectorFromString(BYTE_SPECIES, text, pos);
      VectorMask<Byte> matchMask =
          inputVec.compare(EQ, v1).or(inputVec.compare(EQ, v2)).or(inputVec.compare(EQ, v3));
      if (matchMask.anyTrue()) {
        int bit = matchMask.firstTrue();
        int found = pos + bit;
        return found < scanLimit ? found : -1;
      }
    }
    for (; pos < scanLimit; pos++) {
      char c = text.charAt(pos);
      if (c == c1 || c == c2 || c == c3) {
        return pos;
      }
    }
    return -1;
  }

  private static int indexOfAsciiTripleUtf16(
      String text, int c1, int c2, int c3, int fromIndex, int limit) {
    int scanLimit = Math.min(limit, text.length());
    int pos = Math.max(0, fromIndex);
    int vectorLen = SHORT_SPECIES.length();
    int vecLimit = scanLimit - vectorLen;

    ShortVector v1 = ShortVector.broadcast(SHORT_SPECIES, (short) c1);
    ShortVector v2 = ShortVector.broadcast(SHORT_SPECIES, (short) c2);
    ShortVector v3 = ShortVector.broadcast(SHORT_SPECIES, (short) c3);

    for (; pos <= vecLimit; pos += vectorLen) {
      ShortVector inputVec = StringSupport.shortVectorFromString(SHORT_SPECIES, text, pos);
      VectorMask<Short> matchMask =
          inputVec.compare(EQ, v1).or(inputVec.compare(EQ, v2)).or(inputVec.compare(EQ, v3));
      if (matchMask.anyTrue()) {
        int bit = matchMask.firstTrue();
        int found = pos + bit;
        return found < scanLimit ? found : -1;
      }
    }
    for (; pos < scanLimit; pos++) {
      char c = text.charAt(pos);
      if (c == c1 || c == c2 || c == c3) {
        return pos;
      }
    }
    return -1;
  }

  private static int indexOfUtf16Class(String text, int[] ranges, int start, int limit) {
    if (!Swar.supportsBmpCodeUnitRanges(ranges, 4) || nativeOrder() == BIG_ENDIAN) {
      return VectorScanProvider.UNSUPPORTED;
    }
    int scanLimit = Math.min(limit, text.length());
    int position = Math.max(0, start);
    int vectorLen = SHORT_SPECIES.length();
    int vecLimit = scanLimit - vectorLen;

    for (; position <= vecLimit; position += vectorLen) {
      ShortVector values = StringSupport.shortVectorFromString(SHORT_SPECIES, text, position);
      VectorMask<Short> matches = ShortVectorScan.matches(values, ranges);
      if (matches.anyTrue()) {
        int found = position + matches.firstTrue();
        return found < scanLimit ? found : -1;
      }
    }

    for (; position < scanLimit; position++) {
      char ch = text.charAt(position);
      if (ShortVectorScan.matches(ch, ranges)) {
        return position;
      }
    }
    return -1;
  }

  private static int indexOfIgnoreCaseLatin1(String text, String prefix, int start) {
    int prefixLen = prefix.length();
    int length = text.length();
    if (prefixLen == 0) {
      return Math.min(Math.max(0, start), length);
    }
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return VectorScanProvider.UNSUPPORTED;
      }
    }

    int pos = Math.max(0, start);
    long verificationWork = 0;
    long workLimit = WorkLimit.forRemaining(length - pos);

    int anchorOffset = RarityOracle.rarestAsciiOffset(prefix, prefixLen);
    char anchor = prefix.charAt(anchorOffset);
    byte low = (byte) toLowerCase(anchor);
    byte high = (byte) toUpperCase(anchor);

    // Fast scalar prologue to catch immediate matches without SIMD setup
    int scalarPrologueLimit = Math.min(length - prefixLen + 1, pos + Integer.BYTES);
    for (; pos < scalarPrologueLimit; pos++) {
      char c = text.charAt(pos + anchorOffset);
      if ((c == (low & 0xFF) || c == (high & 0xFF))
          && regionMatchesIgnoreCase(text, pos, prefix, prefixLen)) {
        return pos;
      }
      if (c == (low & 0xFF) || c == (high & 0xFF)) {
        verificationWork += prefixLen;
        if (WorkLimit.isExhausted(verificationWork, workLimit)) {
          return VectorScanProvider.UNSUPPORTED;
        }
      }
    }

    int vectorLen = BYTE_SPECIES.length();
    int limit = length - vectorLen;
    if (pos > limit) {
      int limitScalar = length - prefixLen;
      for (int p = Math.max(start, pos - anchorOffset); p <= limitScalar; p++) {
        char c = text.charAt(p + anchorOffset);
        if (c != (low & 0xFF) && c != (high & 0xFF)) {
          continue;
        }
        if (regionMatchesIgnoreCase(text, p, prefix, prefixLen)) {
          return p;
        }
        verificationWork += prefixLen;
        if (WorkLimit.isExhausted(verificationWork, workLimit)) {
          return VectorScanProvider.UNSUPPORTED;
        }
      }
      return -1;
    }

    ByteVector lowVec = ByteVector.broadcast(BYTE_SPECIES, low);
    ByteVector highVec = ByteVector.broadcast(BYTE_SPECIES, high);

    for (; pos <= limit; pos += vectorLen) {
      ByteVector inputVec = StringSupport.byteVectorFromString(BYTE_SPECIES, text, pos);
      VectorMask<Byte> matchMask = inputVec.compare(EQ, lowVec).or(inputVec.compare(EQ, highVec));

      if (matchMask.anyTrue()) {
        long activeLanes = matchMask.toLong();
        while (activeLanes != 0) {
          int bit = Long.numberOfTrailingZeros(activeLanes);
          int candidatePos = pos + bit - anchorOffset;
          if (WorkLimit.candidateInBounds(candidatePos, start, length, prefixLen)
              && regionMatchesIgnoreCase(text, candidatePos, prefix, prefixLen)) {
            return candidatePos;
          }
          if (WorkLimit.candidateInBounds(candidatePos, start, length, prefixLen)) {
            verificationWork += prefixLen;
            if (WorkLimit.isExhausted(verificationWork, workLimit)) {
              return VectorScanProvider.UNSUPPORTED;
            }
          }
          activeLanes &= activeLanes - 1;
        }
      }
    }

    int limitScalar = length - prefixLen;
    for (int p = Math.max(start, pos - anchorOffset); p <= limitScalar; p++) {
      char c = text.charAt(p + anchorOffset);
      if (c != (low & 0xFF) && c != (high & 0xFF)) {
        continue;
      }
      if (regionMatchesIgnoreCase(text, p, prefix, prefixLen)) {
        return p;
      }
      verificationWork += prefixLen;
      if (WorkLimit.isExhausted(verificationWork, workLimit)) {
        return VectorScanProvider.UNSUPPORTED;
      }
    }
    return -1;
  }

  private static int indexOfIgnoreCaseUtf16(String text, String prefix, int start) {
    int prefixLen = prefix.length();
    int length = text.length();
    if (prefixLen == 0) {
      return Math.min(Math.max(0, start), length);
    }
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return VectorScanProvider.UNSUPPORTED;
      }
    }
    if (nativeOrder() == BIG_ENDIAN) {
      return VectorScanProvider.UNSUPPORTED;
    }

    int pos = Math.max(0, start);
    long verificationWork = 0;
    long workLimit = WorkLimit.forRemaining(length - pos);

    int anchorOffset = RarityOracle.rarestAsciiOffset(prefix, prefixLen);
    char anchor = prefix.charAt(anchorOffset);
    short low = (short) toLowerCase(anchor);
    short high = (short) toUpperCase(anchor);

    int vectorLen = SHORT_SPECIES.length();
    int limit = length - vectorLen;
    if (pos > limit) {
      int limitScalar = length - prefixLen;
      for (int p = Math.max(start, pos - anchorOffset); p <= limitScalar; p++) {
        char c = text.charAt(p + anchorOffset);
        if (c != low && c != high) {
          continue;
        }
        if (regionMatchesIgnoreCase(text, p, prefix, prefixLen)) {
          return p;
        }
        verificationWork += prefixLen;
        if (WorkLimit.isExhausted(verificationWork, workLimit)) {
          return VectorScanProvider.UNSUPPORTED;
        }
      }
      return -1;
    }

    ShortVector lowVec = ShortVector.broadcast(SHORT_SPECIES, low);
    ShortVector highVec = ShortVector.broadcast(SHORT_SPECIES, high);

    for (; pos <= limit; pos += vectorLen) {
      ShortVector inputVec = StringSupport.shortVectorFromString(SHORT_SPECIES, text, pos);
      VectorMask<Short> matchMask = inputVec.compare(EQ, lowVec).or(inputVec.compare(EQ, highVec));

      if (matchMask.anyTrue()) {
        long activeLanes = matchMask.toLong();
        while (activeLanes != 0) {
          int bit = Long.numberOfTrailingZeros(activeLanes);
          int candidatePos = pos + bit - anchorOffset;
          if (WorkLimit.candidateInBounds(candidatePos, start, length, prefixLen)
              && regionMatchesIgnoreCase(text, candidatePos, prefix, prefixLen)) {
            return candidatePos;
          }
          if (WorkLimit.candidateInBounds(candidatePos, start, length, prefixLen)) {
            verificationWork += prefixLen;
            if (WorkLimit.isExhausted(verificationWork, workLimit)) {
              return VectorScanProvider.UNSUPPORTED;
            }
          }
          activeLanes &= activeLanes - 1;
        }
      }
    }

    int limitScalar = length - prefixLen;
    for (int p = Math.max(start, pos - anchorOffset); p <= limitScalar; p++) {
      char c = text.charAt(p + anchorOffset);
      if (c != low && c != high) {
        continue;
      }
      if (regionMatchesIgnoreCase(text, p, prefix, prefixLen)) {
        return p;
      }
      verificationWork += prefixLen;
      if (WorkLimit.isExhausted(verificationWork, workLimit)) {
        return VectorScanProvider.UNSUPPORTED;
      }
    }
    return -1;
  }

  private static int[] clampRangesForLatin1(int[] ranges) {
    int numRanges = ranges.length / 2;
    int[] clamped = new int[ranges.length];
    int writeIdx = 0;
    for (int r = 0; r < numRanges; r++) {
      int low = ranges[r * 2];
      int high = ranges[r * 2 + 1];
      if (low > 255) {
        continue;
      }
      int clampedHigh = Math.min(high, 255);
      if (low <= clampedHigh) {
        clamped[writeIdx++] = low;
        clamped[writeIdx++] = clampedHigh;
      }
    }
    if (writeIdx == 0) {
      return null;
    }
    if (writeIdx < ranges.length) {
      return Arrays.copyOf(clamped, writeIdx);
    }
    return clamped;
  }

  static int indexOfMultiLiteral(
      String text,
      String[] literals,
      char[] anchorChars,
      int[] anchorOffsets,
      int[] anchorRanges,
      int minLength,
      int start) {
    if (text == null || literals == null || literals.length == 0 || text.length() < minLength) {
      return -1;
    }
    if (StringSupport.isLatin1(text)) {
      if (anchorRanges == null) {
        AsciiBitmap.Builder builder = new AsciiBitmap.Builder();
        for (char c : anchorChars) {
          builder.add(c);
        }
        anchorRanges = builder.build().toRanges();
      }
      return ByteVectorScan.indexOfMultiLiteral(
          StringSupport.value(text),
          0,
          text.length(),
          literals,
          anchorChars,
          anchorOffsets,
          anchorRanges,
          minLength,
          start);
    }
    if (StringSupport.isUtf16(text)) {
      return indexOfMultiLiteralUtf16(text, literals, anchorChars, anchorOffsets, minLength, start);
    }
    if (text.length() - start >= StringChunkBuffer.MIN_CHUNK_THRESHOLD) {
      return indexOfMultiLiteralChunked(
          text, literals, anchorChars, anchorOffsets, minLength, start);
    }
    return VectorScanProvider.UNSUPPORTED;
  }

  static int indexOfMultiLiteral(
      String text,
      String[] literals,
      char[] anchorChars,
      int[] anchorOffsets,
      int minLength,
      int start) {
    return indexOfMultiLiteral(text, literals, anchorChars, anchorOffsets, null, minLength, start);
  }

  private static int indexOfMultiLiteralUtf16(
      String text,
      String[] literals,
      char[] anchorChars,
      int[] anchorOffsets,
      int minLength,
      int start) {
    if (nativeOrder() == BIG_ENDIAN) {
      return VectorScanProvider.UNSUPPORTED;
    }
    int numLits = literals.length;
    int length = text.length();
    int pos = Math.max(0, start);
    int vectorLen = SHORT_SPECIES.length();
    int limit = length - vectorLen;

    ShortVector v0 = ShortVector.broadcast(SHORT_SPECIES, (short) anchorChars[0]);
    ShortVector v1 =
        numLits >= 2 ? ShortVector.broadcast(SHORT_SPECIES, (short) anchorChars[1]) : null;
    ShortVector v2 =
        numLits >= 3 ? ShortVector.broadcast(SHORT_SPECIES, (short) anchorChars[2]) : null;
    ShortVector v3 =
        numLits >= 4 ? ShortVector.broadcast(SHORT_SPECIES, (short) anchorChars[3]) : null;

    for (; pos <= limit; pos += vectorLen) {
      ShortVector inputVec = StringSupport.shortVectorFromString(SHORT_SPECIES, text, pos);
      VectorMask<Short> matchMask = inputVec.compare(EQ, v0);
      if (numLits >= 2) {
        matchMask = matchMask.or(inputVec.compare(EQ, v1));
      }
      if (numLits >= 3) {
        matchMask = matchMask.or(inputVec.compare(EQ, v2));
      }
      if (numLits >= 4) {
        matchMask = matchMask.or(inputVec.compare(EQ, v3));
      }

      if (matchMask.anyTrue()) {
        long activeLanes = matchMask.toLong();
        while (activeLanes != 0) {
          int bit = Long.numberOfTrailingZeros(activeLanes);
          int matchIndex = pos + bit;
          for (int i = 0; i < numLits; i++) {
            int candidatePos = matchIndex - anchorOffsets[i];
            String lit = literals[i];
            if (candidatePos >= start
                && candidatePos + lit.length() <= length
                && text.charAt(matchIndex) == anchorChars[i]
                && text.startsWith(lit, candidatePos)) {
              return candidatePos;
            }
          }
          activeLanes &= activeLanes - 1;
        }
      }
    }

    int scalarLimit = length - minLength;
    for (; pos <= scalarLimit; pos++) {
      for (int i = 0; i < numLits; i++) {
        String lit = literals[i];
        if (pos + lit.length() <= length && text.startsWith(lit, pos)) {
          return pos;
        }
      }
    }
    return -1;
  }

  static int indexOfTeddy(String text, TeddyModel model, int start) {
    if (text == null || model == null || text.length() < model.minLength()) {
      return -1;
    }
    if (!StringSupport.hasAccess()) {
      return VectorScanProvider.UNSUPPORTED;
    }
    if (StringSupport.isLatin1(text)) {
      return TeddyVectorScan.indexOfTeddyUtf8(
          StringSupport.value(text), 0, text.length(), model, start);
    }
    if (StringSupport.isUtf16(text) && nativeOrder() != BIG_ENDIAN) {
      return TeddyVectorScan.indexOfTeddyUtf16(text, model, start);
    }
    return VectorScanProvider.UNSUPPORTED;
  }

  private static int indexOfCharClassChunked(String text, int[] ranges, int start, int limit) {
    if (!Swar.supportsBmpCodeUnitRanges(ranges, 4)) {
      return VectorScanProvider.UNSUPPORTED;
    }
    int scanLimit = Math.min(limit, text.length());
    int pos = Math.max(0, start);
    char[] chunk = StringChunkBuffer.get();

    while (pos < scanLimit) {
      int chunkSize = StringChunkBuffer.copyChunk(text, pos, scanLimit, chunk);
      int matchInChunk = scanChunkCharClass(chunk, chunkSize, ranges);
      if (matchInChunk >= 0) {
        return pos + matchInChunk;
      }
      if (chunkSize < StringChunkBuffer.CHUNK_SIZE) {
        break;
      }
      pos += chunkSize;
    }
    return -1;
  }

  private static int indexOfAsciiPairChunked(
      String text, int c1, int c2, int fromIndex, int limit) {
    int scanLimit = Math.min(limit, text.length());
    int pos = Math.max(0, fromIndex);
    char[] chunk = StringChunkBuffer.get();

    while (pos < scanLimit) {
      int chunkSize = StringChunkBuffer.copyChunk(text, pos, scanLimit, chunk);
      int matchInChunk = scanChunkAsciiPair(chunk, chunkSize, c1, c2);
      if (matchInChunk >= 0) {
        return pos + matchInChunk;
      }
      if (chunkSize < StringChunkBuffer.CHUNK_SIZE) {
        break;
      }
      pos += chunkSize;
    }
    return -1;
  }

  private static int indexOfAsciiTripleChunked(
      String text, int c1, int c2, int c3, int fromIndex, int limit) {
    int scanLimit = Math.min(limit, text.length());
    int pos = Math.max(0, fromIndex);
    char[] chunk = StringChunkBuffer.get();

    while (pos < scanLimit) {
      int chunkSize = StringChunkBuffer.copyChunk(text, pos, scanLimit, chunk);
      int matchInChunk = scanChunkAsciiTriple(chunk, chunkSize, c1, c2, c3);
      if (matchInChunk >= 0) {
        return pos + matchInChunk;
      }
      if (chunkSize < StringChunkBuffer.CHUNK_SIZE) {
        break;
      }
      pos += chunkSize;
    }
    return -1;
  }

  private static int indexOfMultiLiteralChunked(
      String text,
      String[] literals,
      char[] anchorChars,
      int[] anchorOffsets,
      int minLength,
      int start) {
    int scanLimit = text.length();
    int pos = Math.max(0, start);
    char[] chunk = StringChunkBuffer.get();
    int overlap = minLength - 1;

    while (pos < scanLimit) {
      int chunkSize = StringChunkBuffer.copyChunk(text, pos, scanLimit, chunk);
      int matchInChunk =
          scanChunkMultiLiteral(chunk, chunkSize, literals, anchorChars, anchorOffsets, minLength);
      if (matchInChunk >= 0) {
        return pos + matchInChunk;
      }
      if (chunkSize < StringChunkBuffer.CHUNK_SIZE) {
        break;
      }
      pos += chunkSize - overlap;
    }
    return -1;
  }

  private static int scanChunkCharClass(char[] chunk, int chunkSize, int[] ranges) {
    int vecLimit = chunkSize - SHORT_SPECIES.length();
    int p = 0;
    for (; p <= vecLimit; p += SHORT_SPECIES.length()) {
      ShortVector v = ShortVector.fromCharArray(SHORT_SPECIES, chunk, p);
      VectorMask<Short> m = ShortVectorScan.matches(v, ranges);
      if (m.anyTrue()) {
        return p + m.firstTrue();
      }
    }
    for (; p < chunkSize; p++) {
      if (ShortVectorScan.matches(chunk[p], ranges)) {
        return p;
      }
    }
    return -1;
  }

  private static int scanChunkAsciiPair(char[] chunk, int chunkSize, int c1, int c2) {
    int vecLimit = chunkSize - SHORT_SPECIES.length();
    int p = 0;
    ShortVector v1 = ShortVector.broadcast(SHORT_SPECIES, (short) c1);
    ShortVector v2 = ShortVector.broadcast(SHORT_SPECIES, (short) c2);

    for (; p <= vecLimit; p += SHORT_SPECIES.length()) {
      ShortVector inputVec = ShortVector.fromCharArray(SHORT_SPECIES, chunk, p);
      VectorMask<Short> matchMask = inputVec.compare(EQ, v1).or(inputVec.compare(EQ, v2));
      if (matchMask.anyTrue()) {
        return p + matchMask.firstTrue();
      }
    }
    for (; p < chunkSize; p++) {
      char c = chunk[p];
      if (c == c1 || c == c2) {
        return p;
      }
    }
    return -1;
  }

  private static int scanChunkAsciiTriple(char[] chunk, int chunkSize, int c1, int c2, int c3) {
    int vecLimit = chunkSize - SHORT_SPECIES.length();
    int p = 0;
    ShortVector v1 = ShortVector.broadcast(SHORT_SPECIES, (short) c1);
    ShortVector v2 = ShortVector.broadcast(SHORT_SPECIES, (short) c2);
    ShortVector v3 = ShortVector.broadcast(SHORT_SPECIES, (short) c3);

    for (; p <= vecLimit; p += SHORT_SPECIES.length()) {
      ShortVector inputVec = ShortVector.fromCharArray(SHORT_SPECIES, chunk, p);
      VectorMask<Short> matchMask =
          inputVec.compare(EQ, v1).or(inputVec.compare(EQ, v2)).or(inputVec.compare(EQ, v3));
      if (matchMask.anyTrue()) {
        return p + matchMask.firstTrue();
      }
    }
    for (; p < chunkSize; p++) {
      char c = chunk[p];
      if (c == c1 || c == c2 || c == c3) {
        return p;
      }
    }
    return -1;
  }

  private static int scanChunkMultiLiteral(
      char[] chunk,
      int chunkSize,
      String[] literals,
      char[] anchorChars,
      int[] anchorOffsets,
      int minLength) {
    int numLits = literals.length;
    int vecLimit = chunkSize - SHORT_SPECIES.length();
    int p = 0;

    ShortVector v0 = ShortVector.broadcast(SHORT_SPECIES, (short) anchorChars[0]);
    ShortVector v1 =
        numLits >= 2 ? ShortVector.broadcast(SHORT_SPECIES, (short) anchorChars[1]) : null;
    ShortVector v2 =
        numLits >= 3 ? ShortVector.broadcast(SHORT_SPECIES, (short) anchorChars[2]) : null;
    ShortVector v3 =
        numLits >= 4 ? ShortVector.broadcast(SHORT_SPECIES, (short) anchorChars[3]) : null;

    for (; p <= vecLimit; p += SHORT_SPECIES.length()) {
      ShortVector inputVec = ShortVector.fromCharArray(SHORT_SPECIES, chunk, p);
      VectorMask<Short> matchMask = inputVec.compare(EQ, v0);
      if (numLits >= 2) {
        matchMask = matchMask.or(inputVec.compare(EQ, v1));
      }
      if (numLits >= 3) {
        matchMask = matchMask.or(inputVec.compare(EQ, v2));
      }
      if (numLits >= 4) {
        matchMask = matchMask.or(inputVec.compare(EQ, v3));
      }

      if (matchMask.anyTrue()) {
        long activeLanes = matchMask.toLong();
        while (activeLanes != 0) {
          int bit = Long.numberOfTrailingZeros(activeLanes);
          int matchIndex = p + bit;
          for (int i = 0; i < numLits; i++) {
            int candidatePos = matchIndex - anchorOffsets[i];
            String lit = literals[i];
            if (candidatePos >= 0
                && candidatePos + lit.length() <= chunkSize
                && chunk[matchIndex] == anchorChars[i]
                && regionMatches(chunk, candidatePos, lit)) {
              return candidatePos;
            }
          }
          activeLanes &= activeLanes - 1;
        }
      }
    }

    int scalarLimit = chunkSize - minLength;
    for (; p <= scalarLimit; p++) {
      for (int i = 0; i < numLits; i++) {
        String lit = literals[i];
        if (p + lit.length() <= chunkSize && regionMatches(chunk, p, lit)) {
          return p;
        }
      }
    }
    return -1;
  }

  private static boolean regionMatches(char[] chars, int offset, String str) {
    int len = str.length();
    for (int i = 0; i < len; i++) {
      if (chars[offset + i] != str.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  private StringVectorScan() {}
}
