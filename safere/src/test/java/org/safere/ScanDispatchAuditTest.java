// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Asserts that each vector dispatch site sizes its crossover decision by the window it is about to
 * scan rather than by the whole input, per Invariant 4.41.
 *
 * <p>Every test here fails against the code as it stood before the window-sized dispatch fix: the
 * defect leaves the answer correct and changes only which kernel runs, so nothing but a dispatch
 * audit can see it.
 */
@DisabledForCrosscheck("implementation test observes package-private dispatch internals")
class ScanDispatchAuditTest {

  /** A haystack long enough that gating on its length would admit every kernel. */
  private static final byte[] LONG_INPUT = "x".repeat(100 * 1024).getBytes(UTF_8);

  private static final String[] LITERALS = {"INFO", "WARN", "ERROR"};

  /**
   * Returns an installed provider, or {@code null} when the build has none.
   *
   * <p>Without a provider every threshold question answers the same way, so there is no dispatch
   * decision left to get wrong; these tests only have content under {@code -Pvector-tests}.
   */
  private static VectorScanProvider installedProvider() {
    return VectorScanProviders.providerFor(ScanKind.PAIR, 64);
  }

  private static ScanEvent consulted(ScanKind kind, int window) {
    return new ScanEvent(kind, ScanDirection.UNSPECIFIED, window, ScanPath.CONSULTED);
  }

  @Test
  void teddyDispatchSizedByWindowNotInput() {
    VectorScanProvider provider = installedProvider();
    if (provider == null) {
      return;
    }
    TeddyModel model = TeddyModel.compileForSelectedProvider(LITERALS);
    assertThat(model).isNotNull();
    Utf8StartAccelerator.Teddy teddy = new Utf8StartAccelerator.Teddy(model);
    Utf8InputScanner scanner = new Utf8InputScanner(LONG_INPUT);
    int window = provider.minimumWindowLength(ScanKind.TEDDY) - 1;
    int fromIndex = LONG_INPUT.length - window;

    List<ScanEvent> events =
        ScanAudit.captureForTesting(
            () -> assertThat(teddy.findCandidate(scanner, fromIndex)).isEqualTo(fromIndex));

    assertThat(events)
        .containsExactly(
            consulted(ScanKind.TEDDY, window),
            new ScanEvent(ScanKind.TEDDY, ScanDirection.FORWARD, window, ScanPath.DECLINED));
  }

  @Test
  void multiLiteralDispatchSizedByWindow() {
    VectorScanProvider provider = installedProvider();
    if (provider == null) {
      return;
    }
    MultiLiteralInfo info = MultiLiteralInfo.create(LITERALS);
    assertThat(info).isNotNull();
    Utf8StartAccelerator.MultiLiteral multiLiteral =
        new Utf8StartAccelerator.MultiLiteral(
            info, TeddyModel.compileForSelectedProvider(LITERALS));
    Utf8InputScanner scanner = new Utf8InputScanner(LONG_INPUT);
    int window = provider.minimumWindowLength(ScanKind.MULTI_LITERAL) - 1;
    int fromIndex = LONG_INPUT.length - window;

    List<ScanEvent> events =
        ScanAudit.captureForTesting(
            () -> assertThat(multiLiteral.findCandidate(scanner, fromIndex)).isNegative());

    assertThat(events)
        .containsExactly(
            consulted(ScanKind.MULTI_LITERAL, window),
            new ScanEvent(ScanKind.MULTI_LITERAL, ScanDirection.FORWARD, window, ScanPath.SCALAR));
  }

  @Test
  void narrowClassScanStillReachesSwar() {
    VectorScanProvider provider = installedProvider();
    if (provider == null) {
      return;
    }
    // A window too narrow for the Vector class kernel but wide enough for the SWAR one. Declining
    // the Vector tier here must not cost the SWAR tier as well.
    int window = provider.minimumWindowLength(ScanKind.CLASS) - 1;
    assertThat(window).isGreaterThanOrEqualTo(64);
    int[] ranges = {'0', '9', 'A', 'F', 'a', 'f'};
    long low = 0;
    long high = 0;
    for (int i = 0; i < ranges.length; i += 2) {
      for (int c = ranges[i]; c <= ranges[i + 1]; c++) {
        if (c < Long.SIZE) {
          low |= 1L << c;
        } else {
          high |= 1L << (c - Long.SIZE);
        }
      }
    }
    long bitmap0 = low;
    long bitmap1 = high;
    Utf8InputScanner scanner = new Utf8InputScanner(LONG_INPUT);

    List<ScanEvent> events =
        ScanAudit.captureForTesting(
            () ->
                assertThat(scanner.indexOfCodePointClass(ranges, bitmap0, bitmap1, 0, window))
                    .isEqualTo(-1));

    assertThat(events)
        .containsExactly(
            consulted(ScanKind.CLASS, window),
            new ScanEvent(ScanKind.CLASS, ScanDirection.FORWARD, window, ScanPath.SWAR));
  }

