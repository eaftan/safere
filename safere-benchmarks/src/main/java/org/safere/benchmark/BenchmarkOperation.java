// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import org.openjdk.jmh.infra.Blackhole;

/** Generic operation semantics and result consumption for cross-engine regex workloads. */
enum BenchmarkOperation {
  MATCHES(EngineCapability.MATCHES),
  FIND(EngineCapability.FIND),
  LOOKING_AT(EngineCapability.LOOKING_AT),
  FIND_ALL_COUNT(EngineCapability.FIND),
  MATCHES_CORPUS(EngineCapability.MATCHES),
  MATCHES_GROUP_LENGTH_SUM(EngineCapability.MATCHES, EngineCapability.GROUP_TEXT),
  FIND_ALL_LENGTH_SUM(EngineCapability.FIND, EngineCapability.GROUP_TEXT),
  FIND_ALL_GROUP_LENGTH_SUM(EngineCapability.FIND, EngineCapability.GROUP_TEXT),
  CAPTURE_GROUPS(EngineCapability.MATCHES, EngineCapability.GROUP_TEXT),
  REPLACE_FIRST(EngineCapability.REPLACE),
  REPLACE_ALL(EngineCapability.REPLACE),
  REPLACE_ALL_LENGTH_SUM(EngineCapability.REPLACE),
  MANUAL_REPLACE_ALL(EngineCapability.APPEND_REPLACEMENT),
  SPLIT_LENGTH_SUM(EngineCapability.SPLIT),
  COMPILE(EngineCapability.COMPILE),
  COMPILE_AND_FIND(EngineCapability.COMPILE, EngineCapability.FIND),
  FIND_ROTATING_UTF16(EngineCapability.FIND),
  COMPILE_AND_FIND_ROTATING_UTF16(EngineCapability.COMPILE, EngineCapability.FIND),
  MATCHER_RESET_FIND(EngineCapability.FIND, EngineCapability.MATCHER_RESET),
  MATCHER_REGION_FIND(EngineCapability.FIND, EngineCapability.REGIONS),
  FIND_GROUP_PRESENT(EngineCapability.FIND, EngineCapability.GROUP_PARTICIPATION),
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
      case LOOKING_AT -> LOOKING_AT;
      case FIND_ALL_COUNT -> FIND_ALL_COUNT;
      case MATCHES_CORPUS -> MATCHES_CORPUS;
      case MATCHES_GROUP_LENGTH_SUM -> MATCHES_GROUP_LENGTH_SUM;
      case FIND_ALL_LENGTH_SUM -> FIND_ALL_LENGTH_SUM;
      case FIND_ALL_GROUP_LENGTH_SUM -> FIND_ALL_GROUP_LENGTH_SUM;
      case CAPTURE_GROUPS -> CAPTURE_GROUPS;
      case REPLACE_FIRST -> REPLACE_FIRST;
      case REPLACE_ALL -> REPLACE_ALL;
      case REPLACE_ALL_LENGTH_SUM -> REPLACE_ALL_LENGTH_SUM;
      case MANUAL_REPLACE_ALL -> MANUAL_REPLACE_ALL;
      case SPLIT_LENGTH_SUM -> SPLIT_LENGTH_SUM;
      case COMPILE -> COMPILE;
      case COMPILE_AND_FIND -> COMPILE_AND_FIND;
      case FIND_ROTATING_UTF16 -> FIND_ROTATING_UTF16;
      case COMPILE_AND_FIND_ROTATING_UTF16 -> COMPILE_AND_FIND_ROTATING_UTF16;
      case MATCHER_RESET_FIND -> MATCHER_RESET_FIND;
      case MATCHER_REGION_FIND -> MATCHER_REGION_FIND;
      case FIND_GROUP_PRESENT -> FIND_GROUP_PRESENT;
      case FIND_GROUP -> FIND_GROUP;
      default ->
          throw new IllegalArgumentException(
              "Operation is not implemented by the cross-engine runner: " + operation);
    };
  }

  boolean isSupportedBy(RegexEngineVariant variant) {
    return variant.capabilities().containsAll(requiredCapabilities);
  }

  BenchmarkTask bind(
      RegexEngineVariant variant,
      List<String> patternSources,
      List<RegexEngineVariant.CompiledRegex> patterns,
      List<RegexEngineVariant.RegexInput> inputs,
      int[] groups,
      String replacement,
      int limit,
      DeclarativeBenchmarkPlan.MatcherLifecycle lifecycle,
      String flagSet,
      int seed,
      int count) {
    if (this == COMPILE) {
      return blackhole ->
          blackhole.consume(variant.compileForBenchmark(patternSources.getFirst(), flagSet));
    }
    if (this == FIND_ROTATING_UTF16 || this == COMPILE_AND_FIND_ROTATING_UTF16) {
      return rotatingUtf16Task(
          variant,
          patternSources.getFirst(),
          patterns,
          seed,
          count,
          this == COMPILE_AND_FIND_ROTATING_UTF16);
    }
    RegexEngineVariant.CompiledRegex pattern = patterns.getFirst();
    RegexEngineVariant.RegexInput input = inputs.getFirst();
    return switch (this) {
      case MATCHES -> blackhole -> blackhole.consume(pattern.matches(input));
      case FIND ->
          lifecycle.matcher() == DeclarativeBenchmarkPlan.MatcherReuse.NONE
              ? blackhole -> blackhole.consume(pattern.find(input))
              : blackhole -> blackhole.consume(pattern.matcher(input).find());
      case LOOKING_AT -> blackhole -> blackhole.consume(pattern.matcher(input).lookingAt());
      case FIND_ALL_COUNT -> blackhole -> blackhole.consume(findAllCount(pattern.matcher(input)));
      case MATCHES_CORPUS -> blackhole -> blackhole.consume(matchesCorpus(pattern, inputs));
      case MATCHES_GROUP_LENGTH_SUM ->
          blackhole -> blackhole.consume(matchesGroupLengthSum(pattern, inputs, groups));
      case FIND_ALL_LENGTH_SUM ->
          blackhole -> blackhole.consume(findAllLengthSum(pattern.matcher(input)));
      case FIND_ALL_GROUP_LENGTH_SUM ->
          blackhole -> blackhole.consume(findAllGroupLengthSum(pattern.matcher(input), groups));
      case CAPTURE_GROUPS ->
          blackhole -> blackhole.consume(captureGroups(pattern.matcher(input), groups));
      case REPLACE_FIRST ->
          blackhole -> blackhole.consume(pattern.replaceFirst(input, replacement));
      case REPLACE_ALL -> blackhole -> blackhole.consume(pattern.replaceAll(input, replacement));
      case REPLACE_ALL_LENGTH_SUM ->
          blackhole -> blackhole.consume(replaceAllLengthSum(patterns, input, replacement));
      case MANUAL_REPLACE_ALL ->
          blackhole -> blackhole.consume(manualReplaceAll(pattern.matcher(input), replacement));
      case SPLIT_LENGTH_SUM ->
          blackhole -> blackhole.consume(splitLengthSum(pattern.split(input, limit)));
      case COMPILE_AND_FIND ->
          blackhole -> {
            try (RegexEngineVariant.CompiledRegex compiled =
                variant.compile(patternSources.getFirst())) {
              blackhole.consume(compiled.find(input));
            }
          };
      case MATCHER_RESET_FIND -> {
        RegexEngineVariant.MatchCursor matcher = pattern.matcher(input);
        yield blackhole -> {
          matcher.reset();
          blackhole.consume(findAllCount(matcher));
        };
      }
      case MATCHER_REGION_FIND -> {
        RegexEngineVariant.MatchCursor matcher = pattern.matcher(input);
        DeclarativeBenchmarkPlan.LifecycleStep region = region(lifecycle);
        yield blackhole -> {
          matcher.region(region.start(), region.end());
          blackhole.consume(matcher.find());
        };
      }
      case FIND_GROUP_PRESENT ->
          blackhole -> blackhole.consume(findGroupPresent(pattern.matcher(input), groups[0]));
      case FIND_GROUP ->
          blackhole -> blackhole.consume(findGroup(pattern.matcher(input), groups[0]));
      case COMPILE -> throw new AssertionError();
      case FIND_ROTATING_UTF16, COMPILE_AND_FIND_ROTATING_UTF16 -> throw new AssertionError();
    };
  }

  Object execute(
      RegexEngineVariant variant,
      List<String> patternSources,
      List<RegexEngineVariant.CompiledRegex> patterns,
      List<RegexEngineVariant.RegexInput> inputs,
      int[] groups,
      String replacement,
      int limit,
      DeclarativeBenchmarkPlan.MatcherLifecycle lifecycle,
      String flagSet,
      int seed,
      int count) {
    if (this == COMPILE) {
      return true;
    }
    if (this == FIND_ROTATING_UTF16 || this == COMPILE_AND_FIND_ROTATING_UTF16) {
      String input = rotatingUtf16Inputs(seed, count)[0];
      if (this == FIND_ROTATING_UTF16) {
        return patterns.getFirst().find(new RegexEngineVariant.StringRegexInput(input));
      }
      try (RegexEngineVariant.CompiledRegex compiled = variant.compile(patternSources.getFirst())) {
        return compiled.find(new RegexEngineVariant.StringRegexInput(input));
      }
    }
    RegexEngineVariant.CompiledRegex pattern = patterns.getFirst();
    RegexEngineVariant.RegexInput input = inputs.getFirst();
    return switch (this) {
      case MATCHES -> pattern.matches(input);
      case FIND ->
          lifecycle.matcher() == DeclarativeBenchmarkPlan.MatcherReuse.NONE
              ? pattern.find(input)
              : pattern.matcher(input).find();
      case LOOKING_AT -> pattern.matcher(input).lookingAt();
      case FIND_ALL_COUNT -> findAllCount(pattern.matcher(input));
      case MATCHES_CORPUS -> matchesCorpus(pattern, inputs);
      case MATCHES_GROUP_LENGTH_SUM -> matchesGroupLengthSum(pattern, inputs, groups);
      case FIND_ALL_LENGTH_SUM -> findAllLengthSum(pattern.matcher(input));
      case FIND_ALL_GROUP_LENGTH_SUM -> findAllGroupLengthSum(pattern.matcher(input), groups);
      case CAPTURE_GROUPS -> captureGroups(pattern.matcher(input), groups);
      case REPLACE_FIRST -> pattern.replaceFirst(input, replacement);
      case REPLACE_ALL -> pattern.replaceAll(input, replacement);
      case REPLACE_ALL_LENGTH_SUM -> replaceAllLengthSum(patterns, input, replacement);
      case MANUAL_REPLACE_ALL -> manualReplaceAll(pattern.matcher(input), replacement);
      case SPLIT_LENGTH_SUM -> splitLengthSum(pattern.split(input, limit));
      case COMPILE_AND_FIND -> {
        try (RegexEngineVariant.CompiledRegex compiled =
            variant.compile(patternSources.getFirst())) {
          yield compiled.find(input);
        }
      }
      case MATCHER_RESET_FIND -> {
        RegexEngineVariant.MatchCursor matcher = pattern.matcher(input);
        matcher.reset();
        yield findAllCount(matcher);
      }
      case MATCHER_REGION_FIND -> {
        RegexEngineVariant.MatchCursor matcher = pattern.matcher(input);
        DeclarativeBenchmarkPlan.LifecycleStep region = region(lifecycle);
        matcher.region(region.start(), region.end());
        yield matcher.find();
      }
      case FIND_GROUP_PRESENT -> findGroupPresent(pattern.matcher(input), groups[0]);
      case FIND_GROUP -> findGroup(pattern.matcher(input), groups[0]);
      case COMPILE -> throw new AssertionError();
      case FIND_ROTATING_UTF16, COMPILE_AND_FIND_ROTATING_UTF16 -> throw new AssertionError();
    };
  }

  private static BenchmarkTask rotatingUtf16Task(
      RegexEngineVariant variant,
      String patternSource,
      List<RegexEngineVariant.CompiledRegex> patterns,
      int seed,
      int count,
      boolean compileEachInvocation) {
    String[] inputs = rotatingUtf16Inputs(seed, count);
    int[] index = {0};
    if (!compileEachInvocation) {
      RegexEngineVariant.CompiledRegex pattern = patterns.getFirst();
      return blackhole -> {
        String input = inputs[index[0]];
        index[0] = (index[0] + 1) % inputs.length;
        blackhole.consume(pattern.find(new RegexEngineVariant.StringRegexInput(input)));
      };
    }
    return blackhole -> {
      String input = inputs[index[0]];
      index[0] = (index[0] + 1) % inputs.length;
      try (RegexEngineVariant.CompiledRegex compiled = variant.compile(patternSource)) {
        blackhole.consume(compiled.find(new RegexEngineVariant.StringRegexInput(input)));
      }
    };
  }

  private static String[] rotatingUtf16Inputs(int seed, int count) {
    Random random = new Random(seed);
    String[] inputs = new String[count];
    for (int i = 0; i < count; i++) {
      inputs[i] = new String(new char[] {(char) random.nextInt()});
    }
    return inputs;
  }

  private static DeclarativeBenchmarkPlan.LifecycleStep region(
      DeclarativeBenchmarkPlan.MatcherLifecycle lifecycle) {
    return lifecycle.steps().stream()
        .filter(step -> step.kind() == DeclarativeBenchmarkPlan.LifecycleStepKind.REGION)
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("Matcher region operation requires region"));
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

  private static String captureGroups(RegexEngineVariant.MatchCursor matcher, int[] groups) {
    if (!matcher.matches()) {
      return "";
    }
    if (groups.length == 1) {
      return matcher.group(groups[0]);
    }
    if (groups.length == 3) {
      return matcher.group(groups[0]) + matcher.group(groups[1]) + matcher.group(groups[2]);
    }
    StringBuilder result = new StringBuilder();
    for (int group : groups) {
      result.append(matcher.group(group));
    }
    return result.toString();
  }

  private static int replaceAllLengthSum(
      List<RegexEngineVariant.CompiledRegex> patterns,
      RegexEngineVariant.RegexInput input,
      String replacement) {
    int sum = 0;
    for (RegexEngineVariant.CompiledRegex pattern : patterns) {
      sum += pattern.replaceAll(input, replacement).length();
    }
    return sum;
  }

  private static String manualReplaceAll(
      RegexEngineVariant.MatchCursor matcher, String replacement) {
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      matcher.appendReplacement(result, replacement);
    }
    matcher.appendTail(result);
    return result.toString();
  }

  private static int splitLengthSum(String[] parts) {
    int sum = parts.length;
    for (String part : parts) {
      sum += part.length();
    }
    return sum;
  }

  private static boolean findGroupPresent(RegexEngineVariant.MatchCursor matcher, int group) {
    return matcher.find() && matcher.groupParticipated(group);
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
