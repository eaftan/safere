// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.safere.Matcher;
import org.safere.Pattern;
import org.safere.Utf8Input;
import org.safere.Utf8Matcher;

/** Capture-consumption cycles used only by this investigation. */
final class CapturePolicyProtocol {
  private CapturePolicyProtocol() {}

  static final class Input {
    final String text;
    final byte[] bytes;

    Input(byte[] bytes) {
      this(new String(bytes, StandardCharsets.UTF_8), bytes);
    }

    Input(String text, byte[] bytes) {
      this.bytes = bytes;
      this.text = text;
    }
  }

  interface Cursor {
    boolean find();

    void reset();

    int start(int group);

    int end(int group);

    String group(int group);
  }

  interface Compiled {
    Cursor matcher(Input input);
  }

  interface Task {
    int run();
  }

  record Usage(String mode, int readEvery) {
    Usage {
      if (!List.of("text", "offset", "phase", "boolean").contains(mode) || readEvery < 1) {
        throw new IllegalArgumentException("Invalid capture consumption mode or read period");
      }
    }
  }

  static Compiled compile(String regex, RegexEngineVariant variant) {
    Pattern pattern = Pattern.compile(regex);
    if (variant == RegexEngineVariant.SAFERE_STRING) {
      return input -> {
        Matcher matcher = pattern.matcher(input.text);
        return new Cursor() {
          @Override
          public boolean find() {
            return matcher.find();
          }

          @Override
          public void reset() {
            matcher.reset();
          }

          @Override
          public int start(int group) {
            return matcher.start(group);
          }

          @Override
          public int end(int group) {
            return matcher.end(group);
          }

          @Override
          public String group(int group) {
            return matcher.group(group);
          }
        };
      };
    }
    if (variant != RegexEngineVariant.SAFERE_UTF8) {
      throw new IllegalArgumentException("Capture policy investigation supports String and UTF-8");
    }
    return input -> {
      Utf8Matcher matcher = pattern.matcher(Utf8Input.trusted(input.bytes));
      return new Cursor() {
        @Override
        public boolean find() {
          return matcher.find();
        }

        @Override
        public void reset() {
          matcher.reset();
        }

        @Override
        public int start(int group) {
          return matcher.start(group);
        }

        @Override
        public int end(int group) {
          return matcher.end(group);
        }

        @Override
        public String group(int group) {
          int start = matcher.start(group);
          int end = matcher.end(group);
          return start < 0
              ? null
              : new String(input.bytes, start, end - start, StandardCharsets.UTF_8);
        }
      };
    };
  }

  static Task bind(
      Compiled pattern, List<Input> inputs, int group, boolean reuse, int rounds, Usage usage) {
    if (inputs.isEmpty() || group < 0 || rounds < 1) {
      throw new IllegalArgumentException(
          "Expected inputs, a nonnegative group and positive rounds");
    }
    List<Cursor> retained = reuse ? inputs.stream().map(pattern::matcher).toList() : null;
    return () -> cycle(pattern, inputs, retained, group, rounds, usage);
  }

  private static int cycle(
      Compiled pattern,
      List<Input> inputs,
      List<Cursor> retained,
      int group,
      int rounds,
      Usage usage) {
    int sum = 0;
    int hits = 0;
    for (int phase = 0; phase < 3; phase++) {
      for (int round = 0; round < rounds; round++) {
        for (int i = 0; i < inputs.size(); i++) {
          Cursor matcher = retained == null ? pattern.matcher(inputs.get(i)) : retained.get(i);
          if (retained != null) {
            matcher.reset();
          }
          while (matcher.find()) {
            sum++;
            boolean read = hits++ % usage.readEvery() == 0 && !usage.mode().equals("boolean");
            if (read && !(usage.mode().equals("phase") && phase == 1)) {
              if (usage.mode().equals("offset")) {
                sum += matcher.end(group) - matcher.start(group);
              } else {
                String text = matcher.group(group);
                sum += text == null ? 0 : text.hashCode();
              }
            }
          }
        }
      }
    }
    return sum;
  }
}
