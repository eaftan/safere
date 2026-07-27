// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.EnumSet;
import java.util.List;
import org.openjdk.jmh.infra.Blackhole;

/** Shared operation semantics and result consumption for cross-engine regex workloads. */
enum BenchmarkOperation {
  MATCHES(EngineCapability.MATCHES),
  FIND(EngineCapability.FIND),
  FIND_ALL_COUNT(EngineCapability.FIND),
  MATCHES_CORPUS(EngineCapability.MATCHES),
  MATCHES_GROUP_LENGTH_SUM(EngineCapability.MATCHES, EngineCapability.GROUP_TEXT),
  FIND_ALL_LENGTH_SUM(EngineCapability.FIND, EngineCapability.GROUP_TEXT),
  FIND_ALL_GROUP_LENGTH_SUM(EngineCapability.FIND, EngineCapability.GROUP_TEXT),
  REPLACE_ALL(EngineCapability.REPLACE),
  FIND_GROUP_PRESENT(EngineCapability.FIND, EngineCapability.GROUP_TEXT),
  FIND_GROUP(EngineCapability.FIND, EngineCapability.GROUP_TEXT);

  private final EnumSet<EngineCapability> requiredCapabilities;

  BenchmarkOperation(EngineCapability... requiredCapabilities) {
    this.requiredCapabilities = EnumSet.noneOf(EngineCapability.class);
    for (EngineCapability capability : requiredCapabilities) {
      this.requiredCapabilities.add(capability);
    }
  }

  static BenchmarkOperation fromDeclarative(DeclarativeBenchmarkPlan.Operation operation) {
    return switch (operation) {
      case MATCHES -> MATCHES;
      case FIND -> FIND;
      case FIND_ALL_COUNT -> FIND_ALL_COUNT;
      case MATCHES_CORPUS -> MATCHES_CORPUS;
      case MATCHES_GROUP_LENGTH_SUM -> MATCHES_GROUP_LENGTH_SUM;
      case FIND_ALL_LENGTH_SUM -> FIND_ALL_LENGTH_SUM;
      case FIND_ALL_GROUP_LENGTH_SUM -> FIND_ALL_GROUP_LENGTH_SUM;
      case REPLACE_ALL -> REPLACE_ALL;
      case FIND_GROUP_PRESENT -> FIND_GROUP_PRESENT;
      case FIND_GROUP -> FIND_GROUP;
      default ->
          throw new IllegalArgumentException(
              "Operation is not implemented by the ordinary cross-engine runner: " + operation);
    };
  }

  boolean isSupportedBy(RegexEngineVariant variant) {
    return variant.capabilities().containsAll(requiredCapabilities);
  }

  BenchmarkTask bind(
      RegexEngineVariant.CompiledRegex pattern,
      List<RegexEngineVariant.RegexInput> inputs,
      int[] groups,
      String replacement) {
    RegexEngineVariant.RegexInput input = inputs.getFirst();
    return switch (this) {
      case MATCHES -> blackhole -> blackhole.consume(pattern.matches(input));
      case FIND -> blackhole -> blackhole.consume(pattern.find(input));
      case FIND_ALL_COUNT -> blackhole -> blackhole.consume(findAllCount(pattern.matcher(input)));
      case MATCHES_CORPUS -> blackhole -> blackhole.consume(matchesCorpus(pattern, inputs));
      case MATCHES_GROUP_LENGTH_SUM ->
          blackhole -> blackhole.consume(matchesGroupLengthSum(pattern, inputs, groups));
      case FIND_ALL_LENGTH_SUM ->
          blackhole -> blackhole.consume(findAllLengthSum(pattern.matcher(input)));
      case FIND_ALL_GROUP_LENGTH_SUM ->
          blackhole -> blackhole.consume(findAllGroupLengthSum(pattern.matcher(input), groups));
      case REPLACE_ALL -> blackhole -> blackhole.consume(pattern.replaceAll(input, replacement));
      case FIND_GROUP_PRESENT ->
          blackhole -> blackhole.consume(findGroupPresent(pattern.matcher(input), groups[0]));
      case FIND_GROUP ->
          blackhole -> blackhole.consume(findGroup(pattern.matcher(input), groups[0]));
    };
  }

  Object execute(
      RegexEngineVariant.CompiledRegex pattern,
      List<RegexEngineVariant.RegexInput> inputs,
      int[] groups,
      String replacement) {
    RegexEngineVariant.RegexInput input = inputs.getFirst();
    return switch (this) {
      case MATCHES -> pattern.matches(input);
      case FIND -> pattern.find(input);
      case FIND_ALL_COUNT -> findAllCount(pattern.matcher(input));
      case MATCHES_CORPUS -> matchesCorpus(pattern, inputs);
      case MATCHES_GROUP_LENGTH_SUM -> matchesGroupLengthSum(pattern, inputs, groups);
      case FIND_ALL_LENGTH_SUM -> findAllLengthSum(pattern.matcher(input));
      case FIND_ALL_GROUP_LENGTH_SUM -> findAllGroupLengthSum(pattern.matcher(input), groups);
      case REPLACE_ALL -> pattern.replaceAll(input, replacement);
      case FIND_GROUP_PRESENT -> findGroupPresent(pattern.matcher(input), groups[0]);
      case FIND_GROUP -> findGroup(pattern.matcher(input), groups[0]);
    };
  }

  private static int findAllCount(RegexEngineVariant.MatchCursor matcher) {
    int count = 0;
    while (matcher.find()) {
      count++;
    }
    return count;
  }

  private static int matchesCorpus(
      RegexEngineVariant.CompiledRegex pattern, List<RegexEngineVariant.RegexInput> inputs) {
    int count = 0;
    for (RegexEngineVariant.RegexInput input : inputs) {
      if (pattern.matches(input)) {
        count++;
      }
    }
    return count;
  }

  private static int matchesGroupLengthSum(
      RegexEngineVariant.CompiledRegex pattern,
      List<RegexEngineVariant.RegexInput> inputs,
      int[] groups) {
    int sum = 0;
    for (RegexEngineVariant.RegexInput input : inputs) {
      RegexEngineVariant.MatchCursor matcher = pattern.matcher(input);
      if (matcher.matches()) {
        sum += groupLengthSum(matcher, groups);
      }
    }
    return sum;
  }

  private static int findAllLengthSum(RegexEngineVariant.MatchCursor matcher) {
    int sum = 0;
    while (matcher.find()) {
      sum += matcher.group(0).length();
    }
    return sum;
  }

  private static int findAllGroupLengthSum(RegexEngineVariant.MatchCursor matcher, int[] groups) {
    int sum = 0;
    while (matcher.find()) {
      sum += groupLengthSum(matcher, groups);
    }
    return sum;
  }

  private static int groupLengthSum(RegexEngineVariant.MatchCursor matcher, int[] groups) {
    int sum = 0;
    for (int group : groups) {
      String value = matcher.group(group);
      if (value != null) {
        sum += value.length();
      }
    }
    return sum;
  }

  private static boolean findGroupPresent(RegexEngineVariant.MatchCursor matcher, int group) {
    return matcher.find() && matcher.group(group) != null;
  }

  private static String findGroup(RegexEngineVariant.MatchCursor matcher, int group) {
    if (!matcher.find()) {
      return null;
    }
    return matcher.group(group);
  }

  @FunctionalInterface
  interface BenchmarkTask {
    void run(Blackhole blackhole);
  }
}
