# SafeRE Benchmark Results

Comparing SafeRE against `java.util.regex` on common regex workloads and
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

| Benchmark | SafeRE | JDK | Ratio |
|---|--:|--:|---|
| Literal match (`"hello"`) | 3 | 23 | **8× faster** |
| Char class match (`[a-zA-Z]+`) | 477 | 130 | 3.7× slower |
| Alternation find (`foo\|bar\|…` ×8) | 14,000 | 827 | 17× slower |
| Capture groups (`(\d{4})-(\d{2})-(\d{2})`) | 105 | 115 | **1.1× faster** |
| Find -ing words in prose (~350 chars) | 34,215 | 5,615 | 6.1× slower |
| Email pattern find | 9,353 | 601 | 16× slower |

## Search Scaling (µs/op, lower is better)

How performance scales with input size (1 KB → 1 MB). These patterns search
through random text that does **not** contain the match (worst-case scan).

### Easy: `ABCDEFGHIJKLMNOPQRSTUVWXYZ$` (literal prefix, memchr-able)

| Text Size | SafeRE | JDK | Ratio |
|--:|--:|--:|---|
| 1 KB | 0.09 | 0.17 | **1.9× faster** |
| 10 KB | 0.86 | 1.50 | **1.7× faster** |
| 100 KB | 8.6 | 15.2 | **1.8× faster** |
| 1 MB | 86.6 | 149.8 | **1.7× faster** |

### Medium: `[XYZ]ABCDEFGHIJKLMNOPQRSTUVWXYZ$` (starts with char class)

| Text Size | SafeRE | JDK | Ratio |
|--:|--:|--:|---|
| 1 KB | 7.4 | 4.3 | 1.7× slower |
| 10 KB | 49.0 | 43.1 | 1.1× slower |
| 100 KB | 456 | 434 | ~same |
| 1 MB | 4,631 | 4,425 | ~same |

### Hard: `[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$` (catastrophic in backtracking engines)

| Text Size | SafeRE | JDK | Ratio |
|--:|--:|--:|---|
| 1 KB | 8.1 | 404 | **50× faster** |
| 10 KB | 49.5 | 2,948 | **60× faster** |
| 100 KB | 461 | 28,759 | **62× faster** |
| 1 MB | 4,715 | 277,413 | **59× faster** |

The Hard pattern has a leading `[ -~]*` that causes O(n²) behavior in the
JDK's backtracking engine. SafeRE's DFA handles it in linear time.

### Successful Search (Easy pattern, match at end of text)

| Text Size | SafeRE | JDK | Ratio |
|--:|--:|--:|---|
| 1 KB | 9.2 | 0.21 | 45× slower |
| 10 KB | 9.6 | 1.5 | 6.3× slower |
| 100 KB | 17.7 | 15.4 | ~same |
| 1 MB | 96.1 | 153.8 | **1.6× faster** |

SafeRE has higher per-match startup cost but scales better; it overtakes JDK
around 100 KB.

## Capture Group Scaling (ns/op, lower is better)

| Groups | SafeRE | JDK | Ratio |
|--:|--:|--:|---|
| 0 | 88 | 64 | 1.4× slower |
| 1 | 114 | 89 | 1.3× slower |
| 3 | 151 | 121 | 1.2× slower |
| 10 | 311 | 364 | **1.2× faster** |

SafeRE is competitive at low capture counts (1–3 groups) and now **beats JDK
at 10 groups** thanks to the OnePass 64-bit action encoding, which raised the
OnePass capture limit from 6 to 16.

## HTTP Request Parsing (ns/op, lower is better)

Pattern: `^(?:GET|POST) +([^ ]+) HTTP`

| Input | SafeRE | JDK | Ratio |
|---|--:|--:|---|
| Full request (97 chars) | 7,212 | 213 | 34× slower |
| Small request (18 chars) | 3,128 | 65 | 48× slower |
| Extract URL (97 chars) | 6,854 | 213 | 32× slower |

This anchored pattern with an alternation start and capture group shows
SafeRE's per-match overhead. The pattern is OnePass-eligible but the
alternation prevents literal fast-path.

## Replace Performance (ns/op, lower is better)

| Benchmark | SafeRE | JDK | Ratio |
|---|--:|--:|---|
| Literal replaceFirst (`"b"→"bb"`) | 38 | 61 | **1.6× faster** |
| Literal replaceAll | 127 | 185 | **1.5× faster** |
| Pig Latin replaceAll (backrefs) | 16,520 | 1,230 | 13× slower |
| Digit replaceAll (`\d+`→`"NUM"`) | 5,955 | 647 | 9.2× slower |
| Empty-match replaceAll (`a*`) | 1,533 | 108 | 14× slower |

SafeRE wins on literal replacements (fast-path). For patterns requiring
regex engine dispatch, the per-match overhead dominates.

## PatternSet Multi-Pattern Matching (ns/op)

Matching text against multiple compiled patterns simultaneously.

