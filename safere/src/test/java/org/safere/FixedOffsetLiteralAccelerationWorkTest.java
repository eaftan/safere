// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisabledForCrosscheck("uses internal work counters to verify acceleration bounds")
@Tag("work-counter")
class FixedOffsetLiteralAccelerationWorkTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void denseLiteralsWithAbsentLeadingCharactersUseOneLeadingScan(boolean utf8) {
    String input = ":=! ".repeat(10_000);
    for (String regex :
        List.of("(?:requisition|req):(\\S*)", "(?:BUG|FIXED)=(\\d*)", "(?:job|journal)!(\\w*)")) {
      Pattern pattern = Pattern.compile(regex);
      var plan = (MultiAnchorDescriptor.StartPlan.FixedOffset) pattern.startPlan();
      InputScanner scanner =
          utf8 ? new Utf8InputScanner(input.getBytes(UTF_8)) : new StringInputScanner(input);
      long leadingWork =
          WorkCounter.countForTesting(
              () -> assertThat(scanner.indexOfCharClass(plan.leadingClass(), 0)).isEqualTo(-1));
      long acceleratedWork =
          WorkCounter.countForTesting(
              () -> {
                int candidate =
                    utf8
                        ? Utf8StartAccelerator.findNextCandidate(
                            pattern.utf8StartAccelerator(), (Utf8InputScanner) scanner, 0)
                        : StringStartAccelerator.findNextCandidate(
                            pattern.stringStartAccelerator(), input, 0, false);
                assertThat(candidate).isEqualTo(-1);
              });
      assertThat(acceleratedWork).as(regex).isLessThanOrEqualTo(leadingWork + 128);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void commonPrefixRejectsNoiseContainingItsInitialCharacterInOneScan(boolean utf8) {
    Pattern pattern = Pattern.compile("(?:requisition|req):(\\S*)");
    String input = "note 42: see ticket owner: nobody state: open ".repeat(1000);
    InputScanner scanner =
        utf8 ? new Utf8InputScanner(input.getBytes(UTF_8)) : new StringInputScanner(input);
    long prefixWork =
        WorkCounter.countForTesting(
            () -> assertThat(scanner.indexOfAscii('q', 0, scanner.length())).isEqualTo(-1));
    long work =
        WorkCounter.countForTesting(
            () -> {
              int candidate =
                  utf8
                      ? Utf8StartAccelerator.findNextCandidate(
                          pattern.utf8StartAccelerator(), (Utf8InputScanner) scanner, 0)
                      : StringStartAccelerator.findNextCandidate(
                          pattern.stringStartAccelerator(), input, 0, false);
              assertThat(candidate).isEqualTo(-1);
            });
    assertThat(work).isLessThanOrEqualTo(prefixWork + 256);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void denseLeadingCharactersDefeatTheAlternateScanAfterOneProbe(boolean utf8) {
    Pattern pattern = Pattern.compile("(?:alpha|beta)=(\\w*)");
    String input = "a________=_______".repeat(2_000);
    long work =
        WorkCounter.countForTesting(
            () -> {
              int candidate =
                  utf8
                      ? Utf8StartAccelerator.findNextCandidate(
                          pattern.utf8StartAccelerator(),
                          new Utf8InputScanner(input.getBytes(UTF_8)),
                          0)
                      : StringStartAccelerator.findNextCandidate(
                          pattern.stringStartAccelerator(), input, 0, false);
              assertThat(candidate).isEqualTo(-1);
            });
    // One literal pass, two offset checks per hit, and a bounded leading probe.
    // UTF-8 also charges the decoding read at each checked start.
    long offsetWork = (utf8 ? 4L : 2L) * 2_000;
    assertThat(work).isLessThanOrEqualTo(input.length() + offsetWork + 128);
  }
}
