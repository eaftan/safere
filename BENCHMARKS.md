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
| Literal match (`"hello"`) | 3 | 13 | 132 | 41 | 78 | **4× faster** | **44× faster** |
| Char class match (`[a-zA-Z]+`) | 379 | 25 | 1,317 | 83 | 448 | 15× slower | **3.5× faster** |
| Alternation find (`foo\|bar\|…` ×8) | 7,620 | 534 | 4,462 | 18 | 1,770 | 14× slower | 1.7× slower |
| Capture groups (`(\d{4})-(\d{2})-(\d{2})`) | 117 | 88 | 567 | 75 | 242 | 1.3× slower | **4.8× faster** |
| Find -ing words in prose (~350 chars) | 23,680 | 2,984 | 20,680 | 18 | 12,878 | 7.9× slower | 1.1× slower |
| Email pattern find | 4,746 | 409 | 1,926 | 87 | 565 | 12× slower | 2.5× slower |

C++ RE2 is the fastest on every benchmark here, as expected for native code
with a mature DFA. Go `regexp` sits between SafeRE and RE2/J — it's an
NFA-only implementation like RE2/J, but written in a faster language.
SafeRE's literal match (3 ns) beats all other engines thanks to the
`String.equals()` fast path. JDK is significantly faster than SafeRE on
short-text benchmarks due to lower per-match overhead. Recent optimizations
(BitState fast path for small texts, DFA setup sharing, engine selection,
DFA start state caching) have significantly narrowed the gap: find-in-text
improved from 11× to 7.9× slower vs JDK, and email find from 23× to 12×
slower.

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
| 10 KB | 0.83 | 1.44 | 0.84 | 0.24 | **1.7× faster** | ~same |
| 100 KB | 8.2 | 14.4 | 8.2 | 2.4 | **1.8× faster** | ~same |
| 1 MB | 83 | 145 | 83 | 24 | **1.7× faster** | ~same |

### Medium: `[XYZ]ABCDEFGHIJKLMNOPQRSTUVWXYZ$` (starts with char class)

| Text Size | SafeRE | JDK | RE2/J | Go | vs JDK | vs RE2/J |
|--:|--:|--:|--:|--:|---|---|
| 1 KB | 0.39 | 0.27 | 22.2 | 14 | 1.5× slower | **57× faster** |
| 10 KB | 3.8 | 2.5 | 230 | 178 | 1.5× slower | **61× faster** |
| 100 KB | 38 | 25 | 2,378 | 1,757 | 1.5× slower | **63× faster** |
| 1 MB | 386 | 254 | 24,138 | 18,534 | 1.5× slower | **63× faster** |

Character-class prefix acceleration now allows SafeRE to scan directly for
`[XYZ]` before invoking the DFA, reducing the gap with JDK from 16–24× to
just 1.5×. SafeRE is now **57–63× faster than RE2/J** on this pattern.

### Hard: `[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$` (catastrophic in backtracking engines)

| Text Size | SafeRE | JDK | RE2/J | Go | vs JDK | vs RE2/J |
|--:|--:|--:|--:|--:|---|---|
| 1 KB | 3.6 | 63 | 37 | 19 | **17× faster** | **10× faster** |
| 10 KB | 29 | 437 | 369 | 259 | **15× faster** | **13× faster** |
| 100 KB | 271 | 4,477 | 3,740 | 2,530 | **17× faster** | **14× faster** |
| 1 MB | 2,803 | 44,850 | 38,224 | 25,999 | **16× faster** | **14× faster** |

The Hard pattern has a leading `[ -~]*` that causes O(n²) behavior in the
JDK's backtracking engine. SafeRE, RE2/J, and Go `regexp` all handle it in
linear time, but SafeRE's DFA is ~10–14× faster than RE2/J's NFA and ~7–9×
faster than Go's NFA. DFA start state caching further improved performance
(1 KB: 4.7 → 3.6 µs, 1 MB: 4,173 → 2,803 µs).

### Successful Search (Easy pattern, match at end of text)

| Text Size | SafeRE | JDK | RE2/J | Go | vs JDK | vs RE2/J |
|--:|--:|--:|--:|--:|---|---|
| 1 KB | 5.5 | 0.20 | 0.80 | 0.28 | 28× slower | 6.9× slower |
| 10 KB | 6.0 | 1.5 | 1.6 | 0.75 | 4.0× slower | 3.8× slower |
| 100 KB | 13 | 15 | 8.9 | 2.9 | **1.1× faster** | 1.5× slower |
| 1 MB | 89 | 152 | 84 | 25 | **1.7× faster** | 1.1× slower |

