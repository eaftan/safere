// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.charset.StandardCharsets.UTF_16BE;
import static java.nio.charset.StandardCharsets.UTF_16LE;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.charset.Charset;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * Access gateway to inspect internal backing storage of a {@link String} when {@code java.base} is
 * open, providing zero-copy vector access for String.
 */
final class StringSupport {
  private static final VarHandle VALUE_HANDLE;
  private static final VarHandle CODER_HANDLE;
  private static final boolean HAS_ACCESS;

  static {
    VarHandle valueHandle = null;
    VarHandle coderHandle = null;
    boolean accessible = false;
    try {
      Module baseModule = String.class.getModule();
      Module ourModule = StringSupport.class.getModule();
      if (baseModule.isOpen("java.lang", ourModule)) {
        MethodHandles.Lookup lookup =
            MethodHandles.privateLookupIn(String.class, MethodHandles.lookup());
        valueHandle = lookup.findVarHandle(String.class, "value", byte[].class);
        coderHandle = lookup.findVarHandle(String.class, "coder", byte.class);
        accessible = true;
      }
    } catch (Throwable ignored) {
      // java.base is not open or reflection failed
    }
    VALUE_HANDLE = valueHandle;
    CODER_HANDLE = coderHandle;
    HAS_ACCESS = accessible;
  }

  public static boolean hasAccess() {
    return HAS_ACCESS && !Boolean.getBoolean("org.safere.experimental.forceStringChunking");
  }

  public static boolean isLatin1(String str) {
    return hasAccess() && coder(str) == 0;
  }

  public static boolean isUtf16(String str) {
    return hasAccess() && coder(str) == 1;
  }

  public static boolean compatibleWith(String str, Charset charset) {
    if (!HAS_ACCESS) {
      return false;
    }
    if (charset.equals(ISO_8859_1) || charset.equals(US_ASCII)) {
      return coder(str) == 0;
    }
    if (charset.equals(UTF_16) || charset.equals(UTF_16LE) || charset.equals(UTF_16BE)) {
      return coder(str) == 1;
    }
    return false;
  }

  public static ByteVector byteVectorFromString(
      VectorSpecies<Byte> species, String str, int offset) {
    return ByteVector.fromArray(species, value(str), offset);
  }

  public static ShortVector shortVectorFromString(
      VectorSpecies<Short> species, String str, int offset) {
    return ByteVector.fromArray(species.withLanes(byte.class), value(str), offset << 1)
        .reinterpretAsShorts();
  }

  static byte[] value(String str) {
    if (!HAS_ACCESS) {
      throw new UnsupportedOperationException(
          "String internal array access not available; open java.base/java.lang to "
              + StringSupport.class.getModule().getName());
    }
    return (byte[]) VALUE_HANDLE.get(str);
  }

  static byte coder(String str) {
    if (!HAS_ACCESS) {
      throw new UnsupportedOperationException(
          "String internal array access not available; open java.base/java.lang to "
              + StringSupport.class.getModule().getName());
    }
    return (byte) CODER_HANDLE.get(str);
  }

  private StringSupport() {}
}
