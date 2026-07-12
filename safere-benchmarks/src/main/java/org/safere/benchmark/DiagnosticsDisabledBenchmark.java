// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Production-path benchmarks used to measure disabled diagnostics overhead. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class DiagnosticsDisabledBenchmark {
  private org.safere.Pattern literal;
  private org.safere.Pattern onePass;
  private org.safere.Pattern ambiguous;
  private org.safere.Pattern dfa;
  private org.safere.Pattern charClassReplacement;
  private org.safere.Pattern dfaReplacement;
  private String dfaInput;
  private String nfaInput;
  private String sparseReplacementInput;
  private String denseReplacementInput;

  /** Initializes patterns and inputs outside measured invocations. */
  @Setup
  public void setup() {
    literal = org.safere.Pattern.compile("abc");
    onePass = org.safere.Pattern.compile("^([A-Z]+):(\\d+)$");
    ambiguous = org.safere.Pattern.compile("(?:a|aa)+b");
    dfa = org.safere.Pattern.compile("a+b");
    charClassReplacement = org.safere.Pattern.compile("[0-9]+");
    dfaReplacement = org.safere.Pattern.compile("a+b");
    dfaInput = "x".repeat(512) + "aaab";
    nfaInput = "a".repeat(8_192) + "b";
    sparseReplacementInput = "x".repeat(512) + " aaab " + "x".repeat(512);
    denseReplacementInput = "1 22 333 4444 55555 666666";
  }

  /** Measures the smallest successful literal full match. */
  @Benchmark
  public boolean tinyLiteralMatches() {
    return literal.matcher("abc").matches();
  }

  /** Measures the smallest successful literal search. */
  @Benchmark
  public boolean tinyLiteralFindHit() {
    return literal.matcher("xabc").find();
  }

  /** Measures the smallest unsuccessful literal search. */
  @Benchmark
  public boolean tinyLiteralFindMiss() {
    return literal.matcher("xyz").find();
  }

  /** Measures an anchored OnePass match with captures. */
  @Benchmark
  public boolean onePassMatches() {
    return onePass.matcher("HEADER:12345").matches();
  }

  /** Measures an unanchored DFA search on a long input. */
  @Benchmark
  public boolean dfaLongFind() {
    return dfa.matcher(dfaInput).find();
  }

  /** Measures exact matching on a small ambiguous input, which is BitState-sized. */
  @Benchmark
  public boolean bitStateShortMatches() {
    return ambiguous.matcher("aaaaab").matches();
  }

  /** Measures exact matching on an input too large for BitState. */
  @Benchmark
  public boolean nfaLongMatches() {
    return ambiguous.matcher(nfaInput).matches();
  }

  /** Measures a general DFA replacement with sparse matches. */
  @Benchmark
  public String dfaSparseReplaceAll() {
    return dfaReplacement.matcher(sparseReplacementInput).replaceAll("z");
  }

  /** Measures a character-class replacement with dense matches. */
  @Benchmark
  public String charClassDenseReplaceAll() {
    return charClassReplacement.matcher(denseReplacementInput).replaceAll("#");
  }

  /** Measures a character-class replacement with no matches. */
  @Benchmark
  public String charClassNoMatchReplaceAll() {
    return charClassReplacement.matcher("no digits here").replaceAll("#");
  }
}
