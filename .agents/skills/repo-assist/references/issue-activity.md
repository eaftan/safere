# Issue Activity And PR Coverage

After acquiring the run lock, collect issue activity through the deterministic helper:

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked repo-assist \
  issue-activity --token <lock-token>
```

For an explicitly requested limited sweep, add `--recent-days <days>`. This includes only issues
currently open and created within that many days of the run's start, plus issues in any state with
a comment posted within the same window. Comment edits alone do not qualify an older issue.
The helper checks comment metadata even when an issue's root timestamp is unchanged, but skips
events, linked PR context, and text pulls for excluded issues. State checkpoints for excluded
issues are preserved. The artifact records this optional scope; state it in the report and do not
silently make it the default for future sweeps. Without this option, the full-backlog workflow below
applies.

Read only the sanitized artifact returned by the helper. It paginates body-free metadata for all
issue states, comments, and closure/reopening events. Issue titles/bodies require a trusted issue
author; comment bodies independently require a trusted comment author. A trusted comment on an
untrusted issue can be assessed, but the untrusted issue title/body remains unavailable. Untrusted
activity and linked PRs expose metadata only. Trust checks, pagination, content pulls, and activity
comparisons belong to Python; the model judges meaning and coverage from fresh sanitized content
and carries unchanged assessments forward through the report helper below.

Cross-repository PR references are supported. The helper resolves each PR in its own repository,
checks its author against the configured repository's trusted-author set, and fetches its summary
only when that author is trusted and fresh assessment content is needed. It does not add external collaborators to the trust set. Valid
external references do not abort collection; untrusted ones expose metadata only. Author-check,
metadata, or pagination failures still fail closed. External PRs provide issue context rather than
entering the contributor review queue; do not check out or repair their branches. Use the sanitized
external summaries instead of invoking discovery or snapshots in another repository to expand trust.

The window begins at the last successful `lastIssueActivityCutoff`, falling back to
`lastRunCompletedAt` for older state. With neither timestamp, establish a baseline and review the
open backlog without calling it newly filed. The next cutoff uses this run's start time, with
inclusive overlap: changes observed during a long run may appear again in the next report, so
activity made while PR reviews run cannot be lost. The artifact is a current snapshot rather than
a historical snapshot at that cutoff.

The helper compares issue/comment/link metadata and content hashes. It checks comment metadata
even when the parent issue's timestamp did not change, detects added/edited/deleted comments when
there is a checkpoint, and identifies timestamp-established edits during migration. The artifact's
`commentChangeBasis` distinguishes checkpoint comparisons, timestamps, and baseline data. Earlier
content may be unavailable; do not reconstruct deleted text or inaccessible GitHub items.

Include every trusted open issue and every trusted new/active issue in any state. Assess the
requested outcome, changes since the last run, decisions in trusted discussion, remaining work,
and maintainer action. Reassess changed issues by reviewing the delta and its implications; reuse
valid prior reasoning instead of repeating investigations with unchanged premises. Carry unchanged
assessments forward mechanically in full using the workflow below. Issue triage alone does not
authorize implementing code, closing issues, posting comments, or opening a PR.

For each issue, list known PRs with repository-qualified identity, URL, state, draft status,
relationship (`fixes` or `references`), coverage (full, partial, related only, unknown), evidence,
and remaining work. Include drafts and
owner-authored PRs for coverage even though they are excluded from contributor code reviews. Refresh
previously known PR identities after merge/closure to retain implementation history. PR caches,
deduplication, checkpoints, and closing-issue relationships use `(repository, number)` identities;
equal numbers in different repositories must not share evidence or imply issue coverage. Legacy
checkpoints without a PR repository refer to the configured repository. A closing link
or reference alone does not establish complete implementation. Use trusted PR context and code
reviews to assess coverage; untrusted PR coverage remains unknown. Distinguish no known addressing
PR from an uninspected linked PR. Merged/closed PRs are history rather than active implementation
coverage and can still leave requirements unresolved.

Provide a clear overview of issues with addressing PRs, partial coverage, and implementation work
with no known addressing PR. An independently trusted comment on an untrusted issue may be
summarized with attribution; keep the root issue metadata-only and do not infer hidden content.

Complete the whole report before `end-run --token <lock-token>` promotes the pending checkpoint.
On failure or interruption use `end-run --token <lock-token> --interrupted`, preserving the prior
successful cutoff and checkpoint. Failed recollections invalidate pending success, so they cannot
be finalized using stale earlier evidence. Do not advance checkpoint fields manually.

## Report And State

After the report title/run metadata and before contributor PR Summary, include `Repository
Activity Since Last Run`. State the window or first-run baseline; summarize new issues, comments,
edits, requirement/decision changes, closures/reopenings, and meaningful PR changes established by
this run. Cross-reference PR coverage and call out draft work, uncovered issues, and maintainer
decisions. Keep unchanged backlog in the current-state tables; say when no new activity was found.

Include an issue summary with the reviewer-owned empty `Done` column:

| Done | Issue | Activity | Addressing PRs / Coverage | Action Needed |
|---|---|---|---|---|
|  | [Issue #321](#issue-321) | New regression details | #323 (draft; partial) | Remaining reproduction needed |

Put the issue tables and details inside one `## Issues` section. Each detail begins with a stable
`<a id="issue-<number>"></a>` anchor followed by `### Issue #<number>: <title>` (omit the title for
an untrusted root). Include URL, current state, activity,
requested outcome, decisions from trusted discussion, linked PR table, remaining work/questions,
and recommendation. Use a metadata-only table for untrusted new/active/open issues. Include
independently trusted comments with attribution; never include an untrusted title/body or comment.

