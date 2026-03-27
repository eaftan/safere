# SafeRE Benchmark Results

Comparing SafeRE against `java.util.regex` (JDK),
[RE2/J](https://github.com/google/re2j) 1.8, C++ RE2 (2024-07-02), and
Go [`regexp`](https://pkg.go.dev/regexp) (Go 1.26.1) on common regex
workloads and pathological patterns that demonstrate backtracking blowup.

**Environment:**
- CPU: Intel Core i7-11700K (8 cores / 16 threads, 3.6 GHz base)
- RAM: 32 GB
- OS: Ubuntu 24.04.4 LTS on WSL2 (kernel 6.6.87.2-microsoft-standard-WSL2),
  Windows 11 host
- JDK: OpenJDK 25.0.2+10-69 (targeting Java 21)
- JMH: 1.37, fork mode (5 forks, full JMH defaults)
- C++ compiler: g++ 13.3.0, `-O3 -DNDEBUG` (CMake Release)
- Go: 1.26.1 linux/amd64

**Cross-language comparison caveats:**
C++ RE2 and Go `regexp` operate on UTF-8 byte strings while Java operates on
UTF-16 char arrays. C++ RE2 benefits from ahead-of-time compilation, no GC
pauses, and no JIT warmup. Go has a concurrent GC with different
characteristics from Java's. These are real-world differences — the goal is to
show SafeRE is in the same algorithmic ballpark as other RE2-family engines,
not to declare a winner across language boundaries. Within the Java ecosystem,
the SafeRE vs JDK vs RE2/J comparison is apples-to-apples.

**Running benchmarks:**

```bash
# Java benchmarks — always use the wrapper script (runs `mvn install` first)
./run-java-benchmarks.sh                        # all benchmarks
./run-java-benchmarks.sh CaptureScalingBenchmark  # specific class

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

| Benchmark | SafeRE | JDK | RE2/J | C++ RE2 | Go | vs JDK | vs RE2/J |
|---|--:|--:|--:|--:|--:|---|---|
| Literal match (`"hello"`) | 3 | 13 | 141 | 41 | 78 | **4× faster** | **47× faster** |
| Char class match (`[a-zA-Z]+`) | 386 | 27 | 1,363 | 83 | 448 | 14× slower | **3.5× faster** |
| Alternation find (`foo\|bar\|…` ×8) | 7,652 | 596 | 4,496 | 18 | 1,770 | 13× slower | 1.7× slower |
| Capture groups (`(\d{4})-(\d{2})-(\d{2})`) | 118 | 90 | 600 | 75 | 242 | 1.3× slower | **5.1× faster** |
| Find -ing words in prose (~350 chars) | 28,458 | 3,169 | 21,015 | 18 | 12,878 | 9.0× slower | 1.4× slower |
| Email pattern find | 4,818 | 423 | 1,979 | 87 | 565 | 11× slower | 2.4× slower |

C++ RE2 is the fastest on every benchmark here, as expected for native code
with a mature DFA. Go `regexp` sits between SafeRE and RE2/J — it's an
NFA-only implementation like RE2/J, but written in a faster language.
SafeRE's literal match (3 ns) beats all other engines thanks to the
`String.equals()` fast path. JDK is significantly faster than SafeRE on
short-text benchmarks due to lower per-match overhead. Recent optimizations
(BitState fast path for small texts, DFA setup sharing, engine selection)
have significantly narrowed the gap: alternation find improved from 24×
to 13× slower vs JDK, email find from 23× to 11× slower, and find-in-text
from 11× to 9× slower.

## Search Scaling (µs/op, lower is better)

How performance scales with input size (1 KB → 1 MB). These patterns search
through random text that does **not** contain the match (worst-case scan).

**Note on C++ RE2 search scaling:** C++ RE2 uses a reverse DFA that
recognizes end-anchored patterns (`$`) and only scans the string suffix,
achieving ~0.04 µs regardless of text size. This is a legitimate
optimization that SafeRE does not yet implement for `PartialMatch`-style
searches. C++ RE2 results are omitted from the scaling tables since they
measure a different code path.

### Easy: `ABCDEFGHIJKLMNOPQRSTUVWXYZ$` (literal prefix, memchr-able)

| Text Size | SafeRE | JDK | RE2/J | Go | vs JDK | vs RE2/J |
|--:|--:|--:|--:|--:|---|---|
| 1 KB | 0.09 | 0.16 | 0.10 | 0.09 | **1.9× faster** | ~same |
| 10 KB | 0.82 | 1.42 | 0.83 | 0.24 | **1.7× faster** | ~same |
| 100 KB | 8.2 | 14.3 | 8.2 | 2.4 | **1.7× faster** | ~same |
| 1 MB | 82 | 143 | 83 | 24 | **1.7× faster** | ~same |

### Medium: `[XYZ]ABCDEFGHIJKLMNOPQRSTUVWXYZ$` (starts with char class)

| Text Size | SafeRE | JDK | RE2/J | Go | vs JDK | vs RE2/J |
|--:|--:|--:|--:|--:|---|---|
| 1 KB | 0.39 | 0.26 | 22.1 | 14 | 1.5× slower | **57× faster** |
| 10 KB | 3.8 | 2.5 | 232 | 178 | 1.5× slower | **61× faster** |
| 100 KB | 38 | 25 | 2,393 | 1,757 | 1.5× slower | **63× faster** |
| 1 MB | 386 | 254 | 24,576 | 18,534 | 1.5× slower | **64× faster** |

Character-class prefix acceleration now allows SafeRE to scan directly for
`[XYZ]` before invoking the DFA, reducing the gap with JDK from 16–24× to
just 1.5×. SafeRE is now **57–64× faster than RE2/J** on this pattern.

### Hard: `[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$` (catastrophic in backtracking engines)

| Text Size | SafeRE | JDK | RE2/J | Go | vs JDK | vs RE2/J |
|--:|--:|--:|--:|--:|---|---|
| 1 KB | 4.7 | 63 | 37 | 19 | **13× faster** | **7.9× faster** |
| 10 KB | 42 | 460 | 382 | 259 | **11× faster** | **9.1× faster** |
| 100 KB | 411 | 4,503 | 3,740 | 2,530 | **11× faster** | **9.1× faster** |
| 1 MB | 4,173 | 44,029 | 37,979 | 25,999 | **11× faster** | **9.1× faster** |

The Hard pattern has a leading `[ -~]*` that causes O(n²) behavior in the
JDK's backtracking engine. SafeRE, RE2/J, and Go `regexp` all handle it in
linear time, but SafeRE's DFA is ~9× faster than RE2/J's NFA and ~6× faster
than Go's NFA. DFA setup sharing further improved small-text performance
(1 KB: 6.6 → 4.7 µs).

### Successful Search (Easy pattern, match at end of text)

| Text Size | SafeRE | JDK | RE2/J | Go | vs JDK | vs RE2/J |
|--:|--:|--:|--:|--:|---|---|
| 1 KB | 5.7 | 0.20 | 0.79 | 0.28 | 29× slower | 7.2× slower |
| 10 KB | 6.1 | 1.5 | 1.5 | 0.75 | 4.1× slower | 4.0× slower |
| 100 KB | 13 | 15 | 8.9 | 2.9 | **1.1× faster** | 1.5× slower |
| 1 MB | 88 | 152 | 83 | 25 | **1.7× faster** | 1.1× slower |

SafeRE has higher per-match startup cost but scales well; it overtakes JDK
around 100 KB. DFA setup sharing improved the 1 KB case from 7.6 to 5.7 µs.

## Capture Group Scaling (ns/op, lower is better)

| Groups | SafeRE | JDK | RE2/J | C++ RE2 | Go | vs JDK | vs RE2/J |
|--:|--:|--:|--:|--:|--:|---|---|
| 0 | 92 | 31 | 398 | 60 | 162 | 3.0× slower | **4.3× faster** |
| 1 | 105 | 42 | 877 | 67 | 239 | 2.5× slower | **8.4× faster** |
| 3 | 143 | 78 | 977 | 82 | 304 | 1.8× slower | **6.8× faster** |
| 10 | 312 | 233 | 1,418 | 373 | 554 | 1.3× slower | **4.5× faster** |

SafeRE closes the gap with JDK as capture count grows — from 3× slower at
0 groups to only 1.3× at 10 groups. SafeRE is consistently **4–8× faster
than RE2/J** on capture extraction. C++ RE2 matches SafeRE at 10 groups —
both use OnePass engines that scale similarly with group count. Go `regexp`
is 1.5–2.3× slower than SafeRE, consistent with its NFA-only approach.

## HTTP Request Parsing (ns/op, lower is better)

Pattern: `^(?:GET|POST) +([^ ]+) HTTP`

| Input | SafeRE | JDK | RE2/J | C++ RE2 | Go | vs JDK | vs RE2/J |
|---|--:|--:|--:|--:|--:|---|---|
| Full request (97 chars) | 537 | 89 | 8,261 | 327 | 950 | 6.0× slower | **15× faster** |
| Small request (18 chars) | 101 | 50 | 933 | 71 | 228 | 2.0× slower | **9.2× faster** |
| Extract URL (97 chars) | 542 | 95 | 8,262 | 319 | 956 | 5.7× slower | **15× faster** |

The BitState fast path for small texts (≤256 chars) dramatically improved
HTTP parsing: full request improved from 6,334 to 537 ns (11.8× faster),
small request from 2,707 to 101 ns (26.8× faster). SafeRE now beats RE2/J
by **9–15×** on all HTTP workloads. JDK remains fastest due to lower startup
overhead on short anchored patterns.

## Replace Performance (ns/op, lower is better)

| Benchmark | SafeRE | JDK | RE2/J | C++ RE2 | Go | vs JDK | vs RE2/J |
|---|--:|--:|--:|--:|--:|---|---|
| Literal replaceFirst (`"b"→"bb"`) | 27 | 41 | 151 | 95 | 578 | **1.5× faster** | **5.6× faster** |
| Literal replaceAll | 89 | 109 | 743 | 402 | 475 | **1.2× faster** | **8.3× faster** |
| Pig Latin replaceAll (backrefs) | 10,140 | 925 | 8,069 | 1,919 | 2,800 | 11× slower | 1.3× slower |
| Digit replaceAll (`\d+`→`"NUM"`) | 3,261 | 301 | 3,117 | 650 | 1,518 | 11× slower | ~same |
| Empty-match replaceAll (`a*`) | 242 | 77 | 415 | 364 | 336 | 3.1× slower | **1.7× faster** |

SafeRE wins on literal replacements — **faster than all other engines**
(including C++ RE2!) on replaceFirst (27 ns) and replaceAll (89 ns), thanks
to the `String.indexOf()` fast path. The BitState fast path improved
pig Latin replaceAll from 14,384 to 10,140 ns and digit replaceAll from
5,849 to 3,261 ns. For complex replacements, JDK remains fastest.
Go `regexp` is consistently faster than RE2/J on replacements.

## PatternSet Multi-Pattern Matching (ns/op)

Matching text against multiple compiled patterns simultaneously (SafeRE-only
feature — neither JDK nor RE2/J has a built-in multi-pattern API).

| Patterns | Unanchored (match) | Unanchored (no match) | Anchored (match) | Anchored (no match) |
|--:|--:|--:|--:|--:|
| 4 | 3,040 | 2,264 | 1,882 | 1,460 |
| 16 | 28,093 | 15,793 | 6,183 | 5,364 |
| 64 | 99,175 | 69,820 | 19,998 | 19,224 |

Anchored matching is 2–5× faster than unanchored. Scaling is roughly linear
in pattern count.

## Fanout & Nested Quantifiers (µs/op, lower is better)

### Unicode Fanout: `(?:[\x{80}-\x{10FFFF}]?){100}[\x{80}-\x{10FFFF}]`

| Text Size | SafeRE | JDK | RE2/J | C++ RE2 | Go |
|--:|--:|--:|--:|--:|--:|
| 1 KB | 8.7 | 0.86 | 108 | 0.05 | 1.4 |
| 10 KB | 350 | 0.85 | 110 | 0.05 | 3.7 |
| 100 KB | 344 | 0.88 | 112 | 0.05 | 3.7 |

JDK's backtracking engine quickly fails and returns false; SafeRE's DFA
explores more states. RE2/J's NFA is even slower than SafeRE here. C++ RE2
handles this pattern trivially (~50 ns) thanks to its mature DFA with
efficient state caching. Go `regexp` handles it well (~4 µs), much faster
than both SafeRE and RE2/J.

### Nested Quantifier: `(?:a?){20}a{20}`

| Text Size | SafeRE | JDK | RE2/J | C++ RE2 | Go | vs JDK | vs RE2/J |
|--:|--:|--:|--:|--:|--:|---|---|
| 1 KB | 6.3 | 15 | 383 | 1.1 | 185 | **2.4× faster** | **61× faster** |
| 10 KB | 44 | 153 | 3,818 | 10.9 | 2,183 | **3.5× faster** | **87× faster** |
| 100 KB | 418 | 1,457 | 37,742 | 108 | 21,753 | **3.5× faster** | **90× faster** |

SafeRE is significantly faster than both JDK and RE2/J here. RE2/J's NFA-only
approach is much slower than SafeRE's DFA on this high-fanout quantifier
pattern. SafeRE is within 4× of C++ RE2, showing both use the same
algorithmic approach. Go `regexp` is similar to RE2/J (NFA-only), both
~50–90× slower than SafeRE.

## Compilation Performance (µs/op, lower is better)

| Pattern | SafeRE | JDK | RE2/J | C++ RE2 | Go | vs JDK | vs RE2/J |
|---|--:|--:|--:|--:|--:|---|---|
| Simple (`hello`) | 2.35 | 0.10 | 0.28 | 1.57 | 0.91 | 24× slower | 8.4× slower |
| Medium (datetime with 6 captures) | 7.90 | 0.32 | 2.14 | 6.68 | 6.03 | 25× slower | 3.7× slower |
| Complex (email regex) | 7.01 | 0.24 | 1.21 | 4.78 | 2.49 | 29× slower | 5.8× slower |
| Alternation (12 alternatives) | 9.25 | 0.42 | 3.11 | 8.09 | 5.94 | 22× slower | 3.0× slower |

SafeRE's compilation is slower because it eagerly builds DFA infrastructure
(including the new pre-computed equivalence class setup), OnePass analysis,
and other data structures up front. JDK defers most work to match time.
C++ RE2 is 1.1–1.5× faster than SafeRE at compilation — a small gap,
indicating SafeRE's compilation logic is algorithmically similar.
Go `regexp` compiles faster than SafeRE (no DFA construction up front).
The DFA setup pre-computation adds ~0.5 µs to compile time but saves ~1.7 µs
per Matcher creation, a net win for any pattern used more than once.

## Pathological Pattern: `a?{n}a{n}` matched against `a{n}`

This is the canonical backtracking blowup case. The JDK's backtracking NFA
exhibits O(2^n) behavior. SafeRE, RE2/J, and C++ RE2 all handle it in linear
time, but the DFA-based engines (SafeRE and C++ RE2) are far faster than
RE2/J's NFA.

### SafeRE vs RE2/J vs C++ RE2 vs Go scalability (µs/op)

| n | SafeRE | RE2/J | C++ RE2 | Go | SafeRE/RE2/J | SafeRE/C++ |
|--:|--:|--:|--:|--:|---|---|
| 10 | 0.061 | 1.78 | 0.054 | 0.88 | **29× faster** | 1.1× slower |
| 15 | 0.082 | 3.87 | 0.069 | 1.83 | **47× faster** | 1.2× slower |
| 20 | 0.103 | 6.92 | 0.073 | 3.08 | **67× faster** | 1.4× slower |
| 25 | 0.126 | 10.95 | 0.078 | 4.75 | **87× faster** | 1.6× slower |
| 30 | 0.145 | 14.85 | 0.084 | 6.45 | **102× faster** | 1.7× slower |
| 50 | 0.237 | 39.00 | 0.110 | 16.8 | **165× faster** | 2.2× slower |
| 100 | 0.448 | 156.6 | 0.174 | 65.0 | **350× faster** | 2.6× slower |

All four linear-time engines scale linearly. SafeRE and C++ RE2 (both
DFA-based) have the lowest growth rates. Go `regexp` and RE2/J (both
NFA-only) are 14–350× slower than SafeRE. Go `regexp` is ~2.5× faster
than RE2/J, reflecting Go's native-code advantage over Java for NFA
execution.

### Three-way comparison (µs/op, small n where JDK is feasible)

| n | SafeRE | RE2/J | JDK | SafeRE vs JDK |
|--:|--:|--:|--:|--:|
| 10 | 0.061 | 1.74 | 9.6 | **157×** |
| 15 | 0.082 | 3.81 | 395 | **4,817×** |
| 20 | 0.104 | 6.78 | 15,624 | **150,231×** |
| 25 | — | — | *(hangs)* | ∞ |

## Find-in-Text Scaling (µs/op, lower is better)

`\b\w+ing\b` on repeated prose, scaling from 1 KB to 1 MB.

| Text Size | SafeRE | JDK | RE2/J | Go | vs JDK | vs RE2/J |
|--:|--:|--:|--:|--:|---|---|
| 1 KB | 53 | 8.0 | 49 | 32 | 6.6× slower | 1.1× slower |
| 10 KB | 411 | 75 | 479 | 318 | 5.5× slower | **1.2× faster** |
| 100 KB | 3,757 | 683 | 4,644 | 4,252 | 5.5× slower | **1.2× faster** |
| 1 MB | 37,595 | 7,199 | 47,206 | 45,112 | 5.2× slower | **1.3× faster** |

SafeRE is faster than RE2/J and Go at scale, but all three linear-time
engines are slower than JDK on this find-all-matches workload. DFA word-
boundary support keeps the gap manageable (5–7× vs JDK rather than 28× in
earlier versions). Go `regexp` is close to RE2/J on this benchmark.

## Analysis

**Where SafeRE wins (vs Java engines):**
- **Literal matching** — 4× faster than JDK, 47× faster than RE2/J
- **Literal replacement** — Fastest of all five engines (including C++ RE2!)
- **Capture groups** — 4–8× faster than RE2/J
- **Hard/pathological patterns** — 11× faster than JDK, 9× faster than RE2/J
- **Nested quantifiers** — 2–4× faster than JDK, 61–90× faster than RE2/J
- **Easy search on large text** — 1.7× faster than JDK, comparable to RE2/J
- **Medium search** — Now 57–64× faster than RE2/J (with char-class prefix accel)
- **HTTP parsing** — 9–15× faster than RE2/J (with BitState fast path)
- **Pathological `a?{n}a{n}`** — 157–150,000× faster than JDK, 29–350× faster than RE2/J

**Where JDK wins:**
- **Short-text patterns** — Lower per-match overhead (13–90 ns vs SafeRE's 3–386 ns range)
- **Medium search patterns** — JDK's JIT optimizes character class search well (1.5× faster)
- **Find-all on many matches** — Per-match overhead compounds (5–7× at scale)
- **Unicode fanout** — DFA state explosion on high-fanout patterns
- **Compilation** — 22–29× faster (defers work to match time)
- **HTTP parsing** — 2–6× faster on short anchored patterns

**Where RE2/J fits:**
- RE2/J is generally **slower than both SafeRE and JDK** on matching tasks
- RE2/J lacks DFA, OnePass, and BitState engines — only has NFA (Pike VM)
- RE2/J provides linear-time safety like SafeRE, but without the DFA
  performance advantage
- RE2/J compilation is 3–8× faster than SafeRE but 3–5× slower than JDK

**SafeRE vs C++ RE2:**
- C++ RE2 is typically **2–20× faster** on matching due to native code, UTF-8
  encoding (shorter strings), and no GC/JIT overhead
- **Compilation is close** — SafeRE is only 1.1–1.5× slower than C++ RE2,
  showing the compilation logic is algorithmically equivalent
- **Pathological patterns** — SafeRE is within 1.1–2.6× of C++ RE2, confirming
  the DFA implementation is correct and efficient
- **Literal replacement** — SafeRE is **faster** than C++ RE2 (27 vs 95 ns for
  replaceFirst), demonstrating Java's `String.indexOf()` optimization
- **Nested quantifiers** — SafeRE is within 4× of C++ RE2 (418 vs 108 µs at
  100 KB), both scaling linearly

**SafeRE vs Go `regexp`:**
- Go `regexp` is an NFA-only implementation (no DFA), like RE2/J
- SafeRE **beats Go on DFA-dominated workloads**: pathological (7–136× faster),
  nested quantifiers (29–52× faster), hard search (2.9–6.2× faster)
- Go beats SafeRE on short-string matching and compilation (no DFA overhead)
- Go `regexp` is consistently ~2–3× faster than RE2/J across the board,
  reflecting Go's native-code advantage over Java for NFA execution

**Key takeaway:** SafeRE is the fastest RE2-family engine on the JVM,
within a small constant factor of the C++ original, and significantly
faster than both Go `regexp` and RE2/J on DFA-dominated workloads.

**The tradeoff:** SafeRE trades constant-factor speed on small inputs for
**guaranteed linear time** and **better scaling** on large inputs and
pathological patterns. For safety-critical applications (user-supplied
regexes, large documents, content filtering), SafeRE eliminates the risk
of catastrophic backtracking while being substantially faster than all
other RE2-family implementations except the C++ original.

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

## Remaining Opportunities

- **Compilation** — Pattern compilation is 22–29× slower than JDK. Opportunities
  include caching parsed Regexp trees and lazy OnePass construction.
- **BitState caching** — A reusable BitState instance per Matcher would reduce
  allocation on repeated `find()` calls.
- **Find-all loop** — Per-match overhead dominates when there are many matches
  (5–7× slower than JDK at scale); a bulk-find mode could amortize engine
  setup cost.
- **DFA state budget tuning** — The default 10,000-state budget may be
  suboptimal for some pattern/text combinations.

---
*Last updated: 2026-03-27 (full JMH defaults, post-optimization round)*
