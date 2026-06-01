// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

/** Describes how an exhaustive sweep should treat one class of divergence. */
interface DivergenceClassification {
  DivergenceStatus status();

  String rationale();

  default boolean actionable() {
    return status() == DivergenceStatus.EXPECTED_ZERO;
  }
}
