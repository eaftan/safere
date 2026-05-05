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
of stopping at the first one. The Surefire summary can still report zero
failures when Jazzer continues successfully, so inspect the XML report for the
actual findings:

```bash
rg -n '== Java Exception|AssertionError|CrosscheckException|divergence|DEDUP_TOKEN' \
  safere-fuzz/target/surefire-reports/TEST-org.safere.fuzz.CharacterClassExpressionFuzzer.xml
```

Jazzer JUnit runs usually write replayable `crash-*` inputs to the checked-in
seed directory for the fuzz target, not to `target/fuzz-reproducers`:

```bash
find safere-fuzz/src/test/resources/org/safere/fuzz/CharacterClassExpressionFuzzerInputs \
  -type f -name 'crash-*' -printf '%TY-%Tm-%Td %TH:%TM:%TS %p\n' | sort
```

The `target/fuzz-reproducers` directory is only for standalone Java reproducers
when Jazzer writes them, and may not exist even when the XML report contains
findings. The `.cifuzz-corpus` directory is a persistent coverage corpus, not a
per-run list of bugs.

## Findings

When Jazzer finds a valid divergence, crash, hang, or stack overflow:

1. Minimize the reproducer.
2. Add a normal JUnit regression test in `safere/src/test/java/org/safere`.
3. Fix SafeRE.
4. Re-run the focused regression test, `mvn -pl safere test`, and the relevant
   fuzz target.

Expected syntax errors and intentionally unsupported non-linear regex features
are valid fuzzer inputs.
