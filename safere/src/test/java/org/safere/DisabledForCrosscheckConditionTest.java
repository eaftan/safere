// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Proxy;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/** Checks that generation exclusions do not disable the original SafeRE tests. */
@DisabledForCrosscheck("tests the original execution condition and package-private internals")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class DisabledForCrosscheckConditionTest {
  @Test
  void originalTestsStayEnabledAndGeneratedMembersAreDisabled() throws Exception {
    String property = "org.safere.crosscheck.generatedTests";
    var constructor =
        Class.forName("org.safere.DisabledForCrosscheckCondition").getDeclaredConstructor();
    constructor.setAccessible(true);
    var condition = (ExecutionCondition) constructor.newInstance();
    String previous = System.getProperty(property);
    try {
      for (AnnotatedElement element :
          new AnnotatedElement[] {
            DisabledForCrosscheckConditionTest.class,
            Nested.class,
            Nested.class.getDeclaredMethod("disabledMethod")
          }) {
        var context =
            (ExtensionContext)
                Proxy.newProxyInstance(
                    ExtensionContext.class.getClassLoader(),
                    new Class<?>[] {ExtensionContext.class},
                    (proxy, method, args) -> {
                      if (method.getName().equals("getElement")) {
                        return Optional.of(element);
                      }
                      throw new UnsupportedOperationException(method.getName());
                    });
        System.clearProperty(property);
        assertThat(condition.evaluateExecutionCondition(context).isDisabled()).isFalse();
        System.setProperty(property, "true");
        assertThat(condition.evaluateExecutionCondition(context).isDisabled()).isTrue();
      }
    } finally {
      if (previous == null) {
        System.clearProperty(property);
      } else {
        System.setProperty(property, previous);
      }
    }
  }

  @DisabledForCrosscheck("nested")
  static class Nested {
    @DisabledForCrosscheck("method")
    void disabledMethod() {}
  }
}
