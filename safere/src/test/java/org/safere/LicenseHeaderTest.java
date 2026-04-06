// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Verifies that all Java source files include the required license header. */
class LicenseHeaderTest {

  // Standard header (4 lines) — files derived from C++ RE2 only.
  private static final String LINE_1 =
      "// This file is part of a Java port of RE2 (https://github.com/google/re2).";
  private static final String LINE_2 =
      "// Original RE2 code is Copyright (c) 2009 The RE2 Authors.";
  private static final String LINE_3 =
      "// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.";
  private static final String LINE_4 =
      "// Licensed under the BSD 3-Clause License (see LICENSE file).";

  // Extended header (6 lines) — files that also incorporate code from RE2/J.
  private static final String RE2J_LINE_3 =
      "// Portions derived from RE2/J (https://github.com/google/re2j),";
  private static final String RE2J_LINE_4 =
      "// Copyright (c) 2009 The Go Authors.";
  private static final String RE2J_LINE_5 =
      "// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.";
  private static final String RE2J_LINE_6 =
      "// Licensed under the BSD 3-Clause License (see LICENSE file).";

  @Test
  void allJavaFilesHaveLicenseHeader() throws IOException {
    Path root = Paths.get("").toAbsolutePath();
    Path srcDir = root.resolve("src");
    assertThat(Files.isDirectory(srcDir))
        .withFailMessage("src/ directory not found at " + root)
        .isTrue();

    List<Path> violations;
    try (Stream<Path> files = Files.walk(srcDir)) {
      violations =
          files
              .filter(p -> p.toString().endsWith(".java"))
              .filter(p -> !hasLicenseHeader(p))
              .toList();
    }

    assertThat(violations.isEmpty())
        .withFailMessage(
            "The following files are missing the license header:\n"
                + String.join("\n", violations.stream().map(Path::toString).toList()))
        .isTrue();
  }

  private static boolean hasLicenseHeader(Path path) {
    try {
      List<String> lines = Files.readAllLines(path);
      return hasStandardHeader(lines) || hasRe2jHeader(lines);
    } catch (IOException e) {
      return false;
    }
  }

  /** Standard 4-line header for files derived from C++ RE2 only. */
  private static boolean hasStandardHeader(List<String> lines) {
    return lines.size() >= 4
        && lines.get(0).equals(LINE_1)
        && lines.get(1).equals(LINE_2)
        && lines.get(2).equals(LINE_3)
        && lines.get(3).equals(LINE_4);
  }

  /** Extended 6-line header for files that also incorporate code from RE2/J. */
  private static boolean hasRe2jHeader(List<String> lines) {
    return lines.size() >= 6
        && lines.get(0).equals(LINE_1)
        && lines.get(1).equals(LINE_2)
        && lines.get(2).equals(RE2J_LINE_3)
        && lines.get(3).equals(RE2J_LINE_4)
        && lines.get(4).equals(RE2J_LINE_5)
        && lines.get(5).equals(RE2J_LINE_6);
  }
}
