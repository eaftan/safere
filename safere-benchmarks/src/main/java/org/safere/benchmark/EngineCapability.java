// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

/** Native operations exposed by a cross-engine benchmark execution variant. */
enum EngineCapability {
  COMPILE,
  FIND,
  MATCHES,
  LOOKING_AT,
  GROUP_PARTICIPATION,
  GROUP_TEXT,
  REPLACE,
  APPEND_REPLACEMENT,
  SPLIT,
  MATCHER_RESET,
  REGIONS;

  int bit() {
    return switch (this) {
      case COMPILE -> 1;
      case FIND -> 1 << 1;
      case MATCHES -> 1 << 2;
      case LOOKING_AT -> 1 << 3;
      case GROUP_PARTICIPATION -> 1 << 4;
      case GROUP_TEXT -> 1 << 5;
      case REPLACE -> 1 << 6;
      case APPEND_REPLACEMENT -> 1 << 7;
      case SPLIT -> 1 << 8;
      case MATCHER_RESET -> 1 << 9;
      case REGIONS -> 1 << 10;
    };
  }
}
