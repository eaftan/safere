# SafeRE Benchmark Report

This report compares SafeRE with `java.util.regex` (JDK), RE2/J 1.8, C++ RE2
through Java's Foreign Function & Memory API (RE2-FFM), native C++ RE2,
PCRE2 JIT, Go [`regexp`](https://pkg.go.dev/regexp), Rust
[`regex`](https://docs.rs/regex), and .NET 10's non-backtracking engine. Lower
times are better.

## Executive summary

The primary result is the 150-measurement real-world matrix, the report's
broadest matching category. By geometric mean, SafeRE is 2.22× faster than the
JDK, 12.5× faster than RE2/J, and 3.06× faster than RE2-FFM on this matrix. The
SafeRE/JDK result remains 1.88× faster after removing the two 100K no-match
cases where JDK backtracking takes roughly 42–44 seconds per operation.

The smaller Core and Application categories are supporting checks, not the
headline. SafeRE is 22% slower than the JDK on the eight core workloads and 7%
slower on the eight application workloads. Against JVM-accessible linear-time
engines, it is 10.0× and 8.36× faster than RE2/J, and 1.92× and 1.83× faster
than RE2-FFM, on Core and Application respectively. These focused results show
that the broad real-world lead over the JDK is workload-dependent rather than
universal.

The cross-runtime results show a more varied tradeoff. Across all 150
real-world measurements, SafeRE is 13% slower than native C++ RE2, 6.56×
faster than Go `regexp`, and takes 2.03× as long as Rust `regex`. On the
subsets supported by those adapters, SafeRE takes 2.47× as long as PCRE2 JIT
across 48 measurements and is 8% slower than .NET non-backtracking across 114.
These are ecosystem context, not controlled same-runtime comparisons.

SafeRE's pre-existing UTF-8 API has mixed results relative to its Java-string
API. String matching is 1.22× faster on five small core searches, while UTF-8
is 1.16× faster on the 48 real-world measurements supported by both paths. The
experimental JDK 26 Vector provider was not enabled in this collection, so the
UTF-8 results use SafeRE's default provider.

The principal costs are compilation and retained memory. SafeRE compilation
takes 71.2× as long as JDK compilation, 11.7× as long as RE2/J, and 2.58× as
long as RE2-FFM across four patterns. SafeRE also retains substantially more
data per compiled pattern. In return, adversarial behavior stays bounded: for
`a?{20}a{20}`, SafeRE takes 0.089 µs while the JDK takes 17,079 µs; for a 1 MiB
end-anchored failed search, SafeRE rejects in 0.049 µs while the JDK takes
47,256 µs.

## Environment and reproducibility

- Benchmarked commit: `516a1ff76141bc83c3910426332c7824acdf007c`
- Commit date/time: 2026-08-02T02:46:40Z
- SafeRE version: 0.11.0-SNAPSHOT
- CPU: Intel Core i7-11700K, 8 cores / 16 threads, 3.6 GHz base
- Memory available to WSL2: 16 GiB; Windows 11 host
- OS: Ubuntu 24.04 on WSL2, Linux 6.6.87.2-microsoft-standard-WSL2
- Java: OpenJDK 26.0.2+10-55, targeting Java 21
- JMH: 1.37
- C++ compiler: g++ 13.3.0, Release build (`-O3 -DNDEBUG`)
- C++ RE2: 2025-11-05
- PCRE2: 10.47 with JIT enabled
- Go: 1.26.1 linux/amd64
- Rust: rustc 1.97.1, `regex` 1.13.1
- .NET SDK: 10.0.110

The collection command was:

```bash
./collect-benchmark-results.sh --cross-language --skip-openjdk-regex
```

The separately licensed OpenJDK-derived suite was intentionally skipped and is
not included in this report.

The complete raw and normalized results used for this report are checked in at
[`benchmark-results/published/516a1ff76141bc83c3910426332c7824acdf007c/`](benchmark-results/published/516a1ff76141bc83c3910426332c7824acdf007c/).
That directory includes the original harness output, the resolved declarative
plan, normalized JSON Lines, generated comparison tables, provenance, and file
checksums so the calculations can be reproduced or independently inspected.

Java used the standard project configuration: 2 forks, 2 warmup iterations of
500 ms, and 5 measurement iterations of 500 ms. Declared no-fork workloads use
`-f 0`. The allocation pass used its dedicated publication configuration and
JMH's GC profiler. No `--long` confirmation run was performed, so every timing
in this report comes from the standard collection.

C++, Go, Rust, and .NET average-time workloads used 2 warmup and 10 measurement
iterations of 2 seconds in one process. .NET cold-start workloads used five
fresh processes. Before execution, the collection materialized
`benchmark-data.json` into one resolved manifest and exact UTF-8 input files;
every harness read those artifacts. Java results report JMH's 99.9% confidence
intervals. The native harnesses report 99.9% Student's t confidence intervals.

Java engines operate on Java strings, except SafeRE UTF-8, which consumes
pre-existing UTF-8 bytes. RE2-FFM includes UTF-16-to-UTF-8 conversion and the
native-call boundary. Native C++, Go, and Rust consume UTF-8; .NET operates on
UTF-16 strings. Cross-runtime ratios therefore describe complete application
paths in their respective runtimes, not isolated engine throughput under one
runtime.

Several very small Java measurements have wide intervals in this standard run,
including SafeRE literal and character-class matching. SafeRE's medium compile
case is also noisy. Aggregate ratios use the measured point estimates, but
close conclusions in those areas should be confirmed with `--long` before
guiding an optimization decision.

## Benchmark categories

The Real-world matrix is the primary headline category because it has the
broadest pattern and input coverage. Core and Application are smaller,
deliberately focused supporting checks that help explain where the headline
does and does not generalize.

| Category | Composition | Question answered |
|---|---|---|
| Real-world | 25 data-driven patterns, each measured on matching and non-matching inputs at 1K, 10K, and 100K: six rows per pattern and 150 rows in total | How does performance vary across broader pattern shapes, input sizes, match positions, and successful versus failed searches? |
| Core | Eight focused operations: literal full match, character-class full match, alternation search, prose search, email search, capture extraction, Pig Latin replacement, and HTTP request parsing | How do the engines compare on a compact cross-section of common regex API operations? |
| Application | Eight realistic tasks: UUID validation, structured log parsing, API route matching, stack-trace extraction, case-insensitive keyword search, URL extraction, CSV field scanning, and secret redaction | How do the engines perform when matching, captures, repeated search, and replacement are combined into application-shaped work? |

Core and Application each give one equal-weight measurement to every listed
workload. The Real-world aggregate gives equal weight to every one of its 150
rows, so each pattern contributes six equal-weight measurements. Engine
adapters exclude workloads they cannot express with equivalent semantics; the
same-runtime JVM summary has complete coverage, while cross-runtime summaries
state their actual row counts.

Compilation, memory, scaling, pathological behavior, UTF-8-specific operations,
and SafeRE-only functionality answer separate questions and are reported in
their own sections rather than folded into these three matching categories.

## Aggregate comparisons

Ratios are SafeRE string time / competitor time. Values below 1 mean SafeRE is
faster. Each workload or parameter row has equal weight, and aggregates use
the geometric mean of speed ratios.

The same-runtime summary uses identical membership for all four JVM engines.
The headline matching category appears first; Compilation is included for
contrast but is not part of the matching headline.

| Category | Rows | vs JDK | vs RE2/J | vs RE2-FFM |
|---|---:|---:|---:|---:|
| Real-world matrix | 150 | 0.451 (2.22× faster) | 0.0798 (12.5× faster) | 0.326 (3.06× faster) |
| Core workloads | 8 | 1.219 (22% slower) | 0.0998 (10.0× faster) | 0.521 (1.92× faster) |
| Application workloads | 8 | 1.069 (7% slower) | 0.120 (8.36× faster) | 0.546 (1.83× faster) |
| Compilation | 4 | 71.22 (takes 71.2× as long) | 11.67 (takes 11.7× as long) | 2.581 (takes 2.58× as long) |

Core contains literal match, character-class match, alternation find,
find-in-text, email find, capture groups, Pig Latin replacement, and full HTTP
parsing. Application contains all eight `ApplicationBenchmark` cases.
Real-world contains 25 patterns, matching and non-matching inputs, and 1K, 10K,
and 100K sizes: six equally weighted rows per pattern. Compilation contains the
four `CompileBenchmark` patterns.

Cross-runtime coverage differs because adapters exclude unsupported syntax or
operations. Each cell below states the comparison directly, followed by the
raw SafeRE/competitor ratio and row count. Comparisons across columns should
not be treated as if they had identical membership.

| Engine | Real-world comparison | Core comparison | Application comparison |
|---|---|---|---|
| Native C++ RE2 | SafeRE 13% slower (`1.126`, 150 rows) | SafeRE 1.32× faster (`0.758`, 8 rows) | SafeRE 4% slower (`1.039`, 8 rows) |
| PCRE2 JIT | SafeRE takes 2.47× as long (`2.471`, 48 rows) | SafeRE 4% slower (`1.041`, 7 rows) | SafeRE takes 2.40× as long (`2.396`, 7 rows) |
| Go `regexp` | SafeRE 6.56× faster (`0.152`, 150 rows) | SafeRE 3.42× faster (`0.293`, 8 rows) | SafeRE 2.34× faster (`0.428`, 8 rows) |
| Rust `regex` | SafeRE takes 2.03× as long (`2.028`, 150 rows) | SafeRE 40% slower (`1.402`, 8 rows) | SafeRE 51% slower (`1.514`, 8 rows) |
| .NET non-backtracking | SafeRE 8% slower (`1.077`, 114 rows) | SafeRE 2.40× faster (`0.418`, 7 rows) | SafeRE 3.47× faster (`0.288`, 6 rows) |

On the headline matrix, SafeRE is 13% slower than native RE2, takes 2.03× as
long as Rust, and is 6.56× faster than Go. The smaller categories add useful
texture: SafeRE is 1.32× faster than native RE2 on Core and approximately even
on Application; Rust leads SafeRE by 1.40× and 1.51× on those categories; and
SafeRE leads Go by 3.42× and 2.34×. .NET's omitted rows include unsupported
patterns and operations, so its strong Core and Application ratios and its
approximately even Real-world ratio describe different subsets.

## Real-world headline analysis

The real-world suite has 25 patterns. Every fully supported pattern contributes
six equal-weight measurements: match and no-match at 1K, 10K, and 100K. The
overall JVM aggregate uses all 150 rows. Native RE2, Go, and Rust also support
all 150; SafeRE UTF-8 and PCRE2 support 48; .NET supports 114.

The JDK aggregate is materially influenced by backtracking no-match cases. At
100K, `wildcardSearch` takes 42.4 seconds and `fruitSearchQuery` takes 43.6
seconds in the JDK, versus 55.4 µs and 132.7 µs in SafeRE. Removing those two
rows changes the SafeRE/JDK geomean from 0.451 (2.22× faster) to 0.532 (1.88×
faster). The matrix also contains cases where the JDK finds an early match very
quickly: `fruitSearchQuery.match.100000` takes 528 ns in the JDK and 17.6 ms in
SafeRE. Thus the headline reflects broad multiplicative performance across the
entire matrix, not a claim that SafeRE wins every workload.

## Supporting Core and Application results

The controlled JVM timings are:

| Core workload (ns/op) | SafeRE | JDK | RE2/J | RE2-FFM |
|---|---:|---:|---:|---:|
| Literal match | 23.7 | 15.9 | 134 | 64.4 |
| Character class | 40.9 | 25.9 | 1,289 | 131 |
| Alternation find | 212 | 563 | 4,421 | 683 |
| Find in prose | 2,435 | 3,111 | 21,058 | 4,483 |
| Email find | 236 | 404 | 2,044 | 273 |
| Capture groups | 148 | 111 | 593 | 374 |
| Pig Latin `replaceAll` | 2,138 | 1,009 | 8,260 | 2,494 |
| Full HTTP parse | 401 | 94.1 | 9,485 | 422 |

| Application workload (ns/op) | SafeRE | JDK | RE2/J | RE2-FFM |
|---|---:|---:|---:|---:|
| UUID validation | 469 | 959 | 2,638 | 675 |
| Log parsing | 1,823 | 1,083 | 15,925 | 3,136 |
| API route | 528 | 526 | 6,447 | 1,173 |
| Stack trace | 2,897 | 1,765 | 28,955 | 4,804 |
| Case-insensitive keywords | 402 | 1,164 | 6,121 | 1,196 |
| URL extraction | 643 | 1,009 | 7,160 | 1,480 |
| CSV field scan | 2,641 | 767 | 10,592 | 6,691 |
| Secret redaction | 1,219 | 735 | 7,113 | 979 |

SafeRE leads the JDK on alternation, prose search, email search, UUIDs,
case-insensitive keywords, and URL extraction. The JDK leads on tiny matching,
captures, replacement, full HTTP parsing, log and stack-trace parsing, CSV
scanning, and redaction. RE2/J is slower on every row in these two tables.
RE2-FFM is close on email search, Pig Latin replacement, HTTP parsing, and
redaction, but its conversion and native-call costs remain visible on short
workloads.

The same workloads provide cross-runtime context:

| Core workload (ns/op) | C++ RE2 | PCRE2 JIT | Go | Rust | .NET |
|---|---:|---:|---:|---:|---:|
| Literal match | 74.2 | 70.8 | 68.0 | 39.2 | 46.7 |
| Character class | 112 | 81.9 | 487 | 76.8 | 127 |
| Alternation find | 412 | 228 | 1,854 | 99.0 | 344 |
| Find in prose | 2,499 | 1,354 | 13,440 | 654 | — |
| Email find | 112 | 102 | 599 | 96.4 | 136 |
| Capture groups | 185 | 177 | 216 | 119 | 825 |
| Pig Latin `replaceAll` | 1,969 | — | 2,815 | 1,700 | 5,690 |
| Full HTTP parse | 397 | 164 | 926 | 263 | 2,132 |

| Application workload (ns/op) | C++ RE2 | PCRE2 JIT | Go | Rust | .NET |
|---|---:|---:|---:|---:|---:|
| UUID validation | 369 | 206 | 990 | 171 | 494 |
| Log parsing | 2,143 | 411 | 2,463 | 1,629 | 6,777 |
| API route | 447 | 312 | 1,060 | 419 | 3,541 |
| Stack trace | 4,360 | 585 | 4,703 | 2,176 | 8,709 |
| Case-insensitive keywords | 627 | 447 | 4,767 | 451 | — |
| URL extraction | 703 | 326 | 1,646 | 502 | 3,579 |
| CSV field scan | 1,610 | 875 | 3,769 | 855 | 10,419 |
| Secret redaction | 735 | — | 2,690 | 800 | — |

Rust is the fastest cross-runtime engine on seven of the eight core rows.
PCRE2 JIT is the fastest on five of its seven application rows. Native RE2
remains especially competitive on matching that scans substantial input, while
its fixed harness overhead is visible on the smallest operations. These engines
make different choices about compilation, captures, syntax, and runtime
representation, so individual rows are more informative than a universal
ranking.

## SafeRE string and UTF-8 paths

The `safere_utf8` variant consumes bytes that were encoded before the timed
operation; it does not include string-to-UTF-8 conversion. Its five shared core
searches are:

| Workload (ns/op) | SafeRE string | SafeRE UTF-8 |
|---|---:|---:|
| Literal match | 23.7 | 51.4 |
| Character class | 40.9 | 109 |
| Alternation find | 212 | 227 |
| Find in prose | 2,435 | 2,956 |
| Email find | 236 | 83.0 |

The geomean string/UTF-8 ratio is 0.823, so string matching is 1.22× faster on
this small set. Across the 48 real-world rows supported by both paths, the
ratio is 1.156, so UTF-8 is 1.16× faster. The per-pattern table in the next
section shows that the aggregate hides large variation: UTF-8 is much faster
for `cjkSearch` and `emojiSearch`, while string matching is much faster for
`customProtocolLink` and `wildcardSearch`.

The 1 MiB hard failed-search result also differs sharply: SafeRE string rejects
in 0.049 µs through required-content analysis, while SafeRE UTF-8 takes 126 µs.
That is a fast-path coverage difference, not an encoding cost, because both
inputs were materialized before timing.

## Real-world pattern detail

The following table makes every pattern's contribution inspectable. Each cell
is the geometric mean of SafeRE string time / competitor time over that
pattern's six rows. A dash means the adapter excludes that pattern.

| Pattern | UTF-8 | JDK | RE2/J | RE2-FFM | C++ RE2 | PCRE2 | Go | Rust | .NET |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `blockedTags1` | — | 1.201 | 0.090 | 0.739 | 0.885 | — | 0.164 | 1.596 | 0.746 |
| `blockedTags2` | — | 1.455 | 0.102 | 0.868 | 1.048 | — | 0.186 | 1.739 | 0.830 |
| `boundedNameMatch` | — | 0.625 | 0.100 | 0.785 | 1.006 | — | 0.136 | 1.721 | 0.652 |
| `caseInsensitiveKeyword` | — | 1.546 | 0.051 | 0.339 | 0.432 | — | 0.063 | 1.795 | — |
| `charReplace` | — | 0.922 | 0.216 | 0.190 | 0.783 | — | 0.274 | 2.237 | 1.504 |
| `cjkSearch` | 5.181 | 5.776 | 0.092 | 0.033 | 0.917 | 1.804 | 0.182 | 1.177 | 0.954 |
| `customProtocolLink` | 0.097 | 0.169 | 0.143 | 0.655 | 4.246 | 5.766 | 0.307 | 5.236 | 3.164 |
| `emojiSearch` | 4.203 | 1.487 | 0.052 | 0.028 | 0.511 | 1.308 | 0.084 | 2.894 | 4.642 |
| `fruitMarkupTag` | — | 2.436 | 0.054 | 0.859 | 1.147 | — | 0.230 | 1.442 | 0.464 |
| `fruitSearchQuery` | — | 0.181 | 0.083 | 1.431 | 1.776 | — | 0.211 | 2.120 | — |
| `greedyOnePass` | 1.097 | 2.514 | 0.200 | 0.066 | 0.960 | 1.745 | 0.386 | 1.464 | 0.950 |
| `jsonBlock` | — | 0.292 | 0.182 | 0.369 | 1.547 | — | 0.261 | 1.904 | 1.690 |
| `layoutBlock` | — | 1.312 | 0.191 | 1.223 | 2.247 | — | 0.282 | 2.719 | 1.522 |
| `malformedEntity` | — | 0.540 | 0.050 | 0.714 | 0.842 | — | 0.105 | 1.285 | 0.661 |
| `mapFieldPath` | 4.486 | 0.175 | 0.031 | 0.273 | 12.270 | 8.242 | 0.252 | 14.548 | — |
| `markupEntity` | 1.323 | 0.408 | 0.306 | 0.057 | 0.436 | 1.452 | 0.233 | 2.065 | 1.492 |
| `markupImageLink` | — | 0.648 | 0.057 | 0.735 | 0.915 | — | 0.108 | 1.305 | 0.584 |
| `metadataBlock` | — | 0.080 | 0.050 | 0.772 | 0.969 | — | 0.127 | 1.249 | 0.676 |
| `overlappingUrl` | — | 0.325 | 0.072 | 0.892 | 1.152 | — | 0.099 | 4.040 | — |
| `sparseUrl` | — | 0.219 | 0.066 | 0.796 | 1.123 | — | 0.073 | 7.985 | — |
| `templateTagMatch` | — | 0.332 | 0.167 | 0.325 | 1.266 | — | 0.243 | 1.657 | 1.166 |
| `turnTitleWhitespaceCjk` | — | 0.197 | 0.159 | 0.337 | 0.768 | — | 0.196 | 1.116 | 0.736 |
| `unprefixedWordBoundary` | 1.688 | 0.540 | 0.160 | 0.155 | 1.236 | 2.107 | 0.192 | 1.546 | 1.187 |
| `versionList` | — | 0.230 | 0.059 | 1.059 | 1.371 | — | 0.117 | 1.604 | 1.073 |
| `wildcardSearch` | 0.137 | 0.002 | 0.001 | 0.012 | 0.537 | 2.323 | 0.012 | 0.641 | — |

The table shows why one overall ratio is insufficient. Rust is substantially
faster on most patterns but slower on `wildcardSearch`; native RE2 is usually
close but has large early-match advantages on `mapFieldPath`; and SafeRE's
required-content rejection creates very large leads over JDK, RE2/J, and Go on
`wildcardSearch`. The real-world geomeans summarize these multiplicative
tradeoffs without erasing their direction.

## Compilation, replacement, and captures

| Compile workload (µs/op) | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | PCRE2 | Go | Rust | .NET |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Simple | 6.64 | 0.108 | 0.388 | 3.02 | 1.58 | 3.38 | 1.02 | 1.78 | 36.0 |
| Medium | 50.8 | 0.428 | 2.12 | 11.0 | 6.95 | 7.76 | 7.01 | 149 | 52.6 |
| Complex | 15.5 | 0.277 | 1.89 | 7.57 | 4.82 | 5.59 | 2.72 | 13.6 | 60.6 |
| Alternation | 26.9 | 0.428 | 4.88 | 12.6 | 7.89 | 7.97 | 6.68 | 58.3 | 3,246 |

SafeRE performs eager parsing, simplification, compilation, and execution-path
analysis. That front-loads work compared with the JDK and RE2/J. Other engines
also expose pattern-dependent construction costs: Rust's medium pattern takes
149 µs, and .NET non-backtracking's alternation takes 3.25 ms. SafeRE's medium
result has a wide confidence interval in this run and should be confirmed
before attributing a precise regression.

First-process Unicode initialization is a separate cost. For the identifier
pattern, SafeRE's first compile takes 78 ms with default flags and 169 ms with
case-insensitive Unicode flags, versus about 1.3 ms for the JDK and 27 ms for
.NET in both cases.

| Replacement workload (ns/op) | SafeRE | JDK | RE2/J | RE2-FFM |
|---|---:|---:|---:|---:|
| Digit `replaceAll` | 158 | 304 | 3,111 | 1,027 |
| Literal `replaceFirst`, no match | 95.2 | 281 | 220 | 486 |
| Literal `replaceFirst` | 98.6 | 55.7 | 159 | 222 |
| Pig Latin `replaceAll` | 2,138 | 1,009 | 8,260 | 2,494 |
| Empty-match `replaceAll` | 99.2 | 83.8 | 431 | 650 |

| Capture groups (ns/op) | SafeRE | JDK | RE2/J | RE2-FFM |
|---|---:|---:|---:|---:|
| 0 | 55.7 | 50.0 | 421 | 87.0 |
| 1 | 78.1 | 73.6 | 931 | 343 |
| 3 | 157 | 108 | 1,061 | 398 |
| 10 | 366 | 248 | 1,490 | 772 |

SafeRE remains faster than RE2/J and RE2-FFM as capture count grows, but the
JDK is faster at every measured capture count in this collection.

## Scaling and adversarial behavior

These rows are selected for distinct scaling questions rather than combined
into one general-purpose aggregate.

| Stress workload (µs/op) | SafeRE | UTF-8 | JDK | RE2/J | RE2-FFM |
|---|---:|---:|---:|---:|---:|
| `a?{20}a{20}` on `a{20}` | 0.089 | 0.075 | 17,079 | 7.23 | 0.109 |
| 1 MiB hard failed search | 0.049 | 126 | 47,256 | 40,302 | 376 |
| Nested quantifier, 100 KiB | 147 | 136 | 1,563 | 39,039 | 169 |

| Stress workload (µs/op) | C++ RE2 | PCRE2 | Go | Rust | .NET |
|---|---:|---:|---:|---:|---:|
| `a?{20}a{20}` on `a{20}` | 0.102 | 1,884 | 2.97 | 0.058 | 0.073 |
| 1 MiB hard failed search | 0.076 | 409 | 25,371 | 0.032 | 2,518 |
| Nested quantifier, 100 KiB | 106 | 2,580 | 21,831 | 128 | 4.35 |

The pathological comparison demonstrates the JDK's exponential backtracking;
larger configured JDK cases are intentionally excluded. RE2/J and Go remain
linear but perform substantially more active-state work. PCRE2 JIT is a
backtracking engine and does not provide the same worst-case guarantee.

The hard search uses `[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$` on a 1 MiB input that
cannot match. SafeRE string, native RE2, and Rust reject from required-content
or reverse-search analysis without scanning the whole input. RE2/J, Go, .NET,
and the JDK do input-proportional or worse work. The nested-quantifier row shows
a different ordering: .NET non-backtracking leads, while SafeRE, native RE2,
Rust, and RE2-FFM cluster within a modest range.

## Memory

Retained compiled-pattern size is larger for SafeRE because it stores the
compiled program and execution analyses:

| Pattern | SafeRE | JDK | RE2/J |
|---|---:|---:|---:|
| Simple | 8,420 B | 756 B | 652 B |
| Medium | 17,132 B | 940 B | 1,692 B |
| Complex | 7,476 B | 1,204 B | 844 B |
| Alternation | 21,620 B | 964 B | 3,500 B |

Measured SafeRE DFA cache growth was 160 B for the simple pattern, 208 B for
medium, 91,384 B for complex, and 2,320 B for alternation. Cache growth is
workload-dependent and is separate from the retained compiled-pattern table.

For the easy search allocation scaling workload, SafeRE remained near 160 B/op
from 1 KiB through 1 MiB. JDK stayed near 56 B/op, and RE2/J near 48 B/op.
These figures describe that search path; result materialization, capture state,
replacement, and other APIs have different allocation profiles. Native retained
memory measurements use runtime-specific accounting and are not combined with
the JVM retained-object measurements.

## SafeRE-specific functionality

`PatternSet` matches multiple patterns simultaneously and has no direct
comparator in the other APIs. At 4, 16, and 64 patterns, anchored successful
matches took 9.31, 11.6, and 30.4 µs; unanchored successful matches took 8.19,
50.2, and 215 µs. Most of these standard-run intervals are wide, so the
directional scaling is more reliable than the precise point estimates.

The diagnostic-hook benchmarks are intentionally excluded from engine
aggregates. Enabling diagnostics changes tiny-operation costs substantially,
while adding little relative overhead to longer NFA and replacement paths; the
suite measures that instrumentation tradeoff separately from normal matching.

## Interpretation

The report's headline conclusion comes from its broadest controlled category:
across the 150-row real-world matrix, SafeRE is 2.22× faster than the JDK, 12.5×
faster than RE2/J, and 3.06× faster than RE2-FFM by geometric mean. The JDK
comparison remains a 1.88× SafeRE lead after removing its two largest
backtracking cases. The smaller Core and Application results qualify that
headline: SafeRE is modestly slower than the JDK there, and individual patterns
range from major SafeRE wins to major JDK wins. Workload shape matters more than
one aggregate ranking.

The native results map the cost of SafeRE's Java implementation against engines
with different runtime and automata choices. Rust `regex` is the strongest
cross-runtime performer over the broad real-world matrix, while PCRE2 JIT leads
many supported application rows. Native RE2 stays close to SafeRE overall. Go
and RE2/J preserve linear-time behavior with NFA-oriented execution but pay more
per-character state-management cost on many scans. .NET non-backtracking is
excellent on the nested-quantifier stress case but supports a smaller subset of
the canonical Java workloads.

SafeRE's trade is explicit: slower compilation, higher retained memory, and
some slower short or backtracking-friendly matches in exchange for bounded
worst-case behavior, strong required-content rejection, and competitive
steady-state matching. This report should be read as a map of those tradeoffs,
not as a claim that one regex engine is universally fastest.
