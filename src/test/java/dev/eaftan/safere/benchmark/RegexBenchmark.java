// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere.benchmark;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * JMH benchmarks comparing SafeRE against {@code java.util.regex}. Run with:
 *
 * <pre>{@code
 * mvn test-compile exec:java \
 *   -Dexec.mainClass=org.openjdk.jmh.Main \
 *   -Dexec.classpathScope=test \
 *   -Dexec.args="dev.eaftan.safere.benchmark"
 * }</pre>
 *
 * <p>Or to run a specific benchmark:
 *
 * <pre>{@code
 * -Dexec.args="dev.eaftan.safere.benchmark.RegexBenchmark.literalMatch"
 * }</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class RegexBenchmark {

  // ---------------------------------------------------------------------------
  // Literal match: pattern "hello" against "hello"
  // ---------------------------------------------------------------------------

  private dev.eaftan.safere.Pattern safeHello;
  private java.util.regex.Pattern jdkHello;
  private static final String HELLO_TEXT = "hello";

  // ---------------------------------------------------------------------------
  // Character class: [a-zA-Z]+ against alphabetic text
  // ---------------------------------------------------------------------------

  private dev.eaftan.safere.Pattern safeAlpha;
  private java.util.regex.Pattern jdkAlpha;
  private static final String ALPHA_TEXT = "TheQuickBrownFoxJumpsOverTheLazyDog";

  // ---------------------------------------------------------------------------
  // Alternation: foo|bar|baz|qux|quux|corge|grault|garply
  // ---------------------------------------------------------------------------

  private dev.eaftan.safere.Pattern safeAlt;
  private java.util.regex.Pattern jdkAlt;
  private static final String ALT_TEXT = "the garply went to the baz and met a quux";

  // ---------------------------------------------------------------------------
  // Capture groups: (\d{4})-(\d{2})-(\d{2})
  // ---------------------------------------------------------------------------

  private dev.eaftan.safere.Pattern safeDate;
  private java.util.regex.Pattern jdkDate;
  private static final String DATE_TEXT = "2025-12-25";

  // ---------------------------------------------------------------------------
  // Find in long text: \b\w+ing\b in prose
  // ---------------------------------------------------------------------------

  private dev.eaftan.safere.Pattern safeFindIng;
  private java.util.regex.Pattern jdkFindIng;
  private static final String PROSE_TEXT =
      "The morning sun was shining brightly, casting long shadows across the rolling "
          + "hills. Birds were singing their melodious songs while children were playing "
          + "in the sprawling gardens. A gentle breeze was blowing through the swaying "
          + "trees, carrying the scent of blooming flowers. People were walking along "
          + "the winding paths, enjoying the refreshing air and the stunning views of "
          + "the surrounding countryside. Everything was moving in perfect harmony.";

  // ---------------------------------------------------------------------------
  // Email-like pattern
  // ---------------------------------------------------------------------------

  private dev.eaftan.safere.Pattern safeEmail;
  private java.util.regex.Pattern jdkEmail;
  private static final String EMAIL_TEXT = "contact user.name+tag@example.co.uk for info";

  @Setup
  public void setup() {
    // Literal
    safeHello = dev.eaftan.safere.Pattern.compile("hello");
    jdkHello = java.util.regex.Pattern.compile("hello");

    // Character class
    safeAlpha = dev.eaftan.safere.Pattern.compile("[a-zA-Z]+");
    jdkAlpha = java.util.regex.Pattern.compile("[a-zA-Z]+");

    // Alternation
    String altPattern = "foo|bar|baz|qux|quux|corge|grault|garply";
    safeAlt = dev.eaftan.safere.Pattern.compile(altPattern);
    jdkAlt = java.util.regex.Pattern.compile(altPattern);

    // Capture
    String datePattern = "(\\d{4})-(\\d{2})-(\\d{2})";
    safeDate = dev.eaftan.safere.Pattern.compile(datePattern);
    jdkDate = java.util.regex.Pattern.compile(datePattern);

    // Find -ing words
    String ingPattern = "\\b\\w+ing\\b";
    safeFindIng = dev.eaftan.safere.Pattern.compile(ingPattern);
    jdkFindIng = java.util.regex.Pattern.compile(ingPattern);

    // Email
    String emailPattern = "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}";
    safeEmail = dev.eaftan.safere.Pattern.compile(emailPattern);
    jdkEmail = java.util.regex.Pattern.compile(emailPattern);
  }

  // ===== Literal match =====

  @Benchmark
  public boolean literalMatch_safere() {
    return safeHello.matcher(HELLO_TEXT).matches();
  }

  @Benchmark
  public boolean literalMatch_jdk() {
    return jdkHello.matcher(HELLO_TEXT).matches();
  }

  // ===== Character class match =====

  @Benchmark
  public boolean charClassMatch_safere() {
    return safeAlpha.matcher(ALPHA_TEXT).matches();
  }

  @Benchmark
  public boolean charClassMatch_jdk() {
    return jdkAlpha.matcher(ALPHA_TEXT).matches();
  }

  // ===== Alternation find =====

  @Benchmark
  public int alternationFind_safere() {
    dev.eaftan.safere.Matcher m = safeAlt.matcher(ALT_TEXT);
    int count = 0;
    while (m.find()) {
      count++;
    }
    return count;
  }

  @Benchmark
  public int alternationFind_jdk() {
    java.util.regex.Matcher m = jdkAlt.matcher(ALT_TEXT);
    int count = 0;
    while (m.find()) {
      count++;
    }
    return count;
  }

  // ===== Capture groups =====

  @Benchmark
  public String captureGroups_safere() {
    dev.eaftan.safere.Matcher m = safeDate.matcher(DATE_TEXT);
    m.matches();
    return m.group(1) + m.group(2) + m.group(3);
  }

  @Benchmark
  public String captureGroups_jdk() {
    java.util.regex.Matcher m = jdkDate.matcher(DATE_TEXT);
    m.matches();
    return m.group(1) + m.group(2) + m.group(3);
  }

  // ===== Find in long text =====

  @Benchmark
  public int findInText_safere() {
    dev.eaftan.safere.Matcher m = safeFindIng.matcher(PROSE_TEXT);
    int count = 0;
    while (m.find()) {
      count++;
    }
    return count;
  }

  @Benchmark
  public int findInText_jdk() {
    java.util.regex.Matcher m = jdkFindIng.matcher(PROSE_TEXT);
    int count = 0;
    while (m.find()) {
      count++;
    }
    return count;
  }

  // ===== Email pattern find =====

  @Benchmark
  public boolean emailFind_safere() {
    return safeEmail.matcher(EMAIL_TEXT).find();
  }

  @Benchmark
  public boolean emailFind_jdk() {
    return jdkEmail.matcher(EMAIL_TEXT).find();
  }
}
