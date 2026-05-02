// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for the package-private matcher transition inventory. */
@DisabledForCrosscheck("package-private Matcher transition metadata is SafeRE-internal")
class MatcherTransitionInventoryTest {

  @Test
  @DisplayName("transition inventory covers every public Matcher method")
  void transitionInventoryCoversPublicMatcherMethods() {
    Set<MatcherTransitionInventory.Signature> actualPublicMethods =
        Arrays.stream(Matcher.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .filter(method -> !method.isBridge())
            .filter(method -> !method.isSynthetic())
            .map(MatcherTransitionInventoryTest::signature)
            .collect(Collectors.toSet());

    Set<MatcherTransitionInventory.Signature> inventoriedMethods =
        MatcherTransitionInventory.transitions().stream()
            .map(MatcherTransitionInventory.Transition::signature)
            .collect(Collectors.toSet());

    assertThat(formatMissing(actualPublicMethods, inventoriedMethods))
        .as("public Matcher methods missing transition metadata")
        .isEmpty();
    assertThat(formatMissing(inventoriedMethods, actualPublicMethods))
        .as("transition metadata entries with no public Matcher method")
        .isEmpty();
  }

  @Test
  @DisplayName("public instance transitions declare deferred capture behavior")
  void publicInstanceTransitionsDeclareDeferredCaptureBehavior() {
    assertThat(MatcherTransitionInventory.transitions())
        .filteredOn(transition -> !transition.signature().isStatic())
        .allSatisfy(transition ->
            assertThat(transition.deferredCaptureEffect())
                .as("%s", format(transition.signature()))
                .isNotNull()
                .isNotEqualTo(MatcherTransitionInventory.DeferredCaptureEffect.NONE));
  }

  @Test
  @DisplayName("static utility transitions are explicitly non-stateful")
  void staticUtilityTransitionsAreExplicitlyNonStateful() {
    MatcherTransitionInventory.Transition quoteReplacement =
        MatcherTransitionInventory.transitionFor(
                new MatcherTransitionInventory.Signature(
                    "quoteReplacement", List.of(String.class), true))
            .orElseThrow();

    assertThat(quoteReplacement.resultEffect())
        .isEqualTo(MatcherTransitionInventory.ResultEffect.STATIC_UTILITY);
    assertThat(quoteReplacement.deferredCaptureEffect())
        .isEqualTo(MatcherTransitionInventory.DeferredCaptureEffect.NONE);
  }

  private static MatcherTransitionInventory.Signature signature(Method method) {
    return new MatcherTransitionInventory.Signature(
        method.getName(),
        List.of(method.getParameterTypes()),
        Modifier.isStatic(method.getModifiers()));
  }

  private static List<String> formatMissing(
      Set<MatcherTransitionInventory.Signature> expected,
      Set<MatcherTransitionInventory.Signature> actual) {
    return expected.stream()
        .filter(signature -> !actual.contains(signature))
        .map(MatcherTransitionInventoryTest::format)
        .sorted(Comparator.naturalOrder())
        .toList();
  }

  private static String format(MatcherTransitionInventory.Signature signature) {
    String params = signature.parameterTypes().stream()
        .map(Class::getSimpleName)
        .collect(Collectors.joining(", "));
    String prefix = signature.isStatic() ? "static " : "";
    return prefix + signature.name() + "(" + params + ")";
  }
}
