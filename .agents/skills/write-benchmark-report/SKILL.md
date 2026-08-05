---
name: write-benchmark-report
description: "Collect, analyze, write, validate, and publish reproducible SafeRE benchmark reports and BENCHMARKS.md updates. Use when asked to launch a benchmark collection, review benchmark results, choose and support headline comparisons, explain benchmark categories or surprising ratios, archive raw benchmark evidence, or prepare a benchmark-report change for review."
---

# Write Benchmark Report

Produce a self-contained, evidence-backed SafeRE benchmark report whose claims can be recalculated
from checked-in raw data. Treat benchmark execution, interpretation, writing, and publication as one
workflow.

## Establish Scope

1. Read the repository `AGENTS.md` Benchmarking section, the current `BENCHMARKS.md`, the benchmark
   collection documentation in `README.md`, and the collection scripts before acting.
2. Inspect the current workload declarations and report plan. Do not preserve historical categories,
   methods, or competitors when the suite has changed.
3. Resolve what "full" means before running:
   - Default project collection: Java engines plus the external OpenJDK-derived suite.
   - Add C++ RE2, PCRE2 JIT, Go, Rust, and .NET only when cross-language context is requested.
   - Skip the external suite only when the user explicitly narrows scope. Call the resulting
     collection incomplete and remove stale external results from the new report.
4. Inspect prerequisites without installing anything. Ask the project owner to install missing
   compilers, SDKs, runtimes, external checkouts, or libraries.
5. Record the exact SafeRE commit before the run. Prefer a clean tree. If the tree is dirty, stop
   unless the intended benchmark source is unambiguous and can be recorded honestly.

Tell the user the planned suites, engines, commands, expected exclusions, and launch/hand-off approach
before starting when they ask for a plan first.

## Collect Results

Use the project wrappers as the only source of benchmark settings. Never invoke JMH through Maven
execution and never run benchmark processes in parallel.

Use one of:

```bash
./collect-benchmark-results.sh
./collect-benchmark-results.sh --long
./collect-benchmark-results.sh --cross-language
./collect-benchmark-results.sh --cross-language --skip-openjdk-regex
```

Use standard mode for routine publication evidence. Use `--long` to confirm close, surprising, or
important comparisons; do not silently mix standard and long point estimates in one aggregate.

Launch the collection in a durable background execution or session with output captured. Watch only
long enough to establish that prerequisites and builds succeeded and the script reached the first
actual benchmark run. Then stop polling to conserve tokens unless the user explicitly asks for
continuous oversight. Tell the user that the collection is still running and provide its output
directory plus the session, process, or log identifier needed to inspect it later. Never describe a
successfully started collection as completed.

Before analyzing results—whether later in the same conversation or after the user returns—check the
process exit status and verify that the output directory contains the expected artifacts for every
selected suite. Preserve the exact command and whether standard, long, smoke, cross-language, and
external-suite modes were used. If the run failed, preserve its output, diagnose the failure, and
continue only when doing so does not compromise comparability.

## Normalize And Audit

Prefer `normalized-results.jsonl` for calculations and retain the original harness output as the
authority. When the collector does not emit normalized JSONL, generate it with
`safere-benchmarks/scripts/compare-benchmarks.py` from the captured JMH and engine JSONL files.

Before writing conclusions:

1. Validate JSON and JSONL syntax.
2. Verify that normalized `(engine, benchmark)` identities are unique.
3. Compare the declared report plan with actual results. Distinguish:
   - complete comparable membership;
   - declared but missing measurements;
   - explicit capability or syntax exclusions.
4. Inventory operations, parameters, sizes, match outcomes, representations, and engines for every
   proposed aggregate.
5. Inspect confidence intervals and rerun important noisy or close cases with `--long` when needed.
6. Trace surprising results to individual rows before speculating about causes. Inspect workload
   definitions and implementation paths; profile before proposing an optimization.
7. File a GitHub issue for a newly discovered bug or material performance-path gap. Include exact
   rows, scaling, likely path selection, and correctness constraints. Do not let the report change
   silently conceal it.

## Compute Comparisons

For every aggregate, define membership, parameter coverage, and weighting in the report.

Use the geometric mean of per-row ratios:

```text
ratio = SafeRE time / competitor time
```

- A ratio below `1.0` means SafeRE is faster.
- A ratio above `1.0` means SafeRE takes longer.
- Give every declared row equal weight unless the report explicitly defines another defensible
  weighting.
- Calculate each competitor on its actual pairwise shared membership when coverage differs.
- Keep controlled same-runtime comparisons separate from cross-runtime context.
- Keep semantically different operations such as matching, compilation, replacement, memory, cold
  start, and adversarial scaling separate unless the combined question is explicitly meaningful.

