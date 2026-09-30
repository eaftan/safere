#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
while [ ! -f "$OUT/jmh-output.txt" ]; do sleep 60; done
# The marker is copied only after the Java standard, no-fork, and cold-start wrappers finish.
sleep 30
printf '%s\n' 'Java timing suites completed; beginning remaining sequential suites.' | tee "$OUT/continuation-status.txt"
./run-java-memory-benchmarks.sh \
  --cross-engine-prefix 'RegexBenchmark.' \
  --cross-engine-scaling-prefix 'SearchScalingBenchmark.' \
  2>&1 | tee "$OUT/java-memory.txt"
java -Xms256m -Xmx256m \
  -Dsafere.benchmark.corpus="$ROOT/safere-benchmarks/target/benchmark-corpus" \
  -cp safere-benchmarks/target/benchmarks.jar \
  org.safere.benchmark.MemoryBenchmark 2>&1 | tee "$OUT/java-pattern-memory.txt"
java -Dsafere.benchmark.corpus="$ROOT/safere-benchmarks/target/benchmark-corpus" \
  -cp safere-benchmarks/target/benchmarks.jar \
  org.safere.benchmark.BenchmarkCollectionPlan report-plan > "$OUT/declared-report-plan.json"
./run-cpp-benchmarks.sh --engine re2 2>&1 | tee "$OUT/re2-raw.txt"
grep '^{' "$OUT/re2-raw.txt" > "$OUT/re2-results.jsonl"
./run-cpp-benchmarks.sh --engine pcre2-jit 2>&1 | tee "$OUT/pcre2-jit-raw.txt"
grep '^{' "$OUT/pcre2-jit-raw.txt" > "$OUT/pcre2-jit-results.jsonl"
./run-rust-benchmarks.sh 2>&1 | tee "$OUT/rust-raw.txt"
grep '^{' "$OUT/rust-raw.txt" > "$OUT/rust-results.jsonl"
python3 safere-benchmarks/scripts/compare-benchmarks.py \
  --jmh "$OUT/jmh-output.txt" \
  --json "$OUT/re2-results.jsonl" "$OUT/pcre2-jit-results.jsonl" "$OUT/rust-results.jsonl" \
  --engines safere,safere_utf8,jdk,re2j,re2_ffm,re2_cpp,pcre2_jit,rust \
  --declared-plan "$OUT/declared-report-plan.json" \
  --output-jsonl "$OUT/normalized-results.jsonl" > "$OUT/merged-tables.md"
printf '%s\n' 'All requested engine suites and normalization completed.' | tee "$OUT/collection-complete.txt"
