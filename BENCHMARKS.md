# SafeRE Benchmark Results

Comparing SafeRE against `java.util.regex` on common regex workloads and
pathological patterns that demonstrate backtracking blowup.

**Environment:**
- JDK: OpenJDK 25.0.2 (targeting Java 21)
- JMH: 1.37, no fork (`-f 0`), 3 warmup × 1s, 5 measurement × 1s
- Machine: Linux (shared environment — results are indicative, not precise)

## Matching Performance (ns/op, lower is better)

| Benchmark | SafeRE | JDK | Ratio |
|---|--:|--:|---|
| Literal match (`"hello"`) | 42 | 25 | 1.7× slower |
| Char class match (`[a-zA-Z]+`) | 472 | 141 | 3.3× slower |
| Alternation find (`foo\|bar\|…` ×8) | 28,454 | 948 | 30× slower |
| Capture groups (`(\d{4})-(\d{2})-(\d{2})`) | 101 | 110 | **1.1× faster** |
| Find -ing words in prose (~350 chars) | 62,160 | 5,695 | 10.9× slower |
| Email pattern find | 12,617 | 626 | 20× slower |

## Compilation Performance (µs/op, lower is better)

| Pattern | SafeRE | JDK | Ratio |
|---|--:|--:|---|
| Simple (`hello`) | 1.71 | 0.12 | 14.6× slower |
| Medium (datetime with 6 captures) | 4.35 | 0.47 | 9.3× slower |
| Complex (email regex) | 6.18 | 0.32 | 19.3× slower |
| Alternation (12 alternatives) | 7.70 | 0.50 | 15.4× slower |

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

**Normal patterns:** SafeRE is 2–30× slower than `java.util.regex` on common
workloads. This is expected — the JDK engine has decades of optimization and
can exploit backtracking heuristics that work well on non-pathological input.
The biggest gap is on `find()` operations with alternation patterns.

**Pathological patterns:** SafeRE delivers on its core promise — **guaranteed
linear time**. On `a?{20}a{20}`, SafeRE is 260,000× faster than the JDK,
and the gap grows exponentially. For n=25+, the JDK hangs while SafeRE
completes in under 0.2 µs.

**Compilation:** SafeRE is ~10–20× slower to compile patterns. This is because
SafeRE performs more work upfront (parse → simplify → compile → build OnePass
automaton), while the JDK defers much of its work to match time.

## Optimization Opportunities

- **Alternation/find:** The DFA state cache is rebuilt per `find()` call. Caching
  the DFA across calls could dramatically improve repeated-find performance.
- **Prefix acceleration:** Already implemented for literal prefixes. Could be
  extended to handle more prefix types.
- **String operations:** The JDK benefits from intrinsified `String` operations
  that SafeRE doesn't yet exploit.
- **Compilation:** Consider caching parsed/compiled results for frequently used
  patterns.

---
*Last updated: 2026-03-17*
