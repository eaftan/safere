// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import org.safere.Pattern.FixedOffsetLiteral;
import org.safere.Pattern.StartAcceleration;

/**
 * Immutable descriptor capturing pre-computed start-position metadata extracted from a regular
 * expression AST.
 */
record StartDescriptor(
    String prefix,
    boolean prefixFoldCase,
    FixedOffsetLiteral fixedOffsetLiteral,
    CharClassScanInfo charClassPrefix,
    StartAcceleration lineAnchor,
    String anchoredPrefix,
    CharClassScanInfo anchoredCharClassPrefix,
    MultiLiteralInfo multiLiteral,
    TeddyModel teddyModel) {

  static final StartDescriptor NONE =
      new StartDescriptor(null, false, null, null, null, null, null, null, null);

  StartDescriptor(
      String prefix,
      boolean prefixFoldCase,
      FixedOffsetLiteral fixedOffsetLiteral,
      CharClassScanInfo charClassPrefix,
      StartAcceleration lineAnchor) {
    this(
        prefix,
        prefixFoldCase,
        fixedOffsetLiteral,
        charClassPrefix,
        lineAnchor,
        null,
        null,
        null,
        null);
  }

  boolean hasStartAcceleration() {
    return prefix != null
        || fixedOffsetLiteral != null
        || charClassPrefix != null
        || lineAnchor != null
        || multiLiteral != null
        || teddyModel != null;
  }
}
