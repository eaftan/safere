# SafeRE Benchmark Results

Comparing SafeRE against `java.util.regex` on common regex workloads and
pathological patterns that demonstrate backtracking blowup.

**Environment:**
- JDK: OpenJDK 25.0.2 (targeting Java 21)
- JMH: 1.37, no-fork mode (`-f 0`)
- Machine: Linux (shared environment — results are indicative, not precise)

**Running benchmarks:**

```bash
# Always use the wrapper script — it runs `mvn install` first to ensure
# the benchmark module picks up the latest safere code.
./run-benchmarks.sh                        # all benchmarks
./run-benchmarks.sh CaptureScalingBenchmark  # specific class
```

## Matching Performance (ns/op, lower is better)

| Benchmark | SafeRE | JDK | Ratio |
|---|--:|--:|---|
| Literal match (`"hello"`) | 3 | 24 | **8× faster** |
| Char class match (`[a-zA-Z]+`) | 475 | 135 | 3.5× slower |
| Alternation find (`foo\|bar\|…` ×8) | 13,729 | 857 | 16× slower |
| Capture groups (`(\d{4})-(\d{2})-(\d{2})`) | 104 | 114 | **1.1× faster** |
| Find -ing words in prose (~350 chars) | 37,502 | 5,601 | 6.7× slower |
| Email pattern find | 7,830 | 638 | 12× slower |

## Search Scaling (µs/op, lower is better)

How performance scales with input size (1 KB → 1 MB). These patterns search
through random text that does **not** contain the match (worst-case scan).

### Easy: `ABCDEFGHIJKLMNOPQRSTUVWXYZ$` (literal prefix, memchr-able)

| Text Size | SafeRE | JDK | Ratio |
|--:|--:|--:|---|
| 1 KB | 0.09 | 0.17 | **1.9× faster** |
| 10 KB | 0.83 | 1.48 | **1.8× faster** |
| 100 KB | 8.4 | 14.6 | **1.7× faster** |
| 1 MB | 83.5 | 146.4 | **1.8× faster** |

### Medium: `[XYZ]ABCDEFGHIJKLMNOPQRSTUVWXYZ$` (starts with char class)

| Text Size | SafeRE | JDK | Ratio |
|--:|--:|--:|---|
| 1 KB | 7.0 | 4.0 | 1.7× slower |
| 10 KB | 45.3 | 41.0 | 1.1× slower |
| 100 KB | 430 | 415 | ~same |
| 1 MB | 4,315 | 4,277 | ~same |

### Hard: `[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$` (catastrophic in backtracking engines)

| Text Size | SafeRE | JDK | Ratio |
|--:|--:|--:|---|
| 1 KB | 7.6 | 390 | **51× faster** |
| 10 KB | 45.7 | 3,062 | **67× faster** |
| 100 KB | 426 | 27,579 | **65× faster** |
| 1 MB | 4,306 | 269,417 | **63× faster** |

The Hard pattern has a leading `[ -~]*` that causes O(n²) behavior in the
JDK's backtracking engine. SafeRE's DFA handles it in linear time.

### Successful Search (Easy pattern, match at end of text)

| Text Size | SafeRE | JDK | Ratio |
|--:|--:|--:|---|
| 1 KB | 6.3 | 0.21 | 30× slower |
| 10 KB | 7.1 | 1.5 | 4.7× slower |
| 100 KB | 14.7 | 14.7 | ~same |
| 1 MB | 90.9 | 147.8 | **1.6× faster** |

SafeRE has higher per-match startup cost but scales better; it overtakes JDK
around 100 KB.

## Capture Group Scaling (ns/op, lower is better)

| Groups | SafeRE | JDK | Ratio |
|--:|--:|--:|---|
| 0 | 90 | 70 | 1.3× slower |
| 1 | 114 | 98 | 1.2× slower |
| 3 | 165 | 119 | 1.4× slower |
| 10 | 6,378 | 374 | 17× slower |

SafeRE is competitive at low capture counts (1–3 groups). At 10 groups, the
jump to BitState capture extraction is costly. This is a known optimization
opportunity.

## HTTP Request Parsing (ns/op, lower is better)

Pattern: `^(?:GET|POST) +([^ ]+) HTTP`

| Input | SafeRE | JDK | Ratio |
|---|--:|--:|---|
| Full request (97 chars) | 7,349 | 213 | 34× slower |
| Small request (18 chars) | 3,007 | 65 | 46× slower |
| Extract URL (97 chars) | 7,236 | 216 | 34× slower |

This anchored pattern with an alternation start and capture group shows
SafeRE's per-match overhead. The pattern is OnePass-eligible but the
alternation prevents literal fast-path.

## Replace Performance (ns/op, lower is better)

| Benchmark | SafeRE | JDK | Ratio |
|---|--:|--:|---|
| Literal replaceFirst (`"b"→"bb"`) | 38 | 61 | **1.6× faster** |
| Literal replaceAll | 127 | 166 | **1.3× faster** |
| Pig Latin replaceAll (backrefs) | 14,974 | 1,124 | 13× slower |
| Digit replaceAll (`\d+`→`"NUM"`) | 5,366 | 576 | 9.3× slower |
| Empty-match replaceAll (`a*`) | 2,557 | 109 | 23× slower |

SafeRE wins on literal replacements (fast-path). For patterns requiring
regex engine dispatch, the per-match overhead dominates.

