// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Stable reasons a compiled pattern requires or limits particular matching strategies. */
public enum PatternLimitation {
  GRAPHEME_REQUIRES_EXACT_ENGINE,
  NULLABLE_LOOP_REQUIRES_EXACT_ENGINE,
  LAZY_SEMANTICS_LIMIT_ONE_PASS,
  NULLABLE_ALTERNATION_LIMITS_ONE_PASS,
  CAPTURE_PRIORITY_REQUIRES_EXACT_ENGINE,
  PROGRAM_TOO_LARGE_FOR_BIT_STATE
}
