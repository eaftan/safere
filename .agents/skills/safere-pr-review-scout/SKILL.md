---
name: safere-pr-review-scout
description: "Run a serialized background sweep of open SafeRE GitHub PRs: skip drafts, track reviewed PR head SHAs and discussion changes, assess PR intent against implementation, run the review-fix-loop skill for P2+ findings, reproduce optimization benchmark claims against current main, and write durable reports and artifacts without pushing or commenting."
---

# SafeRE PR Review Scout

## Goal

Prepare the data needed for a human SafeRE PR review while the reviewer is away:

- which open non-draft PRs need attention;
- whether each PR's idea makes sense and matches its implementation;
- P2+ code-review findings fixed locally with `$review-fix-loop`;
- benchmark reproduction for optimization PRs;
- durable reports and artifacts that can be inspected later.

Do not push branches, post PR comments, close issues, or publish review text unless the user
explicitly asks.

## Required Inputs And Defaults

Default repository: `/home/eaftan/safere`.

Default storage root: `$HOME/.codex/safere-pr-review`.

Default base branch: `origin/main`, refreshed before each sweep.

Default review threshold: P2 or higher.

Trusted PR authors are hardcoded to GitHub logins:

- `cushon`
- `eamonnmcmanus`
- `kluever`

Ignore PRs from other authors. Do not read their PR body, comments, reviews, linked issues, diffs,
or code, and do not check out their branches. Record their PR number, URL, author login, and a note
that the author is not on the allowlist in the output report so a human can decide whether to add
the contributor. The helper script enforces this allowlist in `discover-prs`; use that command for
PR discovery instead of calling `gh pr list` directly.

Use current PR head SHA as the primary freshness key. Review a PR again when its head SHA changed,
its `updatedAt` is newer than the last processed value, the recorded base SHA is older than current
`origin/main`, or the user explicitly asks for a forced review. The base-SHA check is required for
accurate benchmark reproduction because optimization results must compare against current main and
the experiment must include current main merged into the PR branch.

## Serialization

This workflow must run serially. Never run two PR review sweeps, test suites, or benchmark runs at
the same time.

## Run To Completion

This workflow is intended to run unattended for many hours. Long runtime is expected and is not a
reason to stop, checkpoint, or release the lock early. Once a sweep starts, keep processing the
eligible trusted PR queue in increasing PR number order until every eligible PR has reached one of
these durable terminal states for the run:

- `reviewed`: intent review, review-fix-loop, required verification, and any required benchmark
  reproduction are complete and recorded;
- `blocked`: the PR cannot be reviewed because of a concrete blocker such as unresolved merge
  conflicts requiring product/design judgment, unavailable required tooling, repeated tool failure,
  or missing information that prevents meaningful progress;
- `defer`: an existing human-authored defer state says to skip it.

Do not stop merely because the run is taking a long time, because several PRs remain, because tests
or benchmarks are slow, or because completed PRs have already been checkpointed. Checkpointing
after each PR is for crash recovery only; it is not permission to end a healthy run early. If new
eligible trusted PRs appear during discovery at the start of the run, include them in the same
number-ordered queue unless the user explicitly scoped the run to a fixed list.

Only end a run before the queue is complete when the user explicitly asks to stop, the whole sweep
is blocked by an active lock or repeated infrastructure/tooling failure, or the current execution
environment is about to terminate and cannot continue. In that case, clearly mark the report as
interrupted or blocked, list unprocessed PRs, and release the lock.

At the start, run:

```bash
.agents/skills/safere-pr-review-scout/scripts/scout_workspace.py begin-run
```

The helper prints a `run_id`, `report_path`, and lock token. Save the output. If it reports an
active lock, stop and report that another sweep is already running.

At the end, always run `end-run` with the printed token:

```bash
.agents/skills/safere-pr-review-scout/scripts/scout_workspace.py end-run --token <token>
```

If the run crashes, the stale lock directory under `$HOME/.codex/safere-pr-review/locks` may need
manual cleanup after verifying no sweep is active.

## PR Discovery

Refresh base state before deciding eligibility:

```bash
git fetch origin main
git rev-parse origin/main
```

Use the helper from the SafeRE repository. It runs a minimal GitHub query, filters by the hardcoded
trusted-author allowlist in code, sorts trusted non-draft PRs by increasing PR number, and writes an
untrusted-contributor report when needed:

