// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

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
