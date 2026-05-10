// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.PatternSyntaxException;

/** Offline differential sweep for SafeRE character-class expression bugs. */
public final class CharacterClassDivergenceSweep {
  private static final List<String> INPUTS =
      List.of(
          "", "a", "b", "c", "&", "-", "0", "1", "9", "A", "Z", "_", "`", "x", " ", "\t", "\u0100",
          "\u00e9", "\n", "]");

  private static final List<Piece> BASE_PIECES =
      List.of(
          piece("empty", ""),
          piece("a", "a"),
          piece("b", "b"),
          piece("ab", "ab"),
          piece("rangeAB", "a-b"),
          piece("zero", "0"),
          piece("range01", "0-1"),
          piece("rawAmp", "&"),
          piece("escapedAmp", "\\&"),
          piece("quoteAmp", "\\Q&\\E"),
          piece("quoteA", "\\Qa\\E"),
          piece("quoteAB", "\\Qab\\E"),
          piece("quoteEmpty", "\\Q\\E"),
          piece("nonInline", "\u0100"),
          piece("escapedNonAscii", "\\\u0100"),
          piece("nestedA", "[a]"),
          piece("nestedB", "[b]"),
          piece("nestedAB", "[ab]"),
          piece("nestedNotB", "[^b]"),
          piece("digit", "\\d"),
          piece("nonDigit", "\\D"),
          piece("word", "\\w"),
          piece("nonWord", "\\W"),
          piece("propertyLower", "\\p{Lower}"),
          piece("propertyNotLower", "\\P{Lower}"),
          piece("propertyJavaLower", "\\p{javaLowerCase}"));

  private static final List<Piece> LEFT_PIECES =
      List.of(
          piece("a", "a"),
          piece("b", "b"),
          piece("ab", "ab"),
          piece("rangeAB", "a-b"),
          piece("zero", "0"),
          piece("digit", "\\d"),
          piece("word", "\\w"),
          piece("nestedA", "[a]"),
          piece("nestedB", "[b]"),
          piece("nestedAB", "[ab]"),
          piece("quoteA", "\\Qa\\E"),
          piece("quoteAB", "\\Qab\\E"));

  private static final List<Piece> AMPERSAND_PIECES =
      List.of(piece("rawAmp", "&"), piece("escapedAmp", "\\&"), piece("quoteAmp", "\\Q&\\E"));

  private static final List<Piece> RIGHT_PIECES =
      List.of(
          piece("empty", ""),
          piece("a", "a"),
          piece("b", "b"),
          piece("ab", "ab"),
          piece("rangeAB", "a-b"),
          piece("zero", "0"),
          piece("range01", "0-1"),
          piece("rawAmp", "&"),
          piece("escapedAmp", "\\&"),
          piece("quoteAmp", "\\Q&\\E"),
          piece("quoteA", "\\Qa\\E"),
          piece("quoteEmpty", "\\Q\\E"),
          piece("nonInline", "\u0100"),
          piece("escapedNonAscii", "\\\u0100"),
          piece("nestedA", "[a]"),
          piece("nestedB", "[b]"),
          piece("nestedAB", "[ab]"),
          piece("digit", "\\d"),
          piece("word", "\\w"),
          piece("nonDigit", "\\D"),
          piece("propertyLower", "\\p{Lower}"),
          piece("propertyNotLower", "\\P{Lower}"),
          piece("propertyJavaLower", "\\p{javaLowerCase}"));

  private static final List<Piece> TRAILING_PIECES =
      List.of(
          piece("none", ""),
          piece("rawAmp", "&"),
          piece("escapedAmp", "\\&"),
          piece("quoteAmp", "\\Q&\\E"),
          piece("rangeToNonDigit", "-\\D"),
          piece("rangeToA", "-a"),
          piece("rangeToAmp", "-&"),
          piece("rawAmpRangeToAmp", "&-&"),
          piece("rawAmpRangeToA", "&-a"),
          piece("rangeToIntersection", "-&&"),
          piece("zeroWidthRangeToNonDigit", "\\Q\\E-\\D"),
          piece("doubleClose", "]"));

