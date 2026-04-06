# SafeRE Benchmark Results

Comparing SafeRE against `java.util.regex` (JDK),
[RE2/J](https://github.com/google/re2j) 1.8, RE2-FFM (C++ RE2 via Java
[FFM API](https://openjdk.org/jeps/454)), C++ RE2 (2024-07-02), and
Go [`regexp`](https://pkg.go.dev/regexp) (Go 1.26.1) on common regex
workloads and pathological patterns that demonstrate backtracking blowup.

**Environment:**
- CPU: Intel Core i7-11700K (8 cores / 16 threads, 3.6 GHz base)
- RAM: 32 GB
- OS: Ubuntu 24.04.4 LTS on WSL2 (kernel 6.6.87.2-microsoft-standard-WSL2),
  Windows 11 host
- JDK: OpenJDK 25.0.2+10-69 (targeting Java 21)
- JMH: 1.37, fork mode (5 forks, full JMH defaults)
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

**Running benchmarks:**

```bash
# Java benchmarks — always use the wrapper script (runs `mvn install` first)
./run-java-benchmarks.sh                        # all benchmarks
./run-java-benchmarks.sh CaptureScalingBenchmark  # specific class

# Java memory profiling — allocation rates (bytes/op) via JMH GC profiler
./run-java-memory-benchmarks.sh                        # all benchmarks with -prof gc
./run-java-memory-benchmarks.sh RegexBenchmark         # specific class

# Java compiled pattern sizes — standalone measurement
java -Xms256m -Xmx256m -cp safere-benchmarks/target/benchmarks.jar \
  org.safere.benchmark.MemoryBenchmark

# C++ RE2 benchmarks
./run-cpp-benchmarks.sh                    # all C++ benchmarks
./run-cpp-benchmarks.sh Regex Compile      # specific benchmark groups

# Go regexp benchmarks
./run-go-benchmarks.sh                     # all Go benchmarks
./run-go-benchmarks.sh Regex Compile       # specific benchmark groups
```

## Methodology

All benchmarks use a warmup-then-measure approach, but the settings differ
between Java and native harnesses to account for their different runtime
characteristics.

**Java (JMH):** All JMH defaults — 5 forks × (5 warmup × 10s + 5 measurement
× 10s). Each fork starts a **fresh JVM process**, which is critical because
the JIT compiler is non-deterministic — different runs may make different
inlining and optimization decisions based on profiling data. Five forks sample
this variance so results reflect typical JIT behavior rather than one lucky
(or unlucky) compilation. The generous warmup (50s per fork) ensures the JIT
completes tiered compilation (C1 → C2) and reaches steady state before
measurement begins. Total: 25 samples from 5 independent JVMs, ~8 minutes per
benchmark method.

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
a single `benchmark-data.json` file, ensuring identical workloads across
languages.

| Setting | Java (JMH) | C++ | Go |
|---|---|---|---|
| Warmup | 5 × 10s per fork | 2 × 2s | 2 × 2s |
| Measurement | 5 × 10s per fork | 10 × 2s | 10 × 2s |
| Forks | 5 (fresh JVM each) | 1 (single process) | 1 (single process) |
| Total samples | 25 | 10 | 10 |
| Optimization | JIT (steady-state) | `-O3 -DNDEBUG` | Go default |

## Matching Performance (ns/op, lower is better)

| Benchmark | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|---|--:|--:|--:|--:|--:|--:|---|---|---|
| Literal match (`"hello"`) | 9 | 13 | 126 | 55 | 40 | 78 | **1.4× faster** | **14× faster** | **6.1× faster** |
| Char class match (`[a-zA-Z]+`) | 18 | 24 | 1,225 | 113 | 85 | 395 | **1.3× faster** | **68× faster** | **6.3× faster** |
| Alternation find (`foo\|bar\|…` ×8) | 812 | 526 | 4,266 | 604 | 19 | 1,784 | 1.5× slower | **5.3× faster** | 1.3× slower |
| Capture groups (`(\d{4})-(\d{2})-(\d{2})`) | 74 | 84 | 575 | 320 | 77 | 241 | **1.1× faster** | **7.8× faster** | **4.3× faster** |
| Find -ing words in prose (~350 chars) | 3,114 | 2,931 | 19,402 | 4,237 | 19 | 12,975 | ~same | **6.2× faster** | **1.4× faster** |
| Email pattern find | 259 | 395 | 1,910 | 223 | 89 | 544 | **1.5× faster** | **7.4× faster** | 1.2× slower |

SafeRE **matches or beats JDK** on 5 of 6 core matching benchmarks, with the
remaining one within 1.5×. Capture groups is now a SafeRE win (74 vs 84 ns/op,
1.1× faster) thanks to the OnePass optimization. The character-class match fast
path (precomputed ASCII bitmap loop) delivers 18 ns — 1.3× faster than JDK and
68× faster than RE2/J. Email find beats JDK by 1.5× thanks to DFA caching and
the DFA sandwich optimization. SafeRE also beats RE2-FFM on 4 of 6
benchmarks — by 6.1× on literal match, 6.3× on character class, 4.3× on
capture groups, and 1.4× on find-in-text — losing only on alternation (1.3×)
and email find (1.2×) where RE2-FFM's native code has an edge. C++ RE2 remains
the fastest on alternation and find-in-text workloads due to native code and
UTF-8 encoding.

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
| 1 KB | 0.09 | 0.16 | 0.10 | 0.16 | 0.09 | **1.8× faster** | ~same | **1.8× faster** |
| 10 KB | 0.81 | 1.41 | 0.82 | 1.05 | 0.24 | **1.7× faster** | ~same | **1.3× faster** |
| 100 KB | 8.0 | 14.0 | 8.0 | 10.7 | 2.3 | **1.8× faster** | ~same | **1.3× faster** |
| 1 MB | 82 | 143 | 82 | 151 | 24 | **1.7× faster** | ~same | **1.8× faster** |

### Medium: `[XYZ]ABCDEFGHIJKLMNOPQRSTUVWXYZ$` (starts with char class)

| Text Size | SafeRE | JDK | RE2/J | RE2-FFM | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|--:|--:|--:|--:|--:|--:|---|---|---|
| 1 KB | 0.39 | 0.26 | 21.9 | 0.16 | 14 | 1.5× slower | **56× faster** | 2.4× slower |
| 10 KB | 3.8 | 2.5 | 230 | 1.1 | 178 | 1.5× slower | **61× faster** | 3.5× slower |
| 100 KB | 37 | 24 | 2,397 | 11 | 1,779 | 1.5× slower | **65× faster** | 3.4× slower |
| 1 MB | 385 | 249 | 24,426 | 152 | 18,641 | 1.5× slower | **63× faster** | 2.5× slower |

Character-class prefix acceleration allows SafeRE to scan directly for
`[XYZ]` before invoking the DFA, reducing the gap with JDK to just 1.5×.
SafeRE is **56–65× faster than RE2/J** on this pattern. RE2-FFM benefits
from C++ RE2's reverse DFA, achieving near-constant time and beating SafeRE
by 2.4–3.5×.

### Hard: `[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$` (catastrophic in backtracking engines)

| Text Size | SafeRE | JDK | RE2/J | RE2-FFM | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|--:|--:|--:|--:|--:|--:|---|---|---|
| 1 KB | 0.018 | 76 | 37 | 0.17 | 19 | **4,200× faster** | **2,100× faster** | **9.4× faster** |
| 10 KB | 0.018 | 434 | 373 | 1.1 | 250 | **24,000× faster** | **21,000× faster** | **61× faster** |
| 100 KB | 0.018 | 4,615 | 3,718 | 11 | 2,480 | **256,000× faster** | **207,000× faster** | **611× faster** |
| 1 MB | 0.019 | 43,773 | 37,857 | 152 | 25,445 | **2.3M× faster** | **2.0M× faster** | **8,000× faster** |

SafeRE achieves **constant-time rejection** (0.018–0.019 µs regardless of text
size) on this pattern, similar to C++ RE2's reverse DFA optimization. SafeRE
detects at compile time that the required literal suffix `ABCDEFGHIJKLMNOPQRSTUVWXYZ`
cannot occur in random ASCII text and short-circuits before scanning. This is
even faster than C++ RE2 (0.048 µs) and RE2-FFM, which must still invoke the
reverse DFA. JDK's backtracking engine exhibits O(n²) behavior due to the
leading `[ -~]*`, making SafeRE millions of times faster at 1 MB. RE2/J and
Go `regexp` handle it in linear time but are still orders of magnitude slower
than SafeRE's constant-time path.

### Successful Search (Easy pattern, match at end of text)

| Text Size | SafeRE | JDK | RE2/J | RE2-FFM | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|--:|--:|--:|--:|--:|--:|---|---|---|
| 1 KB | 0.32 | 0.19 | 0.79 | 0.24 | 0.22 | 1.7× slower | **2.5× faster** | 1.3× slower |
| 10 KB | 1.1 | 1.5 | 1.5 | 1.1 | 0.74 | **1.4× faster** | **1.4× faster** | ~same |
| 100 KB | 8.4 | 14.9 | 8.9 | 10.8 | 2.8 | **1.8× faster** | ~same | **1.3× faster** |
| 1 MB | 83 | 150 | 83 | 153 | 25 | **1.8× faster** | ~same | **1.8× faster** |

SafeRE has higher per-match startup cost but scales well; it overtakes JDK
at 10 KB. DFA caching keeps the 1 KB case at 0.32 µs. RE2-FFM follows a
similar pattern — 1.3× faster than SafeRE at 1 KB but 1.8× slower at 1 MB,
as FFM per-call overhead grows with input size.

## Capture Group Scaling (ns/op, lower is better)

| Groups | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|--:|--:|--:|--:|--:|--:|--:|---|---|---|
| 0 | 56 | 31 | 392 | 73 | 61 | 160 | 1.8× slower | **7.0× faster** | **1.3× faster** |
| 1 | 72 | 41 | 879 | 284 | 68 | 242 | 1.8× slower | **12× faster** | **3.9× faster** |
| 3 | 94 | 75 | 958 | 329 | 84 | 311 | 1.3× slower | **10× faster** | **3.5× faster** |
| 10 | 200 | 235 | 1,398 | 737 | 367 | 600 | **1.2× faster** | **7.0× faster** | **3.7× faster** |

SafeRE closes the gap with JDK as capture count grows — from 1.8× slower at
0 groups to **1.2× faster at 10 groups**. SafeRE is consistently **7–12×
faster than RE2/J** and **3.5–3.9× faster than RE2-FFM** on capture extraction
(at 1+ groups). The RE2-FFM gap reflects FFM call overhead on top of C++ RE2's
capture engine. At 0 groups (no captures), SafeRE is 1.3× faster than RE2-FFM.
C++ RE2 matches SafeRE at 10 groups — both use OnePass engines that scale
similarly with group count. Go `regexp` is 1.5–2.1× slower than SafeRE,
consistent with its NFA-only approach.

## HTTP Request Parsing (ns/op, lower is better)

Pattern: `^(?:GET|POST) +([^ ]+) HTTP`

| Input | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|---|--:|--:|--:|--:|--:|--:|---|---|---|
| Full request (97 chars) | 276 | 90 | 8,218 | 389 | 324 | 982 | 3.1× slower | **30× faster** | **1.4× faster** |
| Small request (18 chars) | 66 | 49 | 929 | 139 | 72 | 247 | 1.3× slower | **14× faster** | **2.1× faster** |
| Extract URL (97 chars) | 279 | 94 | 8,177 | 386 | 321 | 974 | 3.0× slower | **29× faster** | **1.4× faster** |

SafeRE's HTTP parsing improved significantly from 1,292 to 276 ns/op thanks to
the HTTP/OnePass fast path optimization. SafeRE is now within 3.1× of JDK on
full HTTP requests (previously 14×) and is faster than C++ RE2 (276 vs
324 ns). SafeRE remains **14–30× faster than RE2/J** on all HTTP workloads.
RE2-FFM is slightly slower than SafeRE on this benchmark, with SafeRE 1.4×
faster on full and extract, and 2.1× faster on small request patterns.

## Replace Performance (ns/op, lower is better)

| Benchmark | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|---|--:|--:|--:|--:|--:|--:|---|---|---|
| Literal replaceFirst (`"b"→"bb"`) | 30 | 40 | 147 | 215 | 98 | 605 | **1.3× faster** | **4.9× faster** | **7.2× faster** |
| Literal replaceAll | 105 | 105 | 706 | 688 | 406 | 495 | ~same | **6.7× faster** | **6.6× faster** |
| Pig Latin replaceAll (backrefs) | 1,415 | 838 | 8,347 | 2,342 | 1,875 | 2,990 | 1.7× slower | **5.9× faster** | **1.7× faster** |
| Digit replaceAll (`\d+`→`"NUM"`) | 194 | 292 | 3,100 | 974 | 644 | 1,612 | **1.5× faster** | **16× faster** | **5.0× faster** |
| Empty-match replaceAll (`a*`) | 243 | 76 | 398 | 648 | 368 | 353 | 3.2× slower | **1.6× faster** | **2.7× faster** |

SafeRE wins on literal replacements — **faster than all other engines**
(including C++ RE2!) on replaceFirst (30 ns), thanks to the `String.indexOf()`
fast path. Digit replaceAll is **1.5× faster than JDK** thanks to the
character-class replaceAll fast path. Pig Latin replaceAll runs at 1,415 ns
via compiled replacement templates and direct BitState find+capture. SafeRE
beats RE2-FFM on every replace benchmark by **1.7–7.2×**, as repeated FFM
round-trips per match add significant overhead. For empty-match replacement,
JDK remains fastest. Go `regexp` is consistently faster than RE2/J on
replacements.

## PatternSet Multi-Pattern Matching (ns/op)

Matching text against multiple compiled patterns simultaneously (SafeRE-only
feature — neither JDK nor RE2/J has a built-in multi-pattern API).

| Patterns | Unanchored (match) | Unanchored (no match) | Anchored (match) | Anchored (no match) |
|--:|--:|--:|--:|--:|
| 4 | 2,989 | 2,285 | 1,858 | 1,638 |
| 16 | 30,033 | 17,532 | 5,384 | 5,102 |
| 64 | 111,646 | 85,093 | 18,274 | 17,827 |

Anchored matching is 2–5× faster than unanchored. Scaling is roughly linear
in pattern count.

## Fanout & Nested Quantifiers (µs/op, lower is better)

### Unicode Fanout: `(?:[\x{80}-\x{10FFFF}]?){100}[\x{80}-\x{10FFFF}]`

| Text Size | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go |
|--:|--:|--:|--:|--:|--:|--:|
| 1 KB | 0.55 | 0.85 | 108 | 3.0 | 0.046 | 1.4 |
| 10 KB | 0.55 | 0.84 | 106 | 31 | 0.046 | 3.7 |
| 100 KB | 0.57 | 0.84 | 109 | 451 | 0.046 | 3.7 |

SafeRE handles this pattern in ~0.56 µs regardless of text size. JDK's
backtracking engine quickly fails and returns false in ~0.84 µs. SafeRE is
**1.5× faster than JDK** and **193–198× faster than RE2/J** on this pattern.
C++ RE2 handles it trivially (~46 ns) thanks to its mature DFA. RE2-FFM
degrades sharply with text size (3 µs → 451 µs) due to increasing UTF-16 →
UTF-8 conversion cost on the FFM boundary. Go `regexp` handles it well
(~4 µs), much faster than RE2/J.

### Nested Quantifier: `(?:a?){20}a{20}`

| Text Size | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|--:|--:|--:|--:|--:|--:|--:|---|---|---|
| 1 KB | 3.0 | 15 | 377 | 1.5 | 1.1 | 186 | **5.0× faster** | **126× faster** | 2.0× slower |
| 10 KB | 27 | 151 | 3,760 | 14 | 11 | 2,225 | **5.6× faster** | **139× faster** | 1.9× slower |
| 100 KB | 277 | 1,402 | 37,633 | 141 | 108 | 21,858 | **5.1× faster** | **136× faster** | 2.0× slower |

SafeRE is significantly faster than both JDK and RE2/J here. RE2/J's NFA-only
approach is much slower than SafeRE's DFA on this high-fanout quantifier
pattern. SafeRE is within 2.6× of C++ RE2, showing both use the same
algorithmic approach. RE2-FFM is ~2× faster than SafeRE, tracking close to
native C++ RE2 with modest FFM overhead. Go `regexp` is similar to RE2/J
(NFA-only), both ~62–79× slower than SafeRE.

## Compilation Performance (µs/op, lower is better)

| Pattern | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|---|--:|--:|--:|--:|--:|--:|---|---|---|
| Simple (`hello`) | 0.44 | 0.09 | 0.27 | 3.39 | 1.56 | 0.89 | 4.9× slower | 1.6× slower | **7.7× faster** |
| Medium (datetime with 6 captures) | 4.65 | 0.31 | 2.12 | 10.44 | 6.94 | 6.36 | 15× slower | 2.2× slower | **2.2× faster** |
| Complex (email regex) | 2.47 | 0.22 | 1.12 | 6.93 | 4.69 | 2.49 | 11× slower | 2.2× slower | **2.8× faster** |
| Alternation (12 alternatives) | 3.55 | 0.41 | 3.01 | 12.33 | 8.22 | 6.12 | 8.7× slower | 1.2× slower | **3.5× faster** |

Lazy initialization defers OnePass analysis and DFA equivalence-class setup
to first match, reducing compile-time work to just parsing and program
compilation. SafeRE is now within 1.6× of RE2/J on simple patterns. JDK
defers most work to match time and remains the fastest compiler. SafeRE
compiles **2.2–7.7× faster than RE2-FFM**, which is the slowest due to FFM
call overhead plus C++ RE2's eager DFA setup. C++ RE2 compilation is 1.5–2×
*slower* than SafeRE — C++ RE2 performs more eager work at compile time (DFA
setup, prefilter analysis). Go `regexp` compiles faster than SafeRE (no DFA
construction up front).

## Pathological Pattern: `a?{n}a{n}` matched against `a{n}`

This is the canonical backtracking blowup case. The JDK's backtracking NFA
exhibits O(2^n) behavior. SafeRE, RE2/J, and C++ RE2 all handle it in linear
time, but the DFA-based engines (SafeRE and C++ RE2) are far faster than
RE2/J's NFA.

### SafeRE vs RE2/J vs RE2-FFM vs C++ RE2 vs Go scalability (µs/op)

| n | SafeRE | RE2/J | RE2-FFM | C++ RE2 | Go | SafeRE/RE2/J | SafeRE/C++ |
|--:|--:|--:|--:|--:|--:|---|---|
| 10 | 0.042 | 1.72 | 0.068 | 0.055 | 0.886 | **41× faster** | ~same |
| 15 | 0.055 | 3.73 | 0.082 | 0.069 | 1.82 | **68× faster** | ~same |
| 20 | 0.072 | 6.64 | 0.092 | 0.071 | 3.07 | **92× faster** | ~same |
| 25 | 0.090 | 10.15 | 0.099 | 0.078 | 4.79 | **113× faster** | 1.2× slower |
| 30 | 0.108 | 14.76 | 0.104 | 0.084 | 6.63 | **137× faster** | 1.3× slower |
| 50 | 0.371 | 38.49 | 0.125 | 0.108 | 17.0 | **104× faster** | 3.4× slower |
| 100 | 0.705 | 148.3 | 0.191 | 0.172 | 64.9 | **210× faster** | 4.1× slower |

All four linear-time engines scale linearly. SafeRE and C++ RE2 (both
DFA-based) have the lowest growth rates at small n, though SafeRE shows higher
growth at large n (3.4× slower than C++ RE2 at n=50, 4.1× at n=100). RE2-FFM
tracks close to C++ RE2 with small FFM overhead. Go `regexp` and RE2/J (both
NFA-only) are 19–210× slower than SafeRE. Go `regexp` is ~2.5× faster than
RE2/J, reflecting Go's native-code advantage over Java for NFA execution.

### Three-way comparison (µs/op, small n where JDK is feasible)

| n | SafeRE | RE2/J | JDK | RE2-FFM | SafeRE vs JDK |
|--:|--:|--:|--:|--:|--:|
| 10 | 0.042 | 1.72 | 9.5 | 0.072 | **226×** |
| 15 | 0.058 | 3.74 | 388 | 0.082 | **6,690×** |
| 20 | 0.073 | 6.77 | 15,389 | 0.094 | **210,808×** |
| 25 | — | — | *(hangs)* | — | ∞ |

## Find-in-Text Scaling (µs/op, lower is better)

`\b\w+ing\b` on repeated prose, scaling from 1 KB to 1 MB.

| Text Size | SafeRE | JDK | RE2/J | RE2-FFM | Go | vs JDK | vs RE2/J | vs RE2-FFM |
|--:|--:|--:|--:|--:|--:|---|---|---|
| 1 KB | 7.3 | 7.3 | 48 | 12 | 32 | ~same | **6.6× faster** | **1.6× faster** |
| 10 KB | 77 | 68 | 468 | 132 | 316 | 1.1× slower | **6.1× faster** | **1.7× faster** |
| 100 KB | 778 | 659 | 4,552 | 8,015 | 4,242 | 1.2× slower | **5.9× faster** | **10× faster** |
| 1 MB | 7,881 | 6,840 | 46,626 | 1,419,234 | 46,741 | 1.2× slower | **5.9× faster** | **180× faster** |

SafeRE is **close to JDK** on find-all-matches scaling, within 1.2× at all
sizes. SafeRE is **5.9–6.6× faster than RE2/J** at all scales. SafeRE is
also **1.6–180× faster than RE2-FFM**, with the advantage growing dramatically
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
representative. We report two geomeans to avoid conflating qualitatively
different workloads:

1. **Core workloads** — everyday regex operations: literal match, character
   class match, alternation find, capture groups, find-in-text, email find,
   pig Latin replace, HTTP full request. These represent typical application
   usage patterns.
2. **Pathological/scaling** — workloads that stress linear-time guarantees:
   `a?{n}a{n}` at n=20, `[ -~]*…$` hard search at 1 MB, nested quantifier
   `(?:a?){20}a{20}` at 100 KB. These are less common in everyday code but
   represent the safety guarantee that motivates the library.

Each individual ratio has measurement uncertainty (JMH reports ± error), but
we report geomean of point estimates, which is standard practice. Per-benchmark
ratios and confidence intervals are available in the detailed tables above.

### vs JDK (`java.util.regex`)

| Category | Geomean | Interpretation |
|---|--:|---|
| Core workloads (8 benchmarks) | 1.13 | **SafeRE is 1.1× slower overall** |
| Pathological/scaling (3 benchmarks) | 0.000074 | **SafeRE is ~13,500× faster** |

On core workloads, SafeRE is 1.1× slower than JDK overall — a modest gap
driven by HTTP parsing (3.1×) and pig Latin replace (1.7×). SafeRE wins on
4 of 8 core benchmarks (literal match, char class match, capture groups,
email find) and is within 1.1× on find-in-text. The pathological
geomean reflects the fundamental algorithmic difference: SafeRE guarantees
linear time while JDK's backtracking engine exhibits exponential blowup on
adversarial patterns. The dramatic improvement from 241× to 13,500× is driven
by SafeRE's constant-time rejection on the Hard search pattern.

### vs RE2/J

| Category | Geomean | Interpretation |
|---|--:|---|
| Core workloads (8 benchmarks) | 0.087 | **SafeRE is 11.5× faster overall** |
| Pathological/scaling (3 benchmarks) | 0.00034 | **SafeRE is ~2,930× faster** |

SafeRE beats RE2/J on every single benchmark in the suite. Both libraries
provide linear-time guarantees, but SafeRE's DFA, OnePass, and BitState
engines provide a large constant-factor advantage over RE2/J's NFA-only
approach. The pathological geomean improved from 52× to 2,930× due to
SafeRE's constant-time Hard search rejection.

### vs RE2-FFM (C++ RE2 via FFM)

| Category | Geomean | Interpretation |
|---|--:|---|
| Core workloads (8 benchmarks) | 0.49 | **SafeRE is 2.1× faster overall** |
| Pathological/scaling (3 benchmarks) | 0.058 | **SafeRE is ~17.3× faster** |

On core workloads, SafeRE is 2.1× faster than RE2-FFM overall. SafeRE wins
decisively on literal match (6.1×), character class match (6.3×), capture
groups (4.3×), and pig Latin replace (1.7×). SafeRE is now 1.4× faster than
RE2-FFM on HTTP parsing. Find-in-text is roughly comparable at small sizes
and increasingly SafeRE-favorable at larger sizes.

On pathological/scaling workloads, SafeRE is now 17.3× faster overall —
a dramatic reversal from the previous 3.1× slower. SafeRE's constant-time
Hard search rejection (0.019 µs vs RE2-FFM's 152 µs at 1 MB) is the main
driver. On the other two pathological benchmarks — `a?{20}a{20}` and nested
quantifiers — SafeRE and RE2-FFM are within 2× of each other, both scaling
linearly. RE2-FFM also pays FFM per-call overhead that grows with input size,
making it much slower than SafeRE on find-all-matches workloads at scale
(e.g., find-in-text at 1 MB: 1.42 seconds vs 7.9 ms).

## Analysis

**Where SafeRE wins (vs Java engines):**
- **Literal matching** — 1.4× faster than JDK, 14× faster than RE2/J
- **Character class matching** — 1.3× faster than JDK, 68× faster than RE2/J
- **Literal replacement** — Fastest of all engines (including C++ RE2!) on replaceFirst
- **Email find** — 1.5× faster than JDK, 7.4× faster than RE2/J
- **Digit replaceAll** — 1.5× faster than JDK, 16× faster than RE2/J
- **Find-in-text** — within 1.2× of JDK, 5.9–6.6× faster than RE2/J
- **Capture groups** — 7–12× faster than RE2/J, 1.2× faster than JDK at 10 groups
- **Hard search** — constant-time rejection (0.018–0.019 µs at all sizes), 4,200–2.3M× faster than JDK, 2,100–2.0M× faster than RE2/J
- **Nested quantifiers** — 5.0–5.6× faster than JDK, 126–139× faster than RE2/J
- **Easy search on large text** — 1.7–1.8× faster than JDK, comparable to RE2/J
- **Medium search** — 56–65× faster than RE2/J
- **HTTP parsing** — 14–30× faster than RE2/J
- **Pathological `a?{n}a{n}`** — 226–211,000× faster than JDK, 41–210× faster than RE2/J

**Where JDK wins:**
- **HTTP parsing** — 3.1× faster (SafeRE's OnePass engine has higher per-character
  overhead on anchored patterns, though the gap narrowed from 14× to 3.1× with
  the HTTP/OnePass fast path optimization)
- **Pig Latin replace** — 1.7× faster (per-match capture extraction cost in multi-match replaceAll)
- **Empty-match replace** — 3.2× faster
- **Compilation** — 4.9–15× faster (defers work to match time)

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
  faster (e.g., alternation find at 604 ns vs 812 ns)
- On large inputs, FFM per-call overhead becomes significant — especially for
  find-all-matches workloads where each `find()` call crosses the JNI/FFM
  boundary with UTF-16 → UTF-8 conversion
- Compilation is 2–3× slower than SafeRE due to FFM call overhead plus C++
  RE2's eager DFA setup
- SafeRE now beats RE2-FFM on the Hard search pattern (0.019 µs vs 152 µs
  at 1 MB) and is 17.3× faster overall on pathological/scaling workloads

**SafeRE vs C++ RE2:**
- C++ RE2 is typically **2–20× faster** on matching due to native code, UTF-8
  encoding (shorter strings), and no GC/JIT overhead
- **Hard search** — SafeRE is now faster than C++ RE2 (0.018 vs 0.048 µs),
  achieving constant-time rejection by detecting the required literal suffix
  cannot occur
- **Compilation** — SafeRE compiles **faster** than C++ RE2 thanks to lazy
  initialization, while C++ RE2 performs more eager DFA setup at compile time
- **Pathological patterns** — SafeRE is within 1.0–4.1× of C++ RE2, confirming
  the DFA implementation is correct and efficient
- **Literal replacement** — SafeRE is **faster** than C++ RE2 (30 vs 98 ns for
  replaceFirst), demonstrating Java's `String.indexOf()` optimization
- **Nested quantifiers** — SafeRE is within 2.6× of C++ RE2 (277 vs 108 µs at
  100 KB), both scaling linearly

**SafeRE vs Go `regexp`:**
- Go `regexp` is an NFA-only implementation (no DFA), like RE2/J
- SafeRE **beats Go on DFA-dominated workloads**: pathological (7–152× faster),
  nested quantifiers (62–79× faster), hard search (1,000–1.4M× faster)
- Go beats SafeRE on short-string matching and compilation (no DFA overhead)
- Go `regexp` is consistently ~2–3× faster than RE2/J across the board,
  reflecting Go's native-code advantage over Java for NFA execution

**Key takeaway:** SafeRE is the fastest RE2-family engine on the JVM —
**2.1× faster than RE2-FFM** and **11.5× faster than RE2/J** on core
workloads by geomean. SafeRE is within a small constant factor of the
C++ original and significantly faster than both Go `regexp` and RE2/J on
DFA-dominated workloads. On core workloads, SafeRE is **1.1× slower than
JDK by geomean** (driven by HTTP parsing at 3.1× and pig Latin replace at
1.7×), while providing **guaranteed linear time** that JDK cannot offer.
On pathological/scaling workloads, SafeRE is **13,500× faster than JDK** and
**17.3× faster than RE2-FFM** — the latter a reversal from the previous 3.1×
disadvantage, driven by the new constant-time Hard search rejection.

**The tradeoff:** SafeRE trades higher per-match overhead on HTTP-style
anchored patterns (3.1× slower than JDK) for **guaranteed linear time** and
**better scaling** on large inputs and pathological patterns. For
safety-critical applications (user-supplied regexes, large documents, content
filtering), SafeRE eliminates the risk of catastrophic backtracking while being
substantially faster than all other RE2-family implementations except
the C++ original.

**Impact of fork mode:** These results were collected with JMH fork mode
(5 forks per benchmark), which starts a fresh JVM for each fork to avoid
JIT profile pollution between benchmarks. Prior results collected in no-fork
mode (`-f 0`) showed distorted JDK numbers (artificially slow on some
benchmarks due to JIT profile contamination from RE2/J and SafeRE code
paths). Fork-mode results are more representative of real-world performance
where JDK regex runs in its own application.

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
    `^(?:GET|POST) +([^ ]+) HTTP` from 1,292 to 276 ns/op.

## Remaining Opportunities

- **HTTP parsing overhead** — HTTP patterns are now 3.1× slower than JDK
  (improved from 14× with the HTTP/OnePass fast path). The remaining gap is
  OnePass per-character overhead on the 97-char request; JDK's backtracking
  engine has lower per-match setup cost on short anchored patterns.
- **Pig Latin / complex replace** — 1.7× slower than JDK. The gap is
  fundamental: SafeRE uses BitState (multi-state exploration) per match while
  JDK does a single backtracking pass. Compiled replacement templates and
  direct BitState already reduced this from 2.2× to 1.7×.
- **Empty-match replace** — 3.2× faster than JDK. Empty-match handling requires
  careful position advancement and is a known gap.
- **Compilation** — Pattern compilation is 4.9–15× slower than JDK. Opportunities
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
| charClassMatch | 96 | 56 | 88 | 80 |
| alternationFind | 544 | 136 | 208 | 512 |
| captureGroups | 408 | 368 | 432 | 408 |
| findInText | 2,080 | 136 | 976 | 2,448 |
| emailFind | 168 | 136 | 88 | 224 |

SafeRE allocates more per match than JDK on most workloads due to Matcher
state objects and DFA workspace arrays. The highest allocation rate is
findInText (2,080 B/op) where repeated `find()` calls accumulate Matcher and
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
| 1 KB | 72 | 56 | 48 |
| 10 KB | 72 | 56 | 48 |
| 100 KB | 72 | 56 | 48 |
| 1 MB | 73 | 57 | 83 |

**Medium pattern:** `[XYZ]ABCDEFGHIJKLMNOPQRSTUVWXYZ$`

| Text size | SafeRE (B/op) | JDK (B/op) | RE2/J (B/op) |
|---|--:|--:|--:|
| 1 KB | 72 | 56 | 48 |
| 10 KB | 72 | 56 | 50 |
| 100 KB | 72 | 56 | 136 |
| 1 MB | 75 | 58 | 288 |

All three engines show near-constant allocation up to 100 KB, indicating
streaming behavior. RE2/J's Medium pattern allocation grows at 1 MB (288 B/op),
likely due to internal buffer resizing in its NFA engine. SafeRE and JDK
remain essentially flat.

### DFA Cache Growth (SafeRE only)

| Pattern | Cache growth (bytes) |
|---|--:|
| simple | 72 |
| medium | 72 |
| complex | 12,240 |
| alternation | 3,168 |

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
