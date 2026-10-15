# Contributor PR Review

Read this reference in full before discovering, triaging, reviewing, repairing, benchmarking,
or reporting contributor PRs. Shared trust, locking, publication, and run orchestration rules
remain in the parent `SKILL.md`. Use the `REPO_ASSIST_SKILL_DIR` resolved there for every helper
command. This workflow uses isolated contributor review worktrees; authored-PR feedback uses
the separate original-worktree workflow in [authored-pr-feedback.md](authored-pr-feedback.md).

Contents:

- [Contributor queue and completion](#contributor-queue-and-completion)
- [PR discovery](#pr-discovery)
- [Author-facing review](#author-facing-copypaste-review)
- [Whole-stack context](#whole-stack-review-context)
- [Self-contained report scope](#self-contained-report-scope)
- [Merge ordering](#merge-ordering-assessment)
- [Classification](#classification)
- [Eligibility](#eligibility)
- [Diff-led re-review](#diff-led-re-review)
- [Per-PR workflow, repairs, verification, and benchmarks](#per-pr-workflow)
- [Contributor report format](#contributor-report-format)
- [Contributor review state](#contributor-review-state)
- [Discipline](#discipline)

## Contributor Queue And Completion

This workflow is intended to run unattended for many hours. Long runtime is expected and is not a
reason to stop, checkpoint, or release the lock early. Once a sweep starts, keep processing the
eligible trusted contributor PR queue in dependency order, then increasing PR number among
independent PRs,
until every eligible PR has reached one of
these durable terminal states for the run:

- `reviewed`: intent review, local repair within the cycle limit, required verification, and
  any required benchmark reproduction are complete and recorded, or broad verification and
  benchmarks were explicitly skipped and recorded because unresolved in-scope findings make them
  non-decision-relevant. Actionable findings may remain when the semantic review/fix-cycle limit
  is exhausted; record them for the author instead;
- `blocked`: the PR cannot be reviewed because of a concrete blocker such as unresolved merge
  conflicts requiring product/design judgment, unavailable required tooling, repeated tool failure,
  or missing information that prevents meaningful progress;
- `defer`: an existing human-authored defer state says to skip it.

Do not stop merely because the run is taking a long time, because several PRs remain, because tests
or benchmarks are slow, or because completed PRs have already been checkpointed. Checkpointing
after each PR is for crash recovery only; it is not permission to end a healthy run early. If new
eligible trusted contributor PRs appear during discovery at the start of the run, include them in the same
number-ordered queue unless the user explicitly scoped the run to a fixed list.

Work through in-scope findings even when a principled correction changes substantial code or the
PR's design. Do not stop local repair because a fix is broad or another design-class defect appears.
Allow at most five semantic review/fix cycles after the initial read-only pass. Each cycle applies
at most one coherent semantic fix batch, runs focused verification, and obtains a fresh review pass.
If the fresh pass after the fifth cycle still has an in-scope finding, preserve the reproduction and
return the remaining findings to the author. This is a reviewed PR with unresolved findings, not a
blocked sweep. Continue to the next independent PR after recording the evidence and recommendation.

Use current PR head SHA as the primary freshness key. A changed head, discussion time, declared
base, or stack trunk triggers delta triage, not automatically a complete re-review. Inspect what
changed since the last assessed state and scale review, validation, and benchmark work to its
effect. For an upper layer in a stack, the immediately lower layer is its comparison base; for a
standalone PR, the declared target branch is its base.

## PR Discovery

Refresh base state before deciding eligibility:

```bash
git fetch origin main
git rev-parse origin/main
```

Use the helper from the SafeRE repository. It runs a body-free GitHub query over all open PRs without
filtering on their direct base branch, filters by the dynamically computed trusted-author set,
sorts trusted non-draft PRs by increasing PR number, and writes an untrusted-contributor report when
needed. Discovering every direct base is necessary for GitHub stacked PRs, whose upper layers target
the branch immediately below them rather than `main`:

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked repo-assist discover --limit 1000
```

The helper obtains the repository-owner login and authenticated user's login from body-free
GitHub metadata in deterministic code. Its `trusted` array excludes owner and authenticated-user
PRs from contributor review; `authored` contains the authenticated user's trusted PRs, including
drafts, for the separate feedback workflow. Ignore contributor `drafts`. For `untrusted` entries,
do not read more content.

Repository-owner PRs remain excluded from contributor review, its summary, and its `prs` state.
They may supply trusted dependency context or issue coverage. When authored by the authenticated
user, they are eligible for the separate feedback assessment and original-worktree repair workflow;
use `authoredPrFeedback` state and that category's report section.

Review every remaining trusted open non-draft PR regardless of its direct base branch. Use the
discovered `headRefName` and `baseRefName` relationships, confirmed with GitHub's `stackEntry`
GraphQL metadata when a chain is present, to identify official stacks, their trunk, and each PR's position. A PR that
targets a non-`main` branch but is not in an official stack is still eligible; review it against its
declared base and state that target clearly in the report.

For untrusted authors, do not read more content. Add a report section listing the PR number, URL,
author login, and "not on trusted contributor allowlist". These are candidates for a human to
consider adding to the allowlist.

Read state from:

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked repo-assist state-path
```

For PRs that may need review, request the sanitized snapshot before code review:

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked repo-assist \
  snapshot <number> --previous-fingerprint <fingerprint>
```

Determine the authenticated reviewer's GitHub login with `gh api user --jq .login`. For each PR,
find that reviewer's latest public comment or submitted review and record it as the human-review
cutoff. Treat this as the signal for when the human last inspected the PR. If there is no such
comment or review, treat the report as the human's first review of the PR.

Keep two narrative baselines separate:

- **Human report baseline:** write the current decision support from the human-review cutoff, not
  as a diff from the previous scout run. Consolidate prior unposted scout work when needed so the
  report is understandable without reading older scout reports. If the human has already approved
  or commented and nothing actionable remains, say that no further review comment is needed.
- **PR-author baseline:** assume the author knows only the public PR description and discussion.
  Explain every finding or pushed fix that has not been communicated publicly, even if an earlier
  scout found it. Never assume the author saw an internal scout report or local work.

Use scout state and earlier reports for freshness, crash recovery, and evidence reuse only. Do not
use the previous scout run as the narrative point of view.

## Author-Facing Copy/Paste Review

The detailed scout report is private maintainer decision support. The fenced content under
`Copy/Paste PR Review` is the only part of that report the PR author will see. Treat it as the actual
review deliverable, not as a summary, excerpt, or pointer to the rest of the report.

- Make the copy/paste review fully self-contained. Never rely on a finding, rationale, benchmark
  table, tradeoff, requested measurement, or recommendation appearing elsewhere in the report.
- Include every material finding and unresolved concern that affects the recommendation or asks the
  author to act. For each one, state the concrete behavior, why it matters, and whether it was fixed
  or what response or change is requested.
- When fixes will be pushed before posting, identify the material fixes and their effects clearly
  enough that the author understands what changed. Do not collapse distinct correctness problems
  into “several issues” when their behavior and impact differ.
- For optimization PRs, include the claimed benefit, the reproduced benchmark evidence, whether it
  supports the claim, any material cost or missing measurement, and the exact question or next
  evidence needed. Put all numeric benchmark evidence in the copy/paste review's own Markdown
  table; a table elsewhere in the report does not count.
- Omit only reviewer-internal bookkeeping such as local commands, test counts, worktree paths,
  review-agent status, and artifact locations. Omitting internal bookkeeping must not remove facts
  the author needs to understand the review decision.
- Before finalizing, extract only the copy/paste review and read it without the rest of the report.
  It passes only if the author can understand what was found, what was fixed, the evidence and
  tradeoffs behind the recommendation, every requested action, and whether the reviewer approves.
  Rewrite it if any answer depends on another report section.

Process each stack from its bottom layer upward so lower-layer changes and local fixes are included
when reviewing dependent layers. Process independent PRs in increasing PR number order.

## Whole-Stack Review Context

When a candidate PR belongs to an official GitHub stack, understand the whole stack before judging
any individual layer. Use sanitized snapshots through the helper for every trusted stack member
whose content is needed, including trusted draft layers and trusted merged foundation layers when
available. Draft and merged layers are context only unless they are independently eligible under
this skill; do not add them to the open non-draft review queue or report summary. If a stack member
or linked PR is untrusted, use only its safe metadata and state explicitly that the stack context
is incomplete. Never bypass the trust boundary to fill that gap.

Establish and record:

- the stack's shared user-facing or architectural objective from its PR descriptions and trusted
  PR discussion;
- the responsibility of each layer and why it depends on the layer below;
- which types, APIs, compatibility bridges, or invariants a lower layer provides to later layers;
- where the intended observable benefit first becomes measurable; and
- whether later layers validate the lower layer's design or instead bypass, duplicate, or soon
  replace it.

Review each PR as both an independently mergeable layer and a step toward that end state. Keep these
two judgments separate:

- **Layer-local assessment:** correctness, compatibility, linear-time and stack-safety properties,
  scope, API quality, unnecessary churn, and whether the layer performs its assigned role.
- **Stack-level assessment:** whether the layer is necessary and well-shaped for the complete
  solution, whether cumulative complexity is justified by end-to-end evidence, and whether local
  fixes narrow or invalidate assumptions in later layers.

Do not require an enabling compiler, representation, or compatibility-migration layer to produce a
standalone matching-speed improvement when the stack deliberately realizes that benefit later. In
that case, require evidence appropriate to the layer's actual role and use clearly labeled
cumulative stack evidence to judge whether the architecture has a worthwhile destination. Do not
attribute the cumulative speedup to the enabling layer. Conversely, later benchmark wins do not
excuse unnecessary lower-layer complexity, a misleading layer-local claim, an unstable abstraction,
or a layer that is unsafe to merge independently.

Interpret the PR title and body together with the stack objective. If a narrow
implementation-technique claim is inaccurate but is not required by the stack's actual objective,
classify it as a description or scope mismatch and assess whether correcting the claim is sufficient;
do not demand a redesign solely to preserve incidental wording. Require redesign when the mismatch
undermines the layer's assigned role, its consumers, or a material claimed benefit.

## Self-Contained Report Scope

Every run report is a current decision-support snapshot of all open trusted non-draft contributor
PRs, not only a log of PRs reviewed during that run. The human reviewer may not have read any
earlier scout report.

- Include every trusted non-draft PR returned by discovery whose author is not the repository owner
  in the report summary and in a detailed PR section.
- When a PR is eligible for review, assess its delta and update the prior assessment only where
  that delta changes the decision. Keep still-valid findings and evidence self-contained.
- When a PR is fresh enough to skip, carry forward and consolidate its most recent still-valid
  assessment, recommendation, copy/paste review text, local-fix references, and benchmark evidence
  into the new report. Do not merely link to or tell the human to read an older report.
- Carry evidence forward after discovery confirms the PR remains open and non-draft and delta
  triage establishes that the evidence still applies. A freshness-key change alone does not
  invalidate prior code review, tests, or benchmarks; a relevant code, workload, runner, dependency,
  or discussion change does. State which evidence was reused and why.
- Exclude merged, closed, draft, and repository-owner PRs. Include open deferred contributor PRs
  with their defer reason.
- Keep carried-forward author-facing text coherent from the public PR discussion and human-review
  cutoff. Do not describe it as old, carried forward, or unchanged in the copy/paste comment unless
  that history is meaningful in the public discussion.

Make an unfinished review-fix loop impossible to miss when scanning the report. If review or
validation began for a PR but did not reach a clean no-P2+ finding result, including unresolved
findings at the cycle limit or an incomplete final pass:

- add a bold alert immediately below the PR summary table listing every affected PR number;
- begin that PR's summary assessment with **REVIEW-FIX LOOP INCOMPLETE**; and
- add the same bold callout at the start of its detailed `Review Fix Loop` section, followed by a
  concise statement of why it stopped and any remaining findings.

Do not use this alert for benchmark-evidence gaps or ordinary human-review focus after a completed
clean loop. A dependent PR blocked before its own review begins is `blocked`, not an unfinished
review-fix loop; state its blocker clearly in the summary.

The report may identify internally which sections were reviewed in this run and which reused valid
evidence, but it must contain all information the human needs to decide and comment without opening
an earlier scout report.

## Merge Ordering Assessment

After the per-PR assessments are current, give the human a practical merge-order recommendation for
the eligible trusted non-draft contributor PRs in the report. Check:

- explicit stacked-PR or base-branch relationships;
- commit ancestry between PR heads;
- semantic dependencies, such as one PR changing data or behavior that another PR accelerates;
- shared production APIs and files that make conflicts likely; and
- whether each standalone branch conflicts with its declared base, and whether each stack remains
  linear and current with its trunk.

Distinguish three cases clearly:

- **Required ordering:** one PR actually depends on another and should not merge first.
- **Recommended ordering:** the PRs are logically independent, but an order will produce a cleaner
  integration, benchmark the final behavior, or reduce repeated conflict resolution.
- **Independent:** either order is reasonable despite possible file overlap.

Do not infer a dependency from overlapping files alone. Explain the specific behavior, API, or
conflict that supports each recommendation. Separate conflicts already caused by a declared base
or stack trunk from conflicts likely to arise between the open PRs. If useful, provide a concrete
sequence with
parenthesized groups for PRs that can land in either order. Account for unresolved review feedback
and local fixes that still need to be pushed; do not present a PR as merge-ready merely to make the
sequence tidy.

Refresh open/merged state before finalizing this section so PRs merged during a long scout run are
not included in the remaining sequence.

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
- current declared-base head SHA differs from `lastBaseSha`;
- current stack-trunk SHA differs from `lastTrunkSha`;
- the user explicitly asks to force review.

The base and trunk conditions matter most for optimization and stacked PRs, but apply them
consistently so design review, tests, and benchmark reproduction reflect the current dependency
chain. Existing state without `lastTrunkSha` is stale and must be reviewed once to populate it.

Skip delta triage only when `status` is `reviewed`, the head SHA matches, the PR `updatedAt`
matches, and `lastBaseSha` and `lastTrunkSha` match the current declared base and trunk. If
`status` is `defer`, skip it and include the defer reason in the run report.

## Diff-Led Re-Review

After trusted discovery and a sanitized snapshot, compare the current PR and its effective base
with the exact heads recorded at the last assessment. For a stack, compare each layer's patch
against its old and new immediate parents (for example with `git range-diff` or equivalent patch
comparison); a tip-to-tip diff can mistake a lower-layer rebase for a change in the upper layer.
Inspect changed discussion through `snapshot`, the changed layer diff, and any changed trunk or
base files that can affect the layer. Record the old/new SHAs and the material delta in the report.

Choose the smallest review that can validate the current decision:

- For metadata-only changes, a patch-equivalent rebase, or an unrelated trunk/base change, verify
  that the prior assessment still applies, carry its review and evidence forward, and update state.
  Do not recreate worktrees, rerun a defect pass, tests, or benchmarks solely because a SHA changed.
  If the earlier review produced local fix commits, a changed PR head or effective base requires
  refreshing the prepared review tree and replaying those fixes. Verify that the fixes still apply
  and pass focused checks; regenerate fix artifacts and references for the current head. If this
  cannot be done, report the fixes as stale and do not present the PR as ready after fixes.
- For a limited layer change, review the changed code and its affected invariants, including nearby
  call sites and tests. Run focused verification when behavior changed. Reuse earlier broad tests
  and benchmark results only if the tested production path, workload, harness, runner, and relevant
  dependency code are unchanged; otherwise run only the proportionate checks needed for the delta.
- For a new PR, substantial redesign, changed central contract, invalidated prior evidence, or a
  delta whose effect cannot be established confidently, perform the full per-PR workflow. Recheck
  a previously blocked PR's blocker first; do not treat its earlier unreviewed code as validated.

A scoped delta review can conclude with the same recommendation as before. Explain the scope and
evidence clearly; do not call a changed PR fully revalidated when only an unaffected earlier
assessment was carried forward. An explicit user request for a full review takes precedence. If a
user asks to inspect a marginal upper-layer change despite unresolved lower-stack findings, review
that layer against its submitted parent, keep the lower findings separate, and do not call the
cumulative stack merge-ready.

## Per-PR Workflow

Process PRs one at a time.

1. Refresh the integration trunk and the PR's declared base, then record their exact remote SHAs:

```bash
git fetch origin main
git fetch origin "<baseRefName>"
trunkSha="$(git rev-parse origin/main)"
declaredBaseSha="$(git rev-parse origin/<baseRefName>)"
```

   For a stack rooted somewhere other than `main`, use that stack's declared trunk instead. Record
   the stack number and position when applicable.

2. Create a durable worktree path:

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked repo-assist \
  worktree-path <number> <head-sha>
```

3. Apply Diff-Led Re-Review first. When its result requires new code review, tests, fixes, or
   benchmarks, create or refresh an isolated worktree for the PR head. Use a local branch named
   like `codex/review/pr-<number>/<short-sha>`. Preserve existing local work if the worktree
   already exists; inspect it before changing anything.

4. Before new code review, tests, fixes, or benchmarks, prepare the local review branch against
   its current effective base. A validated metadata-only or unrelated-base delta needs no replay.
   - Record the original PR head SHA before merging.
   - For a standalone PR, update it against the current head of its declared base branch.
   - For the bottom of a stack, update it against the current stack trunk.
   - For an upper stack layer, first finish the local review of the layer immediately below it,
     including any local fixes. Replay only this PR's layer commits onto that prepared lower-layer
     head. Use the resulting lower-layer head as this PR's review base. Do not merge `main` directly
     into every upper layer or review the cumulative stack as though it were all introduced by the
     upper PR.
   - If a lower layer ended with any unresolved in-scope finding, do not use its submitted or
     partial local-fix head as a synthetic base. Normally mark each dependent open layer blocked by
     the unresolved downstack contract, carry forward only its sanitized intent and stack context,
     and skip code validation and benchmarks until the lower contract is coherent. Continue with
     independent PRs. For an explicitly requested marginal layer review, use the submitted parent
     only as comparison context and label the result as layer-local, not whole-stack clearance.
   - Keep the prepared stack linear. If the submitted stack is stale relative to its trunk, perform
     the cascading update locally from the bottom upward. Do not push stack rebases during a scout
     run.
   - Resolve conflicts when they are straightforward and principled.
   - Record both the prepared review-base SHA and the post-update/pre-fix `HEAD` SHA. Use the latter
     as the starting point for local review-fix artifacts.
   - If conflicts require product/design judgment, stop that PR, report the conflict, mark it
     blocked in the run report/state, and continue with the next PR.
   - Do not benchmark or run review-fix-loop on a PR branch whose effective base or stack trunk is
     stale unless the report clearly says the update was blocked and no review was performed.

5. Perform PR intent review before running automated review:
   - State the PR's claimed goal from its title, description, comments, and reviews. For a
     stacked PR, first summarize the whole stack's objective and this layer's role in achieving it.
   - Inspect the diff and relevant code.
   - Identify the central benefit the PR is intended to deliver, such as correctness,
     maintainability, throughput, lower allocation, lower retained memory, or broader capability.
     Express it as an observable outcome rather than accepting the implementation technique itself
     as the benefit. For a stacked PR, distinguish the evidence appropriate to this layer from the
     end-to-end benefit realized by the complete stack.
   - Identify the costs introduced to obtain that benefit: implementation size and duplication,
     conceptual complexity, new public API, persistent state, maintenance burden, compatibility
     risk, and performance tradeoffs. Consider whether a simpler approach could obtain most of the
     benefit.
   - Decide whether the idea makes sense for SafeRE by weighing the demonstrated benefit against
     those costs. Apply this proportionally: a small cleanup may be justified directly by clearer
     code, while a substantial increase in complexity requires correspondingly strong evidence.
     For an enabling stack layer, consider whether later layers actually consume its abstraction
     and whether cumulative stack evidence justifies the architectural direction.
   - Check that the evidence measures the central benefit. Throughput does not demonstrate lower
     allocation, reduced allocation does not demonstrate lower retained memory, and correctness
     tests do not demonstrate maintainability or performance. When the primary benefit is not
     measured, say so and ask what evidence would establish it.
   - For an optimization, resolve the exact claimed benchmark IDs, workload data, metric, runner,
     and comparable revisions before starting local repair or any benchmark run. A benchmark added
     by the PR is valid evidence when its declarations can be transplanted unchanged onto a
     controlled base as described below. If the named benchmark is absent from the PR tree and no
     exact reproducible command or artifact is provided, record the claim as unreproducible
     immediately. Do not spend long-run time on approximate substitutes; use a small
     standard-config negative control only when it can materially change the recommendation.
   - If a PR-added benchmark depends on PR-only production APIs or behavior and therefore cannot be
     transplanted unchanged and executed against the effective base, record the exact
     incompatibility and classify the claimed comparison as unreproducible. Do not troubleshoot it
     as an infrastructure blocker or alter the benchmark into a different workload.
   - Decide whether the implementation matches the stated goal.
   - Check design fit, JDK compatibility, linear-time risk, test adequacy, benchmark evidence, and
     scope creep.
   - After local correctness fixes, reassess the value proposition. If a necessary fix reduces or
     removes part of the claimed benefit, do not carry forward the original justification unchanged.
     In a stack, propagate the corrected assumptions upward and reassess every dependent layer whose
     design, eligible pattern set, or benchmark claim is affected.
   - Record a recommendation: ready after fixes, needs clarification, needs more tests, needs
     benchmark evidence, or needs redesign.

6. For a full review, start with one complete read-only defect pass using `$review-fix-loop`'s
   reviewer instructions against the recorded prepared review-base SHA, not automatically against
   `main`. For an upper stack layer, this is the prepared lower-layer head. For a limited semantic
   delta, use those reviewer standards on the changed code and affected invariants; preserve the
   still-valid earlier complete pass instead of repeating it.
   - Assess the complete finding set before editing. Run `$review-fix-loop` using the same prepared
     review-base SHA with two task-specific overrides: set per-fix verification to the focused tests
     or invariant checks relevant to the finding instead of a broad normal repository command, and
     stop after five semantic review/fix cycles even if another in-scope finding remains.
     Repo-assist performs proportionate broad verification after convergence.
   - If the five-cycle limit is exhausted, preserve all reproductions and return the remaining
     findings to the author instead.
   - The final state should be no remaining P2+ findings, a documented blocker/false positive, or
     complete author-facing findings when the five-cycle limit is exhausted.
   - If fixes are made, make a local-only commit in the review branch so fixes are durable and
     benchmarkable. Do not push.
   - Save a patch file under the PR artifact directory by diffing from the post-update/pre-fix
     marker to final `HEAD`. Do not diff from the original PR head, because that includes upstream
     main changes and any merge conflict resolutions.
   - When findings are returned to the author for any reason, run only focused reproductions needed
     to establish them. Record broad validation and benchmarks as skipped because the reviewed tree
     is known to require correction, then continue the sweep.

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked repo-assist \
  artifact-dir <number> <head-sha>
git diff <post-update-pre-fix-head>..HEAD > <artifact-dir>/review-fixes.patch
```

   For PRs that did not terminate with unresolved findings, stage validation from cheap to expensive,
   and do not start the expensive suite while review or code changes are still in progress:
   - During diagnosis, run only focused tests or invariant checks that prove each finding and fix.
   - Reach review convergence first: after the last semantic edit, obtain the required fresh
     no-findings pass or decide that remaining findings belong with the author.
   - After the last edit, run formatting and static-analysis preflights before any exhaustive or
     generated compatibility suite. Re-run the preflights after every later source edit.
   - Select proportionate broad verification from `AGENTS.md`, CI configuration, and any applicable
     specialized skill. Run exhaustive or generated compatibility suites only when the affected
     behavior or an applicable skill requires them. Run the selected broad command once on the
     final semantic tree. If a completed test phase is followed by a formatting-only or static-only
     failure, fix it and rerun the failed check and any phases that did not execute; do not repeat
     already completed exhaustive behavior tests when no semantic bytecode changed. Report the
     split verification accurately.
   - If a preflight or broad test exposes a problem that requires a semantic source edit, return to
     focused verification and a fresh review pass, counting the edit as another semantic review/fix
     cycle, then repeat the preflights and affected broad validation on the new final semantic tree.
     If five cycles were already consumed, preserve the failing reproduction and use the
     unresolved-findings outcome instead of editing. The formatting-only shortcut does not apply.

7. For optimization PRs only, reproduce benchmarks when first reviewed or when delta triage shows
   the relevant performance path or measurement changed. Preserve exact earlier measurements as
   historical evidence when their production path, effective base, workload, harness, and runner
   are unchanged; do not present them as newly measured results.
   - Skip benchmark execution when the PR ended with unresolved in-scope findings; focused
     reproduction is already sufficient for the decision.
   - Name the primary performance claim and its matching metric before selecting workloads. Use
     elapsed time for throughput or latency, allocation per operation for allocation claims, and a
     retained-object or heap measurement for footprint claims. Measure each material claimed axis;
     do not substitute a convenient metric for the one that motivates the PR.
   - For a standalone PR, baseline is the current declared base and experiment is the updated PR
     plus local review fixes.
   - For a stack bottom, baseline is the current trunk. For an upper layer, baseline is the prepared
     lower-layer review head and experiment adds the current layer plus local fixes. This measures
     the marginal effect claimed by that PR without attributing lower-layer changes to it.
   - If the PR explicitly claims cumulative stack performance against the trunk, run that as a
     separate labeled comparison; do not substitute it for the layer comparison.
   - If the current PR is an enabling layer whose actual role is representation, compilation, or
     compatibility migration and the performance benefit is intentionally realized later, do not
     invent a standalone throughput requirement. Verify the layer's stated structural or compile-
     work claim directly, and use a separate cumulative comparison only as evidence that the stack
     has a useful destination. State that the cumulative result does not measure the lower layer's
     marginal performance.
   - For targeted SafeRE nanosecond workloads whose benchmark definitions are identical at both
     revisions, prefer `safere-benchmarks/scripts/compare-branch.sh` with explicit immutable refs.
     Run String and UTF-8 variants separately, add `--vector` only when the experimental provider is
     part of the claim. Start with the standard configuration. Use `--long` only to confirm an
     exact claimed comparison whose standard result is close, surprising, or decision-critical;
     never use long mode merely to make an approximate substitute more persuasive.
     The comparison script invokes `./run-java-benchmarks.sh`; otherwise use that wrapper directly.
   - If the comparison script rejects changed workload data, harness code, runner settings, or build
     definitions, do not bypass its comparability check. Build a controlled baseline with current
     effective-base production code plus the PR's benchmark-only declarations, record its exact
     commit, and run paired wrapper commands in isolated clean worktrees. The effective base is the
     declared base for a standalone PR, the trunk for a stack bottom, or the prepared lower-layer
     head for an upper layer.
   - Never run benchmarks in parallel.
   - Prefer benchmark filters claimed in the PR description or comments. If unclear, choose the
     smallest relevant benchmark set and state the inference.
   - Compare the effective base directly with the final corrected tree first. Run a submitted-tree
     versus corrected-tree ablation only when the final result misses the claim and a local fix
     plausibly changed that exact path. Do not run a third redundant pair when the two comparisons
     already isolate the effect.
   - Save raw benchmark output and extracted summary tables under the PR artifact directory.
   - Report ratios as experiment time divided by baseline time, where values below `1.0` mean the
     PR is faster.
   - If the repository lacks a suitable measurement for the primary benefit, do not treat a
     secondary neutral result as successful reproduction. Record the missing evidence, propose a
     concrete way to measure it, and recommend focused human review when the unmeasured benefit is
     needed to justify material complexity or another tradeoff. Do not run a broad substitute
     matrix after establishing that the primary claim cannot be reproduced from repository state.
   - If reproduced results do not roughly match the PR's claimed performance outcome, diagnose the
     mismatch before writing the final recommendation:
     - First check whether `$review-fix-loop` made local correctness fixes that could plausibly
       affect the benchmarked code path. If yes, run one aggregate serial ablation comparing the
       post-update/pre-fix marker with the final corrected tree, using the same benchmark command.
       Split out an individual fix or small group only when the aggregate result materially changes
       a decision-critical claim but cannot identify which correction caused it. Save raw ablation
       logs and a short ablation summary under the PR artifact directory. Do not run ablations in
       parallel.
     - If correctness fixes do not explain the mismatch, write a concrete hypothesis for the
       discrepancy. Consider current-main baseline drift, PR revision drift, benchmark workload or
       data changes, stale PR description numbers, missing benchmark cases, command/JMH setting
       differences, and ordinary measurement variance. State which explanation is best supported
       by the evidence and which remains uncertain.
     - Do not treat a benchmark mismatch as fully understood until the report says whether local
       fixes likely affected the result and gives a hypothesis for any remaining discrepancy.

8. Write a final PR assessment and recommendation.
   - Recommend `can merge` only when the PR description is a reasonable thing to do for SafeRE, the
     implementation matches the stated intent, there are no major correctness/design/linear-time
     concerns, review-fix-loop found no unresolved P2+ findings, required verification passed, and
     evidence appropriate to the central claimed benefit supports the cost of the change. For an
     optimization PR, benchmark results must roughly match each performance outcome needed to
     justify the change; a neutral secondary metric does not satisfy an unmeasured primary claim.
     For an enabling stack layer, evidence may combine layer-appropriate structural verification
     with separately labeled end-to-end stack measurements; do not require an unclaimed standalone
     speedup or overlook whether the layer is safe to merge on its own.
   - Otherwise recommend focused human review and list the specific concerns: intent mismatch,
     design risk, correctness risk, compatibility risk, linear-time risk, missing or failing tests,
     benchmark mismatch, missing or inconclusive benefit evidence, complexity not justified by the
     measured benefit, unresolved review findings, merge conflict, or scope concern.
   - Keep this section decision-oriented. It should tell the human reviewer what to focus on.
   - Write the copy/paste review in the human reviewer's first-person voice, addressed to the PR
     author. This is the only report content the author will see, so include every material finding,
     fix, benchmark conclusion, tradeoff, request, and rationale needed to understand the review and
     its recommendation. Never shorten it on the assumption that the detailed report supplies
     context. When local fixes resolve the findings, assume the human will push those fixes to the
     PR branch before posting the review but that the author has not been told separately. State
     what was noticed, explain its impact, say "I've pushed a commit that fixes it" (or equivalent),
     explain the material fix, and end with "LGTM" when the fixed result satisfies the merge
     criteria. Do not ask the author to apply a local scout commit or refer to a machine-local
     branch/path in the copy/paste text. Keep unresolved concerns explicit and do not say "LGTM"
     when they remain.
   - Match the maintainer's established voice in the copy/paste review: direct, concise,
     conversational, candid, and collaborative. Write as a person discussing the change with its
     author, not as a report generator announcing findings. Prefer ordinary phrasing such as "The
     results are pretty mixed" or "I'm concerned about the added complexity here" when that is what
     the evidence supports. Avoid stiff transitions, abstract recommendation language, repeated
     qualifications, and prose that merely restates a table. Do not imitate typos or casualness that
     would make the technical point less clear.
   - Describe complexity in terms the author can act on, such as generated variants that must stay
     synchronized or an extra abstraction without a demonstrated benefit. Do not quote changed line
     counts merely to make a change sound large; include a size measurement only when it is material
     to the technical decision or the maintainer asks for it.
   - When the evidence does not yet justify a material tradeoff, make the missing decision explicit
     in the copy/paste review. Explain the cost and the unmeasured benefit, ask focused questions
     about evidence or simpler alternatives, and offer concrete measurements that would resolve the
     question. Do not convert uncertainty into approval merely because correctness checks pass.
   - Keep all local validation bookkeeping in the report, not the copy/paste review. Required CI is
     the merge gate, so never tell the author that local tests passed, give test counts, list local
     test or shell commands, or mention review-fix-loop/Codex/agent passes or an "automated review."
     It is useful to say that a pushed fix adds regression coverage, to report benchmark evidence,
     or to explain an underlying problem discovered by a local check; do not report the status of
     the local check itself.
   - Whenever the copy/paste review reports benchmark results, include those measurements in a
     Markdown table, even when there is only one result and even when the detailed report already
     contains a benchmark table. Never use prose as the only presentation of numeric benchmark
     results or ratios in the copy/paste review. Use columns that make the comparison
     self-contained, including the benchmark or workload identity, the relevant representations or
     configurations, the normalized ratio or baseline and experiment values, and a concise
     interpretation. State the ratio direction near the table (for example, lower is better for
     `PR/main` time), and keep any explanation of the cause, tradeoff, confidence intervals, or
     recommendation in prose around the table.
   - Format the copy/paste review as a paste-ready GitHub comment without hard-wrapped prose. Each
     prose paragraph must occupy one physical line, regardless of its rendered length. Use physical
     line breaks only where Markdown structure requires them, such as between paragraphs and for
     the header, separator, and rows of a benchmark table. Do not wrap a sentence or table cell
     across source lines, and avoid lists in the copy/paste review when ordinary prose is clear.
   - Use precise, concrete language in author-facing text. Standard technical terminology is useful
     and encouraged when it accurately names the concept, such as SIMD, KMP, integer overflow,
     register pressure, or linear time. Do not replace precise terms with vague labels that merely
     sound technical. For example, do not call unrelated worst-case complexity and integer-overflow
     bugs "boundary problems"; name each problem directly. Prefer "add tests covering these cases"
     to "add systematic coverage," and describe the measurements wanted instead of asking for a
     "threshold sweep." Avoid scout vocabulary, abstract process labels, invented umbrella terms,
     and compressed wording that the author would need to decode. Define genuinely unfamiliar or
     project-specific terms on first use. Before finalizing, rewrite any phrase that does not convey
     a recognized technical concept or whose practical meaning is unclear.
   - Keep review feedback respectful and collaborative. Describe the observed code behavior and its
     impact without assigning blame. Ask genuine questions when the author may have context or when
     more than one fix is reasonable; prefer phrasing such as "Could we...?", "It looks like...",
     and "What do you think?" over commands or prosecutorial conclusions. Do not manufacture doubt
     about a verified bug: state the fact calmly, explain why it matters, and invite the author to
     choose or discuss the remedy. Reread line comments specifically for accusatory tone.
   - For every suggested line comment, include the file path, current PR-head line number, and exact
     source line the comment should attach to. Verify the quoted line and number against the PR head
     before finalizing the report so the human can place the comment without guessing.
   - Base the prose on the public PR discussion, not scout chronology. Avoid phrases such as
     "yesterday's run", "the previous scout", "still", "remains", "new commits", "retained fix",
     or "refreshed against main" unless the public discussion makes that history meaningful to the
     author. When the author has not been told about a finding, introduce it directly: "I noticed
     that ... I've pushed a commit that fixes it."
9. Update the durable report and state after each PR, not only at the end. Update that PR's row in
   the report's PR Summary table at the same checkpoint while preserving its reviewer-owned `Done`
   value. If the sweep is interrupted, completed PRs should still be discoverable.

10. After writing the complete report, perform a final author-copy audit before marking the report
    completed or updating `LATEST.md`. Extract every fenced `Copy/Paste PR Review` and read each one
    without its surrounding report section. Rewrite any review that fails any of these checks:
    - It must contain every author-relevant finding, impact, fix, benchmark conclusion, tradeoff,
      request, rationale, and recommendation needed to act without reading the report.
    - It must exclude local validation bookkeeping, commands, test counts, worktree or artifact
      paths, local-only commit language, and references to agents or automated review passes.
    - When a resolved fix will be pushed before the comment is posted, it must say that a fixing
      commit was pushed, explain the material change, and end with `LGTM` when merge criteria are
      satisfied. It must not ask the author to apply a scout-local commit.
    - Every numeric benchmark claim must appear in a self-contained Markdown table with the ratio
      direction or an unambiguous speedup column; prose alone is insufficient.
    - The voice, chronology, terminology, line wrapping, and tone must satisfy the author-facing
      rules above, and the recommendation must match the detailed assessment.
    After any rewrite, read the fenced review alone again. If its conclusion changes, update the
    detailed assessment and summary row before completing the report.

Use the [reusable review scripts](reusable-tools.md) for these repeated mechanics: checkpoint each
completed PR with preserved `Done` state, refresh sanitized head/discussion data, gather merge-tree
evidence, and extract author copies for the final semantic audit. The benchmark helper supports
exact paired wrapper runs and native-unit evidence extraction when compare-branch.sh is unsuitable.

## Contributor Report Format

After the repository activity overview, include a compact decision-oriented summary of every open trusted non-draft contributor
PR. Keep each assessment to one brief sentence or phrase. Make the PR text in each row an
internal link to that PR's detailed section. Use an explicit `pr-<number>` HTML anchor immediately
before every detailed PR heading so the link remains stable regardless of punctuation or Unicode
in the PR title. Include reviewed, blocked, and deferred PRs; do not include untrusted PRs because
they were not inspected and therefore have no assessment. Make `Done` the first column. Leave it
empty when creating a row so the human reviewer can enter `Y` after handling the PR. The column is
reviewer-owned: never fill it in or infer completion, and preserve any existing value when updating
a row in the same report.

```markdown
## PR Summary

| Done | PR | Brief Assessment | Recommendation |
|---|---|---|---|
|  | [PR #123: Optimize matching](#pr-123) | Correct after local fixes; claimed gains reproduced. | Can merge |
|  | [PR #124: Revise parser API](#pr-124) | The public API shape needs a maintainer decision. | Focus human review |
```

Update the summary row whenever its detailed PR section changes. The summary is an index and a
quick decision aid, not a substitute for the evidence in the detailed section.

Immediately after the PR Summary, include a `Merge Ordering` section covering only eligible
contributor PRs that remain open and non-draft when the report is finalized. State whether any hard dependencies exist, give a
recommended sequence or independent groups when useful, and explain the specific semantic or
conflict rationale. Also identify branches that already need current main merged independently of
the recommended inter-PR order.

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
<a id="pr-<number>"></a>
## PR #<number>: <title>

URL: <url>
Classification: optimization | other
Classification evidence: <short reason>
Trunk: origin/<trunk> @ <sha>
Declared base: origin/<baseRefName> @ <sha>
Prepared review base: <sha>
Stack: none | #<stack-number>, position <position> of <size>
PR head: <sha>
Base update: yes | blocked | already up to date
Post-update/pre-fix head: <sha or none>
Experiment branch: codex/review/pr-<number>/<short-sha>
Artifacts: <path>
Human review cutoff: <timestamp and comment/review summary, or "none; first-review perspective">

### PR Intent Review

Claimed goal:
- ...

Stack context:
- Stack objective: ...
- This layer's role: ...
- Downstack contracts consumed: ...
- Upstack consumers enabled: ...
- End-to-end evidence: ...

Central benefit and evidence:
- Intended observable benefit: ...
- Evidence that directly measures it: ...
- Material costs or tradeoffs: ...
- Simpler alternatives considered: ...
- Effect of local fixes on the benefit: unchanged | narrowed | removed | not applicable

Assessment:
- Makes sense for SafeRE: yes | partial | no
- Performs its stack role: yes | partial | no | not applicable
- Benefit justifies complexity: yes | partial | no | evidence needed
- Implementation matches stated goal: yes | partial | no
- Linear-time/design concerns: ...
- Compatibility concerns: ...
- Test evidence: ...
- Scope concerns: ...

Recommendation:
- ...

### Review Fix Loop

**REVIEW-FIX LOOP INCOMPLETE:** <why it stopped and any remaining findings, only when applicable>

Result: no P2+ findings | fixes committed locally | findings for author | blocked | false positive documented

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

| Benchmark | baseline | PR+fixes | PR/baseline | Interpretation |
|---|---:|---:|---:|---|
| ... | ... | ... | ... | ... |

Summary:
- Reproduced: yes | partial | no | inconclusive
- Notes: ...

Mismatch diagnostics:
- Correctness-fix ablation: not needed | run | blocked
- Result: <whether local fixes explain the benchmark mismatch>
- Hypothesis if not explained by fixes: <best-supported explanation or "unknown">
- Ablation artifacts: <path or none>

### Assessment And Recommendation

Recommendation: can merge | focus human review | blocked

Assessment:
- PR intent is reasonable: yes | partial | no
- Implementation matches intent: yes | partial | no
- Major correctness/design concerns: none | <concerns>
- Review-fix-loop status: clean | fixes committed locally | blocked | unresolved findings
- Verification status: passed | failed | incomplete
- Benchmark status: matches claim | roughly matches claim | does not match claim | inconclusive | not applicable
- Benefit/cost status: justified | partially justified | evidence needed | not justified

Human review focus:
- <specific issues to inspect, or "No major concerns found.">

### Copy/Paste PR Review

```markdown
<the complete first-person review addressed to the author; this fenced content is the only part of the report the author will see. Include every material finding, pushed fix, benchmark conclusion, tradeoff, rationale, requested action, and recommendation needed to understand the review without any other report section. For resolved scout fixes, explain each distinct problem and impact, say the reviewer pushed a fixing commit, explain the material fix, and conclude LGTM only when appropriate. Keep only reviewer-internal validation status, commands, test counts, shell checks, worktree paths, artifacts, and automated-review status outside this comment. Use plain, concrete language and state requests in terms of the code, behavior, tests, or measurements wanted. Keep the tone respectful and collaborative: explain impact without blame and use genuine questions where design judgment is involved. Make the text self-contained from the public discussion; never rely on the author knowing about earlier scout runs or unposted local work. Whenever benchmark measurements are included, present every numeric result and ratio in this review's own Markdown table rather than only in prose, define the normalization direction, and give each row a concise interpretation. Do not hard-wrap prose: put each prose paragraph on one physical line and use additional line breaks only for required Markdown structure, especially the benchmark table. Before finalizing, read only this fenced content and rewrite it if any conclusion, evidence, rationale, request, or recommendation depends on another report section.>
```
````

## Contributor Review State

Maintain contributor assessments in `state.json.prs["<number>"]`, separately from issue and
authored-feedback state. A record has this shape:

```json
{
  "lastHeadSha": "abc123",
  "fingerprint": "sha256-of-sanitized-discussion-state",
  "lastBaseSha": "789abc",
  "lastTrunkSha": "012def",
  "lastSeenUpdatedAt": "2026-07-04T17:42:00Z",
  "lastReviewedAt": "2026-07-04T18:00:00Z",
  "classification": "optimization",
  "lastReport": "/home/eaftan/.codex/safere-pr-review/reports/2026-07-04T170000Z.md",
  "lastFixBranch": "codex/review/pr-123/abc1234",
  "lastFixCommit": "def456",
  "status": "reviewed"
}
```

Seeded contributor state may use these statuses:

- `needs_review`: always review on the next sweep, then update to `reviewed` after a successful
  review.
- `reviewed`: skip only while PR head SHA, PR `updatedAt`, `lastBaseSha`, and `lastTrunkSha` still
  match current GitHub state.
- `defer`: skip regardless of SHA changes until a human changes the status; include `deferReason`
  in sweep reports.
- `unknown`: treat like `needs_review`.

## Discipline

- Converge review and run cheap preflights before expensive tests; run broad validation once per
  final semantic tree.
- Verify that an exact benchmark exists in the PR or can be transplanted unchanged before launching
  JMH, and do not use long substitute runs for missing claims.
- Do not use benchmark evidence from a dirty or ambiguous checkout.
- Do not average unrelated benchmark ratios unless the report explicitly states the included
  benchmark set and uses geometric mean.
- If a new unrelated SafeRE bug is found during review, follow the repository rule to file a
  GitHub issue immediately.
