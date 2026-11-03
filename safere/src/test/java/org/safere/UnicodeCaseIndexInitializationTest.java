// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisabledForCrosscheck("checks SafeRE's internal Unicode index initialization")
class UnicodeCaseIndexInitializationTest {
  private static final String INDEX_NAME = "org/safere/UnicodeCaseFolding$UnicodeCaseClosureIndex";
  // Generated crosscheck tests copy this disabled test without its SafeRE-only probe class.
  private static final String PROBE_CLASS_NAME = "org.safere.UnicodeCaseIndexInitializationProbe";

  @ParameterizedTest
  @ValueSource(
      strings = {
        "[h-j]+",
        "[0-9]+",
        "[Kk]",
        "[A-Za-z]+",
        "[AaBbCc]+",
        "\\p{L}+",
        "\\p{IsAlphabetic}+",
        "\\p{script=Latin}+",
        "\\p{L}[\\p{L}\\p{Nd}_]*"
      })
  void ordinaryCharacterClassesDoNotBuildUnicodeCaseIndex(String regex)
      throws IOException, InterruptedException, URISyntaxException {
    assertThat(runFreshProcess(regex))
        .as("index initialization for %s", regex)
        .doesNotContain(INDEX_NAME);
  }

  @Test
  void unicodeCaseClassBuildsIndexWithinSmallHeap()
      throws IOException, InterruptedException, URISyntaxException {
    assertThat(runFreshProcess("(?iu)[K-K]")).contains(INDEX_NAME);
  }

  @ParameterizedTest
  @ValueSource(strings = {"C", "C.UTF-8"})
  void fourMemberFoldClassStillBuildsIndexDuringCompilation(String locale)
      throws IOException, InterruptedException, URISyntaxException {
    assertThat(runFreshProcess("[Θθϑϴ]", locale)).contains(INDEX_NAME);
  }

  private static String runFreshProcess(String regex)
      throws IOException, InterruptedException, URISyntaxException {
    return runFreshProcess(regex, "");
  }

  private static String runFreshProcess(String regex, String locale)
      throws IOException, InterruptedException, URISyntaxException {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String pathSeparator = System.getProperty("path.separator");
    String classPath =
        Path.of(Pattern.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            + pathSeparator
            + Path.of(
                UnicodeCaseIndexInitializationTest.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
    // Process arguments use the platform's native encoding, which may be ASCII in isolation.
    String encodedRegex =
        Base64.getEncoder().encodeToString(regex.getBytes(StandardCharsets.UTF_8));
    ProcessBuilder processBuilder =
        new ProcessBuilder(
                java,
                "-Xmx12m",
                "-Xlog:class+init=info",
                "-cp",
                classPath,
                PROBE_CLASS_NAME,
                encodedRegex)
            .redirectErrorStream(true);
    if (!locale.isEmpty()) {
      processBuilder.environment().put("LC_ALL", locale);
    }
    Process process = processBuilder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as(output).isZero();
    return output;
  }
}
