# Reusable Review Scripts

Use these helpers for repeated mechanics in the contributor workflow. They accept explicit inputs
rather than embedding PR numbers, run IDs, workload lists, or repository/worktree paths. Run them
from the SafeRE repository using the **loaded** skill directory:

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked python \
  "$REPO_ASSIST_SKILL_DIR/scripts/review-tools.py" --help
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked python \
  "$REPO_ASSIST_SKILL_DIR/scripts/benchmark-tools.py" --help
```

`REVIEW_ROOT` is the storage root, `RUN_ARTIFACTS` is an artifact directory for the current run,
and `REPO` is the SafeRE checkout. Existing `discover`, `snapshot`, `issue-activity`,
`authored-feedback`, `authored-worktree`, `begin-run`, and `end-run` remain the trust and lifecycle
helpers. Saved discovery/snapshot files below must come from those helpers. The reusable scripts
neither expand author trust nor fetch bodies through another API. Per-PR repair code, review prose,
intent/evidence decisions, and benchmark selection remain the agent's work.

## Refresh contributor freshness

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked python \
  "$REPO_ASSIST_SKILL_DIR/scripts/review-tools.py" --root "$REVIEW_ROOT" \
  refresh --repository-name eaftan/safere --output-dir "$RUN_ARTIFACTS/freshness"
```

Runs discovery and sanitized snapshots **serially through the existing trust helper** and compares
head/discussion freshness with contributor state. Archives discovery and each sanitized result.
Exit `0` means no contributor head/discussion changes; `1` means new or changed items need delta
triage; `2` means collection failed. It does not change assessment state or mark items reviewed.
Refresh Git base/trunk and official stack metadata separately: unchanged head/discussion is not
sufficient to reuse evidence when a dependency changed. Authored feedback and issues keep their
separate collectors and state.

## Checkpoint one contributor assessment

Prepare the detailed section (starting with `## PR #N: ...`, without its anchor or author-copy
subsection), the unfenced author review, a full sanitized snapshot, and a JSON spec:

```json
{
  "number": 123,
  "assessment": "Invariant preserved; relevant verification passed.",
  "recommendation": "Can merge",
  "record": {
    "status": "reviewed",
    "classification": "other",
    "lastHeadSha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "lastBaseSha": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    "lastTrunkSha": "cccccccccccccccccccccccccccccccccccccccc",
    "lastSeenUpdatedAt": "2026-10-09T00:00:00Z",
    "fingerprint": "fingerprint-from-sanitized-snapshot",
    "preparedHeadSha": "dddddddddddddddddddddddddddddddddddddddd",
    "lastFixCommit": null,
    "lastFixBranch": null
  }
}
```

Use actual immutable SHAs; the base and trunk are independent inputs, especially for stacks.
`preparedHeadSha` records the final reviewed tree. Set fix fields explicitly, including `null` when
there is no applicable fix, so an older fix cannot silently carry into a different head. Include
additional contributor fields such as `preparedReviewBaseSha` and `stackContext` when applicable;
unknown existing contributor fields and all other state categories are preserved.

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked python \
  "$REPO_ASSIST_SKILL_DIR/scripts/review-tools.py" --root "$REVIEW_ROOT" \
  checkpoint --token "$RUN_TOKEN" --discovery "$RUN_ARTIFACTS/discovery.json" \
  --snapshot "$RUN_ARTIFACTS/pr-snapshot.json" --spec "$RUN_ARTIFACTS/assessment.json" \
  --section "$RUN_ARTIFACTS/pr-section.md" --author-copy "$RUN_ARTIFACTS/pr-review.md"
```

Requires the current lock token and running report. The PR must be in sanitized contributor
`discovery.trusted`, with matching head and discussion data; owner/authenticated-user/draft entries
are rejected. Use `snapshot --force` when an unchanged snapshot omits its full PR object. A single
summary row must already exist in the report. The script inserts or replaces the stable detailed
section, updates that row while preserving its `Done` cell, and merges the supplied record into
`state.prs`. It never writes issue checkpoints, authored-feedback state, `LATEST.md`, or completion.

The proposed record and author copy are saved under `artifacts/<runId>/pr-<number>/` before the
report/state writes. Each file is replaced atomically; the pair is not a database transaction.
Repeat the same command after an interrupted write: section replacement is idempotent and will
bring the report and state back into agreement. Work remains serialized by the active run lock.

## Assess merge ordering

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked python \
  "$REPO_ASSIST_SKILL_DIR/scripts/review-tools.py" --root "$REVIEW_ROOT" \
  merge-order --discovery "$RUN_ARTIFACTS/discovery.json" --repository "$REPO" \
  > "$RUN_ARTIFACTS/merge-order.json"
```

