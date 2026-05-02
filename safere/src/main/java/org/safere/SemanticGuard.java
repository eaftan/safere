// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Semantic guard categories that justify a non-general engine path. */
enum SemanticGuard {
  BOUNDED_STATE,
  CAPTURE_DEFERABLE,
  CAPTURE_EQUIVALENT,
  CONSERVATIVE_START_SET,
  GROUP_ZERO_EQUIVALENT,
  LEFTMOST_FIRST_EQUIVALENT,
  NO_USER_CAPTURES,
  NON_NULLABLE_PATTERN,
  SIMPLE_REPLACEMENT,
  WHOLE_PATTERN_SHAPE
}
