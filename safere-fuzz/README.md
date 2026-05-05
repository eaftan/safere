# SafeRE Fuzz Tests

This module contains Jazzer fuzz targets for SafeRE. The targets use
`safere-crosscheck` as the oracle: each operation runs against both SafeRE and
`java.util.regex`, and `CrosscheckException` signals a semantic divergence.

## Targets

- `CompileFuzzer` fuzzes compile/reject behavior.
- `ParserCompatibilityFuzzer` fuzzes grammar-biased compile and membership compatibility.
- `CharacterClassExpressionFuzzer` fuzzes JDK character-class expression syntax.
- `EscapeSyntaxFuzzer` fuzzes escape syntax and escaped literal compatibility.
- `DialectSyntaxFuzzer` fuzzes non-JDK-looking syntax and dialect boundary cases.
- `ParserStackSafetyFuzzer` fuzzes parser nesting depth and stack safety.
- `CaptureSemanticsFuzzer` fuzzes quantified capture semantics and capture-observing APIs.
- `MatchFuzzer` fuzzes `matches()`, `lookingAt()`, `find()`, and `find(int)`.
- `FindSequenceFuzzer` fuzzes stateful matcher API call sequences.
- `ReplacementFuzzer` fuzzes replacement APIs.
- `SplitFuzzer` fuzzes `split` and `splitWithDelimiters`.
- `RegionBoundsFuzzer` fuzzes regions and anchoring/transparent bounds.
- `UnicodeFuzzer` biases input strings toward Unicode boundary cases.

## Regression Mode

Without `JAZZER_FUZZ`, Jazzer runs each target as a JUnit parameterized test
over the empty input and the checked-in seed corpus. Seed inputs live under
`src/test/resources/org/safere/fuzz/<FuzzerClass>Inputs/<methodName>/`.

```bash
mvn -pl safere-fuzz -am test
```

Run one target:

```bash
mvn -pl safere-fuzz -am -Dtest=MatchFuzzer -Dsurefire.failIfNoSpecifiedTests=false test
```

## Fuzzing Mode

Set `JAZZER_FUZZ=1` to run coverage-guided fuzzing. Jazzer runs one fuzz test
per Maven invocation, so select a single target with `-Dtest`.

The Maven configuration disables Jazzer's `RegexInjection` sanitizer for these
targets. SafeRE fuzzers intentionally compile generated patterns with both
SafeRE and `java.util.regex`; sanitizer findings on the JDK oracle are noise for
this crosscheck workflow.

```bash
JAZZER_FUZZ=1 mvn -pl safere-fuzz -am -Dtest=MatchFuzzer \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Limit a local run with Jazzer options:

```bash
JAZZER_FUZZ=1 mvn -pl safere-fuzz -am -Dtest=MatchFuzzer \
  -Dsurefire.failIfNoSpecifiedTests=false -Djazzer.max_duration=2m test
```

Collect multiple findings from one run:

```bash
JAZZER_FUZZ=1 mvn -pl safere-fuzz -am -Dtest=CharacterClassExpressionFuzzer \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Djazzer.max_duration=30m \
  -Djazzer.keep_going=10 \
  -Djazzer.reproducer_path=target/fuzz-reproducers \
  test
```

`jazzer.keep_going` tells Jazzer to keep fuzzing after distinct findings instead
of stopping at the first one.

## Findings

When Jazzer finds a valid divergence, crash, hang, or stack overflow:

1. Minimize the reproducer.
2. Add a normal JUnit regression test in `safere/src/test/java/org/safere`.
3. Fix SafeRE.
4. Re-run the focused regression test, `mvn -pl safere test`, and the relevant
   fuzz target.

Expected syntax errors and intentionally unsupported non-linear regex features
are valid fuzzer inputs.
