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

/** Evaluates the generated study through public JDK APIs. */
public final class StudyProbe {
  private static String bounds(MatchResult match, boolean captures) {
    StringBuilder result = new StringBuilder("[");
    for (int group = 0; group <= (captures ? match.groupCount() : 0); group++) {
      if (group > 0) {
        result.append(',');
      }
      if (match.start(group) < 0) {
        result.append("null");
      } else {
        result
            .append('[')
            .append(match.start(group))
            .append(',')
            .append(match.end(group))
            .append(']');
      }
    }
    return result.append(']').toString();
  }

  private static String operation(Pattern pattern, String text, String name, boolean captures) {
    Matcher matcher = pattern.matcher(text);
    boolean matched = name.equals("lookingAt") ? matcher.lookingAt() : matcher.matches();
    return matched ? bounds(matcher, captures) : "null";
  }

  private static String operations(Pattern pattern, String text, boolean captures) {
    Matcher matcher = pattern.matcher(text);
    List<String> history = new ArrayList<>();
    while (matcher.find()) {
      String before = bounds(matcher, captures);
      matcher.group();
      MatchResult snapshot = matcher.toMatchResult();
      if (!before.equals(bounds(snapshot, captures)) || !before.equals(bounds(matcher, captures))) {
        throw new AssertionError("Observing captures changed the match");
      }
      history.add(before);
    }
    return "{\"first\":"
        + (history.isEmpty() ? "null" : history.getFirst())
        + ",\"lookingAt\":"
        + operation(pattern, text, "lookingAt", captures)
        + ",\"matches\":"
        + operation(pattern, text, "matches", captures)
        + ",\"history\":["
        + String.join(",", history)
        + "]}";
  }

  private static String decode(String value) {
    return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
  }

  /** Runs the declared patterns and writes each result with its pattern and input identity. */
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    Files.writeString(root.resolve("java-runtime.txt"), Runtime.version() + "\n");
    List<String> inputs =
        Files.readAllLines(root.resolve("inputs.tsv")).stream().map(StudyProbe::decode).toList();
    try (BufferedWriter output = Files.newBufferedWriter(root.resolve("jdk.jsonl"))) {
      for (String line : Files.readAllLines(root.resolve("patterns.tsv"))) {
        String[] fields = line.split("\t", -1);
        int id = Integer.parseInt(fields[0]);
        Pattern pattern = Pattern.compile(decode(fields[1]));
        Pattern validation = fields[3].equals("1") ? Pattern.compile(decode(fields[2])) : null;
        for (int index = 0; index < inputs.size(); index++) {
          String text = inputs.get(index);
          output.write(
              "{\"pattern\":"
                  + id
                  + ",\"input\":"
                  + index
                  + ",\"actual\":"
                  + operations(pattern, text, true)
                  + ",\"expandedValidation\":"
                  + (validation == null ? "null" : operations(validation, text, false))
                  + "}\n");
        }
      }
    }
  }
}
