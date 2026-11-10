# Decision: adopt DFA-first in a separate change

The investigation supports selecting DFA-first as the next production policy.
The investigation PR preserves the experiments and evidence; the dependent policy
PR implements the decision.

## Evidence used for the decision

Use the complete expanded comparison, rather than the initial candidate screen.
Main, DFA-first, replay credit, and greedy work have identical membership across
80 standard-mode cases: 40 scenarios across String and UTF-8. Other policies have
partial coverage or belong to the earlier study and are not ranked on this set.

| Policy | Geometric-mean ratio | P90 ratio | Worst-case ratio |
|---|---:|---:|---:|
| Production | 1.000 | 1.000 | 1.000 |
| DFA-first | 0.349 | 1.264 | 2.193 |
| Replay credit | 0.362 | 1.202 | 2.228 |
| Greedy work estimates | 0.378 | 1.169 | 2.335 |

Ratios are candidate time / the measured production baseline time; lower is better.
Each exact case has equal weight. P90 is the nearest-rank 90th percentile of the
80 case ratios (the 72nd sorted value), not an individual-operation latency
percentile. These are point estimates, without confidence intervals for the
aggregate or percentile. Long confirmations, shared-worker measurements, phase
clocks, and sampled latency are separate comparisons.

The inputs and protocols are constructed mechanism workloads, not a measured
application traffic distribution. These aggregates do not predict a deployment
speedup or establish statistical superiority between close policies.

## Why DFA-first

DFA-first has the lowest geometric-mean and worst-case point estimates in the full
matrix, while replay and work have lower P90 ratios. It also has the simplest
implementation: remove history-driven capture-first speculation from unanchored
searches without adding adaptive state, work instrumentation, or tuning knobs.
It avoids the costly speculative scans seen on rejecting inputs.

Successful String extraction can regress substantially, including on the existing
stack document. UTF-8 extraction can favor DFA-first on the same regex. Replay
preserves some String extraction paths better, but its fresh-matcher regression
removes the worst-case advantage suggested by the smaller screen. Greedy work's
counts are not calibrated across engines; periodic and probabilistic exploration
also pay for repeated expensive probes. No policy dominates every workload.

The [review feedback](https://github.com/eaftan/safere/pull/1140#issuecomment-6682360802)
supports DFA-first as the first change and reports an additional forward-DFA-then-
capture variant that did not justify its complexity. That additional experiment
is reported by the reviewer and is not part of the archived matrix.

## Implementation scope and follow-ups

The dependent change should preserve anchored capture preference, shared capture
demand for already-required exact fallbacks, operation-required captures, and
public matching/capture semantics. It should include focused strategy, lifecycle,
and work-bound coverage plus measurements of the actual implementation against
its parent revision. It should add no adaptive policy state or new eligibility
threshold.

Subsequent work can profile successful String capture recovery, reassess the
512-unit threshold, and select a few recurring benchmarks. Those are separate
decisions; the investigation corpus remains opt-in and excluded from release
collections. The historical writeups and plain-text summaries remain unchanged.

## Sources

- [Expanded study and limitations](results/decision-rules/README.md)
- [Normalized standard and diagnostic results](results/decision-rules/derived/normalized-results.jsonl)
- [Complete plain-text tables](results/decision-rules/derived/summary.txt)
- [Historical ownership and lease study](results/README.md)

To reproduce this table, select `main-standard` and `dfa-standard` rows, plus
`replay` and `work` from `candidates-core` and `selected-robustness` in the normalized
results. Pair each candidate with the `main-standard` row of the same scenario and
representation, then compute the geometric mean, nearest-rank P90, and maximum.
