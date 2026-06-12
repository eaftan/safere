---
name: review-fix-loop
description: Run Codex's noninteractive code review on a Git working tree, fix review findings at or above a requested priority such as P2, run local verification, and repeat until no in-scope findings remain. Use when the user asks to run /review, codex exec review, or a review/fix loop over uncommitted changes, branch changes, or a specific commit.
---

# Review Fix Loop

## Overview

Use this workflow to run the Codex reviewer from the CLI, address only the requested severity threshold, and iterate until the reviewer reports no remaining in-scope findings.

## Workflow

1. Confirm the review scope and threshold from the user's request.
   - Default scope: uncommitted changes.
   - Default threshold: P2 or higher when the user says "priority >= P2" or similar.
   - Treat P0, P1, and P2 as in scope for a P2 threshold.
   - Leave P3, nits, style-only suggestions, and optional improvements alone unless the user asks for them.

2. Fix any already-provided review findings before running another review.
   - Preserve unrelated user changes.
   - Do not commit, push, reset, or revert unless the user explicitly asks.
   - Keep staged/unstaged intent stable unless staging is part of the requested workflow.

3. Run the repository's normal verification commands after each fix.
   - Prefer commands documented in `AGENTS.md`, README files, CI configs, or project tooling.
   - If the repo has no documented command, run the narrowest relevant build/test command you can infer.
   - If verification cannot be run, record exactly why before continuing.

4. Run the noninteractive reviewer.
   - Expect review runs to take about 5-15 minutes. If the command returns a
     running session, wait in longer intervals rather than polling or tailing
     logs frequently; inspect the JSONL log only when the run appears stuck or
     has exceeded the expected window.
   - For uncommitted changes, use:

```bash
tmpdir="$(mktemp -d)"
codex exec review --uncommitted --json --output-last-message "$tmpdir/review.md" --ephemeral > "$tmpdir/review.jsonl"
sed -n '1,240p' "$tmpdir/review.md"
```

   - For branch review, add `--base <branch>` instead of `--uncommitted` when the user asks for a base branch review.
   - For commit review, use `--commit <sha>` when the user asks for a specific commit.
   - Prefer `codex exec review` over `codex review` when you need `--json` or `--output-last-message`.

5. Parse the final review message.
   - If there are no findings, stop.
   - If findings remain but all are below the requested threshold, stop and report that no in-scope findings remain.
   - If there are in-scope findings, fix them, verify, and run the reviewer again.

6. Avoid unbounded loops.
   - If the same in-scope finding survives two fix attempts, inspect the diff and tests carefully.
   - If it is genuinely blocked by missing information or an external issue, stop and explain the blocker.
   - If it is a false positive, document why and continue only if the user requested false-positive suppression or documentation.

## Review Output Handling

Use temporary files for reviewer JSONL and final messages unless the user asks to keep artifacts. The useful artifact for humans is usually the `--output-last-message` file; the JSONL stream is mainly for debugging failed reviewer runs.

When reporting back, include:

- The in-scope findings fixed.
- Verification commands run and their results.
- Whether a final reviewer pass found no in-scope findings.
- Any remaining out-of-scope findings, only if they are material to the user.