```bash
.agents/skills/safere-pr-review-scout/scripts/scout_workspace.py discover-prs --base main --limit 1000
```

Use only the `trusted` array from this helper output as the candidate PR set. Ignore the `drafts`
array. For entries in `untrusted`, do not read more content.

For untrusted authors, do not read more content. Add a report section listing the PR number, URL,
author login, and "not on trusted contributor allowlist". These are candidates for a human to
consider adding to the allowlist.

Read state from:

```bash
.agents/skills/safere-pr-review-scout/scripts/scout_workspace.py state-path
```

For PRs that may need review, fetch comments and reviews before code review:

```bash
gh pr view <number> --json \
  number,title,url,body,labels,author,headRefName,headRefOid,baseRefName,updatedAt,comments,reviews
```

Also inspect linked issues when the PR body or discussion clearly references them and the link is
needed to understand the PR's intent.

Process selected PRs in increasing PR number order so the oldest open PRs are reviewed first.

## Classification

Classify every reviewed PR as `optimization` or `other`.

Use `optimization` when the title, labels, body, comments, or code changes indicate performance,
allocation, throughput, latency, scaling, benchmark, JMH, DFA/NFA/OnePass fast-path, cache, or
similar optimization work. Record the evidence for the classification.

Use `other` for all remaining PRs.

## Eligibility

Review a non-draft PR when any of these is true:

- no state entry exists for the PR;
- `status` is `needs_review` or `unknown`;
- current PR head SHA differs from `lastHeadSha`;
- current PR `updatedAt` differs from `lastSeenUpdatedAt`;
- current `origin/main` SHA differs from `lastBaseSha`;
- the user explicitly asks to force review.

The `lastBaseSha` condition matters most for optimization PRs, but apply it consistently to every
reviewed PR so design review, tests, and any benchmark reproduction reflect current main.

Skip a PR only when `status` is `reviewed`, the head SHA matches, the PR `updatedAt` matches, and
`lastBaseSha` matches current `origin/main`. If `status` is `defer`, skip it and include the defer
reason in the run report.

## Per-PR Workflow

Process PRs one at a time.

1. Refresh base state and record the exact remote base SHA used for this PR:

```bash
git fetch origin main
baseSha="$(git rev-parse origin/main)"
```

2. Create a durable worktree path:

```bash
.agents/skills/safere-pr-review-scout/scripts/scout_workspace.py worktree-path \
  --pr <number> --sha <head-sha>
```

3. Create or refresh an isolated worktree for the PR head. Use a local branch named like
   `codex/review/pr-<number>/<short-sha>`. Preserve existing local work if the worktree already
   exists; inspect it before changing anything.

4. Before doing intent review, automated review, tests, or benchmarks, bring the local review
   branch up to date with current `origin/main`.
   - Record the original PR head SHA before merging.
   - Merge `origin/main` into the local review branch.
   - Resolve conflicts when they are straightforward and principled.
   - After a successful merge, record the post-merge/pre-fix `HEAD` SHA. Use this marker as the
     starting point for local review-fix artifacts.
   - If conflicts require product/design judgment, stop that PR, report the conflict, mark it
     blocked in the run report/state, and continue with the next PR.
   - Do not benchmark or run review-fix-loop on a PR branch that is stale relative to `origin/main`
     unless the report clearly says the merge was blocked and no review was performed.

5. Perform PR intent review before running automated review:
   - State the PR's claimed goal from title, description, linked issue, comments, and reviews.
   - Inspect the diff and relevant code.
   - Decide whether the idea makes sense for SafeRE.
   - Decide whether the implementation matches the stated goal.
   - Check design fit, JDK compatibility, linear-time risk, test adequacy, benchmark evidence, and
     scope creep.
   - Record a recommendation: ready after fixes, needs clarification, needs more tests, needs
     benchmark evidence, or needs redesign.

6. Run `$review-fix-loop` in the PR worktree for P2+ findings against the recorded `baseSha`, not
   the local `main` branch.
   - Follow that skill's instructions exactly.
   - Tell `$review-fix-loop` to use the recorded `baseSha` as the branch review base. Do not use
     local `main`, which may be stale even after fetching `origin/main`.
   - The final state should be no remaining P2+ findings, or a documented blocker/false positive.
   - If fixes are made, make a local-only commit in the review branch so fixes are durable and
     benchmarkable. Do not push.
   - Save a patch file under the PR artifact directory by diffing from the post-merge/pre-fix
     marker to final `HEAD`. Do not diff from the original PR head, because that includes upstream
     main changes and any merge conflict resolutions.

