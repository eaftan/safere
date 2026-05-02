// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * Operators for Regexp AST nodes, corresponding to different types of regular expression
 * constructs. Each Regexp node has an op that determines how it matches.
 *
 * <p>These correspond to RE2's RegexpOp enum in regexp.h.
 */
enum RegexpOp {
  /** Matches no strings. */
  NO_MATCH,

  /** Matches empty string. */
  EMPTY_MATCH,

  /** Matches a single Unicode code point ({@code rune}). */
  LITERAL,

  /** Matches a sequence of Unicode code points ({@code runes}). */
  LITERAL_STRING,

  /** Matches concatenation of sub-expressions. */
  CONCAT,

  /** Matches union (alternation) of sub-expressions. */
  ALTERNATE,

  /** Matches sub-expression zero or more times ({@code *}). */
  STAR,

  /** Matches sub-expression one or more times ({@code +}). */
  PLUS,

  /** Matches sub-expression zero or one times ({@code ?}). */
  QUEST,

  /**
   * Matches sub-expression at least {@code min} times, at most {@code max} times. A {@code max} of
   * -1 means no upper limit.
   */
  REPEAT,

  /** Source-level non-capturing parenthesized subexpression. */
  NON_CAPTURE,

  /** Parenthesized (capturing) subexpression with capture index and optional name. */
  CAPTURE,

  /** Matches any character (except possibly newline). */
  ANY_CHAR,

  /** Matches any byte. Not used in Java (no byte-level matching). */
  ANY_BYTE,

  /** Matches empty string at beginning of line ({@code ^}). */
  BEGIN_LINE,

  /** Matches empty string at end of line ({@code $}). */
  END_LINE,

  /** Matches word boundary ({@code \b}). */
  WORD_BOUNDARY,

  /** Matches not-a-word boundary ({@code \B}). */
  NO_WORD_BOUNDARY,

  /** Matches empty string at beginning of text ({@code \A}). */
  BEGIN_TEXT,

  /** Matches empty string at end of text ({@code \z}). */
  END_TEXT,

  /** Matches character class given by associated CharClass. */
  CHAR_CLASS,

  /**
   * Forces match of entire expression right now, with a match ID. Used internally by multi-pattern
   * matching (Set).
   */
  HAVE_MATCH;
}
