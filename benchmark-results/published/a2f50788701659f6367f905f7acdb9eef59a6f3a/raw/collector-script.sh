#!/bin/bash
# Collect the optimization investigation's selected engines sequentially.
set -euo pipefail
cd "$(dirname "$0")"
output="${1:-benchmark-results/optimization-$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p "$output"
output="$(cd "$output" && pwd)"
git rev-parse HEAD > "$output/safere-commit.txt"
git diff --binary > "$output/worktree.patch"
cp "$0" "$output/collector-script.sh"
printf '%s\n' "./collect-optimization-benchmarks.sh $output" > "$output/command.txt"
{ java -version; mvn -version; rustc --version; cargo --version; cmake --version; uname -a; lscpu; } > "$output/environment.txt" 2>&1
export SAFERE_BENCHMARK_VARIANTS=safere-string,safere-utf8
./run-java-benchmarks.sh --provider default --declared 2>&1 | tee "$output/jmh-output.txt"
java -Dsafere.benchmark.variants="$SAFERE_BENCHMARK_VARIANTS" -Dsafere.benchmark.corpus="$PWD/safere-benchmarks/target/benchmark-corpus" -cp safere-benchmarks/target/benchmarks.jar org.safere.benchmark.BenchmarkCollectionPlan report-plan > "$output/declared-report-plan.json"
./run-cpp-benchmarks.sh --engine re2 2>&1 | tee "$output/cpp-raw.txt"
grep '^{' "$output/cpp-raw.txt" > "$output/cpp-results.jsonl"
./run-rust-benchmarks.sh 2>&1 | tee "$output/rust-raw.txt"
grep '^{' "$output/rust-raw.txt" > "$output/rust-results.jsonl"
python3 safere-benchmarks/scripts/compare-benchmarks.py --jmh "$output/jmh-output.txt" --json "$output/cpp-results.jsonl" "$output/rust-results.jsonl" --engines safere,safere_utf8,re2_cpp,rust --declared-plan "$output/declared-report-plan.json" --output-jsonl "$output/normalized-results.jsonl" > "$output/merged-tables.md"
