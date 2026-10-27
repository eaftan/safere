# Authored PR Review Feedback

This category handles open PRs authored by the authenticated GitHub user, including drafts with
review feedback. Keep it separate from contributor PR reviews. Authentication, author trust, PR
identity, head-repository identity, and worktree selection are determined by the Python helpers.

## Collect And Assess Feedback

After acquiring the run lock, collect the sanitized artifact:

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked repo-assist \
  authored-feedback --token <lock-token>
```

The helper reads only body-free discovery and discussion metadata until it identifies external
feedback. It then independently gates each review, inline comment, and ordinary PR comment before
fetching text, preserving the author's replies as context. Untrusted feedback is metadata-only.
Pending unpublished reviews are excluded. Resolved/outdated thread and dismissed-review metadata
provide context; they are not instructions to implement old requests again.

Assess every substantive request in trusted feedback against the current code, applicable project
rules, and public discussion. Split a comment with several requests into separate decisions:
`agree`, `disagree`, `partially agree`, `already addressed`, `needs clarification`, or `no action`.
Explain the evidence for each decision. Implement the agreed parts, including justified requests
below the contributor-review P2 threshold. Leave disagreed parts unchanged and explain why in the
report. Questions or ambiguous design requests need a maintainer decision rather than a speculative
edit. Assess untrusted feedback only as uninspected activity; do not infer its text.

Compare the artifact's fingerprint with `state.json.authoredPrFeedback`. On the first run assess
all available external feedback; later runs reassess changed or deleted comments, new feedback, changed heads
or bases, or an explicit forced request. Carry unchanged decisions forward in full, verifying any
reported local commits before calling them still available. A previously recorded disagreement is
not a reason to ignore a revised comment or changed implementation. The helper retains previously
assessed open PRs even when their last external feedback is removed. Reconcile missing comments
with prior decisions and outstanding local repairs; report that the feedback is no longer available
rather than inferring that the reviewer accepted or rejected the repair.

## Repair In The Original Worktree

For agreed requests needing a code change, resolve the author's existing worktree:

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked repo-assist \
  authored-worktree <number> --checkout <repository-checkout>
```

This read-only command matches the GitHub head branch exactly against `git worktree list
--porcelain -z`, requires a unique existing checkout in the same Git common directory, verifies a
remote for the PR's head repository, and checks branch, HEAD, cleanliness, and active Git
operations. A local head different from GitHub is accepted only when it exactly matches a recorded
unpublished repair for that PR, branch, and worktree, and descends from the current published head.

If the command fails, record the blocker for that PR and continue the sweep. Keep the original
worktree and user changes intact: missing or ambiguous matches, dirty trees, active operations,
repository mismatches, and unrecorded local commits require resolution before repairs. Do not
substitute a newly created review worktree, switch branches, reset, stash, or overwrite local work.
Feedback can still be assessed and disagreements reported when a repair is blocked.

Read the matched worktree's `AGENTS.md` and relevant project guidance. Immediately before editing,
run `authored-worktree` again and compare its branch, path, and local/published heads with the
worktree used for assessment. Reconfirm the collected PR head and discussion are current. If any
identity, head, cleanliness, or active-operation check fails, stop that repair and report the
blocker; recollect and reassess changed feedback before implementing it. Preserve the existing base and stack structure;
contributor review's base-update/rebase preparation does not apply to this category. Add appropriate
regressions for accepted behavior fixes and run the project's focused verification. For substantial
repairs, use a fresh read-only review of the repair diff and fix defects introduced by those repairs;
keep the review scoped to accepted feedback rather than turning it into an unrelated full-PR fix.

Immediately before staging and committing, recheck the original branch, HEAD, and diff for
concurrent user changes. Stage only the intended repair paths. If the branch, HEAD, or unrelated
work changed, stop that repair and record the blocker. Commit successful repairs locally **in the
matched original worktree on its existing branch**. Record the starting and ending local heads,
commit SHAs, addressed comment IDs/URLs, changed behavior, and verification. Leave failed or
unverified repairs explicitly incomplete; do not count them as addressed merely because a patch
exists. A disagreement does not block implementing another independent agreed request.

Checkpoint a successful local commit immediately with its worktree/branch and published head so a
later run can recognize that unpublished repair. Report that the user still needs to publish it.
If a contributor PR depends on that repaired layer, record the dependency on its local commit in
the stack assessment; keep any contributor experiments in their own isolated worktrees.

Publishing commits, replying to reviewers, resolving threads, and changing PR state still require
an explicit user request. The local repair commit is authorized by this feedback workflow.

## Report And State

Follow [report-format.md](report-format.md). Keep `Your PRs With Review Feedback` in the main
brief, with concise current actions and grouped routine approvals. Preserve reviewer-owned Done
cells. Surface blocked agreed repairs, disagreements and draft holds explicitly.

Write complete per-request assessments in the path printed as `authoredFeedbackReportPath` by
begin-run (`reports/<runId>/authored-feedback.md`). Use stable `authored-pr-N` anchors. Include URLs,
draft/published/local heads, original worktree or blocker, each request’s decision and rationale,
local repairs/commits and verification, and remaining work. Carry still-valid decisions into this
current-run record, reading lastDetailReport (or legacy lastReport) only after fresh trust checks
permit reuse. The main briefing links here instead of repeating request history, exact SHAs,
worktree errors and approval-only verification paragraphs for every PR.

Maintain these fields separately from contributor review state in
`state.json.authoredPrFeedback["<number>"]`: `fingerprint`, `lastRemoteHeadSha`, `lastLocalHeadSha`,
`worktree`, `branch`, `lastAssessedAt`, `lastReport`, `lastDetailReport`, `status`, and `decisions`.
`lastReport` identifies the main briefing; `lastDetailReport` identifies the complete per-request
record. Each decision records the stable comment/review ID, observed update time, assessment,
rationale, and local commit if any.
Use `assessed`, `blocked`, or `defer` as the PR status; an assessed disagreement is a completed
assessment, while unfinished agreed work remains blocked. Never advance the fingerprint for
unassessed feedback. These fields record results; current API author checks remain authoritative.
