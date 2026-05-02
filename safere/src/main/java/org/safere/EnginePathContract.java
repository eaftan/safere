// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Machine-readable engine-path contract metadata for package-private tests. */
record EnginePathContract(
    EnginePath path,
    EnginePathRole role,
    Set<ResultAuthority> authorities,
    Set<SemanticGuard> guards) {

  private static final List<EnginePathContract> ALL =
      List.of(
          new EnginePathContract(
              EnginePath.LITERAL_FAST_PATHS,
              EnginePathRole.AUTHORITATIVE_PRODUCER,
              EnumSet.of(
                  ResultAuthority.NO_MATCH,
                  ResultAuthority.GROUP_ZERO,
                  ResultAuthority.SEARCH_CURSOR),
              EnumSet.of(SemanticGuard.NO_USER_CAPTURES)),
          new EnginePathContract(
              EnginePath.CHAR_CLASS_MATCH_FAST_PATHS,
              EnginePathRole.AUTHORITATIVE_PRODUCER,
              EnumSet.of(ResultAuthority.NO_MATCH, ResultAuthority.GROUP_ZERO),
              EnumSet.of(SemanticGuard.WHOLE_PATTERN_SHAPE)),
          new EnginePathContract(
              EnginePath.CHAR_CLASS_REPLACEMENT_FAST_PATH,
              EnginePathRole.AUTHORITATIVE_PRODUCER,
              EnumSet.of(ResultAuthority.REPLACEMENT_RESULT),
              EnumSet.of(
                  SemanticGuard.NON_NULLABLE_PATTERN,
                  SemanticGuard.SIMPLE_REPLACEMENT,
                  SemanticGuard.WHOLE_PATTERN_SHAPE)),
          new EnginePathContract(
              EnginePath.KEYWORD_ALTERNATION_FAST_PATH,
              EnginePathRole.GUARDED_OPTIMIZATION,
              EnumSet.of(
                  ResultAuthority.NO_MATCH,
                  ResultAuthority.GROUP_ZERO,
                  ResultAuthority.CAPTURES,
                  ResultAuthority.SEARCH_CURSOR),
              EnumSet.of(
                  SemanticGuard.WHOLE_PATTERN_SHAPE,
                  SemanticGuard.LEFTMOST_FIRST_EQUIVALENT)),
          new EnginePathContract(
              EnginePath.START_ACCELERATION,
              EnginePathRole.FILTER,
              EnumSet.of(ResultAuthority.CANDIDATE_START),
              EnumSet.of(SemanticGuard.CONSERVATIVE_START_SET)),
          new EnginePathContract(
              EnginePath.ONE_PASS,
              EnginePathRole.GUARDED_OPTIMIZATION,
              EnumSet.of(
                  ResultAuthority.NO_MATCH,
                  ResultAuthority.GROUP_ZERO,
                  ResultAuthority.CAPTURES,
                  ResultAuthority.SEARCH_CURSOR),
              EnumSet.of(
                  SemanticGuard.LEFTMOST_FIRST_EQUIVALENT,
                  SemanticGuard.CAPTURE_EQUIVALENT)),
          new EnginePathContract(
              EnginePath.DFA,
              EnginePathRole.PARTIAL_PRODUCER,
              EnumSet.of(ResultAuthority.NO_MATCH, ResultAuthority.GROUP_ZERO),
              EnumSet.of(SemanticGuard.GROUP_ZERO_EQUIVALENT)),
          new EnginePathContract(
              EnginePath.REVERSE_DFA,
              EnginePathRole.FILTER,
              EnumSet.of(ResultAuthority.CANDIDATE_START, ResultAuthority.NO_MATCH),
              EnumSet.of(SemanticGuard.GROUP_ZERO_EQUIVALENT)),
          new EnginePathContract(
              EnginePath.BIT_STATE,
              EnginePathRole.GUARDED_OPTIMIZATION,
              EnumSet.of(
                  ResultAuthority.NO_MATCH,
                  ResultAuthority.GROUP_ZERO,
                  ResultAuthority.CAPTURES,
                  ResultAuthority.SEARCH_CURSOR),
              EnumSet.of(
                  SemanticGuard.BOUNDED_STATE,
                  SemanticGuard.CAPTURE_EQUIVALENT)),
          new EnginePathContract(
              EnginePath.LAZY_CAPTURE_EXTRACTION,
              EnginePathRole.PARTIAL_PRODUCER,
              EnumSet.of(ResultAuthority.GROUP_ZERO, ResultAuthority.DEFERRED_CAPTURES),
              EnumSet.of(SemanticGuard.CAPTURE_DEFERABLE)));

  static List<EnginePathContract> all() {
    return ALL;
  }
}
