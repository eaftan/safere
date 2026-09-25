// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.tools.unicode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class UnicodeTableGeneratorOptionsTest {
  @Test
  void defaultsToCheckedInData() {
    UnicodeTableGenerator.Options options = UnicodeTableGenerator.Options.parse();
    Path data = Path.of("safere-unicode/data/17.0.0");
    assertThat(options.output())
        .isEqualTo(Path.of("safere/src/main/java/org/safere/UnicodeGeneratedTables.java"));
    assertThat(options.sources()).isEqualTo(GraphemeTableGenerator.Sources.inDirectory(data));
    assertThat(options.unicodeLicense()).isEqualTo(data.resolve("LICENSE.txt"));
    assertThat(options.unicodeVersion()).isEqualTo("17.0.0");
  }

  @Test
  void outputOnlyKeepsDefaultData() {
    UnicodeTableGenerator.Options options = UnicodeTableGenerator.Options.parse("out/Tables.java");
    assertThat(options.output()).isEqualTo(Path.of("out/Tables.java"));
    assertThat(options.sources())
        .isEqualTo(
            GraphemeTableGenerator.Sources.inDirectory(Path.of("safere-unicode/data/17.0.0")));
  }

  @Test
  void dataDirectoryAndPerFileOverrides() {
    UnicodeTableGenerator.Options options =
        UnicodeTableGenerator.Options.parse(
            "--unicode-data=ucd",
            "--grapheme-break-property=ucd/auxiliary/GraphemeBreakProperty.txt",
            "--emoji-data=ucd/emoji/emoji-data.txt",
            "--unicode-license=LICENSE",
            "--unicode-version=18.0.0",
            "out/Tables.java");
    assertThat(options.output()).isEqualTo(Path.of("out/Tables.java"));
    assertThat(options.sources())
        .isEqualTo(
            new GraphemeTableGenerator.Sources(
                Path.of("ucd/auxiliary/GraphemeBreakProperty.txt"),
                Path.of("ucd/DerivedCoreProperties.txt"),
                Path.of("ucd/emoji/emoji-data.txt")));
    assertThat(options.unicodeLicense()).isEqualTo(Path.of("LICENSE"));
    assertThat(options.unicodeVersion()).isEqualTo("18.0.0");
  }

  @Test
  void rejectsUnknownOptionsMissingValuesAndExtraOutputs() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> UnicodeTableGenerator.Options.parse("--unicode-dat=ucd"))
        .withMessageContaining("Usage:");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> UnicodeTableGenerator.Options.parse("--unicode-version="))
        .withMessageContaining("Usage:");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> UnicodeTableGenerator.Options.parse("a.java", "b.java"))
        .withMessageContaining("Usage:");
  }
}
