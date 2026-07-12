// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Matching strategies that can participate for at least one operation and input. */
public enum PatternCapability {
  LITERAL_MATCH,
  CHARACTER_CLASS_MATCH,
  KEYWORD_MATCH,
  ONE_PASS_PRIMARY,
  ONE_PASS_CAPTURE_EXTRACTION,
  DFA_BOUNDARY_SEARCH,
  DFA_REJECT_PREFILTER,
  BIT_STATE,
  NFA
}
