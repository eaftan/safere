# SafeRE Benchmark Results

Comparing SafeRE against `java.util.regex` (JDK),
[RE2/J](https://github.com/google/re2j) 1.8, RE2-FFM (C++ RE2 via Java
[FFM API](https://openjdk.org/jeps/454)), C++ RE2 (2024-07-02), and
Go [`regexp`](https://pkg.go.dev/regexp) (Go 1.26.1) on common regex
workloads and pathological patterns that demonstrate backtracking blowup.

**Environment:**
- Benchmarked commit: `0ebd8aed7edcfe43c5a9cee92efee84167a76aa0`
- Commit date/time: 2026-04-27T02:42:22Z
- CPU: Intel Core i7-11700K (8 cores / 16 threads, 3.6 GHz base)
- RAM: 32 GB
- OS: Ubuntu 24.04.4 LTS on WSL2 (kernel 6.6.87.2-microsoft-standard-WSL2),
  Windows 11 host
- JDK: OpenJDK 25.0.2+10-69 (targeting Java 21)
- JMH: 1.37, fork mode (3 forks, 3 warmup × 5s, 5 measurement × 5s)
- RE2-FFM: C++ RE2 (2024-07-02) accessed via Java FFM API (JEP 454)
- C++ compiler: g++ 13.3.0, `-O3 -DNDEBUG` (CMake Release)
- Go: 1.26.1 linux/amd64

**Cross-language comparison caveats:**
C++ RE2 and Go `regexp` operate on UTF-8 byte strings while Java operates on
UTF-16 char arrays. RE2-FFM wraps C++ RE2 via the Foreign Function & Memory
API, adding per-call overhead for UTF-16 → UTF-8 conversion and native memory
management. C++ RE2 benefits from ahead-of-time compilation, no GC pauses, and
no JIT warmup. Go has a concurrent GC with different characteristics from
Java's. These are real-world differences — the goal is to show SafeRE is in
the same algorithmic ballpark as other RE2-family engines, not to declare a
winner across language boundaries. Within the Java ecosystem, the SafeRE vs JDK
vs RE2/J vs RE2-FFM comparison is apples-to-apples.

For instructions on collecting publication-quality benchmark data, see
[Benchmarks](README.md#benchmarks) in the README.

## Methodology

All benchmarks use a warmup-then-measure approach, but the settings differ
between Java and native harnesses to account for their different runtime
characteristics.

**Java (JMH):** 3 forks × (3 warmup × 5s + 5 measurement × 5s), except
pathological benchmark classes use `-f 0` so the JDK engine can be timed only
for feasible input sizes without spawning JVMs that may hang.

**C++ and Go:** 2 warmup + 10 measurement iterations × 2s each, single process.
Native code has no JIT, so the same binary always runs the same machine code —
forks are unnecessary. Warmup is shorter (2 iterations vs 5) because it only
needs to prime CPU caches, branch predictors, and the memory allocator, all of
which settle within a few seconds. More measurement iterations (10 vs JMH's 5
per fork) compensate for the lack of fork-level variance sampling. Total: 10
samples per benchmark.

**Statistical reporting:** All harnesses report mean ± 99.9% confidence
interval half-width. Java uses JMH's built-in statistics. C++ and Go use
Student's t-distribution (t ≈ 4.781 for 9 df at 99.9%).

**Shared test data:** All harnesses read patterns, inputs, and parameters from
a single `benchmark-data.json` file. Application cases also define their
operation type and expected result in that file, so Java, C++, and Go validate
the same semantics before timing.

**Workload coverage:** The suite separates focused microbenchmarks,
data-driven application workloads, and pathological/scaling workloads.

| Setting | Java (JMH) | C++ | Go |
|---|---|---|---|
| Warmup | 3 × 5s per fork | 2 × 2s | 2 × 2s |
| Measurement | 5 × 5s per fork | 10 × 2s | 10 × 2s |
| Forks | 3 (fresh JVM each) | 1 (single process) | 1 (single process) |
| Total samples | 15 | 10 | 10 |
| Optimization | JIT (steady-state) | `-O3 -DNDEBUG` | Go default |

## Matching Performance (ns/op, lower is better)

| Benchmark | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|---|--:|--:|--:|--:|--:|--:|---|---|---|
| Literal match (`"hello"`) | 11 | 13 | 128 | 57 | 40 | 76 | **1.2× faster** | **12× faster** | **5.3× faster** |
| Char class match (`[a-zA-Z]+`) | 21 | 24 | 1,236 | 110 | 82 | 364 | **1.2× faster** | **60× faster** | **5.4× faster** |
| Alternation find (`foo\|bar\|…` ×8) | 2,207 | 534 | 4,344 | 615 | 17 | 1,693 | 4.1× slower | **2.0× faster** | 3.6× slower |
| Capture groups (`(\d{4})-(\d{2})-(\d{2})`) | 77 | 96 | 559 | 325 | 75 | 233 | **1.2× faster** | **7.3× faster** | **4.2× faster** |
| Find -ing words in prose (~350 chars) | 3,429 | 2,985 | 20,026 | 4,249 | 17 | 12,426 | 1.1× slower | **5.8× faster** | **1.2× faster** |
| Email pattern find | 285 | 394 | 1,918 | 230 | 82 | 546 | **1.4× faster** | **6.7× faster** | 1.2× slower |

SafeRE beats JDK on literal matching, character-class matching, capture groups,
and email find. It is slower on alternation find, where C++ RE2 and RE2-FFM
benefit from native-code prefix handling, and modestly slower on find-in-text.
SafeRE remains faster than RE2/J on every matching benchmark, with especially
large gains on literal and character-class matches.

## Application Workloads (ns/op, lower is better)

Data-driven validation, parsing, extraction, scanning, and redaction cases from
`ApplicationBenchmark`.

| Benchmark | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|---|--:|--:|--:|--:|--:|--:|---|---|---|
| UUID validation | 425 | 1,299 | 2,549 | 602 | 344 | 956 | **3.1× faster** | **6.0× faster** | **1.4× faster** |
| Log line parse | 3,861 | 1,118 | 15,867 | 2,873 | 1,998 | 2,249 | 3.5× slower | **4.1× faster** | 1.3× slower |
| API route match | 611 | 646 | 6,650 | 1,007 | 306 | 997 | **1.1× faster** | **11× faster** | **1.6× faster** |
| Stack trace extract | 5,895 | 2,450 | 28,185 | 4,754 | 4,106 | 4,367 | 2.4× slower | **4.8× faster** | 1.2× slower |
| Case-insensitive keywords | 4,059 | 1,213 | 6,740 | 1,284 | 477 | 4,417 | 3.3× slower | **1.7× faster** | 3.2× slower |
| URL extraction | 869 | 1,270 | 7,572 | 1,424 | 608 | 1,572 | **1.5× faster** | **8.7× faster** | **1.6× faster** |
| CSV field scan | 3,285 | 742 | 10,245 | 6,268 | 1,222 | 3,455 | 4.4× slower | **3.1× faster** | **1.9× faster** |
| Secret redaction | 2,147 | 783 | 7,166 | 978 | 618 | 2,524 | 2.7× slower | **3.3× faster** | 2.2× slower |

SafeRE wins 3 of 8 application workloads against JDK and is faster than RE2/J
on all 8. Against RE2-FFM, SafeRE wins UUID validation, API route matching,
URL extraction, and CSV field scanning, while RE2-FFM has an edge on workloads
where C++ RE2's native execution offsets FFM overhead.

## Search Scaling (µs/op, lower is better)

How performance scales with input size (1 KB → 1 MB). These patterns search
through random text that does **not** contain the match (worst-case scan).

**Note on C++ RE2 search scaling:** C++ RE2 uses a reverse DFA that
recognizes end-anchored patterns (`$`) and only scans the string suffix,
achieving ~0.04 µs regardless of text size. SafeRE now implements a similar
optimization for the Hard pattern (see Search Scaling Hard below), achieving
constant-time rejection. C++ RE2 results are omitted from the scaling tables
since they measure a different code path. RE2-FFM wraps C++ RE2 and exhibits
the same constant-time behavior for Hard and Medium patterns.

### Easy: `ABCDEFGHIJKLMNOPQRSTUVWXYZ$` (literal prefix, memchr-able)

| Text Size | SafeRE | JDK | RE2/J | RE2-FFM | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|--:|--:|--:|--:|--:|--:|---|---|---|
| 1 KB | 0.09 | 0.16 | 0.10 | 0.17 | 0.08 | **1.8× faster** | ~same | **1.9× faster** |
| 10 KB | 0.83 | 1.44 | 0.84 | 1.07 | 0.23 | **1.7× faster** | ~same | **1.3× faster** |
| 100 KB | 8.2 | 14.2 | 8.2 | 11.1 | 2.3 | **1.7× faster** | ~same | **1.4× faster** |
| 1 MB | 84 | 146 | 84 | 152 | 24 | **1.7× faster** | ~same | **1.8× faster** |

### Medium: `[XYZ]ABCDEFGHIJKLMNOPQRSTUVWXYZ$` (starts with char class)

| Text Size | SafeRE | JDK | RE2/J | RE2-FFM | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|--:|--:|--:|--:|--:|--:|---|---|---|
| 1 KB | 0.38 | 0.26 | 21.7 | 0.16 | 14 | 1.5× slower | **56× faster** | 2.4× slower |
| 10 KB | 3.7 | 2.4 | 233 | 1.0 | 171 | 1.5× slower | **63× faster** | 3.6× slower |
| 100 KB | 37 | 24 | 2,397 | 11 | 1,714 | 1.5× slower | **65× faster** | 3.5× slower |
| 1 MB | 381 | 370 | 24,548 | 149 | 18,101 | ~same | **64× faster** | 2.5× slower |

Character-class prefix acceleration allows SafeRE to scan directly for
`[XYZ]` before invoking the DFA, reducing the gap with JDK to just 1.5×.
SafeRE is **56–65× faster than RE2/J** on this pattern. RE2-FFM benefits
from C++ RE2's reverse DFA, achieving near-constant time and beating SafeRE
by 2.4–3.6×.

### Hard: `[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$` (catastrophic in backtracking engines)

| Text Size | SafeRE | JDK | RE2/J | RE2-FFM | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|--:|--:|--:|--:|--:|--:|---|---|---|
| 1 KB | 0.021 | 59 | 36 | 0.17 | 18 | **2,800× faster** | **1,700× faster** | **8.2× faster** |
| 10 KB | 0.021 | 448 | 380 | 1.1 | 244 | **21,000× faster** | **18,000× faster** | **51× faster** |
| 100 KB | 0.020 | 4,472 | 3,748 | 11 | 2,437 | **224,000× faster** | **187,000× faster** | **560× faster** |
| 1 MB | 0.020 | 45,835 | 38,671 | 153 | 24,995 | **2.3M× faster** | **1.9M× faster** | **7,700× faster** |

SafeRE achieves **constant-time rejection** (~0.020 µs regardless of text
size) on this pattern, similar to C++ RE2's reverse DFA optimization. SafeRE
detects at compile time that the required literal suffix `ABCDEFGHIJKLMNOPQRSTUVWXYZ`
cannot occur in random ASCII text and short-circuits before scanning. This is
even faster than C++ RE2 (0.044 µs) and RE2-FFM, which must still invoke the
reverse DFA. JDK's backtracking engine exhibits O(n²) behavior due to the
leading `[ -~]*`, making SafeRE millions of times faster at 1 MB. RE2/J and
Go `regexp` handle it in linear time but are still orders of magnitude slower
than SafeRE's constant-time path.

### Successful Search (Easy pattern, match at end of text)

| Text Size | SafeRE | JDK | RE2/J | RE2-FFM | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|--:|--:|--:|--:|--:|--:|---|---|---|
| 1 KB | 0.35 | 0.18 | 0.81 | 0.24 | 0.21 | 1.9× slower | **2.4× faster** | 1.4× slower |
| 10 KB | 1.1 | 1.6 | 1.6 | 1.1 | 0.72 | **1.4× faster** | **1.4× faster** | ~same |
| 100 KB | 8.5 | 15.2 | 8.9 | 11.1 | 2.7 | **1.8× faster** | ~same | **1.3× faster** |
| 1 MB | 84 | 154 | 84 | 155 | 24 | **1.8× faster** | ~same | **1.8× faster** |

SafeRE has higher per-match startup cost but scales well; it overtakes JDK
at 10 KB. DFA caching keeps the 1 KB case at 0.35 µs. RE2-FFM follows a
similar pattern — 1.4× faster than SafeRE at 1 KB but 1.8× slower at 1 MB,
as FFM per-call overhead grows with input size.

## Capture Group Scaling (ns/op, lower is better)

| Groups | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|--:|--:|--:|--:|--:|--:|--:|---|---|---|
| 0 | 60 | 30 | 399 | 74 | 59 | 160 | 2.0× slower | **6.7× faster** | **1.2× faster** |
| 1 | 75 | 40 | 887 | 273 | 65 | 232 | 1.9× slower | **12× faster** | **3.6× faster** |
| 3 | 97 | 78 | 963 | 328 | 79 | 293 | 1.2× slower | **10× faster** | **3.4× faster** |
| 10 | 197 | 236 | 1,405 | 755 | 372 | 533 | **1.2× faster** | **7.1× faster** | **3.8× faster** |

SafeRE closes the gap with JDK as capture count grows — from 2.0× slower at
0 groups to **1.2× faster at 10 groups**. SafeRE is consistently **7–12×
faster than RE2/J** and **3.4–3.8× faster than RE2-FFM** on capture extraction
(at 1+ groups). The RE2-FFM gap reflects FFM call overhead on top of C++ RE2's
capture engine. At 0 groups (no captures), SafeRE is 1.2× faster than RE2-FFM.
C++ RE2 is slower than SafeRE at 10 groups — both use OnePass-style engines
that scale similarly with group count. Go `regexp` is 1.6–2.7× slower than SafeRE,
consistent with its NFA-only approach.

## HTTP Request Parsing (ns/op, lower is better)

Pattern: `^(?:GET|POST) +([^ ]+) HTTP`

| Input | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|---|--:|--:|--:|--:|--:|--:|---|---|---|
| Full request (97 chars) | 269 | 91 | 8,108 | 384 | 308 | 902 | 3.0× slower | **30× faster** | **1.4× faster** |
| Small request (18 chars) | 70 | 49 | 950 | 138 | 69 | 223 | 1.4× slower | **14× faster** | **2.0× faster** |
| Extract URL (97 chars) | 276 | 92 | 8,100 | 384 | 311 | 900 | 3.0× slower | **29× faster** | **1.4× faster** |

SafeRE's HTTP parsing improved significantly from 1,292 to 269 ns/op thanks to
the HTTP/OnePass fast path optimization. SafeRE is now within 3.0× of JDK on
full HTTP requests (previously 14×) and is faster than C++ RE2 (269 vs
308 ns). SafeRE remains **14–30× faster than RE2/J** on all HTTP workloads.
RE2-FFM is slightly slower than SafeRE on this benchmark, with SafeRE 1.4×
faster on full and extract, and 2.0× faster on small request patterns.

## Replace Performance (ns/op, lower is better)

| Benchmark | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|---|--:|--:|--:|--:|--:|--:|---|---|---|
| Literal replaceFirst (`"b"→"bb"`) | 30 | 40 | 146 | 213 | 97 | 566 | **1.3× faster** | **4.9× faster** | **7.1× faster** |
| Literal replaceAll | 105 | 106 | 704 | 681 | 414 | 462 | ~same | **6.7× faster** | **6.5× faster** |
| Pig Latin replaceAll (backrefs) | 1,519 | 902 | 7,857 | 2,318 | 1,837 | 2,700 | 1.7× slower | **5.2× faster** | **1.5× faster** |
| Digit replaceAll (`\d+`→`"NUM"`) | 195 | 283 | 3,091 | 964 | 648 | 1,495 | **1.5× faster** | **16× faster** | **4.9× faster** |
| Empty-match replaceAll (`a*`) | 270 | 75 | 398 | 638 | 378 | 333 | 3.6× slower | **1.5× faster** | **2.4× faster** |

SafeRE wins on literal replacements — **faster than all other engines**
(including C++ RE2!) on replaceFirst (30 ns), thanks to the `String.indexOf()`
fast path. Digit replaceAll is **1.5× faster than JDK** thanks to the
character-class replaceAll fast path. Pig Latin replaceAll runs at 1,519 ns
via compiled replacement templates and direct BitState find+capture. SafeRE
beats RE2-FFM on every replace benchmark by **1.5–7.1×**, as repeated FFM
round-trips per match add significant overhead. For empty-match replacement,
JDK remains fastest. Go `regexp` is consistently faster than RE2/J on
replacements.

## PatternSet Multi-Pattern Matching (ns/op)

Matching text against multiple compiled patterns simultaneously (SafeRE-only
feature — neither JDK nor RE2/J has a built-in multi-pattern API).

| Patterns | Unanchored (match) | Unanchored (no match) | Anchored (match) | Anchored (no match) |
|--:|--:|--:|--:|--:|
| 4 | 3,243 | 2,630 | 2,219 | 1,885 |
| 16 | 30,717 | 17,271 | 5,779 | 5,484 |
| 64 | 78,935 | 58,597 | 18,324 | 17,614 |

Anchored matching is 2–5× faster than unanchored. Scaling is roughly linear
in pattern count.

## Fanout & Nested Quantifiers (µs/op, lower is better)

### Unicode Fanout: `(?:[\x{80}-\x{10FFFF}]?){100}[\x{80}-\x{10FFFF}]`

| Text Size | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go |
|--:|--:|--:|--:|--:|--:|--:|
| 1 KB | 0.55 | 0.83 | 105 | 3.0 | 0.047 | 1.4 |
| 10 KB | 0.55 | 0.83 | 104 | 32 | 0.047 | 3.6 |
| 100 KB | 0.57 | 0.84 | 106 | 447 | 0.047 | 3.5 |

SafeRE handles this pattern in ~0.56 µs regardless of text size. JDK's
backtracking engine quickly fails and returns false in ~0.84 µs. SafeRE is
**1.5× faster than JDK** and **184–190× faster than RE2/J** on this pattern.
C++ RE2 handles it trivially (~47 ns) thanks to its mature DFA. RE2-FFM
degrades sharply with text size (3 µs → 447 µs) due to increasing UTF-16 →
UTF-8 conversion cost on the FFM boundary. Go `regexp` handles it well
(~4 µs), much faster than RE2/J.

### Nested Quantifier: `(?:a?){20}a{20}`

| Text Size | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|--:|--:|--:|--:|--:|--:|--:|---|---|---|
| 1 KB | 3.0 | 15 | 374 | 1.4 | 1.1 | 184 | **5.0× faster** | **125× faster** | 2.1× slower |
| 10 KB | 27 | 139 | 3,806 | 14 | 11 | 2,139 | **5.2× faster** | **142× faster** | 1.9× slower |
| 100 KB | 277 | 1,465 | 37,525 | 140 | 106 | 22,167 | **5.3× faster** | **135× faster** | 2.0× slower |

SafeRE is significantly faster than both JDK and RE2/J here. RE2/J's NFA-only
approach is much slower than SafeRE's DFA on this high-fanout quantifier
pattern. SafeRE is within 2.6× of C++ RE2, showing both use the same
algorithmic approach. RE2-FFM is ~2× faster than SafeRE, tracking close to
native C++ RE2 with modest FFM overhead. Go `regexp` is similar to RE2/J
(NFA-only), both ~62–80× slower than SafeRE.

## Compilation Performance (µs/op, lower is better)

| Pattern | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|---|--:|--:|--:|--:|--:|--:|---|---|---|
| Simple (`hello`) | 0.46 | 0.09 | 0.27 | 3.90 | 1.48 | 0.87 | 5.1× slower | 1.7× slower | **8.5× faster** |
| Medium (datetime with 6 captures) | 4.76 | 0.31 | 2.12 | 23.52 | 6.47 | 5.88 | 15× slower | 2.2× slower | **4.9× faster** |
| Complex (email regex) | 2.64 | 0.23 | 1.14 | 11.59 | 4.52 | 2.41 | 12× slower | 2.3× slower | **4.4× faster** |
| Alternation (12 alternatives) | 3.60 | 0.41 | 2.96 | 21.16 | 7.67 | 5.69 | 8.8× slower | 1.2× slower | **5.9× faster** |

Lazy initialization defers OnePass analysis and DFA equivalence-class setup
to first match, reducing compile-time work to just parsing and program
compilation. SafeRE is now within 1.7× of RE2/J on simple patterns. JDK
defers most work to match time and remains the fastest compiler. SafeRE
compiles **4.4–8.5× faster than RE2-FFM**, which is the slowest due to FFM
call overhead plus C++ RE2's eager DFA setup. C++ RE2 compilation is
1.4–3.2× slower than SafeRE — C++ RE2 performs more eager work at compile
time (DFA setup, prefilter analysis). Go `regexp` compilation is comparable
overall and is faster than SafeRE only on the complex email pattern.

## Pathological Pattern: `a?{n}a{n}` matched against `a{n}`

This is the canonical backtracking blowup case. The JDK's backtracking NFA
exhibits O(2^n) behavior. SafeRE, RE2/J, and C++ RE2 all handle it in linear
time, but the DFA-based engines (SafeRE and C++ RE2) are far faster than
RE2/J's NFA.

### SafeRE vs RE2/J vs RE2-FFM vs C++ RE2 vs Go scalability (µs/op)

| n | SafeRE | RE2/J | RE2-FFM | C++ RE2 | Go | SafeRE/RE2/J | SafeRE/C++ |
|--:|--:|--:|--:|--:|--:|---|---|
| 10 | 0.043 | 1.68 | 0.068 | 0.054 | 0.880 | **39× faster** | ~same |
| 15 | 0.058 | 3.73 | 0.081 | 0.067 | 1.76 | **64× faster** | ~same |
| 20 | 0.070 | 6.61 | 0.089 | 0.073 | 2.95 | **94× faster** | ~same |
| 25 | 0.082 | 10.14 | 0.095 | 0.079 | 4.44 | **124× faster** | ~same |
| 30 | 0.094 | 14.53 | 0.100 | 0.085 | 6.62 | **155× faster** | 1.1× slower |
| 50 | 0.456 | 37.73 | 0.127 | 0.110 | 16.5 | **83× faster** | 4.1× slower |
| 100 | 0.933 | 145.9 | 0.191 | 0.172 | 66.3 | **156× faster** | 5.4× slower |

All four linear-time engines scale linearly. SafeRE and C++ RE2 (both
DFA-based) have the lowest growth rates at small n, though SafeRE shows higher
growth at large n (4.1× slower than C++ RE2 at n=50, 5.4× at n=100). RE2-FFM
tracks close to C++ RE2 with small FFM overhead. Go `regexp` and RE2/J (both
NFA-only) are slower than SafeRE: RE2/J by 39–156× and Go by 20–71×. Go
`regexp` is usually faster than RE2/J, reflecting Go's native-code advantage
over Java for NFA execution.

### Three-way comparison (µs/op, small n where JDK is feasible)

| n | SafeRE | RE2/J | JDK | RE2-FFM | SafeRE vs JDK |
|--:|--:|--:|--:|--:|--:|
| 10 | 0.042 | 1.72 | 9.3 | 0.074 | **222×** |
| 15 | 0.055 | 3.90 | 396 | 0.082 | **7,200×** |
| 20 | 0.070 | 6.64 | 15,316 | 0.090 | **218,803×** |
| 25 | — | — | *(hangs)* | — | ∞ |

## Find-in-Text Scaling (µs/op, lower is better)

`\b\w+ing\b` on repeated prose, scaling from 1 KB to 1 MB.

| Text Size | SafeRE | JDK | RE2/J | RE2-FFM | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|--:|--:|--:|--:|--:|--:|---|---|---|
| 1 KB | 8.7 | 7.4 | 49 | 12 | 31 | 1.2× slower | **5.7× faster** | **1.4× faster** |
| 10 KB | 84 | 73 | 475 | 139 | 308 | 1.2× slower | **5.6× faster** | **1.6× faster** |
| 100 KB | 821 | 719 | 4,599 | 8,201 | 4,145 | 1.1× slower | **5.6× faster** | **10× faster** |
| 1 MB | 8,444 | 7,045 | 46,825 | 1,495,616 | 43,831 | 1.2× slower | **5.5× faster** | **177× faster** |

SafeRE is **close to JDK** on find-all-matches scaling, within 1.2× at all
sizes. SafeRE is **5.5–5.7× faster than RE2/J** at all scales. SafeRE is
also **1.4–177× faster than RE2-FFM**, with the advantage growing dramatically
at larger sizes due to RE2-FFM's per-call FFM overhead. DFA caching,
word-boundary support, and the DFA sandwich optimization keep SafeRE
competitive with JDK.

**Note on RE2-FFM find-in-text scaling:** At 100 KB and above, RE2-FFM becomes
extremely slow (1.4 seconds at 1 MB) because the FFM shim performs UTF-16 →
UTF-8 conversion on every `find()` call. This is a limitation of the FFM
wrapper, not of C++ RE2 itself — native C++ RE2 handles this workload in ~19 µs
at 1 MB.

## Summary Statistics

We use the **geometric mean of speed ratios** (SafeRE time / competitor time)
as the single summary statistic for cross-benchmark comparison. Ratios < 1.0
mean SafeRE is faster.

**Why geometric mean?** It is the only mean that is consistent under inversion
— geomean(A/B) = 1/geomean(B/A) — so the "winner" doesn't change depending
on which direction you express the ratio. Arithmetic mean of ratios is biased
by outliers and inconsistent under inversion. Geometric mean treats
multiplicative improvements symmetrically: 2× faster and 2× slower cancel
to 1.0. This is the standard approach used in systems benchmarking (SPEC,
DaCapo, Renaissance).

**Caveats:** The geomean is only as meaningful as the benchmark suite is
representative. We report three geomeans to avoid conflating qualitatively
different workloads:

1. **Core micro workloads** — focused regex operations: literal match, character
   class match, alternation find, capture groups, find-in-text, email find,
   pig Latin replace, HTTP full request. These are useful for isolating engine
   behavior, but they are not a complete model of application regex use.
2. **Application workloads** — data-driven validation, parsing, extraction,
   scanning, and redaction cases from `ApplicationBenchmark`. These are intended
   to represent ordinary application usage more directly than the microbenchmarks.
3. **Pathological/scaling** — workloads that stress linear-time guarantees:
   `a?{n}a{n}` at n=20, `[ -~]*…$` hard search at 1 MB, nested quantifier
   `(?:a?){20}a{20}` at 100 KB. These are less common in everyday code but
   represent the safety guarantee that motivates the library.

Each individual ratio has measurement uncertainty (JMH reports ± error), but
we report geomean of point estimates, which is standard practice. Per-benchmark
ratios and confidence intervals are available in the detailed tables above.

### vs JDK (`java.util.regex`)

| Category | Geomean | Interpretation |
|---|--:|---|
| Core workloads (8 benchmarks) | 1.33 | **SafeRE is 1.3× slower overall** |
| Application workloads (8 benchmarks) | 1.71 | **SafeRE is 1.7× slower overall** |
| Pathological/scaling (3 benchmarks) | 0.000072 | **SafeRE is ~13,800× faster** |

On core workloads, SafeRE is 1.3× slower than JDK overall, driven mostly by
alternation find (4.1×), HTTP parsing (3.0×), and pig Latin replace (1.7×).
SafeRE wins on 4 of 8 core benchmarks: literal match, character-class match,
capture groups, and email find.

On application workloads, SafeRE is 1.7× slower than JDK overall. SafeRE wins
UUID validation, API route matching, and URL extraction, while JDK is faster on
log parsing, stack-trace extraction, case-insensitive keyword search, CSV
field scanning, and secret redaction. The pathological geomean reflects the
fundamental algorithmic difference: SafeRE guarantees linear time while JDK's
backtracking engine exhibits exponential blowup on adversarial patterns.

### vs RE2/J

| Category | Geomean | Interpretation |
|---|--:|---|
| Core workloads (8 benchmarks) | 0.106 | **SafeRE is 9.4× faster overall** |
| Application workloads (8 benchmarks) | 0.218 | **SafeRE is 4.6× faster overall** |
| Pathological/scaling (3 benchmarks) | 0.00034 | **SafeRE is ~2,910× faster** |

SafeRE beats RE2/J on every benchmark in the summary categories. Both
libraries provide linear-time guarantees, but SafeRE's DFA, OnePass, and
BitState engines provide a large constant-factor advantage over RE2/J's
NFA-only approach. The advantage is 9.4× on core workloads and 4.6× on
application workloads, where SafeRE wins every listed application case.

### vs RE2-FFM (C++ RE2 via FFM)

| Category | Geomean | Interpretation |
|---|--:|---|
| Core workloads (8 benchmarks) | 0.585 | **SafeRE is 1.7× faster overall** |
| Application workloads (8 benchmarks) | 1.06 | **SafeRE is roughly even overall** |
| Pathological/scaling (3 benchmarks) | 0.059 | **SafeRE is ~17.0× faster** |

On core workloads, SafeRE is 1.7× faster than RE2-FFM overall. SafeRE wins
decisively on literal match, character-class match, capture groups, HTTP
parsing, and several replacement workloads. RE2-FFM wins on alternation and
email find, where C++ RE2's native execution has an edge.

On application workloads, SafeRE and RE2-FFM are roughly even by geomean.
SafeRE wins UUID validation, API route matching, URL extraction, and CSV field
scanning; RE2-FFM wins log parsing, stack-trace extraction,
case-insensitive keyword search, and secret redaction.

On pathological/scaling workloads, SafeRE is now 17.0× faster overall —
a dramatic reversal from the previous 3.1× slower result. SafeRE's constant-time
Hard search rejection (0.020 µs vs RE2-FFM's 153 µs at 1 MB) is the main
driver. On the other two pathological benchmarks — `a?{20}a{20}` and nested
quantifiers — SafeRE and RE2-FFM are within 2× of each other, both scaling
linearly. RE2-FFM also pays FFM per-call overhead that grows with input size,
making it much slower than SafeRE on find-all-matches workloads at scale
(e.g., find-in-text at 1 MB: 1.50 seconds vs 8.4 ms).

## Analysis

**Where SafeRE wins (vs Java engines):**
- **Literal matching** — 1.2× faster than JDK, 12× faster than RE2/J
- **Character class matching** — 1.2× faster than JDK, 60× faster than RE2/J
- **Literal replacement** — Fastest of all engines (including C++ RE2!) on replaceFirst
- **Email find** — 1.4× faster than JDK, 6.7× faster than RE2/J
- **Digit replaceAll** — 1.5× faster than JDK, 16× faster than RE2/J
- **Find-in-text** — within 1.2× of JDK, 5.5–5.7× faster than RE2/J
- **Capture groups** — 7–12× faster than RE2/J, 1.2× faster than JDK at 10 groups
- **Hard search** — constant-time rejection (~0.020 µs at all sizes), 2,800–2.3M× faster than JDK, 1,700–1.9M× faster than RE2/J
- **Nested quantifiers** — 5.0–5.3× faster than JDK, 125–142× faster than RE2/J
- **Easy search on large text** — 1.7–1.8× faster than JDK, comparable to RE2/J
- **Medium search** — 56–65× faster than RE2/J
- **HTTP parsing** — 14–30× faster than RE2/J
- **Pathological `a?{n}a{n}`** — 222–219,000× faster than JDK, 39–156× faster than RE2/J

**Where JDK wins:**
- **HTTP parsing** — 3.0× faster (SafeRE's OnePass engine has higher per-character
  overhead on anchored patterns, though the gap narrowed from 14× to 3.0× with
  the HTTP/OnePass fast path optimization)
- **Pig Latin replace** — 1.7× faster (per-match capture extraction cost in multi-match replaceAll)
- **Empty-match replace** — JDK is 3.6× faster
- **Compilation** — 5.1–15× faster (defers work to match time)

**Where RE2/J fits:**
- RE2/J is **slower than both SafeRE and JDK** on every matching benchmark
- RE2/J lacks DFA, OnePass, and BitState engines — only has NFA (Pike VM)
- RE2/J provides linear-time safety like SafeRE, but without the DFA
  performance advantage
- RE2/J compilation is 1.2–2.2× faster than SafeRE but 3–5× slower than JDK

**RE2-FFM (C++ RE2 via FFM):**
- RE2-FFM provides C++ RE2's algorithmic strength (DFA, reverse DFA) from Java
  via the Foreign Function & Memory API
- On short inputs (< 1 KB), RE2-FFM is competitive with SafeRE and sometimes
  faster (e.g., alternation find at 615 ns vs 2,207 ns)
- On large inputs, FFM per-call overhead becomes significant — especially for
  find-all-matches workloads where each `find()` call crosses the JNI/FFM
  boundary with UTF-16 → UTF-8 conversion
- Compilation is 4–6× slower than SafeRE due to FFM call overhead plus C++
  RE2's eager DFA setup
- SafeRE now beats RE2-FFM on the Hard search pattern (0.020 µs vs 153 µs
  at 1 MB) and is 17.0× faster overall on pathological/scaling workloads

**SafeRE vs C++ RE2:**
- C++ RE2 is typically **2–20× faster** on matching due to native code, UTF-8
  encoding (shorter strings), and no GC/JIT overhead
- **Hard search** — SafeRE is now faster than C++ RE2 (0.020 vs 0.044 µs),
  achieving constant-time rejection by detecting the required literal suffix
  cannot occur
- **Compilation** — SafeRE compiles **faster** than C++ RE2 thanks to lazy
  initialization, while C++ RE2 performs more eager DFA setup at compile time
- **Pathological patterns** — SafeRE is within 1.0–4.1× of C++ RE2, confirming
  the DFA implementation is correct and efficient
- **Literal replacement** — SafeRE is **faster** than C++ RE2 (30 vs 98 ns for
  replaceFirst), demonstrating Java's `String.indexOf()` optimization
- **Nested quantifiers** — SafeRE is within 2.6× of C++ RE2 (277 vs 106 µs at
  100 KB), both scaling linearly

**SafeRE vs Go `regexp`:**
- Go `regexp` is an NFA-only implementation (no DFA), like RE2/J
- SafeRE **beats Go on DFA-dominated workloads**: pathological (7–152× faster),
  nested quantifiers (61–80× faster), hard search (880–1.2M× faster)
- Go beats SafeRE on short-string matching and compilation (no DFA overhead)
- Go `regexp` is consistently ~2–3× faster than RE2/J across the board,
  reflecting Go's native-code advantage over Java for NFA execution

**Key takeaway:** SafeRE is the fastest RE2-family engine on the JVM —
**1.7× faster than RE2-FFM** and **9.4× faster than RE2/J** on core
workloads by geomean. SafeRE is within a small constant factor of the
C++ original and significantly faster than both Go `regexp` and RE2/J on
DFA-dominated workloads. On core workloads, SafeRE is **1.3× slower than
JDK by geomean** (driven by alternation find at 4.1×, HTTP parsing at 3.0×,
and pig Latin replace at 1.7×), while providing **guaranteed linear time**
that JDK cannot offer.
On pathological/scaling workloads, SafeRE is **13,800× faster than JDK** and
**17.0× faster than RE2-FFM** — the latter a reversal from the previous 3.1×
disadvantage, driven by the new constant-time Hard search rejection.

**The tradeoff:** SafeRE trades higher per-match overhead on HTTP-style
anchored patterns (3.0× slower than JDK) for **guaranteed linear time** and
**better scaling** on large inputs and pathological patterns. For
safety-critical applications (user-supplied regexes, large documents, content
filtering), SafeRE eliminates the risk of catastrophic backtracking while being
substantially faster than all other RE2-family implementations except
the C++ original.

**Impact of fork mode:** These results were collected with JMH fork mode
(3 forks per benchmark), which starts fresh JVMs to avoid JIT profile
pollution between benchmarks and samples fork-to-fork variance. Pathological
benchmark classes intentionally run without forking because some JDK cases can
hang at larger input sizes.

## Optimizations Applied

1. **DFA caching per Matcher** — DFA state cache persists across `find()` calls,
   avoiding full DFA reconstruction per search.
2. **ASCII fast path in DFA** — Pre-computed 128-entry lookup table for character
   class mapping, avoiding binary search for ASCII text.
3. **Pre-allocated DFA expand() arrays** — Reuses visited/stack/frontier arrays
   instead of allocating on each state expansion.
4. **Start position threading** — DFA, NFA, and BitState accept a `startPos`
   parameter, eliminating substring creation for the DFA check.
5. **Search limit support** — BitState and NFA accept a `searchLimit` parameter
   bounding where new search threads start.
6. **Literal fast path** — Fully literal patterns bypass all regex engines and use
   `String.indexOf()` / `String.equals()` directly.
7. **Reverse DFA bounding** — Three-DFA sandwich: forward DFA finds match end,
   reverse DFA finds match start, then NFA runs on just the match range.
8. **OnePass 64-bit action encoding** — Raised capture group limit from 6 to 16
   by switching from 32-bit to 64-bit action words.
9. **DFA word boundary support** — Native `\b`/`\B` handling in the DFA avoids
   NFA fallback for word-boundary patterns.
10. **Skip reverse DFA for anchored patterns** — Anchored patterns skip reverse
    program compilation entirely, saving ~2 µs per Pattern.
11. **Lazy reverse program compilation** — Reverse program is compiled on first
    access rather than eagerly, avoiding cost for patterns that never need it.
12. **Pre-allocated DFA computeNext() workspace** — Reuses seed/successor arrays
    instead of allocating per-transition.
13. **BitState fast path for small texts** — Texts ≤256 chars skip DFA construction
    and use BitState directly, avoiding ~500ns DFA overhead per Matcher.
14. **OnePass submatch in sandwich path** — Uses OnePass for capture extraction
    when pattern is eligible, avoiding BitState/NFA overhead.
15. **Character-class prefix acceleration** — Patterns starting with an ASCII
    character class get a boolean[128] bitmap for fast text scanning, skipping
    positions that cannot start a match.
16. **DFA setup sharing** — Equivalence class boundaries and ASCII class map are
    pre-computed at Pattern.compile() time and shared across all Matcher
    instances, saving ~1.7 µs per Matcher creation.
17. **DFA start state caching** — Caches start states by position context
    (beginning-of-text, beginning-of-line, etc.), eliminating expand()
    allocation on repeated DFA searches.
18. **Lazy OnePass/DFA analysis** — Defers OnePass.build() and Dfa.buildSetup()
    from compile time to first use, improving compilation 2–5×.
19. **Lazy capture extraction** — Defers capture-group resolution from find()
    to first access of start()/end()/group(), avoiding BitState/NFA overhead
    when only match presence is needed.
20. **CHAR_CLASS instruction** — Single bytecode instruction for character
    classes with precomputed ASCII bitmap, replacing multi-instruction
    range chains.
21. **Pattern-level DFA caching** — ThreadLocal DFA instances shared across
    all Matchers for the same Pattern, preserving warm state caches.
22. **DFA sandwich for alternation** — Uses `dfaStartReliable()` to enable
    DFA range-narrowing even for alternation and bounded-repeat patterns.
23. **Character-class match fast path** — Patterns like `[a-zA-Z]+` bypass
    the full engine cascade in `matches()` with a tight bitmap scanning loop.
24. **Character-class replaceAll fast path** — Patterns like `\d+` with simple
    replacements (no group refs) bypass all engines with a single-pass scan.
25. **Compiled replacement template** — Pre-parses replacement strings with group
    references (`$1`, `$2`) into a template, eliminating per-match `parseInt`,
    `substring`, and per-character scanning overhead.
26. **Direct BitState find+capture** — For `replaceAll` with group references on
    short text, bypasses the DFA sandwich and runs BitState once per match for
    combined find + capture, eliminating 3 DFA passes per match.
27. **Cached DFA references in Matcher** — Caches forward/reverse DFA in Matcher
    fields to avoid repeated ThreadLocal lookups in find-all loops.
28. **Reverse DFA / end-anchor optimization** — Patterns ending with `$` and a
    required literal suffix are detected at compile time; the DFA checks for
    the suffix's presence before scanning, achieving constant-time rejection
    for non-matching inputs.
29. **HTTP/OnePass fast path** — Improved anchored pattern handling in the OnePass
    engine, reducing per-character overhead for patterns like
    `^(?:GET|POST) +([^ ]+) HTTP` from 1,292 to 269 ns/op.

## Remaining Opportunities

- **HTTP parsing overhead** — HTTP patterns are now 3.0× slower than JDK
  (improved from 14× with the HTTP/OnePass fast path). The remaining gap is
  OnePass per-character overhead on the 97-char request; JDK's backtracking
  engine has lower per-match setup cost on short anchored patterns.
- **Pig Latin / complex replace** — 1.7× slower than JDK. The gap is
  fundamental: SafeRE uses BitState (multi-state exploration) per match while
  JDK does a single backtracking pass. Compiled replacement templates and
  direct BitState already reduced this from 2.2× to 1.7×.
- **Empty-match replace** — 3.6× slower than JDK. Empty-match handling requires
  careful position advancement and is a known gap.
- **Compilation** — Pattern compilation is 5.1–15× slower than JDK. Opportunities
  include caching parsed Regexp trees.
- **DFA state budget tuning** — The default 10,000-state budget may be
  suboptimal for some pattern/text combinations.

---

## Memory Usage

Memory metrics complement the throughput data above. Unlike time-based
benchmarks, memory measurements are deterministic — they count bytes, not
time — and are not affected by other processes on the machine.

### Methodology

**Per-match allocation rate** (`gc.alloc.rate.norm`): Measured via JMH's
built-in GC profiler (`-prof gc`). Reports bytes allocated per operation,
normalized via TLAB accounting. This is the most actionable memory metric: it
directly determines GC pressure in production workloads.

**Compiled pattern size**: Measured via the heap-delta technique: allocate 500
instances of a compiled pattern, force GC, measure heap growth, and divide by
instance count. Seven trials are run and the median is reported. Run with
`-Xms256m -Xmx256m` for fixed heap to reduce noise.

**DFA cache growth**: For SafeRE only. Compiles a pattern, measures heap before
and after matching against a large text (which lazily populates DFA states).
Multiple trials, median reported.

**Cross-language memory**: C++ RE2 uses `mallinfo2()` heap delta around pattern
compilation and `RE2::ProgramSize()` for bytecode instruction count. Go uses
`runtime.MemStats` heap delta. These numbers are not directly comparable to
Java's due to different allocation strategies (manual vs GC-managed), but
provide order-of-magnitude context.

### Per-Match Allocation Rate (bytes/op)

| Benchmark | SafeRE (B/op) | JDK (B/op) | RE2/J (B/op) | RE2-FFM (B/op) |
|---|--:|--:|--:|--:|
| literalMatch | 96 | 56 | 72 | 48 |
| charClassMatch | 104 | 56 | 88 | 80 |
| alternationFind | 336 | 136 | 208 | 512 |
| captureGroups | 368 | 368 | 432 | 408 |
| findInText | 2,088 | 136 | 976 | 2,448 |
| emailFind | 176 | 136 | 88 | 224 |

SafeRE allocates more per match than JDK on most workloads due to Matcher
state objects and DFA workspace arrays. The highest allocation rate is
findInText (2,088 B/op) where repeated `find()` calls accumulate Matcher and
capture state. RE2-FFM allocates similarly to SafeRE on complex patterns due
to FFM memory segment management, but uses less on simple literal matches.

### Compiled Pattern Size (bytes, retained heap)

| Pattern | SafeRE | JDK | RE2/J |
|---|--:|--:|--:|
| simple | 1,348 | 756 | 652 |
| medium | 5,212 | 940 | 1,692 |
| complex | 2,620 | 1,204 | 844 |
| alternation | 6,580 | 964 | 3,500 |

**Interpretation:** SafeRE patterns are larger than JDK patterns because they
include a compiled `Prog` (bytecode), pre-built character class bitmaps, and
other structures needed for linear-time matching. JDK patterns defer most
compilation work to match time. RE2/J's sizes fall between the two.

### Memory Scaling with Input Size

How per-match allocation scales with text size (Easy and Medium patterns).

**Easy pattern:** `ABCDEFGHIJKLMNOPQRSTUVWXYZ$`

| Text size | SafeRE (B/op) | JDK (B/op) | RE2/J (B/op) |
|---|--:|--:|--:|
| 1 KB | 80 | 56 | 48 |
| 10 KB | 80 | 56 | 48 |
| 100 KB | 80 | 56 | 48 |
| 1 MB | 80 | 58 | 48 |

**Medium pattern:** `[XYZ]ABCDEFGHIJKLMNOPQRSTUVWXYZ$`

| Text size | SafeRE (B/op) | JDK (B/op) | RE2/J (B/op) |
|---|--:|--:|--:|
| 1 KB | 80 | 56 | 48 |
| 10 KB | 80 | 56 | 81 |
| 100 KB | 80 | 56 | 51 |
| 1 MB | 81 | 98 | 153 |

All three engines show near-constant allocation across input sizes, indicating
streaming behavior. SafeRE stays near 80 B/op for these search workloads.

### DFA Cache Growth (SafeRE only)

| Pattern | Cache growth (bytes) |
|---|--:|
| simple | 80 |
| medium | 80 |
| complex | 16,344 |
| alternation | 3,176 |

SafeRE lazily builds DFA states during matching. The cache is bounded by
`maxStates` (default 10,000 states). Simple patterns that use the literal
fast path or OnePass engine may not populate the DFA cache at all. Complex
patterns with large character classes (e.g., email) grow the cache
significantly as the DFA explores more state transitions.

### C++ RE2 Memory (heap bytes)

| Benchmark | heapBytes |
|---|--:|
| compileSimple | 144 |
| compileMedium | 1,568 |
| compileComplex | 608 |
| compileAlternation | 1,696 |
| literalMatch | 144 |
| charClassMatch | 32 |
| alternationFind | 2,912 |
| captureGroups | 816 |
| findInText | 192 |
| emailFind | 576 |

C++ RE2 uses manual memory management with minimal overhead. Compiled pattern
sizes (144–1,696 bytes) are much smaller than Java equivalents due to the
absence of object headers, vtable pointers, and GC metadata.

### Go Memory (heap bytes)

| Benchmark | heapBytes |
|---|--:|
| compileSimple | 1,384 |
| compileMedium | 9,408 |
| compileComplex | 3,640 |
| compileAlternation | 9,496 |
| literalMatch | 1,384 |
| charClassMatch | 888 |
| alternationFind | 5,688 |
| captureGroups | 5,672 |
| findInText | 2,616 |
| emailFind | 3,640 |

Go's compiled pattern sizes (1,384–9,496 bytes) fall between C++ RE2 and
SafeRE, reflecting Go's GC-managed allocator with per-object overhead similar
to Java but lighter than Java's full object model.

---
*Last updated: 2026-03-31 (rerun all Java benchmarks after OnePass capture optimization)*
