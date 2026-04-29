# SafeRE Fuzz Tests

This module contains Jazzer fuzz targets for SafeRE. The targets use
`safere-crosscheck` as the oracle: each operation runs against both SafeRE and
`java.util.regex`, and `CrosscheckException` signals a semantic divergence.

## Targets

- `CompileFuzzer` fuzzes compile/reject behavior.
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
mvn -pl safere-fuzz test
```

Run one target:

```bash
mvn -pl safere-fuzz -Dtest=MatchFuzzer test
```

## Fuzzing Mode

Set `JAZZER_FUZZ=1` to run coverage-guided fuzzing. Jazzer runs one fuzz test
per Maven invocation, so select a single target with `-Dtest`.

```bash
JAZZER_FUZZ=1 mvn -pl safere-fuzz -Dtest=MatchFuzzer test
```

Limit a local run with Jazzer options:

```bash
JAZZER_FUZZ=1 mvn -pl safere-fuzz -Dtest=MatchFuzzer \
  -Djazzer.max_duration=2m test
```

## Findings

When Jazzer finds a valid divergence, crash, hang, or stack overflow:

1. Minimize the reproducer.
2. Add a normal JUnit regression test in `safere/src/test/java/org/safere`.
3. Fix SafeRE.
4. Re-run the focused regression test, `mvn -pl safere test`, and the relevant
   fuzz target.

Expected syntax errors and intentionally unsupported non-linear regex features
are valid fuzzer inputs.
