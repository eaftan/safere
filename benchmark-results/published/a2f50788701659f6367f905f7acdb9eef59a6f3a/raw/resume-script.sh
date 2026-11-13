#!/bin/bash
set -euo pipefail
cd /home/eaftan/safere-benchmarks-20261111
out="$PWD/benchmark-results/optimization-20261111"
git diff --binary > "$out/resume-worktree.patch"
SAFERE_BENCHMARK_COMPLETED="$out/cpp-completed.txt" ./run-cpp-benchmarks.sh --engine re2 2>&1 | tee "$out/cpp-resume-raw.txt"
{ grep '^{' "$out/cpp-raw.txt"; grep '^{' "$out/cpp-resume-raw.txt"; } > "$out/cpp-results.jsonl"
SAFERE_BENCHMARK_TIMING_ONLY=true ./run-rust-benchmarks.sh 2>&1 | tee "$out/rust-raw.txt"
grep '^{' "$out/rust-raw.txt" > "$out/rust-results.jsonl"
python3 safere-benchmarks/scripts/compare-benchmarks.py --jmh "$out/jmh-output.txt" --json "$out/cpp-results.jsonl" "$out/rust-results.jsonl" --engines safere,safere_utf8,re2_cpp,rust --declared-plan "$out/declared-report-plan.json" --output-jsonl "$out/normalized-results.jsonl" > "$out/merged-tables.md"
