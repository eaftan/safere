# SafeRE

A linear-time regular expression matching library for Java.

SafeRE is a port of [RE2](https://github.com/google/re2) to Java, with
significant performance optimization work to adapt to the JVM's performance
characteristics and approach parity with `java.util.regex`.  Unlike RE2, which
uses POSIX leftmost-longest semantics, SafeRE matches `java.util.regex`
semantics and provides a drop-in replacement for `java.util.regex.Pattern` and
`java.util.regex.Matcher`.

An earlier port of RE2 to Java exists as
[RE2/J](https://github.com/google/re2j).  RE2/J is valuable work, but it is
substantially slower than `java.util.regex` on common workloads and does not
provide a drop-in replacement API.  SafeRE addresses both of these gaps.

SafeRE **guarantees linear-time matching** regardless of the pattern or input.
It achieves this by using finite automata (DFA/NFA) instead of backtracking.
Patterns that require exponential time in `java.util.regex` — such as
`a?{25}a{25}` matched against `a` repeated 25 times — complete in microseconds
with SafeRE.

## Installation

SafeRE is available on [Maven Central](https://central.sonatype.com/artifact/org.safere/safere).

**Maven:**

```xml
<dependency>
  <groupId>org.safere</groupId>
  <artifactId>safere</artifactId>
  <version>0.10.0</version>
</dependency>
```

**Gradle (Kotlin DSL):**

```kotlin
implementation("org.safere:safere:0.10.0")
```

**Gradle (Groovy DSL):**

```groovy
implementation 'org.safere:safere:0.10.0'
```

## Quick Start

```java
import org.safere.Pattern;
import org.safere.Matcher;

// Compile a pattern (thread-safe, reusable)
Pattern p = Pattern.compile("(\\w+)@(\\w+\\.\\w+)");

// Match against input
Matcher m = p.matcher("contact user@example.com for info");
if (m.find()) {
    System.out.println(m.group());   // "user@example.com"
    System.out.println(m.group(1));  // "user"
    System.out.println(m.group(2));  // "example.com"
}
```

SafeRE is a drop-in replacement for `java.util.regex.Pattern` and
`java.util.regex.Matcher`. Just change your imports.

## Diagnostics

SafeRE can inspect a compiled pattern's static features and capabilities with
`Pattern.analysis()`, and can report the strategies actually used by matching and replacement
operations through a process-wide `SafeReMatchDiagnostics` listener.

See [SafeRE Diagnostics](DIAGNOSTICS.md) for examples, event semantics, thread-safe aggregation,
privacy guarantees, and performance guidance.

## Direct UTF-8 Input

Applications that already store text as UTF-8 can match it directly without
first decoding the entire input to a `String`. This is primarily intended for
JVM data systems, storage engines, network services, and parsing pipelines that
process byte-oriented text in hot loops. It can avoid an input-sized UTF-8 to
UTF-16 conversion, preserve zero-copy capture slicing, and write replacements
back to byte-oriented output. If an application already owns a `String`, the
regular `Pattern` and `Matcher` APIs remain the simpler choice.

For example:

```java
import static java.nio.charset.StandardCharsets.UTF_8;

import org.safere.Pattern;
import org.safere.Utf8Input;
import org.safere.Utf8Matcher;

byte[] bytes = "contact user@example.com for info".getBytes(UTF_8);
Utf8Input input = Utf8Input.validated(bytes);
Utf8Matcher matcher = Pattern.compile("(\\w+)@(\\w+\\.\\w+)").matcher(input);

if (matcher.find()) {
    // UTF-8 match coordinates are byte offsets relative to the input view.
    int addressStart = matcher.start();
    int addressEnd = matcher.end();
}
```

`Utf8Input.validated` performs strict UTF-8 validation. Callers that already
guarantee valid UTF-8 can use `Utf8Input.trusted` to avoid that validation pass.
The input is a borrowed view: its covered bytes must not be mutated while a
matcher is using them.

The UTF-8 API also supports capture bounds and byte-native replacement through
`Utf8Sink`. See [Direct UTF-8 Matching](UTF8.md) for the complete API,
ownership, coordinate, malformed-input, and replacement contracts.

## Development

SafeRE uses google-java-format through Spotless. To format Java sources, run:

```bash
mvn spotless:apply
```

CI checks formatting with:

```bash
mvn spotless:check
```

To have commits format Java sources automatically, enable the repo hooks once:

```bash
git config core.hooksPath .githooks
```

## Why SafeRE?

`java.util.regex` uses a backtracking NFA that can exhibit **exponential**
time complexity on certain patterns. This is a well-known class of
[ReDoS](https://en.wikipedia.org/wiki/ReDoS) vulnerabilities. SafeRE
eliminates this risk entirely.

| Pattern | SafeRE | RE2/J | RE2-FFM | JDK | SafeRE vs JDK |
|---|--:|--:|--:|--:|--:|
| `a?{10}a{10}` vs `aaaaaaaaaa` | 0.042 µs | 1.72 µs | 0.068 µs | 9.5 µs | **226×** |
| `a?{15}a{15}` vs `aaa...` (15) | 0.055 µs | 3.73 µs | 0.082 µs | 388 µs | **6,690×** |
| `a?{20}a{20}` vs `aaa...` (20) | 0.072 µs | 6.64 µs | 0.092 µs | 15,389 µs | **210,808×** |
| `a?{25}a{25}` vs `aaa...` (25) | 0.090 µs | 10.15 µs | 0.099 µs | *(hangs)* | ∞ |

SafeRE grows linearly and is 41–113× faster than RE2/J. The JDK grows
exponentially and hangs at n=25.

## Features

- **Drop-in API** — `Pattern` and `Matcher` are drop-in replacements for
  `java.util.regex`
- **Linear-time guarantee** — No input can cause catastrophic backtracking
- **Full Unicode** — Operates on Unicode code points, supports `\p{...}`
  properties, Unicode-aware case folding
- **Named captures** — Java-compatible `(?<name>...)` syntax
- **Direct UTF-8 input** — Matches borrowed UTF-8 byte-array views without
  materializing an input-sized `String`
- **Multi-pattern matching** — `PatternSet` matches multiple patterns
  simultaneously in a single pass
- **Five execution engines** — OnePass, DFA, BitState, NFA, and reverse DFA,
  automatically selected per query

## Comparison with RE2 Family

SafeRE is part of a family of linear-time regex libraries that share RE2's
core algorithms.  Here is how they compare:

| Feature | [RE2](https://github.com/google/re2) (C++) | [Go `regexp`](https://pkg.go.dev/regexp) | [RE2/J](https://github.com/google/re2j) | **SafeRE** |
|---|:---:|:---:|:---:|:---:|
| Language | C++ | Go | Java | Java |
| Linear-time guarantee | ✅ | ✅ | ✅ | ✅ |
| Full Unicode support | ✅ | ✅ | ✅ | ✅ |
| Submatch extraction | ✅ | ✅ | ✅ | ✅ |
| Named captures | ✅ | ✅ | ✅ | ✅ |
| DFA engine | ✅ | ❌ | ❌ | ✅ |
| NFA (Pike VM) engine | ✅ | ✅ | ✅ | ✅ |
| OnePass engine | ✅ | ✅ | ❌ | ✅ |
| BitState engine | ✅ | ✅ | ❌ | ✅ |
| Reverse DFA | ✅ | ❌ | ❌ | ✅ |
| Literal optimization | ✅ | ✅ | ✅ | ✅ |
| Multi-pattern matching | ✅ (`RE2::Set`) | ❌ | ❌ | ✅ (`PatternSet`) |
| Drop-in `java.util.regex` API | — | — | ❌ | ✅ |
| Java version | — | — | 8+ | 21+ |

## Supported Syntax

SafeRE supports most of the syntax from `java.util.regex`:

| Category | Syntax |
|---|---|
| Literals | `a`, `\n`, `\t`, `\x{1F600}`, `\Q...\E` |
| Character classes | `[abc]`, `[a-z]`, `[^0-9]`, `.` |
| Perl classes | `\d`, `\D`, `\s`, `\S`, `\w`, `\W` |
| Unicode properties | `\p{L}`, `\p{IsHan}`, `\P{Digit}`, `\p{Lower}` |
| Quantifiers | `*`, `+`, `?`, `{n}`, `{n,}`, `{n,m}` |
| Non-greedy | `*?`, `+?`, `??`, `{n,m}?` |
| Alternation | `a\|b` |
| Grouping | `(...)`, `(?:...)` |
| Named captures | `(?<name>...)` |
| Anchors | `^`, `$`, `\A`, `\z`, `\b`, `\B` |
| Flags | `(?i)`, `(?m)`, `(?s)`, `(?U)` |

### Not Supported

These features violate the linear-time guarantee and are **rejected at
compile time** with a clear error:

- **Backreferences** (`\1`, `\2`, ...) — require exponential time
- **Lookahead / Lookbehind** (`(?=...)`, `(?<=...)`, `(?!...)`, `(?<!...)`)
- **Possessive quantifiers over consuming operands** (`a*+`, `a++`, `a?+`)
- **Atomic groups** (`(?>...)`)

Additionally, the `CANON_EQ` flag is not supported.  This flag enables
matching based on Unicode canonical equivalence (e.g., treating a precomposed
character the same as its decomposed form).  It is rarely used and adds
significant implementation complexity.

The `Matcher.hitEnd()` and `Matcher.requireEnd()` APIs are not supported.
These methods expose details of the JDK backtracking engine's search order,
including which alternatives and quantified paths the engine tried before
stopping.  SafeRE's linear-time engines explore possible states in lockstep
instead, and exactly reproducing the JDK's observer state would require
simulating backtracking-style path priority in cases that are incompatible
with SafeRE's performance model.  Direct use of these methods appears rare;
they were primarily introduced for streaming-tokenizer use cases such as
`java.util.Scanner`.

## Semantic Compatibility with java.util.regex

SafeRE aims to match `java.util.regex` behavior exactly, except where doing so
would conflict with the linear-time guarantee or where observed JDK behavior
appears to be an implementation detail rather than a stable regex rule. Known
intentional differences are documented in
[Intentional Divergences from java.util.regex](INTENTIONAL_DIVERGENCES.md).

Both SafeRE and `java.util.regex` use **leftmost-first** alternation
semantics (the first alternate that matches wins), which differs from POSIX
leftmost-longest.  This means SafeRE is a drop-in replacement for
`java.util.regex` for alternation behavior.

### Unicode Version

SafeRE supports Unicode 17.0 for Unicode regex properties such as `\p{L}`,
`\p{IsHan}`, `\p{script=Latin}`, `\p{block=BasicLatin}`, and Unicode-aware
predefined classes under `UNICODE_CHARACTER_CLASS`. This Unicode data is
versioned with SafeRE and is independent of the JDK used to run the library, so
the same SafeRE release has stable Unicode property behavior across supported
JDKs.

The `java*` property family, such as `\p{javaLowerCase}` and
`\p{javaJavaIdentifierStart}`, continues to follow the running JDK's
`java.lang.Character` predicates because those properties are explicitly
defined by Java in terms of the runtime `Character` implementation.

## Flags

SafeRE supports the same flag constants as `java.util.regex.Pattern`:

| Flag | Value | Description |
|---|--:|---|
| `CASE_INSENSITIVE` | 2 | Case-insensitive matching |
| `MULTILINE` | 8 | `^` and `$` match at line boundaries |
| `DOTALL` | 32 | `.` matches line terminators |
| `UNICODE_CASE` | 64 | Unicode-aware case folding |
| `UNICODE_CHARACTER_CLASS` | 256 | Unicode-aware `\w`, `\d`, `\s` |
| `COMMENTS` | 4 | Permit whitespace and `#` comments |
| `LITERAL` | 16 | Treat pattern as a literal string |
| `UNIX_LINES` | 1 | Only `\n` is a line terminator |

```java
Pattern p = Pattern.compile("hello", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
```

## PatternSet: Multi-Pattern Matching

SafeRE includes `PatternSet`, a SafeRE-only feature that matches multiple
patterns simultaneously in a single pass (neither `java.util.regex` nor
RE2/J offers this):

```java
PatternSet.Builder builder = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
int id0 = builder.add("error.*timeout");
int id1 = builder.add("warning.*disk");
int id2 = builder.add("info.*startup");
PatternSet set = builder.compile();

List<Integer> matches = set.match("error: connection timeout");
// matches contains id0
```

## Migrating from java.util.regex

SafeRE is designed as a drop-in replacement. In most cases, you only need
to change your imports:

```java
// Before
import java.util.regex.Pattern;
import java.util.regex.Matcher;

// After
import org.safere.Pattern;
import org.safere.Matcher;
```

### Validating with safere-crosscheck

To verify that SafeRE behaves identically to `java.util.regex` in your
application, use the [safere-crosscheck](safere-crosscheck/) module. It
provides `Pattern` and `Matcher` classes that run both engines on every
operation and throw an exception if results diverge:

```java
// Crosscheck mode — just change the import
import org.safere.crosscheck.Pattern;
import org.safere.crosscheck.Matcher;
```

Every call is recorded in a trace. If a divergence is found, the exception
includes the full trace for easy bug reporting. See
[safere-crosscheck/README.md](safere-crosscheck/README.md) for details.

### Fuzz Testing

SafeRE also has local Jazzer fuzz targets in
[safere-fuzz](safere-fuzz/). They use `safere-crosscheck` as the oracle and can
run either as regression tests over checked-in seeds or as coverage-guided
fuzzers with `JAZZER_FUZZ=1`.

For parser dialect work, SafeRE also has an explicit long-running
character-class syntax sweep. It lives outside the ordinary JUnit path because
it enumerates a large generated matrix against `java.util.regex`; run it when
working on character-class parsing:

```bash
./run-exhaustive-sweep.sh CharacterClassDivergenceSweep \
  --output-dir=target/exhaustive-reports/character-class-sweep-full
```

Use generated-case ranges when debugging a specific matrix region:

```bash
./run-exhaustive-sweep.sh CharacterClassDivergenceSweep --range=:1000000 \
  --output-dir=target/exhaustive-reports/character-class-sweep-smoke
```

See [TESTING.md](TESTING.md) for the full testing workflow.

### What works unchanged

- `Pattern.compile()`, `Pattern.matches()`, `Pattern.quote()`
- `Matcher.matches()`, `lookingAt()`, `find()`, `group()`, `start()`, `end()`
- `replaceFirst()`, `replaceAll()`, `appendReplacement()`, `appendTail()`
- `split()`, `asPredicate()`, `asMatchPredicate()`
- All flags: `CASE_INSENSITIVE`, `MULTILINE`, `DOTALL`, `UNICODE_CASE`, etc.
- Replacement strings: `$1`, `${name}`, `\\`, `\$`

### What to watch for

1. **Backreferences** (`\1`, `\2`) — not supported; will throw
   `PatternSyntaxException` at compile time.
2. **Lookahead / lookbehind** (`(?=...)`, `(?<=...)`) — not supported.
3. **Possessive quantifiers over consuming operands** (`a*+`, `a++`) — not
   supported.
4. **`Matcher.hitEnd()` and `Matcher.requireEnd()`** are not available.
5. **Named captures** support Java-compatible `(?<name>...)` syntax and
   Python-style `(?P<name>...)` syntax.

See [Semantic Compatibility](#semantic-compatibility-with-javautilregex) for
minor edge-case differences.

## Architecture

The processing pipeline mirrors RE2:

```
Pattern string → Parse → Simplify → Compile → Execute
                  ↓         ↓          ↓          ↓
               Regexp     Regexp      Prog     Engine
               (AST)    (simpler)  (bytecode)  (match)
```

SafeRE automatically selects the fastest engine for each query:

| Engine | When Used | Capabilities |
|---|---|---|
| **Literal** | Pattern is a plain string | `String.indexOf()` — fastest |
| **OnePass** | Pattern is unambiguous | Single-pass with captures |
| **DFA** | General patterns | Fast boolean match, no captures |
| **Reverse DFA** | Multi-find on long text | Bounds match range for NFA |
| **BitState** | Small text × program | Captures via backtracking with visited bitmap |
| **NFA** | Fallback | Full Pike VM, handles everything |

For `find()` on long texts, SafeRE uses a three-DFA sandwich (like RE2):
1. Forward DFA confirms a match exists and finds the earliest match end
2. Reverse DFA scans backward to find the match start
3. Anchored forward DFA finds the actual match end
4. NFA extracts captures on just the bounded `[start, end]` range

For a detailed architecture walkthrough, see [DESIGN.md](DESIGN.md).

## Building

Requires Java 21+ and Maven.

```bash
# Build and install (library + benchmarks)
mvn install

# Run tests
mvn test -pl safere

# Generate Javadoc
mvn javadoc:javadoc -pl safere
```

## Benchmarks

SafeRE includes a [JMH](https://github.com/openjdk/jmh) benchmark suite in the
`safere-benchmarks` module, comparing SafeRE against `java.util.regex` (JDK),
[RE2/J](https://github.com/google/re2j), RE2-FFM (C++ RE2 via Java
[FFM API](https://openjdk.org/jeps/454)), C++ RE2, and Go `regexp`.
The suite includes focused microbenchmarks, data-driven application workloads,
scaling/pathological cases, replacement, memory, and `PatternSet` benchmarks.
Benchmark recipes and configuration live in
`safere-benchmarks/benchmark-data.json`. Before a benchmark starts, its runner
materializes that file into a resolved manifest and exact UTF-8 inputs under
`safere-benchmarks/target/benchmark-corpus`. Java, C++, and Go consume only
those generated artifacts instead of independently interpreting input recipes.
See
[`safere-benchmarks/BENCHMARK_INPUTS.md`](safere-benchmarks/BENCHMARK_INPUTS.md)
for details.

Java workload additions are data-only: declarations select generic operations,
engine capabilities, inputs, timing modes, and result consumption. See
[`safere-benchmarks/DECLARATIVE_BENCHMARK_PLAN.md`](safere-benchmarks/DECLARATIVE_BENCHMARK_PLAN.md)
and
[`safere-benchmarks/DECLARATIVE_COLLECTION.md`](safere-benchmarks/DECLARATIVE_COLLECTION.md).

SafeRE also maintains a separate
[OpenJDK-derived regex benchmark suite](https://github.com/eaftan/safere-openjdk-regex-benchmarks).
It compares SafeRE and `java.util.regex` on compatible workloads adapted from
OpenJDK's regex microbenchmarks. That suite is GPL-2.0-only, so its source is
not vendored here or included in the `safere-benchmarks` Maven module.

### Benchmark Collection

To collect a full set of benchmark data for updating
[BENCHMARKS.md](BENCHMARKS.md), run the collection script from the repository
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
C++ RE2 and Go `regexp`:

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

When the run finishes, hand off the result directory to the agent that will
update `BENCHMARKS.md`:

```text
benchmark-results/latest
```

The important files in that directory are:

```text
jmh-output.txt
declared-report-plan.json
merged-tables.md
java-memory.txt
java-pattern-memory.txt
```

Cross-language runs also include:

```text
cpp-results.jsonl
go-results.jsonl
cross-runtime-tables.md
```

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
# Java benchmarks (throughput)
./run-java-benchmarks.sh                        # standard benchmarks
./run-java-benchmarks.sh --declared             # all declared execution profiles
./run-java-benchmarks.sh --long --declared

# Java memory profiling (allocation rates via JMH GC profiler)
./run-java-memory-benchmarks.sh --declared
```

Use `BenchmarkCollectionPlan trials` to discover trial IDs by mode, timing
unit, workload prefix, or execution variant. Benchmark regexes select generic
JMH entry points, and arguments after `--` can select a specific trial or pass
other JMH options. See
[`safere-benchmarks/CROSS_ENGINE_EXECUTION.md`](safere-benchmarks/CROSS_ENGINE_EXECUTION.md)
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

### C++ RE2 and Go Benchmarks

The benchmark suite includes C++ RE2 and Go `regexp` harnesses for
cross-language comparison. Prerequisites: CMake ≥ 3.14 + C++17 compiler
(for C++), Go ≥ 1.21 (for Go). Dependencies are fetched automatically.

```bash
# C++ RE2 benchmarks
./run-cpp-benchmarks.sh                    # all C++ benchmarks
./run-cpp-benchmarks.sh Regex Application  # specific benchmark groups

# Go regexp benchmarks
./run-go-benchmarks.sh                     # all Go benchmarks
./run-go-benchmarks.sh Regex Application   # specific benchmark groups
```

### Comparing Results Manually

A comparison script turns JMH output into side-by-side markdown:

```bash
python3 safere-benchmarks/scripts/compare-benchmarks.py \
  --jmh jmh-output.txt
```

Add C++ and Go JSON-lines files when comparing cross-language results:

```bash
python3 safere-benchmarks/scripts/compare-benchmarks.py \
  --jmh jmh-output.txt \
  --json cpp-results.jsonl go-results.jsonl \
  --engines safere,jdk,re2j,re2_ffm,re2_cpp,go
```

To distinguish absent results from declared exclusions, include the report plan:

```bash
python3 safere-benchmarks/scripts/compare-benchmarks.py \
  --jmh jmh-output.txt \
  --declared-plan declared-report-plan.json
```

### Latest Results

See [BENCHMARKS.md](BENCHMARKS.md) for full results. Highlights:

| Benchmark | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go | vs JDK |
|---|--:|--:|--:|--:|--:|--:|---|
| Literal match | 14 ns | 13 ns | 127 ns | 59 ns | 40 ns | 76 ns | 1.1× slower |
| Alternation find | 226 ns | 529 ns | 4,437 ns | 656 ns | 19 ns | 1,699 ns | **2.3× faster** |
| Capture groups (10) | 214 ns | 247 ns | 1,485 ns | 765 ns | 356 ns | 578 ns | **1.2× faster** |
| Hard pattern (1 MiB) | 0.04 µs | 44,845 µs | 38,996 µs | 350 µs | 0.04 µs | 25,453 µs | **1.1M× faster** |
| Pathological (n=20) | 0.09 µs | 17,670 µs | 6.9 µs | 0.10 µs | 0.07 µs | 3.0 µs | **196,000× faster** |
| Literal replaceFirst | 55 ns | 41 ns | 149 ns | 217 ns | 98 ns | 583 ns | 1.3× slower |

**Summary (geometric mean of speed ratios):**

| vs | Core | Application | Real-world matrix | Pathological/scaling |
|---|---|---|---|---|
| JDK | 1.09× slower | approximately even | **1.89× faster** | **13,500× faster** |
| RE2/J | **11.7× faster** | **7.81× faster** | **10.6× faster** | **2,820× faster** |
| RE2-FFM | **2.19× faster** | **1.66× faster** | **2.54× faster** | **23.2× faster** |
| C++ RE2 | 2.32× slower | 1.32× slower | 1.44× slower | 1.11× slower |
| Go `regexp` | **3.99× faster** | **2.01× faster** | **6.17× faster** | **1,520× faster** |

## License

This project is a Java port of [RE2](https://github.com/google/re2).
It also incorporates code from [RE2/J](https://github.com/google/re2j),
a Java port of Go's `regexp` package.

RE2 is Copyright (c) 2009 The RE2 Authors. All rights reserved.

RE2/J is Copyright (c) 2009 The Go Authors. All rights reserved.

This project contains code derived from both RE2 and RE2/J and is licensed
under the BSD 3-Clause License, consistent with both original projects.

Modifications and Java port: Copyright (c) 2026 Eddie Aftandilian.

See [LICENSE](LICENSE) for details.

## Acknowledgments

This work builds directly on the design and implementation of RE2 by
the RE2 authors, and on RE2/J by the Go authors.

- [RE2](https://github.com/google/re2) — the C++ library whose design and
  algorithms SafeRE is based on
- [Go `regexp`](https://pkg.go.dev/regexp) — the Go standard library
  implementation of RE2
- [RE2/J](https://github.com/google/re2j) — an earlier port of RE2 to Java.
  SafeRE's parser, Java API layer, and portions of the test suite are
  derived from RE2/J (see [TESTING.md](TESTING.md))
- Russ Cox's [article series on regular expression matching](https://swtch.com/~rsc/regexp/regexp1.html)
  — explains the theory behind RE2's approach
