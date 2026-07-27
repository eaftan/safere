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
  SAFERE_STRING("safere-string", "safere", InputRepresentation.JAVA_STRING, allCapabilities()) {
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
            public String group(int group) {
              return matcher.group(group);
            }
          };
        }

        @Override
        public String replaceAll(RegexInput input, String replacement) {
          return pattern.matcher(string(input)).replaceAll(replacement);
        }

        @Override
        public BenchmarkOperation.BenchmarkTask bind(
            BenchmarkOperation operation,
            List<RegexInput> inputs,
            int[] groups,
            String replacement) {
          String input = string(inputs.getFirst());
          return switch (operation) {
            case MATCHES -> blackhole -> blackhole.consume(pattern.matcher(input).matches());
            case FIND -> blackhole -> blackhole.consume(pattern.matcher(input).find());
            default -> CompiledRegex.super.bind(operation, inputs, groups, replacement);
          };
        }
      };
    }
  },
  SAFERE_UTF8(
      "safere-utf8",
      "safere_utf8",
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

        @Override
        public BenchmarkOperation.BenchmarkTask bind(
            BenchmarkOperation operation,
            List<RegexInput> inputs,
            int[] groups,
            String replacement) {
          org.safere.Utf8Input input = utf8(inputs.getFirst());
          return switch (operation) {
            case FIND -> blackhole -> blackhole.consume(pattern.find(input));
            default -> CompiledRegex.super.bind(operation, inputs, groups, replacement);
          };
        }
      };
    }
  },
  JDK_STRING("jdk-string", "jdk", InputRepresentation.JAVA_STRING, allCapabilities()) {
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
            public String group(int group) {
              return matcher.group(group);
            }
          };
        }

        @Override
        public String replaceAll(RegexInput input, String replacement) {
          return pattern.matcher(string(input)).replaceAll(replacement);
        }

        @Override
        public BenchmarkOperation.BenchmarkTask bind(
            BenchmarkOperation operation,
            List<RegexInput> inputs,
            int[] groups,
            String replacement) {
          String input = string(inputs.getFirst());
          return switch (operation) {
            case MATCHES -> blackhole -> blackhole.consume(pattern.matcher(input).matches());
            case FIND -> blackhole -> blackhole.consume(pattern.matcher(input).find());
            default -> CompiledRegex.super.bind(operation, inputs, groups, replacement);
          };
        }
      };
    }
  },
  RE2J_STRING("re2j-string", "re2j", InputRepresentation.JAVA_STRING, allCapabilities()) {
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
            public String group(int group) {
              return matcher.group(group);
            }
          };
        }

        @Override
        public String replaceAll(RegexInput input, String replacement) {
          return pattern.matcher(string(input)).replaceAll(replacement);
        }

        @Override
        public BenchmarkOperation.BenchmarkTask bind(
            BenchmarkOperation operation,
            List<RegexInput> inputs,
            int[] groups,
            String replacement) {
          String input = string(inputs.getFirst());
          return switch (operation) {
            case MATCHES -> blackhole -> blackhole.consume(pattern.matcher(input).matches());
            case FIND -> blackhole -> blackhole.consume(pattern.matcher(input).find());
            default -> CompiledRegex.super.bind(operation, inputs, groups, replacement);
          };
        }
      };
    }
  },
  RE2_FFM_STRING_CONVERSION(
      "re2-ffm-string-conversion",
      "re2_ffm",
      InputRepresentation.JAVA_STRING_WITH_TIMED_UTF8_CONVERSION,
      allCapabilities()) {
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
          };
        }

        @Override
        public String replaceAll(RegexInput input, String replacement) {
          return pattern.matcher(string(input)).replaceAll(replacement);
        }

        @Override
        public BenchmarkOperation.BenchmarkTask bind(
            BenchmarkOperation operation,
            List<RegexInput> inputs,
            int[] groups,
            String replacement) {
          String input = string(inputs.getFirst());
          return switch (operation) {
            case MATCHES -> blackhole -> blackhole.consume(pattern.matcher(input).matches());
            case FIND -> blackhole -> blackhole.consume(pattern.matcher(input).find());
            default -> CompiledRegex.super.bind(operation, inputs, groups, replacement);
          };
        }
      };
    }
  };

  private final String id;
  private final String reportEngine;
  private final InputRepresentation inputRepresentation;
  private final EnumSet<EngineCapability> capabilities;

  RegexEngineVariant(
      String id,
      String reportEngine,
      InputRepresentation inputRepresentation,
      EnumSet<EngineCapability> capabilities) {
    this.id = id;
    this.reportEngine = reportEngine;
    this.inputRepresentation = inputRepresentation;
    this.capabilities = capabilities;
  }

  String id() {
    return id;
  }

  String reportEngine() {
    return reportEngine;
  }

  InputRepresentation inputRepresentation() {
    return inputRepresentation;
  }

  EnumSet<EngineCapability> capabilities() {
    return capabilities.clone();
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

    default String group(int group) {
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

    default BenchmarkOperation.BenchmarkTask bind(
        BenchmarkOperation operation, List<RegexInput> inputs, int[] groups, String replacement) {
      return operation.bind(this, inputs, groups, replacement);
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
