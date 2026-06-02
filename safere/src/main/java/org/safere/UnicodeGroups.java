// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Map;

/** Unicode general category and script range tables generated from the maintainer JDK. */
final class UnicodeGroups {
  private UnicodeGroups() {}

  /**
   * Returns an unmodifiable map of Unicode group names to their code point range tables. Keys
   * include major categories, subcategories, and script names.
   */
  static Map<String, int[][]> groups() {
    return UnicodeGeneratedTables.unicodeGroups();
  }
}
