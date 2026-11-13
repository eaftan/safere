---
name: optimize-workload
description: Optimize a SafeRE workload from an issue or textual description by profiling, comparing approaches, implementing the strongest option, running the review-fix-loop skill, and measuring target gains and plausible regressions.
---

# Optimize SafeRE Workload

Deliver a measured implementation improvement with preserved correctness and an explicit account of
regression risks. Accept an issue number/URL or a textual workload description. Reading an issue
as input does not authorize posting to it, closing it, or publishing code.

## Establish the target and baseline

- Read the repository's contributor instructions, performance guidance and
  [references/safere.md](references/safere.md). Follow the SafeRE workflow throughout.
- For an issue, read its body and relevant discussion, linked evidence and unresolved requirements.
  For a textual description, map it to executable operations, inputs and expected results. If it
  names a backlog, choose a coherent initial target rather than silently implementing the entire
  backlog. Honor supplied scope, providers, time limits and measurement preferences.
- Identify the exact API, operation, input representation, timing boundary and relevant input
  shapes/sizes. Verify output semantics before treating competitor ratios as optimization evidence.
  Establish whether the target is cold initialization, steady-state execution, throughput,
  allocation, retained memory, or scaling; these need different measurements.
- Record the baseline revision and any source patch, harness/workload versions, runtime, settings
  and commands. Preserve unrelated work; use isolated worktrees/build outputs when necessary.
  Do not switch a dirty checkout to run comparisons.
- Reproduce the workload with a repository runner. Create a minimal representative benchmark if
  none exists, in the project's established workload format. Ensure its output is consumed and
  validated. Plan comparable before/after controls now, including new workloads identified below,
  so the baseline can measure them before production changes.

## Profile and compare approaches

1. Profile the reproduced workload before proposing implementation changes. Prefer the project's
   CPU profiler; add allocation or memory profiling when evidence indicates those costs. For cold
   initialization, capture the first operation; a warmed loop cannot explain first-use latency.
   Confirm that the intended engine/path and expensive work are actually sampled.
2. Connect samples to source code and input scaling. Distinguish observed costs from hypotheses.
   If the suspected bottleneck does not appear, investigate the discrepancy before editing it.
3. Present several materially different approaches, normally two or three, with their mechanism,
   expected benefit, affected paths, correctness/complexity risks, memory/initialization costs and
   likely regressions. Consider whether removing unnecessary work beats adding another fast path.
   Do not invent alternatives merely to reach a count; explain if only one viable option remains.
4. Select the best supported approach and briefly explain the choice in a progress update. Weigh
   target benefit, frequency/coverage, algorithmic guarantees, regression exposure and complexity.
   Proceed with implementation without an approval checkpoint unless a real constraint requires it.

## Implement and verify correctness

- Fix the workload class rather than recognizing the example's pattern or input. Preserve observable
  results and algorithmic/stack-safety contracts. Make the proof or invariant explicit when changing
  search bounds, pruning, cache behavior, dispatch or fallback paths.
- Add focused correctness coverage for the changed boundaries and fallbacks. If diagnosis exposes
  an actual behavior bug, follow the repository's bug-fixing discipline, including failing-first
  regression coverage where required.
- Reprofile when needed to establish whether the intended work was removed or shifted elsewhere.
  Do not use profiled timing as ordinary before/after evidence.
- Reject an approach that yields no measured improvement or breaks required contracts. Revise the
  hypothesis or try another supported option; preserve evidence from abandoned experiments locally.
  Do not broaden into unrelated optimization work to manufacture a successful outcome.

## Run the review/fix loop

Resolve and read the installed **$review-fix-loop** skill and execute it on the complete optimization
change, including benchmark and test additions, at **P2 or higher** unless the user specifies a
different threshold. Follow that skill's delegation requirements: fresh read-only reviewers, the
complete system review-agent instructions, sequential passes, verification after fixes, and a final
pass with no in-scope findings. Do not replace it with self-review. If the dependency or delegation
is unavailable, report the exact blocker and leave the review step explicitly incomplete.

## Measure regressions and final benefit

Build a control set from the changed mechanism and callers, not just neighboring benchmark names.
Use existing workloads and add plausible missing ones. Select applicable dimensions rather than
exhaustively multiplying them:

- Cases that enter the new path, narrowly miss its gate, and use the fallback; successful and failed
  operations; early/late matches, dense/sparse candidates and false positives.
- Tiny inputs where dispatch/setup dominates and larger inputs where scans/state growth dominate;
  multiple sizes where the mechanism could change scaling.
- Relevant representations and character distributions, captures versus existence-only results,
  repeated calls, matcher reuse/reset/regions, replacement and compilation/initialization effects.
- Features sharing changed helpers, caches or engine-selection logic, including cases the new
  heuristic rejects. Consider patterns outside the original workload family.

Explain why each control could regress. For gaps in the suite, declare new representative workloads
and measure them on **both** baseline and candidate with identical definitions. Keep one-off
experiments in the project's investigation area unless they warrant durable suite coverage.

Run the target and controls after the review fixes using the repository's standard runners and
settings. Run measurements sequentially. Keep production-code revisions comparable: input recipes,
patterns, flags, result consumption, harness code and build settings must agree. If new declarations
are needed, apply the same benchmark-only changes to a controlled baseline. Prefer existing revision
comparison tooling when its prerequisites hold; do not bypass its comparability guards. Do not create
commits merely to satisfy a benchmark tool without authorization; recorded patches and isolated
worktrees can support an uncommitted comparison.

Report per-workload baseline/candidate times, ratios, errors or spread, and relevant input sizes.
Distinguish noise from measured regressions; use confirmation measurements as project guidance and
user constraints permit. Do not hide a regressed control inside an aggregate or substitute
cross-engine rankings for before/after evidence.

For a material regression, investigate and refine or reject the approach. Do not silently accept a
tradeoff that conflicts with the user's performance goal. If no supported approach improves the
target without unacceptable costs, report that result with evidence and any decision needed.
After code changes from regression analysis, rerun affected correctness checks and measurements,
then run $review-fix-loop again on the final diff. Benchmark additions are part of that review too.
Avoid repeating unchanged checks without new risk or evidence.

## Finish

Provide a concise account of:

- The bottleneck, alternatives considered, selected mechanism and preserved invariant.
- Before/after target results and existing/new controls, including regressions and uncertainty.
- Correctness verification, final review status and remaining limitations.
- Revisions/patches, commands and local evidence locations needed to reproduce the comparison.

Completion requires a measured target improvement, assessed regression controls and no remaining
in-scope review findings. Clearly identify incomplete steps or unsuccessful experiments rather than
claiming an optimization is finished. Keep focused evidence local and summarize it in the PR if one
is requested. This skill does not itself authorize commits, pushes, PRs or issue updates.
