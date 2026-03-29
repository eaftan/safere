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
  // Literal match
  // ---------------------------------------------------------------------------

  private dev.eaftan.safere.Pattern safeHello;
  private java.util.regex.Pattern jdkHello;
  private com.google.re2j.Pattern re2jHello;
  private dev.eaftan.safere.re2ffm.RE2FfmPattern re2ffmHello;
  private String helloText;

  // ---------------------------------------------------------------------------
  // Character class
  // ---------------------------------------------------------------------------

  private dev.eaftan.safere.Pattern safeAlpha;
  private java.util.regex.Pattern jdkAlpha;
  private com.google.re2j.Pattern re2jAlpha;
  private dev.eaftan.safere.re2ffm.RE2FfmPattern re2ffmAlpha;
  private String alphaText;

  // ---------------------------------------------------------------------------
  // Alternation
  // ---------------------------------------------------------------------------

  private dev.eaftan.safere.Pattern safeAlt;
  private java.util.regex.Pattern jdkAlt;
  private com.google.re2j.Pattern re2jAlt;
  private dev.eaftan.safere.re2ffm.RE2FfmPattern re2ffmAlt;
  private String altText;

  // ---------------------------------------------------------------------------
  // Capture groups
  // ---------------------------------------------------------------------------

  private dev.eaftan.safere.Pattern safeDate;
  private java.util.regex.Pattern jdkDate;
  private com.google.re2j.Pattern re2jDate;
  private dev.eaftan.safere.re2ffm.RE2FfmPattern re2ffmDate;
  private String dateText;

  // ---------------------------------------------------------------------------
  // Find in long text
  // ---------------------------------------------------------------------------

  private dev.eaftan.safere.Pattern safeFindIng;
  private java.util.regex.Pattern jdkFindIng;
  private com.google.re2j.Pattern re2jFindIng;
  private dev.eaftan.safere.re2ffm.RE2FfmPattern re2ffmFindIng;
  private String proseText;

  // ---------------------------------------------------------------------------
  // Email-like pattern
  // ---------------------------------------------------------------------------

  private dev.eaftan.safere.Pattern safeEmail;
  private java.util.regex.Pattern jdkEmail;
  private com.google.re2j.Pattern re2jEmail;
  private dev.eaftan.safere.re2ffm.RE2FfmPattern re2ffmEmail;
  private String emailText;

  @Setup
  public void setup() {
    BenchmarkData data = BenchmarkData.get();

    // Literal
    String helloPattern = data.getString("regex.literalMatch.pattern");
    helloText = data.getString("regex.literalMatch.text");
    safeHello = dev.eaftan.safere.Pattern.compile(helloPattern);
    jdkHello = java.util.regex.Pattern.compile(helloPattern);
    re2jHello = com.google.re2j.Pattern.compile(helloPattern);
    re2ffmHello = dev.eaftan.safere.re2ffm.RE2FfmPattern.compile(helloPattern);

    // Character class
    String alphaPattern = data.getString("regex.charClassMatch.pattern");
    alphaText = data.getString("regex.charClassMatch.text");
    safeAlpha = dev.eaftan.safere.Pattern.compile(alphaPattern);
    jdkAlpha = java.util.regex.Pattern.compile(alphaPattern);
    re2jAlpha = com.google.re2j.Pattern.compile(alphaPattern);
    re2ffmAlpha = dev.eaftan.safere.re2ffm.RE2FfmPattern.compile(alphaPattern);

    // Alternation
    String altPattern = data.getString("regex.alternationFind.pattern");
    altText = data.getString("regex.alternationFind.text");
    safeAlt = dev.eaftan.safere.Pattern.compile(altPattern);
    jdkAlt = java.util.regex.Pattern.compile(altPattern);
    re2jAlt = com.google.re2j.Pattern.compile(altPattern);
    re2ffmAlt = dev.eaftan.safere.re2ffm.RE2FfmPattern.compile(altPattern);

    // Capture
    String datePattern = data.getString("regex.captureGroups.pattern");
    dateText = data.getString("regex.captureGroups.text");
    safeDate = dev.eaftan.safere.Pattern.compile(datePattern);
    jdkDate = java.util.regex.Pattern.compile(datePattern);
    re2jDate = com.google.re2j.Pattern.compile(datePattern);
    re2ffmDate = dev.eaftan.safere.re2ffm.RE2FfmPattern.compile(datePattern);

    // Find -ing words
    String ingPattern = data.getString("regex.findInText.pattern");
    proseText = data.getString("regex.findInText.text");
    safeFindIng = dev.eaftan.safere.Pattern.compile(ingPattern);
    jdkFindIng = java.util.regex.Pattern.compile(ingPattern);
    re2jFindIng = com.google.re2j.Pattern.compile(ingPattern);
    re2ffmFindIng = dev.eaftan.safere.re2ffm.RE2FfmPattern.compile(ingPattern);

    // Email
    String emailPattern = data.getString("regex.emailFind.pattern");
    emailText = data.getString("regex.emailFind.text");
    safeEmail = dev.eaftan.safere.Pattern.compile(emailPattern);
    jdkEmail = java.util.regex.Pattern.compile(emailPattern);
    re2jEmail = com.google.re2j.Pattern.compile(emailPattern);
    re2ffmEmail = dev.eaftan.safere.re2ffm.RE2FfmPattern.compile(emailPattern);
  }

  // ===== Literal match =====

  @Benchmark
  public boolean literalMatch_safere() {
    return safeHello.matcher(helloText).matches();
  }

  @Benchmark
  public boolean literalMatch_jdk() {
    return jdkHello.matcher(helloText).matches();
  }

  @Benchmark
  public boolean literalMatch_re2j() {
    return re2jHello.matcher(helloText).matches();
  }

  @Benchmark
  public boolean literalMatch_re2ffm() {
    return re2ffmHello.matcher(helloText).matches();
  }

  // ===== Character class match =====

  @Benchmark
  public boolean charClassMatch_safere() {
    return safeAlpha.matcher(alphaText).matches();
  }

  @Benchmark
  public boolean charClassMatch_jdk() {
    return jdkAlpha.matcher(alphaText).matches();
  }

  @Benchmark
  public boolean charClassMatch_re2j() {
    return re2jAlpha.matcher(alphaText).matches();
  }

  @Benchmark
  public boolean charClassMatch_re2ffm() {
    return re2ffmAlpha.matcher(alphaText).matches();
  }

  // ===== Alternation find =====

  @Benchmark
  public int alternationFind_safere() {
    dev.eaftan.safere.Matcher m = safeAlt.matcher(altText);
    int count = 0;
    while (m.find()) {
      count++;
    }
    return count;
  }

  @Benchmark
  public int alternationFind_jdk() {
    java.util.regex.Matcher m = jdkAlt.matcher(altText);
    int count = 0;
    while (m.find()) {
      count++;
    }
    return count;
  }

  @Benchmark
  public int alternationFind_re2j() {
    com.google.re2j.Matcher m = re2jAlt.matcher(altText);
    int count = 0;
    while (m.find()) {
      count++;
    }
    return count;
  }

  @Benchmark
  public int alternationFind_re2ffm() {
    dev.eaftan.safere.re2ffm.RE2FfmMatcher m = re2ffmAlt.matcher(altText);
    int count = 0;
    while (m.find()) {
      count++;
    }
    return count;
  }

  // ===== Capture groups =====

  @Benchmark
  public String captureGroups_safere() {
    dev.eaftan.safere.Matcher m = safeDate.matcher(dateText);
    m.matches();
    return m.group(1) + m.group(2) + m.group(3);
  }

  @Benchmark
  public String captureGroups_jdk() {
    java.util.regex.Matcher m = jdkDate.matcher(dateText);
    m.matches();
    return m.group(1) + m.group(2) + m.group(3);
  }

  @Benchmark
  public String captureGroups_re2j() {
    com.google.re2j.Matcher m = re2jDate.matcher(dateText);
    m.matches();
    return m.group(1) + m.group(2) + m.group(3);
  }

  @Benchmark
  public String captureGroups_re2ffm() {
    dev.eaftan.safere.re2ffm.RE2FfmMatcher m = re2ffmDate.matcher(dateText);
    m.matches();
    return m.group(1) + m.group(2) + m.group(3);
  }

  // ===== Find in long text =====

  @Benchmark
  public int findInText_safere() {
    dev.eaftan.safere.Matcher m = safeFindIng.matcher(proseText);
    int count = 0;
    while (m.find()) {
      count++;
    }
    return count;
  }

  @Benchmark
  public int findInText_jdk() {
    java.util.regex.Matcher m = jdkFindIng.matcher(proseText);
    int count = 0;
    while (m.find()) {
      count++;
    }
    return count;
  }

  @Benchmark
  public int findInText_re2j() {
    com.google.re2j.Matcher m = re2jFindIng.matcher(proseText);
    int count = 0;
    while (m.find()) {
      count++;
    }
    return count;
  }

  @Benchmark
  public int findInText_re2ffm() {
    dev.eaftan.safere.re2ffm.RE2FfmMatcher m = re2ffmFindIng.matcher(proseText);
    int count = 0;
    while (m.find()) {
      count++;
    }
    return count;
  }

  // ===== Email pattern find =====

  @Benchmark
  public boolean emailFind_safere() {
    return safeEmail.matcher(emailText).find();
  }

  @Benchmark
  public boolean emailFind_jdk() {
    return jdkEmail.matcher(emailText).find();
  }

  @Benchmark
  public boolean emailFind_re2j() {
    return re2jEmail.matcher(emailText).find();
  }

  @Benchmark
  public boolean emailFind_re2ffm() {
    return re2ffmEmail.matcher(emailText).find();
  }
}
