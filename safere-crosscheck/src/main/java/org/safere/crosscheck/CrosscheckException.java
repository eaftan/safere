// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.crosscheck;

/**
 * Thrown when SafeRE and {@code java.util.regex} produce different results for the same operation.
 *
 * <p>The exception message includes the divergent method call, both results, and the full API call
 * trace leading up to the divergence. This provides enough context to reproduce and report the bug.
 */
public class CrosscheckException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String method;
  private final String args;
  private final String safereResult;
  private final String jdkResult;
  private final String trace;

  /**
   * Creates a new crosscheck exception.
   *
   * @param method the method that diverged
   * @param args the arguments to the method
   * @param safereResult the result from SafeRE
   * @param jdkResult the result from java.util.regex
   * @param trace the formatted API call trace
   */
  public CrosscheckException(
      String method, String args, String safereResult, String jdkResult, String trace) {
    super(formatMessage(method, args, safereResult, jdkResult, trace));
    this.method = method;
    this.args = args;
    this.safereResult = safereResult;
    this.jdkResult = jdkResult;
    this.trace = trace;
  }

  /** Returns the name of the method that produced divergent results. */
  public String method() {
    return method;
  }

  /** Returns the arguments passed to the divergent method. */
  public String args() {
    return args;
  }

  /** Returns the result from SafeRE. */
  public String safereResult() {
    return safereResult;
  }

  /** Returns the result from {@code java.util.regex}. */
  public String jdkResult() {
    return jdkResult;
  }

  /** Returns the formatted API call trace leading up to the divergence. */
  public String trace() {
    return trace;
  }

  private static String formatMessage(
      String method, String args, String safereResult, String jdkResult, String trace) {
    return "Crosscheck divergence in "
        + method
        + "("
        + args
        + "):\n"
        + "  SafeRE: "
        + safereResult
        + "\n"
        + "  JDK:    "
        + jdkResult
        + "\n\n"
        + trace;
  }
}
