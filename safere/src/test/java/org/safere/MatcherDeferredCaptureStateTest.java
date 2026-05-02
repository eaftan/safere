// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests internal deferred-capture lifecycle invariants for {@link Matcher}. */
@DisabledForCrosscheck("uses package-private reflection to assert SafeRE matcher internals")
class MatcherDeferredCaptureStateTest {
  private static final Pattern DEFERRED_CAPTURE_PATTERN =
      Pattern.compile("(?m)(?:^|,)(?:\"([^\"]*)\"|([^,\r\n]+))");
  private static final String CSV = "id,name\n42,\"Ada Lovelace\"";

  @Test
  @DisplayName("reset clears stale deferred captures")
  void resetClearsStaleDeferredCaptures() throws ReflectiveOperationException {
    Matcher matcher = matcherWithDeferredCaptures();

    matcher.reset();

    assertDeferredCaptureStateClear(matcher);
    assertThatThrownBy(matcher::group).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("reset(input) clears stale deferred captures")
  void resetInputClearsStaleDeferredCaptures() throws ReflectiveOperationException {
    Matcher matcher = matcherWithDeferredCaptures();

    matcher.reset("name");

    assertDeferredCaptureStateClear(matcher);
    assertThatThrownBy(matcher::group).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("region clears stale deferred captures")
  void regionClearsStaleDeferredCaptures() throws ReflectiveOperationException {
    Matcher matcher = matcherWithDeferredCaptures();

    matcher.region(0, 2);

    assertDeferredCaptureStateClear(matcher);
    assertThatThrownBy(matcher::group).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("failed find(start) clears stale deferred captures")
  void failedFindStartClearsStaleDeferredCaptures() throws ReflectiveOperationException {
    Matcher matcher = matcherWithDeferredCaptures();

    assertThat(matcher.find(CSV.length())).isFalse();

    assertDeferredCaptureStateClear(matcher);
    assertThatThrownBy(matcher::group).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("usePattern clears stale deferred captures")
  void usePatternClearsStaleDeferredCaptures() throws ReflectiveOperationException {
    Matcher matcher = matcherWithDeferredCaptures();

    matcher.usePattern(Pattern.compile("name"));

    assertDeferredCaptureStateClear(matcher);
    assertThatThrownBy(matcher::group).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("anchoring bounds change resolves stale deferred captures before clearing markers")
  void anchoringBoundsChangeResolvesStaleDeferredCapturesBeforeClearingMarkers()
      throws ReflectiveOperationException {
    Matcher matcher = matcherWithDeferredCaptures();

    matcher.useAnchoringBounds(false);

    assertDeferredCaptureStateClear(matcher);
    assertThat(matcher.group(1)).isNull();
    assertThat(matcher.group(2)).isEqualTo("id");
  }

  @Test
  @DisplayName("transparent bounds change resolves stale deferred captures before clearing markers")
  void transparentBoundsChangeResolvesStaleDeferredCapturesBeforeClearingMarkers()
      throws ReflectiveOperationException {
    Matcher matcher = matcherWithDeferredCaptures();

    matcher.useTransparentBounds(true);

    assertDeferredCaptureStateClear(matcher);
    assertThat(matcher.group(1)).isNull();
    assertThat(matcher.group(2)).isEqualTo("id");
  }

  private static Matcher matcherWithDeferredCaptures() throws ReflectiveOperationException {
    Matcher matcher = DEFERRED_CAPTURE_PATTERN.matcher(CSV);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group()).isEqualTo("id");
    assertThat(booleanField(matcher, "capturesResolved")).isFalse();
    return matcher;
  }

  private static void assertDeferredCaptureStateClear(Matcher matcher)
      throws ReflectiveOperationException {
    assertThat(booleanField(matcher, "capturesResolved")).isTrue();
    assertThat(booleanField(matcher, "groupZeroResolved")).isTrue();
    assertThat(intField(matcher, "deferredMatchStart")).isZero();
    assertThat(intField(matcher, "deferredMatchEnd")).isZero();
    assertThat(booleanField(matcher, "deferredEndMatch")).isFalse();
  }

  private static boolean booleanField(Matcher matcher, String name)
      throws ReflectiveOperationException {
    return (boolean) field(name).get(matcher);
  }

  private static int intField(Matcher matcher, String name) throws ReflectiveOperationException {
    return (int) field(name).get(matcher);
  }

  private static Field field(String name) throws ReflectiveOperationException {
    Field field = Matcher.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }
}