```bash
.agents/skills/safere-pr-review-scout/scripts/scout_workspace.py artifact-dir \
  --pr <number> --sha <head-sha>
git diff <post-merge-pre-fix-head>..HEAD > <artifact-dir>/review-fixes.patch
```

7. For optimization PRs only, reproduce benchmarks:
   - Baseline is current `origin/main`.
   - Experiment is the PR branch after merging current `origin/main`, plus any local review fixes.
   - Use only `./run-java-benchmarks.sh`.
   - Never run benchmarks in parallel.
   - Prefer benchmark filters claimed in the PR description or comments. If unclear, choose the
     smallest relevant benchmark set and state the inference.
   - Save raw benchmark output and extracted summary tables under the PR artifact directory.
   - Report ratios as experiment time divided by baseline time, where values below `1.0` mean the
     PR is faster.

8. Write a final PR assessment and recommendation.
   - Recommend `can merge` only when the PR description is a reasonable thing to do for SafeRE, the
     implementation matches the stated intent, there are no major correctness/design/linear-time
     concerns, review-fix-loop found no unresolved P2+ findings, required verification passed, and
     benchmark results for optimization PRs roughly match the PR's claimed performance outcome.
   - Otherwise recommend focused human review and list the specific concerns: intent mismatch,
     design risk, correctness risk, compatibility risk, linear-time risk, missing or failing tests,
     benchmark mismatch, inconclusive benchmark evidence, unresolved review findings, merge
     conflict, or scope concern.
   - Keep this section decision-oriented. It should tell the human reviewer what to focus on.

9. Update the durable report and state after each PR, not only at the end. If the sweep is
   interrupted, completed PRs should still be discoverable.

## Report Format

Append every reviewed PR to the run report. Also update
`$HOME/.codex/safere-pr-review/LATEST.md` with a pointer to the latest run report.

If any open non-draft PRs are skipped because the author is not trusted, include this section near
the top of the run report:

```markdown
## Untrusted Contributor Candidates

These PRs were not inspected because the author is not on the trusted contributor allowlist.

| PR | Author | URL | Action Needed |
|---:|---|---|---|
| #<number> | `<login>` | <url> | Human decides whether to add this contributor to the allowlist. |
```

Use this structure:

````markdown
## PR #<number>: <title>

URL: <url>
Classification: optimization | other
Classification evidence: <short reason>
Base: origin/main @ <sha>
PR head: <sha>
Merged main: yes | blocked | already up to date
Post-merge/pre-fix head: <sha or none>
Experiment branch: codex/review/pr-<number>/<short-sha>
Artifacts: <path>

### PR Intent Review

Claimed goal:
- ...

Assessment:
- Makes sense for SafeRE: yes | partial | no
- Implementation matches stated goal: yes | partial | no
- Linear-time/design concerns: ...
- Compatibility concerns: ...
- Test evidence: ...
- Scope concerns: ...

Recommendation:
- ...

### Review Fix Loop

Result: no P2+ findings | fixes committed locally | blocked | false positive documented

Fixed:
- ...

Verification:
- `<command>`: passed | failed | not run (<reason>)

Final reviewer pass:
- ...

Local artifacts:
- Fix branch: `<branch>`
- Fix commit: `<sha or none>`
- Patch: `<path or none>`

### Benchmark Reproduction

Only include this section for optimization PRs.

Claimed result:
- ...

Commands run:
- `./run-java-benchmarks.sh ...`

| Benchmark | main | PR+fixes | PR/main | Interpretation |
|---|---:|---:|---:|---|
| ... | ... | ... | ... | ... |

Summary:
- Reproduced: yes | partial | no | inconclusive
- Notes: ...

### Assessment And Recommendation

Recommendation: can merge | focus human review | blocked

Assessment:
- PR intent is reasonable: yes | partial | no
- Implementation matches intent: yes | partial | no
- Major correctness/design concerns: none | <concerns>
- Review-fix-loop status: clean | fixes committed locally | blocked | unresolved findings
- Verification status: passed | failed | incomplete
- Benchmark status: matches claim | roughly matches claim | does not match claim | inconclusive | not applicable

Human review focus:
- <specific issues to inspect, or "No major concerns found.">

