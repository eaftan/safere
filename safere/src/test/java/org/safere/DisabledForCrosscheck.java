// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a SafeRE test or test class that should be disabled only in generated crosscheck tests.
 *
 * <p>The crosscheck test generator rewrites this annotation to JUnit's {@code @Disabled}. Use an
 * issue reference in the reason for fixable SafeRE/JDK divergences, and a plain reason for tests
 * that are intentionally not relevant to crosscheck.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.METHOD, ElementType.TYPE})
@interface DisabledForCrosscheck {
  /** Explains why this test is disabled in generated crosscheck coverage. */
  String value();
}
