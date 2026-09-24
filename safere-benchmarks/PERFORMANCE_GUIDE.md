# Performance Work

Read this guide when running benchmarks, changing benchmark workloads, profiling,
optimizing, or publishing performance claims. Commands run from the repository root.
For report collection and publication, also use the
[write-benchmark-report skill](../.agents/skills/write-benchmark-report/SKILL.md).

## Benchmarking

### Running Benchmarks

**Use `./run-java-benchmarks.sh` for SafeRE's Java benchmark suite.** Use the
collection wrappers below for additional suites. The Java runner uses `mvn install` to build a shaded (fat) JAR, then runs it with
`java -jar`. This is required for JMH fork mode — forked JVMs need a
self-contained classpath. Do NOT use `mvn exec:java`, which breaks fork
mode because the forked child cannot find JMH classes.

The script is the **single source of truth** for benchmark settings.
Benchmark classes have no `@Fork`, `@Warmup`, or `@Measurement` annotations
— all statistical rigor settings are controlled by the script.

```bash
# BENCHMARKS.md updates and routine benchmark evidence
./run-java-benchmarks.sh '^org\.safere\.benchmark\.CrossEngineBenchmark\.'

# Longer confirmation run for close, surprising, or important comparisons
./run-java-benchmarks.sh --long '^org\.safere\.benchmark\.CrossEngineBenchmark\.'

# Separately licensed OpenJDK-derived suite (requires an external checkout)
./run-openjdk-regex-benchmarks.sh

# Full collection; includes the external OpenJDK-derived suite by default
./collect-benchmark-results.sh

# Longer confirmation collection
./collect-benchmark-results.sh --long

# Add cross-runtime engines
./collect-benchmark-results.sh --cross-language
```

Arguments after the mode flag are passed directly to JMH as benchmark regex
filters.

**Run benchmarks sequentially.** Concurrent workloads compete for CPU, cache,
and memory bandwidth, invalidating comparisons. Use the declarative collection
plan to select generic runners and trials:

```bash
./run-java-benchmarks.sh --declared
./run-java-benchmarks.sh --smoke --declared
```

**Extract summary tables from JMH output** using grep:

```bash
./run-java-benchmarks.sh '^org\.safere\.benchmark\.CrossEngineBenchmark\.' 2>&1 \
  | grep -E '^(Benchmark|[A-Z][a-zA-Z]+Benchmark\.)'
```

### Key Rules

- **Use standard mode for routine evidence and `BENCHMARKS.md` updates.**
  Use `--long` for close, surprising, or especially important comparisons.
  Read the runner for current fork, warmup, and measurement settings; do not
  duplicate those settings in benchmark classes or assume old values.
- **Declared `noFork` workloads always use `-f 0`.** The generic collection
  runner derives this setting from the measurement profile.
- **Default benchmark collection includes both Java suites.**
  `./collect-benchmark-results.sh` collects SafeRE, JDK, RE2/J, and RE2-FFM
  results from SafeRE's suite, then SafeRE/JDK results from the external
  OpenJDK-derived suite. Use `./collect-benchmark-results.sh --cross-language`
  only when broader C++ RE2, PCRE2 JIT, Go `regexp`, Rust `regex`, and .NET
  non-backtracking context is explicitly needed.
- **OpenJDK-derived benchmarks stay external.** Their GPL-2.0-only repository
  must be checked out separately and must not be vendored or added to SafeRE's
  Maven modules. The collection script runs them as a separate result set
  against the current SafeRE snapshot. A request to run "our benchmarks" or the
  "full benchmarks" includes this external suite unless the user explicitly
  narrows the requested scope. Use it when broad matching, search, compilation,
  or rejection changes could affect its workloads.
- **A collection does not require continuous monitoring.** Launch long
  collections in a durable session with captured output. Verify that
  prerequisites and builds succeed and that the first actual benchmark starts,
  then stop polling unless the user explicitly asks for continuous oversight.
  Before analyzing the results, verify the final exit status and the expected
  artifacts for every selected suite. Do not describe a successfully started
  collection as completed.
