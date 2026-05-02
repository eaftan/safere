// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Package-private inventory of public {@link Matcher} lifecycle transitions.
 *
 * <p>The inventory is deliberately close to {@link Matcher} so tests can verify that every public
 * Matcher method is assigned an explicit state-transition category when the API changes.
 */
final class MatcherTransitionInventory {
  enum ResultEffect {
    RESET_NO_ATTEMPT,
    MATCH_ATTEMPT,
    REQUIRES_MATCH,
    PRESERVE,
    STATIC_UTILITY
  }

  enum DeferredCaptureEffect {
    CLEAR,
    MAY_DEFER,
    RESOLVE_AS_NEEDED,
    RESOLVE_FOR_SNAPSHOT,
    RESOLVE_FOR_REPLACEMENT,
    RESOLVE_BEFORE_BOUNDS_CHANGE,
    PRESERVE,
    NONE
  }

  enum CursorEffect {
    RESET_TO_INPUT_START,
    RESET_TO_REGION_START,
    SET_FROM_ARGUMENT_THEN_SEARCH,
    DERIVE_FROM_PREVIOUS_RESULT_THEN_SEARCH,
    PRESERVE,
    NONE
  }

  enum ReplacementEffect {
    RESET_APPEND_POSITION,
    ADVANCE_APPEND_POSITION,
    MANAGED_BY_APPEND_OPERATIONS,
    PRESERVE,
    NONE
  }

  enum EndStateEffect {
    UPDATE_FROM_ATTEMPT,
    PRESERVE,
    FOLLOWS_OPERATIONS,
    NONE
  }

  enum StructuralMutationEffect {
    INVALIDATES_ACTIVE_TRAVERSAL,
    DETECTS_CALLBACK_MUTATION,
    ORACLE_DEFINED,
    NOT_STRUCTURAL,
    NONE
  }

  enum CacheEffect {
    INVALIDATE_PATTERN_CACHES,
    INVALIDATE_INPUT_CACHES,
    INVALIDATE_REGION_ASSUMPTIONS,
    PRESERVE,
    NONE
  }

  record Signature(String name, List<Class<?>> parameterTypes, boolean isStatic) {
    Signature(String name, boolean isStatic, Class<?>... parameterTypes) {
      this(name, List.of(parameterTypes), isStatic);
    }
  }

  record Transition(
      Signature signature,
      ResultEffect resultEffect,
      DeferredCaptureEffect deferredCaptureEffect,
      CursorEffect cursorEffect,
      ReplacementEffect replacementEffect,
      EndStateEffect endStateEffect,
      StructuralMutationEffect structuralMutationEffect,
      CacheEffect cacheEffect,
      Set<String> legalObservationGroups) {}

  private static final Set<String> OBSERVER_ONLY = Set.of("observer-only");
  private static final Set<String> MATCH_RESULT = Set.of("observer-only", "match-result");
  private static final Set<String> END_STATE_ONLY = Set.of("observer-only", "end-state");
  private static final Set<String> REPLACEMENT = Set.of("observer-only", "replacement");
  private static final Set<String> STATIC_UTILITY = Set.of("static-utility");

