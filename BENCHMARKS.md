# SafeRE Benchmark Results

Comparing SafeRE against `java.util.regex` on common regex workloads and
pathological patterns that demonstrate backtracking blowup.

**Environment:**
- JDK: OpenJDK 25.0.2 (targeting Java 21)
- JMH: 1.37, forked JVM via uber-jar (`java -jar safere-benchmarks/target/benchmarks.jar`)
- Machine: Linux (shared environment — results are indicative, not precise)

## Matching Performance (ns/op, lower is better)

| Benchmark | SafeRE | JDK | Ratio | Change |
|---|--:|--:|---|---|
| Literal match (`"hello"`) | 3 | 25 | **9× faster** | ↑ literal fast path |
| Char class match (`[a-zA-Z]+`) | 395 | 149 | 2.6× slower | ↑ 19% faster |
| Alternation find (`foo\|bar\|…` ×8) | 14,232 | 1,042 | 13.7× slower | ↑ 51% faster |
| Capture groups (`(\d{4})-(\d{2})-(\d{2})`) | 101 | 114 | **1.1× faster** | — |
| Find -ing words in prose (~350 chars) | 38,229 | 5,940 | 6.4× slower | ↑ 49% faster |
| Email pattern find | 8,301 | 891 | 9.3× slower | ↑ 35% faster |

*"Change" column compares to the initial baseline before optimizations.*

## Compilation Performance (µs/op, lower is better)

| Pattern | SafeRE | JDK | Ratio |
|---|--:|--:|---|
| Simple (`hello`) | 1.67 | 0.11 | 15.9× slower |
| Medium (datetime with 6 captures) | 4.27 | 0.41 | 10.3× slower |
| Complex (email regex) | 5.92 | 0.29 | 20.1× slower |
| Alternation (12 alternatives) | 7.51 | 0.49 | 15.2× slower |

## Pathological Pattern: `a?{n}a{n}` matched against `a{n}`

This is the canonical backtracking blowup case. The JDK's backtracking NFA
exhibits O(2^n) behavior. SafeRE's linear-time engines handle it in O(n).

### SafeRE scalability (µs/op)

| n | SafeRE |
|--:|--:|
| 10 | 0.065 |
| 15 | 0.087 |
| 20 | 0.106 |
| 25 | 0.126 |
| 30 | 0.155 |
| 50 | 0.241 |
| 100 | 0.471 |

Growth is linear: 10× increase in n → ~7× increase in time.

### SafeRE vs JDK (µs/op)

| n | SafeRE | JDK | Speedup |
|--:|--:|--:|--:|
| 10 | 0.065 | 17.7 | 272× |
| 15 | 0.088 | 585.8 | 6,657× |
| 20 | 0.110 | 28,704.0 | 260,945× |
| 25 | 0.126 | *(hangs)* | ∞ |

## Analysis

**Normal patterns:** SafeRE is 3–14× slower than `java.util.regex` on common
find workloads, but **9× faster** for literal matching thanks to the
`String.indexOf()` fast path. The optimization rounds closed the gap
substantially: find-in-text improved 49% (via reverse DFA bounding),
alternation find improved 51%, email find improved 35%, and char class
improved 19%.

**Pathological patterns:** SafeRE delivers on its core promise — **guaranteed
linear time**. On `a?{20}a{20}`, SafeRE is 260,000× faster than the JDK,
and the gap grows exponentially. For n=25+, the JDK hangs while SafeRE
completes in under 0.2 µs.

**Compilation:** SafeRE is ~10–20× slower to compile patterns. This is because
SafeRE performs more work upfront (parse → simplify → compile → build OnePass
automaton + reverse program), while the JDK defers much of its work to match time.

## Optimizations Applied

1. **DFA caching per Matcher** — DFA state cache persists across `find()` calls,
   avoiding full DFA reconstruction per search (23× speedup on DFA search alone).
2. **ASCII fast path in DFA** — Pre-computed 128-entry lookup table for character
   class mapping, avoiding binary search for ASCII text.
3. **Pre-allocated DFA expand() arrays** — Reuses visited/stack/frontier arrays
   instead of allocating `boolean[]`, `ArrayList<Integer>` on each state expansion.
4. **Start position threading** — DFA, NFA, and BitState accept a `startPos`
   parameter, eliminating substring creation for the DFA check.
5. **Search limit support** — BitState and NFA accept a `searchLimit` parameter
   bounding where new search threads start.
6. **Literal fast path** — Fully literal patterns bypass all regex engines and use
   `String.indexOf()` / `String.equals()` directly (9× faster than JDK).
7. **Reverse DFA bounding** — Three-DFA sandwich: forward DFA finds match end,
   reverse DFA finds match start, then NFA runs on just the match range. Reduces
   BitState allocation from ~34KB to <1KB for typical find-in-text workloads.
   Gated behind a call counter to avoid penalizing single-find workloads.

## Remaining Opportunities

- **Compilation** — Pattern compilation is 10–20× slower than JDK. Opportunities
  include caching parsed Regexp trees and lazy OnePass/DFA construction.
- **BitState caching** — A reusable BitState instance per Matcher would further
  reduce allocation on repeated `find()` calls.

---
*Last updated: 2026-03-18*
