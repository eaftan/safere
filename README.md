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
  <version>0.1.0</version>
</dependency>
```

**Gradle (Kotlin DSL):**

```kotlin
implementation("org.safere:safere:0.1.0")
```

**Gradle (Groovy DSL):**

```groovy
implementation 'org.safere:safere:0.1.0'
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
- **Named captures** — `(?P<name>...)` syntax
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

SafeRE supports most of the syntax from `java.util.regex` and RE2:

| Category | Syntax |
|---|---|
| Literals | `a`, `\n`, `\t`, `\x{1F600}`, `\Q...\E` |
| Character classes | `[abc]`, `[a-z]`, `[^0-9]`, `.` |
| Perl classes | `\d`, `\D`, `\s`, `\S`, `\w`, `\W` |
| Unicode properties | `\p{L}`, `\p{Han}`, `\P{Digit}`, `\p{Greek}` |
| POSIX classes | `[:alpha:]`, `[:digit:]`, `[:space:]` (inside `[...]`) |
| Quantifiers | `*`, `+`, `?`, `{n}`, `{n,}`, `{n,m}` |
| Non-greedy | `*?`, `+?`, `??`, `{n,m}?` |
| Alternation | `a\|b` |
| Grouping | `(...)`, `(?:...)` |
| Named captures | `(?P<name>...)` |
| Anchors | `^`, `$`, `\A`, `\z`, `\b`, `\B` |
| Flags | `(?i)`, `(?m)`, `(?s)`, `(?U)` |

### Not Supported

These features violate the linear-time guarantee and are **rejected at
compile time** with a clear error:

- **Backreferences** (`\1`, `\2`, ...) — require exponential time
- **Lookahead / Lookbehind** (`(?=...)`, `(?<=...)`, `(?!...)`, `(?<!...)`)
- **Possessive quantifiers** (`*+`, `++`, `?+`)
- **Atomic groups** (`(?>...)`)

Additionally, the `CANON_EQ` flag is not supported.  This flag enables
matching based on Unicode canonical equivalence (e.g., treating a precomposed
character the same as its decomposed form).  It is rarely used and adds
significant implementation complexity.

## Semantic Compatibility with java.util.regex

SafeRE aims to match `java.util.regex` behavior exactly, and does so in all
cases that we know of except where JDK behavior is suspected to be a bug:

1. **Nested repetition with captures** — In patterns like `(a)*$`, JDK's
   backtracking engine leaks captures from failed starting positions.  JDK is
   itself internally inconsistent here (`(a)*$` vs `(?:(a))*$` give different
   group 1 results).  SafeRE follows NFA-correct semantics.  See
   [issue #52](https://github.com/eaftan/safere/issues/52) for details.

Both SafeRE and `java.util.regex` use **leftmost-first** alternation
semantics (the first alternate that matches wins), which differs from POSIX
leftmost-longest.  This means SafeRE is a drop-in replacement for
`java.util.regex` for alternation behavior.

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
3. **Possessive quantifiers** (`*+`, `++`) — not supported.
4. **Named captures** use `(?P<name>...)` syntax (RE2-style), not
   `(?<name>...)` (Java-style). Both are accepted.

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

### Publication-Quality Benchmark Collection

To collect a full set of benchmark data for updating
[BENCHMARKS.md](BENCHMARKS.md), run the collection script from the repository
root:

```bash
./collect-benchmark-results.sh
```

To verify the collection pipeline without doing a full run:

```bash
./collect-benchmark-results.sh --smoke
```

The script runs the Java, C++ RE2, and Go benchmark batches sequentially,
captures raw output, extracts native JSON-lines results, and generates merged
markdown tables.

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
cpp-results.jsonl
go-results.jsonl
merged-tables.md
java-memory.txt
java-pattern-memory.txt
```

### Targeted Benchmark Runs

Always use the wrapper scripts — they run `mvn install` first to ensure
the benchmark module picks up the latest SafeRE code. These are useful for
development iteration or focused investigation; use
`./collect-benchmark-results.sh` for full publication-quality collection.

```bash
# Java benchmarks (throughput)
./run-java-benchmarks.sh                        # all benchmarks
./run-java-benchmarks.sh RegexBenchmark         # specific class

# Java memory profiling (allocation rates via JMH GC profiler)
./run-java-memory-benchmarks.sh                 # all benchmarks
./run-java-memory-benchmarks.sh RegexBenchmark  # specific class

# Fast development iteration only — NOT for BENCHMARKS.md
./run-java-benchmarks.sh --quick RegexBenchmark
```

### C++ RE2 and Go Benchmarks

The benchmark suite includes C++ RE2 and Go `regexp` harnesses for
cross-language comparison. Prerequisites: CMake ≥ 3.14 + C++17 compiler
(for C++), Go ≥ 1.21 (for Go). Dependencies are fetched automatically.

```bash
# C++ RE2 benchmarks
./run-cpp-benchmarks.sh                    # all C++ benchmarks
./run-cpp-benchmarks.sh Regex Compile      # specific benchmark groups

# Go regexp benchmarks
./run-go-benchmarks.sh                     # all Go benchmarks
./run-go-benchmarks.sh Regex Compile       # specific benchmark groups
```

### Comparing Results Manually

A comparison script merges JMH, C++, and Go results into side-by-side markdown:

```bash
python3 safere-benchmarks/scripts/compare-benchmarks.py \
  --jmh jmh-output.txt --json cpp-results.jsonl go-results.jsonl
```

### Latest Results

See [BENCHMARKS.md](BENCHMARKS.md) for full results. Highlights:

| Benchmark | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go | vs JDK |
|---|--:|--:|--:|--:|--:|--:|---|
| Literal match | 9 ns | 13 ns | 126 ns | 55 ns | 40 ns | 78 ns | **1.4× faster** |
| Capture groups (3) | 94 ns | 75 ns | 958 ns | 329 ns | 84 ns | 311 ns | 1.3× slower |
| Capture groups (10) | 200 ns | 235 ns | 1,398 ns | 737 ns | 367 ns | 600 ns | **1.2× faster** |
| Hard pattern (1 MB) | 0.019 µs | 43,773 µs | 37,857 µs | 152 µs | 0.048 µs | 25,445 µs | **2.3M× faster** |
| Pathological (n=20) | 0.072 µs | 15,389 µs | 6.64 µs | 0.092 µs | 0.071 µs | 3.07 µs | **210,808× faster** |
| Literal replaceFirst | 30 ns | 40 ns | 147 ns | 215 ns | 98 ns | 605 ns | **1.3× faster** |

**Summary (geometric mean of speed ratios):**

| vs | Core workloads | Pathological/scaling |
|---|---|---|
| JDK | 1.1× slower | **13,500× faster** |
| RE2/J | **11.5× faster** | **2,930× faster** |
| RE2-FFM | **2.1× faster** | **17.3× faster** |

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
