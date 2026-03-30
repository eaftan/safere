// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere.benchmark;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * JMH benchmarks measuring how per-match allocation rate scales with input size. Run with {@code
 * -prof gc} to get {@code gc.alloc.rate.norm} (bytes/op).
 *
 * <p>This answers: does allocation per operation stay constant as input grows (good — streaming
 * behavior), or does it increase (indicating buffers that scale with input)?
 *
 * <p>Run with:
 *
 * <pre>{@code
 * ./run-java-memory-benchmarks.sh MemoryScalingBenchmark
 * }</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class MemoryScalingBenchmark {

  @Param({"1024", "10240", "102400", "1048576"})
  int textSize;

  // Patterns from benchmark-data.json searchScaling section.
  private dev.eaftan.safere.Pattern safeEasy;
  private java.util.regex.Pattern jdkEasy;
  private com.google.re2j.Pattern re2jEasy;

  private dev.eaftan.safere.Pattern safeMedium;
  private java.util.regex.Pattern jdkMedium;
  private com.google.re2j.Pattern re2jMedium;

  private String randomText;

  @Setup
  public void setup() {
    BenchmarkData data = BenchmarkData.get();

    String easyPattern = data.getString("searchScaling.patterns.easy");
    String mediumPattern = data.getString("searchScaling.patterns.medium");
    String alphabet = data.getString("searchScaling.randomText.alphabet").replace("\\n", "\n");
    int seed = data.getInt("searchScaling.randomText.seed");

    safeEasy = dev.eaftan.safere.Pattern.compile(easyPattern);
    jdkEasy = java.util.regex.Pattern.compile(easyPattern);
    re2jEasy = com.google.re2j.Pattern.compile(easyPattern);

    safeMedium = dev.eaftan.safere.Pattern.compile(mediumPattern);
    jdkMedium = java.util.regex.Pattern.compile(mediumPattern);
    re2jMedium = com.google.re2j.Pattern.compile(mediumPattern);

    randomText = makeRandomText(textSize, alphabet, seed);
  }

  // ===== Easy pattern (allocation scaling with text size) =====

  @Benchmark
  public boolean searchEasy_safere() {
    return safeEasy.matcher(randomText).find();
  }

  @Benchmark
  public boolean searchEasy_jdk() {
    return jdkEasy.matcher(randomText).find();
  }

  @Benchmark
  public boolean searchEasy_re2j() {
    return re2jEasy.matcher(randomText).find();
  }

  // ===== Medium pattern (allocation scaling with text size) =====

  @Benchmark
  public boolean searchMedium_safere() {
    return safeMedium.matcher(randomText).find();
  }

  @Benchmark
  public boolean searchMedium_jdk() {
    return jdkMedium.matcher(randomText).find();
  }

  @Benchmark
  public boolean searchMedium_re2j() {
    return re2jMedium.matcher(randomText).find();
  }

  private static String makeRandomText(int size, String alphabet, int seed) {
    Random rng = new Random(seed);
    char[] buf = new char[size];
    for (int i = 0; i < size; i++) {
      buf[i] = alphabet.charAt(rng.nextInt(alphabet.length()));
    }
    return new String(buf);
  }
}