Compares exact final prepared heads, preferring a recorded fix commit, for reviewed contributors.
Rejects reviewed records whose head or discussion timestamp differs from the supplied discovery.
Reports real pairwise textual conflicts, ancestry, and shared files, and lists entries lacking a
reviewed tree. `git merge-tree --write-tree` writes Git objects but changes no branch or checkout.
Use the results as evidence for the human ordering recommendation: file overlap is not a semantic
dependency, and a textual merge is not combined correctness or cumulative-performance validation.
Draft/owner stack layers are included only through an eligible upper PR's prepared tree, not as
additional contributor queue entries.

## Audit report structure and extract author reviews

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked python \
  "$REPO_ASSIST_SKILL_DIR/scripts/review-tools.py" --root "$REVIEW_ROOT" \
  audit --discovery "$RUN_ARTIFACTS/discovery.json" --report "$REPORT_PATH" \
  --repository "$REPO" --check-worktrees --output-dir "$RUN_ARTIFACTS/author-copies"
```

Checks queue/summary/detail agreement, terminal contributor state with head/discussion freshness
against the supplied discovery, author-review fences, obvious
internal bookkeeping and hard-wrapped prose. A detailed benchmark results table requires a table in the
author-facing review as well; documentation-only advice needs no invented benchmark table. Optional worktree checks require a clean checkout at each
reviewed final head and ancestry from its **recorded** trunk. Output is diagnostic: exit `1` means
mechanical problems need correction. It does not modify the report or state.

Read every extracted review alone afterward. The script cannot establish factual completeness,
tone, numeric-claim placement, whether findings are fully explained, or whether an approval matches
the actual evidence. It never certifies that semantic reading occurred. Complete that audit before
`end-run`; only successful `end-run` promotes the issue cutoff and `LATEST.md`.

## Paired wrapper measurements

For identical targeted nanosecond workloads, continue preferring
`safere-benchmarks/scripts/compare-branch.sh`. Use this helper for exact declared trials requiring
the repository wrapper directly, including microsecond compilation workloads:

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked python \
  "$REPO_ASSIST_SKILL_DIR/scripts/benchmark-tools.py" compare \
  --worktree "$BENCH_WORKTREE" --baseline "$BASE_SHA" --experiment "$FINAL_SHA" \
  --trials "$RUN_ARTIFACTS/resolved-trials.txt" --mode standard \
  --log "$RUN_ARTIFACTS/benchmark-standard.log"
```

Supply one exact trial ID per line. Default filter/parameter are
`CrossEngineScalingBenchmark.run` and `crossEngineScalingTrial`; `--filter` and `--parameter`
select other declared trial families. `--maven` can select the already installed Maven executable.
Do not use this to approximate missing private workloads. Resolve claim/data/metric/comparable
revisions first. Use `--mode long` only for a justified confirmation of the exact comparison.

The worktree must be clean on an isolated `codex/review/` branch, and the log must be outside it.
Do not use an authored original worktree or switch a checkout while a reviewer reads it. Full
immutable SHAs are required. Workload, harness, build and wrapper differences fail the comparison;
construct a controlled baseline with unchanged PR benchmark declarations before retrying. Both arms
run serially through `mvn ... clean` and `./run-java-benchmarks.sh`, preserving script-owned
measurement settings. The helper attempts to restore the original branch after success or failure without discarding
files. If changed files prevent restoration, it reports that error and preserves them for inspection.
The log records the requested trial identities. Each arm must produce exactly those results before
the helper writes its completion marker. A failed run keeps its partial log and has no completion
marker; use a new log path on retry.
Run no other Maven or benchmark job concurrently, including formatter hooks invoked by commits.

## Extract paired evidence

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked python \
  "$REPO_ASSIST_SKILL_DIR/scripts/benchmark-tools.py" extract \
  --repository "$REPO" --log "$RUN_ARTIFACTS/benchmark-standard.log" \
  --mode standard --output "$RUN_ARTIFACTS/results-standard.json" \
  > "$RUN_ARTIFACTS/results-standard.md"
```

Reuses SafeRE's maintained JMH parser. Accepts only a completed single paired log with matching
benchmark/engine sets and native units. Both arms must contain every requested trial from the log
manifest; duplicate rows, mismatched units, invalid scores, and incomplete comparisons are rejected.
For older logs without a manifest (including `compare-branch.sh` logs), supply the original exact
selection using `--trials resolved-trials.txt --parameter crossEngineScalingTrial`. Matching result
sets alone cannot establish completeness. JSON also records the requested identities and parameter. JSON preserves native scores/errors/units, revision SHAs, mode,
experiment/baseline score ratios, and overlap of JMH's reported error intervals. The table includes
units per row, so mixed nanosecond/microsecond evidence is not silently relabeled. Lower ratios mean
lower elapsed time; throughput and allocation have their own metric interpretation. Interval
overlap is descriptive, not a claim of statistical significance. Evidence extraction does not
replace benchmark judgment or the author-facing review's own table.