State comparisons directly. Prefer "SafeRE is 13% slower" or "SafeRE takes 2.03× as long" over
ambiguous phrases such as "1.13× slower." Include the raw ratio and row count in aggregate tables.

Run sensitivity analyses when a few extreme rows may drive a headline. Report both the complete
aggregate and a clearly labeled diagnostic exclusion; never replace the complete result with a
more favorable subset. Break representation comparisons down by pattern, match outcome, and size
before attributing them to encoding or implementation choices.

## Choose The Headline

Choose the broadest representative controlled category, not automatically the category with the
largest SafeRE advantage. For the current suite this will often be the Real-world matrix because it
covers many patterns, successful and failed searches, and multiple input sizes. Re-evaluate this
choice whenever workload structure changes.

Use smaller focused categories as supporting checks. Explain where they agree with or qualify the
headline. A headline such as a broad SafeRE/JDK lead must not imply that SafeRE wins every pattern
when focused categories or individual rows show otherwise.

Describe every major report category in a compact table with:

- composition and row count;
- operation and parameter coverage;
- weighting;
- the question the category answers.

## Write `BENCHMARKS.md`

Lead with outcomes. Use this order when it fits the evidence:

1. Executive summary led by the primary category and its sensitivity result.
2. Environment and reproducibility.
3. Benchmark-category definitions.
4. Same-runtime aggregates, then explicitly contextual cross-runtime aggregates.
5. Primary-category analysis and inspectable supporting tables.
6. Smaller supporting categories.
7. Representation-specific results such as String versus pre-existing UTF-8.
8. Compilation, replacement, captures, scaling, adversarial behavior, memory, and SafeRE-only
   functionality.
9. Interpretation of tradeoffs and limitations.

Include:

- the full benchmarked SafeRE SHA and that commit's UTC date/time using `Z` notation;
- SafeRE, JDK, JMH, runtime, compiler, engine/library, OS, CPU, and memory versions as applicable;
- the exact collection command and benchmark mode;
- timing configuration, forks/processes, confidence-interval convention, input representation, and
  conversion boundaries;
- every material exclusion and actual row count;
- a prominent link to published raw and normalized artifacts.

Do not add a separate results timestamp unless explicitly requested. Do not include test counts,
personal names, stale external-suite results, or claims supported only by ignored local files.

Use neutral language and explain likely architectural reasons only when evidence supports them.
Acknowledge SafeRE costs such as compilation time, memory, or slower short paths alongside its
linear-time and fast-path benefits. Avoid universal rankings.

## Publish Evidence

Keep ordinary timestamped runs under ignored `benchmark-results/`. Publish the reviewed collection
under:

```text
benchmark-results/published/<full-SafeRE-commit>/
```

Preserve the complete result directory, including raw JMH/native output, engine JSONL, resolved
plan, memory output, normalized JSONL, and generated tables. Do not clean whitespace or otherwise
rewrite raw output.

Add a publication `README.md` that records:

- benchmarked commit and UTC commit time;
- exact command and mode;
- included and skipped suites;
- artifact roles and normalized schema;
- a statement that raw output is authoritative.

Add `SHA256SUMS` for every other file in the publication directory. Configure `.gitignore` so local
runs remain ignored while `benchmark-results/published/**` is trackable. Mark bulky raw and
generated artifacts non-diffable in `.gitattributes`; keep the publication README and checksums
reviewable.

## Validate The Report And Publication

Perform all applicable checks:

```bash
python3 -m unittest discover -s safere-benchmarks/scripts -p 'test_*.py'
bash -n collect-benchmark-results.sh
git diff --check
```

Also:

- parse every JSON/JSONL artifact;
- regenerate normalized JSONL from raw inputs and require byte-identical output;
- verify normalized identity uniqueness;
- run `sha256sum --check SHA256SUMS` from the publication directory;
- check Markdown tables for consistent column counts;
- recalculate every reported aggregate and inspectable pattern-level ratio from the published data;
- verify that local timestamped results remain ignored and published results are trackable;
- verify that only intended source, documentation, and publication files changed.

Do not rerun the full benchmark collection merely because report prose or normalization tooling
changed. State that derived artifacts were regenerated from preserved raw output. Run matching
implementation tests only when matching code changed.

## Hand Off

Summarize:

- the primary headline and its most important qualification;
- collection scope and explicitly skipped suites;
- artifact location;
- validations run and not run;
- discovered issues filed;
- changed files.

Do not commit, push, open a PR, or post benchmark claims externally unless the user explicitly asks.
