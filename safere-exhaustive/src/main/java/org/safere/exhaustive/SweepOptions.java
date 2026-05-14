// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import java.nio.file.Files;
import java.nio.file.Path;

/** Shared command-line options for exhaustive sweep tools. */
record SweepOptions(
    long rangeStartInclusive,
    long rangeEndExclusive,
    Path outputDir,
    long progressInterval,
    int threads,
    Path replayFile,
    String jsonlFileName) {
  Path jsonlPath() {
    return outputDir.resolve(jsonlFileName);
  }

  long totalChecks(long totalCases) {
    long end = Math.min(rangeEndExclusive, totalCases);
    if (rangeStartInclusive >= end) {
      return 0;
    }
    return end - rangeStartInclusive;
  }

  void printStartup(String sweepName) {
    System.out.println("sweep=" + sweepName);
    System.out.println("mode=" + (replayFile == null ? "sweep" : "replay"));
    System.out.println("range=" + rangeDescription());
    System.out.println("outputDir=" + outputDir);
    System.out.println("progressInterval=" + progressInterval);
    System.out.println("threads=" + threads);
    System.out.println("replayFile=" + (replayFile == null ? "" : replayFile));
    System.out.println("jsonl=" + jsonlPath());
  }

  static SweepOptions parse(
      String[] args, Path defaultOutputDir, String jsonlFileName, long defaultProgressInterval) {
    long rangeStartInclusive = 0;
    long rangeEndExclusive = Long.MAX_VALUE;
    Path outputDir = defaultOutputDir;
    long progressInterval = defaultProgressInterval;
    int threads = Runtime.getRuntime().availableProcessors();
    Path replayFile = null;
    for (String arg : args) {
      if (arg.startsWith("--range=")) {
        String value = arg.substring("--range=".length());
        int colon = value.indexOf(':');
        if (colon < 0) {
          throw new IllegalArgumentException("--range must use start:end syntax");
        }
        String start = value.substring(0, colon);
        String end = value.substring(colon + 1);
        rangeStartInclusive = start.isEmpty() ? 0 : Long.parseLong(start);
        rangeEndExclusive = end.isEmpty() ? Long.MAX_VALUE : Long.parseLong(end);
      } else if (arg.startsWith("--output-dir=")) {
        outputDir = Path.of(arg.substring("--output-dir=".length()));
      } else if (arg.startsWith("--progress-interval=")) {
        progressInterval = Long.parseLong(arg.substring("--progress-interval=".length()));
      } else if (arg.startsWith("--threads=")) {
        threads = Integer.parseInt(arg.substring("--threads=".length()));
      } else if (arg.startsWith("--replay-file=")) {
        replayFile = Path.of(arg.substring("--replay-file=".length()));
      } else {
        throw new IllegalArgumentException("unknown argument: " + arg);
      }
    }
    if (rangeStartInclusive < 0 || rangeEndExclusive < 0) {
      throw new IllegalArgumentException("--range bounds must be non-negative");
    }
    if (rangeEndExclusive < rangeStartInclusive) {
      throw new IllegalArgumentException("--range end must be greater than or equal to start");
    }
    if (progressInterval < 1) {
      throw new IllegalArgumentException("--progress-interval must be at least 1");
    }
    if (threads < 1) {
      throw new IllegalArgumentException("--threads must be at least 1");
    }
    if (replayFile != null && !Files.isRegularFile(replayFile)) {
      throw new IllegalArgumentException("--replay-file must be a regular file");
    }
    return new SweepOptions(
        rangeStartInclusive,
        rangeEndExclusive,
        outputDir,
        progressInterval,
        threads,
        replayFile,
        jsonlFileName);
  }

  private String rangeDescription() {
    String end = rangeEndExclusive == Long.MAX_VALUE ? "" : Long.toString(rangeEndExclusive);
    return rangeStartInclusive + ":" + end;
  }
}
