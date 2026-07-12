# Issue 474 Phase 7 Performance Verification

Phase 7 passed on 2026-07-12. All JMH invocations ran serially through
`./run-java-benchmarks.sh`. Routine evidence used the standard configuration; close or surprising
results used `--long` confirmation.

## Revisions and method

- Baseline: `c8f51f3fa99976566ac9a3260df371caedec929e`, committed
  `2026-07-08T02:24:42Z`.
- Issue branch starting point: `6e427eee3eb1ae6666a0080946c0b3428b559c94`, committed
  `2026-07-12T17:20:33Z`, plus the Phase 7 benchmark and disabled-path optimization changes in this
  worktree.
- The baseline ran in a detached worktree with the identical
  `DiagnosticsDisabledBenchmark` source added as benchmark-only scaffolding.
- Ratios below are issue-branch time divided by baseline time; values below 1.0 favor the issue
  branch.

## Disabled diagnostics regression matrix

| Workload | Baseline ns/op | Branch ns/op | Branch / baseline |
|---|---:|---:|---:|
| BitState-sized ambiguous match | 89.412 | 89.369 | 1.000 |
| Dense character-class replacement | 105.013 | 103.021 | 0.981 |
| No-match character-class replacement | 20.690 | 20.449 | 0.988 |
| Long DFA find | 579.022 | 605.815 | 1.046 |
| Sparse DFA replacement | 1,596.580 | 1,638.242 | 1.026 |
| Long NFA match | 13,336.972 | 13,449.810 | 1.008 |
| OnePass match | 59.551 | 59.468 | 0.999 |
| Tiny literal find hit | 17.010 | 15.664 | 0.921 |
| Tiny literal find miss | 15.806 | 15.338 | 0.970 |
| Tiny literal full match | 14.141 | 14.157 | 1.001 |

The geometric mean branch/baseline ratio is 0.994. Individual confidence intervals overlap for the
slower results, so the matrix does not show a statistically convincing material regression.

An initial implementation using a volatile global listener did regress the smallest operations.
CPU profiling showed listener lookup and diagnostics helper frames on the disabled hot path. A
synchronized mutable call site replaced the volatile lookup, and direct local accumulator guards
were used in the literal and character-class fast paths. The final results above include those
changes.

Long-mode confirmation covered the initially regressed no-match replacement and OnePass cases:

| Workload | Baseline ns/op | Branch ns/op |
|---|---:|---:|
| No-match character-class replacement | 20.312 ± 0.248 | 20.716 ± 0.338 |
| OnePass match | 59.697 ± 0.990 | 60.904 ± 1.687 |

The long-mode confidence intervals overlap in both cases.

## Enabled diagnostics overhead

The standard matrix measured an enabled no-op listener and representative `LongAdder` aggregation.
Enabled diagnostics intentionally pay for bounded operation bookkeeping and immutable event
materialization. The full standard-configuration matrix was:

| Workload | Disabled ns/op | No-op ns/op | No-op overhead | LongAdder ns/op | LongAdder overhead |
|---|---:|---:|---:|---:|---:|
| BitState-sized ambiguous match | 89.369 | 254.405 | 2.85× | 260.225 | 2.91× |
| Dense character-class replacement | 103.021 | 255.950 | 2.48× | 262.858 | 2.55× |
| No-match character-class replacement | 20.449 | 161.648 | 7.90× | 171.883 | 8.41× |
| Long DFA find | 605.815 | 800.373 | 1.32× | 799.383 | 1.32× |
| Sparse DFA replacement | 1,638.242 | 1,832.636 | 1.12× | 1,812.957 | 1.11× |
| Long NFA match | 13,449.810 | 13,731.093 | 1.02× | 13,562.986 | 1.01× |
| OnePass match | 59.468 | 213.447 | 3.59× | 218.884 | 3.68× |
| Tiny literal find hit | 15.664 | 169.111 | 10.80× | 172.314 | 11.00× |
| Tiny literal find miss | 15.338 | 213.742 | 13.94× | 161.021 | 10.50× |
| Tiny literal full match | 14.157 | 162.858 | 11.50× | 167.083 | 11.80× |

The fixed event cost dominates tiny operations and becomes a small fraction of long NFA work.
Long-mode confirmation of failed literal find measured 209.789 ± 3.955 ns/op for the no-op listener
and 159.768 ± 1.935 ns/op for `LongAdder`. This counterintuitive ordering persisted across forks and
is attributed to listener-specific JIT escape-analysis and dispatch shape; it does not affect the
disabled-path merge gate.

### Future enabled-mode optimization

Enabled diagnostics currently add approximately 150–200 ns of fixed work per public operation.
This is a possible follow-up issue or PR discussion item, not part of the Phase 7 merge gate.

A reasonable estimate is that allocation-conscious implementation work could reduce the fixed cost
to roughly 60–100 ns without changing the public semantics. Candidate work includes reusing
per-thread accumulator storage, avoiding fresh primitive bookkeeping arrays, lazily materializing
empty or unused collections, specializing events with one strategy and no decisions, and reducing
intermediate record allocation.

A more aggressive callback or API redesign might reach roughly 20–50 ns, but could require an
ephemeral event view, primitive callback fields, sampling, or restrictions on listeners retaining
events. Those tradeoffs would weaken some combination of immutability, listener simplicity, and
retainability, so the allocation-conscious approach should be profiled and attempted first.

## Static analysis cost

| Workload | ns/op |
|---|---:|
| Cached `Pattern.analysis()` | 0.528 ± 0.020 |
| Compile only | 35,494.045 ± 3,523.521 |
| Compile plus first analysis | 36,484.973 ± 2,757.395 |

Compile-only and compile-plus-analysis confidence intervals overlap broadly. Complete analysis is
lazy unless explicitly requested or required by an enabled compilation event, and cached retrieval
is effectively a field access.

## Allocation verification

A final JFR allocation probe installed `SafeReMatchDiagnostics.NONE`, warmed the literal matching
path, and recorded repeated public operations. It found no allocations of diagnostic event or
bookkeeping classes. Enabled operation-count tests separately verify exactly one callback per public
operation regardless of internal match count.

Artifacts are outside the repository under `/tmp/issue474-*`, including the raw standard and long
JMH logs, CPU profile, JFR recording, and printed allocation events.

## Gate decision

Phase 7 passes:

- disabled diagnostics have no diagnostic allocations;
- the final standard matrix has no statistically convincing material throughput regression;
- initially surprising tiny-operation results were confirmed with `--long` after optimization;
- enabled no-op and `LongAdder` costs are measured and documented; and
- explicit static analysis cost is isolated from compilation and matching.