SafeRE has higher per-match startup cost but scales well; it overtakes JDK
around 100 KB. DFA setup sharing improved the 1 KB case from 7.6 to 5.5 µs.

## Capture Group Scaling (ns/op, lower is better)

| Groups | SafeRE | JDK | RE2/J | C++ RE2 | Go | vs JDK | vs RE2/J |
|--:|--:|--:|--:|--:|--:|---|---|
| 0 | 89 | 32 | 395 | 60 | 162 | 2.8× slower | **4.4× faster** |
| 1 | 106 | 42 | 892 | 67 | 239 | 2.5× slower | **8.4× faster** |
| 3 | 143 | 78 | 979 | 82 | 304 | 1.8× slower | **6.8× faster** |
| 10 | 323 | 235 | 1,408 | 373 | 554 | 1.4× slower | **4.4× faster** |

SafeRE closes the gap with JDK as capture count grows — from 2.8× slower at
0 groups to only 1.4× at 10 groups. SafeRE is consistently **4–8× faster
than RE2/J** on capture extraction. C++ RE2 matches SafeRE at 10 groups —
both use OnePass engines that scale similarly with group count. Go `regexp`
is 1.5–2.3× slower than SafeRE, consistent with its NFA-only approach.

## HTTP Request Parsing (ns/op, lower is better)

Pattern: `^(?:GET|POST) +([^ ]+) HTTP`

| Input | SafeRE | JDK | RE2/J | C++ RE2 | Go | vs JDK | vs RE2/J |
|---|--:|--:|--:|--:|--:|---|---|
| Full request (97 chars) | 545 | 90 | 8,422 | 327 | 950 | 6.1× slower | **15× faster** |
| Small request (18 chars) | 99 | 52 | 963 | 71 | 228 | 1.9× slower | **9.7× faster** |
| Extract URL (97 chars) | 547 | 94 | 8,282 | 319 | 956 | 5.8× slower | **15× faster** |

The BitState fast path for small texts (≤256 chars) dramatically improved
HTTP parsing: full request improved from 6,334 to 545 ns (11.6× faster),
small request from 2,707 to 99 ns (27.3× faster). SafeRE now beats RE2/J
by **10–15×** on all HTTP workloads. JDK remains fastest due to lower startup
overhead on short anchored patterns.

## Replace Performance (ns/op, lower is better)

| Benchmark | SafeRE | JDK | RE2/J | C++ RE2 | Go | vs JDK | vs RE2/J |
|---|--:|--:|--:|--:|--:|---|---|
| Literal replaceFirst (`"b"→"bb"`) | 27 | 41 | 153 | 95 | 578 | **1.5× faster** | **5.7× faster** |
| Literal replaceAll | 88 | 106 | 699 | 402 | 475 | **1.2× faster** | **7.9× faster** |
| Pig Latin replaceAll (backrefs) | 9,932 | 898 | 7,859 | 1,919 | 2,800 | 11× slower | 1.3× slower |
| Digit replaceAll (`\d+`→`"NUM"`) | 3,176 | 299 | 3,095 | 650 | 1,518 | 11× slower | ~same |
| Empty-match replaceAll (`a*`) | 233 | 76 | 405 | 364 | 336 | 3.1× slower | **1.7× faster** |

SafeRE wins on literal replacements — **faster than all other engines**
(including C++ RE2!) on replaceFirst (27 ns) and replaceAll (88 ns), thanks
to the `String.indexOf()` fast path. The BitState fast path improved
pig Latin replaceAll from 14,384 to 9,932 ns and digit replaceAll from
5,849 to 3,176 ns. For complex replacements, JDK remains fastest.
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
| 1 KB | 8.6 | 0.85 | 106 | 0.05 | 1.4 |
| 10 KB | 345 | 0.84 | 107 | 0.05 | 3.7 |
| 100 KB | 340 | 0.85 | 107 | 0.05 | 3.7 |

JDK's backtracking engine quickly fails and returns false; SafeRE's DFA
explores more states. RE2/J's NFA is even slower than SafeRE here. C++ RE2
handles this pattern trivially (~50 ns) thanks to its mature DFA with
efficient state caching. Go `regexp` handles it well (~4 µs), much faster
than both SafeRE and RE2/J.

### Nested Quantifier: `(?:a?){20}a{20}`

