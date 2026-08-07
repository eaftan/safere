package org.safere.re2ffm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class RE2FfmTest {

  public RE2FfmTest() {}

  @Test
  public void testSimpleSplit() {
    RE2FfmPattern p = RE2FfmPattern.compile(",");
    String[] parts = p.split("a,b,c");
    assertArrayEquals(new String[] {"a", "b", "c"}, parts);
  }

  @Test
  public void testSplitLimit() {
    RE2FfmPattern p = RE2FfmPattern.compile(",");
    String[] parts = p.split("a,b,c", 2);
    assertArrayEquals(new String[] {"a", "b,c"}, parts);
  }

  @Test
  public void testNonAsciiMapping() {
    // "hello, \u4e16\u754c" ("hello, 世界")
    // "世界" starts at char offset 7.
    // "世" in UTF-8 is 3 bytes: \xE4\xB8\x96.
    // "界" in UTF-8 is 3 bytes: \xE5\x85\x8C.
    RE2FfmPattern p = RE2FfmPattern.compile("世界");
    RE2FfmMatcher m = p.matcher("hello, 世界!");

    assertTrue(m.find());
    assertEquals(7, m.start());
    assertEquals(9, m.end());
    assertEquals("世界", m.group());
  }

  @Test
  public void testSurrogatePairs() {
    // \uD83D\uDE00 is the emoji grinning face 😀 (1 character point, 2 Java chars, 4 UTF-8 bytes)
    String text = "hello \uD83D\uDE00 world";
    RE2FfmPattern p = RE2FfmPattern.compile("\uD83D\uDE00");
    RE2FfmMatcher m = p.matcher(text);

    assertTrue(m.find());
    assertEquals(6, m.start());
    assertEquals(8, m.end());
    assertEquals("\uD83D\uDE00", m.group());

    // Split around surrogate
    String[] parts = p.split(text);
    assertArrayEquals(new String[] {"hello ", " world"}, parts);
  }

  @Test
  public void testReplaceAll() {
    RE2FfmPattern p = RE2FfmPattern.compile("(\\w+)");
    assertEquals("X X", p.matcher("hello world").replaceAll("X"));
    assertEquals("[hello] [world]", p.matcher("hello world").replaceAll("[$1]"));
  }

  @Test
  public void testReplaceFirst() {
    RE2FfmPattern p = RE2FfmPattern.compile("(\\w+)");
    assertEquals("X world", p.matcher("hello world").replaceFirst("X"));
    assertEquals("[hello] world", p.matcher("hello world").replaceFirst("[$1]"));
  }

  @Test
  public void testReplaceAllMultiDigitGroup() {
    RE2FfmPattern p = RE2FfmPattern.compile("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)");
    assertEquals("xjy", p.matcher("xabcdefghijy").replaceAll("$10"));
  }

  @Test
  public void testReplaceFirstMultiDigitGroup() {
    RE2FfmPattern p = RE2FfmPattern.compile("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)");
    assertEquals("xjy", p.matcher("xabcdefghijy").replaceFirst("$10"));
  }

  @Test
  public void testReplaceAllStateDiscrepancy() {
    RE2FfmPattern p = RE2FfmPattern.compile("a");
    RE2FfmMatcher m = p.matcher("aba");
    assertTrue(m.find());
    assertEquals("a", m.group());

    assertEquals("xbx", m.replaceAll("x"));

    try {
      m.group();
      fail("Expected IllegalStateException");
    } catch (IllegalStateException expected) {
      // Expected
    }

    assertFalse(m.find());
  }

  @Test
  public void testReplaceAllStateDiscrepancyJavaFallback() {
    RE2FfmPattern p = RE2FfmPattern.compile("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)");
    RE2FfmMatcher m = p.matcher("xabcdefghijy");
    assertTrue(m.find());
    assertEquals("abcdefghij", m.group());

    assertEquals("xxy", m.replaceAll("x"));

    try {
      m.group();
      fail("Expected IllegalStateException");
    } catch (IllegalStateException expected) {
      // Expected
    }

    assertFalse(m.find());
  }
}
