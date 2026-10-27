# Maintainer Brief And Supporting Records

The main report should answer “what changed, why does it matter, and what should I do?” without
requiring another chat summary. Use concise connected prose and small tables. Preserve the full
reviewing workflow and evidence; organize it so the reader chooses when to inspect that detail.

## Main report

For a small queue, aim for 600–900 words. Scale with actionable work rather than repeating every
field in the state schema. Include:

1. A short opening with the important decisions or blockers.
2. `## PR Summary`, one row for every eligible contributor PR, including blocked/deferred items:

   | Done | PR | Assessment | Next action |
   |---|---|---|---|
   |  | [PR #123 — Faster compilation](#pr-123) | Fix ready; first-use cost unmeasured | Push fix; request first-match measurements |

   Use a descriptive title, a concrete next action, and explicit fix publication status. Avoid
   generic instructions such as “focus human review” without saying what decision is needed.
3. `## Merge Ordering`: explain actual dependencies/conflicts and useful handling order in a few
   sentences. When independent, say so once. Distinguish tracking issues from prerequisite PRs.
4. A short explanation for each contributor PR, with its stable `pr-N` anchor and `## PR #N:`
   heading. Usually 80–150 words: explain the problem and proposed behavior, your assessment and
   material evidence/tradeoff, corrections and whether they are local or pushed, and the next step.
   Give opened/ready dates when age or draft history helps explain why it is being reviewed now.
   Retrieve dates through body-free metadata; do not infer them from a commit’s author date.
   Link directly to the standalone copy/paste review and the full review/evidence. The checkpoint
   helper inserts these links. Do not embed the long author review or repeat benchmark tables.
5. `## Your PRs With Review Feedback`: a compact table of current actionable feedback and newly
   useful approvals. Explain disagreements, draft holds and blocked repairs. Group routine
   approvals when clearer. Preserve reviewer-owned Done cells; link the complete per-request record.
6. `## Issue Activity`: only new or meaningfully changed issues, changed decisions and uncovered
   requirements that matter now. Group related issues around the same PR or decision. Omit
   inactive backlog, mechanical metadata-only changes with no implication, historical PR lists,
   and duplicate summaries of bugs already explained under contributor PRs. Link active details.
7. A short verification/evidence note: relevant checks passed or specific unresolved limitations,
   with links. Never hide a failure or unresolved P2+ finding behind a success summary.

Start with an empty Done cell for new items; preserve existing marks without inferring completion.
Keep approval, publication and merge as separate actions. A local fix is never described as pushed
in the maintainer briefing until its remote head is verified. The standalone author copy may assume
publication before posting, but label that prerequisite clearly in the briefing.

## Supporting records

Use current-run links so the briefing is self-contained for decisions while evidence is one click
away. Full records can be detailed, but omit duplicated narratives and boilerplate fields that do
not apply. Exact revisions, source/worktree paths, command logs, benchmark tables, review findings,
original-worktree blockers and historical feedback belong here.

- `reports/<runId>/pr-N.md`: complete contributor assessment and fenced author review.
- `reports/<runId>/authored-feedback.md`: complete per-request decisions and repairs, including
  routine approvals omitted or grouped in the main brief.
- `reports/<runId>/issue-activity.md`: assessments and coverage for active issues only, with the
  existing issue helper’s summary rows/anchors. No unchanged open-issue inventory.
- `artifacts/<runId>/pr-N/copy-paste-review.md`: standalone paste-ready author comment.
- Existing raw artifacts retain exact verification and benchmark evidence.

The main report and full contributor record both use the same `pr-N` identity. Keep `lastReport`
pointing at the main report and `lastDetailReport` at the current contributor detail file. For issue
assessments, `lastReport` points to the issue-activity record; the issue helper owns those bindings.
For authored feedback, record the main and detail paths separately. Never make a reader hunt through
an earlier sweep for still-valid reasoning.

## Final audit

The helper audit checks the contributor queue, freshness, main anchors, linked detail identities,
author-copy links/content, and optional worktrees. The issue helper audits active-issue supporting
records rather than requiring a backlog section in the brief. Read the main report independently:
can the maintainer understand each change and its next action without asking for a summary?
Then read each standalone author review independently using the contributor review’s semantic rules.