  private static final List<Transition> TRANSITIONS = List.of(
      transition(
          signature("matches"),
          ResultEffect.MATCH_ATTEMPT,
          DeferredCaptureEffect.MAY_DEFER,
          CursorEffect.RESET_TO_REGION_START,
          ReplacementEffect.PRESERVE,
          EndStateEffect.UPDATE_FROM_ATTEMPT,
          StructuralMutationEffect.INVALIDATES_ACTIVE_TRAVERSAL,
          CacheEffect.PRESERVE,
          END_STATE_ONLY),
      transition(
          signature("lookingAt"),
          ResultEffect.MATCH_ATTEMPT,
          DeferredCaptureEffect.MAY_DEFER,
          CursorEffect.RESET_TO_REGION_START,
          ReplacementEffect.PRESERVE,
          EndStateEffect.UPDATE_FROM_ATTEMPT,
          StructuralMutationEffect.INVALIDATES_ACTIVE_TRAVERSAL,
          CacheEffect.PRESERVE,
          END_STATE_ONLY),
      transition(
          signature("find"),
          ResultEffect.MATCH_ATTEMPT,
          DeferredCaptureEffect.MAY_DEFER,
          CursorEffect.DERIVE_FROM_PREVIOUS_RESULT_THEN_SEARCH,
          ReplacementEffect.PRESERVE,
          EndStateEffect.UPDATE_FROM_ATTEMPT,
          StructuralMutationEffect.INVALIDATES_ACTIVE_TRAVERSAL,
          CacheEffect.PRESERVE,
          END_STATE_ONLY),
      transition(
          signature("find", int.class),
          ResultEffect.MATCH_ATTEMPT,
          DeferredCaptureEffect.CLEAR,
          CursorEffect.SET_FROM_ARGUMENT_THEN_SEARCH,
          ReplacementEffect.RESET_APPEND_POSITION,
          EndStateEffect.UPDATE_FROM_ATTEMPT,
          StructuralMutationEffect.INVALIDATES_ACTIVE_TRAVERSAL,
          CacheEffect.PRESERVE,
          END_STATE_ONLY),
      transition(
          signature("results"),
          ResultEffect.MATCH_ATTEMPT,
          DeferredCaptureEffect.RESOLVE_FOR_SNAPSHOT,
          CursorEffect.DERIVE_FROM_PREVIOUS_RESULT_THEN_SEARCH,
          ReplacementEffect.PRESERVE,
          EndStateEffect.FOLLOWS_OPERATIONS,
          StructuralMutationEffect.DETECTS_CALLBACK_MUTATION,
          CacheEffect.PRESERVE,
          MATCH_RESULT),
      transition(
          signature("groupCount"),
          ResultEffect.PRESERVE,
          DeferredCaptureEffect.PRESERVE,
          CursorEffect.PRESERVE,
          ReplacementEffect.PRESERVE,
          EndStateEffect.PRESERVE,
          StructuralMutationEffect.NOT_STRUCTURAL,
          CacheEffect.PRESERVE,
          OBSERVER_ONLY),
      matchObserver(signature("group")),
      matchObserver(signature("group", int.class)),
      matchObserver(signature("group", String.class)),
      matchObserver(signature("start")),
      matchObserver(signature("start", int.class)),
      matchObserver(signature("start", String.class)),
      matchObserver(signature("end")),
      matchObserver(signature("end", int.class)),
      matchObserver(signature("end", String.class)),
      transition(
          staticSignature("quoteReplacement", String.class),
          ResultEffect.STATIC_UTILITY,
          DeferredCaptureEffect.NONE,
          CursorEffect.NONE,
          ReplacementEffect.NONE,
          EndStateEffect.NONE,
          StructuralMutationEffect.NONE,
          CacheEffect.NONE,
          STATIC_UTILITY),
      replacementLoop(signature("replaceFirst", String.class)),
      replacementLoop(signature("replaceFirst", Function.class)),
      replacementLoop(signature("replaceAll", String.class)),
      replacementLoop(signature("replaceAll", Function.class)),
      appendReplacement(signature("appendReplacement", StringBuilder.class, String.class)),
      appendTail(signature("appendTail", StringBuilder.class)),
      appendReplacement(signature("appendReplacement", StringBuffer.class, String.class)),
      appendTail(signature("appendTail", StringBuffer.class)),
      transition(
          signature("reset"),
          ResultEffect.RESET_NO_ATTEMPT,
          DeferredCaptureEffect.CLEAR,
          CursorEffect.RESET_TO_INPUT_START,
          ReplacementEffect.RESET_APPEND_POSITION,
          EndStateEffect.PRESERVE,
          StructuralMutationEffect.INVALIDATES_ACTIVE_TRAVERSAL,
          CacheEffect.PRESERVE,
          OBSERVER_ONLY),
      transition(
          signature("reset", CharSequence.class),
          ResultEffect.RESET_NO_ATTEMPT,
          DeferredCaptureEffect.CLEAR,
          CursorEffect.RESET_TO_INPUT_START,
          ReplacementEffect.RESET_APPEND_POSITION,
          EndStateEffect.PRESERVE,
          StructuralMutationEffect.INVALIDATES_ACTIVE_TRAVERSAL,
          CacheEffect.INVALIDATE_INPUT_CACHES,
          OBSERVER_ONLY),
      transition(
          signature("region", int.class, int.class),
          ResultEffect.RESET_NO_ATTEMPT,
          DeferredCaptureEffect.CLEAR,
          CursorEffect.RESET_TO_REGION_START,
          ReplacementEffect.RESET_APPEND_POSITION,
          EndStateEffect.PRESERVE,
          StructuralMutationEffect.INVALIDATES_ACTIVE_TRAVERSAL,
          CacheEffect.INVALIDATE_REGION_ASSUMPTIONS,
          OBSERVER_ONLY),
      observer(signature("regionStart")),
      observer(signature("regionEnd")),
      observer(signature("hitEnd")),
      observer(signature("requireEnd")),
      observer(signature("namedGroups")),
      observer(signature("pattern")),
      observer(signature("toString")),
      transition(
          signature("usePattern", Pattern.class),
          ResultEffect.RESET_NO_ATTEMPT,
          DeferredCaptureEffect.CLEAR,
          CursorEffect.DERIVE_FROM_PREVIOUS_RESULT_THEN_SEARCH,
          ReplacementEffect.PRESERVE,
          EndStateEffect.PRESERVE,
          StructuralMutationEffect.INVALIDATES_ACTIVE_TRAVERSAL,
          CacheEffect.INVALIDATE_PATTERN_CACHES,
          OBSERVER_ONLY),
      boundsChange(signature("useTransparentBounds", boolean.class)),
      observer(signature("hasTransparentBounds")),
      boundsChange(signature("useAnchoringBounds", boolean.class)),
      observer(signature("hasAnchoringBounds")),
      transition(
          signature("toMatchResult"),
          ResultEffect.REQUIRES_MATCH,
          DeferredCaptureEffect.RESOLVE_FOR_SNAPSHOT,
          CursorEffect.PRESERVE,
          ReplacementEffect.PRESERVE,
          EndStateEffect.PRESERVE,
          StructuralMutationEffect.NOT_STRUCTURAL,
          CacheEffect.PRESERVE,
          MATCH_RESULT));

