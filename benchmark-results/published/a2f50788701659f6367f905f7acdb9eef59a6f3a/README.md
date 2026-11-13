# Optimization investigation evidence

[Read the engineering report](REPORT.md). This standalone investigation is separate from release benchmark reporting.

The SafeRE library revision is `a2f50788701659f6367f905f7acdb9eef59a6f3a`, with commit time `2026-11-12T04:25:33Z`. The library code remained at that revision. Benchmark-harness changes were uncommitted during the run: the initial, resumed, and final patches are preserved in `raw/`. The final harness snapshot is in `harness-final.tar.gz`; it includes the collection script, declarations, normalizer, native sources and lockfile. PR #1183 extracts the reusable failure fixes; its commit is not the measured library revision.

## Scope and commands

Standard timing, selected development corpus, sequential SafeRE String/default UTF-8, native RE2, and Rust regex. No smoke measurements or profiling timings enter the normalized comparisons. The experimental Vector provider, memory collection, other engines, Rebar and the external OpenJDK-derived suite were excluded. This is not a full release collection.

The initial command was:

```bash
./collect-optimization-benchmarks.sh benchmark-results/optimization-20261111
```

`raw/collector-script.sh` records that script's initial contents. `raw/command.txt` records its resolved output directory. The initial collection stopped in RE2; `raw/resume-script.sh` preserves the first resume command. Rust subsequently stopped on an incompatible expected count. Its second resume used:

```bash
SAFERE_BENCHMARK_COMPLETED="$out/rust-completed.txt" \
SAFERE_BENCHMARK_TIMING_ONLY=true ./run-rust-benchmarks.sh
```

Here `out` is the original output directory; the successful command's captured output is `raw/rust-resume-raw.txt`. The completed list includes the explicitly incompatible `RepeatedClassFindBenchmark.lines.1024` row, alongside already measured rows. Four Java tokenizer trials were rerun with the standard Java wrapper after the omitted-mode correction; their exact trial IDs and measurement settings are in `raw/java-retry.txt`.

The exit statuses apply to individual segments: `raw/initial-exit-status` records the RE2 failure, `raw/rust-initial-exit-status` records the Rust failure, and `raw/exit-status` records the successful final Rust continuation. None is a blanket assertion that an uninterrupted collection succeeded.

## Files and authority

Raw harness output is authoritative. Preserve it without whitespace cleanup or rewriting.

- `raw/jmh-output.txt` and `raw/java-retry.txt`: original Java output and successful retries. `raw/jmh-combined.txt` is their concatenation.
- `raw/cpp-raw.txt`, `raw/cpp-resume-raw.txt`: original and resumed RE2 output. `raw/cpp-results.jsonl` extracts JSON records from both.
- `raw/rust-raw.txt`, `raw/rust-resume-raw.txt`: original and resumed Rust output. `raw/rust-results.jsonl` extracts JSON records from both.
- `raw/collection.log`, `raw/resume.log`: orchestration output, including failures.
- `raw/normalized-results.jsonl`: one record per unique `(engine, benchmark)` with `score`, `error`, and `unit`. Units remain explicit. `error` is the harness-reported confidence interval; zero may reflect rounding.
- `raw/declared-report-plan.json`: selected Java trials and declared exclusions. `resolved-manifest.json` records all expanded engine/workload decisions, including the original Rust row that failed validation.
- `materialized-corpus.tar.gz`: the resolved manifest and exact materialized inputs, with the manifest's hashes. `benchmark-data.json` preserves the source declarations.
- `aggregates.json`: pairwise geometric means, row counts, and exact workload membership. `opportunities.json`: representative candidate rows, patterns and times converted to nanoseconds.
- `raw/profile-*.jfr` and matching `.log` files: diagnostic recordings and JMH command information. They are not comparison measurements.
- `raw/INVESTIGATION.md`: initial working notes. The checked report supersedes their incorrect suggestion that first-compile timers include process launch.
- `SHA256SUMS`: checksums for every other publication file.

## Recalculate and verify

From the repository root:

```bash
publication=benchmark-results/published/a2f50788701659f6367f905f7acdb9eef59a6f3a
python3 safere-benchmarks/scripts/compare-benchmarks.py \
  --jmh "$publication/raw/jmh-combined.txt" \
  --json "$publication/raw/cpp-results.jsonl" "$publication/raw/rust-results.jsonl" \
  --engines safere,safere_utf8,re2_cpp,rust \
  --declared-plan "$publication/raw/declared-report-plan.json" \
  --output-jsonl /tmp/optimization-normalized.jsonl > /tmp/optimization-tables.md
cmp /tmp/optimization-normalized.jsonl "$publication/raw/normalized-results.jsonl"
python3 "$publication/analyze.py"
(cd "$publication" && sha256sum --check SHA256SUMS)
```

The captured source revision's normalizer is also in `harness-final.tar.gz`. Reproduction of a new timing run requires the documented toolchains and the matching library revision. Absolute paths in original logs describe the original machine; they are not required destinations for a new run. The run was segmented, lacked continuous environmental monitoring, and did not record memory capacity or the native compiler version. Comparisons are intended to prioritize large optimization opportunities.
