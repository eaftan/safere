# SafeRE optimization guidance

Resolve project paths from the repository/worktree
root; read the current files rather than relying on historical engine thresholds or runner settings.

## Required project context

Read `AGENTS.md` and `safere-benchmarks/PERFORMANCE_GUIDE.md`. Consult `docs/ARCHITECTURE.md` and
`docs/INVARIANTS.md` for affected engine, capture, parser or matcher contracts. For benchmark
mechanics, use `safere-benchmarks/README.md`, `CROSS_ENGINE_EXECUTION.md` and
`SPECIALIZED_MEASUREMENT_MODES.md` as relevant. Use `docs/TESTING.md` to choose focused checks.

Preserve linear input-time behavior for a fixed pattern and stack safety. An accelerator must not
introduce repeated overlapping rescans, matcher replay at candidate positions, or unbounded
backtracking. Account for the total work across candidate retries and fallback transitions, not
only one helper call. Preserve JDK-compatible semantics within the supported dialect. If an
optimization exposes a SafeRE/JDK correctness bug, use the repository's `divergence-bug-fix` skill.

## Controlled measurements

- Profile the workload before changing production code. Use available async-profiler or JFR through
  the documented diagnostic workflow. Cold compilation needs cold profiling; steady-state samples
  cannot identify all initialization costs.
- Use `./run-java-benchmarks.sh` for Java timing, never `mvn exec:java`. Use standard measurement
  settings first and follow project confirmation guidance unless the user has narrowed it.
- Use `safere-benchmarks/scripts/compare-branch.sh` for supported committed-revision comparisons.
  Pass explicit baseline/current refs and one exact SafeRE execution variant per invocation. It
  requires identical workload/harness/build definitions and a clean checkout. If those differ or
  the optimization is uncommitted, use controlled baseline/candidate worktrees with the same
  benchmark-only declarations and project wrappers, recording exact patches. Run sequentially.
- Keep String and pre-existing UTF-8 measurements explicit. Respect the requested scan provider;
  do not enable experimental providers as an incidental part of collecting controls. SafeRE's
  Pattern compiler is shared by String and UTF-8; do not duplicate compile measurements by encoding.
- Keep new workloads in `benchmark-data.json`, using generic operations and materialization. Use
  the corresponding investigation corpus for one-off cases. Do not hardcode test patterns or
  generated input recipes in adapters, alter result consumption, or convert engine syntax implicitly.
- Rebar is a reporting holdout: do not tune against it or copy its inputs into the development
  corpus. Do not launch a full corpus collection for a focused optimization unless required by
  the change or requested. Reserve corpus-wide archives/reports for that separate workflow.

For engine/accelerator changes, consider String/UTF-8, Boolean search/capture extraction,
`find`/`matches`/replacement callers, boundaries, failed searches, sparse/dense candidates, ASCII
and multibyte/supplementary inputs, and engine fallback thresholds. Choose controls tied to the
actual changed helper and quantify setup, compile, allocation or retained-state costs when the
approach shifts work into those phases. Run matching builds/tests sequentially per checkout.

Keep focused raw measurements and profiles local. A requested performance PR needs measured
before/after target and control results and material tradeoffs in its description; routine test
command reporting belongs in chat under project conventions. Use the installed review-fix-loop
skill as the parent workflow requires.
