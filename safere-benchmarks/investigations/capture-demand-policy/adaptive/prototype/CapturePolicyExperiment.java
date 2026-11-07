// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Investigation-only producer adaptation; supplied by an opt-in source patch. */
final class CapturePolicyExperiment {
  static final String MODE = System.getProperty("safere.experimental.capturePolicy", "main");
  static final boolean ENABLED = !MODE.equals("main");
  static final boolean WORK =
      Boolean.getBoolean("safere.experimental.captureWork")
          || MODE.equals("work")
          || MODE.equals("random")
          || MODE.equals("periodic")
          || MODE.equals("observe");
  static final int MAX_WORK = 1 << 24;
  private static final int MAX_COST = 1 << 20;
  private static final long SEED = Long.getLong("safere.experimental.captureSeed", 1L);

  static {
    System.setProperty("safere.experimental.captureActualMode", MODE);
    System.setProperty("safere.experimental.captureActualSeed", Long.toString(SEED));
  }

  private int readHistory;
  private int successes;
  private int hitHistory;
  private int attempts;
  private boolean sticky;
  private boolean demandPreference;
  private boolean jointPreference;
  private int replayCredit;
  private int dfaMean;
  private int captureMean;
  private int dfaSamples;
  private int captureSamples;
  private int uncertain;
  private int schedule;
  private long randomState = seed(SEED);
  private boolean inFind;
  private boolean pending;
  private boolean read;
  private boolean replay;
  private boolean meaningful;
  private boolean decisionMade;
  private boolean captureSelected;
  private int span;
  private int boundaryWork;
  private int replayWork;
  private boolean resolvingReplay;
  private long choices;
  private long captureChoices;
  private long replayCount;
  private long rejected;
  private long totalWork;
  private long userReplayWork;

  private static long seed(long value) {
    return value == 0 ? 0x9e3779b97f4a7c15L : value;
  }

  void begin(int remaining) {
    retire();
    inFind = true;
    meaningful = remaining >= 16;
    span = Math.max(1, remaining);
    decisionMade = false;
    captureSelected = false;
    boundaryWork = 0;
    replayWork = 0;
    replay = false;
    read = false;
  }

  void finish(boolean matched) {
    inFind = false;
    if (meaningful) {
      hitHistory = (hitHistory << 1) | (matched ? 1 : 0);
      attempts = Math.min(32, attempts + 1);
    }
    pending = matched;
    if (!matched) {
      if (meaningful) {
        replayCredit = Math.max(-32, replayCredit - 4);
        rejected = increment(rejected);
      }
      learnCost();
    }
  }

  void read(boolean deferred) {
    if (pending) {
      read = true;
      sticky = true;
      if (deferred && !replay) {
        replay = true;
        replayCount = increment(replayCount);
      }
    }
  }

  void retire() {
    if (!pending) {
      return;
    }
    readHistory = (readHistory << 1) | (read ? 1 : 0);
    successes = Math.min(32, successes + 1);
    int reads = Integer.bitCount(readHistory);
    if (successes >= 4) {
      if (4 * reads >= 3 * successes) {
        demandPreference = true;
      } else if (2 * reads <= successes) {
        demandPreference = false;
      }
      int numerator = reads * Integer.bitCount(hitHistory);
      int denominator = successes * Math.max(1, attempts);
      if (2 * numerator >= denominator) {
        jointPreference = true;
      } else if (4 * numerator <= denominator) {
        jointPreference = false;
      }
    }
    int credit = replay ? 4 : captureSelected && read ? 1 : !read ? -1 : 0;
    replayCredit = Math.max(-32, Math.min(32, replayCredit + credit));
    learnCost();
    pending = false;
  }

  boolean prefer(boolean shared, boolean anchored) {
    if (!inFind || anchored) {
      return shared;
    }
    if (decisionMade) {
      return captureSelected;
    }
    decisionMade = true;
    captureSelected =
        switch (MODE) {
          case "observe" -> shared;
          case "dfa" -> false;
          case "localSticky" -> sticky;
          case "fraction" -> demandPreference;
          case "joint" -> demandPreference && jointPreference;
          case "replay" -> replayCredit > 0;
          case "work", "random", "periodic" -> chooseWork();
          default -> throw new IllegalArgumentException("Unknown investigation policy: " + MODE);
        };
    choices = increment(choices);
    if (captureSelected) {
      captureChoices = increment(captureChoices);
    }
    return captureSelected;
  }