  private static final List<Piece> SEPARATORS =
      List.of(
          piece("none", ""),
          piece("emptyQuote", "\\Q\\E"),
          piece("twoEmptyQuotes", "\\Q\\E\\Q\\E"),
          piece("space", " "),
          piece("comment", " #x\n"),
          piece("emptyQuoteSpace", "\\Q\\E "),
          piece("spaceEmptyQuote", " \\Q\\E"));

  private static final List<Piece> COMMENT_SEPARATORS =
      List.of(
          piece("none", ""),
          piece("emptyQuote", "\\Q\\E"),
          piece("twoEmptyQuotes", "\\Q\\E\\Q\\E"),
          piece("space", " "),
          piece("comment", " #x\n"),
          piece("emptyQuoteSpace", "\\Q\\E "),
          piece("spaceEmptyQuote", " \\Q\\E"));

  private static final List<Piece> OPERATORS =
      List.of(
          piece("&&", "&&"),
          piece("&&&", "&&&"),
          piece("&&&&", "&&&&"),
          piece("&&&&&", "&&&&&"),
          piece("&&&&&&", "&&&&&&"));

  private static final List<Piece> SECOND_OPERATORS =
      List.of(piece("&&", "&&"), piece("&&&", "&&&"), piece("&&&&", "&&&&"));

  private static final List<Piece> GRAMMAR_LEFT_ATOMS =
      List.of(
          piece("empty", ""),
          piece("a", "a"),
          piece("dash", "-"),
          piece("rangeAB", "a-b"),
          piece("digit", "\\d"),
          piece("nestedA", "[a]"),
          piece("nestedB", "[b]"),
          piece("quoteAmp", "\\Q&\\E"));

  private static final List<Piece> GRAMMAR_RHS_ATOMS =
      List.of(
          piece("empty", ""),
          piece("a", "a"),
          piece("b", "b"),
          piece("nestedA", "[a]"),
          piece("nestedB", "[b]"),
          piece("nestedAB", "[ab]"),
          piece("propertyNotLower", "\\P{Lower}"),
          piece("digit", "\\d"));

  private static final List<Piece> GRAMMAR_TAILS =
      List.of(
          piece("none", ""),
          piece("rangeToAmp", "-&"),
          piece("rawAmpRangeToAmp", "&-&"),
          piece("rawAmpRangeToA", "&-a"),
          piece("rangeToA", "-a"),
          piece("propertyNotLower", "\\P{Lower}"),
          piece("doubleClose", "]"));

  private static final List<Piece> GRAMMAR_ZERO_WIDTH_AND_TRIVIA =
      List.of(
          piece("none", ""),
          piece("emptyQuote", "\\Q\\E"),
          piece("twoEmptyQuotes", "\\Q\\E\\Q\\E"),
          piece("space", " "),
          piece("comment", " #x\n"),
          piece("emptyQuoteSpace", "\\Q\\E "),
          piece("spaceEmptyQuote", " \\Q\\E"));

  private static final int DEFAULT_MAX_PER_BUCKET = Integer.MAX_VALUE;

  private CharacterClassDivergenceSweep() {}

  public static void main(String[] args) throws IOException {
    Options options = Options.parse(args);
    Files.createDirectories(options.outputDir());
    Files.deleteIfExists(options.jsonlPath());

    if (options.replayFile() != null) {
      runReplay(options);
      return;
    }

    RunState state = runSweep(options);

    System.out.println("checked=" + state.checked.sum());
    System.out.println("generated=" + state.generated);
    System.out.println("divergences=" + state.divergences.sum());
    System.out.println("buckets=" + state.buckets.size());
    System.out.println("threads=" + options.threads());
    System.out.println("jsonl=" + options.jsonlPath());
  }

