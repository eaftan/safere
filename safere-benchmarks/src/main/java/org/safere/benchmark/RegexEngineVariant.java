// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Java-side regex execution variants and their representation/timing boundaries. */
enum RegexEngineVariant {
  SAFERE_STRING(
      "safere-string", "safere", "java", InputRepresentation.JAVA_STRING, allCapabilities()) {
    @Override
    CompiledRegex compile(String regex) {
      org.safere.Pattern pattern = org.safere.Pattern.compile(regex);
      return new CompiledRegex() {
        @Override
        public boolean find(RegexInput input) {
          return pattern.matcher(string(input)).find();
        }

        @Override
        public boolean matches(RegexInput input) {
          return pattern.matcher(string(input)).matches();
        }

        @Override
        public MatchCursor matcher(RegexInput input) {
          org.safere.Matcher matcher = pattern.matcher(string(input));
          return new MatchCursor() {
            @Override
            public boolean find() {
              return matcher.find();
            }

            @Override
            public boolean matches() {
              return matcher.matches();
            }

            @Override
            public boolean lookingAt() {
              return matcher.lookingAt();
            }

            @Override
            public String group(int group) {
              return matcher.group(group);
            }

            @Override
            public void reset() {
              matcher.reset();
            }

            @Override
            public void region(int start, int end) {
              matcher.region(start, end);
            }

            @Override
            public void appendReplacement(StringBuilder result, String replacement) {
              matcher.appendReplacement(result, replacement);
            }

            @Override
            public void appendTail(StringBuilder result) {
              matcher.appendTail(result);
            }
          };
        }

        @Override
        public String replaceFirst(RegexInput input, String replacement) {
          return pattern.matcher(string(input)).replaceFirst(replacement);
        }

        @Override
        public String replaceAll(RegexInput input, String replacement) {
          return pattern.matcher(string(input)).replaceAll(replacement);
        }

        @Override
        public String[] split(RegexInput input, int limit) {
          return pattern.split(string(input), limit);
        }
      };
    }
  },
  SAFERE_UTF8(
      "safere-utf8",
      "safere_utf8",
      "java",
      InputRepresentation.PREEXISTING_UTF8,
      EnumSet.of(EngineCapability.FIND)) {
    @Override
    CompiledRegex compile(String regex) {
      org.safere.Pattern pattern = org.safere.Pattern.compile(regex);
      return new CompiledRegex() {
        @Override
        public boolean find(RegexInput input) {
          return pattern.find(utf8(input));
        }

        @Override
        public MatchCursor matcher(RegexInput input) {
          org.safere.Utf8Matcher matcher = pattern.matcher(utf8(input));
          return new MatchCursor() {
            @Override
            public boolean find() {
              return matcher.find();
            }
          };
        }
      };
    }
  },
  JDK_STRING("jdk-string", "jdk", "java", InputRepresentation.JAVA_STRING, allCapabilities()) {
    @Override
    CompiledRegex compile(String regex) {
      java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
      return new CompiledRegex() {
        @Override
        public boolean find(RegexInput input) {
          return pattern.matcher(string(input)).find();
        }

        @Override
        public boolean matches(RegexInput input) {
          return pattern.matcher(string(input)).matches();
        }

        @Override
        public MatchCursor matcher(RegexInput input) {
          java.util.regex.Matcher matcher = pattern.matcher(string(input));
          return new MatchCursor() {
            @Override
            public boolean find() {
              return matcher.find();
            }

            @Override
            public boolean matches() {
              return matcher.matches();
            }

            @Override
            public boolean lookingAt() {
              return matcher.lookingAt();
            }

            @Override
            public String group(int group) {
              return matcher.group(group);
            }

            @Override
            public void reset() {
              matcher.reset();
            }

            @Override
            public void region(int start, int end) {
              matcher.region(start, end);
            }

            @Override
            public void appendReplacement(StringBuilder result, String replacement) {
              matcher.appendReplacement(result, replacement);
            }

            @Override
            public void appendTail(StringBuilder result) {
              matcher.appendTail(result);
            }
          };
        }

        @Override
        public String replaceFirst(RegexInput input, String replacement) {
          return pattern.matcher(string(input)).replaceFirst(replacement);
        }

        @Override
        public String replaceAll(RegexInput input, String replacement) {
          return pattern.matcher(string(input)).replaceAll(replacement);
        }

        @Override
        public String[] split(RegexInput input, int limit) {
          return pattern.split(string(input), limit);
        }
      };
    }
  },
  RE2J_STRING("re2j-string", "re2j", "re2", InputRepresentation.JAVA_STRING, re2jCapabilities()) {
    @Override
    CompiledRegex compile(String regex) {
      com.google.re2j.Pattern pattern = com.google.re2j.Pattern.compile(regex);
      return new CompiledRegex() {
        @Override
        public boolean find(RegexInput input) {
          return pattern.matcher(string(input)).find();
        }

        @Override
        public boolean matches(RegexInput input) {
          return pattern.matcher(string(input)).matches();
        }

        @Override
        public MatchCursor matcher(RegexInput input) {
          com.google.re2j.Matcher matcher = pattern.matcher(string(input));
          return new MatchCursor() {
            @Override
            public boolean find() {
              return matcher.find();
            }

            @Override
            public boolean matches() {
              return matcher.matches();
            }

            @Override
            public boolean lookingAt() {
              return matcher.lookingAt();
            }

            @Override
            public String group(int group) {
              return matcher.group(group);
            }

            @Override
            public void reset() {
              matcher.reset();
            }

            @Override
            public void appendReplacement(StringBuilder result, String replacement) {
              matcher.appendReplacement(result, replacement);
            }

            @Override
            public void appendTail(StringBuilder result) {
              matcher.appendTail(result);
            }
          };
        }

        @Override
        public String replaceFirst(RegexInput input, String replacement) {
          return pattern.matcher(string(input)).replaceFirst(replacement);
        }

        @Override
        public String replaceAll(RegexInput input, String replacement) {
          return pattern.matcher(string(input)).replaceAll(replacement);
        }

        @Override
        public String[] split(RegexInput input, int limit) {
          return pattern.split(string(input), limit);
        }
      };
    }
  },
  RE2_FFM_STRING_CONVERSION(
      "re2-ffm-string-conversion",
      "re2_ffm",
      "re2",
      InputRepresentation.JAVA_STRING_WITH_TIMED_UTF8_CONVERSION,
      ffmCapabilities()) {
    @Override
    CompiledRegex compile(String regex) {
      org.safere.re2ffm.RE2FfmPattern pattern = org.safere.re2ffm.RE2FfmPattern.compile(regex);
      return new CompiledRegex() {
        @Override
        public boolean find(RegexInput input) {
          return pattern.matcher(string(input)).find();
        }

        @Override
        public boolean matches(RegexInput input) {
          return pattern.matcher(string(input)).matches();
        }

        @Override
        public MatchCursor matcher(RegexInput input) {
          org.safere.re2ffm.RE2FfmMatcher matcher = pattern.matcher(string(input));
          return new MatchCursor() {
            @Override
            public boolean find() {
              return matcher.find();
            }

            @Override
            public boolean matches() {
              return matcher.matches();
            }

            @Override
            public String group(int group) {
              return matcher.group(group);
            }

            @Override
            public void reset() {
              matcher.reset();
            }
          };
        }

        @Override
        public String replaceFirst(RegexInput input, String replacement) {
          return pattern.matcher(string(input)).replaceFirst(replacement);
        }

        @Override
        public String replaceAll(RegexInput input, String replacement) {
          return pattern.matcher(string(input)).replaceAll(replacement);
        }

        @Override
        public String[] split(RegexInput input, int limit) {
          return pattern.split(string(input), limit);
        }
      };
    }
  };

