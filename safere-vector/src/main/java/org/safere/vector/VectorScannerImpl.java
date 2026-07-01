// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.vector;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;

/** Implementation of VectorScannerBridge using the Java Vector API. */
public final class VectorScannerImpl implements VectorScannerBridge {
  private static final VectorSpecies<Short> SHORT_SPECIES = ShortVector.SPECIES_PREFERRED;
  private static final VectorSpecies<Byte> BYTE_SPECIES = ByteVector.SPECIES_PREFERRED;

  private static final VarHandle VALUE_HANDLE;
  private static final VarHandle CODER_HANDLE;

  static {
    VarHandle valHandle = null;
    VarHandle codHandle = null;
    try {
      MethodHandles.Lookup lookup =
          MethodHandles.privateLookupIn(String.class, MethodHandles.lookup());
      valHandle = lookup.findVarHandle(String.class, "value", byte[].class);
      codHandle = lookup.findVarHandle(String.class, "coder", byte.class);
    } catch (Throwable t) {
      valHandle = null;
      codHandle = null;
    }
    VALUE_HANDLE = valHandle;
    CODER_HANDLE = codHandle;
  }

  @Override
  public int scan(char[] array, char target, int fromIndex, int toIndex) {
    int length = toIndex;
    int limit = fromIndex + SHORT_SPECIES.loopBound(length - fromIndex);
    short targetShort = (short) target;

    MemorySegment segment = MemorySegment.ofArray(array);

    int i = fromIndex;
    for (; i < limit; i += SHORT_SPECIES.length()) {
      long byteOffset = (long) i * 2;
      ShortVector v =
          ShortVector.fromMemorySegment(
              SHORT_SPECIES, segment, byteOffset, ByteOrder.nativeOrder());
      VectorMask<Short> mask = v.eq(targetShort);
      if (mask.anyTrue()) {
        return i + mask.firstTrue();
      }
    }
    // Clean up tail
    for (; i < length; i++) {
      if (array[i] == target) {
        return i;
      }
    }
    return -1;
  }

  @Override
  public int scanString(String text, char target, int fromIndex, int toIndex) {
    if (VALUE_HANDLE == null || CODER_HANDLE == null) {
      return -2; // Not supported
    }

    byte[] value;
    byte coder;
    try {
      value = (byte[]) VALUE_HANDLE.get(text);
      coder = (byte) CODER_HANDLE.get(text);
    } catch (Throwable t) {
      return -2; // Fallback
    }

    if (coder == 0) { // Latin-1
      if (target >= 256) {
        return -1; // Latin-1 string can never match a non-Latin-1 character
      }
      byte targetByte = (byte) target;
      int limit = fromIndex + BYTE_SPECIES.loopBound(toIndex - fromIndex);
      MemorySegment segment = MemorySegment.ofArray(value);

      int i = fromIndex;
      for (; i < limit; i += BYTE_SPECIES.length()) {
        ByteVector v =
            ByteVector.fromMemorySegment(BYTE_SPECIES, segment, (long) i, ByteOrder.nativeOrder());
        VectorMask<Byte> mask = v.eq(targetByte);
        if (mask.anyTrue()) {
          return i + mask.firstTrue();
        }
      }
      for (; i < toIndex; i++) {
        if (value[i] == targetByte) {
          return i;
        }
      }
      return -1;
    } else { // UTF-16
      short targetShort = (short) target;
      int limit = fromIndex + SHORT_SPECIES.loopBound(toIndex - fromIndex);
      MemorySegment segment = MemorySegment.ofArray(value);

      int i = fromIndex;
      for (; i < limit; i += SHORT_SPECIES.length()) {
        long byteOffset = (long) i * 2;
        ShortVector v =
            ShortVector.fromMemorySegment(
                SHORT_SPECIES, segment, byteOffset, ByteOrder.nativeOrder());
        VectorMask<Short> mask = v.eq(targetShort);
        if (mask.anyTrue()) {
          return i + mask.firstTrue();
        }
      }
      for (; i < toIndex; i++) {
        int byteOffset = i * 2;
        char ch;
        if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
          ch = (char) ((value[byteOffset] & 0xFF) | ((value[byteOffset + 1] & 0xFF) << 8));
        } else {
          ch = (char) (((value[byteOffset] & 0xFF) << 8) | (value[byteOffset + 1] & 0xFF));
        }
        if (ch == target) {
          return i;
        }
      }
      return -1;
    }
  }
}
