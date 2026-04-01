// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package dev.eaftan.safere;

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

  private static final String LINE_1 =
      "// This file is part of a Java port of RE2 (https://github.com/google/re2).";
  private static final String LINE_2 =
      "// Original RE2 code is Copyright (c) 2009 The RE2 Authors.";
  private static final String LINE_3 =
      "// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.";
  private static final String LINE_4 =
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
      return lines.size() >= 4
          && lines.get(0).equals(LINE_1)
          && lines.get(1).equals(LINE_2)
          && lines.get(2).equals(LINE_3)
          && lines.get(3).equals(LINE_4);
    } catch (IOException e) {
      return false;
    }
  }
}