## PatternSet Multi-Pattern Matching (ns/op)

Matching text against multiple compiled patterns simultaneously.

| Patterns | Unanchored (match) | Unanchored (no match) | Anchored (match) | Anchored (no match) |
|--:|--:|--:|--:|--:|
| 4 | 3,643 | 2,615 | 2,134 | 1,750 |
| 16 | 34,778 | 20,504 | 9,024 | 8,676 |
| 64 | 119,816 | 93,448 | 41,166 | 41,499 |

Anchored matching is 2–3× faster than unanchored. Scaling is roughly linear
in pattern count.

## Fanout & Nested Quantifiers (µs/op, lower is better)

### Unicode Fanout: `(?:[\x{80}-\x{10FFFF}]?){100}[\x{80}-\x{10FFFF}]`

| Text Size | SafeRE | JDK | Ratio |
|--:|--:|--:|---|
| 1 KB | 10.0 | 1.4 | 7× slower |
| 10 KB | 359 | 1.4 | 257× slower |
| 100 KB | 358 | 1.4 | 256× slower |

JDK's backtracking engine quickly fails and returns false; SafeRE's DFA
explores more states. The pattern is a stress test for DFA state explosion.

### Nested Quantifier: `(?:a?){20}a{20}`

| Text Size | SafeRE | JDK | Ratio |
|--:|--:|--:|---|
| 1 KB | 8.0 | 18.2 | **2.3× faster** |
| 10 KB | 48.5 | 183 | **3.8× faster** |
| 100 KB | 445 | 1,855 | **4.2× faster** |

SafeRE's advantage grows with input size, as expected for a linear-time
engine vs. backtracking.

## Compilation Performance (µs/op, lower is better)

| Pattern | SafeRE | JDK | Ratio |
|---|--:|--:|---|
| Simple (`hello`) | 2.00 | 0.11 | 18× slower |
| Medium (datetime with 6 captures) | 7.56 | 0.39 | 20× slower |
| Complex (email regex) | 8.05 | 0.29 | 28× slower |
| Alternation (12 alternatives) | 9.79 | 0.49 | 20× slower |

## Pathological Pattern: `a?{n}a{n}` matched against `a{n}`

This is the canonical backtracking blowup case. The JDK's backtracking NFA
exhibits O(2^n) behavior. SafeRE's linear-time engines handle it in O(n).

### SafeRE scalability (µs/op)

| n | SafeRE |
|--:|--:|
| 10 | 0.062 |
| 15 | 0.083 |
| 20 | 0.103 |
| 25 | 0.122 |
| 30 | 0.143 |
| 50 | 0.232 |
| 100 | 0.436 |

Growth is linear: 10× increase in n → ~7× increase in time.

### SafeRE vs JDK (µs/op)

| n | SafeRE | JDK | Speedup |
|--:|--:|--:|--:|
| 10 | 0.066 | 15.8 | 239× |
| 15 | 0.089 | 664.7 | 7,468× |
| 20 | 0.111 | 27,459 | 247,378× |
| 25 | 0.122 | *(hangs)* | ∞ |

## Find-in-Text Scaling (µs/op, lower is better)

`\b\w+ing\b` on repeated prose, scaling from 1 KB to 1 MB.

| Text Size | SafeRE | JDK | Ratio |
|--:|--:|--:|---|
| 1 KB | 83 | 12.7 | 6.5× slower |
| 10 KB | 781 | 127 | 6.1× slower |
| 100 KB | 10,546 | 1,194 | 8.8× slower |
| 1 MB | 345,258 | 12,173 | 28× slower |

SafeRE's find-all-matches loop has high per-match overhead that compounds
on text with many matches. This is a known optimization target (BitState
caching, find-loop specialization).

## Analysis

**Where SafeRE wins:**
- **Literal matching** — 8× faster via `String.indexOf()` fast path
- **Literal replacement** — 1.3–1.6× faster for simple replacements
- **Capture groups (≤3)** — Competitive or faster (OnePass engine)
- **Hard/pathological patterns** — 63–250,000× faster, the core value proposition
- **Nested quantifiers** — 2–4× faster, scaling advantage grows with input size
- **Easy search on large text** — 1.7–1.9× faster (DFA + prefix acceleration)

**Where JDK wins:**
- **Small-text patterns** — JDK has lower per-match overhead (~30–50ns startup)
- **Find-all on many matches** — Per-match overhead compounds
- **Unicode fanout** — DFA state explosion on high-fanout patterns
- **Compilation** — 18–28× faster (JDK defers work to match time)

**The tradeoff:** SafeRE trades constant-factor speed on small inputs for
**guaranteed linear time** and **better scaling** on large inputs and
pathological patterns. For safety-critical applications (user-supplied
regexes, large documents, content filtering), SafeRE eliminates the risk
of catastrophic backtracking.

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

## Remaining Opportunities

- **Compilation** — Pattern compilation is 18–28× slower than JDK. Opportunities
  include caching parsed Regexp trees and lazy OnePass/DFA construction.
- **BitState caching** — A reusable BitState instance per Matcher would reduce
  allocation on repeated `find()` calls.
- **10+ capture groups** — The 17× gap at 10 groups suggests BitState dispatch
  overhead; a specialized capture-extraction path could help.
- **Find-all loop** — Per-match overhead dominates when there are many matches;
  a bulk-find mode could amortize engine setup cost.

---
*Last updated: 2026-03-23*
