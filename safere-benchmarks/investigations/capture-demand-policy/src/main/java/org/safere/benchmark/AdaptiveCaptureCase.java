// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.function.Function;
import org.safere.Matcher;
import org.safere.Pattern;
import org.safere.Utf8Input;
import org.safere.Utf8Matcher;

/** Prepared schedules and independent capture oracle for adaptive-policy experiments. */
final class AdaptiveCaptureCase {
  private final String regex;
  private final CapturePolicyProtocol.Input[] inputs;
  private final int[] groups;
  private final int[] readEvery;
  private final int[][] order;
  private final String lifetime;
  private final boolean utf8;
  private final boolean offsets;

  AdaptiveCaptureCase(
      JsonObject config,
      String scenario,
      String representation,
      Function<String, CrossEngineBenchmarkPlan.Trial> trials,
      Function<String, CapturePolicyProtocol.Input> source) {
    JsonObject definition = config.getAsJsonObject("scenarios").getAsJsonObject(scenario);
    if (definition == null || !(representation.equals("string") || representation.equals("utf8"))) {
      throw new IllegalArgumentException("Unknown adaptive scenario or representation");
    }
    utf8 = representation.equals("utf8");
    String trial = definition.get("reference").getAsString() + "@safere-" + representation;
    regex = trials.apply(trial).workload().patterns().getFirst();
    groups =
        definition.getAsJsonArray("groups").asList().stream().mapToInt(v -> v.getAsInt()).toArray();
    inputs = new CapturePolicyProtocol.Input[2];
    String[] keys = {
      definition.get("hitInput").getAsString(), definition.get("missInput").getAsString()
    };
    for (int i = 0; i < inputs.length; i++) {
      inputs[i] = source.apply(keys[i]);
    }
    offsets = definition.get("consumption").getAsString().equals("offset");
    lifetime = definition.get("lifetime").getAsString();
    JsonArray phases = definition.getAsJsonArray("phases");
    if (phases.size() != 3) {
      throw new IllegalArgumentException("Expected exactly three adaptive phases");
    }
    readEvery = new int[phases.size()];
    order = new int[phases.size()][];
    for (int phase = 0; phase < phases.size(); phase++) {
      JsonObject phaseConfig = phases.get(phase).getAsJsonObject();
      readEvery[phase] = phaseConfig.get("readEvery").getAsInt();
      if (readEvery[phase] < 0) {
        throw new IllegalArgumentException("Capture-read period must be nonnegative");
      }
      order[phase] =
          schedule(
              config.get("documentsPerPhase").getAsInt(),
              phaseConfig.get("hitEvery").getAsInt(),
              definition.get("order").getAsString(),
              config.get("scheduleWidth").getAsInt(),
              config.get("scheduleSeed").getAsLong() + phase);
    }
  }

  static AdaptiveCaptureCase load(String scenario, String representation) {
    BenchmarkData data = BenchmarkData.get();
    return new AdaptiveCaptureCase(
        data.investigationConfiguration().getAsJsonObject("adaptive"),
        scenario,
        representation,
        CrossEngineBenchmarkPlan.load()::resolve,
        key -> new CapturePolicyProtocol.Input(data.getInputString(key), data.getInputBytes(key)));
  }

  static int[] schedule(int count, int hitEvery, String ordering, int width, long seed) {
    if (count < 1
        || hitEvery < 0
        || width < 1
        || count % width != 0
        || (hitEvery > 0 && width % hitEvery != 0)) {
      throw new IllegalArgumentException("Invalid adaptive document schedule");
    }
    int[] slots = new int[count];
    for (int i = 0; i < count; i++) {
      boolean hit =
          hitEvery != 0
              && (ordering.equals("blocked") ? i % width < width / hitEvery : i % hitEvery == 0);
      slots[i] = hit ? 0 : 1;
    }
    if (ordering.equals("shuffled")) {
      Random random = new Random(seed);
      for (int i = slots.length - 1; i > 0; i--) {
        int other = random.nextInt(i + 1);
        int swap = slots[i];
        slots[i] = slots[other];
        slots[other] = swap;
      }
    } else if (!ordering.equals("periodic") && !ordering.equals("blocked")) {
      throw new IllegalArgumentException("Unknown document ordering");
    }
    return slots;
  }

  Task task(boolean booleanOnly) {
    return new Task(Pattern.compile(regex), booleanOnly);
  }

  Shared shared() {
    return new Shared(Pattern.compile(regex));
  }

  final class Shared {
    private final Pattern pattern;

    private Shared(Pattern pattern) {
      this.pattern = pattern;
    }

    Task task(boolean booleanOnly) {
      return new Task(pattern, booleanOnly);
    }
  }

  void validate() {
    long expected = oracle(false);
    Task single = task(false);
    long sampledChecksum = 0;
    for (int[] phase : order) {
      for (int i = 0; i < phase.length; i++) {
        sampledChecksum += single.runOne();
      }
    }
    if (task(false).run(null) != expected
        || sampledChecksum != expected
        || task(true).run(null) != oracle(true)) {
      throw new IllegalStateException("Adaptive capture protocol differs from its JDK oracle");
    }
  }

