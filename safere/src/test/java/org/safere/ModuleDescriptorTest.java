// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisabledForCrosscheck("requires SafeRE's named module and the JDK Vector module")
class ModuleDescriptorTest {
  private static final String VECTOR_MODULE_NAME = "jdk.incubator.vector";

  @TempDir Path temporaryDirectory;

  @Test
  void doesNotExposeIncubatorModulesToConsumers() throws URISyntaxException {
    ModuleDescriptor descriptor =
        ModuleFinder.of(safeReModulePath())
            .find("org.safere")
            .orElseThrow(() -> new AssertionError("SafeRE module descriptor not found"))
            .descriptor();

    assertThat(descriptor.requires())
        .extracting(ModuleDescriptor.Requires::name)
        .noneMatch(name -> name.startsWith("jdk.incubator."));
  }

  @Test
  void strictModularConsumerCanEnableVectorProvider()
      throws IOException, InterruptedException, URISyntaxException {
    Path sourceDirectory = temporaryDirectory.resolve("src");
    Path packageDirectory = sourceDirectory.resolve("consumer");
    Path classesDirectory = temporaryDirectory.resolve("classes");
    Files.createDirectories(packageDirectory);
    Files.createDirectories(classesDirectory);
    Files.writeString(
        sourceDirectory.resolve("module-info.java"),
        "module safere.consumer { requires org.safere; }\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        packageDirectory.resolve("Main.java"),
        """
        package consumer;

        import java.util.Arrays;
        import org.safere.Pattern;
        import org.safere.Utf8Input;

        public final class Main {
          public static void main(String[] args) {
            byte[] input = new byte[2048];
            Arrays.fill(input, (byte) 'a');
            input[input.length - 1] = 'z';
            if (!Pattern.compile("[z]").find(Utf8Input.trusted(input))) {
              throw new AssertionError("Vector-enabled search did not find the final byte");
            }
          }
        }
        """,
        StandardCharsets.UTF_8);

    Path safeReModulePath = safeReModulePath();
    ProcessResult compilation =
        run(
            javaTool("javac"),
            "-Werror",
            "--module-path",
            safeReModulePath.toString(),
            "-d",
            classesDirectory.toString(),
            sourceDirectory.resolve("module-info.java").toString(),
            packageDirectory.resolve("Main.java").toString());
    assertThat(compilation.exitCode()).as(compilation.output()).isZero();

    ProcessResult execution =
        run(
            javaTool("java"),
            "--add-modules",
            VECTOR_MODULE_NAME,
            "-Dorg.safere.experimental.vectorScanProvider=vector",
            "--module-path",
            safeReModulePath + System.getProperty("path.separator") + classesDirectory,
            "--module",
            "safere.consumer/consumer.Main");
    assertThat(execution.exitCode()).as(execution.output()).isZero();
  }

  private static String javaTool(String name) {
    String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
    return Path.of(System.getProperty("java.home"), "bin", name + suffix).toString();
  }

  private static Path safeReModulePath() throws URISyntaxException {
    return Path.of(Pattern.class.getProtectionDomain().getCodeSource().getLocation().toURI());
  }

  private static ProcessResult run(String... command) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new ProcessResult(process.waitFor(), output);
  }

  private record ProcessResult(int exitCode, String output) {}
}
