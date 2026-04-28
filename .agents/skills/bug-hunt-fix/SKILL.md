---
name: bug-hunt-fix
description: Systematic code bug hunting with regression-test proof and fixes. Use when Codex is asked to review a codebase or module for potential bugs, write tests that demonstrate each bug, verify the tests fail before fixing, implement principled fixes, and rerun focused and appropriate broader tests.
---

# Bug Hunt Fix

## Overview

Use this workflow to turn exploratory bug review into evidence-backed fixes. The central rule is:
do not fix a suspected bug until a focused regression test fails for the right reason.

## Workflow

1. Establish the work surface.
   - Check branch, worktree status, and any open PR or in-progress work.
   - Preserve unrelated user changes. Do not reset or revert files you did not intentionally edit.
   - Read project instructions and local test conventions before adding tests.

2. Review systematically.
   - Walk files by subsystem rather than randomly: public API/state, parser/validation, compiler,
     engines, utilities, then performance shortcuts.
   - Prefer API and state-machine invariants first; they often reveal testable mismatches.
   - Compare behavior against authoritative references when relevant, such as the JDK for
     Java-compatible regex APIs.

3. Form candidate bugs as falsifiable claims.
   - State the expected behavior, the observed code path, and why the current implementation may
     violate the contract.
   - Reject candidates that are intentional documented divergences or unsupported features.
   - For temporally stable platform behavior, small local reference programs are useful.

4. Write regression tests before implementation.
   - Add narrow tests that fail only if the suspected bug exists.
   - Name tests for behavior, not issue numbers.
   - Run a focused test command and capture the failure message.
   - If the test passes, the candidate is not verified; remove or rethink it before moving on.

5. Fix the root cause.
   - Make the smallest principled production change that repairs the invariant.
   - Prefer sharing existing helpers and semantics over adding special cases.
   - Keep changes scoped to the verified bugs unless another bug blocks the fix.

6. Verify after the fix.
   - Rerun the focused failing tests and ensure they now pass.
   - Run broader tests based on blast radius. For public API or shared engine changes, run the
     relevant module or full suite when feasible.
   - Report any test that could not be run.

7. Summarize with evidence.
   - List each verified bug, the regression test that proved it, and the fix.
   - Include exact test commands and pass/fail outcomes.
   - Mention remaining risk or deliberately skipped broader validation.

## Bug-Finding Heuristics

- Look for methods that claim compatibility with a platform API; compare edge cases with that
  platform.
- Inspect reset, region, cursor, snapshot, and mutation state transitions.
- Audit fast paths against the slow path contract: literals, prefixes, shortcuts, caches, and
  deferred work.
- Check null handling and exception types where the API is intended to be a drop-in replacement.
- Check snapshot/copy APIs for missing metadata, stale state, or accidental aliasing.
- Check bounded searches and regions for assertions that should see different boundaries than
  consumed text.

## Test Discipline

- Keep the fail-first and pass-after-fix runs separate in the narrative.
- Do not leave passing exploratory tests for disproven candidates unless they add meaningful
  coverage and fit the requested scope.
- When running long suites, keep the process alive until it exits or the user redirects the task.
- If the user requests a target count of bugs, keep hunting until that count is reached or explain
  why further candidates were not verified.
