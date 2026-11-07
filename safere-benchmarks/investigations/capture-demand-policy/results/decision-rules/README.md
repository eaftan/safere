# Adaptive capture-demand policies: results and assessment

Recent capture consumption alone does not resolve the tradeoff: captures can be
read on every hit while almost every document rejects. Adding search outcomes or
observed work avoids much of that cost. Replay credit and greedy work estimates
led the adaptive screen, but both regress on fresh successful String matchers;
no candidate establishes a better general default.

Probabilistic exploration shows a real recovery benefit in deterministic transition
traces, yet its repeated expensive probes substantially worsen rare-hit time and
tail latency compared with greedy work. These results support keeping the
production policy while deciding which tradeoffs merit a narrower experiment.

This study extends [#1075](https://github.com/eaftan/safere/issues/1075) with actual
consumption-rate, outcome-aware, replay-aware, and work-aware decision rules,
including probabilistic exploration. It is an opt-in investigation, excluded from
release collections and recurring reports. The PR retains main's production policy.

## What was compared

“Main” is library revision
`0e6c0069424f9e8e1bef1eb602d6fab41c706c16` (`2026-11-04T04:50:45Z`).
All variants use the same benchmark Java, inputs, scheduling, and wrapper settings.
The earlier capture-policy study has a different main revision and harness;
its timings are not pooled into these comparisons.

Main's sticky Pattern hint has two roles: selecting a capture-capable producer
before DFA rejection, and collecting captures during an exact fallback that is
already required. Every candidate preserves the latter role. Current-operation
capture requirements and current-result availability remain separate from history.

| Policy | Rule |
|---|---|
| Main | One shared sticky Pattern bit, set by inner-capture consumption. |
| DFA-first (`dfa`) | Minimal earlier #1073 patch: unanchored find bypasses history-driven capture-first speculation. Anchored preference is preserved. |
| Local sticky (`localSticky`) | Minimal earlier patch: one Matcher-local bit, set by its own capture consumption, with no Pattern seeding or forgetting. |
| Consumption fraction (`fraction`) | Last 32 retired successful results; any inner-capture access counts once per result. After four observations, enter capture-first at 75% consumption and leave at 50%. |
| Consumption and outcomes (`joint`) | The same consumption rule, additionally requiring an estimated hit-with-consumption probability of 50% to enter and 25% to leave. Search outcomes use a separate 32-observation window. |
| Replay credit (`replay`) | Credit in [-32,32]: an actual user-triggered extraction replay adds four; a consumed capture-first result adds one; an unused success subtracts one; a meaningful failure subtracts four. Prefer capture-first above zero. |
| Greedy work (`work`) | After observing capture demand, sample both producers and prefer the lower moving estimate of work per remaining native input unit. New observations have weight 1/8. |
| Periodic exploration (`periodic`) | The same work estimates, with deterministic exploration of the other producer at the uncertainty-dependent interval below. |
| Probabilistic exploration (`random`) | The same estimates, but choose the alternative with probability 1/4 while uncertain and 1/32 otherwise. |

The five requested candidate families are fraction, joint, replay, work, and
probabilistic exploration. Periodic exploration is the control for the value of
randomness. None sets engine-selection probability directly from capture frequency.

For exploration, “uncertain” is a heuristic: fewer than four observations of either
producer, estimated costs within 25%, or a recent cost differing by over 50% from
its moving estimate. A surprise keeps the next 32 decisions uncertain. Greedy work
has the same bootstrap observations but no continuing exploration. All work
policies start with DFA-first until their own matcher observes capture demand.

Adaptive training concerns completed unanchored find results. A result's
consumption observation is finalized at retirement; repeated group reads count
once, and creating a full snapshot counts as consumption. Reset/input/region
changes retain local history; changing Pattern restarts it. Searches starting
with fewer than 16 native units remaining do not enter the outcome/work samples,
so cheap terminal failures do not dilute those estimates equally with full scans.

The existing 512-native-unit eligibility guard, BitState capacity checks, and
bounded fallback remain intact. Every search makes at most one adaptive producer
choice. Fixed-size state and saturating counters add no rescan or retry loop.
Randomization preserves the existing worst-case matching contract.

Two additional controls measure framework cost: `patchedMain` keeps the source
patch but disables policy state and work counters; `observe` retains main's shared
producer rule while enabling the full bookkeeping and work instrumentation. The
minimal DFA/local-sticky controls contain no adaptive framework overhead.

## Work estimates and measurement boundaries

Work is a proxy, not a clock measurement. The estimator combines existing
BitState logical instruction counts, instrumented DFA transition attempts, and
scan-call/length proxies. OnePass replay uses match length; NFA fallback receives
a conservative saturated charge. Costs are normalized by remaining native input.
These units do not calibrate different engines' instruction costs, capture-string
allocation, cache construction, or every auxiliary path. Instrumentation needed by
a policy is included in that policy's timing.

The environment is OpenJDK 26.0.2, JMH 1.37, the default scan provider, and the
virtualized i7-11700K host recorded with the raw captures. Standard runs use two
forks, two 500 ms warmups and five 500 ms measurements per fork. Long runs use two
forks, three 1 s warmups and five 1 s measurements. All benchmarks run sequentially
through the shared Java wrapper and global measurement lock.

Ordinary cycle timing scans three phases of 512 documents (1,536 per invocation).
Tables normalize those cycles to time per document. Individual-document latency
uses a separate JMH SampleTime method; its means/percentiles are compared with the
same method, not pooled with cycle averages. Phase diagnostics use separate
clocks, divided by document counts within each measurement iteration before
averaging. Shared-Pattern runs use four workers, split evenly between capture and
Boolean consumers; their score is a worker average, not separate role latencies.

## Inputs and comparison coverage

These are constructed mechanism workloads using the existing stack-trace and
custom-scheme regexes. Valid/invalid frames and links, ASCII/multibyte padding,
and schedules were created for this investigation. They are not production traces.
The existing four-frame stack document and scheme document provide controls with
actual capture-text consumption.

The stack regex recognizes a Java-style stack frame and captures its class,
method, file, and line. Synthetic cases consume the class capture; the existing
document consumes class and line. The scheme regex recognizes Markdown-style
`[label](customScheme://...)` links; these cases consume the scheme target.
Every document is scanned with repeated `find()` through exhaustion.

All-hit means every document matches; rare-hit means one in sixteen matches;
half-hit means one in two. Capture reads independently occur on every hit, every
fourth hit, or no hits. Read-use phases are capture → Boolean → capture; hit-rate
phases are all → rare → all. Periodic, alternating, blocked, and shuffled schedules
are built outside timing from the declarations, with shuffle seed `20261103 + phase`.
Offsets consume native group lengths; text reads hash the returned capture text,
including UTF-8 decoding inside timing. An independent JDK checksum checks the
same schedule and lifetime using separate Patterns before measurements.

The common screen is 32 exact rows: both patterns and representations, covering
all-hit text consumption, rare hits, one-in-four capture reads, Boolean-only
consumption, rare-hit rotation, shuffled half-hit rotation, capture/Boolean/capture
phases, and the two existing documents. The two adaptive modes with the lowest
equal-row geometric mean time/main ratio receive expanded coverage. That screen
is a synthetic mix; its ranking is not a deployment recommendation.

Expanded cases cover fresh matchers, all rejections, additional read frequencies,
alternating and blocked traffic, changes in hit rate, multibyte input, and lengths
511/513 around the existing cutoff. Standard and long estimates remain separate.
Random seeds 1/17/73 and a deterministic exploration control provide sensitivity
checks. Probabilistic/periodic phase and latency comparisons are collected even
when those modes are not screening winners.

Retained means two cursors, one for each positive/negative input, sharing a Pattern.
Fresh means allocation of a new cursor for every document scan. String rotation
uses one Matcher with reset(newInput). Utf8Matcher has no reset(newInput) overload;
UTF-8 rotation uses one immutable joined input and alternating regions. Those
region measurements have different API/context costs and do not stand in for an
unavailable UTF-8 reset API. Input preparation and compilation are outside timing;
reset/region changes, searches, and capture materialization are inside.

## Common screen: every policy on identical membership

Ratios throughout are candidate time / main time: below one is faster. The screen
weights its 32 exact scenario/representation rows equally. These geometric means
are point estimates, with no aggregate confidence interval or traffic weighting.

| Policy | Rows | Geomean | String | UTF-8 | Worst row |
|---|---:|---:|---:|---:|---:|
| main | 32 | 1.000 | 1.000 | 1.000 | 1.000 |
| dfa | 32 | 0.424 | 0.510 | 0.353 | 2.110 |
| localSticky | 32 | 0.698 | 0.712 | 0.683 | 1.045 |
| fraction | 32 | 0.717 | 0.767 | 0.670 | 1.658 |
| joint | 32 | 0.502 | 0.550 | 0.459 | 1.663 |
| replay | 32 | 0.434 | 0.464 | 0.407 | 1.299 |
| work | 32 | 0.443 | 0.472 | 0.417 | 1.272 |
| periodic | 32 | 0.608 | 0.647 | 0.573 | 1.296 |
| random | 32 | 0.595 | 0.635 | 0.558 | 1.336 |
| patchedMain | 32 | 1.003 | 1.009 | 0.998 | 1.147 |
| observe | 32 | 1.038 | 1.036 | 1.039 | 1.172 |

The prescribed selection rule chooses `replay` and `work` for expanded coverage.
DFA-first has a slightly lower overall screen score, but its worst row takes
2.110× main's time. The adaptive winners reduce that core worst case while
retaining large rejection gains. Their expanded worst cases below are worse.

The controls put the gains in context. Keeping the patch with adaptation disabled
has a 1.003 geometric-mean ratio; enabling observation/work bookkeeping with main's
producer choice has 1.038. Individual control ratios reach 1.147 and 1.172.
These standard-mode point estimates expose potential bookkeeping/layout costs;
they do not justify subtracting a universal overhead from any candidate.

History ownership helps when each input retains its own cursor. On rare scheme
inputs, local sticky has String ratio 0.014, but the same local bit on one rotating
Matcher gives 0.996. A cursor that sees both successes and failures needs an actual
decision rule, beyond merely moving the sticky bit.

The consumption-only rule gives String rotating rare-scheme ratio 0.896, compared
with 0.016 for `joint`. Both consume captures on every successful hit. The
additional rejection history is the distinguishing signal. Conversely, one-in-four
reads on successful String stack inputs produce ratios 1.658/1.663 for
fraction/joint, while the UTF-8 counterparts are 0.392/0.396. A fixed consumption
threshold cannot account for those different producer costs. Those are standard
screen estimates; fraction/joint did not receive long confirmation.

The strong overall scores depend on rejected/mixed documents. Removing all rare-hit
and half-hit scenarios leaves 20 rows per policy: all-hit extraction, Boolean-only,
read-use phases, and existing documents. This diagnostic exclusion is shown beside
the complete screen, not substituted for it.

| Policy | Diagnostic rows | Geomean excluding rare/half |
|---|---:|---:|
| dfa | 20 | 1.101 |
| fraction | 20 | 1.037 |
| joint | 20 | 1.036 |
| replay | 20 | 1.085 |
| work | 20 | 1.081 |
| periodic | 20 | 1.120 |
| random | 20 | 1.087 |

Every candidate in that sensitivity slice is slower in geometric mean. This is why
the complete screen cannot support a universal speedup claim.

## Longer confirmations and successful-extraction costs

Times are µs/document. Each ± value is JMH's 99.9% confidence-interval half-width
for the individual mean, normalized from cycle time. Ratios use point estimates;
these intervals are not confidence intervals for ratios. Standard and long results
are never pooled.

| Long workload | Main | DFA-first | Replay | Work |
|---|---:|---:|---:|---:|
| Stack, all-hit text / String | 0.645 ± 0.014 | 1.398 ± 0.040 | 0.675 ± 0.012 | 0.684 ± 0.008 |
| Stack, all-hit text / UTF-8 | 2.669 ± 0.111 | 1.358 ± 0.013 | 2.691 ± 0.115 | 2.775 ± 0.080 |
| Scheme, all-hit text / String | 0.540 ± 0.017 | 0.629 ± 0.033 | 0.573 ± 0.030 | 0.582 ± 0.010 |
| Scheme, rare rotation / String | 10.240 ± 0.156 | 0.142 ± 0.006 | 0.163 ± 0.006 | 0.191 ± 0.002 |
| Scheme, rare regions / UTF-8 | 8.759 ± 0.165 | 0.159 ± 0.007 | 0.168 ± 0.004 | 0.202 ± 0.002 |
| Four-frame document / String | 2.415 ± 0.064 | 4.378 ± 0.048 | 2.500 ± 0.065 | 2.546 ± 0.076 |
| Four-frame document / UTF-8 | 5.756 ± 0.077 | 4.332 ± 0.071 | 5.885 ± 0.126 | 5.988 ± 0.136 |
| Scheme document / UTF-8 | 1.048 ± 0.028 | 1.256 ± 0.013 | 1.082 ± 0.034 | 1.364 ± 0.018 |
| Stack, all-hit fresh / String | 0.651 ± 0.010 | 1.447 ± 0.080 | 1.429 ± 0.022 | 1.504 ± 0.044 |

The large rotating rare-scheme benefit survives longer runs. Successful String
stack extraction favors main; UTF-8 stack extraction favors DFA-first. Greedy work
does not exploit that UTF-8 opportunity: its counted work is not a calibrated
cross-engine time estimate. On the UTF-8 scheme document it takes 1.301× main's
time, versus replay's 1.032×. Allocation, decoding, and paths outside the proxy
remain relevant.

Fresh String stack matchers are a decisive limitation: replay takes 2.194× and work
2.309× main's time in long confirmation. Local sticky also takes 2.150×. Local
history is discarded before it can help the next document, while main retains
shared Pattern knowledge. Testing bounded Pattern priors could address this
separate issue; no prior is added here.

## Expanded and shared-Pattern coverage

Main, DFA-first, replay, and work cover all 80 standard scenario/representation
rows. The table weights every row equally, including fresh matchers, rejection-only
cases, sizes around the cutoff, and multibyte inputs. It is a second constructed
mix, distinct from the core screen.

| Policy | Rows | Geomean/main | Worst row/main |
|---|---:|---:|---:|
| dfa | 80 | 0.349 | 2.193 |
| replay | 80 | 0.362 | 2.228 |
| work | 80 | 0.378 | 2.335 |

Replay/work's worst expanded row is fresh successful String stack matching.
Work also takes 1.500× main's time on rejecting-only String scheme documents,
where there is no capture-demand benefit to pay for its bookkeeping. The
511/513 and multibyte rows are retained in the full summary; no new eligibility
threshold was selected from them.

Shared-Pattern results below compare the same four-worker method and give ratios
to its own main control. Two workers read captures and two use Boolean results;
all own private cursors. These averages cannot identify either role's latency.

| Shared case | DFA-first/main | Replay/main | Work/main |
|---|---:|---:|---:|
| Scheme, all-hit / String | 1.377 | 1.250 | 1.230 |
| Scheme, rare rotation / String | 0.014 | 0.017 | 0.019 |
| Stack, all-hit / String | 1.717 | 1.145 | 1.191 |
| Stack, rare regions / UTF-8 | 0.120 | 0.124 | 0.130 |

Sharing does not eliminate the tradeoff. Both selected policies preserve large
rare-hit gains but lose on successful shared scheme extraction. The remaining
policies were not collected on this shared matrix.

## Recovery, exploration cost, and individual-document tails

Separate clocked phases show changing hit rate on one rotating String Matcher:
all documents match, then one in sixteen matches, then all match again. Captures
are read on every hit. Values are µs/document from diagnostic phase counters,
without confidence intervals; the full iteration ranges remain in normalized data.

| Policy | All-hit phase | Rare-hit phase | Return to all-hit |
|---|---:|---:|---:|
| main | 0.543 | 8.355 | 0.537 |
| dfa | 0.658 | 0.145 | 0.658 |
| replay | 0.715 | 0.162 | 0.713 |
| work | 0.731 | 0.189 | 0.730 |
| periodic | 0.661 | 2.551 | 0.682 |
| random | 0.613 | 2.236 | 0.627 |

Replay/work avoid most rare-phase cost, but do not return to main's all-hit time.
Random and periodic improve on main in the rare phase while retaining much more
cost than greedy work. Transition traces below explain the producer choices;
phase clocks alone do not establish the cause of every overhead difference.

Long ordinary cycle measurements confirm that continuing exploration has a large
rare-rotation cost. These are separate from the clocked phases above:

| Rare scheme rotation, µs/doc | Work | Periodic | Random (seed 1) |
|---|---:|---:|---:|
| string | 0.191 ± 0.002 | 2.553 ± 0.019 | 2.670 ± 0.018 |
| utf8 | 0.202 ± 0.002 | 2.493 ± 0.080 | 2.354 ± 0.081 |

The individual-document SampleTime method exposes the occasional expensive choice.
This table uses String rare scheme rotation; the reported percentiles describe the
JMH sample distribution, not an application service-level latency guarantee.
Sampling, harness overhead, GC, and system noise affect especially the far tail.

| Policy | Mean µs/doc | p50 | p95 | p99 |
|---|---:|---:|---:|---:|
| main | 10.629 | 10.560 | 11.872 | 27.424 |
| dfa | 0.227 | 0.122 | 0.635 | 0.751 |
| replay | 0.225 | 0.135 | 0.160 | 0.744 |
| work | 0.289 | 0.163 | 0.767 | 0.947 |
| periodic | 4.067 | 0.198 | 9.456 | 16.768 |
| random | 2.949 | 0.180 | 10.768 | 16.768 |

Low exploration medians coexist with p95/p99 near the expensive producer's cost.
Replay/work avoid most of that tail here. The complete summary also contains
all-hit and read-phase latency for both representations; no candidate has a
uniform percentile advantage. In particular, replay's UTF-8 stack read-phase p99
is 18.698 µs against main's 13.592 µs. These sampled results are separate from
cycle-average estimates and were not used for the screen selection.

## Randomness versus deterministic exploration

The dedicated standard-mode seed cohort is a separate measurement, not a repeat
pooled into the core or long results. Selected cycle times are µs/document:

| Case | Random seed 1 | Seed 17 | Seed 73 | Periodic |
|---|---:|---:|---:|---:|
| Rare scheme rotation / String | 2.722 | 2.428 | 2.438 | 2.564 |
| Rare scheme regions / UTF-8 | 2.409 | 2.473 | 2.354 | 2.493 |
| Scheme hit-rate phases / String | 1.183 | 1.281 | 1.253 | 1.252 |
| Stack hit-rate phases / String | 0.805 | 0.795 | 0.796 | 0.757 |

Across these seeds, randomization does not consistently beat the deterministic
schedule. On String rare scheme rotation it spans 2.429–2.722 µs/document, with
periodic at 2.564. All are much more expensive than greedy work's long estimate,
although that cross-cohort comparison has different measurement settings. The
same-settings core also favors greedy work (0.018/main versus approximately
0.25/main for both exploration variants on that row).

A seed reproduces an identical decision stream given identical histories. It does
not guarantee byte-identical JMH timings or the same warmup decision count in a
time-limited run. Three seeds and one deterministic schedule are sensitivity
checks, not a proof that random exploration has no value.

## What the deterministic traces establish

These are cold, finite diagnostic runs over the declared rotating transition
inputs, with extra work accounting enabled in every mode. They are not timings or
steady-state JMH histories. `observe` mirrors main's producer rule; trace
`dfa`/`localSticky` use the full framework to mirror the minimal controls' choices.

For String scheme hit-rate phases, each cell counts capture-first choices out of
512 eligible producer decisions:

| Policy | All hits | Rare hits | Return to all hits | Rare-phase work/doc |
|---|---:|---:|---:|---:|
| observe | 511/512 | 512/512 | 512/512 | 1712.1 |
| fraction | 508/512 | 512/512 | 512/512 | 1712.1 |
| joint | 508/512 | 33/512 | 497/512 | 157.5 |
| replay | 511/512 | 9/512 | 0/512 | 81.5 |
| work | 511/512 | 2/512 | 0/512 | 57.3 |
| periodic | 493/512 | 129/512 | 326/512 | 496.6 |
| random | 495/512 | 152/512 | 336/512 | 551.5 |

The fraction rule remains capture-first even when most documents fail. Joint
history backs off and recovers. Replay/work back off fastest in this trace, but
make no capture-first choices on the return phase: shared exact fallback can
already provide captures, so replay need not observe another user-triggered replay,
and the greedy estimator need not resample its stale capture cost.

Periodic/random explore during the return phase and recover many capture-first
choices. They also keep choosing the expensive producer during rare hits. For
random seeds 1/17/73 the rare phase contains 152/122/123 capture-first choices;
return phases contain 336/340/318. This is direct evidence of both the recovery
benefit and the ongoing exploration cost for these parameters.

Consumption-phase traces show a different tradeoff. On String stack
capture → Boolean → capture input, fraction chooses capture-first on only 16/512
Boolean-phase decisions and replay on 32/512. Greedy work keeps it on all 512
because its estimated producer work remains lower. Forgetting capture demand does
not automatically reduce actual work when the capture engine is the cheaper search
path.

The sampled profiles agree with the rare-input mechanism: main's rejecting
baseline and the random rotating candidate spend prominent sampled stacks in
BitState; greedy work shifts toward DFA and its accounting paths. Profiles were
collected separately and their timings are excluded. The profiler reused each
policy's output path across forks, so only the retained final-fork captures are
summarized. These sampled stacks support the path explanation, not a numerical
attribution of the total speed difference.

## Assessment and limits

The strongest result is the need to distinguish capture demand from the value of
speculating before rejection. Consumption fraction is a useful observation, but
insufficient on its own. Search outcomes and work/replay signals improve on it in
the rotating rejection cases. The current fraction/joint thresholds nevertheless
lose on some successful extraction workloads, so this does not rule out other
consumption-based policies.

Replay credit is comparatively simple and led the adaptive screen, but misses
recovery when already-required fallback supplies captures without another replay.
Greedy work avoids repeated expensive exploration, while its coarse units and
stale estimates miss useful choices. Both need a separate answer for fresh
matchers, whose local learning cannot amortize.

Probabilistic exploration was a reasonable idea to test: it does recover choices
that greedy work leaves stale. At the tested rates, its repeated costly probes
outweigh that benefit on rejecting rotations, including the tail. The deterministic
control shows that continuing exploration, not randomness alone, is the main
tradeoff exposed here. A lower or cost-budgeted exploration rate would be a new
experiment, not an improvement demonstrated by these results.

Keep the existing production rule in this PR. If pursuing another round, the
clearest separate questions are bounded shared priors for fresh matchers, feedback
that accounts for already-required exact fallback, better cross-engine cost
estimates, and exploration bounded by observed cost. Those need predeclared
parameters and application evidence before choosing a default. No existing
eligibility limit, production engine choice, or public API changes here.

Only one predeclared configuration per decision rule was tested; this does not
identify optimal windows, thresholds, cost weights, or exploration rates. Pattern
priors remain a separate future experiment. Expanded coverage is selected-only;
missing cells remain unmeasured. The patterns, synthetic mix, API lifetimes, JVM,
and host constrain every conclusion. External application and cross-engine suites
were not collected for this focused investigation.

## Evidence and reproduction

The frozen investigation source is
[`aa274ea98549fb7228eb574ffbdb42642f5be25c`](https://github.com/eaftan/safere/commit/aa274ea98549fb7228eb574ffbdb42642f5be25c).
Its commit time is `2026-11-04T07:05:06Z`; the library's Maven version is
`1.2.0-SNAPSHOT`.
Each cohort records the pinned library base, identical harness hashes, full source
overlay, JAR/Matcher bytecode hashes, exact wrapper command, runtime policy/seed,
raw JMH output, and successful exit/membership audit. Minimal controls' source
identities are checked separately from the full adaptive framework.

The archive contains **27 complete cohorts and 872 raw primary rows**, plus
88 deterministic work traces. Profiles and smoke execution checks are excluded
from those primary comparisons. [cohort-inventory.json](cohort-inventory.json) and
[derived/inventory.json](derived/inventory.json) give exact membership. The long
fresh-matcher confirmations and dedicated exploration phase/latency/long cohorts
extend the initial staged plan to resolve material regressions and the exploration
question; no policy parameters were retuned.

| Evidence | Contents |
|---|---|
| [derived/summary.txt](derived/summary.txt) | Complete plain-text comparison tables, including every long, shared, phase, sampled-latency, seed, and robustness row. The original [summary.txt](../summary.txt) is unchanged. |
| [derived/normalized-results.jsonl](derived/normalized-results.jsonl) | Unique identities include cohort, policy, scenario, representation, seed, and mode. Cycle values are divided by 1,536; SampleTime values are per document. Phase means/min/max are derived from per-iteration counter ratios. |
| [cohorts/](cohorts/) | Authoritative raw results, commands, logs, source identities/overlays, exit markers, and audits. Raw files are copied without rewriting. |
| [derived/trace-tables.md](derived/trace-tables.md), [work-traces/](work-traces/) | Phase/checkpoint work and choice summaries; original inputs, TSV state, and exact probe commands. Work units are not clock time. |
| [profiles/](profiles/) | Baseline and candidate profile commands, logs, JMH JSON, and sampled-stack summaries. Binary JFR captures remain local. Profile scores are excluded from all tables above. |
| [environment/](environment/) | OpenJDK/Maven, CPU, OS, and memory details: OpenJDK 26.0.2+10-55, Maven 3.9.14, JMH 1.37, i7-11700K with 16 logical CPUs, WSL2 Linux 6.6.87.2, approximately 15.5 GiB visible memory. |
| [SHA256SUMS](SHA256SUMS) | Recursive hashes for this new archive. The parent archive's checksum file covers only the historical study. |

To reproduce measurements, follow [the adaptive runner instructions](../../adaptive/README.md)
and its [predeclared plan](../../adaptive/PLAN.md). Commands are preserved per cohort;
rebase local worktree/output paths when replaying them. The prototype must be
explicitly applied to the pinned detached library base; clean the library build
when changing source state. Neither the collector nor the release suite applies it.

To regenerate the derived evidence from this archive, run from the repository root:

```bash
python3 safere-benchmarks/investigations/capture-demand-policy/adaptive/analyze.py \
  safere-benchmarks/investigations/capture-demand-policy/results/decision-rules/cohorts \
  /tmp/capture-policy-derived
python3 safere-benchmarks/investigations/capture-demand-policy/adaptive/analyze-traces.py \
  safere-benchmarks/investigations/capture-demand-policy/results/decision-rules/work-traces \
  /tmp/capture-policy-derived
```

The derivation audits exact cohort membership, measurement settings, common
benchmark-source hashes, bounded trace state, and identical semantic checksums
across policies, seeds, and representations. Derived files regenerate identically
from the preserved inputs. The investigation-only lifecycle coverage exercises
retirement, delayed/repeated reads, snapshots, replacements, input/region/bounds and
Pattern changes; focused public-API and work-bound checks also passed under the
prototype. This validates the measured prototypes within that coverage, not a
complete compatibility proof or a production recommendation.