`issueActivity` is the helper-owned per-issue checkpoint of metadata, comment IDs/timestamps,
state-event metadata, PR metadata, and content hashes. `lastIssueActivityCutoff` advances only after
a successful report. Keep maintainer assessments separately in `state.json.issues`, recording
`fingerprint` (the stable evidence digest), `evidenceFingerprint`, `contentFingerprint`,
`lastReviewedAt`, `lastReport`, and recommendation. The issue helper writes these bindings; do not
use a fingerprint of transient artifact fields such as the activity window or `changed` flag.
Preserve both when migrating older PR-only state. Contributor PR review freshness remains governed by its existing keys.

## Incremental Assessment And Report Reuse

Refresh metadata every run, including all comment IDs/authors/timestamps, state events, known PR
identities and closing links, PR head/state/draft status, and the current trusted-author set. These
inputs form `evidenceFingerprint`; a change invalidates the saved assessment. Trust changes also
invalidate reuse before any earlier assessment prose is exposed. Unrelated changes to main do not
invalidate all issues: identify issues whose assessment depends on changed code or a new maintainer decision
and explicitly request fresh assessment for those issues.

The artifact routes each selected issue with `assessmentAction`:

- `review`, `contentStatus: fresh`: read its freshly gated content and assess the delta. New issues,
  changed evidence, explicit reassessments, and missing legacy bindings take this path. Root,
  comment, and trusted PR summary content is fetched only on this path. An untrusted root still
  has no title/body; comments retain independent author gating.
- `reuse`, `contentStatus: unchanged`: current evidence agrees with both the last successful
  collection and a saved assessment. The artifact contains metadata, hashes and `assessmentSource`,
  but intentionally omits root/comment bodies and PR summary text. Do not refetch snapshots or
  rewrite these assessments solely because the issues remain open.

Use the loaded skill's report helper to copy all reusable issues into the active report:

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked python \
  "$REPO_ASSIST_SKILL_DIR/scripts/issue-tools.py" --root "$REVIEW_ROOT" \
  carry-forward --token "$RUN_TOKEN" --activity "$RUN_ARTIFACTS/issue-activity.json"
```

It validates the current run, latest successful collection, and saved bindings before reading the
prior report. It copies the complete issue section and coverage table, preserves reviewer-owned
`Done` cells (preferring an existing cell in the active report), and labels the assessment reused.
It updates `lastReport` and `lastCarriedAt`, preserving `lastReviewedAt`. Do not read entire earlier
reports to regenerate unchanged issue sections. Missing or malformed source sections require fresh
assessment rather than reconstructing inaccessible content. Repeating the helper is idempotent;
report and state files are individually atomic, and the active lock serializes writes.

For a policy/code change or an unavailable prior report, recollect with explicit issue numbers:

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked repo-assist \
  issue-activity --token "$RUN_TOKEN" --reassess 321 654
```

Repeat any explicitly requested `--recent-days` scope on recollection. Reassessment does not expand
that scope. Recollection replaces the artifact and invalidates the old pending success first; only
the latest artifact is valid for report helpers. Keep all still-needed reassessment numbers on each
retry until checkpointed. Refresh the affected issue's reasoning, not the entire backlog.

Before writing fresh issue rows, recover reviewer marks with this read-only helper:

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked python \
  "$REPO_ASSIST_SKILL_DIR/scripts/issue-tools.py" --root "$REVIEW_ROOT" \
  done-values --token "$RUN_TOKEN" --activity "$RUN_ARTIFACTS/issue-activity.json"
```

Copy its `done` values into fresh rows; changes to an issue do not reset the reviewer's marks.
The helper exposes only the reviewer-owned cells, even when old assessment text is ineligible for
reuse. An existing active-report cell takes precedence, including a deliberately cleared mark.
New issues default to empty cells; `unavailable` flags inaccessible old marks rather than inventing
them. Do not read old issue prose to obtain a mark. Write full sections and summary rows for freshly
assessed issues, then checkpoint explicit numbers:

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked python \
  "$REPO_ASSIST_SKILL_DIR/scripts/issue-tools.py" --root "$REVIEW_ROOT" \
  checkpoint --token "$RUN_TOKEN" --activity "$RUN_ARTIFACTS/issue-activity.json" \
  --issues 321 654
```

This records the stable evidence/content bindings and the actual assessment time in `state.issues`,
without advancing helper-owned successful collection state or changing contributor/authored PR
records. Older assessments lacking these bindings receive one fresh baseline; subsequent unchanged
runs can reuse them. Before completing the report, audit every selected issue's full section, summary
row, and matching assessment bindings pointing to this report:

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked python \
  "$REPO_ASSIST_SKILL_DIR/scripts/issue-tools.py" --root "$REVIEW_ROOT" \
  audit --token "$RUN_TOKEN" --activity "$RUN_ARTIFACTS/issue-activity.json"
```

This read-only structural check does not replace judging changed evidence or reviewing report
accuracy. The report remains self-contained even when only a few issues needed new judgment.

Individual trusted issue snapshots are also available without changing the legacy PR command:

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked repo-assist \
  snapshot <number> --kind issue --previous-fingerprint <fingerprint>
```