- **Do not commit optimizations that do not improve benchmark results.**
  Every optimization must be validated with before/after benchmarks.
- **`benchmark-data.json` is the only checked-in workload definition.** Large
  UTF-8 input files may be checked in under `safere-benchmarks/data/`
  and referenced by a `file` recipe with a pinned SHA-256 in the JSON. Keep
  source and license attribution with data copied from other projects.
  Benchmark scripts materialize the definitions and data into a resolved
  manifest and exact UTF-8 input files before execution. Java, C++, Go, Rust,
  and other harnesses read only those generated artifacts. Edit the JSON file
  to change workload definitions; never hardcode values or generation logic in
  a harness.
- **Zero implicit benchmark syntax conversion.** Regex patterns and
  replacement templates remain Java-canonical workload data. When an engine
  needs different syntax, declare the engine's exact alternate beside the
  canonical value in `benchmark-data.json`, with a reason, and have the adapter
  select that profile or use the canonical value unchanged. Runners and
  adapters must not parse, rewrite, translate, escape, or otherwise infer
  engine-specific pattern or replacement syntax, including numbered or named
  group references and quoting rules. If the schema cannot yet express the
  required alternate, extend and validate the schema first; do not add a
  conversion helper in a harness.

### Summary Statistics

When updating `BENCHMARKS.md`, include the full benchmarked Git SHA and that
commit's date/time in UTC using `Z` notation. Do not add a separate benchmark
results timestamp unless explicitly requested.

The structure and workload groupings in `BENCHMARKS.md` must reflect the
current benchmark suite and the questions the report is intended to answer.
Do not preserve a fixed list of summary categories, benchmark methods, or
competitors after the suite or reporting goals have changed.

When reporting an aggregate comparison:

- Define its workload membership, parameter coverage, and weighting clearly.
- Include the benchmark families that materially affect the report's
  conclusions; do not silently omit important or inconvenient results.
- Keep semantically different groups separate when combining them would hide
  useful behavior. Label cross-runtime comparisons as context rather than
  controlled same-runtime comparisons.
- Compute the **geometric mean of speed ratios** rather than an arithmetic mean
  of ratios. Use `SafeRE time / competitor time`, so values below 1.0 mean
  SafeRE is faster.
- Compute each competitor aggregate over its actual pairwise shared membership
  when engine coverage differs. Report the row count and material exclusions;
  do not present a partial-coverage aggregate as an overall engine comparison.
- Report the raw geomean and a readable interpretation. For ratios below 1.0,
  use "N× faster." For ratios above 1.0, use a percentage such as "13% slower"
  or the unambiguous "takes 2.03× as long," not "1.13× slower." Explain when a
  small number of extreme cases materially influences the aggregate.

**Why geometric mean:** It is the only mean consistent under inversion
(geomean(A/B) = 1/geomean(B/A)), treats multiplicative improvements
symmetrically, and is the standard in systems benchmarking (SPEC, DaCapo,
Renaissance). Do not use arithmetic mean of ratios — it is biased by outliers
and inconsistent under inversion.

`BENCHMARKS.md` must be self-contained for checked-in benchmark claims. For a
published benchmark report, check in the reviewed collection under
`benchmark-results/published/<full-SafeRE-commit>/`, including complete raw
outputs, normalized results, the resolved plan, a publication README, and
SHA-256 checksums. Keep ordinary timestamped runs ignored. The report must link
prominently to the published artifacts, and its claims must be reproducible
from them; neither the report nor an ignored or machine-local artifact may be
the sole inspectable evidence.

