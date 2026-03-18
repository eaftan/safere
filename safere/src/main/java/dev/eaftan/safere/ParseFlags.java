// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

/**
 * Bit flags controlling the behavior of the regular expression parser. These flags affect what
 * syntax is accepted and how certain constructs are interpreted.
 *
 * <p>These correspond to RE2's ParseFlags enum in regexp.h.
 */
final class ParseFlags {

  /** No flags set. */
  public static final int NONE = 0;

  /** Fold case during matching (case-insensitive). */
  public static final int FOLD_CASE = 1 << 0;

  /** Treat the pattern as a literal string instead of a regexp. */
  public static final int LITERAL = 1 << 1;

  /**
   * Allow character classes like {@code [^a-z]}, {@code \D}, {@code \s}, and {@code [[:space:]]} to
   * match newline.
   */
  public static final int CLASS_NL = 1 << 2;

  /** Allow {@code .} to match newline. */
  public static final int DOT_NL = 1 << 3;

  /** Combined: both {@link #CLASS_NL} and {@link #DOT_NL}. */
  public static final int MATCH_NL = CLASS_NL | DOT_NL;

  /**
   * Treat {@code ^} and {@code $} as only matching at beginning and end of text, not around
   * embedded newlines. This is Perl's default mode.
   */
  public static final int ONE_LINE = 1 << 4;

  /** Regexp and text are in Latin-1, not UTF-8/Unicode. Not used in Java. */
  public static final int LATIN1 = 1 << 5;

  /** Repetition operators ({@code *}, {@code +}, {@code ?}) are non-greedy by default. */
  public static final int NON_GREEDY = 1 << 6;

  /** Allow Perl character classes: {@code \d}, {@code \s}, {@code \w} and their negations. */
  public static final int PERL_CLASSES = 1 << 7;

  /** Allow Perl's {@code \b} (word boundary) and {@code \B} (not word boundary). */
  public static final int PERL_B = 1 << 8;

  /**
   * Perl extensions: non-capturing parens {@code (?:...)}, non-greedy quantifiers, flag editing,
   * etc.
   */
  public static final int PERL_X = 1 << 9;

  /**
   * Allow {@code \p{Han}} for Unicode script/category groups and {@code \P{Han}} for their
   * negation.
   */
  public static final int UNICODE_GROUPS = 1 << 10;

  /** Never match {@code \n}, even if the regexp mentions it explicitly. */
  public static final int NEVER_NL = 1 << 11;

  /** Parse all parentheses as non-capturing. */
  public static final int NEVER_CAPTURE = 1 << 12;

  /**
   * Composite flag matching Perl's default behavior. Includes {@link #CLASS_NL}, {@link #ONE_LINE},
   * {@link #PERL_CLASSES}, {@link #PERL_B}, {@link #PERL_X}, and {@link #UNICODE_GROUPS}.
   */
  public static final int LIKE_PERL =
      CLASS_NL | ONE_LINE | PERL_CLASSES | PERL_B | PERL_X | UNICODE_GROUPS;

  /**
   * Internal flag: on {@link RegexpOp#END_TEXT}, indicates the original pattern used {@code $}
   * rather than {@code \z}.
   */
  public static final int WAS_DOLLAR = 1 << 13;

  /** Mask of all valid parse flags. */
  public static final int ALL_FLAGS = (1 << 14) - 1;

  private ParseFlags() {} // Non-instantiable.
}
