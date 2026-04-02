# SafeRE — Agent Guidelines

## Project Overview

SafeRE is a linear-time regular expression matching library for Java, modeled on
Russ Cox's RE2. The C++ RE2 reference implementation is in `re2-reference/`.
The RE2/J (Java) reference is in `re2j-reference/`.

- **Package**: `dev.eaftan.safere`
- **Java version**: 21 (LTS) — built and tested with OpenJDK 25
- **Build**: Maven (`mvn`)
- **Tests**: JUnit 6 (6.0.3), AssertJ
- **Coverage**: JaCoCo
- **Benchmarks**: JMH (Java Microbenchmark Harness)

## License

BSD 3-Clause License (same as RE2). All source files must include this header:

```java
// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
```

## Build & Test

```bash
# Run tests (quiet output)
mvn -pl safere test -q

# Install to local repo (needed before benchmarks)
mvn install -DskipTests -q

# Run benchmarks (see Benchmarking section below)
./run-java-benchmarks.sh RegexBenchmark
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
safere/src/main/java/dev/eaftan/safere/   # Library source
safere/src/test/java/dev/eaftan/safere/   # Tests
safere-benchmarks/                         # JMH benchmark suite
re2-reference/                             # C++ RE2 reference (read-only)
re2j-reference/                            # RE2/J (Java) reference (read-only)
```

## Architecture

The processing pipeline mirrors RE2:

```
Pattern string → Parse → Simplify → Compile → Execute
                  ↓         ↓          ↓          ↓
               Regexp     Regexp      Prog     NFA/DFA
               (AST)    (simpler)  (bytecode)   (match)
```

### Key Internal Classes

- `Parser` — stack-based operator-precedence regex parser → `Regexp` AST
- `Simplifier` — AST simplification (character class folding, etc.)
- `Compiler` — Thompson NFA construction → `Prog` / `Inst` bytecode
- `Regexp` — AST node (operator + children)
- `CharClass` / `CharClassBuilder` — sorted Unicode code point ranges
- `Prog` / `Inst` — compiled bytecode program

### Execution Engines (in priority order)

1. **Fast paths** — literal `String.indexOf()`, character-class bitmap scan
2. **OnePass** — deterministic single-pass matcher for unambiguous patterns
3. **DFA** — lazy DFA with cached states (forward, reverse, anchored)
4. **BitState** — NFA with visited-state bitmap for small texts
5. **NFA** — Pike VM for arbitrarily large texts

Engine selection in `Matcher.doFind()`:
1. Literal fast path → `String.indexOf()`
2. Anchored OnePass → direct OnePass if `^` and OnePass-eligible
3. Prefix acceleration → skip to first literal/charclass prefix match
4. OnePass for small text → `searchUnanchored()` if text ≤ 256 chars
5. DFA sandwich → forward DFA, reverse DFA, anchored DFA for match bounds
6. BitState/NFA fallback → full search with capture extraction

### Public API

Drop-in replacements for `java.util.regex`:
- `Pattern` — compiled regex (replaces `java.util.regex.Pattern`)
- `Matcher` — match state (replaces `java.util.regex.Matcher`)
- `PatternSet` — multi-pattern matching (SafeRE-only feature)

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
- **Do not include test counts in documentation** (DESIGN.md, TESTING.md,
  etc.) — counts change frequently as tests are added and will go stale.

## External Validation

When swapping SafeRE into external projects for validation (#26), **fix every
bug you find immediately**. Do not just report it and move on. The workflow is:

1. Identify the failure and root-cause it.
2. Write a regression test in SafeRE that reproduces the bug.
3. Fix the bug in SafeRE.
4. Run the SafeRE test suite to confirm the fix and no regressions.
5. Re-install (`mvn install -DskipTests -q`) and re-run the external
   project's failing tests to confirm they now pass.
6. Commit the fix.
7. Then continue with the rest of the validation.

## Pull Requests

- **Never push to a PR (or create one) without first verifying locally that
  all tests pass** (`mvn -pl safere test`). CI failures waste time and block
  merges.
- **Update existing PRs — do not close and reopen.** Push commits (or
  force-push if rebasing) to the existing branch. Closing and reopening PRs
  loses review context and clutters the issue tracker.
- Whenever you create a PR, enable auto-merge on it:
  `gh pr merge <number> --auto --squash`
- Do not push directly to `main`. Always create a branch and open a PR.
- After creating a PR, monitor CI and ensure all checks pass. If CI fails,
  debug and fix the problem locally, then push the fix.
- When polling CI status, wait at least 2 minutes between checks. CI jobs
  take minutes to complete — tight-looping wastes tokens for no benefit.

## GitHub Issues

- Do not close an issue until **all** items in it are resolved. If only some
  items are done, post a progress comment instead.
- When referencing an issue in a commit message, use `Fixes #N` only if the
  commit fully resolves the issue. Otherwise use `Refs #N` or `Part of #N`.

## Bug Fixing Philosophy

- **Make principled fixes.** Understand the root cause before changing code.
  Do not add special cases, flags, or if-statements that paper over symptoms.
  A correct fix addresses the underlying design flaw — if a DFA cache key is
  missing a dimension, fix the cache key; don't add a post-hoc correction.
- **Verify the invariant, not just the test.** After a fix, articulate *why*
  it is correct in general, not just why it makes a specific test pass.  If
  you can construct a new input that would still break, the fix is wrong.
- **Prefer fewer moving parts.** A fix that removes a special case is better
  than one that adds a new special case.  Simpler code has fewer bugs.

## Key Constraints

- **JDK compatibility**: SafeRE aims to be a drop-in replacement for
  `java.util.regex`. Match JDK behavior unless (a) the difference is
  fundamental to SafeRE's approach (e.g., linear-time guarantees require
  rejecting backreferences) or (b) the JDK's behavior is likely a bug
  (e.g., JDK is internally inconsistent). When diverging, document the
  reason.
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
- **No installing software**: Never install packages, libraries, or tools
  on the machine. If something is missing, ask the project owner to install
  it.