  @Test
  void singleByteClassDoesNotRecordRoutingProbeAsDispatch() {
    VectorScanProvider provider = installedProvider();
    if (provider == null) {
      return;
    }
    int window = provider.minimumWindowLength(ScanKind.CLASS);
    Utf8InputScanner scanner = new Utf8InputScanner(LONG_INPUT);

    List<ScanEvent> events =
        captureScan(
            () ->
                scanner.indexOfCodePointClass(
                    new int[] {'z', 'z'}, 0, 1L << ('z' - Long.SIZE), 0, window));

    assertThat(events).isEmpty();
  }

  @Test
  void tripleClassRecordsOnlyItsActualTripleScan() {
    VectorScanProvider provider = installedProvider();
    if (provider == null) {
      return;
    }
    int window = provider.minimumWindowLength(ScanKind.CLASS);
    Utf8InputScanner scanner = new Utf8InputScanner(LONG_INPUT);

    List<ScanEvent> events =
        captureScan(
            () ->
                scanner.indexOfCodePointClass(
                    new int[] {'a', 'a', 'b', 'b', 'c', 'c'}, 0, 0, 0, window));

    assertThat(events)
        .containsExactly(
            consulted(ScanKind.TRIPLE, window),
            new ScanEvent(ScanKind.TRIPLE, ScanDirection.FORWARD, window, ScanPath.VECTOR));
  }

  @Test
  void thresholdBoundaryRecordsExpectedPath() {
    VectorScanProvider provider = installedProvider();
    if (provider == null) {
      return;
    }
    int minimum = provider.minimumWindowLength(ScanKind.BYTE);
    Utf8InputScanner scanner = new Utf8InputScanner(LONG_INPUT);

    assertThat(captureScan(() -> scanner.indexOfAscii('z', 0, minimum - 1)))
        .containsExactly(
            consulted(ScanKind.BYTE, minimum - 1),
            new ScanEvent(ScanKind.BYTE, ScanDirection.FORWARD, minimum - 1, ScanPath.SWAR));
    assertThat(captureScan(() -> scanner.indexOfAscii('z', 0, minimum)))
        .containsExactly(
            consulted(ScanKind.BYTE, minimum),
            new ScanEvent(ScanKind.BYTE, ScanDirection.FORWARD, minimum, ScanPath.VECTOR));
  }

