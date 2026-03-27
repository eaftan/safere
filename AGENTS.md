# SafeRE — Agent Guidelines

## Project Overview

SafeRE is a linear-time regular expression matching library for Java, modeled on
Russ Cox's RE2. The C++ RE2 reference implementation is in `re2-reference/`.

- **Package**: `dev.eaftan.safere`
- **Java version**: 21 (LTS)
- **Build**: Maven
- **Tests**: JUnit 6 (6.0.3)
- **Coverage**: JaCoCo

## License

MIT License. All source files must include this header:

```java
// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.
```

## Code Style

Follow the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html):

- 2-space indentation, no tabs
- 100-character line limit
- Braces on same line (`if (...) {`)
- One class per file (except private inner classes)
- `static` imports grouped separately, sorted alphabetically
- Non-static imports sorted alphabetically
- No wildcard imports
- Use `@Override` on all overriding methods
- Write Javadoc for all public and protected members
- Use `{@code ...}` in Javadoc for code fragments
- Fields: `camelCase`; constants: `UPPER_SNAKE_CASE`; classes: `PascalCase`

## Project Structure

```
src/main/java/dev/eaftan/safere/   # Library source
src/test/java/dev/eaftan/safere/   # Tests
re2-reference/                     # C++ RE2 reference (read-only)
re2j-reference/                    # RE2/J (Java) reference (read-only) 
```

## Architecture

The processing pipeline mirrors RE2:

```
Pattern string → Parse → Simplify → Compile → Execute
                  ↓         ↓          ↓          ↓
               Regexp     Regexp      Prog     NFA/DFA
               (AST)    (simpler)  (bytecode)   (match)
```

Key internal classes:
- `Regexp` — AST node (operator + children)
- `CharClass` — sorted Unicode code point ranges
- `Prog` / `Inst` — compiled bytecode program
- `Parser` — recursive-descent regex parser
- `Compiler` — Thompson NFA construction
- `NFA` — Pike VM execution engine
- `DFA` — lazy DFA execution engine

Public API (drop-in for `java.util.regex`):
- `Pattern` — compiled regex (replaces `java.util.regex.Pattern`)
- `Matcher` — match state (replaces `java.util.regex.Matcher`)

## Progress Tracking

- When a phase is completed, mark it with ✅ in `PLAN.md`
  (e.g., `### Phase 1: Project Skeleton & Unicode Tables ✅`)

## Testing

- Use JUnit 6 (6.0.3) with `org.junit.jupiter.api.*` imports
- Use AssertJ (`org.assertj.core.api.Assertions.*`) for all assertions
- Test class naming: `FooTest.java` for `Foo.java`
- Use `@Test`, `@ParameterizedTest`, `@DisplayName` as appropriate
- Aim for high coverage; JaCoCo is configured in the build
- Port test cases from RE2's C++ test suite where applicable

## GitHub Issues

- Do not close an issue until **all** items in it are resolved. If only some
  items are done, post a progress comment instead.
- When referencing an issue in a commit message, use `Fixes #N` only if the
  commit fully resolves the issue. Otherwise use `Refs #N` or `Part of #N`.

## Key Constraints

- **Linear time**: No backreferences, no lookahead/lookbehind, no possessive
  quantifiers. These features violate linear-time guarantees and must be
  rejected at parse time with a clear error.
- **Unicode**: Operate on Unicode code points (not UTF-16 code units). Use
  `Character.codePointAt()` and related methods.
- **Stack safety**: Use iterative tree walkers (Walker pattern from RE2), not
  recursion, for Regexp tree traversal. Deeply nested regexes must not cause
  `StackOverflowError`.
- **No `\C`**: RE2's "match any byte" is not applicable to Java strings.
- **No `@SuppressWarnings`**: Do not add `@SuppressWarnings` annotations
  without explicit approval from the project owner. Fix the underlying
  issue instead.
- **Avoid `Object` arrays**: Use typed collections (`List<T>`, etc.) instead
  of `Object[]` to maintain type safety. Primitive arrays (e.g., `int[]`)
  are fine for performance reasons.

## Benchmarking (Phase 10)

- Use JMH (Java Microbenchmark Harness)
- Compare against `java.util.regex` on the same patterns/inputs
- Compare against C++ RE2 (via subprocess invocation)
- Include pathological patterns that demonstrate exponential blowup in
  backtracking engines (e.g., `a?{n}a{n}` matched against `a{n}`)
- **Do not commit performance optimizations that do not improve benchmark
  results.** Every optimization must be validated with before/after
  benchmarks, and only committed if there is a measurable improvement.
- **Always use `./run-java-benchmarks.sh`** to run benchmarks. This script
  runs `mvn install` to build a shaded (fat) JAR, then runs it with
  `java -jar`. This is required for JMH fork mode — forked JVMs need a
  self-contained classpath. Do NOT use `mvn exec:java`, which breaks fork
  mode because the forked child cannot find JMH classes.
- **Use JMH defaults for results that go into BENCHMARKS.md.**
  The script passes no JMH flags by default, letting JMH use its
  built-in defaults (5 forks, 5 warmup × 10s, 5 measurement × 10s).
  This produces reliable, publishable numbers but takes ~8 minutes per
  benchmark method. **Do NOT set `JMH_OPTS` when generating data for
  BENCHMARKS.md — run the script with no environment overrides.**
  Only use `JMH_OPTS` for quick development iteration (validating an
  optimization before committing). These two use cases require
  different commands:

```bash
# BENCHMARKS.md updates — NO JMH_OPTS, full JMH defaults
./run-java-benchmarks.sh RegexBenchmark

# Quick development iteration ONLY — fast but NOT for BENCHMARKS.md
JMH_OPTS="-f 0 -wi 1 -i 3 -w 1 -r 1" ./run-java-benchmarks.sh RegexBenchmark
```

