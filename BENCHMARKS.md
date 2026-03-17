# SafeRE Benchmark Results

Comparing SafeRE against `java.util.regex` on common regex workloads and
pathological patterns that demonstrate backtracking blowup.

**Environment:**
- JDK: OpenJDK 25.0.2 (targeting Java 21)
- JMH: 1.37, no fork (`-f 0`), 3 warmup × 2s, 5 measurement × 2s
- Machine: Linux (shared environment — results are indicative, not precise)

## Matching Performance (ns/op, lower is better)

| Benchmark | SafeRE | JDK | Ratio | Change |
|---|--:|--:|---|---|
| Literal match (`"hello"`) | 43 | 23 | 1.9× slower | — |
| Char class match (`[a-zA-Z]+`) | 382 | 133 | 2.9× slower | ↑ 19% faster |
| Alternation find (`foo\|bar\|…` ×8) | 13,805 | 857 | 16.1× slower | ↑ 51% faster |
| Capture groups (`(\d{4})-(\d{2})-(\d{2})`) | 100 | 112 | **1.1× faster** | — |
| Find -ing words in prose (~350 chars) | 75,219 | 5,616 | 13.4× slower | — |
| Email pattern find | 8,184 | 555 | 14.7× slower | ↑ 35% faster |

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

**Normal patterns:** SafeRE is 2–16× slower than `java.util.regex` on common
workloads. The optimization round closed the gap significantly: alternation find
improved 51% (30× → 16× vs JDK), email find improved 35% (20× → 15× vs JDK),
and char class matching improved 19%.

**Pathological patterns:** SafeRE delivers on its core promise — **guaranteed
linear time**. On `a?{20}a{20}`, SafeRE is 260,000× faster than the JDK,
and the gap grows exponentially. For n=25+, the JDK hangs while SafeRE
completes in under 0.2 µs.

**Compilation:** SafeRE is ~10–20× slower to compile patterns. This is because
SafeRE performs more work upfront (parse → simplify → compile → build OnePass
automaton), while the JDK defers much of its work to match time.

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

## Remaining Opportunities

- **BitState allocation** — BitState allocates bitmaps proportional to text length.
  For repeated `find()` on long texts, this is the dominant cost. A cached/reusable
  BitState instance per Matcher would help.
- **Reverse DFA** — RE2 uses a reverse DFA to find match start after the forward
  DFA finds match end. This would allow bounded capture extraction on a small
  substring, avoiding the full-text BitState cost.
- **Compilation** — Pattern compilation is 10–20× slower than JDK. Opportunities
  include caching parsed Regexp trees and lazy OnePass/DFA construction.

---
*Last updated: 2026-03-17*
