// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class AnchorOptTest {
  @Test
  void httpPatternIsOnePassAndAnchored() {
    Pattern p = Pattern.compile("^(?:GET|POST) +([^ ]+) HTTP");
    // Access package-private methods
    assertThat(p.prog().anchorStart()).isTrue();
    assertThat(p.onePass()).isNotNull();
  }

  @Test
  void httpPatternFindWorks() {
    Pattern p = Pattern.compile("^(?:GET|POST) +([^ ]+) HTTP");
    Matcher m = p.matcher("GET /foo HTTP/1.1");
    assertThat(m.find()).isTrue();
    assertThat(m.group(1)).isEqualTo("/foo");
  }

  @Test
  void anchoredPatternSecondFindReturnsFalse() {
    Pattern p = Pattern.compile("^(?:GET|POST) +([^ ]+) HTTP");
    Matcher m = p.matcher("GET /foo HTTP/1.1");
    assertThat(m.find()).isTrue();
    assertThat(m.find()).isFalse();
  }

  @Test 
  void anchoredPatternNoMatchAtNonZero() {
    Pattern p = Pattern.compile("^hello");
    Matcher m = p.matcher("say hello world");
    assertThat(m.find()).isFalse();
  }

  @Test
  void anchoredPatternMatchAtZero() {
    Pattern p = Pattern.compile("^hello");
    Matcher m = p.matcher("hello world");
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("hello");
  }
}
