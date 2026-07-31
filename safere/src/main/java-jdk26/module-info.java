// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

/** SafeRE on JDK 26, with optional readability of the incubator Vector API. */
module org.safere {
  requires static jdk.incubator.vector;

  exports org.safere;
}
