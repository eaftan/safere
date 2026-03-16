// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
      "// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.";
  private static final String LINE_2 =
      "// See LICENSE file in the project root for details.";

  @Test
  void allJavaFilesHaveLicenseHeader() throws IOException {
    Path root = Paths.get("").toAbsolutePath();
    Path srcDir = root.resolve("src");
    assertTrue(Files.isDirectory(srcDir), "src/ directory not found at " + root);

    List<Path> violations;
    try (Stream<Path> files = Files.walk(srcDir)) {
      violations =
          files
              .filter(p -> p.toString().endsWith(".java"))
              .filter(p -> !hasLicenseHeader(p))
              .toList();
    }

    assertTrue(
        violations.isEmpty(),
        "The following files are missing the license header:\n"
            + String.join("\n", violations.stream().map(Path::toString).toList()));
  }

  private static boolean hasLicenseHeader(Path path) {
    try {
      List<String> lines = Files.readAllLines(path);
      return lines.size() >= 2
          && lines.get(0).equals(LINE_1)
          && lines.get(1).equals(LINE_2);
    } catch (IOException e) {
      return false;
    }
  }
}
