# Running benchmarks

Run commands from the repository root. Read the
[performance guide](PERFORMANCE_GUIDE.md) before collecting or interpreting results.


SafeRE includes a [JMH](https://github.com/openjdk/jmh) benchmark suite in the
`safere-benchmarks` module, comparing SafeRE against `java.util.regex` (JDK),
[RE2/J](https://github.com/google/re2j), RE2-FFM (C++ RE2 via Java
 [FFM API](https://openjdk.org/jeps/454)), C++ RE2, PCRE2 JIT, Go `regexp`, and
 Rust [`regex`](https://crates.io/crates/regex), and .NET's non-backtracking
 regex engine.
The suite includes focused microbenchmarks, data-driven application workloads,
scaling/pathological cases, replacement, memory, and `PatternSet` benchmarks.
Benchmark recipes and configuration live in
`safere-benchmarks/benchmark-data.json`. Before a benchmark starts, its runner
materializes that file into a resolved manifest and exact UTF-8 inputs under
`safere-benchmarks/target/benchmark-corpus`. Java, C++, Go, Rust, and .NET
consume only those generated artifacts instead of independently interpreting
input recipes.
See
[`safere-benchmarks/BENCHMARK_INPUTS.md`](BENCHMARK_INPUTS.md)
for details.

Workloads using existing generic operations and input recipes can be added
through data declarations: these select engine capabilities, inputs, timing
modes, and result consumption. New operation or recipe kinds also require
harness implementation and validation. See
[`safere-benchmarks/DECLARATIVE_BENCHMARK_PLAN.md`](DECLARATIVE_BENCHMARK_PLAN.md)
and
[`safere-benchmarks/DECLARATIVE_COLLECTION.md`](DECLARATIVE_COLLECTION.md).

SafeRE also maintains a separate
[OpenJDK-derived regex benchmark suite](https://github.com/eaftan/safere-openjdk-regex-benchmarks).
It compares SafeRE and `java.util.regex` on compatible workloads adapted from
OpenJDK's regex microbenchmarks. That suite is GPL-2.0-only, so its source is
not vendored here or included in the `safere-benchmarks` Maven module.

### Benchmark Collection

To collect a full set of benchmark data for updating
[BENCHMARKS.md](../BENCHMARKS.md), run the collection script from the repository
root:

```bash
./collect-benchmark-results.sh
```

The default collection includes SafeRE's Java suite—SafeRE,
`java.util.regex`, RE2/J, and RE2-FFM—and the external OpenJDK-derived
SafeRE/JDK suite. Clone the external repository beside SafeRE before the first
collection:

```bash
git clone https://github.com/eaftan/safere-openjdk-regex-benchmarks.git \
  ../safere-openjdk-regex-benchmarks
```

These are the normal engineering comparisons because the engines run in the
same JVM environment. Use `--openjdk-regex-repo PATH` when the external
checkout is elsewhere.

Use the longer Java mode when confirming close, surprising, or especially
important comparisons:

```bash
./collect-benchmark-results.sh --long
```

Use the cross-language mode only when you need broader ecosystem context from
C++ RE2, PCRE2 JIT, Go `regexp`, Rust `regex`, and .NET non-backtracking:

```bash
./collect-benchmark-results.sh --cross-language
```

To verify the collection pipeline without doing a full run:

```bash
./collect-benchmark-results.sh --smoke
```

The script runs benchmark batches sequentially, captures raw output, and
generates markdown tables.

The collection script installs the SafeRE version from the current checkout,
builds the external suite against that exact version, and runs both engines.
Use `--skip-openjdk-regex` only for a deliberately incomplete local collection,
such as when the separate checkout is unavailable. A comprehensive
cross-runtime collection uses `--cross-language`; the OpenJDK-derived suite
remains included by default.

The OpenJDK-derived results remain a separate result set: they are not folded
into `merged-tables.md` because the external workloads and their upstream JMH
schedules differ from SafeRE's native suite.

By default, results are written to a timestamped directory under
`benchmark-results/`, and `benchmark-results/latest` is updated to point to
the newest run.

When the run finishes, inspect the result directory before updating `BENCHMARKS.md`:

```text
benchmark-results/latest
```

The important files in that directory are:

```text
jmh-output.txt
normalized-results.jsonl
declared-report-plan.json
merged-tables.md
java-memory.txt
java-pattern-memory.txt
```

Cross-language runs also include:

```text
cpp-results.jsonl
go-results.jsonl
rust-results.jsonl
dotnet-results.jsonl
cross-runtime-tables.md
```

`normalized-results.jsonl` combines the parsed Java and selected native
measurements into the common engine/benchmark/score/error/unit schema used by
the comparison tooling. Reviewed result sets supporting published claims are
retained under `benchmark-results/published/<full-SafeRE-commit>/`; other
timestamped result directories remain local and ignored by Git.

Default runs also include:

```text
openjdk-regex-output.txt
openjdk-regex-results.json
```

### Targeted Benchmark Runs

Always use the wrapper scripts — they run `mvn install` first to ensure
the benchmark module picks up the latest SafeRE code. These are useful for
development iteration or focused investigation; use
`./collect-benchmark-results.sh` for a full collection.

```bash
# Java benchmarks (time per operation)
./run-java-benchmarks.sh                        # standard benchmarks
./run-java-benchmarks.sh --declared             # all declared execution profiles
./run-java-benchmarks.sh --long --declared

# Java memory profiling (allocation rates via JMH GC profiler)
./run-java-memory-benchmarks.sh --declared
```

For a controlled before/after comparison of two commits that share the same workload and benchmark
harness definitions, use:

```bash
./safere-benchmarks/scripts/compare-branch.sh \
  --baseline origin/main \
  --current HEAD \
  'RegexBenchmark\.emailFind@safere-string'
```

The command resolves both refs before switching revisions, rebuilds each revision, and prints a
normalized comparison table. Each invocation must select exactly one SafeRE execution variant;
run String and UTF-8 comparisons separately. Use `--vector` when both revisions should enable the
experimental Vector provider, and use `--long` to confirm close, surprising, or important results.
The command deliberately refuses comparisons when workload data, runner settings, harness code, or
relevant build definitions differ. In those cases, construct a controlled baseline that uses the
same benchmark definitions, or use the full collection workflow when preparing a published report.

Use `BenchmarkCollectionPlan trials` to discover trial IDs by mode, timing
unit, workload prefix, or execution variant. Benchmark regexes select generic
JMH entry points, and arguments after `--` can select a specific trial or pass
other JMH options. See
[`safere-benchmarks/CROSS_ENGINE_EXECUTION.md`](CROSS_ENGINE_EXECUTION.md)
for workload IDs, execution variants, and timing boundaries.

Run a targeted workload from the external OpenJDK-derived suite with:

```bash
./run-openjdk-regex-benchmarks.sh \
  'org.safere.bench.openjdk.FindPatternComparison.*'
```

The wrapper defaults to a sibling `safere-openjdk-regex-benchmarks` checkout.
Use `--repo PATH` or `SAFERE_OPENJDK_REGEX_BENCHMARKS_REPO` to select another
location. Standard runs preserve the JMH schedules defined by the external
suite; `--smoke` provides a short compile-and-execute check.

`CrosscheckOverheadBenchmark` is excluded from the no-argument Java benchmark
run. It measures overhead in the `safere-crosscheck` facade and should be run
explicitly only when optimizing crosscheck:

```bash
./run-java-benchmarks.sh '^org\.safere\.benchmark\.CrosscheckOverheadBenchmark\.'
```

### Cross-Runtime Benchmarks

The benchmark suite includes C++ RE2, PCRE2 JIT, Go `regexp`, Rust `regex`, and
.NET non-backtracking harnesses for cross-language comparison. Each runner
executes every workload implemented by its adapter when no filter is supplied.
The C++ engines share one workload harness, so PCRE2 does not use a narrower
workload allowlist than RE2.

Toolchain requirements, installation links, per-engine commands, JIT
requirements, memory-platform limits, and smoke-test instructions are in
[`safere-benchmarks/CROSS_RUNTIME_ENGINES.md`](CROSS_RUNTIME_ENGINES.md).

Benchmark patterns and replacement templates are written in Java syntax. A
value that needs different syntax in another regex dialect declares exact
alternatives beside its Java-canonical definition in
`safere-benchmarks/benchmark-data.json`; a missing alternate means that the Java
value is used unchanged. Harnesses do not translate syntax or infer replacement
templates from operation names. See the
[syntax-profile schema](DECLARATIVE_BENCHMARK_PLAN.md#pattern-profiles)
for profile mappings and validation rules.

```bash
# All C++ engines, or one engine
./run-cpp-benchmarks.sh                    # all native C++ benchmarks
./run-cpp-benchmarks.sh --engine re2
./run-cpp-benchmarks.sh --engine pcre2-jit
./run-cpp-benchmarks.sh Regex Application  # specific benchmark groups

# Go regexp benchmarks
./run-go-benchmarks.sh                     # all Go benchmarks
./run-go-benchmarks.sh RegexBenchmark.literalMatch # smoke test
./run-go-benchmarks.sh Regex Application   # specific benchmark groups

# Rust regex benchmarks
./run-rust-benchmarks.sh                   # all Rust benchmarks
./run-rust-benchmarks.sh RegexBenchmark.literalMatch # smoke test
./run-rust-benchmarks.sh Regex Application # specific benchmark groups

# .NET non-backtracking benchmarks
./run-dotnet-benchmarks.sh                    # all supported .NET workloads
./run-dotnet-benchmarks.sh --smoke            # exercise each supported workload once
./run-dotnet-benchmarks.sh --list-exclusions  # explain every unsupported workload
./run-dotnet-benchmarks.sh Regex Application  # specific benchmark groups
```

The .NET harness uses `RegexOptions.NonBacktracking` and
`RegexOptions.CultureInvariant`. It decodes the shared UTF-8 corpus and
selects exact `dotnet` pattern- and replacement-profile alternatives before
timing. Unicode scalar ranges use equivalent UTF-16 regex expressions,
including surrogate-pair alternatives where needed. This setup work is
excluded from execution and compilation measurements. The runner consumes the
fully expanded workload plan and executes every compatible workload.
`--list-exclusions` emits a reason for every excluded workload.

### Comparing Results Manually

A comparison script turns JMH output into side-by-side markdown:

```bash
python3 safere-benchmarks/scripts/compare-benchmarks.py \
  --jmh jmh-output.txt
```

Add the cross-runtime JSON-lines files when comparing cross-language results.
The C++ file contains both `re2_cpp` and `pcre2_jit` records:

```bash
python3 safere-benchmarks/scripts/compare-benchmarks.py \
  --jmh jmh-output.txt \
  --json cpp-results.jsonl go-results.jsonl rust-results.jsonl dotnet-results.jsonl \
  --engines safere,jdk,re2j,re2_ffm,re2_cpp,pcre2_jit,go,rust,dotnet_nonbacktracking
```

To distinguish absent results from declared exclusions, include the report plan:

```bash
python3 safere-benchmarks/scripts/compare-benchmarks.py \
  --jmh jmh-output.txt \
  --declared-plan declared-report-plan.json
```
