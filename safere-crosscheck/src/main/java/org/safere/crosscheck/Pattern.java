// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.crosscheck;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.regex.PatternSyntaxException;

/**
 * A crosscheck wrapper that compiles and executes regular expressions on both SafeRE and
 * {@code java.util.regex}, comparing results and throwing {@link CrosscheckException} on
 * divergence.
 *
 * <p>This class has the same API as {@link org.safere.Pattern} and {@link java.util.regex.Pattern},
 * so switching to crosscheck mode requires only changing the import:
 *
 * <pre>
 *   // Before:
 *   import org.safere.Pattern;
 *   // After:
 *   import org.safere.crosscheck.Pattern;
 * </pre>
 *
 * <p>When both engines agree, SafeRE's result is returned. When they disagree, a
 * {@link CrosscheckException} is thrown with full details and an API call trace. When SafeRE
 * rejects a pattern that the JDK accepts (e.g., backreferences), an
 * {@link UnsupportedPatternException} is thrown.
 */
public final class Pattern {

  // Flag constants — identical to java.util.regex.Pattern and org.safere.Pattern.
  public static final int UNIX_LINES = java.util.regex.Pattern.UNIX_LINES;
  public static final int CASE_INSENSITIVE = java.util.regex.Pattern.CASE_INSENSITIVE;
  public static final int COMMENTS = java.util.regex.Pattern.COMMENTS;
  public static final int MULTILINE = java.util.regex.Pattern.MULTILINE;
  public static final int LITERAL = java.util.regex.Pattern.LITERAL;
  public static final int DOTALL = java.util.regex.Pattern.DOTALL;
  public static final int UNICODE_CASE = java.util.regex.Pattern.UNICODE_CASE;
  public static final int UNICODE_CHARACTER_CLASS = java.util.regex.Pattern.UNICODE_CHARACTER_CLASS;

  private final org.safere.Pattern saferePattern;
  private final java.util.regex.Pattern jdkPattern;

  private Pattern(org.safere.Pattern saferePattern, java.util.regex.Pattern jdkPattern) {
    this.saferePattern = saferePattern;
    this.jdkPattern = jdkPattern;
  }

  /**
   * Compiles the given regular expression on both engines.
   *
   * @throws UnsupportedPatternException if SafeRE rejects the pattern but the JDK accepts it
   * @throws CrosscheckException if the JDK rejects the pattern but SafeRE accepts it
   * @throws PatternSyntaxException if both engines reject the pattern
   */
  public static Pattern compile(String regex) {
    return compile(regex, 0);
  }

  /**
   * Compiles the given regular expression with the given flags on both engines.
   *
   * @throws UnsupportedPatternException if SafeRE rejects the pattern but the JDK accepts it
   * @throws CrosscheckException if the JDK rejects the pattern but SafeRE accepts it
   * @throws PatternSyntaxException if both engines reject the pattern
   */
  public static Pattern compile(String regex, int flags) {
    org.safere.Pattern sp = null;
    java.util.regex.Pattern jp = null;
    PatternSyntaxException safereEx = null;
    PatternSyntaxException jdkEx = null;

    try {
      sp = org.safere.Pattern.compile(regex, flags);
    } catch (PatternSyntaxException e) {
      safereEx = e;
    }
    try {
      jp = java.util.regex.Pattern.compile(regex, flags);
    } catch (PatternSyntaxException e) {
      jdkEx = e;
    }

    if (sp != null && jp != null) {
      // Both compiled successfully.
      return new Pattern(sp, jp);
    } else if (safereEx != null && jdkEx != null) {
      // Both rejected — rethrow SafeRE's exception.
      throw safereEx;
    } else if (safereEx != null) {
      // SafeRE rejected, JDK accepted — unsupported feature.
      throw UnsupportedPatternException.fromCause(safereEx);
    } else {
      // JDK rejected, SafeRE accepted — unexpected divergence.
      throw new CrosscheckException(
          "Pattern.compile",
          quote(regex) + ", " + flags,
          "compiled successfully",
          "PatternSyntaxException: " + jdkEx.getMessage(),
          "(no trace — divergence during compilation)");
    }
  }

  /**
   * Convenience method that compiles the regex and tests if it matches the entire input, on both
   * engines.
   */
  public static boolean matches(String regex, CharSequence input) {
    boolean sr = org.safere.Pattern.matches(regex, input);
    boolean jr = java.util.regex.Pattern.matches(regex, input.toString());
    if (sr != jr) {
      throw new CrosscheckException(
          "Pattern.matches",
          quote(regex) + ", " + quote(input),
          String.valueOf(sr),
          String.valueOf(jr),
          "(no trace — static method)");
    }
    return sr;
  }

  /** Returns a literal pattern string for the given string. Delegates to SafeRE. */
  public static String quote(String s) {
    return org.safere.Pattern.quote(s);
  }

  /** Creates a crosscheck {@link Matcher} for the given input. */
  public Matcher matcher(CharSequence input) {
    return new Matcher(this, input);
  }

  /** Returns the flags this pattern was compiled with. */
  public int flags() {
    return saferePattern.flags();
  }

  /** Returns the regular expression from which this pattern was compiled. */
  public String pattern() {
    return saferePattern.pattern();
  }

  /**
   * Splits the input around matches of this pattern, comparing results from both engines.
   *
   * @throws CrosscheckException if the engines produce different split results
   */
  public String[] split(CharSequence input) {
    return split(input, 0);
  }

  /**
   * Splits the input around matches of this pattern with the given limit, comparing results from
   * both engines.
   *
   * @throws CrosscheckException if the engines produce different split results
   */
  public String[] split(CharSequence input, int limit) {
    String[] sr = saferePattern.split(input, limit);
    String[] jr = jdkPattern.split(input, limit);
    if (!Arrays.equals(sr, jr)) {
      throw new CrosscheckException(
          "Pattern.split",
          quote(input) + ", " + limit,
          Arrays.toString(sr),
          Arrays.toString(jr),
          "(no trace — Pattern method)");
    }
    return sr;
  }

  /**
   * Splits the input around matches of this pattern, including delimiters, comparing results from
   * both engines.
   *
   * @throws CrosscheckException if the engines produce different split results
   */
  public String[] splitWithDelimiters(CharSequence input) {
    return splitWithDelimiters(input, 0);
  }

  /**
   * Splits the input around matches of this pattern with the given limit, including delimiters,
   * comparing results from both engines.
   *
   * @throws CrosscheckException if the engines produce different split results
   */
  public String[] splitWithDelimiters(CharSequence input, int limit) {
    String[] sr = saferePattern.splitWithDelimiters(input, limit);
    String[] jr = jdkPattern.splitWithDelimiters(input, limit);
    if (!Arrays.equals(sr, jr)) {
      throw new CrosscheckException(
          "Pattern.splitWithDelimiters",
          quote(input) + ", " + limit,
          Arrays.toString(sr),
          Arrays.toString(jr),
          "(no trace — Pattern method)");
    }
    return sr;
  }

  /** Returns the named capturing groups in this pattern. */
  public Map<String, Integer> namedGroups() {
    return saferePattern.namedGroups();
  }

  /** Returns the pattern string. */
  @Override
  public String toString() {
    return saferePattern.toString();
  }

  /** Returns the underlying SafeRE pattern (package-private, used by Matcher). */
  org.safere.Pattern saferePattern() {
    return saferePattern;
  }

  /** Returns the underlying JDK pattern (package-private, used by Matcher). */
  java.util.regex.Pattern jdkPattern() {
    return jdkPattern;
  }

  private static String quote(CharSequence s) {
    return "\"" + s + "\"";
  }
}
