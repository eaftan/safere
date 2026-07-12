// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Stable semantic and structural features found in a compiled pattern. */
public enum PatternFeature {
  LITERAL,
  CAPTURES,
  ALTERNATION,
  LAZY_QUANTIFIER,
  BOUNDED_REPEAT,
  ANCHOR,
  WORD_BOUNDARY,
  GRAPHEME,
  CASE_INSENSITIVE,
  UNICODE_CHARACTER_CLASS,
  NULLABLE,
  NULLABLE_ALTERNATION,
  NESTED_NULLABLE_QUANTIFIER,
  PROGRESS_CHECK,
  START_ANCHOR,
  END_ANCHOR,
  CAPTURES_IN_QUANTIFIER
}
