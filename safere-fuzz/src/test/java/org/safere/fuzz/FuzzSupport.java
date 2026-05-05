// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import java.util.List;
import java.util.regex.PatternSyntaxException;
import org.safere.crosscheck.Pattern;

final class FuzzSupport {

  private static final int[] FLAGS = {
      Pattern.UNIX_LINES,
      Pattern.CASE_INSENSITIVE,
      Pattern.COMMENTS,
      Pattern.MULTILINE,
      Pattern.LITERAL,
      Pattern.DOTALL,
      Pattern.UNICODE_CASE,
      Pattern.UNICODE_CHARACTER_CLASS
  };

  private FuzzSupport() {}

  static org.safere.Pattern compileCompatibleOrSkip(String regex, int flags) {
    org.safere.Pattern safeRePattern = null;
    java.util.regex.Pattern jdkPattern = null;
    PatternSyntaxException safeReException = null;
    PatternSyntaxException jdkException = null;

    try {
      safeRePattern = org.safere.Pattern.compile(regex, flags);
    } catch (PatternSyntaxException e) {
      safeReException = e;
    }
    try {
      jdkPattern = java.util.regex.Pattern.compile(regex, flags);
    } catch (PatternSyntaxException e) {
      jdkException = e;
    }

    if (safeRePattern != null && jdkPattern != null) {
      return safeRePattern;
    }
    if (safeReException != null && jdkException != null) {
      return null;
    }
    if (safeReException != null && isIntentionallyUnsupported(regex)) {
      return null;
    }

    String safeRe = safeReException == null
        ? "compiled successfully"
        : safeReException.getClass().getSimpleName() + ": " + safeReException.getMessage();
    String jdk = jdkException == null
        ? "compiled successfully"
        : jdkException.getClass().getSimpleName() + ": " + jdkException.getMessage();
    throw new AssertionError("compile divergence for /" + regex + "/ flags=" + flags
        + "\nSafeRE: " + safeRe + "\nJDK: " + jdk);
  }

  static void assertFullMatchesJdk(String regex, int flags, List<String> inputs) {
    org.safere.Pattern safeRePattern = compileCompatibleOrSkip(regex, flags);
    if (safeRePattern == null) {
      return;
    }
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex, flags);
    for (String input : inputs) {
      boolean safeReMatches = safeRePattern.matcher(input).matches();
      boolean jdkMatches = jdkPattern.matcher(input).matches();
      if (safeReMatches != jdkMatches) {
        throw new AssertionError("matches() divergence for /" + regex + "/ flags=" + flags
            + " input=\"" + input + "\"\nSafeRE: " + safeReMatches + "\nJDK: " + jdkMatches);
      }
    }
  }

  static Pattern compileOrSkip(String regex, int flags) {
    try {
      return Pattern.compile(regex, flags);
    } catch (PatternSyntaxException expected) {
      return null;
    }
  }

  static int consumeFlags(FuzzedDataProvider data) {
    int flags = 0;
    for (int flag : FLAGS) {
      if (data.consumeBoolean()) {
        flags |= flag;
      }
    }
    return flags;
  }

  static int consumeParserFlags(FuzzedDataProvider data) {
    int flags = 0;
    if (data.consumeBoolean()) {
      flags |= Pattern.COMMENTS;
    }
    if (data.consumeBoolean()) {
      flags |= Pattern.CASE_INSENSITIVE;
    }
    if (data.consumeBoolean()) {
      flags |= Pattern.UNICODE_CASE;
    }
    if (data.consumeBoolean()) {
      flags |= Pattern.UNICODE_CHARACTER_CLASS;
    }
    return flags;
  }

  private static boolean isIntentionallyUnsupported(String regex) {
    return hasLookaround(regex) || hasBackreference(regex) || hasPossessiveQuantifier(regex);
  }

  private static boolean hasLookaround(String regex) {
    return regex.contains("(?=")
        || regex.contains("(?!")
        || regex.contains("(?<=")
        || regex.contains("(?<!");
  }

  private static boolean hasBackreference(String regex) {
    return regex.matches(".*\\\\[1-9].*")
        || regex.contains("\\k<")
        || regex.contains("\\g{")
        || regex.contains("\\g");
  }

  private static boolean hasPossessiveQuantifier(String regex) {
    for (int i = 1; i < regex.length(); i++) {
      if (regex.charAt(i) == '+' && isPossessiveQuantifierPrefix(regex.charAt(i - 1))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isPossessiveQuantifierPrefix(char c) {
    return c == '?' || c == '*' || c == '+' || c == '}';
  }

  static int consumeIndex(FuzzedDataProvider data, String input) {
    return data.consumeInt(0, input.length());
  }

  static int[] consumeRegion(FuzzedDataProvider data, String input) {
    int start = consumeIndex(data, input);
    int end = data.consumeInt(start, input.length());
    return new int[] {start, end};
  }

  static String consumeUnicodeHeavyString(FuzzedDataProvider data, int maxCodePoints) {
    int codePoints = data.consumeInt(0, maxCodePoints);
    StringBuilder sb = new StringBuilder(codePoints);
    for (int i = 0; i < codePoints; i++) {
      switch (data.consumeInt(0, 9)) {
        case 0 -> sb.append((char) data.consumeInt('a', 'z'));
        case 1 -> sb.append((char) data.consumeInt('0', '9'));
        case 2 -> sb.append('\n');
        case 3 -> sb.append('\r');
        case 4 -> sb.append('\u2028');
        case 5 -> sb.append('\u0301');
        case 6 -> sb.append(Character.toChars(data.consumeInt(0x10000, 0x10FFFF)));
        case 7 -> sb.append((char) data.consumeInt(0xD800, 0xDBFF));
        case 8 -> sb.append((char) data.consumeInt(0xDC00, 0xDFFF));
        case 9 -> sb.append((char) data.consumeInt(0x80, 0xFFFD));
        default -> throw new AssertionError();
      }
    }
    return sb.toString();
  }
}
