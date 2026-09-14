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
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("checks SafeRE's internal Unicode index initialization")
class UnicodeCaseIndexInitializationTest {
  private static final String INDEX_NAME = "org/safere/UnicodeCaseFolding$UnicodeCaseClosureIndex";
  // Generated crosscheck tests copy this disabled test without its SafeRE-only probe class.
  private static final String PROBE_CLASS_NAME = "org.safere.UnicodeCaseIndexInitializationProbe";

  @Test
  void ordinaryCharacterClassesDoNotBuildUnicodeCaseIndex()
      throws IOException, InterruptedException, URISyntaxException {
    for (String regex : new String[] {"[h-j]+", "[0-9]+", "[Kk]"}) {
      assertThat(runFreshProcess(regex))
          .as("index initialization for %s", regex)
          .doesNotContain(INDEX_NAME);
    }
  }

  @Test
  void unicodeCaseClassBuildsIndexWhenNeeded()
      throws IOException, InterruptedException, URISyntaxException {
    assertThat(runFreshProcess("(?iu)[K-K]")).contains(INDEX_NAME);
  }

  private static String runFreshProcess(String regex)
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
    Process process =
        new ProcessBuilder(java, "-Xlog:class+init=info", "-cp", classPath, PROBE_CLASS_NAME, regex)
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as(output).isZero();
    return output;
  }
}