  private final String id;
  private final String reportEngine;
  private final String patternProfile;
  private final InputRepresentation inputRepresentation;
  private final EnumSet<EngineCapability> capabilities;

  RegexEngineVariant(
      String id,
      String reportEngine,
      String patternProfile,
      InputRepresentation inputRepresentation,
      EnumSet<EngineCapability> capabilities) {
    this.id = id;
    this.reportEngine = reportEngine;
    this.patternProfile = patternProfile;
    this.inputRepresentation = inputRepresentation;
    this.capabilities = capabilities;
  }

  String id() {
    return id;
  }

  String reportEngine() {
    return reportEngine;
  }

  String patternProfile() {
    return patternProfile;
  }

  InputRepresentation inputRepresentation() {
    return inputRepresentation;
  }

  EnumSet<EngineCapability> capabilities() {
    return capabilities.clone();
  }

  DeclarativeBenchmarkPlan.EngineDeclaration declaration() {
    EnumSet<DeclarativeBenchmarkPlan.Feature> features =
        EnumSet.noneOf(DeclarativeBenchmarkPlan.Feature.class);
    for (EngineCapability capability : capabilities) {
      switch (capability) {
        case COMPILE -> {}
        case FIND -> features.add(DeclarativeBenchmarkPlan.Feature.FIND);
        case MATCHES -> features.add(DeclarativeBenchmarkPlan.Feature.MATCHES);
        case LOOKING_AT -> features.add(DeclarativeBenchmarkPlan.Feature.LOOKING_AT);
        case GROUP_TEXT -> features.add(DeclarativeBenchmarkPlan.Feature.CAPTURE_TEXT);
        case REPLACE -> {
          features.add(DeclarativeBenchmarkPlan.Feature.REPLACE);
          features.add(DeclarativeBenchmarkPlan.Feature.NUMBERED_REPLACEMENT);
        }
        case APPEND_REPLACEMENT -> {
          features.add(DeclarativeBenchmarkPlan.Feature.APPEND_REPLACEMENT);
          features.add(DeclarativeBenchmarkPlan.Feature.NUMBERED_REPLACEMENT);
        }
        case SPLIT -> features.add(DeclarativeBenchmarkPlan.Feature.SPLIT);
        case MATCHER_RESET -> features.add(DeclarativeBenchmarkPlan.Feature.MATCHER_STATE);
        case REGIONS -> {
          features.add(DeclarativeBenchmarkPlan.Feature.MATCHER_STATE);
          features.add(DeclarativeBenchmarkPlan.Feature.REGIONS);
        }
      }
    }
    switch (this) {
      case SAFERE_STRING -> {
        features.add(DeclarativeBenchmarkPlan.Feature.DFA_CACHE);
        features.add(DeclarativeBenchmarkPlan.Feature.DIAGNOSTICS);
        features.add(DeclarativeBenchmarkPlan.Feature.FLAGGED_COMPILE);
        features.add(DeclarativeBenchmarkPlan.Feature.JAVA_CHARACTER_CLASS);
        features.add(DeclarativeBenchmarkPlan.Feature.LINEAR_TIME);
        features.add(DeclarativeBenchmarkPlan.Feature.PATTERN_SET);
        features.add(DeclarativeBenchmarkPlan.Feature.RETAINED_HEAP);
      }
      case SAFERE_UTF8 -> {
        features.add(DeclarativeBenchmarkPlan.Feature.LINEAR_TIME);
        features.add(DeclarativeBenchmarkPlan.Feature.MATCHER_STATE);
        features.add(DeclarativeBenchmarkPlan.Feature.REGIONS);
        features.add(DeclarativeBenchmarkPlan.Feature.UTF8_INPUT);
        features.add(DeclarativeBenchmarkPlan.Feature.UTF8_REPLACEMENT);
      }
      case JDK_STRING -> {
        features.add(DeclarativeBenchmarkPlan.Feature.FLAGGED_COMPILE);
        features.add(DeclarativeBenchmarkPlan.Feature.JAVA_CHARACTER_CLASS);
        features.add(DeclarativeBenchmarkPlan.Feature.RETAINED_HEAP);
      }
      case RE2J_STRING -> {
        features.add(DeclarativeBenchmarkPlan.Feature.LINEAR_TIME);
        features.add(DeclarativeBenchmarkPlan.Feature.RETAINED_HEAP);
      }
      case RE2_FFM_STRING_CONVERSION -> features.add(DeclarativeBenchmarkPlan.Feature.LINEAR_TIME);
    }
    DeclarativeBenchmarkPlan.InputRepresentation representation =
        switch (inputRepresentation) {
          case JAVA_STRING -> DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING;
          case PREEXISTING_UTF8 -> DeclarativeBenchmarkPlan.InputRepresentation.PREEXISTING_UTF8;
          case JAVA_STRING_WITH_TIMED_UTF8_CONVERSION ->
              DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING_WITH_TIMED_UTF8_CONVERSION;
        };
    return new DeclarativeBenchmarkPlan.EngineDeclaration(
        id, representation, features, EnumSet.noneOf(DeclarativeBenchmarkPlan.Flag.class), true);
  }

