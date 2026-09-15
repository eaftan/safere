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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.nio.charset.Charset;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * Access gateway to inspect internal backing storage of a {@link String}, providing zero-copy
 * vector access for String either via JDK vector methods (PR 32373 / JDK-8390049) or via reflective
 * access when {@code java.base} is open.
 */
final class StringSupport {
  private static final MethodHandle BYTE_COMPATIBLE_MH;
  private static final MethodHandle BYTE_FROM_STRING_MH;
  private static final MethodHandle SHORT_FROM_STRING_MH;
  private static final boolean HAS_JDK_VECTOR_STRING;

  private static final VarHandle VALUE_HANDLE;
  private static final VarHandle CODER_HANDLE;
  private static final boolean HAS_REFLECTIVE_ACCESS;

  static {
    MethodHandle byteCompat = null;
    MethodHandle byteFromStr = null;
    MethodHandle shortFromStr = null;
    boolean jdkVectorString = false;
    try {
      MethodHandles.Lookup lookup = MethodHandles.publicLookup();
      byteCompat =
          lookup.findStatic(
              ByteVector.class,
              "compatibleWith",
              MethodType.methodType(boolean.class, String.class, Charset.class));
      byteFromStr =
          lookup.findStatic(
              ByteVector.class,
              "fromString",
              MethodType.methodType(
                  ByteVector.class, VectorSpecies.class, String.class, Charset.class, int.class));
      shortFromStr =
          lookup.findStatic(
              ShortVector.class,
              "fromString",
              MethodType.methodType(
                  ShortVector.class, VectorSpecies.class, String.class, Charset.class, int.class));
      jdkVectorString = true;
    } catch (Throwable ignored) {
      // JDK does not have ByteVector.fromString / ShortVector.fromString
    }
    BYTE_COMPATIBLE_MH = byteCompat;
    BYTE_FROM_STRING_MH = byteFromStr;
    SHORT_FROM_STRING_MH = shortFromStr;
    HAS_JDK_VECTOR_STRING = jdkVectorString;

    VarHandle valueHandle = null;
    VarHandle coderHandle = null;
    boolean reflectiveAccess = false;
    try {
      Module baseModule = String.class.getModule();
      Module ourModule = StringSupport.class.getModule();
      if (baseModule.isOpen("java.lang", ourModule)) {
        MethodHandles.Lookup lookup =
            MethodHandles.privateLookupIn(String.class, MethodHandles.lookup());
        valueHandle = lookup.findVarHandle(String.class, "value", byte[].class);
        coderHandle = lookup.findVarHandle(String.class, "coder", byte.class);
        reflectiveAccess = true;
      }
    } catch (Throwable ignored) {
      // java.base is not open or reflection failed
    }
    VALUE_HANDLE = valueHandle;
    CODER_HANDLE = coderHandle;
    HAS_REFLECTIVE_ACCESS = reflectiveAccess;
  }

  public static boolean hasAccess() {
    return (HAS_JDK_VECTOR_STRING || HAS_REFLECTIVE_ACCESS)
        && !Boolean.getBoolean("org.safere.experimental.forceStringChunking");
  }

  public static boolean isLatin1(String str) {
    if (!hasAccess()) {
      return false;
    }
    if (HAS_JDK_VECTOR_STRING) {
      try {
        return (boolean) BYTE_COMPATIBLE_MH.invokeExact(str, ISO_8859_1);
      } catch (Throwable t) {
        return false;
      }
    }
    return coder(str) == 0;
  }

  public static boolean isUtf16(String str) {
    if (!hasAccess()) {
      return false;
    }
    if (HAS_JDK_VECTOR_STRING) {
      return !isLatin1(str);
    }
    return coder(str) == 1;
  }

  public static boolean compatibleWith(String str, Charset charset) {
    if (!hasAccess()) {
      return false;
    }
    if (charset.equals(ISO_8859_1) || charset.equals(US_ASCII)) {
      return isLatin1(str);
    }
    if (charset.equals(UTF_16) || charset.equals(UTF_16LE) || charset.equals(UTF_16BE)) {
      return isUtf16(str);
    }
    return false;
  }

  public static ByteVector byteVectorFromString(
      VectorSpecies<Byte> species, String str, int offset) {
    if (HAS_JDK_VECTOR_STRING) {
      try {
        return (ByteVector) BYTE_FROM_STRING_MH.invokeExact(species, str, ISO_8859_1, offset);
      } catch (Throwable t) {
        throw new AssertionError(t);
      }
    }
    return ByteVector.fromArray(species, (byte[]) VALUE_HANDLE.get(str), offset);
  }

  public static ShortVector shortVectorFromString(
      VectorSpecies<Short> species, String str, int offset) {
    if (HAS_JDK_VECTOR_STRING) {
      try {
        return (ShortVector) SHORT_FROM_STRING_MH.invokeExact(species, str, UTF_16, offset);
      } catch (Throwable t) {
        throw new AssertionError(t);
      }
    }
    return ByteVector.fromArray(
            species.withLanes(byte.class), (byte[]) VALUE_HANDLE.get(str), offset << 1)
        .reinterpretAsShorts();
  }

  static byte coder(String str) {
    if (!HAS_REFLECTIVE_ACCESS) {
      throw new UnsupportedOperationException(
          "String internal array access not available; open java.base/java.lang to "
              + StringSupport.class.getModule().getName());
    }
    return (byte) CODER_HANDLE.get(str);
  }

  private StringSupport() {}
}
