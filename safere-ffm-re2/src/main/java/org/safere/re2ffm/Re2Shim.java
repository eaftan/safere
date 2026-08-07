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
import java.util.Locale;

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
  private static final MethodHandle FIND_ALL;
  private static final MethodHandle REPLACE_FIRST;
  private static final MethodHandle REPLACE_ALL;
  private static final MethodHandle REPLACE_LITERAL;

  static {
    // Load the native library from the build directory or system path.
    String libPath = System.getProperty("re2shim.library.path");
    SymbolLookup lookup;
    if (libPath != null) {
      String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
      String libName = osName.contains("mac") ? "libre2_shim.dylib" : "libre2_shim.so";
      lookup = SymbolLookup.libraryLookup(Path.of(libPath, libName), Arena.global());
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
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    // re2_pattern_t* re2_compile_case_insensitive(const char* pattern, int pattern_len)
    COMPILE_CASE_INSENSITIVE =
        linker.downcallHandle(
            lookup.find("re2_compile_case_insensitive").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    // void re2_free(re2_pattern_t* p)
    FREE =
        linker.downcallHandle(
            lookup.find("re2_free").orElseThrow(), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

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

    // int re2_replace_first(const re2_pattern_t* p, const char* text, int text_len,
    //                      const char* rewrite, int rewrite_len,
    //                      char* out_buf, int out_cap, int* out_len)
    REPLACE_FIRST =
        linker.downcallHandle(
            lookup.find("re2_replace_first").orElseThrow(),
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

    // int re2_find_all(const re2_pattern_t* p, const char* text, int text_len,
    //                  int32_t* matches_out, int max_matches)
    FIND_ALL =
        linker.downcallHandle(
            lookup.find("re2_find_all").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT));

    // void re2_replace_literal(const uint8_t* input, int input_len,
    //                          const int* groups, int groups_count,
    //                          const uint8_t* replacement, int replacement_len,
    //                          uint8_t* output)
    REPLACE_LITERAL =
        linker.downcallHandle(
            lookup.find("re2_replace_literal").orElseThrow(),
            FunctionDescriptor.ofVoid(
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

  static int findAll(
      MemorySegment handle,
      MemorySegment text,
      int textLen,
      MemorySegment matchesOut,
      int maxMatches) {
    try {
      return (int) FIND_ALL.invokeExact(handle, text, textLen, matchesOut, maxMatches);
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

  static void replaceLiteral(
      MemorySegment input,
      int inputLen,
      MemorySegment groups,
      int groupsCount,
      MemorySegment replacement,
      int replacementLen,
      MemorySegment output) {
    try {
      REPLACE_LITERAL.invokeExact(
          input, inputLen, groups, groupsCount, replacement, replacementLen, output);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  static int replaceFirst(
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
          REPLACE_FIRST.invokeExact(
              handle, text, textLen, rewrite, rewriteLen, outBuf, outCap, outLen);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  public record ReplaceResult(String result, int count) {}

  public static ReplaceResult replaceAll(
      MemorySegment re2Ptr, byte[] textBytes, String textString, byte[] rewriteBytes) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment textSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, textBytes);
      MemorySegment rewriteSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, rewriteBytes);
      MemorySegment outLenSeg = arena.allocate(ValueLayout.JAVA_INT);
      int cap = Math.max(textBytes.length * 2, 256);
      MemorySegment outBuf = arena.allocate(cap);
      int count =
          replaceAll(
              re2Ptr,
              textSeg,
              textBytes.length,
              rewriteSeg,
              rewriteBytes.length,
              outBuf,
              cap,
              outLenSeg);
      if (count < 0) {
        int needed = outLenSeg.get(ValueLayout.JAVA_INT, 0);
        outBuf = arena.allocate(needed);
        count =
            replaceAll(
                re2Ptr,
                textSeg,
                textBytes.length,
                rewriteSeg,
                rewriteBytes.length,
                outBuf,
                needed,
                outLenSeg);
      }
      if (count == 0) {
        return new ReplaceResult(textString, 0);
      }
      int actualLen = outLenSeg.get(ValueLayout.JAVA_INT, 0);
      byte[] resBytes = new byte[actualLen];
      MemorySegment.copy(outBuf, ValueLayout.JAVA_BYTE, 0, resBytes, 0, actualLen);
      return new ReplaceResult(
          new String(resBytes, java.nio.charset.StandardCharsets.UTF_8), count);
    }
  }
}