  List<RegexInput> prepareInputs(BenchmarkData data, List<String> inputKeys) {
    List<RegexInput> inputs = new ArrayList<>(inputKeys.size());
    for (String inputKey : inputKeys) {
      if (inputRepresentation == InputRepresentation.PREEXISTING_UTF8) {
        inputs.add(new Utf8RegexInput(org.safere.Utf8Input.trusted(data.getInputBytes(inputKey))));
      } else {
        inputs.add(new StringRegexInput(data.getInputString(inputKey)));
      }
    }
    return List.copyOf(inputs);
  }

  abstract CompiledRegex compile(String regex);

  Object compileForBenchmark(String regex, String flagSet) {
    int flags = flagSet == null ? 0 : BenchmarkFlags.parse(flagSet);
    return switch (this) {
      case SAFERE_STRING, SAFERE_UTF8 -> org.safere.Pattern.compile(regex, flags);
      case JDK_STRING -> java.util.regex.Pattern.compile(regex, flags);
      case RE2J_STRING -> {
        if (flags != 0) {
          throw new UnsupportedOperationException("RE2/J flagged compilation is unsupported");
        }
        yield com.google.re2j.Pattern.compile(regex);
      }
      case RE2_FFM_STRING_CONVERSION -> {
        if (flags != 0) {
          throw new UnsupportedOperationException("RE2-FFM flagged compilation is unsupported");
        }
        yield org.safere.re2ffm.RE2FfmPattern.compile(regex);
      }
    };
  }

