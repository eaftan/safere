# SafeRE Vector API benchmarks

This benchmark-only module compares SafeRE's current UTF-8 SWAR scanners with optional
`jdk.incubator.vector` prototypes. It is intentionally outside the normal Maven reactor so SafeRE's
production and standard benchmark builds retain their existing JDK requirements.

Run the smoke or standard configuration with any supported JDK (21 through 26):

```bash
./run-vector-benchmarks.sh --smoke
./run-vector-benchmarks.sh
```

The shared Java benchmark module targets JDK 22 because it includes the FFM engine. To measure the
Vector provider on JDK 21, first build and materialize the benchmark artifacts with the production
JDK 26 toolchain, then select JDK 21 and reuse those Java 21-compatible artifacts with
`./run-vector-benchmarks.sh --no-build`.

Use `--long` for confirmation runs and `--trials` to select comma-separated trial IDs from
`safere-benchmarks/benchmark-data.json`. The runner passes `--add-modules=jdk.incubator.vector` to
both the host JVM and JMH forks. All inputs and trial lists come from the shared benchmark data.
Use `--methods` with a pipe-separated list to select benchmark implementations, for example
`--methods 'swar|vectorBounds|vectorCursor'`.
Use `--end-to-end` to run complete `Pattern.find(Utf8Input)` and repeated `Utf8Matcher.find()`
comparisons in separate SWAR and Vector JVMs. Use `--provider swar` or `--provider vector` to run
only one side. The latter exercises the production multi-release JAR selection path and immutable
startup selection. End-to-end runs exclude singleton regexes because they are compiled to the
literal scanner and therefore do not exercise the character-class provider.

The Vector implementation, activation property, supported scans, and tuning thresholds are
experimental and may change incompatibly or be removed in any SafeRE release.

The `swarProvider` and `vectorProvider` methods call their scanners through stable, monomorphic
benchmark-only provider interfaces. They measure the dispatch shape an optional provider would add
independently of the production multi-release selection mechanism.

The `vectorCursor` benchmark drains all matching lanes from each vector mask before advancing. It
models a scan-all cursor that retains rather than discards the remaining matches in a loaded vector.
The `vectorBounds` benchmark broadcasts every range bound before entering the input loop and then
compares loaded input vectors with those retained bound vectors.
The `safeRe` benchmark runs the corresponding complete UTF-8 `Pattern.find` operation for first-hit
trials and a reset `Utf8Matcher.find` loop for scan-all trials.

The trial ID format is `shape/traversal/density/length/offset`, where:

- `shape` is `singleton`, `pair`, `range`, `range2`, `alnum3`, or `mixed4`;
- `traversal` is `first` or `all`;
- `density` is `absent`, `late`, `sparse`, `dense`, `prefixFalse`, or `nonascii`;
- `length` is the logical UTF-8 byte length; and
- `offset` is the borrowed array window's starting offset.
