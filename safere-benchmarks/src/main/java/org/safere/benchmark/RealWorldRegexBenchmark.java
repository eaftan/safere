package org.safere.benchmark;

import java.util.Random;
import java.util.concurrent.TimeUnit;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Comparative performance benchmark for regular expression engines using real-world regex patterns.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class RealWorldRegexBenchmark {

  /** Regex operation types to benchmark. */
  public enum Operation {
    FIND,
    REPLACE_ALL_EMPTY
  }

  /** Functional interface for regex search operation. */
  @FunctionalInterface
  public interface RegexFind {
    boolean find(String input);
  }

  /** Functional interface for regex replace operation. */
  @FunctionalInterface
  public interface RegexReplaceAll {
    String replaceAll(String input, String replacement);
  }

  /** Container wrapping pattern instances and operations for a specific regex engine. */
  public record RegexEngine(Object pattern, RegexFind finder, RegexReplaceAll replacer)
      implements AutoCloseable {

    @Override
    public void close() throws Exception {
      if (pattern instanceof AutoCloseable closeable) {
        closeable.close();
      }
    }
  }

  /** Unified benchmark operation resolved during setup. */
  @FunctionalInterface
  public interface BenchmarkOperation {
    /** Executes the pre-configured operation. */
    Object execute(String input);
  }

  /** Engine types available for benchmarking. */
  public enum EngineType {
    SafeRE {
      @Override
      public RegexEngine compile(String patternStr) {
        org.safere.Pattern p = org.safere.Pattern.compile(patternStr);
        return new RegexEngine(
            p,
            input -> p.matcher(input).find(),
            (input, replacement) -> p.matcher(input).replaceAll(replacement));
      }
    },
    JDK {
      @Override
      public RegexEngine compile(String patternStr) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(patternStr);
        return new RegexEngine(
            p,
            input -> p.matcher(input).find(),
            (input, replacement) -> p.matcher(input).replaceAll(replacement));
      }
    },
    RE2J {
      @Override
      public RegexEngine compile(String patternStr) {
        com.google.re2j.Pattern p = com.google.re2j.Pattern.compile(patternStr);
        return new RegexEngine(
            p,
            input -> p.matcher(input).find(),
            (input, replacement) -> p.matcher(input).replaceAll(replacement));
      }
    },
    RE2_FFM {
      @Override
      public RegexEngine compile(String patternStr) {
        org.safere.re2ffm.RE2FfmPattern p = org.safere.re2ffm.RE2FfmPattern.compile(patternStr);
        return new RegexEngine(
            p,
            input -> p.matcher(input).find(),
            (input, replacement) -> p.matcher(input).replaceAll(replacement));
      }
    };

    public abstract RegexEngine compile(String patternStr);
  }

  /** Pattern types to benchmark. */
  public enum PatternType {
    MAP_FIELD_PATH(
        "([\\S]+)\\b\\[(.+)\\]",
        Operation.FIND,
        "config.overrides[some_value]",
        "config.overrides"),
    MARKUP_IMAGE_LINK(
        "<<!(image|media|link)(\\([^\\)]*\\))?/?>?>",
        Operation.REPLACE_ALL_EMPTY,
        "<<!image(src=\"http://image.png\")>>",
        "<<!image"),
    VERSION_LIST(
        " ?[\\[［](?:(?:\\d+\\.){2,}\\d+(?:, )?)+[\\]］]",
        Operation.REPLACE_ALL_EMPTY,
        "[1.2.3, 4.5.6]",
        "[1.2.3,"),
    MALFORMED_ENTITY(
        "<[^>]*entity[^>]*>{1,2}",
        Operation.REPLACE_ALL_EMPTY,
        "<entity id=\"123\">",
        "<ent id=\"123\">"),
    MARKUP_ENTITY(
        "<<!entity.*?>>", Operation.FIND, "<<!entity(id=\"123\")>>", "<<entity(id=\"123\")"),
    CUSTOM_PROTOCOL_LINK(
        "\\[(.*?)]\\(customProtocol://((?:<[^>]*>|[^()]*|\\([^()]*\\))*)\\)",
        Operation.FIND,
        "[Click here](customProtocol://<doc_1>)",
        "[Click here](http://google.com)"),
    WILDCARD_SEARCH(
        "(?is).*\\b(you|your)\\b.*",
        Operation.FIND,
        "What can I do for you today?",
        "What is the capital of France?"),
    METADATA_BLOCK(
        "(?s)<meta_start>.*?<meta_end>",
        Operation.REPLACE_ALL_EMPTY,
        "<meta_start>Thinking process: analyzing query...<meta_end>",
        "<meta_start>Thinking process: analyzing query..."),
    BLOCKED_TAGS_1(
        "```code_block\\n(\\s)*# (topic|tags)_identified:.*?\\n```",
        Operation.REPLACE_ALL_EMPTY,
        "```code_block\n# tags_identified: shopping\n```",
        "```code_block\n# tags: shopping\n```"),
    BLOCKED_TAGS_2(
        "<container>\\n(\\s)*# (topic|tags)_identified:.*?\\n</container>",
        Operation.REPLACE_ALL_EMPTY,
        "<container>\n# topic_identified: sports\n</container>",
        "<container>\n# topic: sports\n</container>"),
    /** URL detection with overlapping alternation prefixes (triggers DFA sandwich) */
    OVERLAPPING_URL(
        "(?i)\\b(?:https?://|www\\.)(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,6}(?:/[a-zA-Z0-9-._~:/?#\\[\\]@!$&'()*+,;=]*)?",
        Operation.REPLACE_ALL_EMPTY,
        "https://www.example.com/search?q=testing&hl=en",
        "some-random-domain-name"),
    /** Case-insensitive keyword matching (exercises compile-time case folding) */
    CASE_INSENSITIVE_KEYWORD(
        "(?i)keyword_to_find",
        Operation.REPLACE_ALL_EMPTY,
        "KEYWORD_TO_FIND",
        "other_random_words"),
    /**
     * Pattern starting with a word boundary and having a suffix alternation (exercises
     * leftmost-first reverse DFA correctness)
     */
    BOUNDED_NAME_MATCH(
        "\\b[Ff]irst [Nn]ame (\\bvalue\\b|\\*\\*value\\*\\*)",
        Operation.REPLACE_ALL_EMPTY,
        "first name value",
        "first name other_value"),
    /**
     * Template tag matching with a trailing boundary (exercises BitState EmptyOp specialization)
     */
    TEMPLATE_TAG_MATCH(
        "(?s)(<template\\s+name\\s*>.*?<\\s*/\\s*template[ \\t]*)([^>]|$)",
        Operation.REPLACE_ALL_EMPTY,
        "<template name>content to match</template>",
        "plain text without template tag");

    // The regular expression pattern string
    private final String patternStr;
    // The operation type (FIND or REPLACE_ALL_EMPTY)
    private final Operation operation;
    // The sample text segment that matches the pattern
    private final String matchPart;
    // The sample text segment that does not match the pattern
    private final String nonMatchPart;

    PatternType(String patternStr, Operation operation, String matchPart, String nonMatchPart) {
      this.patternStr = patternStr;
      this.operation = operation;
      this.matchPart = matchPart;
      this.nonMatchPart = nonMatchPart;
    }
  }

  @Param public EngineType engine;
  @Param public PatternType patternType;

  @Param({"1000", "10000"})
  public int inputSize;

  @Param({"true", "false"})
  public boolean match;

  private RegexEngine regexEngine;
  private BenchmarkOperation benchmarkOp;
  private String testInput;

  @Setup
  public void setup() {
    regexEngine = engine.compile(patternType.patternStr);

    benchmarkOp =
        switch (patternType.operation) {
          case FIND -> input -> regexEngine.finder().find(input);
          case REPLACE_ALL_EMPTY -> input -> regexEngine.replacer().replaceAll(input, "");
        };

    String template = match ? patternType.matchPart : patternType.nonMatchPart;
    testInput = generateInput(template, inputSize);
  }

  @TearDown
  public void tearDown() throws Exception {
    regexEngine.close();
  }

  // Safe characters used to pad test inputs. Excludes vowels to avoid forming accidental
  // matches (e.g. "you" or "your" in WILDCARD_SEARCH) and special regex syntax characters
  // (e.g. "<", ">", "[", "]", etc.) to avoid corrupting the structure of templates.
  private static final String SAFE_DELIMITER_ALPHABET =
      "bcdfghjklmnpqrstvwxyzBCDFGHJKLMNPQRSTVWXYZ0123456789";

  private String generateInput(String template, int size) {
    if (template.length() >= size) {
      return template.substring(0, size);
    }
    StringBuilder sb = new StringBuilder(size);
    Random rand = new Random(42); // Fixed seed for reproducibility
    while (sb.length() < size) {
      sb.append(template);
      if (sb.length() < size) {
        sb.append(SAFE_DELIMITER_ALPHABET.charAt(rand.nextInt(SAFE_DELIMITER_ALPHABET.length())));
      }
    }
    return sb.substring(0, size);
  }

  @Benchmark
  public void runBenchmark(Blackhole bh) {
    bh.consume(benchmarkOp.execute(testInput));
  }
}
