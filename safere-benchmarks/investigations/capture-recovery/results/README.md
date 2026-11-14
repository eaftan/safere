# Capture-recovery follow-up to #1075

The local candidate reduces time on the selected recovery workloads by about
3.4% overall (equal-weight geometric mean). The larger gain is stack-document
extraction: roughly 14–15% less time on String and 15–17% less on UTF-8 across the
complete long run and its reversed-order confirmation. Successful UTF-8 scheme
extraction and its rejection control cost about 4% more in the confirmation.
That tradeoff is part of the candidate's assessment.

At 512 units, early capture production uses roughly
36–47% less time when every hit consumes text, but rejection takes
4.7–5.0× as long as disabled preference.

Keep the existing 512-unit capture-demand limit for this change. Frequent extraction
and rejection favor opposite choices, with no universal size break-even established.
Extending eligibility carries the rejection cost into larger inputs; disabling it
sacrifices the frequent-extraction benefit. The measurements do not establish that
512 is optimal. Choosing another default needs a defensible workload weighting.

## What changed

The implementation fuses simple greedy character loops inside BitState. A helper
walks the loop without returning to the interpreter switch for each character.
It still saves each possible loop exit in the same order, checks the same visited
states, and charges the same logical instructions against the work budget.
Capture recovery and exact fallback benefit from this general mechanism. It adds
bounded shared program metadata; cold-start and memory effects were not measured.
The existing engine-selection rules, including DFA-first unanchored searches
and the anchored capture preference, remain in place.

Baseline String stack-document profiles sampled capture replay more often than the
reverse-DFA pass. This motivated work on BitState's interpreter. The reverse-DFA
implementation itself is unchanged. Sampling classifications are approximate,
include inlining effects, and are not precise wall-time attribution.

The benchmark adapter now decodes requested UTF-8 capture ranges during timing,
retaining native byte bounds and borrowed-view offsets. This enables UTF-8 variants
of existing capture-text declarations. Ten new standing cases cover ASCII and
multibyte extraction, primed rejection, mixed participation queries, and anchored
extraction at 511/512/513 units. Existing declarations are unchanged. The larger
cutoff protocol remains opt-in, separate from release collections.

## Recovery results against pinned main

Each ratio is candidate time / main time; below one means less time. The baseline
is `4ead777e87f9209c2d9dc06fb958a23bd01349be` (commit UTC `2026-11-11T04:40:44Z`), which already includes DFA-first.
The `greedy-*` artifact filenames refer to character-loop fusion.

| Workload | String ratio | UTF-8 ratio |
|---|---:|---:|
| Stack, every-hit text | 0.908× | 0.922× |
| Stack, one-in-four text | 0.942× | 0.975× |
| Stack, all misses | 0.984× | 0.989× |
| Stack, four-frame document | 0.848× | 0.833× |
| Scheme, every-hit text | 0.985× | 1.038× |
| Scheme, one-in-four text | 0.995× | 1.020× |
| Scheme, all misses | 1.061× | 1.046× |
| Scheme, one hit in sixteen, rotating cursor | 0.951× | 0.993× |

The full long cohort has 16 equally weighted rows: eight scenarios in each native
representation. Its geometric mean is 0.966×, nearest-rank P90 is
1.046×, and largest observed ratio is 1.061×. These are
fixture summaries, not estimates of application traffic. Standard and long results
are reported separately. The additional reversed-order pair confirms the stack
gain and the UTF-8 scheme cost; it is not pooled into the complete cohort.

The two patterns come from the existing stack-trace and custom-scheme extraction
benchmarks. Inputs are constructed ASCII prose, valid/invalid frames or links,
and an existing four-frame example document. Stack text reads the fully qualified
class name; the document case reads class names and line numbers for each frame.
Scheme text reads the custom-scheme payload. Each invocation scans three phases
of 512 documents. Ordinary cases retain one cursor per input. The rare scheme
rotation resets one String cursor to new inputs; UTF-8 rotates regions over prepared
bytes. Positive documents have one match except the four-frame document case.
Text is read on every hit or one in four hits as labeled. All-miss cases consume
no capture text. String preparation and UTF-8 encoding are outside timing;
requested UTF-8 capture decoding is inside timing.

[Full scores, JMH errors, and both measurement modes](recovery-table.md),
[reversed-order confirmation](recovery-confirm-rows.json),
[baseline sampling summary](baseline-profile-summary.txt).

## Cutoff experiments and assessment

The counterfactual source snapshots differ only in
`Matcher.CAPTURE_DEMAND_TEXT_LIMIT`: 0 disables early capture preference on these
nonempty inputs; 2048 extends it; 512 is the current limit. Other capacity and
eligibility guards remain. Fast paths such as OnePass still apply. Both
counterfactuals include the same character-loop optimization.