  private static RunState runSweep(Options options) throws IOException {
    RunState runState = new RunState(options);
    if (options.threads() == 1) {
      SweepState worker = new SweepState(runState, 0);
      runClassicMatrix(worker);
      runChainedAmpersandMatrix(worker);
      runGrammarSequenceMatrix(worker);
      worker.finish();
      return runState;
    }

    AtomicReference<Throwable> failure = new AtomicReference<>();
    List<Thread> workers = new ArrayList<>();
    for (int i = 0; i < options.threads(); i++) {
      int workerIndex = i;
      Thread worker =
          new Thread(
              () -> {
                try {
                  SweepState state = new SweepState(runState, workerIndex);
                  runClassicMatrix(state);
                  runChainedAmpersandMatrix(state);
                  runGrammarSequenceMatrix(state);
                  state.finish();
                } catch (Throwable t) {
                  failure.compareAndSet(null, t);
                }
              },
              "character-class-sweep-" + workerIndex);
      worker.start();
      workers.add(worker);
    }
    for (Thread worker : workers) {
      try {
        worker.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted while waiting for sweep workers", e);
      }
    }
    Throwable throwable = failure.get();
    if (throwable != null) {
      if (throwable instanceof Error error) {
        throw error;
      }
      if (throwable instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IOException("sweep worker failed", throwable);
    }
    return runState;
  }

  private static void runReplay(Options options) throws IOException {
    long generated = 0;
    long checked = 0;
    long divergences = 0;
    try (BufferedReader reader =
        Files.newBufferedReader(options.replayFile(), StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        generated++;
        checked++;
        String regex = jsonField(trimmed, "regex");
        regex = regex == null ? unjson(trimmed) : regex;
        Outcome jdk = jdkOutcome(regex);
        Outcome safere = safeReOutcome(regex);
        if (semanticallyEqual(jdk, safere)) {
          continue;
        }
        divergences++;
        String replayLine =
            "{"
                + "\"regex\":\""
                + json(regex)
                + "\","
                + "\"jdkAccepted\":"
                + jdk.accepted()
                + ","
                + "\"safeReAccepted\":"
                + safere.accepted()
                + ","
                + "\"jdkMatches\":\""
                + json(jdk.matches())
                + "\","
                + "\"safeReMatches\":\""
                + json(safere.matches())
                + "\","
                + "\"jdkError\":\""
                + json(jdk.error())
                + "\","
                + "\"safeReError\":\""
                + json(safere.error())
                + "\""
                + "}";
        Files.writeString(
            options.jsonlPath(),
            replayLine + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      }
    }
    System.out.println("checked=" + checked);
    System.out.println("generated=" + generated);
    System.out.println("divergences=" + divergences);
    System.out.println("buckets=" + divergences);
    System.out.println("threads=1");
    System.out.println("jsonl=" + options.jsonlPath());
    if (divergences > 0) {
      throw new IllegalStateException("replay found " + divergences + " behavioral divergences");
    }
  }

  private static String jsonField(String line, String field) {
    String prefix = "\"" + field + "\":\"";
    int start = line.indexOf(prefix);
    if (start < 0) {
      return null;
    }
    start += prefix.length();
    StringBuilder value = new StringBuilder();
    boolean escaped = false;
    for (int i = start; i < line.length(); i++) {
      char c = line.charAt(i);
      if (escaped) {
        value.append('\\').append(c);
        escaped = false;
      } else if (c == '\\') {
        escaped = true;
      } else if (c == '"') {
        return unjson(value.toString());
      } else {
        value.append(c);
      }
    }
    throw new IllegalArgumentException("unterminated JSON field in line: " + line);
  }

  private static String unjson(String value) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c != '\\') {
        result.append(c);
        continue;
      }
      if (++i >= value.length()) {
        throw new IllegalArgumentException("trailing JSON escape in: " + value);
      }
      char escaped = value.charAt(i);
      switch (escaped) {
        case 'n' -> result.append('\n');
        case 't' -> result.append('\t');
        case 'r' -> result.append('\r');
        case 'b' -> result.append('\b');
        case 'f' -> result.append('\f');
        case '"', '\\' -> result.append(escaped);
        case 'u' -> {
          if (i + 4 >= value.length()) {
            throw new IllegalArgumentException("short JSON unicode escape in: " + value);
          }
          result.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
          i += 4;
        }
        default -> result.append(escaped);
      }
    }
    return result.toString();
  }

  private static void runClassicMatrix(SweepState state) {
    for (boolean comments : List.of(false, true)) {
      for (boolean negated : List.of(false, true)) {
        List<Piece> separators = comments ? COMMENT_SEPARATORS : nonCommentSeparators();
        for (Piece first : BASE_PIECES) {
          for (Piece second : BASE_PIECES) {
            for (Piece separator : separators) {
              for (Piece operator : OPERATORS) {
                for (Piece afterOperator : separators) {
                  for (Piece right : RIGHT_PIECES) {
                    for (Piece trailing : TRAILING_PIECES) {
                      state.check(
                          new CaseSpec(
                              "classic-two-lefts",
                              comments,
                              negated,
                              List.of(
                                  first,
                                  second,
                                  separator,
                                  operator,
                                  afterOperator,
                                  right,
                                  trailing)));
                    }
                  }
                }
              }
            }
          }
        }
        for (Piece first : BASE_PIECES) {
          for (Piece ampersand : AMPERSAND_PIECES) {
            for (Piece separator : separators) {
              for (Piece operator : OPERATORS) {
                for (Piece afterOperator : separators) {
                  for (Piece right : RIGHT_PIECES) {
                    for (Piece trailing : TRAILING_PIECES) {
                      state.check(
                          new CaseSpec(
                              "classic-raw-amp-left",
                              comments,
                              negated,
                              List.of(
                                  first,
                                  ampersand,
                                  separator,
                                  operator,
                                  afterOperator,
                                  right,
                                  trailing)));
                    }
                  }
                }
              }
            }
          }
        }
        for (Piece separator : separators) {
          for (Piece operator : OPERATORS) {
            for (Piece afterOperator : separators) {
              for (Piece right : RIGHT_PIECES) {
                for (Piece trailing : TRAILING_PIECES) {
                  state.check(
                      new CaseSpec(
                          "classic-no-left",
                          comments,
                          negated,
                          List.of(separator, operator, afterOperator, right, trailing)));
                }
              }
            }
          }
        }
      }
    }
  }

  private static void runChainedAmpersandMatrix(SweepState state) {
    for (boolean negated : List.of(false, true)) {
      for (Piece left : LEFT_PIECES) {
        for (Piece separator1 : COMMENT_SEPARATORS) {
          for (Piece firstOperator : OPERATORS) {
            for (Piece separator2 : COMMENT_SEPARATORS) {
              for (Piece ampersand : AMPERSAND_PIECES) {
                for (Piece separator3 : COMMENT_SEPARATORS) {
                  for (Piece secondOperator : SECOND_OPERATORS) {
                    for (Piece separator4 : COMMENT_SEPARATORS) {
                      for (Piece right : RIGHT_PIECES) {
                        for (Piece trailing : TRAILING_PIECES) {
                          state.check(
                              new CaseSpec(
                                  "chained-raw-amp",
                                  true,
                                  negated,
                                  List.of(
                                      left,
                                      separator1,
                                      firstOperator,
                                      separator2,
                                      ampersand,
                                      separator3,
                                      secondOperator,
                                      separator4,
                                      right,
                                      trailing)));
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
      for (Piece left : LEFT_PIECES) {
        for (Piece separator1 : COMMENT_SEPARATORS) {
          for (Piece firstOperator : OPERATORS) {
            for (Piece separator2 : COMMENT_SEPARATORS) {
              for (Piece nested : nestedRights()) {
                for (Piece separator3 : COMMENT_SEPARATORS) {
                  for (Piece secondOperator : SECOND_OPERATORS) {
                    for (Piece separator4 : COMMENT_SEPARATORS) {
                      for (Piece right : RIGHT_PIECES) {
                        state.check(
                            new CaseSpec(
                                "chained-nested-rhs",
                                true,
                                negated,
                                List.of(
                                    left,
                                    separator1,
                                    firstOperator,
                                    separator2,
                                    nested,
                                    separator3,
                                    secondOperator,
                                    separator4,
                                    right)));
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  private static void runGrammarSequenceMatrix(SweepState state) {
    for (boolean comments : List.of(false, true)) {
      for (boolean negated : List.of(false, true)) {
        List<Piece> trivia = comments ? GRAMMAR_ZERO_WIDTH_AND_TRIVIA : nonCommentSeparators();
        for (Piece left : GRAMMAR_LEFT_ATOMS) {
          for (Piece operator : OPERATORS) {
            for (Piece right : GRAMMAR_RHS_ATOMS) {
              for (Piece tail1 : GRAMMAR_TAILS) {
                for (Piece separator : trivia) {
                  for (Piece tail2 : GRAMMAR_TAILS) {
                    state.check(
                        new CaseSpec(
                            "grammar-sequence",
                            comments,
                            negated,
                            List.of(left, operator, right, tail1, separator, tail2)));
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  private static List<Piece> nestedRights() {
    return List.of(
        piece("nestedA", "[a]"),
        piece("nestedB", "[b]"),
        piece("nestedAB", "[ab]"),
        piece("nestedNotB", "[^b]"));
  }

  private static List<Piece> nonCommentSeparators() {
    return SEPARATORS.stream().filter(separator -> !separator.isCommentsOnly()).toList();
  }

  private static Piece piece(String label, String text) {
    return new Piece(label, text);
  }

  private static Outcome jdkOutcome(String regex) {
    try {
      return new Outcome(true, matches(java.util.regex.Pattern.compile(regex)), "");
    } catch (PatternSyntaxException e) {
      return new Outcome(false, "", e.getDescription());
    } catch (RuntimeException e) {
      return new Outcome(false, "", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static Outcome safeReOutcome(String regex) {
    try {
      return new Outcome(true, matches(org.safere.Pattern.compile(regex)), "");
    } catch (PatternSyntaxException e) {
      return new Outcome(false, "", e.getDescription());
    } catch (RuntimeException e) {
      return new Outcome(false, "", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static String matches(java.util.regex.Pattern pattern) {
    StringBuilder result = new StringBuilder();
    for (String input : INPUTS) {
      if (pattern.matcher(input).matches()) {
        appendInput(result, input);
      }
    }
    return result.toString();
  }

  private static String matches(org.safere.Pattern pattern) {
    StringBuilder result = new StringBuilder();
    for (String input : INPUTS) {
      if (pattern.matcher(input).matches()) {
        appendInput(result, input);
      }
    }
    return result.toString();
  }

  private static void appendInput(StringBuilder result, String input) {
    if (result.length() > 0) {
      result.append(',');
    }
    result.append(escape(input));
  }

  private static String escape(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
        .replace("\"", "\\\"");
  }

  private static String differenceInputs(Outcome jdk, Outcome safere) {
    if (jdk.accepted() != safere.accepted()) {
      return "compile";
    }
    return "jdk=" + jdk.matches() + ";safere=" + safere.matches();
  }

  private static String bucketFor(CaseSpec spec, Outcome jdk, Outcome safere) {
    String kind = jdk.accepted() != safere.accepted() ? "compile" : "membership";
    String direction = direction(jdk, safere);
    return String.join(
        "|",
        "kind=" + kind,
        "direction=" + direction,
        "template=" + spec.template(),
        "comments=" + spec.comments(),
        "negated=" + spec.negated(),
        "ops=" + operatorLabels(spec),
        "seps=" + separatorClass(spec),
        "rawAmp=" + containsLabel(spec, "rawAmp"),
        "escapedAmp=" + containsLabel(spec, "escapedAmp"),
        "quoteEmpty=" + containsLabel(spec, "quoteEmpty"),
        "nested=" + containsPrefix(spec, "nested"),
        "rangeTail=" + containsRangeTail(spec),
        "doubleClose=" + containsLabel(spec, "doubleClose"));
  }

  private static String direction(Outcome jdk, Outcome safere) {
    if (jdk.accepted() && !safere.accepted()) {
      return "jdk-accepts";
    }
    if (!jdk.accepted() && safere.accepted()) {
      return "safere-accepts";
    }
    return "matches";
  }

  private static String operatorLabels(CaseSpec spec) {
    List<String> labels = new ArrayList<>();
    for (Piece piece : spec.pieces()) {
      if (piece.text().startsWith("&&")) {
        labels.add(piece.label());
      }
    }
    return String.join(",", labels);
  }

  private static String separatorClass(CaseSpec spec) {
    boolean hasComment = containsLabel(spec, "comment");
    boolean hasSpace =
        containsLabel(spec, "space")
            || containsLabel(spec, "emptyQuoteSpace")
            || containsLabel(spec, "spaceEmptyQuote");
    boolean hasEmptyQuote =
        containsLabel(spec, "emptyQuote")
            || containsLabel(spec, "twoEmptyQuotes")
            || containsLabel(spec, "emptyQuoteSpace")
            || containsLabel(spec, "spaceEmptyQuote");
    return (hasComment ? "comment" : "no-comment")
        + ","
        + (hasSpace ? "space" : "no-space")
        + ","
        + (hasEmptyQuote ? "emptyQuote" : "no-emptyQuote");
  }

  private static boolean containsLabel(CaseSpec spec, String label) {
    return spec.pieces().stream().anyMatch(piece -> piece.label().equals(label));
  }

  private static boolean containsPrefix(CaseSpec spec, String prefix) {
    return spec.pieces().stream().anyMatch(piece -> piece.label().startsWith(prefix));
  }

  private static boolean containsRangeTail(CaseSpec spec) {
    return spec.pieces().stream()
        .anyMatch(piece -> piece.label().contains("range") || piece.label().contains("Range"));
  }

  private record Piece(String label, String text) {
    boolean isCommentsOnly() {
      return text.contains(" ") || text.contains("#");
    }
  }

  private record CaseSpec(String template, boolean comments, boolean negated, List<Piece> pieces) {
    String regex() {
      StringBuilder regex = new StringBuilder();
      if (comments) {
        regex.append("(?x)");
      }
      regex.append('[');
      if (negated) {
        regex.append('^');
      }
      for (Piece piece : pieces) {
        regex.append(piece.text());
      }
      regex.append(']');
      return regex.toString();
    }

    String labels() {
      List<String> labels = new ArrayList<>();
      for (Piece piece : pieces) {
        if (!piece.text().isEmpty()) {
          labels.add(piece.label());
        }
      }
      return String.join(",", labels);
    }
  }

  private record Outcome(boolean accepted, String matches, String error) {}

  private record Divergence(
      CaseSpec spec, String regex, Outcome jdk, Outcome safere, String bucket, String reduced) {
    String toJson() {
      return "{"
          + "\"bucket\":\""
          + json(bucket)
          + "\","
          + "\"template\":\""
          + json(spec.template())
          + "\","
          + "\"labels\":\""
          + json(spec.labels())
          + "\","
          + "\"regex\":\""
          + json(regex)
          + "\","
          + "\"reduced\":\""
          + json(reduced)
          + "\","
          + "\"jdkAccepted\":"
          + jdk.accepted()
          + ","
          + "\"safeReAccepted\":"
          + safere.accepted()
          + ","
          + "\"jdkMatches\":\""
          + json(jdk.matches())
          + "\","
          + "\"safeReMatches\":\""
          + json(safere.matches())
          + "\","
          + "\"jdkError\":\""
          + json(jdk.error())
          + "\","
          + "\"safeReError\":\""
          + json(safere.error())
          + "\""
          + "}";
    }
  }

  private static String json(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\t", "\\t");
  }

  private static final class RunState {
    final Options options;
    final Map<String, Bucket> buckets = new LinkedHashMap<>();
    final LongAdder checked = new LongAdder();
    final LongAdder divergences = new LongAdder();
    long generated;
    long nextProgressReport;

    RunState(Options options) {
      this.options = options;
      this.nextProgressReport =
          firstProgressAt(options.rangeStartInclusive(), options.progressInterval());
    }

    synchronized void recordGenerated(long workerGenerated) {
      if (workerGenerated > generated) {
        generated = workerGenerated;
      }
    }

    synchronized void reportProgressIfNeeded(long workerGenerated) {
      recordGenerated(workerGenerated);
      if (generated < nextProgressReport) {
        return;
      }
      System.out.printf(
          "progress generated=%,d checked=%,d divergences=%,d buckets=%,d jsonl=%s%n",
          generated, checked.sum(), divergences.sum(), buckets.size(), options.jsonlPath());
      while (nextProgressReport <= generated) {
        nextProgressReport += options.progressInterval();
      }
    }

    boolean reserveDivergenceExample(String bucketName) {
      synchronized (this) {
        divergences.increment();
        Bucket bucket = buckets.computeIfAbsent(bucketName, Bucket::new);
        if (bucket.savedExamples >= options.maxPerBucket()) {
          return false;
        }
        bucket.savedExamples++;
        return true;
      }
    }

    synchronized void appendJsonl(Divergence divergence) {
      try {
        Files.writeString(
            options.jsonlPath(),
            divergence.toJson() + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private static final class SweepState {
    final RunState runState;
    final Options options;
    final int workerIndex;
    long generated;
    long nextProgressReport;

    SweepState(RunState runState, int workerIndex) {
      this.runState = runState;
      this.options = runState.options;
      this.workerIndex = workerIndex;
      this.nextProgressReport =
          firstProgressAt(options.rangeStartInclusive(), options.progressInterval());
    }

    void check(CaseSpec spec) {
      if (generated >= options.rangeEndExclusive()) {
        return;
      }
      long caseIndex = generated++;
      if (caseIndex < options.rangeStartInclusive()) {
        reportProgressIfNeeded();
        return;
      }
      if (caseIndex % options.threads() != workerIndex) {
        reportProgressIfNeeded();
        return;
      }
      runState.checked.increment();
      String regex = spec.regex();
      Outcome jdk = jdkOutcome(regex);
      Outcome safere = safeReOutcome(regex);
      if (semanticallyEqual(jdk, safere)) {
        reportProgressIfNeeded();
        return;
      }
      String bucketName = bucketFor(spec, jdk, safere);
      if (!runState.reserveDivergenceExample(bucketName)) {
        reportProgressIfNeeded();
        return;
      }
      Divergence divergence =
          new Divergence(spec, regex, jdk, safere, bucketName, reduce(spec, jdk, safere));
      runState.appendJsonl(divergence);
      reportProgressIfNeeded();
    }

    void finish() {
      runState.recordGenerated(generated);
    }

    void reportProgressIfNeeded() {
      if (generated < nextProgressReport) {
        return;
      }
      runState.reportProgressIfNeeded(generated);
      while (nextProgressReport <= generated) {
        nextProgressReport += options.progressInterval();
      }
    }
  }

  private static String reduce(CaseSpec spec, Outcome expectedJdk, Outcome expectedSafeRe) {
    List<Piece> pieces = new ArrayList<>(spec.pieces());
    boolean changed;
    do {
      changed = false;
      for (int i = 0; i < pieces.size(); i++) {
        if (pieces.get(i).text().isEmpty()) {
          continue;
        }
        List<Piece> candidate = new ArrayList<>(pieces);
        candidate.set(i, piece("removed", ""));
        String regex =
            new CaseSpec(spec.template(), spec.comments(), spec.negated(), candidate).regex();
        Outcome jdk = jdkOutcome(regex);
        Outcome safere = safeReOutcome(regex);
        if (semanticallyEqual(expectedJdk, jdk) && semanticallyEqual(expectedSafeRe, safere)) {
          pieces = candidate;
          changed = true;
          break;
        }
      }
    } while (changed);
    return new CaseSpec(spec.template(), spec.comments(), spec.negated(), pieces).regex();
  }

  private static boolean semanticallyEqual(Outcome left, Outcome right) {
    if (left.accepted() != right.accepted()) {
      return false;
    }
    return !left.accepted() || left.matches().equals(right.matches());
  }

  private static String printable(String value) {
    return value.replace("\n", "\\n").replace("\t", "\\t");
  }

  private static final class Bucket {
    final String name;
    int savedExamples;

    Bucket(String name) {
      this.name = name;
    }
  }

  private static String maxPerBucketDescription(int maxPerBucket) {
    return maxPerBucket == Integer.MAX_VALUE ? "uncapped" : Integer.toString(maxPerBucket);
  }

  private static long firstProgressAt(long rangeStartInclusive, long progressInterval) {
    if (rangeStartInclusive <= 0) {
      return progressInterval;
    }
    long remainder = rangeStartInclusive % progressInterval;
    if (remainder == 0) {
      return rangeStartInclusive;
    }
    return rangeStartInclusive + (progressInterval - remainder);
  }

  private record Options(
      long rangeStartInclusive,
      long rangeEndExclusive,
      int maxPerBucket,
      Path outputDir,
      long progressInterval,
      int threads,
      Path replayFile) {
    Path jsonlPath() {
      return outputDir.resolve("character-class-divergences.jsonl");
    }

    static Options parse(String[] args) {
      long rangeStartInclusive = 0;
      long rangeEndExclusive = Long.MAX_VALUE;
      int maxPerBucket = DEFAULT_MAX_PER_BUCKET;
      Path outputDir = Path.of("target", "exhaustive-reports", "character-class-sweep");
      long progressInterval = 1_000_000;
      int threads = 1;
      Path replayFile = null;
      for (String arg : args) {
        if (arg.startsWith("--range=")) {
          String value = arg.substring("--range=".length());
          int colon = value.indexOf(':');
          if (colon < 0) {
            throw new IllegalArgumentException("--range must use start:end syntax");
          }
          String start = value.substring(0, colon);
          String end = value.substring(colon + 1);
          rangeStartInclusive = start.isEmpty() ? 0 : Long.parseLong(start);
          rangeEndExclusive = end.isEmpty() ? Long.MAX_VALUE : Long.parseLong(end);
        } else if (arg.startsWith("--max-per-bucket=")) {
          String value = arg.substring("--max-per-bucket=".length());
          maxPerBucket = value.equals("uncapped") ? Integer.MAX_VALUE : Integer.parseInt(value);
        } else if (arg.startsWith("--output-dir=")) {
          outputDir = Path.of(arg.substring("--output-dir=".length()));
        } else if (arg.startsWith("--progress-interval=")) {
          progressInterval = Long.parseLong(arg.substring("--progress-interval=".length()));
        } else if (arg.startsWith("--threads=")) {
          threads = Integer.parseInt(arg.substring("--threads=".length()));
        } else if (arg.startsWith("--replay-file=")) {
          replayFile = Path.of(arg.substring("--replay-file=".length()));
        } else {
          throw new IllegalArgumentException("unknown argument: " + arg);
        }
      }
      if (rangeStartInclusive < 0 || rangeEndExclusive < 0) {
        throw new IllegalArgumentException("--range bounds must be non-negative");
      }
      if (rangeEndExclusive < rangeStartInclusive) {
        throw new IllegalArgumentException("--range end must be greater than or equal to start");
      }
      if (maxPerBucket < 0) {
        throw new IllegalArgumentException("--max-per-bucket must be non-negative");
      }
      if (progressInterval < 1) {
        throw new IllegalArgumentException("--progress-interval must be at least 1");
      }
      if (threads < 1) {
        throw new IllegalArgumentException("--threads must be at least 1");
      }
      if (replayFile != null && !Files.isRegularFile(replayFile)) {
        throw new IllegalArgumentException("--replay-file must be a regular file");
      }
      return new Options(
          rangeStartInclusive,
          rangeEndExclusive,
          maxPerBucket,
          outputDir,
          progressInterval,
          threads,
          replayFile);
    }
  }
}
