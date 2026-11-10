# Adaptive decision rules: experiment plan

Library baseline: `0e6c0069424f9e8e1bef1eb602d6fab41c706c16`
(`2026-11-04T04:50:45Z`). All candidates share one harness and input definitions.
The production sources on the PR branch remain unchanged; experimental source
changes are supplied as an opt-in patch applied only in an isolated checkout.

Compare unmodified main, DFA-first, local sticky, consumption fraction, consumption
plus rejection history, replay credit, greedy work estimates, uncertainty-based
probabilistic exploration, and deterministic exploration at matching intervals.
An instrumented-main control measures common bookkeeping overhead. Shared Pattern
priors are deferred until decision-rule evidence justifies a separate comparison.

Before implementation, profile main on successful and rejecting scheme inputs.
Keep profiles separate from unprofiled measurements. Screen with standard wrapper
settings, confirm important or close findings in long mode, and use three recorded
seeds for probabilistic sensitivity. Run all measurements sequentially.

## Predeclared prototype parameters

These are mechanism probes, not proposed production thresholds. Do not tune them
against collected timing results.

- Consumption and meaningful-search histories: at most 32 observations each.
  A successful result is observed once at retirement, after any capture reads.
  Require four retired successes before the consumption-fraction decision.
- Consumption preference: enter at 75% reads per successful result, leave at 50%.
- Demand plus search outcomes: additionally enter when the estimated probability
  of a hit with consumed captures reaches 50%, leave at 25%.
- Meaningful search: at least 16 native units remain at its start. Cheap terminal
  failure is not treated as an equally expensive rejection observation.
- Replay credit: bounded to [-32, 32]; an actual user-triggered replay adds four,
  a consumed capture-first result adds one, an unused success subtracts one, and
  a meaningful failed search subtracts four. Prefer capture-first above zero.
- Work estimates: saturating fixed-point moving means with weight 1/8 for new
  observations, normalized by remaining input units. Count BitState instruction
  steps and DFA work, with explicitly documented approximations for accelerated
  skips and unsupported exact engines. Measure their instrumentation overhead.
- Work policies first observe DFA and, after capture demand appears, sample the
  capture producer. Greedy work then exploits its estimate without exploration.
- Exploration: one in four eligible decisions while uncertain, otherwise one in
  32. Uncertainty means fewer than four observations for either producer, costs
  within 25%, or a recent cost observation differing by more than 50% from its
  estimate. A cost surprise keeps the next 32 decisions uncertain.
- Compare random exploration with a deterministic schedule using the same
  intervals. Use independent Matcher-local bounded state, no shared random state,
  and repeatable seeds 1, 17, and 73. Each search makes at most one producer choice;
  existing bounded fallback remains intact.

## Coverage and reporting

Vary document hit rate independently from reads conditional on a hit. Include
periodic, shuffled, and blocked schedules; capture/Boolean/capture and changing
hit-rate phases; fresh cursors; cursors retained per input; String reset(newInput);
UTF-8 region rotation; shared Patterns; both representations; multibyte text;
existing successful-extraction documents; and boundary controls. UTF-8 has no
reset(newInput) API, so region rotation is reported separately.

Use independent JDK checksums, with validation and timing following the same
cursor lifetime. Keep validation Patterns separate from measured Patterns. Normal
cycle timing has no per-phase clocks. A separate phase-diagnostic runner records
phase time and documents; do not pool its estimates with ordinary cycle timings.

Before collecting, cover history retirement, repeated reads, delayed reads,
snapshots, replacements, reset/input changes, bounds/regions, Pattern changes,
bounded counters, deterministic seed behavior, and fixed work/engine-call bounds.
Document remaining implementation or measurement limitations explicitly.

## Staged collection fixed before timing

The core screen uses 32 exact rows per policy: both families and representations,
with all-hit text, rare-hit text, one-in-four reads, Boolean-only consumption,
rare-hit input/region rotation, shuffled half-hit rotation, read-use transitions,
and the two existing application documents. Report every candidate on this common
membership. Select the two adaptive candidates with the lowest equal-row geometric
mean time/main ratio for expanded coverage; also report their worst regressions.
This screening score is a mechanism comparison, not a representative deployment mix.

Run remaining scenarios, shared-Pattern cases, and separate phase diagnostics for
main, DFA-first, and the selected two candidates. Long-confirm successful extraction,
rare rejection, rotating inputs, usage transitions, and application documents.
Check probabilistic exploration on seeds 1/17/73 independently of whether it is a
screening winner; compare it with deterministic exploration on common membership.
Include an original-policy instrumentation control and a patch-disabled control
so bookkeeping and code-layout costs remain visible.

Adaptive training is scoped to completed find results. Anchored public operations
retain main's producer preference, and operation-required captures remain mandatory.
Phase diagnostics include clocks; work traces enable extra counters. Neither is
pooled with ordinary uninstrumented cycle timing.

Individual-document latency uses a separate JMH SampleTime method advancing the
same phased schedule one document at a time. Keep its means and percentiles
separate from complete-cycle averages. Compare main, DFA-first, and selected
candidates on the declared latency subset to expose occasional expensive choices.
