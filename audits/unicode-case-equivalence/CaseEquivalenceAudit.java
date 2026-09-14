// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Black-box audit of case equivalence; run with SafeRE on the class path. */
public final class CaseEquivalenceAudit {
  private static final int LIMIT = Character.MAX_CODE_POINT + 1;

  /** Takes the Unicode 17 CaseFolding.txt path and an output directory. */
  public static void main(String[] args) throws Exception {
    Path data = Path.of(args[0]);
    Path output = Path.of(args[1]);
    Files.createDirectories(output);
    int[] parent = new int[LIMIT];
    int[] fold = new int[LIMIT];
    for (int cp = 0; cp < LIMIT; cp++) {
      parent[cp] = cp;
      fold[cp] = cp;
    }
    for (String line : Files.readAllLines(data)) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String[] fields = line.split(";");
      if (fields[1].trim().equals("C") || fields[1].trim().equals("S")) {
        int source = Integer.parseInt(fields[0].trim(), 16);
        int target = Integer.parseInt(fields[2].trim(), 16);
        fold[source] = target;
        join(parent, source, target);
      }
    }
    for (int cp = 0; cp < LIMIT; cp++) {
      join(parent, cp, Character.toUpperCase(cp));
      join(parent, cp, Character.toLowerCase(cp));
      join(parent, cp, Character.toTitleCase(cp));
    }
    Map<Integer, List<Integer>> families = new TreeMap<>();
    for (int cp = 0; cp < LIMIT; cp++) {
      int root = root(parent, cp);
      if (root != cp) {
        families.computeIfAbsent(root, key -> new ArrayList<>(List.of(key))).add(cp);
      }
    }
    List<Integer> participants = families.values().stream().flatMap(List::stream).sorted().toList();
    List<String> familyLines = new ArrayList<>();
    List<String> extraLinks = new ArrayList<>();
    for (List<Integer> family : families.values()) {
      String formatted = family.stream().map(CaseEquivalenceAudit::hex)
          .reduce((a, b) -> a + " " + b).orElseThrow();
      familyLines.add(formatted);
      if (family.stream().map(cp -> fold[cp]).distinct().count() > 1) {
        extraLinks.add(formatted);
      }
    }
    Files.write(output.resolve("families.txt"), familyLines);
    Files.write(output.resolve("broader-than-simple-fold.txt"), extraLinks);
    List<String> differences = new ArrayList<>();
    differences.add("engine\tsyntax\tpattern\tinput\texpected\tactual");
    long pairs = 0;
    long jdkDifferences = 0;
    long safeDifferences = 0;
    int flags = java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE;
    for (int source : participants) {
      String literal = "\\x{" + Integer.toHexString(source) + "}";
      String[] regexes = {literal, "[" + literal + "]", "[" + literal + "-" + literal + "]",
          "[^" + literal + "]", "[^" + literal + "-" + literal + "]"};
      for (int syntax = 0; syntax < regexes.length; syntax++) {
        java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regexes[syntax], flags)
            .matcher("");
        org.safere.Matcher safe = org.safere.Pattern.compile(regexes[syntax], flags).matcher("");
        for (int target : participants) {
          boolean expected = root(parent, source) == root(parent, target);
          if (syntax >= 3) {
            expected = !expected;
          }
          String input = new String(Character.toChars(target));
          boolean jdkActual = jdk.reset(input).matches();
          boolean safeActual = safe.reset(input).matches();
          pairs++;
          if (jdkActual != expected) {
            jdkDifferences++;
            differences.add("JDK\t" + syntax + "\t" + hex(source) + "\t" + hex(target)
                + "\t" + expected + "\t" + jdkActual);
          }
          if (safeActual != expected) {
            safeDifferences++;
            differences.add("SafeRE\t" + syntax + "\t" + hex(source) + "\t" + hex(target)
                + "\t" + expected + "\t" + safeActual);
          }
        }
      }
    }
    Files.write(output.resolve("differences.tsv"), differences);
    String summary = "java.runtime.version=" + System.getProperty("java.runtime.version")
        + "\ncaseFolding.sha256=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(data)))
        + "\nfamilies=" + families.size() + "\nparticipants=" + participants.size()
        + "\nbroaderFamilies=" + extraLinks.size() + "\npairsPerEngine=" + pairs
        + "\njdkDifferences=" + jdkDifferences + "\nsafeDifferences=" + safeDifferences + "\n";
    Files.writeString(output.resolve("summary.txt"), summary);
    System.out.print(summary);
  }

  private static String hex(int cp) {
    return "U+%04X".formatted(cp);
  }

  private static int root(int[] parent, int cp) {
    while (parent[cp] != cp) {
      parent[cp] = parent[parent[cp]];
      cp = parent[cp];
    }
    return cp;
  }

  private static void join(int[] parent, int a, int b) {
    int first = root(parent, a);
    int second = root(parent, b);
    parent[Math.max(first, second)] = Math.min(first, second);
  }
}
