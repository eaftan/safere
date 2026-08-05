// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

/**
 * Accesses java.lang.String internals using MethodHandles to retrieve the backing byte array and
 * loader vectors without copying. Requires {@code --add-opens java.base/java.lang=ALL-UNNAMED} at
 * runtime.
 */
final class StringUnsafeLoader {
  private static final VarHandle VALUE_HANDLE;
  private static final VarHandle CODER_HANDLE;
  private static final byte CODER_LATIN1 = 0;

  static {
    try {
      MethodHandles.Lookup lookup =
          MethodHandles.privateLookupIn(String.class, MethodHandles.lookup());
      VALUE_HANDLE = lookup.findVarHandle(String.class, "value", byte[].class);
      CODER_HANDLE = lookup.findVarHandle(String.class, "coder", byte.class);
    } catch (Exception e) {
      throw new RuntimeException("Failed to access String internals via reflection", e);
    }
  }

  private static VectorSpecies<Byte> getByteSpecies(VectorShape shape) {
    if (shape == VectorShape.S_64_BIT) return ByteVector.SPECIES_64;
    if (shape == VectorShape.S_128_BIT) return ByteVector.SPECIES_128;
    if (shape == VectorShape.S_256_BIT) return ByteVector.SPECIES_256;
    if (shape == VectorShape.S_512_BIT) return ByteVector.SPECIES_512;
    throw new IllegalArgumentException("Unsupported shape: " + shape);
  }

  public static byte[] getBackingArray(String s) {
    return (byte[]) VALUE_HANDLE.get(s);
  }

  public static byte getCoder(String s) {
    return (byte) CODER_HANDLE.get(s);
  }

  /**
   * Loads a ShortVector (of the requested species) from the String starting at the character
   * offset. If the string is stored as Latin-1 bytes, loads them and inflates them to shorts in
   * registers. If UTF-16, loads shorts directly.
   */
  public static ShortVector loadShortVector(
      VectorSpecies<Short> species, String s, int charOffset) {
    byte[] value = getBackingArray(s);
    byte coder = getCoder(s);

    if (coder == CODER_LATIN1) {
      // Get ByteVector species of the same shape (same bit size)
      VectorSpecies<Byte> byteSpecies = getByteSpecies(species.vectorShape());
      // Load bytes
      ByteVector byteVec = ByteVector.fromArray(byteSpecies, value, charOffset);
      // Zero-extend/inflate to ShortVector of the target species
      return (ShortVector) byteVec.convert(VectorOperators.B2S, 0);
    } else {
      // UTF-16 characters are 2 bytes each, so offset is charOffset * 2
      MemorySegment segment = MemorySegment.ofArray(value);
      return ShortVector.fromMemorySegment(
          species, segment, (long) charOffset * 2, ByteOrder.nativeOrder());
    }
  }
}
