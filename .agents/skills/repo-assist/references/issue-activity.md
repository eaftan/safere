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
comparisons belong to Python; the model judges meaning and coverage from this sanitized output.

Cross-repository PR references are supported. The helper resolves each PR in its own repository,
checks its author against the configured repository's trusted-author set, and fetches its summary
only when that author is trusted. It does not add external collaborators to the trust set. Valid
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
and maintainer action. Carry unchanged assessments forward in full. Issue triage alone does not
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

Each issue detail has a stable `<a id="issue-<number>"></a>` anchor, URL, current state, activity,
requested outcome, decisions from trusted discussion, linked PR table, remaining work/questions,
and recommendation. Use a metadata-only table for untrusted new/active/open issues. Include
independently trusted comments with attribution; never include an untrusted title/body or comment.

`issueActivity` is the helper-owned per-issue checkpoint of metadata, comment IDs/timestamps,
state-event metadata, PR metadata, and content hashes. `lastIssueActivityCutoff` advances only after
a successful report. Keep maintainer assessments separately in `state.json.issues`, recording
`fingerprint`, `lastReviewedAt`, `lastReport`, and recommendation. Preserve both when migrating
older PR-only state. Contributor PR review freshness remains governed by its existing keys.

Individual trusted issue snapshots are also available without changing the legacy PR command:

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked repo-assist \
  snapshot <number> --kind issue --previous-fingerprint <fingerprint>
```
