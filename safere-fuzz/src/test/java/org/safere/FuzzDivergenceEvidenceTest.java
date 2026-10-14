// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class FuzzDivergenceEvidenceTest {
  @Test
  void defaultEvidenceCollectionLeavesDiagnosticsUnclaimed() {
    var previous = Pattern.diagnostics();
    String property = System.getProperty("safere.fuzz.divergenceEvidence");
    System.clearProperty("safere.fuzz.divergenceEvidence");
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
    try {
      FuzzDivergenceEvidence.begin();
      assertThat(Pattern.diagnostics()).isSameAs(SafeReMatchDiagnostics.NONE);
    } finally {
      Pattern.setDiagnostics(previous);
      if (property == null) System.clearProperty("safere.fuzz.divergenceEvidence");
      else System.setProperty("safere.fuzz.divergenceEvidence", property);
    }
  }

  @Test
  void signalsComeFromExecutingTheSemanticPathAndClearBetweenCases() {
    String property = System.getProperty("safere.fuzz.divergenceEvidence");
    System.setProperty("safere.fuzz.divergenceEvidence", "true");
    var previous = Pattern.diagnostics();
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
    try {
      FuzzDivergenceEvidence.begin();
      assertThatThrownBy(() -> Pattern.compile("[a&&&b]"))
          .isInstanceOf(java.util.regex.PatternSyntaxException.class);
      assertThat(FuzzDivergenceEvidence.observed()).contains("CHARACTER_CLASS_SYNTAX");
      FuzzDivergenceEvidence.begin();
      assertThat(Pattern.compile("(?x)#\\Q\n.").matcher("a").matches()).isTrue();
      assertThat(FuzzDivergenceEvidence.observed())
          .contains("COMMENT_QUOTING")
          .doesNotContain("CHARACTER_CLASS_SYNTAX");
      FuzzDivergenceEvidence.begin();
      var matcher = Pattern.compile("a*").matcher("a");
      matcher.replaceAll("-");
      assertThat(matcher.hasMatch()).isFalse();
      assertThat(FuzzDivergenceEvidence.observed()).contains("EXHAUSTED_EMPTY_MATCH");
      FuzzDivergenceEvidence.begin();
      matcher.reset();
      while (matcher.find()) {
        /* Exercise terminal-empty find directly as well. */
      }
      assertThat(FuzzDivergenceEvidence.observed()).contains("EXHAUSTED_EMPTY_MATCH");
      FuzzDivergenceEvidence.begin();
      matcher.reset();
      matcher.find();
      matcher.usePattern(Pattern.compile("b"));
      assertThat(matcher.group()).isEqualTo("a");
      assertThat(FuzzDivergenceEvidence.observed()).contains("USE_PATTERN_GROUP_ZERO");
    } finally {
      Pattern.setDiagnostics(previous);
      if (property == null) System.clearProperty("safere.fuzz.divergenceEvidence");
      else System.setProperty("safere.fuzz.divergenceEvidence", property);
    }
  }
}