| Patterns | Unanchored (match) | Unanchored (no match) | Anchored (match) | Anchored (no match) |
|--:|--:|--:|--:|--:|
| 4 | 3,835 | 2,770 | 2,406 | 1,837 |
| 16 | 38,189 | 21,594 | 9,592 | 9,061 |
| 64 | 132,567 | 104,355 | 43,235 | 42,373 |

Anchored matching is 2–3× faster than unanchored. Scaling is roughly linear
in pattern count.

## Fanout & Nested Quantifiers (µs/op, lower is better)

### Unicode Fanout: `(?:[\x{80}-\x{10FFFF}]?){100}[\x{80}-\x{10FFFF}]`

| Text Size | SafeRE | JDK | Ratio |
|--:|--:|--:|---|
| 1 KB | 10.5 | 1.5 | 7× slower |
| 10 KB | 369 | 1.5 | 246× slower |
| 100 KB | 362 | 1.5 | 241× slower |

JDK's backtracking engine quickly fails and returns false; SafeRE's DFA
explores more states. The pattern is a stress test for DFA state explosion.

### Nested Quantifier: `(?:a?){20}a{20}`

| Text Size | SafeRE | JDK | Ratio |
|--:|--:|--:|---|
| 1 KB | 8.4 | 19.2 | **2.3× faster** |
| 10 KB | 50.5 | 191 | **3.8× faster** |
| 100 KB | 465 | 1,975 | **4.2× faster** |

SafeRE's advantage grows with input size, as expected for a linear-time
engine vs. backtracking.

## Compilation Performance (µs/op, lower is better)

| Pattern | SafeRE | JDK | Ratio |
|---|--:|--:|---|
| Simple (`hello`) | 2.15 | 0.11 | 20× slower |
| Medium (datetime with 6 captures) | 10.78 | 0.41 | 26× slower |
| Complex (email regex) | 7.80 | 0.30 | 26× slower |
| Alternation (12 alternatives) | 9.88 | 0.49 | 20× slower |

## Pathological Pattern: `a?{n}a{n}` matched against `a{n}`

This is the canonical backtracking blowup case. The JDK's backtracking NFA
exhibits O(2^n) behavior. SafeRE's linear-time engines handle it in O(n).

### SafeRE scalability (µs/op)

| n | SafeRE |
|--:|--:|
| 10 | 0.063 |
| 15 | 0.085 |
| 20 | 0.103 |
| 25 | 0.124 |
| 30 | 0.144 |
| 50 | 0.235 |
| 100 | 0.450 |

Growth is linear: 10× increase in n → ~7× increase in time.

### SafeRE vs JDK (µs/op)

| n | SafeRE | JDK | Speedup |
|--:|--:|--:|--:|
| 10 | 0.063 | 15.8 | 251× |
| 15 | 0.084 | 613.7 | 7,306× |
| 20 | 0.105 | 27,819 | 264,943× |
| 25 | 0.124 | *(hangs)* | ∞ |

## Find-in-Text Scaling (µs/op, lower is better)

`\b\w+ing\b` on repeated prose, scaling from 1 KB to 1 MB.

| Text Size | SafeRE | JDK | Ratio |
|--:|--:|--:|---|
| 1 KB | 64 | 13.1 | 4.9× slower |
| 10 KB | 482 | 128 | 3.8× slower |
| 100 KB | 4,614 | 1,245 | 3.7× slower |
| 1 MB | 45,714 | 12,524 | 3.7× slower |

SafeRE's find-all-matches loop has per-match overhead that compounds on text
with many matches, but DFA word-boundary support has significantly improved
scaling (previously 28× slower at 1 MB, now 3.7×).

## Analysis

**Where SafeRE wins:**
- **Literal matching** — 8× faster via `String.indexOf()` fast path
- **Literal replacement** — 1.5–1.6× faster for simple replacements
- **Capture groups (≤10)** — Competitive or faster (OnePass engine, 64-bit actions)
- **Hard/pathological patterns** — 59–265,000× faster, the core value proposition
- **Nested quantifiers** — 2–4× faster, scaling advantage grows with input size
- **Easy search on large text** — 1.7–1.9× faster (DFA + prefix acceleration)

**Where JDK wins:**
- **Small-text patterns** — JDK has lower per-match overhead (~30–50ns startup)
- **Find-all on many matches** — Per-match overhead compounds (3.7× at 1 MB)
- **Unicode fanout** — DFA state explosion on high-fanout patterns
- **Compilation** — 20–26× faster (JDK defers work to match time)

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
8. **OnePass 64-bit action encoding** — Raised capture group limit from 6 to 16
   by switching from 32-bit to 64-bit action words.
9. **DFA word boundary support** — Native `\b`/`\B` handling in the DFA avoids
   NFA fallback for word-boundary patterns.

## Remaining Opportunities

- **Compilation** — Pattern compilation is 20–26× slower than JDK. Opportunities
  include caching parsed Regexp trees and lazy OnePass/DFA construction.
- **BitState caching** — A reusable BitState instance per Matcher would reduce
  allocation on repeated `find()` calls.
- **Find-all loop** — Per-match overhead dominates when there are many matches
  (3.7× slower at 1 MB); a bulk-find mode could amortize engine setup cost.

---
*Last updated: 2026-03-24*