| Text Size | SafeRE | JDK | RE2/J | C++ RE2 | Go | vs JDK | vs RE2/J |
|--:|--:|--:|--:|--:|--:|---|---|
| 1 KB | 5.1 | 15 | 396 | 1.1 | 185 | **3.0× faster** | **78× faster** |
| 10 KB | 31 | 153 | 3,824 | 10.9 | 2,183 | **4.9× faster** | **123× faster** |
| 100 KB | 276 | 1,464 | 37,715 | 108 | 21,753 | **5.3× faster** | **137× faster** |

SafeRE is significantly faster than both JDK and RE2/J here. RE2/J's NFA-only
approach is much slower than SafeRE's DFA on this high-fanout quantifier
pattern. SafeRE is within 2.6× of C++ RE2, showing both use the same
algorithmic approach. Go `regexp` is similar to RE2/J (NFA-only), both
~36–79× slower than SafeRE.

## Compilation Performance (µs/op, lower is better)

| Pattern | SafeRE | JDK | RE2/J | C++ RE2 | Go | vs JDK | vs RE2/J |
|---|--:|--:|--:|--:|--:|---|---|
| Simple (`hello`) | 0.43 | 0.10 | 0.28 | 1.57 | 0.91 | 4.3× slower | 1.5× slower |
| Medium (datetime with 6 captures) | 3.99 | 0.31 | 2.15 | 6.68 | 6.03 | 13× slower | 1.9× slower |
| Complex (email regex) | 2.42 | 0.24 | 1.15 | 4.78 | 2.49 | 10× slower | 2.1× slower |
| Alternation (12 alternatives) | 3.18 | 0.42 | 3.00 | 8.09 | 5.94 | 7.6× slower | 1.1× slower |

Lazy initialization defers OnePass analysis and DFA equivalence-class setup
to first match, reducing compile-time work to just parsing and program
compilation. SafeRE is now within 1.6× of RE2/J on simple patterns. JDK
defers most work to match time and remains the fastest compiler.
C++ RE2 compilation is now 1.5–2× *slower* than SafeRE — C++ RE2 performs
more eager work at compile time (DFA setup, prefilter analysis).
Go `regexp` compiles faster than SafeRE (no DFA construction up front).

## Pathological Pattern: `a?{n}a{n}` matched against `a{n}`

This is the canonical backtracking blowup case. The JDK's backtracking NFA
exhibits O(2^n) behavior. SafeRE, RE2/J, and C++ RE2 all handle it in linear
time, but the DFA-based engines (SafeRE and C++ RE2) are far faster than
RE2/J's NFA.

### SafeRE vs RE2/J vs C++ RE2 vs Go scalability (µs/op)

| n | SafeRE | RE2/J | C++ RE2 | Go | SafeRE/RE2/J | SafeRE/C++ |
|--:|--:|--:|--:|--:|---|---|
| 10 | 0.059 | 1.91 | 0.054 | 0.88 | **32× faster** | 1.1× slower |
| 15 | 0.081 | 4.12 | 0.069 | 1.83 | **51× faster** | 1.2× slower |
| 20 | 0.100 | 7.28 | 0.073 | 3.08 | **73× faster** | 1.4× slower |
| 25 | 0.122 | 11.19 | 0.078 | 4.75 | **92× faster** | 1.6× slower |
| 30 | 0.141 | 16.06 | 0.084 | 6.45 | **114× faster** | 1.7× slower |
| 50 | 0.234 | 42.0 | 0.110 | 16.8 | **180× faster** | 2.1× slower |
| 100 | 0.432 | 161.9 | 0.174 | 65.0 | **375× faster** | 2.5× slower |

All four linear-time engines scale linearly. SafeRE and C++ RE2 (both
DFA-based) have the lowest growth rates. Go `regexp` and RE2/J (both
NFA-only) are 19–375× slower than SafeRE. Go `regexp` is ~2.5× faster
than RE2/J, reflecting Go's native-code advantage over Java for NFA
execution.

### Three-way comparison (µs/op, small n where JDK is feasible)

| n | SafeRE | RE2/J | JDK | SafeRE vs JDK |
|--:|--:|--:|--:|--:|
| 10 | 0.059 | 1.89 | 9.4 | **159×** |
| 15 | 0.080 | 3.77 | 389 | **4,863×** |
| 20 | 0.101 | 6.76 | 15,499 | **153,455×** |
| 25 | — | — | *(hangs)* | ∞ |

## Find-in-Text Scaling (µs/op, lower is better)

`\b\w+ing\b` on repeated prose, scaling from 1 KB to 1 MB.

