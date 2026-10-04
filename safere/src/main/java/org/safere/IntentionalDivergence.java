// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * Diagnostic evidence that an intentional semantic path was exercised, not a bug classification.
 */
public enum IntentionalDivergence {
  /** Character-class syntax rejected under SafeRE's documented intersection rules. */
  CHARACTER_CLASS_SYNTAX,
  /** Comments discard quoting constructs as ordinary comment text. */
  COMMENT_QUOTING,
  /** A terminal empty find invalidates the prior match coherently. */
  EXHAUSTED_EMPTY_MATCH,
  /** Changing patterns preserves coherent group-zero state. */
  USE_PATTERN_GROUP_ZERO
}
