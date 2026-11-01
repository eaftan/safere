// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see ../../LICENSE).

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Collects JDK-only policy observations through supported public APIs. */
public final class OtherRulesProbe {
  private static String bounds(MatchResult match, boolean captures) {
    StringBuilder result = new StringBuilder("[");
    for (int group = 0; group <= (captures ? match.groupCount() : 0); group++) {
      if (group > 0) {
        result.append(',');
      }
      if (match.start(group) < 0) {
        result.append("null");
      } else {
        result.append('[').append(match.start(group)).append(',').append(match.end(group));
        result.append(']');
      }
    }
    return result.append(']').toString();
  }

  private static String anchored(Pattern pattern, String text, boolean full) {
    Matcher matcher = pattern.matcher(text);
    return (full ? matcher.matches() : matcher.lookingAt()) ? bounds(matcher, true) : "null";
  }

  private static List<MatchResult> history(Pattern pattern, String text) {
    List<MatchResult> result = new ArrayList<>();
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      result.add(matcher.toMatchResult());
    }
    return result;
  }

  private static String operations(Pattern pattern, String text, List<MatchResult> matches) {
    List<String> spans = matches.stream().map(m -> bounds(m, true)).toList();
    return "{\"first\":"
        + (spans.isEmpty() ? "null" : spans.getFirst())
        + ",\"lookingAt\":"
        + anchored(pattern, text, false)
        + ",\"matches\":"
        + anchored(pattern, text, true)
        + ",\"history\":["
        + String.join(",", spans)
        + "]}";
  }

  private static String captureText(MatchResult match) {
    StringBuilder result = new StringBuilder("<");
    for (int group = 1; group <= match.groupCount(); group++) {
      result.append(match.group(group) == null ? "" : match.group(group)).append('|');
    }
    return result.append('>').toString();
  }

  private static String replacement(List<MatchResult> matches, String text, boolean captures) {
    StringBuilder result = new StringBuilder();
    int previous = 0;
    for (MatchResult match : matches) {
      result.append(text, previous, match.start());
      result.append(captures ? captureText(match) : "X");
      previous = match.end();
    }
    return result.append(text, previous, text.length()).toString();
  }

  private static List<String> split(List<MatchResult> matches, String text, int limit) {
    List<String> result = new ArrayList<>();
    int previous = 0;
    for (MatchResult match : matches) {
      if (limit > 0 && result.size() == limit - 1) {
        break;
      }
      if (previous == 0 && match.start() == 0 && match.end() == 0) {
        continue;
      }
      result.add(text.substring(previous, match.start()));
      previous = match.end();
    }
    if (previous == 0 && result.isEmpty()) {
      return List.of(text);
    }
    result.add(text.substring(previous));
    if (limit == 0) {
      while (!result.isEmpty() && result.getLast().isEmpty()) {
        result.removeLast();
      }
    }
    return result;
  }

  private static int different(Object expected, Object actual) {
    return expected.equals(actual) ? 0 : 1;
  }

  private static String checks(Pattern pattern, String text, List<MatchResult> matches) {
    Matcher observed = pattern.matcher(text);
    Matcher plain = pattern.matcher(text);
    int observation = 0;
    int index = 0;
    while (plain.find()) {
      if (!observed.find()) {
        throw new AssertionError("Observation changed find exhaustion");
      }
      observation += different(bounds(plain, false), bounds(observed, false));
      observation += different(bounds(matches.get(index++), true), bounds(observed, true));
      for (int group = observed.groupCount(); group >= 0; group--) {
        observed.group(group);
      }
      observation += different(bounds(observed, true), bounds(observed.toMatchResult(), true));
    }
    if (observed.find()) {
      throw new AssertionError("Observation added a result");
    }
    observed.reset();
    boolean first = observed.find();
    int reset = first == !matches.isEmpty() ? 0 : 1;
    if (first) {
      reset += different(bounds(matches.getFirst(), true), bounds(observed, true));
    }
    StringBuilder template = new StringBuilder("<");
    for (int group = 1; group <= observed.groupCount(); group++) {
      template.append('$').append(group).append('|');
    }
    template.append('>');
    String expected = replacement(matches, text, true);
    StringBuilder appended = new StringBuilder();
    Matcher appendMatcher = pattern.matcher(text);
    while (appendMatcher.find()) {
      appendMatcher.appendReplacement(appended, template.toString());
    }
    appendMatcher.appendTail(appended);
    return "{\"observation\":"
        + observation
        + ",\"reset\":"
        + reset
        + ",\"literalReplacement\":"
        + different(replacement(matches, text, false), pattern.matcher(text).replaceAll("X"))
        + ",\"captureReplacement\":"
        + different(expected, pattern.matcher(text).replaceAll(template.toString()))
        + ",\"functionalReplacement\":"
        + different(expected, pattern.matcher(text).replaceAll(OtherRulesProbe::captureText))
        + ",\"appendReplacement\":"
        + different(expected, appended.toString())
        + ",\"splitNegative\":"
        + different(split(matches, text, -1), List.of(pattern.split(text, -1)))
        + ",\"splitZero\":"
        + different(split(matches, text, 0), List.of(pattern.split(text, 0)))
        + ",\"splitPositive\":"
        + different(split(matches, text, 2), List.of(pattern.split(text, 2)))
        + "}";
  }

  private static String decode(String value) {
    return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
  }

  private static String supplementaryControls() {
    List<String> subjects = List.of("😀", "a😀", "😀a", "😀😀");
    List<String> expressions = List.of("", "()", "(?:|a)", "(?:|.)");
    int utf16 = 0;
    int scalar = 0;
    int neither = 0;
    for (String text : subjects) {
      List<String> units = new ArrayList<>();
      for (int position = 0; position <= text.length(); position++) {
        units.add("[[" + position + "," + position + "]]");
      }
      List<String> points = new ArrayList<>();
      for (int position = 0; ; position = text.offsetByCodePoints(position, 1)) {
        points.add("[[" + position + "," + position + "]]");
        if (position == text.length()) {
          break;
        }
      }
      for (String expression : expressions) {
        List<String> actual =
            history(Pattern.compile(expression), text).stream()
                .map(match -> bounds(match, false))
                .toList();
        if (actual.equals(units)) {
          utf16++;
        } else if (actual.equals(points)) {
          scalar++;
        } else {
          neither++;
        }
      }
    }
    StringBuilder starts = new StringBuilder();
    for (String expression : List.of("(?:(a)){0,2}b", "(?:(ab)){0,2}b")) {
      String text = expression.contains("(ab)") ? " ab" : "a b";
      Pattern pattern = Pattern.compile(expression);
      Matcher find = pattern.matcher(text);
      if (!find.find()) {
        throw new AssertionError("Missing failed-start control match");
      }
      Matcher fresh = pattern.matcher(text).region(find.start(), text.length());
      if (!fresh.find()) {
        throw new AssertionError("Missing isolated-start control match");
      }
      if (!starts.isEmpty()) {
        starts.append(',');
      }
      starts
          .append("{\"regex\":\"")
          .append(expression)
          .append("\",\"text\":\"")
          .append(text)
          .append("\",\"find\":")
          .append(bounds(find, true))
          .append(",\"isolatedStart\":")
          .append(bounds(fresh, true))
          .append('}');
    }
    return "{\"unicodeEmptyCursor\":{\"utf16\":"
        + utf16
        + ",\"scalar\":"
        + scalar
        + ",\"neither\":"
        + neither
        + "},\"failedStartControls\":["
        + starts
        + "]}";
  }

  /** Runs a declared corpus and records explicit pattern/subject identities. */
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    Files.writeString(root.resolve("java-runtime.txt"), Runtime.version() + "\n");
    Files.writeString(root.resolve("auxiliary.json"), supplementaryControls() + "\n");
    List<String> inputs =
        Files.readAllLines(root.resolve("inputs.tsv")).stream()
            .map(OtherRulesProbe::decode)
            .toList();
    try (BufferedWriter output = Files.newBufferedWriter(root.resolve("jdk.jsonl"))) {
      for (String line : Files.readAllLines(root.resolve("patterns.tsv"))) {
        String[] fields = line.split("\t", -1);
        int id = Integer.parseInt(fields[0]);
        Pattern pattern = Pattern.compile(decode(fields[1]));
        Pattern pair = fields[2].isEmpty() ? null : Pattern.compile(decode(fields[2]));
        for (int index = 0; index < inputs.size(); index++) {
          String text = inputs.get(index);
          List<MatchResult> matches = history(pattern, text);
          output.write(
              "{\"pattern\":"
                  + id
                  + ",\"input\":"
                  + index
                  + ",\"actual\":"
                  + operations(pattern, text, matches)
                  + ",\"paired\":"
                  + (pair == null ? "null" : operations(pair, text, history(pair, text)))
                  + ",\"checks\":"
                  + checks(pattern, text, matches)
                  + "}\n");
        }
      }
    }
  }
}
