// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Stable strategy-level description of matching work. */
public enum MatchStrategy {
  NONE,
  LITERAL,
  CHARACTER_CLASS,
  KEYWORD,
  ONE_PASS,
  DFA,
  BIT_STATE,
  NFA
}
