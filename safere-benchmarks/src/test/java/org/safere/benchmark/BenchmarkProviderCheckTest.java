// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BenchmarkProviderCheckTest {
  @Test
  void startupChecksLoadBothProvidersAndRejectMissingVectorModule() throws Exception {
    for (String provider : List.of("default", "vector")) {
      List<String> command = command(provider);
      if (provider.equals("vector")) {
        command.add(1, "--add-modules=jdk.incubator.vector");
      }
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(process.waitFor()).as(output).isZero();
      assertThat(output).contains("startup check passed: " + provider);
    }
    Process missing = new ProcessBuilder(command("vector")).redirectErrorStream(true).start();
    String output = new String(missing.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(missing.waitFor()).as(output).isNotZero();
    assertThat(output).contains("Could not enable", "--add-modules=jdk.incubator.vector");
  }

  private static List<String> command(String provider) {
    List<String> command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                BenchmarkProviderCheck.class.getName()));
    if (provider.equals("vector")) {
      command.add(1, "-Dorg.safere.experimental.vectorScanProvider=vector");
    }
    return command;
  }
}