  long oracle(boolean booleanOnly) {
    java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(regex);
    long sum = 0;
    for (int phase = 0; phase < order.length; phase++) {
      int hits = 0;
      for (int slot : order[phase]) {
        java.util.regex.Matcher matcher = compiled.matcher(inputs[slot].text);
        while (matcher.find()) {
          sum++;
          if (!booleanOnly && readEvery[phase] != 0 && hits % readEvery[phase] == 0) {
            for (int group : groups) {
              String value = matcher.group(group);
              sum +=
                  value == null
                      ? 0
                      : offsets
                          ? (utf8 ? value.getBytes(StandardCharsets.UTF_8).length : value.length())
                          : value.hashCode();
            }
          }
          hits++;
        }
      }
    }
    return sum;
  }

  final class Task {
    private final Pattern pattern;
    private final boolean booleanOnly;
    private final Cursor[] retained;
    private final Cursor rotating;
    private int stepPhase;
    private int stepDocument;
    private int stepHits;

    private Task(Pattern pattern, boolean booleanOnly) {
      this.pattern = pattern;
      this.booleanOnly = booleanOnly;
      retained =
          lifetime.equals("retained") ? new Cursor[] {cursor(0, false), cursor(1, false)} : null;
      rotating = lifetime.equals("rotating") ? cursor(0, true) : null;
      if (retained == null && rotating == null && !lifetime.equals("fresh")) {
        throw new IllegalArgumentException("Unknown adaptive cursor lifetime");
      }
    }

    long run(AdaptivePhaseCounters counters) {
      long sum = 0;
      for (int phase = 0; phase < order.length; phase++) {
        long started = counters == null ? 0 : System.nanoTime();
        int hits = 0;
        for (int slot : order[phase]) {
          Cursor matcher =
              retained != null ? retained[slot] : rotating != null ? rotating : cursor(slot, false);
          if (retained != null || rotating != null) {
            matcher.reset(slot);
          }
          while (matcher.find()) {
            sum++;
            if (!booleanOnly && readEvery[phase] != 0 && hits % readEvery[phase] == 0) {
              for (int group : groups) {
                sum +=
                    offsets
                        ? matcher.end(group) - matcher.start(group)
                        : hash(matcher.group(group));
              }
            }
            hits++;
          }
        }
        if (counters != null) {
          counters.record(phase, System.nanoTime() - started, order[phase].length);
        }
      }
      return sum;
    }

    long runOne() {
      int slot = order[stepPhase][stepDocument];
      Cursor matcher =
          retained != null ? retained[slot] : rotating != null ? rotating : cursor(slot, false);
      if (retained != null || rotating != null) {
        matcher.reset(slot);
      }
      long sum = 0;
      while (matcher.find()) {
        sum++;
        if (!booleanOnly && readEvery[stepPhase] != 0 && stepHits % readEvery[stepPhase] == 0) {
          for (int group : groups) {
            sum += offsets ? matcher.end(group) - matcher.start(group) : hash(matcher.group(group));
          }
        }
        stepHits++;
      }
      stepDocument++;
      if (stepDocument == order[stepPhase].length) {
        stepDocument = 0;
        stepHits = 0;
        stepPhase = (stepPhase + 1) % order.length;
      }
      return sum;
    }

    private Cursor cursor(int slot, boolean rotate) {
      if (!utf8) {
        Matcher matcher = pattern.matcher(inputs[slot].text);
        return new Cursor() {
          @Override
          public void reset(int next) {
            if (rotate) {
              matcher.reset(inputs[next].text);
            } else {
              matcher.reset();
            }
          }

          @Override
          public boolean find() {
            return matcher.find();
          }

          @Override
          public int start(int group) {
            return matcher.start(group);
          }

          @Override
          public int end(int group) {
            return matcher.end(group);
          }

          @Override
          public String group(int group) {
            return matcher.group(group);
          }
        };
      }
      byte[] bytes;
      int split;
      if (rotate) {
        split = inputs[0].bytes.length;
        bytes = new byte[split + inputs[1].bytes.length];
        System.arraycopy(inputs[0].bytes, 0, bytes, 0, split);
        System.arraycopy(inputs[1].bytes, 0, bytes, split, inputs[1].bytes.length);
      } else {
        bytes = inputs[slot].bytes;
        split = 0;
      }
      Utf8Matcher matcher = pattern.matcher(Utf8Input.trusted(bytes));
      return new Cursor() {
        @Override
        public void reset(int next) {
          if (rotate) {
            matcher.region(next == 0 ? 0 : split, next == 0 ? split : bytes.length);
          } else {
            matcher.reset();
          }
        }

        @Override
        public boolean find() {
          return matcher.find();
        }

        @Override
        public int start(int group) {
          return matcher.start(group);
        }

        @Override
        public int end(int group) {
          return matcher.end(group);
        }

        @Override
        public String group(int group) {
          int begin = matcher.start(group);
          return begin < 0
              ? null
              : new String(bytes, begin, matcher.end(group) - begin, StandardCharsets.UTF_8);
        }
      };
    }
  }

  private static int hash(String value) {
    return value == null ? 0 : value.hashCode();
  }

  private interface Cursor {
    void reset(int slot);

    boolean find();

    int start(int group);

    int end(int group);

    String group(int group);
  }
}
