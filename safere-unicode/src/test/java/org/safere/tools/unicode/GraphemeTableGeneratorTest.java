// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.tools.unicode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphemeTableGeneratorTest {
  @TempDir Path temporary;

  @Test
  void parsesSingletonsRangesAndIndependentPropertyColumns() throws IOException {
    prepareData();
    GraphemeTableGenerator.Result result = generate();
    assertThat(result.unicodeVersion()).isEqualTo("17.0.0");
    Map<String, int[][]> output = result.tables();
    assertThat(output.get("GCB_CONTROL"))
        .isDeepEqualTo(new int[][] {{0xa, 0xa}, {0xd, 0xd}, {0x20, 0x22}});
    assertThat(output.get("GCB_ZWJ")).isDeepEqualTo(new int[][] {{0x200d, 0x200d}});
    assertThat(output.get("INCB_EXTEND")).isDeepEqualTo(new int[][] {{0x60, 0x61}});
    assertThat(output.get("EXTENDED_PICTOGRAPHIC")).isDeepEqualTo(new int[][] {{0xa9, 0xa9}});
    Map<String, int[][]> regenerated = generate().tables();
    assertThat(regenerated.keySet()).containsExactlyElementsOf(output.keySet());
    output.forEach((key, ranges) -> assertThat(regenerated.get(key)).isDeepEqualTo(ranges));
  }

  @Test
  void rejectsWrongVersionAndMissingRequiredProperty() throws IOException {
    prepareData();
    Path file = temporary.resolve("GraphemeBreakProperty.txt");
    String original = Files.readString(file);
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                GraphemeTableGenerator.generate(
                    GraphemeTableGenerator.Sources.inDirectory(temporary), "16.0.0"))
        .withMessageContaining("Wrong Unicode version");
    Files.writeString(file, original.replace("0040 ; Prepend", "#0040 ; Prepend"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> generate())
        .withMessageContaining("Missing Unicode property GCB_PREPEND");
  }

  @Test
  void rejectsOverlapsAndOutOfRangeCodePoints() throws IOException {
    prepareData();
    Path file = temporary.resolve("GraphemeBreakProperty.txt");
    String original = Files.readString(file);
    Files.writeString(file, original + "0021 ; Control\n");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> generate())
        .withMessageContaining("Overlapping Unicode ranges");
    Files.writeString(file, original + "110000 ; Control\n");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> generate())
        .withMessageContaining("Invalid range");
  }

  @Test
  void rejectsUnknownPropertyInsteadOfSilentlyDroppingIt() throws IOException {
    prepareData();
    Path file = temporary.resolve("DerivedCoreProperties.txt");
    Files.writeString(file, Files.readString(file) + "0010 ; InCB; FutureValue\n");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> generate())
        .withMessageContaining("Unknown InCB value");
  }

  @Test
  void rejectsFilesThatDisagreeOnVersion() throws IOException {
    prepareData();
    Path file = temporary.resolve("DerivedCoreProperties.txt");
    Files.writeString(file, Files.readString(file).replace("17.0.0", "18.0.0"));
    assertThatIllegalArgumentException()
        .isThrownBy(this::generate)
        .withMessageContaining("disagree on version");
    Files.writeString(file, Files.readString(file).replace("18.0.0", "17.0.0"));
    Path emoji = temporary.resolve("emoji-data.txt");
    Files.writeString(emoji, Files.readString(emoji).replace("17.0", "16.0"));
    assertThatIllegalArgumentException()
        .isThrownBy(this::generate)
        .withMessageContaining("disagree on version");
  }

  @Test
  void acceptsAnotherVersionWhenExplicitlyExpected() throws IOException {
    prepareData();
    for (String name :
        List.of("GraphemeBreakProperty.txt", "DerivedCoreProperties.txt", "emoji-data.txt")) {
      Path file = temporary.resolve(name);
      Files.writeString(file, Files.readString(file).replace("17.0", "18.0"));
    }
    assertThatIllegalArgumentException()
        .isThrownBy(this::generate)
        .withMessageContaining("expected 17.0.0 but data files declare 18.0.0");
    GraphemeTableGenerator.Result result =
        GraphemeTableGenerator.generate(
            GraphemeTableGenerator.Sources.inDirectory(temporary), "18.0.0");
    assertThat(result.unicodeVersion()).isEqualTo("18.0.0");
  }

  @Test
  void rejectsMissingVersionHeader() throws IOException {
    prepareData();
    Path file = temporary.resolve("emoji-data.txt");
    Files.writeString(file, Files.readString(file).replace("# Version: 17.0\n", ""));
    assertThatIllegalArgumentException()
        .isThrownBy(this::generate)
        .withMessageContaining("Missing Unicode version header");
  }

  @Test
  void readsFilesFromANestedLayout() throws IOException {
    prepareData();
    Path auxiliary = Files.createDirectory(temporary.resolve("auxiliary"));
    Path emoji = Files.createDirectory(temporary.resolve("emoji"));
    Files.move(
        temporary.resolve("GraphemeBreakProperty.txt"),
        auxiliary.resolve("GraphemeBreakProperty.txt"));
    Files.move(temporary.resolve("emoji-data.txt"), emoji.resolve("emoji-data.txt"));
    GraphemeTableGenerator.Result result =
        GraphemeTableGenerator.generate(
            new GraphemeTableGenerator.Sources(
                auxiliary.resolve("GraphemeBreakProperty.txt"),
                temporary.resolve("DerivedCoreProperties.txt"),
                emoji.resolve("emoji-data.txt")),
            GraphemeTableGenerator.DEFAULT_UNICODE_VERSION);
    assertThat(result.tables().get("GCB_ZWJ")).isDeepEqualTo(new int[][] {{0x200d, 0x200d}});
  }

  private GraphemeTableGenerator.Result generate() throws IOException {
    return GraphemeTableGenerator.generate(
        GraphemeTableGenerator.Sources.inDirectory(temporary),
        GraphemeTableGenerator.DEFAULT_UNICODE_VERSION);
  }

  private void prepareData() throws IOException {
    Files.writeString(
        temporary.resolve("GraphemeBreakProperty.txt"),
        """
        # GraphemeBreakProperty-17.0.0.txt
        # @missing: 0000..10FFFF; Other
        000D ; CR
        000A ; LF
        0020..0021 ; Control # comment
        0022 ; Control
        0030 ; Extend
        0040 ; Prepend
        0050 ; SpacingMark
        0060 ; L
        0070 ; V
        0080 ; T
        0090 ; LV
        00A0 ; LVT
        200D ; ZWJ
        1F1E6..1F1FF ; Regional_Indicator
        """);
    Files.writeString(
        temporary.resolve("DerivedCoreProperties.txt"),
        """
        # DerivedCoreProperties-17.0.0.txt
        0030 ; Alphabetic
        0040 ; InCB; Linker
        0050 ; InCB ; Consonant
        0060..0061 ; InCB; Extend # comment
        """);
    Files.writeString(
        temporary.resolve("emoji-data.txt"),
        """
        # Version: 17.0
        0023 ; Emoji
        00A9 ; Extended_Pictographic# comment without preceding space
        """);
  }
}
