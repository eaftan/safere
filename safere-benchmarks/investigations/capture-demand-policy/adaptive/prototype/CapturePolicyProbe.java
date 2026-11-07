// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Deterministic state/work trace, run separately from timing measurements. */
public final class CapturePolicyProbe {
  private CapturePolicyProbe() {}

  /** Reads pattern/input files and externally declared phase schedules, writing a TSV trace. */
  public static void main(String[] args) throws IOException {
    String regex = Files.readString(Path.of(args[0]));
    String[] inputs = {Files.readString(Path.of(args[1])), Files.readString(Path.of(args[2]))};
    int[] groups = integers(args[3]);
    boolean utf8 = args[4].equals("utf8");
    int[] hitEvery = integers(args[5]);
    int[] readEvery = integers(args[6]);
    int documents = Integer.parseInt(args[7]);
    Path output = Path.of(args[8]);
    Pattern compiled = Pattern.compile(regex);
    byte[] first = inputs[0].getBytes(StandardCharsets.UTF_8);
    byte[] second = inputs[1].getBytes(StandardCharsets.UTF_8);
    byte[] joined = new byte[first.length + second.length];
    System.arraycopy(first, 0, joined, 0, first.length);
    System.arraycopy(second, 0, joined, first.length, second.length);
    Matcher matcher =
        utf8
            ? new Matcher(compiled, new Utf8InputScanner(joined, 0, joined.length))
            : compiled.matcher(inputs[0]);
    StringBuilder trace =
        new StringBuilder(
            "mode\trepresentation\tphase\tdocument\tfindCalls\tchecksum\tsuccesses\treads\tattempts"
                + "\thits\tcredit\tdfaMean\tcaptureMean\tdfaSamples\tcaptureSamples\tchoices"
                + "\tcaptureChoices\treplays\trejections\twork\tuserReplayWork\n");
    long checksum = 0;
    long calls = 0;
    for (int phase = 0; phase < hitEvery.length; phase++) {
      int hits = 0;
      for (int document = 0; document < documents; document++) {
        boolean positive = hitEvery[phase] > 0 && document % hitEvery[phase] == 0;
        if (utf8) {
          matcher.region(positive ? 0 : first.length, positive ? first.length : joined.length);
        } else {
          matcher.reset(inputs[positive ? 0 : 1]);
        }
        while (true) {
          calls++;
          if (!matcher.find()) {
            break;
          }
          checksum++;
          if (readEvery[phase] > 0 && hits % readEvery[phase] == 0) {
            for (int group : groups) {
              String value;
              if (utf8) {
                int start = matcher.start(group);
                value =
                    start < 0
                        ? null
                        : new String(
                            joined, start, matcher.end(group) - start, StandardCharsets.UTF_8);
              } else {
                value = matcher.group(group);
              }
              checksum += value == null ? 0 : value.hashCode();
            }
          }
          hits++;
        }
        int done = document + 1;
        if (done <= 8 || (done & (done - 1)) == 0 || done == documents) {
          CapturePolicyExperiment.Snapshot s = matcher.capturePolicySnapshot();
          if (s.choices() > calls) {
            throw new IllegalStateException("More producer choices than search attempts");
          }
          trace
              .append(CapturePolicyExperiment.MODE)
              .append('\t')
              .append(args[4])
              .append('\t')
              .append(phase)
              .append('\t')
              .append(done)
              .append('\t')
              .append(calls)
              .append('\t')
              .append(checksum)
              .append('\t')
              .append(s.successes())
              .append('\t')
              .append(s.reads())
              .append('\t')
              .append(s.attempts())
              .append('\t')
              .append(s.hits())
              .append('\t')
              .append(s.credit())
              .append('\t')
              .append(s.dfaMean())
              .append('\t')
              .append(s.captureMean())
              .append('\t')
              .append(s.dfaSamples())
              .append('\t')
              .append(s.captureSamples())
              .append('\t')
              .append(s.choices())
              .append('\t')
              .append(s.captureChoices())
              .append('\t')
              .append(s.replays())
              .append('\t')
              .append(s.rejections())
              .append('\t')
              .append(s.work())
              .append('\t')
              .append(s.userReplayWork())
              .append('\n');
        }
      }
    }
    Files.writeString(output, trace);
  }

  private static int[] integers(String text) {
    return Arrays.stream(text.split(",")).mapToInt(Integer::parseInt).toArray();
  }
}
