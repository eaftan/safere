// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.recording;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Builds a Markdown overlap report from recording-facade events. */
public final class OverlapReporter {

  private static final int MAX_EXACT_GROUPS = 100;
  private static final int MAX_CONTAINMENT_ROWS = 100;

  private OverlapReporter() {}

  /**
   * Reads a recording event file and writes a Markdown overlap report.
   *
   * @param args input event path followed by output report path
   */
  public static void main(String[] args) {
    if (args.length != 2) {
      throw new IllegalArgumentException("expected: <events.tsv> <overlap-report.md>");
    }
    Path eventsPath = Path.of(args[0]);
    Path reportPath = Path.of(args[1]);
    try {
      Report report = analyze(Files.readAllLines(eventsPath, StandardCharsets.UTF_8));
      Files.createDirectories(reportPath.getParent());
      Files.writeString(reportPath, report.markdown(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Report analyze(List<String> lines) {
    Map<Long, Lifecycle> lifecycles = new LinkedHashMap<>();
    for (String line : lines) {
      Event event = Event.parse(line);
      lifecycles
          .computeIfAbsent(event.objectId(), unused -> new Lifecycle(event.kind(), event.test()))
          .add(event);
    }

    Map<String, FingerprintGroup> groups = new HashMap<>();
    Map<String, OriginStats> origins = new TreeMap<>();
    for (Lifecycle lifecycle : lifecycles.values()) {
      LifecycleFingerprint fingerprint = lifecycle.fingerprint();
      groups
          .computeIfAbsent(
              fingerprint.executionHash(),
              unused -> new FingerprintGroup(fingerprint.category(), fingerprint.sample()))
          .add(lifecycle.origin());
      origins
          .computeIfAbsent(lifecycle.origin(), unused -> new OriginStats())
          .add(fingerprint.executionHash());
    }

    Map<OriginPair, Integer> sharedCounts = new HashMap<>();
    for (FingerprintGroup group : groups.values()) {
      List<String> groupOrigins = group.distinctOriginList();
      for (int first = 0; first < groupOrigins.size(); first++) {
        for (int second = first + 1; second < groupOrigins.size(); second++) {
          OriginPair pair = OriginPair.sorted(groupOrigins.get(first), groupOrigins.get(second));
          sharedCounts.merge(pair, 1, Integer::sum);
        }
      }
    }

    return new Report(lines.size(), lifecycles.size(), groups, origins, sharedCounts);
  }

  record Report(
      int eventCount,
      int lifecycleCount,
      Map<String, FingerprintGroup> groups,
      Map<String, OriginStats> origins,
      Map<OriginPair, Integer> sharedCounts) {

    String markdown() {
      StringBuilder output = new StringBuilder();
      output.append("# SafeRE CI Test Overlap Report\n\n");
      output.append("- Recorded events: ").append(eventCount).append('\n');
      output.append("- Recorded object lifecycles: ").append(lifecycleCount).append('\n');
      output.append("- Distinct execution fingerprints: ").append(groups.size()).append('\n');
      output.append("- Test methods: ").append(origins.size()).append("\n\n");

      output.append("## Test-method summary\n\n");
      output.append("| Test method | Executions | Distinct | Internal repeats |\n");
      output.append("|---|---:|---:|---:|\n");
      origins.entrySet().stream()
          .sorted(
              Map.Entry.<String, OriginStats>comparingByValue(
                      Comparator.comparingInt(OriginStats::executions))
                  .reversed())
          .forEach(
              entry ->
                  output
                      .append("| `")
                      .append(entry.getKey())
                      .append("` | ")
                      .append(entry.getValue().executions())
                      .append(" | ")
                      .append(entry.getValue().distinctCount())
                      .append(" | ")
                      .append(entry.getValue().internalRepeats())
                      .append(" |\n"));

      output.append("\n## Highest containment\n\n");
      output.append("| Contained test | Containing test | Shared | Containment |\n");
      output.append("|---|---|---:|---:|\n");
      containmentRows().stream()
          .limit(MAX_CONTAINMENT_ROWS)
          .forEach(
              row ->
                  output
                      .append("| `")
                      .append(row.contained())
                      .append("` | `")
                      .append(row.containing())
                      .append("` | ")
                      .append(row.shared())
                      .append(" | ")
                      .append(String.format("%.2f%%", row.containment() * 100.0))
                      .append(" |\n"));

      output.append("\n## Exact execution overlaps\n\n");
      groups.entrySet().stream()
          .filter(entry -> entry.getValue().distinctOrigins().size() > 1)
          .sorted(
              Map.Entry.<String, FingerprintGroup>comparingByValue(
                      Comparator.comparingInt(FingerprintGroup::executionCount))
                  .reversed())
          .limit(MAX_EXACT_GROUPS)
          .forEach(
              entry -> {
                FingerprintGroup group = entry.getValue();
                output
                    .append("### `")
                    .append(entry.getKey(), 0, 16)
                    .append("` — ")
                    .append(group.category())
                    .append(", ")
                    .append(group.executionCount())
                    .append(" executions\n\n");
                group
                    .countsByOrigin()
                    .forEach(
                        (origin, count) ->
                            output
                                .append("- `")
                                .append(origin)
                                .append("`: ")
                                .append(count)
                                .append('\n'));
                output.append("\n```text\n").append(group.sample()).append("\n```\n\n");
              });

      return output.toString();
    }

    private List<ContainmentRow> containmentRows() {
      List<ContainmentRow> rows = new ArrayList<>();
      sharedCounts.forEach(
          (pair, shared) -> {
            int firstDistinct = origins.get(pair.first()).distinctCount();
            int secondDistinct = origins.get(pair.second()).distinctCount();
            rows.add(
                new ContainmentRow(
                    pair.first(), pair.second(), shared, (double) shared / firstDistinct));
            rows.add(
                new ContainmentRow(
                    pair.second(), pair.first(), shared, (double) shared / secondDistinct));
          });
      rows.sort(
          Comparator.comparingDouble(ContainmentRow::containment)
              .reversed()
              .thenComparing(Comparator.comparingInt(ContainmentRow::shared).reversed()));
      return rows;
    }
  }

  private static final class Lifecycle {
    private final String kind;
    private final String test;
    private final List<Event> events = new ArrayList<>();

    Lifecycle(String kind, String test) {
      this.kind = kind;
      this.test = test;
    }

    void add(Event event) {
      events.add(event);
    }

    String origin() {
      return normalizeOrigin(test);
    }

    LifecycleFingerprint fingerprint() {
      events.sort(Comparator.comparingLong(Event::sequence));
      StringBuilder interaction = new StringBuilder();
      StringBuilder execution = new StringBuilder();
      append(interaction, kind);
      append(execution, kind);
      for (Event event : events) {
        append(interaction, event.method());
        append(interaction, event.arguments());
        append(execution, event.method());
        append(execution, event.arguments());
        append(execution, event.result());
        append(execution, event.exception());
      }
      return new LifecycleFingerprint(
          sha256(interaction.toString()),
          sha256(execution.toString()),
          kind + "/" + events.getFirst().method(),
          formatSample(events));
    }
  }

  private static final class FingerprintGroup {
    private final String category;
    private final String sample;
    private final Map<String, Integer> countsByOrigin = new TreeMap<>();
    private int executionCount;

    FingerprintGroup(String category, String sample) {
      this.category = category;
      this.sample = sample;
    }

    String category() {
      return category;
    }

    void add(String origin) {
      executionCount++;
      countsByOrigin.merge(origin, 1, Integer::sum);
    }

    String sample() {
      return sample;
    }

    int executionCount() {
      return executionCount;
    }

    Map<String, Integer> countsByOrigin() {
      return countsByOrigin;
    }

    Set<String> distinctOrigins() {
      return countsByOrigin.keySet();
    }

    List<String> distinctOriginList() {
      return new ArrayList<>(countsByOrigin.keySet());
    }
  }

  private static final class OriginStats {
    private final Set<String> distinct = new HashSet<>();
    private int executions;

    void add(String fingerprint) {
      executions++;
      distinct.add(fingerprint);
    }

    int executions() {
      return executions;
    }

    int distinctCount() {
      return distinct.size();
    }

    int internalRepeats() {
      return executions - distinct.size();
    }
  }

  private record Event(
      String kind,
      String test,
      long objectId,
      long sequence,
      String method,
      String arguments,
      String result,
      String exception) {

    static Event parse(String line) {
      String[] fields = line.split("\\t", -1);
      if (fields.length != 8) {
        throw new IllegalArgumentException("malformed recording event");
      }
      return new Event(
          fields[0],
          decode(fields[1]),
          Long.parseLong(fields[2]),
          Long.parseLong(fields[3]),
          decode(fields[4]),
          decode(fields[5]),
          decode(fields[6]),
          decode(fields[7]));
    }
  }

  private record LifecycleFingerprint(
      String interactionHash, String executionHash, String category, String sample) {}

  private record OriginPair(String first, String second) {
    static OriginPair sorted(String first, String second) {
      return first.compareTo(second) <= 0
          ? new OriginPair(first, second)
          : new OriginPair(second, first);
    }
  }

  private record ContainmentRow(
      String contained, String containing, int shared, double containment) {}

  private static String normalizeOrigin(String test) {
    return test.replaceAll("/\\[(?:test-template-invocation|repetition|dynamic-test):[^]]+]", "");
  }

  private static String decode(String value) {
    return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
  }

  private static void append(StringBuilder output, String value) {
    output.append(value.length()).append(':').append(value);
  }

  private static String formatSample(List<Event> events) {
    StringBuilder sample = new StringBuilder();
    for (Event event : events) {
      sample
          .append(event.method())
          .append('(')
          .append(formatArguments(event.arguments()))
          .append(')');
      if (!event.exception().isEmpty()) {
        sample.append(" throws ").append(event.exception());
      } else {
        sample.append(" -> ").append(formatValue(event.result()));
      }
      sample.append('\n');
    }
    return sample.toString().stripTrailing();
  }

  private static String formatArguments(String arguments) {
    List<String> values = new ArrayList<>();
    int offset = 0;
    while (offset < arguments.length()) {
      int colon = arguments.indexOf(':', offset);
      int length = Integer.parseInt(arguments.substring(offset, colon));
      int valueStart = colon + 1;
      values.add(formatValue(arguments.substring(valueStart, valueStart + length)));
      offset = valueStart + length;
    }
    return String.join(", ", values);
  }

  private static String formatValue(String value) {
    if (value.isEmpty()) {
      return "";
    }
    if (value.equals("N")) {
      return "null";
    }
    if (value.equals("B0")) {
      return "false";
    }
    if (value.equals("B1")) {
      return "true";
    }
    if (value.startsWith("I") || value.startsWith("F") || value.startsWith("C")) {
      return value.substring(1);
    }
    if (value.startsWith("S")) {
      int colon = value.indexOf(':');
      return '"' + escape(value.substring(colon + 1)) + '"';
    }
    if (value.startsWith("Y")) {
      int length = Base64.getDecoder().decode(value.substring(1)).length;
      return "<" + length + " bytes>";
    }
    return value;
  }

  private static String escape(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
        .replace("\"", "\\\"");
  }

  private static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }
}
