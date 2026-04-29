// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a SafeRE test or test class that should be disabled only in generated crosscheck tests.
 *
 * <p>The annotation is active only when the generated crosscheck test profile sets the
 * {@code org.safere.crosscheck.generatedTests} system property. Use an issue reference in the
 * reason for fixable SafeRE/JDK divergences, and a plain reason for tests that are intentionally not
 * relevant to crosscheck.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@ExtendWith(DisabledForCrosscheckCondition.class)
@interface DisabledForCrosscheck {
  /** Explains why this test is disabled in generated crosscheck coverage. */
  String value();
}

/** JUnit condition backing {@link DisabledForCrosscheck}. */
final class DisabledForCrosscheckCondition implements ExecutionCondition {
  static final String GENERATED_TESTS_PROPERTY = "org.safere.crosscheck.generatedTests";

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    if (!Boolean.getBoolean(GENERATED_TESTS_PROPERTY)) {
      return ConditionEvaluationResult.enabled("not a generated crosscheck test run");
    }

    return context
        .getElement()
        .map(element -> element.getAnnotation(DisabledForCrosscheck.class))
        .map(annotation -> ConditionEvaluationResult.disabled(annotation.value()))
        .orElseGet(() -> ConditionEvaluationResult.enabled("not disabled for crosscheck"));
  }
}