  @Test
  void allDispatchLaddersSatisfyPairingAcrossNarrowAndWideWindows() {
    VectorScanProvider provider = installedProvider();
    if (provider == null) {
      return;
    }
    Utf8InputScanner scanner = new Utf8InputScanner(LONG_INPUT);

    // 1. BYTE
    int byteMin = provider.minimumWindowLength(ScanKind.BYTE);
    assertThat(captureScan(() -> scanner.indexOfAscii('z', 0, byteMin - 1)))
        .containsExactly(
            consulted(ScanKind.BYTE, byteMin - 1),
            new ScanEvent(ScanKind.BYTE, ScanDirection.FORWARD, byteMin - 1, ScanPath.SWAR));
    assertThat(captureScan(() -> scanner.indexOfAscii('z', 0, byteMin)))
        .containsExactly(
            consulted(ScanKind.BYTE, byteMin),
            new ScanEvent(ScanKind.BYTE, ScanDirection.FORWARD, byteMin, ScanPath.VECTOR));

    // 2. PAIR
    int pairMin = provider.minimumWindowLength(ScanKind.PAIR);
    assertThat(captureScan(() -> scanner.indexOfAsciiPair('y', 'z', 0, pairMin - 1)))
        .containsExactly(
            consulted(ScanKind.PAIR, pairMin - 1),
            new ScanEvent(ScanKind.PAIR, ScanDirection.FORWARD, pairMin - 1, ScanPath.SWAR));
    assertThat(captureScan(() -> scanner.indexOfAsciiPair('y', 'z', 0, pairMin)))
        .containsExactly(
            consulted(ScanKind.PAIR, pairMin),
            new ScanEvent(ScanKind.PAIR, ScanDirection.FORWARD, pairMin, ScanPath.VECTOR));

    // 3. TRIPLE
    int tripleMin = provider.minimumWindowLength(ScanKind.TRIPLE);
    assertThat(captureScan(() -> scanner.indexOfAsciiTriple('u', 'v', 'w', 0, tripleMin - 1)))
        .containsExactly(
            consulted(ScanKind.TRIPLE, tripleMin - 1),
            new ScanEvent(ScanKind.TRIPLE, ScanDirection.FORWARD, tripleMin - 1, ScanPath.SWAR));
    assertThat(captureScan(() -> scanner.indexOfAsciiTriple('u', 'v', 'w', 0, tripleMin)))
        .containsExactly(
            consulted(ScanKind.TRIPLE, tripleMin),
            new ScanEvent(ScanKind.TRIPLE, ScanDirection.FORWARD, tripleMin, ScanPath.VECTOR));

    // 4. CLASS
    int classMin = provider.minimumWindowLength(ScanKind.CLASS);
    int[] ranges = {'0', '9'};
    long bitmap0 = 0x03FF000000000000L;
    assertThat(
            captureScan(() -> scanner.indexOfCodePointClass(ranges, bitmap0, 0, 0, classMin - 1)))
        .containsExactly(
            consulted(ScanKind.CLASS, classMin - 1),
            new ScanEvent(ScanKind.CLASS, ScanDirection.FORWARD, classMin - 1, ScanPath.SWAR));
    assertThat(captureScan(() -> scanner.indexOfCodePointClass(ranges, bitmap0, 0, 0, classMin)))
        .containsExactly(
            consulted(ScanKind.CLASS, classMin),
            new ScanEvent(ScanKind.CLASS, ScanDirection.FORWARD, classMin, ScanPath.VECTOR));

    // 5. IGNORE_CASE (single-anchor and pair-anchor ladders)
    int ignoreCaseMin = provider.minimumWindowLength(ScanKind.IGNORE_CASE);
    int[] failure = {0, 0, 0};
    int fromNarrow = LONG_INPUT.length - (ignoreCaseMin - 1);
    assertThat(
            captureScan(
                () ->
                    scanner.indexOfIgnoreCase(
                        "abc", failure, 0, (byte) 'a', (byte) 'A', fromNarrow)))
        .containsExactly(
            consulted(ScanKind.IGNORE_CASE, ignoreCaseMin - 1),
            new ScanEvent(
                ScanKind.IGNORE_CASE, ScanDirection.FORWARD, ignoreCaseMin - 1, ScanPath.SWAR));
    assertThat(
            captureScan(
                () ->
                    scanner.indexOfPairIgnoreCase(
                        "abc",
                        failure,
                        0,
                        (byte) 'a',
                        (byte) 'A',
                        1,
                        (byte) 'b',
                        (byte) 'B',
                        fromNarrow)))
        .containsExactly(
            consulted(ScanKind.IGNORE_CASE, ignoreCaseMin - 1),
            new ScanEvent(
                ScanKind.IGNORE_CASE, ScanDirection.FORWARD, ignoreCaseMin - 1, ScanPath.SWAR));
    int fromWide = LONG_INPUT.length - ignoreCaseMin;
    assertThat(
            captureScan(
                () ->
                    scanner.indexOfIgnoreCase("abc", failure, 0, (byte) 'a', (byte) 'A', fromWide)))
        .containsExactly(
            consulted(ScanKind.IGNORE_CASE, ignoreCaseMin),
            new ScanEvent(
                ScanKind.IGNORE_CASE, ScanDirection.FORWARD, ignoreCaseMin, ScanPath.VECTOR));
    assertThat(
            captureScan(
                () ->
                    scanner.indexOfPairIgnoreCase(
                        "abc",
                        failure,
                        0,
                        (byte) 'a',
                        (byte) 'A',
                        1,
                        (byte) 'b',
                        (byte) 'B',
                        fromWide)))
        .containsExactly(
            consulted(ScanKind.IGNORE_CASE, ignoreCaseMin),
            new ScanEvent(
                ScanKind.IGNORE_CASE, ScanDirection.FORWARD, ignoreCaseMin, ScanPath.VECTOR));

    // 6. TEDDY
    TeddyModel teddyModel = TeddyModel.compileForSelectedProvider(LITERALS);
    assertThat(teddyModel).isNotNull();
    Utf8StartAccelerator.Teddy teddy = new Utf8StartAccelerator.Teddy(teddyModel);
    int teddyMin = provider.minimumWindowLength(ScanKind.TEDDY);
    int teddyFromNarrow = LONG_INPUT.length - (teddyMin - 1);
    List<ScanEvent> teddyNarrowEvents =
        ScanAudit.captureForTesting(
            () ->
                assertThat(teddy.findCandidate(scanner, teddyFromNarrow))
                    .isEqualTo(teddyFromNarrow));
    assertThat(teddyNarrowEvents)
        .containsExactly(
            consulted(ScanKind.TEDDY, teddyMin - 1),
            new ScanEvent(ScanKind.TEDDY, ScanDirection.FORWARD, teddyMin - 1, ScanPath.DECLINED));
    int teddyFromWide = LONG_INPUT.length - teddyMin;
    assertThat(captureScan(() -> teddy.findCandidate(scanner, teddyFromWide)))
        .containsExactly(
            consulted(ScanKind.TEDDY, teddyMin),
            new ScanEvent(ScanKind.TEDDY, ScanDirection.FORWARD, teddyMin, ScanPath.VECTOR));

    // 7. MULTI_LITERAL
    MultiLiteralInfo multiLiteralInfo = MultiLiteralInfo.create(LITERALS);
    assertThat(multiLiteralInfo).isNotNull();
    Utf8StartAccelerator.MultiLiteral multiLiteral =
        new Utf8StartAccelerator.MultiLiteral(multiLiteralInfo, teddyModel);
    int multiLiteralMin = provider.minimumWindowLength(ScanKind.MULTI_LITERAL);
    int mlFromNarrow = LONG_INPUT.length - (multiLiteralMin - 1);
    assertThat(captureScan(() -> multiLiteral.findCandidate(scanner, mlFromNarrow)))
        .containsExactly(
            consulted(ScanKind.MULTI_LITERAL, multiLiteralMin - 1),
            new ScanEvent(
                ScanKind.MULTI_LITERAL,
                ScanDirection.FORWARD,
                multiLiteralMin - 1,
                ScanPath.SCALAR));
    int mlFromWide = LONG_INPUT.length - multiLiteralMin;
    assertThat(captureScan(() -> multiLiteral.findCandidate(scanner, mlFromWide)))
        .containsExactly(
            consulted(ScanKind.MULTI_LITERAL, multiLiteralMin),
            new ScanEvent(
                ScanKind.MULTI_LITERAL, ScanDirection.FORWARD, multiLiteralMin, ScanPath.VECTOR));
  }

