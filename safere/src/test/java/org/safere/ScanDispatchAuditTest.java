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
        .startsWith(consulted(ScanKind.MULTI_LITERAL, window))
        .contains(
            new ScanEvent(ScanKind.MULTI_LITERAL, ScanDirection.FORWARD, window, ScanPath.SCALAR));
  }

  @Test
  void alternationUtf8DispatchSizedByWindow() {
    VectorScanProvider provider = installedProvider();
    if (provider == null) {
      return;
    }
    int window =
        Math.min(
                provider.minimumWindowLength(ScanKind.TEDDY),
                provider.minimumWindowLength(ScanKind.MULTI_LITERAL))
            - 1;
    MultiAnchorDescriptor.Anchor.Alternation alternation =
        MultiAnchorDescriptor.Anchor.Alternation.create(LITERALS, false);
    Utf8InputScanner scanner = new Utf8InputScanner(LONG_INPUT);
    // findNextWithin derives the scan length by widening toIndex by the longest literal, three
    // bytes per char (Alternation.MAX_UTF8_BYTES_PER_CHAR, which is private to that record). This
    // places the far edge of the window exactly `window` bytes ahead of the start.
    int longestLiteralBytes = "ERROR".length() * 3;
    int fromIndex = 1000;
    int toIndex = fromIndex + window - longestLiteralBytes;

    List<ScanEvent> events =
        ScanAudit.captureForTesting(
            () ->
                assertThat(alternation.findNextWithin(scanner, fromIndex, toIndex)).isEqualTo(-1));

    assertThat(events)
        .containsExactly(
            consulted(ScanKind.TEDDY, window),
            new ScanEvent(ScanKind.TEDDY, ScanDirection.FORWARD, window, ScanPath.DECLINED),
            consulted(ScanKind.MULTI_LITERAL, window),
            new ScanEvent(
                ScanKind.MULTI_LITERAL, ScanDirection.FORWARD, window, ScanPath.DECLINED));
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

    // The reverse ladder computes its window as start - limit + 1, so the same two windows sit at
    // fromIndex minimum - 2 and minimum - 1.
    assertThat(captureScan(() -> scanner.lastIndexOfAscii('z', minimum - 2, 0)))
        .containsExactly(
            consulted(ScanKind.BYTE, minimum - 1),
            new ScanEvent(ScanKind.BYTE, ScanDirection.REVERSE, minimum - 1, ScanPath.SWAR));
    assertThat(captureScan(() -> scanner.lastIndexOfAscii('z', minimum - 1, 0)))
        .containsExactly(
            consulted(ScanKind.BYTE, minimum),
            new ScanEvent(ScanKind.BYTE, ScanDirection.REVERSE, minimum, ScanPath.VECTOR));
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