  private boolean chooseWork() {
    // A producer cannot be justified by absent capture demand. This also avoids blind warmup
    // speculation on Boolean-only matchers. An existing estimate remains available for recovery.
    if (!sticky) {
      return false;
    }
    if (captureSamples == 0) {
      return true;
    }
    if (dfaSamples == 0) {
      return false;
    }
    boolean best = captureMean < dfaMean;
    if (MODE.equals("work")) {
      return best;
    }
    int high = Math.max(captureMean, dfaMean);
    boolean close = 4L * Math.abs(captureMean - dfaMean) <= high;
    int interval = captureSamples < 4 || dfaSamples < 4 || close || uncertain > 0 ? 4 : 32;
    uncertain = Math.max(0, uncertain - 1);
    boolean explore;
    if (MODE.equals("periodic")) {
      schedule = (schedule + 1) & 31;
      explore = (schedule & (interval - 1)) == 0;
    } else {
      long x = randomState;
      x ^= x << 13;
      x ^= x >>> 7;
      x ^= x << 17;
      randomState = x;
      explore = (x & (interval - 1)) == 0;
    }
    return explore ? !best : best;
  }

  void work(long units) {
    long bounded = Math.max(0L, Math.min(MAX_WORK, units));
    totalWork = add(totalWork, bounded);
    if (resolvingReplay && pending && read) {
      userReplayWork = add(userReplayWork, bounded);
    }
    if (resolvingReplay) {
      replayWork = saturatingWork(replayWork, units);
    } else {
      boundaryWork = saturatingWork(boundaryWork, units);
    }
  }

  void beginReplay() {
    resolvingReplay = true;
  }

  void endReplay() {
    resolvingReplay = false;
  }

  private void learnCost() {
    if (!WORK || !decisionMade || !meaningful) {
      return;
    }
    int cost = (int) Math.min(MAX_COST, ((long) boundaryWork + replayWork + 1) * 256 / span);
    if (captureSelected) {
      captureMean = update(captureMean, cost, captureSamples);
      captureSamples = Math.min(4096, captureSamples + 1);
    } else {
      dfaMean = update(dfaMean, cost, dfaSamples);
      dfaSamples = Math.min(4096, dfaSamples + 1);
    }
  }

  private int update(int mean, int cost, int samples) {
    if (samples == 0) {
      return cost;
    }
    if (2L * Math.abs(cost - mean) > Math.max(1, mean)) {
      uncertain = 32;
    }
    return (7 * mean + cost + 4) / 8;
  }

  void resetPattern() {
    readHistory = 0;
    successes = 0;
    hitHistory = 0;
    attempts = 0;
    sticky = false;
    demandPreference = false;
    jointPreference = false;
    replayCredit = 0;
    dfaMean = 0;
    captureMean = 0;
    dfaSamples = 0;
    captureSamples = 0;
    uncertain = 0;
    schedule = 0;
    randomState = seed(SEED);
    pending = false;
    inFind = false;
    resolvingReplay = false;
  }

  Snapshot snapshot() {
    return new Snapshot(
        successes,
        Integer.bitCount(readHistory),
        attempts,
        Integer.bitCount(hitHistory),
        replayCredit,
        dfaMean,
        captureMean,
        dfaSamples,
        captureSamples,
        choices,
        captureChoices,
        replayCount,
        rejected,
        totalWork,
        userReplayWork);
  }

  record Snapshot(
      int successes,
      int reads,
      int attempts,
      int hits,
      int credit,
      int dfaMean,
      int captureMean,
      int dfaSamples,
      int captureSamples,
      long choices,
      long captureChoices,
      long replays,
      long rejections,
      long work,
      long userReplayWork) {}

  private static int saturatingWork(int current, long units) {
    return (int) Math.min(MAX_WORK, current + Math.max(0L, Math.min(MAX_WORK, units)));
  }

  private static long add(long value, long extra) {
    return value > Long.MAX_VALUE - extra ? Long.MAX_VALUE : value + extra;
  }

  private static long increment(long value) {
    return value == Long.MAX_VALUE ? value : value + 1;
  }
}
