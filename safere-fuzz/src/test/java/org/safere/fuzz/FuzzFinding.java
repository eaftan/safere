// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Lossless finding context, separate from Jazzer's raw reproducer and run metadata. */
final class FuzzFinding {
  private FuzzFinding() {}

  static AssertionError failure(
      String regex,
      int flags,
      String input,
      String operation,
      String state,
      String history,
      String safeRe,
      String jdk) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("target", System.getProperty("safere.fuzz.target", "local-test"));
    fields.put("commit", System.getProperty("safere.fuzz.commit", "unrecorded"));
    fields.put("rawReproducerDirectory", System.getProperty("jazzer.reproducer_path", "default"));
    fields.put("divergenceSignals", org.safere.FuzzDivergenceEvidence.observed());
    fields.put("regex", regex);
    fields.put("flags", Integer.toString(flags));
    fields.put("input", input);
    fields.put("operation", operation);
    fields.put("state", state);
    fields.put("history", history);
    fields.put("safeRe", safeRe);
    fields.put("jdk", jdk);
    fields.put("javaVersion", System.getProperty("java.runtime.version"));
    String json = json(fields);
    AssertionError finding = new AssertionError(operation + " divergence\n" + json);
    String directory = System.getProperty("safere.fuzz.findingsDir");
    if (directory != null) {
      try {
        Path output = Path.of(directory);
        Files.createDirectories(output);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String digest =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Files.writeString(output.resolve(digest + ".json"), json + "\n");
      } catch (IOException | NoSuchAlgorithmException exception) {
        // Preserve the semantic finding even if evidence cannot be persisted.
        finding.addSuppressed(exception);
      }
    }
    return finding;
  }

  static String json(Map<String, String> fields) {
    StringBuilder json = new StringBuilder("{");
    String separator = "";
    for (var entry : fields.entrySet()) {
      json.append(separator)
          .append(FuzzSupport.javaStringLiteral(entry.getKey()))
          .append(":")
          .append(FuzzSupport.javaStringLiteral(entry.getValue()));
      separator = ",";
    }
    return json.append('}').toString();
  }
}
