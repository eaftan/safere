# Adaptive capture-demand decision rules

This extends the historical #1075 study with five decision-rule candidates:
recent capture-consumption fraction, consumption plus rejection history,
replay-driven credit, work-based estimates, and uncertainty-based probabilistic
exploration. Greedy work estimates and deterministic exploration separate the
value of exploration from the value of randomization.

[PLAN.md](PLAN.md) records parameters and collection scope fixed before timing.
The original production policy remains unchanged on this PR branch. All library
changes are preserved as an opt-in prototype, applied only to a separate detached
checkout of main at `0e6c0069424f9e8e1bef1eb602d6fab41c706c16`.

## Reproduction

First create a detached worktree at the pinned main revision. Overlay
`run-java-benchmarks.sh` and `safere-benchmarks/` from frozen investigation revision
`aa274ea98549fb7228eb574ffbdb42642f5be25c`, rather than the moving PR tip. The
archived source hashes and overlays identify the measured harness exactly, even
after this branch is rebased or the shared benchmark framework changes. Use
identical benchmark sources and definitions for main and the prototype.

Collect unmodified main before applying the prototype:

```bash
python3 safere-benchmarks/investigations/capture-demand-policy/adaptive/run.py \
  /tmp/safere-1075-adaptive benchmark-results/adaptive-main \
  --stage core --policies main
```

Apply the prototype explicitly, then collect selected candidates:

```bash
python3 safere-benchmarks/investigations/capture-demand-policy/adaptive/apply.py \
  /tmp/safere-1075-adaptive
python3 safere-benchmarks/investigations/capture-demand-policy/adaptive/run.py \
  /tmp/safere-1075-adaptive benchmark-results/adaptive-candidates \
  --stage core --policies fraction joint replay work periodic random
```

`apply.py` requires the pinned detached checkout and validates source hashes. It
refuses unexpected local edits. `--restore` reverses the tracked-file patch and
removes only the known prototype files. Clean the library build after switching
source states so removed classes cannot remain in the benchmark JAR.

Use `apply.py --control dfa` or `--control localSticky` for the earlier minimal
control patches. Collect each separately and restore it with the same `--control`
and `--restore` options before switching. The collector checks their exact Matcher
hashes and passes an explicit control marker into JMH forks. They contain no
adaptive framework bookkeeping.

Other collection stages are `robustness`, `shared`, `phases`, `latency`, `seeds`,
and `long`. Each takes an explicit policy list and a fresh output directory.
Use `--seeds 1 17 73` for seed sensitivity, `--scenarios` for an explicit subset,
and `--smoke` for execution checks. No-fork smoke checks allow one policy and seed
per process. Standard and long modes retain the shared wrapper's fork, warmup,
and measurement settings. `latency` uses JMH SampleTime for individual document
scans; the other ordinary stages measure complete cycles. `phases` includes
separate diagnostic clocks and must not be pooled with ordinary cycle timing.

The collector verifies the runtime policy and seed, exact result membership,
workers, forks, and diagnostic counters. It stores the commands, source hashes,
source overlay, logs, raw JMH JSON, and process exit status. It never applies a
policy or mutates library sources automatically.

## Prototype scope and cost accounting

The readable prototype sources are in [prototype/](prototype/); the tracked-file
changes are in [policies/adaptive.patch](../policies/adaptive.patch). Tests cover
retirement, repeated reads, delayed snapshots, reset/input changes, bounds/regions,
Pattern changes, replacements, both representations, bounded state, and repeatable
random histories. An original-policy assertion remains enabled for main and its
observation control; other candidates deliberately vary that internal choice.

The prototype adapts unanchored `find()` producer selection within existing
eligibility limits. Anchored public operations preserve main's preference.
Shared capture-demand recording and operation-required captures remain intact.
Training is based on completed find results; snapshots count as consumption when
created, independently of later reads of the immutable snapshot.

Work estimates are proxies rather than elapsed-time measurements. They count
BitState's existing logical instruction steps, DFA forward/reverse transition
attempts, and one scan-call unit plus one unit per sixteen skipped native units.
State-accelerator skips use the same proxy. OnePass replay uses native match length;
an NFA fallback without equivalent counters receives a conservative saturated
charge. Costs are normalized by input remaining at the search's start. The
observation control enables the same work accounting while retaining main's
producer rule, exposing its overhead. `patchedMain` disables policy state and
counters while retaining the source patch's layout and eligibility checks.

Every producer choice retains the existing bounded fallback. Randomization makes
one bounded choice; it adds no retry loop and does not change the worst-case
matching contract. State and work counters saturate, while the fixed-width random
state intentionally wraps. There is no shared RNG or wall-clock feedback in the
policies. A seed repeats a decision stream for identical histories; time-based JMH
warmup can execute different numbers of decisions across runs.

The completed [results and assessment](../results/decision-rules/README.md) include
all candidate comparisons, selected expanded coverage, exploration sensitivity,
and the limitations that prevent a production recommendation. Raw cohorts, source
identities, normalized rows, deterministic traces, and checksums are preserved
separately from the historical study. Its original `summary.txt` is unchanged;
the new study also has a plain-text summary.

New measurements belong under ignored `benchmark-results/` during collection.
After verifying successful completion, `archive.py` copies the completed cohorts
and diagnostic evidence into a fresh publication directory without rewriting raw
files. `analyze.py` and `analyze-traces.py` audit and regenerate derived artifacts;
the report includes the exact commands for the preserved archive. No experimental
policy is adopted by this PR.