- **Not a clean-room port**: SafeRE is a port of RE2, using the C++ RE2
  source as a reference. Never describe it as a "clean-room" port.
- **No personal names in documentation**: Refer to projects (RE2, RE2/J)
  rather than individuals. Do not mention people's names in DESIGN.md,
  README.md, TESTING.md, or other documentation.
- **Regression tests for bugs**: Any time a bug is found, a regression test
  must be added that fails without the fix and passes with it.

## Benchmarking

### Running Benchmarks

**Always use `./run-java-benchmarks.sh`** to run benchmarks. This script
runs `mvn install` to build a shaded (fat) JAR, then runs it with
`java -jar`. This is required for JMH fork mode — forked JVMs need a
self-contained classpath. Do NOT use `mvn exec:java`, which breaks fork
mode because the forked child cannot find JMH classes.

```bash
# BENCHMARKS.md updates — NO JMH_OPTS, full JMH defaults
./run-java-benchmarks.sh RegexBenchmark

# Quick development iteration ONLY — fast but NOT for BENCHMARKS.md
JMH_OPTS="-f 0 -wi 1 -i 3 -w 1 -r 1" ./run-java-benchmarks.sh RegexBenchmark
```

**Run benchmarks in batches, not all at once.** Run 2–3 benchmark classes
per invocation and collect results incrementally:

```bash
./run-java-benchmarks.sh RegexBenchmark CompileBenchmark
./run-java-benchmarks.sh SearchScalingBenchmark CaptureScalingBenchmark
./run-java-benchmarks.sh HttpBenchmark ReplaceBenchmark FanoutBenchmark
./run-java-benchmarks.sh PathologicalBenchmark PathologicalComparisonBenchmark
```

**Extract summary tables from JMH output** using grep:

```bash
./run-java-benchmarks.sh RegexBenchmark 2>&1 \
  | grep -E '^(Benchmark|[A-Z][a-zA-Z]+Benchmark\.)'
```

### Key Rules

- **NEVER set `JMH_OPTS` when generating data for BENCHMARKS.md.** Use full
  JMH defaults (5 forks, 5 warmup × 10s, 5 measurement × 10s). `JMH_OPTS`
  is ONLY for quick development iteration.
- **NEVER run benchmarks in parallel.** All benchmark runs (Java, C++, Go)
  must run sequentially, one at a time. Parallel runs compete for CPU,
  cache, and memory bandwidth, producing inaccurate results.
- **Do not commit optimizations that do not improve benchmark results.**
  Every optimization must be validated with before/after benchmarks.
- **Use fork mode for publishable data.** Each fork starts a fresh JVM,
  eliminating JIT profile pollution. Use `-f 0` only for quick spot-checks
  during development.
- **All harnesses share `benchmark-data.json`.** This ensures identical
  patterns, inputs, and parameters across Java, C++, and Go. Edit the
  JSON file to change workloads; never hardcode values in the harness.

### Summary Statistics

When reporting benchmark results in BENCHMARKS.md, always compute and report
**geometric mean of the speed ratios** (SafeRE time / competitor time) as the
single summary statistic. Report two geomeans, against both JDK and RE2/J:

1. **Core workloads geomean** — includes: literalMatch, emailFind, findInText,
   alternationFind, charClassMatch, captureGroups, pigLatinReplace, httpFull
   (and any future "everyday usage" benchmarks). This answers: "Is SafeRE
   competitive for normal use?"
2. **Pathological/scaling geomean** — includes: pathological, searchHardFail,
   and other benchmarks that demonstrate linear-time guarantees or scaling
   behavior. This answers: "Does the linear-time guarantee matter?"

**Why geometric mean:** It is the only mean consistent under inversion
(geomean(A/B) = 1/geomean(B/A)), treats multiplicative improvements
symmetrically, and is the standard in systems benchmarking (SPEC, DaCapo,
Renaissance). Do not use arithmetic mean of ratios — it is biased by outliers
and inconsistent under inversion.

**Ratio convention:** Express as `SafeRE / competitor` so values < 1.0 mean
SafeRE is faster. For readability, also express as "SafeRE is N× faster" or
"SafeRE is N× slower" alongside the raw geomean.

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
  -jar safere-benchmarks/target/benchmarks.jar <BenchmarkClass> -f 0 -wi 1 -i 3 -w 1 -r 1
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
- JFR has lower overhead than async-profiler for allocation tracking.
- async-profiler is preferred for CPU profiling (no safepoint bias).
- JFR `.jfr` files can also be opened in JDK Mission Control for visual
  analysis (not available on this machine, but files can be downloaded).
