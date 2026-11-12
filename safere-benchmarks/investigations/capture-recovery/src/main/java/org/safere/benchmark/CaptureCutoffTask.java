// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.List;

/** Prepared native cursors and capture-consumption schedule for one cutoff trial. */
final class CaptureCutoffTask implements AutoCloseable {
  private final RegexEngineVariant.CompiledRegex pattern;
  private final RegexEngineVariant.MatchCursor[] cursors;
  private final String operation;
  private final int documents;
  private final int hitEvery;
  private final int readEvery;

  CaptureCutoffTask(
      RegexEngineVariant variant,
      String regex,
      List<RegexEngineVariant.RegexInput> inputs,
      String operation,
      int documents,
      int hitEvery,
      int readEvery) {
    if (documents < 1 || hitEvery < 0 || readEvery < 0 || inputs.size() != 2) {
      throw new IllegalArgumentException("Invalid cutoff protocol");
    }
    this.operation = operation;
    this.documents = documents;
    this.hitEvery = hitEvery;
    this.readEvery = readEvery;
    pattern = variant.compile(regex);
    cursors = inputs.stream().map(pattern::matcher).toArray(RegexEngineVariant.MatchCursor[]::new);
    // Steady state after actual text consumption, including Boolean/rejection controls.
    if (!search(cursors[0]) || cursors[0].group(1) == null) {
      throw new IllegalStateException("Declared positive primer failed");
    }
    cursors[1].reset();
    if (search(cursors[1])) {
      throw new IllegalStateException("Declared rejection input matched");
    }
  }

  private boolean search(RegexEngineVariant.MatchCursor cursor) {
    return switch (operation) {
      case "matches" -> cursor.matches();
      case "lookingAt" -> cursor.lookingAt();
      case "find" -> cursor.find();
      default -> throw new IllegalArgumentException(operation);
    };
  }

  long run() {
    long checksum = 0;
    int hits = 0;
    for (int document = 0; document < documents; document++) {
      int index = hitEvery > 0 && document % hitEvery == 0 ? 0 : 1;
      RegexEngineVariant.MatchCursor cursor = cursors[index];
      cursor.reset();
      if (search(cursor)) {
        checksum++;
        if (readEvery > 0 && hits++ % readEvery == 0) {
          checksum = checksum * 31 + cursor.group(1).hashCode();
        }
      }
    }
    return checksum;
  }

  @Override
  public void close() {
    pattern.close();
  }
}
