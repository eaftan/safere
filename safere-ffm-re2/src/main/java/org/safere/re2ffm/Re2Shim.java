// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.re2ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * Low-level FFM bindings to the re2_shim native library. Each static method corresponds to a C
 * function in re2_shim.h.
 */
final class Re2Shim {

  private static final MethodHandle COMPILE;
  private static final MethodHandle COMPILE_CASE_INSENSITIVE;
  private static final MethodHandle FREE;
  private static final MethodHandle OK;
  private static final MethodHandle ERROR;
  private static final MethodHandle NUM_CAPTURING_GROUPS;
  private static final MethodHandle FULL_MATCH;
  private static final MethodHandle FIND;
  private static final MethodHandle REPLACE_ALL;

  static {
    // Load the native library from the build directory or system path.
    String libPath = System.getProperty("re2shim.library.path");
    SymbolLookup lookup;
    if (libPath != null) {
      lookup =
          SymbolLookup.libraryLookup(Path.of(libPath, "libre2_shim.so"), Arena.global());
    } else {
      // Fall back to system library path
      System.loadLibrary("re2_shim");
      lookup = SymbolLookup.loaderLookup();
    }

    Linker linker = Linker.nativeLinker();

    // re2_pattern_t* re2_compile(const char* pattern, int pattern_len)
    COMPILE =
        linker.downcallHandle(
            lookup.find("re2_compile").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    // re2_pattern_t* re2_compile_case_insensitive(const char* pattern, int pattern_len)
    COMPILE_CASE_INSENSITIVE =
        linker.downcallHandle(
            lookup.find("re2_compile_case_insensitive").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    // void re2_free(re2_pattern_t* p)
    FREE =
        linker.downcallHandle(
            lookup.find("re2_free").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    // bool re2_ok(const re2_pattern_t* p)
    OK =
        linker.downcallHandle(
            lookup.find("re2_ok").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS));

    // const char* re2_error(const re2_pattern_t* p)
    ERROR =
        linker.downcallHandle(
            lookup.find("re2_error").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // int re2_num_capturing_groups(const re2_pattern_t* p)
    NUM_CAPTURING_GROUPS =
        linker.downcallHandle(
            lookup.find("re2_num_capturing_groups").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    // bool re2_full_match(const re2_pattern_t* p, const char* text, int text_len)
    FULL_MATCH =
        linker.downcallHandle(
            lookup.find("re2_full_match").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT));

    // bool re2_find(const re2_pattern_t* p, const char* text, int text_len,
    //              int startpos, int32_t* matches_out, int nmatches)
    FIND =
        linker.downcallHandle(
            lookup.find("re2_find").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT));

    // int re2_replace_all(const re2_pattern_t* p, const char* text, int text_len,
    //                    const char* rewrite, int rewrite_len,
    //                    char* out_buf, int out_cap, int* out_len)
    REPLACE_ALL =
        linker.downcallHandle(
            lookup.find("re2_replace_all").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS));
  }

  private Re2Shim() {}

  static MemorySegment compile(MemorySegment pattern, int patternLen) {
    try {
      return (MemorySegment) COMPILE.invokeExact(pattern, patternLen);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  static MemorySegment compileCaseInsensitive(MemorySegment pattern, int patternLen) {
    try {
      return (MemorySegment) COMPILE_CASE_INSENSITIVE.invokeExact(pattern, patternLen);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  static void free(MemorySegment handle) {
    try {
      FREE.invokeExact(handle);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  static boolean ok(MemorySegment handle) {
    try {
      return (boolean) OK.invokeExact(handle);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  static String error(MemorySegment handle) {
    try {
      MemorySegment errPtr = (MemorySegment) ERROR.invokeExact(handle);
      return errPtr.reinterpret(1024).getString(0);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  static int numCapturingGroups(MemorySegment handle) {
    try {
      return (int) NUM_CAPTURING_GROUPS.invokeExact(handle);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  static boolean fullMatch(MemorySegment handle, MemorySegment text, int textLen) {
    try {
      return (boolean) FULL_MATCH.invokeExact(handle, text, textLen);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  static boolean find(
      MemorySegment handle,
      MemorySegment text,
      int textLen,
      int startpos,
      MemorySegment matchesOut,
      int nmatches) {
    try {
      return (boolean) FIND.invokeExact(handle, text, textLen, startpos, matchesOut, nmatches);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  static int replaceAll(
      MemorySegment handle,
      MemorySegment text,
      int textLen,
      MemorySegment rewrite,
      int rewriteLen,
      MemorySegment outBuf,
      int outCap,
      MemorySegment outLen) {
    try {
      return (int)
          REPLACE_ALL.invokeExact(
              handle, text, textLen, rewrite, rewriteLen, outBuf, outCap, outLen);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }
}