  static RegexEngineVariant fromId(String id) {
    for (RegexEngineVariant variant : values()) {
      if (variant.id.equals(id)) {
        return variant;
      }
    }
    throw new IllegalArgumentException("Unknown cross-engine execution variant: " + id);
  }

  private static EnumSet<EngineCapability> allCapabilities() {
    return EnumSet.allOf(EngineCapability.class);
  }

  private static EnumSet<EngineCapability> ffmCapabilities() {
    return EnumSet.of(
        EngineCapability.COMPILE,
        EngineCapability.FIND,
        EngineCapability.MATCHES,
        EngineCapability.GROUP_TEXT,
        EngineCapability.REPLACE,
        EngineCapability.SPLIT,
        EngineCapability.MATCHER_RESET);
  }

  private static EnumSet<EngineCapability> re2jCapabilities() {
    EnumSet<EngineCapability> capabilities = allCapabilities();
    capabilities.remove(EngineCapability.REGIONS);
    return capabilities;
  }

  private static String string(RegexInput input) {
    return ((StringRegexInput) input).value();
  }

  private static org.safere.Utf8Input utf8(RegexInput input) {
    return ((Utf8RegexInput) input).value();
  }

  sealed interface RegexInput permits StringRegexInput, Utf8RegexInput {}

  record StringRegexInput(String value) implements RegexInput {}

  record Utf8RegexInput(org.safere.Utf8Input value) implements RegexInput {}

  interface MatchCursor {
    default boolean find() {
      throw new UnsupportedOperationException();
    }

    default boolean matches() {
      throw new UnsupportedOperationException();
    }

    default boolean lookingAt() {
      throw new UnsupportedOperationException();
    }

    default String group(int group) {
      throw new UnsupportedOperationException();
    }

    default void reset() {
      throw new UnsupportedOperationException();
    }

    default void region(int start, int end) {
      throw new UnsupportedOperationException();
    }

    default void appendReplacement(StringBuilder result, String replacement) {
      throw new UnsupportedOperationException();
    }

    default void appendTail(StringBuilder result) {
      throw new UnsupportedOperationException();
    }
  }

  interface CompiledRegex extends AutoCloseable {
    default boolean find(RegexInput input) {
      throw new UnsupportedOperationException();
    }

    default boolean matches(RegexInput input) {
      throw new UnsupportedOperationException();
    }

    default MatchCursor matcher(RegexInput input) {
      throw new UnsupportedOperationException();
    }

    default String replaceAll(RegexInput input, String replacement) {
      throw new UnsupportedOperationException();
    }

    default String replaceFirst(RegexInput input, String replacement) {
      throw new UnsupportedOperationException();
    }

    default String[] split(RegexInput input, int limit) {
      throw new UnsupportedOperationException();
    }

    @Override
    default void close() {}
  }

  enum InputRepresentation {
    JAVA_STRING,
    PREEXISTING_UTF8,
    JAVA_STRING_WITH_TIMED_UTF8_CONVERSION
  }
}
