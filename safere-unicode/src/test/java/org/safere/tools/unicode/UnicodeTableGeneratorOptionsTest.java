// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.tools.unicode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UnicodeTableGeneratorOptionsTest {
  @Test
  void defaultsToCheckedInLicense() {
    UnicodeTableGenerator.Options options = UnicodeTableGenerator.Options.parse();
    assertThat(options.output())
        .isEqualTo(Path.of("safere/src/main/java/org/safere/UnicodeGeneratedTables.java"));
    assertThat(options.unicodeLicense())
        .isEqualTo(Path.of("safere/src/main/resources/META-INF/LICENSE-Unicode.txt"));
  }

  @Test
  void outputOnlyKeepsDefaultLicense() {
    UnicodeTableGenerator.Options options = UnicodeTableGenerator.Options.parse("out/Tables.java");
    assertThat(options.output()).isEqualTo(Path.of("out/Tables.java"));
    assertThat(options.unicodeLicense())
        .isEqualTo(Path.of("safere/src/main/resources/META-INF/LICENSE-Unicode.txt"));
  }

  @Test
  void licenseAndOutputOverrides() {
    UnicodeTableGenerator.Options options =
        UnicodeTableGenerator.Options.parse("--unicode-license=LICENSE", "out/Tables.java");
    assertThat(options.output()).isEqualTo(Path.of("out/Tables.java"));
    assertThat(options.unicodeLicense()).isEqualTo(Path.of("LICENSE"));
  }

  @Test
  void rejectsUnknownOptionsMissingValuesAndExtraOutputs() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> UnicodeTableGenerator.Options.parse("--unicode-data=ucd"))
        .withMessageContaining("Usage:");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> UnicodeTableGenerator.Options.parse("--unicode-license="))
        .withMessageContaining("Usage:");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> UnicodeTableGenerator.Options.parse("a.java", "b.java"))
        .withMessageContaining("Usage:");
  }

  @Test
  void buildsGraphemeTablesFromIcu4j() {
    Map<String, int[][]> tables = UnicodeTableGenerator.buildGraphemeTables();
    assertThat(tables)
        .containsOnlyKeys(
            "Control",
            "Extend",
            "Prepend",
            "SpacingMark",
            "L",
            "V",
            "T",
            "LV",
            "LVT",
            "InCB_Linker",
            "InCB_Consonant",
            "InCB_Extend");
    tables.forEach(
        (name, ranges) -> {
          assertThat(ranges).as(name).isNotEmpty();
          for (int i = 0; i < ranges.length; i++) {
            assertThat(ranges[i]).hasSize(2);
            assertThat(ranges[i][0]).isBetween(0, ranges[i][1]);
            assertThat(ranges[i][1]).isLessThanOrEqualTo(Character.MAX_CODE_POINT);
            if (i > 0) {
              assertThat(ranges[i][0]).isGreaterThan(ranges[i - 1][1] + 1);
            }
          }
        });
  }
}