The cutoff fixtures use a start-anchored stack parser and a deterministic
whitespace/key/integer parser. End anchors are omitted so end-anchored reverse
probing cannot bypass the decision at larger sizes. Whitespace padding gives exact
ASCII lengths in both native representations. Rejections keep required literals
and digits but fail inside the expression. A separate post-timing JVM records
public operation diagnostics: the stack comparison switches DFA to BitState;
the deterministic control uses the prepared OnePass runner for all three operations
at these sizes. None of these audited cases uses a reverse DFA.

Each invocation resets retained positive/rejection cursors and scans 16 documents
sharing a Pattern. Actual capture text is consumed before timing to establish demand,
including for rejection controls. `text` reads group 1 on every hit; `mixed` reads
it on one in four hits; `rare` has one hit in sixteen and reads it; `reject` has no
hits. These measure warm steady state after capture consumption.

The standard screen covers stack lengths 512/1024 with all four profiles, the
OnePass control with text/rejection, and current-limit boundaries at 511/512/513:
180 measurements. The long confirmation below covers 512 units, both native
representations, and all three operations, with extended preference measured first.
Ratios are extended preference / disabled preference.

| Input | Operation | Every-hit text | One-in-four text | Rejection |
|---|---|---:|---:|---:|
| safere-string | matches | 0.631× | 1.092× | 4.967× |
| safere-string | lookingAt | 0.614× | 1.121× | 5.001× |
| safere-string | find | 0.636× | 1.126× | 5.004× |
| safere-utf8 | matches | 0.529× | 0.929× | 4.709× |
| safere-utf8 | lookingAt | 0.546× | 0.902× | 4.709× |
| safere-utf8 | find | 0.538× | 0.895× | 4.721× |

The current-limit profiles show BitState instruction/stack work on 512-unit
rejections; 1024-unit rejection samples predominantly show forward DFA work.
Capture-heavy 1024-unit profiles contain both DFA and submatch work. This agrees
with the extra-pass versus eager-backtracking tradeoff in the measurements.

Frequent extraction benefits from early capture production; rejection pays several
times as much. Mixed consumption lies closer to the crossover and depends on
representation and size. Control timings with unchanged paths also varied, limiting
precision near 1×. The existing boundary visibly trades faster extraction below
512 for cheaper rejection above it. This supports retaining a bounded preference. The retained 512 guard remains a
conservative default rather than a measured optimum.

[Complete standard cutoff table and boundary times](cutoff-table.md),
[long cutoff scores and JMH errors](cutoff-long-rows.json),
[exact resolved input corpus](resolved-cutoff-corpus/manifest.json),
[cutoff sampling summary](cutoff-profile-summary.txt),
[profile commands and sources](cutoff-512-profile/command.json).

## Reproduction and scope

Measurements use the project wrappers, sequentially, on 11th Gen Intel(R) Core(TM) i7-11700K @ 3.60GHz, OpenJDK
26.0.2, JMH 1.37, Linux/WSL2. Standard mode uses two forks, two 500 ms warmups and
five 500 ms measurements; long mode uses two forks, three 1 s warmups and five
1 s measurements. JMH errors are its reported 99.9% confidence half-widths.
Recovery times are per 1,536-document cycle; cutoff times are per 16-document batch.
Boundary tables divide batch scores by 16. Smoke and JFR timings are excluded
from comparison evidence.

To reproduce a cutoff snapshot, start from the pinned baseline and overlay its
archived main sources and the matching benchmark-test support archive before
invoking the archived runner into a new output directory. Maven compiles those
tests even when timing builds skip executing them. Run these commands from this
evidence directory inside the repository:

```bash
git worktree add --detach /tmp/capture-cutoff-repro 4ead777e87f9209c2d9dc06fb958a23bd01349be
tar -xzf cutoff-0-stack-screen/sources.tar.gz -C /tmp/capture-cutoff-repro
tar -xzf benchmark-test-support.tar.gz -C /tmp/capture-cutoff-repro
python3 /tmp/capture-cutoff-repro/safere-benchmarks/investigations/capture-recovery/run.py /tmp/capture-cutoff-rerun --family stack --sizes 512 1024 --profiles text mixed rare reject
```

Each completed cohort preserves commands, source identities, raw JSON/logs and exit
statuses. Cutoff cohorts also archive exact selected sources and separate path
audits. `tools/analyze.py` regenerates the principal tables; source-hash checks
verify the intended comparison differences. Only completed measurement cohorts are
archived here; incomplete collection output remains local and is excluded. Historical policy-study artifacts are unchanged.
`SHA256SUMS` protects the final evidence set.

Library, deterministic work-bound, benchmark-protocol, UTF-8 adapter, schema and
standing-execution checks passed. Default counter-free artifacts were restored;
the regular benchmark JAR excludes opt-in investigation classes. This is a focused,
single-thread study with warm Patterns and mostly constructed inputs. The external
OpenJDK-derived checkout was unavailable; no corpus-wide performance claim follows.
This investigation layer preserves the candidate as a prototype patch. Runtime
adoption is proposed in the next PR; the 512-unit limit is unchanged in both layers.

[Artifact map and capture-time documentation notes](ARTIFACTS.md).