When updating `BENCHMARKS.md`, update the external OpenJDK-derived benchmark
section from the same collection. Keep its results and aggregates separate from
SafeRE's native suite, and record the SafeRE commit, external benchmark
repository commit, pinned OpenJDK source commit, SafeRE version, and JDK
version. If the user explicitly narrows the collection to skip that suite,
identify the report as incomplete and remove stale external results rather than
retaining results from a different collection. Do not leave the previous
external results in place after publishing a new full collection.

### Writing About Benchmark Results

- **Use professional, neutral language.** Do not use terms like "crushes",
  "destroys", "demolishes", or other language that puts down other
  implementations. Every engine makes deliberate design tradeoffs.
- **State facts and ratios.** Write "SafeRE is 50× faster than RE2/J"
  rather than "SafeRE crushes RE2/J."
- **Explain supported causes.** Attribute performance gaps to specific design
  decisions only when workload inspection, implementation tracing, or profiling
  supports the explanation. Label inferences as such, and profile before
  proposing an optimization. Do not present speculation as a measured cause or
  imply that another implementation is poorly written.
- **Acknowledge tradeoffs.** When SafeRE is slower, explain what it gains
  in return (e.g., linear-time guarantees). When it's faster, note what
  the other engine optimizes for instead.

## Profiling

Use profiling to identify actual bottlenecks before implementing optimizations.
**Do not guess** — profile first, optimize second.

### async-profiler

Use `asprof` when async-profiler is available on PATH. Check availability in the
current environment; do not assume a particular installation path or version.
If it is missing, use built-in JFR or ask the project owner to install it.

**CPU flame graph** — identifies where CPU time is spent:

```bash
# Attach to a running JVM by PID
asprof collect -d 30 -e cpu -o flamegraph -f /tmp/cpu-flame.html <pid>
```

**Allocation profiling** — identifies where objects are allocated:

```bash
asprof collect -d 30 -e alloc -o flamegraph -f /tmp/alloc-flame.html <pid>
```

**Flat output** — top methods by sample count (quick text summary):

```bash
asprof collect -d 30 -e cpu -o flat -f /tmp/cpu-flat.txt <pid>
```

**Filtering to SafeRE code** — use `-I` to include only relevant frames:

```bash
asprof collect -d 30 -e cpu -o flat -I 'org.safere.*' -f /tmp/safere-cpu.txt <pid>
```

**Tips:**

- Always pass `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints` to the
  JVM for accurate line-level profiling. Without these flags, samples are biased
  toward safepoints.
- For profiling JMH benchmarks, use `-f 0` (no-fork mode) so async-profiler can
  attach to the same JVM. Fork mode spawns child JVMs that need separate
  attachment.
- Profile for at least 10–30 seconds to get statistically meaningful samples.
- Use `-t` (threads) to see per-thread breakdown.

### Java Flight Recorder (JFR)

JFR is built into the supported JDKs and can record allocation and execution
samples. The direct JAR commands below are diagnostic profiling runs, not
publishable benchmark comparisons. Build the benchmark JAR with the project
runner first, and use the runner's standard modes for before/after evidence.

**Record to file:**

```bash
java -XX:StartFlightRecording=duration=30s,filename=/tmp/recording.jfr \
  -jar safere-benchmarks/target/benchmarks.jar <BenchmarkClass> -f 0 -wi 1 -i 3 -w 1 -r 1
```

**Attach to running JVM:**

```bash
jcmd <pid> JFR.start name=profile duration=30s filename=/tmp/recording.jfr
```

**Analyze JFR files** — use `jfr` CLI tool for text summaries:

```bash
jfr summary /tmp/recording.jfr
jfr print --events jdk.ObjectAllocationInNewTLAB /tmp/recording.jfr | head -100
jfr print --events jdk.ExecutionSample /tmp/recording.jfr | head -100
```

**Tips:**

- async-profiler is preferred for CPU profiling (no safepoint bias).
- JFR `.jfr` files can also be opened in JDK Mission Control for visual
  analysis (not available on this machine, but files can be downloaded).