- **Run benchmarks in batches, not all at once.** Running the full suite
  takes a very long time. Run 2–3 benchmark classes per invocation and
  collect results incrementally. For example:

```bash
./run-java-benchmarks.sh RegexBenchmark CompileBenchmark
./run-java-benchmarks.sh SearchScalingBenchmark CaptureScalingBenchmark
./run-java-benchmarks.sh HttpBenchmark ReplaceBenchmark FanoutBenchmark
./run-java-benchmarks.sh PathologicalBenchmark PathologicalComparisonBenchmark
```

- **NEVER run benchmarks in parallel.** All benchmark runs (Java, C++, Go)
  must run sequentially, one at a time. Parallel runs compete for CPU,
  cache, and memory bandwidth, producing inaccurate results.

- **Extract summary tables from JMH output** using grep. JMH prints
  verbose per-iteration output; the summary table at the end has one
  header line starting with `Benchmark` and data lines starting with
  the class name:

```bash
./run-java-benchmarks.sh RegexBenchmark 2>&1 \
  | grep -E '^(Benchmark|[A-Z][a-zA-Z]+Benchmark\.)'
```

### Benchmarking Best Practices

- **Use fork mode for Java benchmarks.** The runner script uses
  `java -jar` on the shaded JAR, which supports JMH fork mode. Each
  fork starts a fresh JVM, eliminating JIT profile pollution between
  benchmarks. Never use `mvn exec:java` — it breaks fork mode.
- **Never run benchmarks in parallel.** CPU, cache, and memory bandwidth
  contention invalidates all measurements. Run one benchmark suite at a
  time (Java, then C++, then Go).
- **Run benchmarks in batches.** The full Java suite with default settings
  takes many hours. Run 2–3 benchmark classes per invocation to get
  incremental results.
- **Use JMH defaults for publishable data.** Don't override `-f`, `-wi`,
  `-i`, `-w`, or `-r` when generating data for BENCHMARKS.md. JMH's
  defaults (5 forks, 5 warmup × 10s, 5 measurement × 10s) are chosen
  for statistical rigor.
- **Use reduced settings only for development.** Quick spot-checks during
  development can use `JMH_OPTS="-f 0 -wi 1 -i 3 -w 1 -r 1"`. Never
  commit these results to BENCHMARKS.md.
- **Warmup matters for different reasons across languages:**
  - Java: JIT compilation (C1 → C2 tiered compilation) needs time to
    reach steady state. JMH's 50s warmup per fork handles this.
  - C++/Go: Only CPU cache and branch predictor priming needed (~4s).
    The native harnesses use 2 × 2s warmup iterations.
- **More measurement iterations compensate for fewer forks.** C++ and Go
  run 10 measurement iterations (vs Java's 5 per fork) since they don't
  have fork-level variance from JIT non-determinism.
- **All harnesses share `benchmark-data.json`.** This ensures identical
  patterns, inputs, and parameters across Java, C++, and Go. Edit the
  JSON file to change workloads; never hardcode values in the harness.

### Writing About Benchmark Results

- **Use professional, neutral language.** Do not use terms like "crushes",
  "destroys", "demolishes", or other language that puts down other
  implementations. Every engine makes deliberate design tradeoffs.
- **State facts and ratios.** Write "SafeRE is 50× faster than RE2/J"
  rather than "SafeRE crushes RE2/J."
- **Explain *why* differences exist.** Attribute performance gaps to
  specific design decisions (e.g., "RE2/J lacks a DFA engine" or "JDK
  defers compilation work to match time") rather than implying one
  implementation is poorly written.
- **Acknowledge tradeoffs.** When SafeRE is slower, explain what it gains
  in return (e.g., linear-time guarantees). When it's faster, note what
  the other engine optimizes for instead.

## Profiling

Use profiling to identify actual bottlenecks before implementing optimizations.
**Do not guess** — profile first, optimize second.

### async-profiler

[async-profiler](https://github.com/jvm-profiling-tools/async-profiler) 4.3 is
installed and on the PATH (`asprof`). It supports CPU, allocation, and lock
profiling with minimal overhead and no safepoint bias.

**CPU flame graph** — identifies where CPU time is spent:

```bash
# Attach to a running JVM by PID
asprof collect -d 30 -e cpu -o flamegraph -f /tmp/cpu-flame.html <pid>

# Or use the JVM agent to profile from start (no PID needed):
java -agentpath:/home/eaftan/async-profiler-4.3-linux-x64/lib/libasyncProfiler.so=start,event=cpu,file=/tmp/cpu-flame.html \
  -jar safere-benchmarks/target/safere-benchmarks.jar <BenchmarkClass> -f 0 -wi 1 -i 3 -w 1 -r 1
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
asprof collect -d 30 -e cpu -o flat -I 'dev.eaftan.safere.*' -f /tmp/safere-cpu.txt <pid>
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

JFR is built into OpenJDK 25 and always available. It's best for allocation
profiling and event-based analysis.

**Record to file:**

```bash
java -XX:StartFlightRecording=duration=30s,filename=/tmp/recording.jfr \
  -jar safere-benchmarks/target/safere-benchmarks.jar <BenchmarkClass> -f 0 -wi 1 -i 3 -w 1 -r 1
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
- JFR has lower overhead than async-profiler for allocation tracking.
- async-profiler is preferred for CPU profiling (no safepoint bias).
- JFR `.jfr` files can also be opened in JDK Mission Control for visual
  analysis (not available on this machine, but files can be downloaded).