  @Test
  void captureRejectsUnpairedConsultation() {
    assertThatThrownBy(
            () ->
                ScanAudit.captureForTesting(() -> ScanAudit.recordConsultation(ScanKind.BYTE, 64)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("odd number of events");
  }

  @Test
  void captureRejectsPathWithoutConsultation() {
    assertThatThrownBy(
            () ->
                ScanAudit.captureForTesting(
                    () -> {
                      ScanAudit.record(ScanKind.BYTE, ScanDirection.FORWARD, 64, ScanPath.SWAR);
                      ScanAudit.record(ScanKind.BYTE, ScanDirection.FORWARD, 64, ScanPath.SWAR);
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("expected ScanPath.CONSULTED");
  }

  @Test
  void captureRejectsConsecutiveConsultations() {
    assertThatThrownBy(
            () ->
                ScanAudit.captureForTesting(
                    () -> {
                      ScanAudit.recordConsultation(ScanKind.BYTE, 64);
                      ScanAudit.recordConsultation(ScanKind.BYTE, 64);
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("consecutive CONSULTED event");
  }

  @Test
  void captureRejectsKindMismatchBetweenConsultationAndPath() {
    assertThatThrownBy(
            () ->
                ScanAudit.captureForTesting(
                    () -> {
                      ScanAudit.recordConsultation(ScanKind.BYTE, 64);
                      ScanAudit.record(ScanKind.PAIR, ScanDirection.FORWARD, 64, ScanPath.SWAR);
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not match path");
  }

  @Test
  void captureRejectsWindowLengthMismatchBetweenConsultationAndPath() {
    assertThatThrownBy(
            () ->
                ScanAudit.captureForTesting(
                    () -> {
                      ScanAudit.recordConsultation(ScanKind.BYTE, 64);
                      ScanAudit.record(ScanKind.BYTE, ScanDirection.FORWARD, 128, ScanPath.SWAR);
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not match path");
  }

  /**
   * The work-counter branches replace each kernel with a scalar counting loop and return before
   * reaching any dispatch ladder, so a capture taken there would report that nothing but scalar
   * code ran. Refusing beats handing back a plausible-looking log.
   */
  @Test
  @Tag("work-counter")
  void captureRefusesToRunUnderWorkCounters() {
    assertThatThrownBy(() -> ScanAudit.captureForTesting(() -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("work-counters");
  }

  /** Captures the dispatch events of one scan, asserting it finds nothing. */
  private static List<ScanEvent> captureScan(IntSupplier scan) {
    return ScanAudit.captureForTesting(() -> assertThat(scan.getAsInt()).isEqualTo(-1));
  }
}
