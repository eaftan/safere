// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

/** Plain JVM cold-start harness for Unicode compile and first-match latency. */
public final class UnicodeColdStartMain {
  private static final String DEFAULT_INPUT = "Ecole_東京_123";

  private UnicodeColdStartMain() {}

  /**
   * Runs one compile and one {@code find()} in a fresh JVM.
   *
   * <p>Usage: {@code UnicodeColdStartMain <safere|jdk> <regex> <flagSet> [input]}.
   */
  public static void main(String[] args) {
    if (args.length < 3 || args.length > 4) {
      System.err.println("Usage: UnicodeColdStartMain <safere|jdk> <regex> <flagSet> [input]");
      System.exit(2);
    }

    String engine = args[0];
    String regex = args[1];
    String flagSet = args[2];
    String input = args.length == 4 ? args[3] : DEFAULT_INPUT;
    int flags = BenchmarkFlags.parse(flagSet);

    long start = System.nanoTime();
    Object pattern = compile(engine, regex, flags);
    long afterCompile = System.nanoTime();
    boolean matched = find(engine, pattern, input);
    long afterMatch = System.nanoTime();

    System.out.printf(
        "engine=%s regex=%s flags=%s compileMs=%.3f matchMs=%.3f matched=%s%n",
        engine,
        regex,
        flagSet,
        nanosToMillis(afterCompile - start),
        nanosToMillis(afterMatch - afterCompile),
        matched);
  }

  private static Object compile(String engine, String regex, int flags) {
    return switch (engine) {
      case "safere" -> org.safere.Pattern.compile(regex, flags);
      case "jdk" -> java.util.regex.Pattern.compile(regex, flags);
      default -> throw new IllegalArgumentException("Unknown engine: " + engine);
    };
  }

  private static boolean find(String engine, Object pattern, String input) {
    return switch (engine) {
      case "safere" -> ((org.safere.Pattern) pattern).matcher(input).find();
      case "jdk" -> ((java.util.regex.Pattern) pattern).matcher(input).find();
      default -> throw new IllegalArgumentException("Unknown engine: " + engine);
    };
  }

  private static double nanosToMillis(long nanos) {
    return nanos / 1_000_000.0;
  }
}
