// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.regex.PatternSyntaxException;
import org.safere.Matcher;
import org.safere.Pattern;
import org.safere.Utf8Input;

/** Exercises arbitrary syntax, UTF-16 text, and UTF-8 windows without a JDK regex oracle. */
public final class RobustnessFuzzer {
  @FuzzTest(maxDuration = "30s")
  void robustness(FuzzedDataProvider data) {
    fuzzerTestOneInput(data);
  }

  /** Shared entry point for local Jazzer and OSS-Fuzz. */
  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
    String regex;
    if (data.consumeBoolean()) {
      int depth = data.consumeInt(0, 512);
      regex = "(?:".repeat(depth) + "a" + ")".repeat(depth);
    } else {
      regex = data.consumeString(256);
    }
    Pattern pattern;
    try {
      pattern = Pattern.compile(regex, FuzzSupport.consumeFlags(data));
    } catch (PatternSyntaxException expected) {
      return;
    }
    String input = data.consumeString(512);
    Matcher matcher = pattern.matcher(input);
    matcher.matches();
    matcher.reset().lookingAt();
    matcher.reset();
    int previousEnd = -1;
    int attempts = 0;
    while (matcher.find()) {
      if (matcher.start() < 0
          || matcher.end() < matcher.start()
          || matcher.end() > input.length()
          || matcher.end() < previousEnd
          || ++attempts > input.length() + 1) {
        throw new AssertionError("invalid String match bounds or find progress");
      }
      previousEnd = matcher.end();
    }
    byte[] bytes = data.consumeBytes(1024);
    int offset = data.consumeInt(0, bytes.length);
    int length = data.consumeInt(0, bytes.length - offset);
    Utf8RobustnessChecks.walk(pattern.matcher(Utf8Input.trusted(bytes, offset, length)), length);
    boolean valid = Utf8RobustnessChecks.isValidUtf8(bytes, offset, length);
    Utf8Input validated;
    try {
      validated = Utf8Input.validated(bytes, offset, length);
    } catch (IllegalArgumentException exception) {
      if (valid) {
        throw new AssertionError("valid UTF-8 rejected", exception);
      }
      return;
    }
    if (!valid) {
      throw new AssertionError("malformed UTF-8 accepted");
    }
    // Keep matching outside the expected validation-exception handler.
    Utf8RobustnessChecks.walk(pattern.matcher(validated), length);
    pattern.find(validated);
  }
}
