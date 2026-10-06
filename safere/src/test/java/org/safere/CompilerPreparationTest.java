// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Ownership and eager preparation invariants for shared compiler preparation. */
@DisabledForCrosscheck("uses package-private compiler and engine preparation internals")
class CompilerPreparationTest {
  private static final int FLAGS = ParseFlags.LIKE_PERL;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "(a){0}(b)",
        "((a){1,2})+b",
        "((a)+){2}",
        "(a?)*",
        "(?i:é𐐀)+",
        "\\b\\p{L}+\\B",
        "\\X+"
      })
  void preparationCanBeReusedWithoutMutatingSourceOrPrograms(String regex) {
    Regexp source = Parser.parse(regex, FLAGS);
    String sourceForm = source.toString();
    Compiler.Prepared prepared = Compiler.prepare(source);
    assertThat(prepared).isNotNull();
    Prog first = Compiler.compile(prepared, false);
    String instructions = first.dump();

    // Compile the other variants before reusing the same tree for the original variant.
    assertThat(Compiler.compile(prepared, true)).isNotNull();
    assertThat(Compiler.compileForDfa(prepared, false)).isNotNull();
    assertThat(Compiler.compileForDfa(prepared, true)).isNotNull();
    Prog flattened = new Prog(first);
    flattened.flatten();
    flattened.freeze();
    Prog second = Compiler.compile(prepared, false);

    assertThat(second).isNotSameAs(first);
    assertThat(second.inst(second.start())).isNotSameAs(first.inst(first.start()));
    assertThat(first.dump()).isEqualTo(instructions);
    assertThat(second.dump()).isEqualTo(instructions);
    assertThat(source.toString()).isEqualTo(sourceForm);
  }

  private static Stream<Arguments> anchors() {
    return Stream.of(
        Arguments.of("(a){0}(b)", false, false, false),
        Arguments.of("\\A(a){0}(b)", true, false, false),
        Arguments.of("(a){0}(b)\\z", false, true, false),
        Arguments.of("\\A(a){0}(b)$", true, true, true));
  }

  @ParameterizedTest
  @MethodSource("anchors")
  void variantsPreserveSourceCaptureCountAndDirectionalAnchors(
      String regex, boolean start, boolean end, boolean dollar) {
    for (boolean unixLines : new boolean[] {false, true}) {
      Compiler.Prepared prepared =
          Compiler.prepare(Parser.parse(regex, FLAGS | (unixLines ? ParseFlags.UNIX_LINES : 0)));
      for (boolean reversed : new boolean[] {true, false}) {
        for (boolean forDfa : new boolean[] {true, false}) {
          Prog prog =
              forDfa
                  ? Compiler.compileForDfa(prepared, reversed)
                  : Compiler.compile(prepared, reversed);
          assertThat(prog.numCaptures()).isEqualTo(3);
          assertThat(prog.reversed()).isEqualTo(reversed);
          assertThat(prog.anchorStart()).isEqualTo(reversed ? end : start);
          assertThat(prog.anchorEnd()).isEqualTo(reversed ? start : end);
          assertThat(prog.dollarAnchorEnd()).isEqualTo(!reversed && dollar);
          assertThat(prog.dollarAnchorUnixLines()).isEqualTo(!reversed && dollar && unixLines);
        }
      }
    }
  }

  @Test
  void captureAndDfaVariantsKeepTheirOwnLoopRegisters() {
    Compiler.Prepared prepared = Compiler.prepare(Parser.parse("(a?)*b", FLAGS));
    for (boolean reversed : new boolean[] {false, true}) {
      Prog captures = Compiler.compile(prepared, reversed);
      Prog dfa = Compiler.compileForDfa(prepared, reversed);
      assertThat(captures.numLoopRegs()).isPositive();
      assertThat(dfa.numLoopRegs()).isZero();
      assertThat(captures.numCaptures()).isEqualTo(2);
      assertThat(dfa.numCaptures()).isEqualTo(2);
    }
  }

  @Test
  void pikeCaptureRequirementComesFromSourceQuantifiers() {
    Compiler.Prepared prepared = Compiler.prepare(Parser.parse("((a)+){2}", FLAGS));
    assertThat(Compiler.compile(prepared, false).requiresPikeNfaCaptureSemantics()).isTrue();
    assertThat(Compiler.compileForDfa(prepared, false).requiresPikeNfaCaptureSemantics()).isTrue();
    assertThat(Compiler.compile(prepared, true).requiresPikeNfaCaptureSemantics()).isFalse();
    assertThat(Compiler.compileForDfa(prepared, true).requiresPikeNfaCaptureSemantics()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"([0-9]{4})-[0-9]{2}", "foo|bar|baz|quux", "(a?)*b", "((a)+){2}b"})
  void eagerEngineProgramsAndSetupsAreReadyWhenCompileReturns(String regex)
      throws ReflectiveOperationException {
    Pattern pattern = Pattern.compile(regex);
    for (String name : new String[] {"onePassAnalysis", "forwardDfaSetup"}) {
      assertThat(field(pattern, name)).as("%s for %s", name, regex).isNotNull();
    }
  }

  @ParameterizedTest
  @CsvSource({
    "'\\w+z', 'hello abcz', 6, 10",
    "'[a-z]+ing\\b', 'the running dog', 4, 11",
    "'\\d+\\.\\d+', 'pi is 3.14 ok', 6, 10",
    "'((a)+){2}b', 'xxaab', 2, 5"
  })
  void reverseDfaProgramIsBuiltOnFirstUse(String regex, String text, int start, int end)
      throws ReflectiveOperationException {
    Pattern pattern = Pattern.compile(regex);
    assertThat(pattern.canUseReverseDfa()).isTrue();
    assertReverseDfaPrepared(pattern, regex, false);

    Matcher miss = pattern.matcher("");
    assertThat(miss.find()).isFalse();
    assertThat(pattern.matcher(text).matches()).isFalse();
    assertReverseDfaPrepared(pattern, regex, false);

    Matcher matcher = pattern.matcher(text);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(start);
    assertThat(matcher.end()).isEqualTo(end);
    assertReverseDfaPrepared(pattern, regex, true);
  }

  private static void assertReverseDfaPrepared(Pattern pattern, String regex, boolean prepared)
      throws ReflectiveOperationException {
    for (String name : new String[] {"reverseProg", "flatReverseDfaProg", "reverseDfaSetup"}) {
      assertThat(field(pattern, name) != null).as("%s for %s", name, regex).isEqualTo(prepared);
    }
  }

  private static Object field(Pattern pattern, String name) throws ReflectiveOperationException {
    // Inspect fields directly: accessors would build deferred artifacts.
    Field field = Pattern.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(pattern);
  }

  @Test
  void reverseDfaSetupBelongsToTheDfaProgram() {
    Pattern pattern = Pattern.compile("(a?)*b");
    Prog dfa = pattern.flatReverseDfaProg();
    Dfa.Setup setup = pattern.reverseDfaSetup();
    assertThat(dfa.numLoopRegs()).isZero();
    assertThat(pattern.flatReverseProg().numLoopRegs()).isPositive();
    assertThat(pattern.reverseDfaSetup()).isSameAs(setup);
    Dfa.Setup expected = Dfa.buildSetup(dfa);
    assertThat(setup.boundaries()).containsExactly(expected.boundaries());
    assertThat(setup.asciiClassMap()).containsExactly(expected.asciiClassMap());
    assertThat(setup.numClasses()).isEqualTo(expected.numClasses());
    assertThat(setup.splitUnicodeWordClasses()).isEqualTo(expected.splitUnicodeWordClasses());
  }
}