  private static final Map<Signature, Transition> BY_SIGNATURE =
      TRANSITIONS.stream()
          .collect(java.util.stream.Collectors.toUnmodifiableMap(
              Transition::signature, transition -> transition));

  private MatcherTransitionInventory() {}

  static List<Transition> transitions() {
    return TRANSITIONS;
  }

  static Optional<Transition> transitionFor(Signature signature) {
    return Optional.ofNullable(BY_SIGNATURE.get(signature));
  }

  private static Signature signature(String name, Class<?>... parameterTypes) {
    return new Signature(name, false, parameterTypes);
  }

  private static Signature staticSignature(String name, Class<?>... parameterTypes) {
    return new Signature(name, true, parameterTypes);
  }

  private static Transition observer(Signature signature) {
    return transition(
        signature,
        ResultEffect.PRESERVE,
        DeferredCaptureEffect.PRESERVE,
        CursorEffect.PRESERVE,
        ReplacementEffect.PRESERVE,
        EndStateEffect.PRESERVE,
        StructuralMutationEffect.NOT_STRUCTURAL,
        CacheEffect.PRESERVE,
        OBSERVER_ONLY);
  }

  private static Transition matchObserver(Signature signature) {
    return transition(
        signature,
        ResultEffect.REQUIRES_MATCH,
        DeferredCaptureEffect.RESOLVE_AS_NEEDED,
        CursorEffect.PRESERVE,
        ReplacementEffect.PRESERVE,
        EndStateEffect.PRESERVE,
        StructuralMutationEffect.NOT_STRUCTURAL,
        CacheEffect.PRESERVE,
        MATCH_RESULT);
  }

  private static Transition replacementLoop(Signature signature) {
    return transition(
        signature,
        ResultEffect.RESET_NO_ATTEMPT,
        DeferredCaptureEffect.MAY_DEFER,
        CursorEffect.DERIVE_FROM_PREVIOUS_RESULT_THEN_SEARCH,
        ReplacementEffect.MANAGED_BY_APPEND_OPERATIONS,
        EndStateEffect.FOLLOWS_OPERATIONS,
        StructuralMutationEffect.DETECTS_CALLBACK_MUTATION,
        CacheEffect.PRESERVE,
        REPLACEMENT);
  }

  private static Transition appendReplacement(Signature signature) {
    return transition(
        signature,
        ResultEffect.REQUIRES_MATCH,
        DeferredCaptureEffect.RESOLVE_FOR_REPLACEMENT,
        CursorEffect.PRESERVE,
        ReplacementEffect.ADVANCE_APPEND_POSITION,
        EndStateEffect.PRESERVE,
        StructuralMutationEffect.ORACLE_DEFINED,
        CacheEffect.PRESERVE,
        REPLACEMENT);
  }

  private static Transition appendTail(Signature signature) {
    return transition(
        signature,
        ResultEffect.PRESERVE,
        DeferredCaptureEffect.PRESERVE,
        CursorEffect.PRESERVE,
        ReplacementEffect.PRESERVE,
        EndStateEffect.PRESERVE,
        StructuralMutationEffect.ORACLE_DEFINED,
        CacheEffect.PRESERVE,
        REPLACEMENT);
  }

  private static Transition boundsChange(Signature signature) {
    return transition(
        signature,
        ResultEffect.PRESERVE,
        DeferredCaptureEffect.RESOLVE_BEFORE_BOUNDS_CHANGE,
        CursorEffect.PRESERVE,
        ReplacementEffect.PRESERVE,
        EndStateEffect.PRESERVE,
        StructuralMutationEffect.ORACLE_DEFINED,
        CacheEffect.INVALIDATE_REGION_ASSUMPTIONS,
        OBSERVER_ONLY);
  }

  private static Transition transition(
      Signature signature,
      ResultEffect resultEffect,
      DeferredCaptureEffect deferredCaptureEffect,
      CursorEffect cursorEffect,
      ReplacementEffect replacementEffect,
      EndStateEffect endStateEffect,
      StructuralMutationEffect structuralMutationEffect,
      CacheEffect cacheEffect,
      Set<String> legalObservationGroups) {
    return new Transition(
        signature,
        resultEffect,
        deferredCaptureEffect,
        cursorEffect,
        replacementEffect,
        endStateEffect,
        structuralMutationEffect,
        cacheEffect,
        legalObservationGroups);
  }
}