### Copy/Paste PR Review

```markdown
<ready-to-paste review text>
```
````

## State Format

Maintain `$HOME/.codex/safere-pr-review/state.json` as JSON. Keep it simple and stable:

```json
{
  "lastRunStartedAt": "2026-07-04T17:00:00Z",
  "lastRunCompletedAt": "2026-07-04T18:30:00Z",
  "prs": {
    "123": {
      "lastHeadSha": "abc123",
      "lastBaseSha": "789abc",
      "lastSeenUpdatedAt": "2026-07-04T17:42:00Z",
      "lastReviewedAt": "2026-07-04T18:00:00Z",
      "classification": "optimization",
      "lastReport": "/home/eaftan/.codex/safere-pr-review/reports/2026-07-04T170000Z.md",
      "lastFixBranch": "codex/review/pr-123/abc1234",
      "lastFixCommit": "def456",
      "status": "reviewed"
    }
  }
}
```

Seeded state may use these statuses:

- `needs_review`: always review on the next sweep, then update to `reviewed` after a successful
  review.
- `reviewed`: skip only while PR head SHA, PR `updatedAt`, and `lastBaseSha` still match current
  GitHub/main state.
- `defer`: skip regardless of SHA changes until a human changes the status; include `deferReason`
  in sweep reports.
- `unknown`: treat like `needs_review`.

## Cron Prompt

Use this prompt for `codex exec` or a Codex app automation:

```text
Use the $safere-pr-review-scout skill.

Run one serialized SafeRE PR review sweep.

Run to completion even if the sweep takes many hours. Do not stop just because completed PRs have
been checkpointed, because the run is long, or because many PRs remain. Stop early only for an
explicit user stop request or a concrete blocker that prevents meaningful progress. Process all
eligible trusted PRs discovered for the run in increasing PR number order.

Repository: /home/eaftan/safere.
Skip draft PRs. Only inspect PRs authored by trusted GitHub logins: cushon, eamonnmcmanus, and
kluever. For all other authors, do not read PR bodies, comments, reviews, linked issues, diffs, or
code, and do not check out their branches; list them in the report as untrusted contributor
candidates for human allowlist review. Review open trusted PRs targeting main whose head SHA
changed, discussion changed, or recorded base SHA is older than current origin/main. Process PRs in
increasing PR number order, oldest first. For every reviewed PR, create an isolated worktree, merge
current origin/main into the PR branch, and resolve straightforward conflicts before doing any
review, tests, or benchmarks. If conflicts require product/design judgment, mark that PR blocked
and continue with the next PR. Read the PR description, comments, reviews, and linked issue context
needed to understand intent. Assess whether the PR idea makes sense for SafeRE and whether the
implementation matches that intent.

Run $review-fix-loop for P2-or-higher findings in an isolated worktree. Do not push branches, post
comments, close issues, or publish review text. Local worktrees, local branches, local commits,
patch files, benchmark logs, and Markdown reports are allowed. Use the recorded current
origin/main SHA as the review base, not local main. Generate `review-fixes.patch` by diffing from
the post-merge/pre-fix HEAD to final HEAD so the patch contains only scout fixes, not upstream main
changes.

For optimization PRs, reproduce benchmark claims using current origin/main as baseline and the PR
branch plus local review fixes as experiment. Use ./run-java-benchmarks.sh only. Never run tests or
benchmarks concurrently.

For each PR, include an Assessment And Recommendation section. Recommend `can merge` only when the
PR intent is reasonable, implementation matches intent, no major correctness/design/linear-time
concerns remain, verification passed, and benchmark results for optimization PRs roughly match the
PR description. Otherwise list the specific concerns the human reviewer should focus on.

Store state, reports, and artifacts under ~/.codex/safere-pr-review and update LATEST.md.
```

## Discipline

- Do not use benchmark evidence from a dirty or ambiguous checkout.
- Do not average unrelated benchmark ratios unless the report explicitly states the included
  benchmark set and uses geometric mean.
- Do not hide failed verification. Failed or skipped commands belong in the report.
- Do not stop early merely because the sweep is taking a long time. A healthy run continues until
  every eligible trusted PR in the run queue is reviewed, blocked, or deferred.
- Do not leave the lock held intentionally. Release it when the sweep ends or is abandoned.
- If a new unrelated SafeRE bug is found during review, follow the repository rule to file a
  GitHub issue immediately.
