# SafeRE Benchmark Results

Comparing SafeRE against `java.util.regex` (JDK) and
[RE2/J](https://github.com/google/re2j) 1.8 on common regex workloads and
pathological patterns that demonstrate backtracking blowup.

**Environment:**
- CPU: Intel Core i7-11700K (8 cores / 16 threads, 3.6 GHz base)
- RAM: 32 GB
- OS: Ubuntu 24.04.4 LTS on WSL2 (kernel 6.6.87.2-microsoft-standard-WSL2),
  Windows 11 host
- JDK: OpenJDK 25.0.2+10-69 (targeting Java 21)
- JMH: 1.37, no-fork mode (`-f 0`)

**Running benchmarks:**

```bash
# Always use the wrapper script — it runs `mvn install` first to ensure
# the benchmark module picks up the latest safere code.
./run-benchmarks.sh                        # all benchmarks
./run-benchmarks.sh CaptureScalingBenchmark  # specific class
```

## Matching Performance (ns/op, lower is better)

| Benchmark | SafeRE | JDK | RE2/J | vs JDK | vs RE2/J |
|---|--:|--:|--:|---|---|
| Literal match (`"hello"`) | 3 | 23 | 145 | **8× faster** | **48× faster** |
| Char class match (`[a-zA-Z]+`) | 521 | 174 | 1,390 | 3× slower | **2.7× faster** |
| Alternation find (`foo\|bar\|…` ×8) | 15,907 | 1,108 | 4,612 | 14× slower | 3.4× slower |
| Capture groups (`(\d{4})-(\d{2})-(\d{2})`) | 114 | 123 | 617 | **1.1× faster** | **5.4× faster** |
| Find -ing words in prose (~350 chars) | 36,634 | 5,477 | 21,899 | 6.7× slower | 1.7× slower |
| Email pattern find | 10,214 | 746 | 2,262 | 14× slower | 4.5× slower |

## Search Scaling (µs/op, lower is better)

How performance scales with input size (1 KB → 1 MB). These patterns search
through random text that does **not** contain the match (worst-case scan).

### Easy: `ABCDEFGHIJKLMNOPQRSTUVWXYZ$` (literal prefix, memchr-able)

| Text Size | SafeRE | JDK | RE2/J | vs JDK | vs RE2/J |
|--:|--:|--:|--:|---|---|
| 1 KB | 0.09 | 0.17 | 0.11 | **1.9× faster** | **1.2× faster** |
| 10 KB | 0.83 | 1.44 | 0.85 | **1.7× faster** | ~same |
| 100 KB | 8.8 | 14.5 | 8.3 | **1.6× faster** | ~same |
| 1 MB | 84 | 145 | 83 | **1.7× faster** | ~same |

### Medium: `[XYZ]ABCDEFGHIJKLMNOPQRSTUVWXYZ$` (starts with char class)

| Text Size | SafeRE | JDK | RE2/J | vs JDK | vs RE2/J |
|--:|--:|--:|--:|---|---|
| 1 KB | 7.0 | 4.2 | 25 | 1.7× slower | **3.6× faster** |
| 10 KB | 45 | 43 | 266 | ~same | **5.9× faster** |
| 100 KB | 422 | 421 | 2,691 | ~same | **6.4× faster** |
| 1 MB | 4,291 | 4,275 | 28,046 | ~same | **6.5× faster** |

### Hard: `[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$` (catastrophic in backtracking engines)

| Text Size | SafeRE | JDK | RE2/J | vs JDK | vs RE2/J |
|--:|--:|--:|--:|---|---|
| 1 KB | 7.6 | 390 | 40 | **51× faster** | **5.3× faster** |
| 10 KB | 46 | 3,028 | 403 | **66× faster** | **8.8× faster** |
| 100 KB | 426 | 27,600 | 4,035 | **65× faster** | **9.5× faster** |
| 1 MB | 4,271 | 267,357 | 41,612 | **63× faster** | **9.7× faster** |

The Hard pattern has a leading `[ -~]*` that causes O(n²) behavior in the
JDK's backtracking engine. Both SafeRE and RE2/J handle it in linear time,
but SafeRE's DFA is ~10× faster than RE2/J's NFA.

### Successful Search (Easy pattern, match at end of text)

| Text Size | SafeRE | JDK | RE2/J | vs JDK | vs RE2/J |
|--:|--:|--:|--:|---|---|
| 1 KB | 9.1 | 0.19 | 0.88 | 48× slower | 10× slower |
| 10 KB | 9.4 | 1.5 | 1.6 | 6.3× slower | 5.9× slower |
| 100 KB | 17.7 | 14.8 | 9.2 | ~same | 1.9× slower |
| 1 MB | 100 | 148 | 85 | **1.5× faster** | 1.2× slower |

SafeRE has higher per-match startup cost but scales well; it overtakes JDK
around 100 KB.

## Capture Group Scaling (ns/op, lower is better)

| Groups | SafeRE | JDK | RE2/J | vs JDK | vs RE2/J |
|--:|--:|--:|--:|---|---|
| 0 | 88 | 64 | 417 | 1.4× slower | **4.8× faster** |
| 1 | 107 | 91 | 915 | 1.2× slower | **8.6× faster** |
| 3 | 147 | 120 | 1,054 | 1.2× slower | **7.2× faster** |
| 10 | 302 | 374 | 1,553 | **1.2× faster** | **5.1× faster** |

SafeRE is competitive at low capture counts and **beats JDK at 10 groups**
thanks to the OnePass 64-bit action encoding. SafeRE is consistently
**5–9× faster than RE2/J** on capture extraction.

## HTTP Request Parsing (ns/op, lower is better)

Pattern: `^(?:GET|POST) +([^ ]+) HTTP`

| Input | SafeRE | JDK | RE2/J | vs JDK | vs RE2/J |
|---|--:|--:|--:|---|---|
| Full request (97 chars) | 7,014 | 220 | 8,533 | 32× slower | **1.2× faster** |
| Small request (18 chars) | 3,045 | 66 | 984 | 46× slower | 3.1× slower |
| Extract URL (97 chars) | 6,947 | 226 | 8,485 | 31× slower | **1.2× faster** |

JDK dominates on this short anchored pattern. SafeRE beats RE2/J on the
full request but loses on the small request due to higher startup cost.

## Replace Performance (ns/op, lower is better)

| Benchmark | SafeRE | JDK | RE2/J | vs JDK | vs RE2/J |
|---|--:|--:|--:|---|---|
| Literal replaceFirst (`"b"→"bb"`) | 40 | 52 | 209 | **1.3× faster** | **5.2× faster** |
| Literal replaceAll | 128 | 147 | 891 | **1.1× faster** | **7.0× faster** |
| Pig Latin replaceAll (backrefs) | 15,000 | 1,153 | 8,341 | 13× slower | 1.8× slower |
| Digit replaceAll (`\d+`→`"NUM"`) | 5,672 | 618 | 3,324 | 9.2× slower | 1.7× slower |
| Empty-match replaceAll (`a*`) | 1,455 | 103 | 510 | 14× slower | 2.9× slower |

SafeRE wins on literal replacements. For complex replacements, JDK is fastest,
with RE2/J in the middle.

## PatternSet Multi-Pattern Matching (ns/op)

Matching text against multiple compiled patterns simultaneously (SafeRE-only
feature — neither JDK nor RE2/J has a built-in multi-pattern API).

| Patterns | Unanchored (match) | Unanchored (no match) | Anchored (match) | Anchored (no match) |
|--:|--:|--:|--:|--:|
| 4 | 3,739 | 2,727 | 2,199 | 1,827 |
| 16 | 36,821 | 21,328 | 9,800 | 8,957 |
| 64 | 130,195 | 99,449 | 43,424 | 42,418 |

Anchored matching is 2–3× faster than unanchored. Scaling is roughly linear
in pattern count.

## Fanout & Nested Quantifiers (µs/op, lower is better)

### Unicode Fanout: `(?:[\x{80}-\x{10FFFF}]?){100}[\x{80}-\x{10FFFF}]`

| Text Size | SafeRE | JDK | RE2/J |
|--:|--:|--:|--:|
| 1 KB | 10 | 1.5 | 113 |
| 10 KB | 345 | 1.5 | 112 |
| 100 KB | 348 | 1.5 | 108 |

JDK's backtracking engine quickly fails and returns false; SafeRE's DFA
explores more states. RE2/J's NFA is even slower than SafeRE here. The
pattern is a stress test for DFA state explosion.

### Nested Quantifier: `(?:a?){20}a{20}`

| Text Size | SafeRE | JDK | RE2/J | vs JDK | vs RE2/J |
|--:|--:|--:|--:|---|---|
| 1 KB | 8.2 | 18 | 415 | **2.2× faster** | **51× faster** |
| 10 KB | 50 | 182 | 4,105 | **3.6× faster** | **82× faster** |
| 100 KB | 455 | 1,820 | 41,086 | **4.0× faster** | **90× faster** |

SafeRE crushes both JDK and RE2/J. RE2/J's NFA-only approach is dramatically
slower than SafeRE's DFA on this high-fanout quantifier pattern.

## Compilation Performance (µs/op, lower is better)

| Pattern | SafeRE | JDK | RE2/J | vs JDK | vs RE2/J |
|---|--:|--:|--:|---|---|
| Simple (`hello`) | 2.07 | 0.11 | 0.29 | 19× slower | 7.1× slower |
| Medium (datetime with 6 captures) | 10.29 | 0.40 | 2.23 | 26× slower | 4.6× slower |
| Complex (email regex) | 7.71 | 0.30 | 1.41 | 26× slower | 5.5× slower |
| Alternation (12 alternatives) | 9.79 | 0.47 | 3.14 | 21× slower | 3.1× slower |

SafeRE's compilation is slower because it eagerly builds DFA infrastructure,
OnePass analysis, and other data structures up front. JDK defers most work to
match time.

## Pathological Pattern: `a?{n}a{n}` matched against `a{n}`

This is the canonical backtracking blowup case. The JDK's backtracking NFA
exhibits O(2^n) behavior. SafeRE and RE2/J both handle it in linear time,
but SafeRE's DFA is 29–357× faster than RE2/J's NFA.

### SafeRE vs RE2/J scalability (µs/op)

| n | SafeRE | RE2/J | Ratio |
|--:|--:|--:|---|
| 10 | 0.060 | 1.74 | **29× faster** |
| 15 | 0.081 | 4.00 | **49× faster** |
| 20 | 0.102 | 7.33 | **72× faster** |
| 25 | 0.123 | 11.50 | **93× faster** |
| 30 | 0.142 | 15.14 | **107× faster** |
| 50 | 0.235 | 41.33 | **176× faster** |
| 100 | 0.438 | 156.2 | **357× faster** |

Both are linear, but SafeRE's growth rate is much lower: 10× increase in n →
~7× increase in SafeRE time vs ~90× in RE2/J.

### Three-way comparison (µs/op, small n where JDK is feasible)

| n | SafeRE | RE2/J | JDK | SafeRE vs JDK |
|--:|--:|--:|--:|--:|
| 10 | 0.063 | 1.77 | 15.6 | **248×** |
| 15 | 0.084 | 3.85 | 674 | **8,024×** |
| 20 | 0.107 | 6.95 | 27,138 | **253,626×** |
| 25 | 0.123 | 11.50 | *(hangs)* | ∞ |

## Find-in-Text Scaling (µs/op, lower is better)

`\b\w+ing\b` on repeated prose, scaling from 1 KB to 1 MB.

| Text Size | SafeRE | JDK | RE2/J | vs JDK | vs RE2/J |
|--:|--:|--:|--:|---|---|
| 1 KB | 64 | 12.8 | 51 | 5× slower | 1.3× slower |
| 10 KB | 479 | 124 | 484 | 3.9× slower | ~same |
| 100 KB | 4,421 | 1,197 | 4,716 | 3.7× slower | **1.1× faster** |
| 1 MB | 44,401 | 12,323 | 47,821 | 3.6× slower | **1.1× faster** |

SafeRE is slightly faster than RE2/J at scale, but both are slower than JDK
on this find-all-matches workload. DFA word-boundary support has significantly
improved scaling (previously 28× slower than JDK at 1 MB, now 3.6×).

## Analysis

**Where SafeRE wins:**
- **Literal matching** — 8× faster than JDK, 48× faster than RE2/J
- **Literal replacement** — Fastest of the three on simple replacements
- **Capture groups** — 5–9× faster than RE2/J; beats JDK at ≥10 groups
- **Hard/pathological patterns** — 63× faster than JDK, 10× faster than RE2/J
- **Nested quantifiers** — 4× faster than JDK, 51–90× faster than RE2/J
- **Easy search on large text** — 1.7× faster than JDK, comparable to RE2/J
- **Medium search** — Matches JDK, 6× faster than RE2/J

**Where JDK wins:**
- **Small-text patterns** — Lower per-match overhead (~30–50ns startup)
- **Find-all on many matches** — Per-match overhead compounds (3.6× at 1 MB)
- **Unicode fanout** — DFA state explosion on high-fanout patterns
- **Compilation** — 19–26× faster (defers work to match time)

**Where RE2/J fits:**
- RE2/J is generally **slower than both SafeRE and JDK** on matching tasks
- RE2/J lacks DFA, OnePass, and BitState engines — only has NFA (Pike VM)
- RE2/J provides linear-time safety like SafeRE, but without the DFA
  performance advantage
- RE2/J compilation is 3–7× faster than SafeRE but 3–5× slower than JDK

**The tradeoff:** SafeRE trades constant-factor speed on small inputs for
**guaranteed linear time** and **better scaling** on large inputs and
pathological patterns. For safety-critical applications (user-supplied
regexes, large documents, content filtering), SafeRE eliminates the risk
of catastrophic backtracking while being substantially faster than RE2/J.

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

## Remaining Opportunities

- **Compilation** — Pattern compilation is 19–26× slower than JDK. Opportunities
  include caching parsed Regexp trees and lazy OnePass/DFA construction.
- **BitState caching** — A reusable BitState instance per Matcher would reduce
  allocation on repeated `find()` calls.
- **Find-all loop** — Per-match overhead dominates when there are many matches
  (3.6× slower than JDK at 1 MB); a bulk-find mode could amortize engine
  setup cost.

---
*Last updated: 2026-03-24*