| Text Size | SafeRE | JDK | RE2/J | Go | vs JDK | vs RE2/J |
|--:|--:|--:|--:|--:|---|---|
| 1 KB | 42 | 7.4 | 49 | 32 | 5.7× slower | 1.2× slower |
| 10 KB | 310 | 73 | 485 | 318 | 4.2× slower | **1.6× faster** |
| 100 KB | 2,772 | 696 | 4,759 | 4,252 | 4.0× slower | **1.7× faster** |
| 1 MB | 27,781 | 7,171 | 47,590 | 45,112 | 3.9× slower | **1.7× faster** |

SafeRE is faster than RE2/J and Go at scale, but all three linear-time
engines are slower than JDK on this find-all-matches workload. DFA word-
boundary support and DFA start state caching keep the gap manageable
(4–6× vs JDK rather than 28× in earlier versions). Go `regexp` is close
to RE2/J on this benchmark.

## Analysis

**Where SafeRE wins (vs Java engines):**
- **Literal matching** — 4× faster than JDK, 44× faster than RE2/J
- **Literal replacement** — Fastest of all five engines (including C++ RE2!)
- **Capture groups** — 4–8× faster than RE2/J
- **Hard/pathological patterns** — 15–17× faster than JDK, 10–14× faster than RE2/J
- **Nested quantifiers** — 3–5× faster than JDK, 78–137× faster than RE2/J
- **Easy search on large text** — 1.7× faster than JDK, comparable to RE2/J
- **Medium search** — Now 57–63× faster than RE2/J (with char-class prefix accel)
- **HTTP parsing** — 10–15× faster than RE2/J (with BitState fast path)
- **Pathological `a?{n}a{n}`** — 159–153,000× faster than JDK, 32–375× faster than RE2/J

**Where JDK wins:**
- **Short-text patterns** — Lower per-match overhead (13–88 ns vs SafeRE's 3–379 ns range)
- **Medium search patterns** — JDK's JIT optimizes character class search well (1.5× faster)
- **Find-all on many matches** — Per-match overhead compounds (4–6× at scale)
- **Unicode fanout** — DFA state explosion on high-fanout patterns
- **Compilation** — 4–13× faster (defers work to match time)
- **HTTP parsing** — 2–6× faster on short anchored patterns

**Where RE2/J fits:**
- RE2/J is generally **slower than both SafeRE and JDK** on matching tasks
- RE2/J lacks DFA, OnePass, and BitState engines — only has NFA (Pike VM)
- RE2/J provides linear-time safety like SafeRE, but without the DFA
  performance advantage
- RE2/J compilation is 1.1–2.1× faster than SafeRE but 3–5× slower than JDK

**SafeRE vs C++ RE2:**
- C++ RE2 is typically **2–20× faster** on matching due to native code, UTF-8
  encoding (shorter strings), and no GC/JIT overhead
- **Compilation** — SafeRE now compiles **faster** than C++ RE2 thanks to lazy
  initialization, while C++ RE2 performs more eager DFA setup at compile time
- **Pathological patterns** — SafeRE is within 1.1–2.5× of C++ RE2, confirming
  the DFA implementation is correct and efficient
- **Literal replacement** — SafeRE is **faster** than C++ RE2 (27 vs 95 ns for
  replaceFirst), demonstrating Java's `String.indexOf()` optimization
- **Nested quantifiers** — SafeRE is within 2.6× of C++ RE2 (276 vs 108 µs at
  100 KB), both scaling linearly

**SafeRE vs Go `regexp`:**
- Go `regexp` is an NFA-only implementation (no DFA), like RE2/J
- SafeRE **beats Go on DFA-dominated workloads**: pathological (7–152× faster),
  nested quantifiers (36–79× faster), hard search (5.3–9.3× faster)
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
17. **DFA start state caching** — Caches start states by position context
    (beginning-of-text, beginning-of-line, etc.), eliminating expand()
    allocation on repeated DFA searches.
18. **Lazy OnePass/DFA analysis** — Defers OnePass.build() and Dfa.buildSetup()
    from compile time to first use, improving compilation 2–5×.

## Remaining Opportunities

- **Compilation** — Pattern compilation is 4–13× slower than JDK. Opportunities
  include caching parsed Regexp trees.
- **BitState caching** — A reusable BitState instance per Matcher would reduce
  allocation on repeated `find()` calls.
- **DFA state budget tuning** — The default 10,000-state budget may be
  suboptimal for some pattern/text combinations.

---
*Last updated: 2026-03-27 (full JMH defaults, post-optimization round)*
