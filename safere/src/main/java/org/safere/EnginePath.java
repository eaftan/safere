// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Engine paths that can be forced or disabled by package-private equivalence tests. */
enum EnginePath {
  LITERAL_FAST_PATHS,
  CHAR_CLASS_MATCH_FAST_PATHS,
  CHAR_CLASS_REPLACEMENT_FAST_PATH,
  KEYWORD_ALTERNATION_FAST_PATH,
  START_ACCELERATION,
  ONE_PASS,
  DFA,
  REVERSE_DFA,
  BIT_STATE,
  LAZY_CAPTURE_EXTRACTION
}
