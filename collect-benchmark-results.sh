#!/bin/bash
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.
#
# Collect publication-quality benchmark outputs for updating BENCHMARKS.md.
#
# Usage:
#   ./collect-benchmark-results.sh
#   ./collect-benchmark-results.sh --output-dir benchmark-results/my-run
#
# The script intentionally does not run the test suite. It runs benchmark
# batches sequentially, captures raw output, extracts native JSONL results, and
# generates merged markdown tables.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEFAULT_RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
OUTPUT_DIR="$SCRIPT_DIR/benchmark-results/$DEFAULT_RUN_ID"

usage() {
  cat <<EOF
Usage:
  ./collect-benchmark-results.sh
  ./collect-benchmark-results.sh --output-dir benchmark-results/my-run

Collects publication-quality benchmark outputs for updating BENCHMARKS.md.
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --output-dir)
      if [ $# -lt 2 ]; then
        echo "ERROR: --output-dir requires a path" >&2
        exit 2
      fi
      OUTPUT_DIR="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ "$OUTPUT_DIR" != /* ]]; then
  OUTPUT_DIR="$SCRIPT_DIR/$OUTPUT_DIR"
fi

mkdir -p "$OUTPUT_DIR"

log() {
  printf '\n=== %s ===\n' "$*"
}

run_and_capture() {
  local output_file="$1"
  shift
  log "Running: $*"
  "$@" 2>&1 | tee "$output_file"
}

extract_jsonl() {
  local input_file="$1"
  local output_file="$2"
  grep '^{' "$input_file" > "$output_file"
}

cd "$SCRIPT_DIR"

log "Writing benchmark outputs to $OUTPUT_DIR"

run_and_capture "$OUTPUT_DIR/java-01-core.txt" \
  ./run-java-benchmarks.sh RegexBenchmark CompileBenchmark

run_and_capture "$OUTPUT_DIR/java-02-scaling.txt" \
  ./run-java-benchmarks.sh SearchScalingBenchmark CaptureScalingBenchmark

run_and_capture "$OUTPUT_DIR/java-03-http-replace-fanout.txt" \
  ./run-java-benchmarks.sh HttpBenchmark ReplaceBenchmark FanoutBenchmark

run_and_capture "$OUTPUT_DIR/java-04-pathological.txt" \
  ./run-java-benchmarks.sh PathologicalBenchmark PathologicalComparisonBenchmark

run_and_capture "$OUTPUT_DIR/java-05-patternset.txt" \
  ./run-java-benchmarks.sh PatternSetBenchmark

log "Combining Java JMH output"
cat \
  "$OUTPUT_DIR/java-01-core.txt" \
  "$OUTPUT_DIR/java-02-scaling.txt" \
  "$OUTPUT_DIR/java-03-http-replace-fanout.txt" \
  "$OUTPUT_DIR/java-04-pathological.txt" \
  "$OUTPUT_DIR/java-05-patternset.txt" \
  > "$OUTPUT_DIR/jmh-output.txt"

run_and_capture "$OUTPUT_DIR/java-memory.txt" \
  ./run-java-memory-benchmarks.sh RegexBenchmark SearchScalingBenchmark MemoryScalingBenchmark

run_and_capture "$OUTPUT_DIR/java-pattern-memory.txt" \
  java -Xms256m -Xmx256m -cp safere-benchmarks/target/benchmarks.jar \
    org.safere.benchmark.MemoryBenchmark

run_and_capture "$OUTPUT_DIR/cpp-raw.txt" \
  ./run-cpp-benchmarks.sh Regex Compile SearchScaling CaptureScaling Http Replace Fanout Pathological

log "Extracting C++ JSONL"
extract_jsonl "$OUTPUT_DIR/cpp-raw.txt" "$OUTPUT_DIR/cpp-results.jsonl"

run_and_capture "$OUTPUT_DIR/go-raw.txt" \
  ./run-go-benchmarks.sh Regex Compile SearchScaling CaptureScaling Http Replace Fanout Pathological

log "Extracting Go JSONL"
extract_jsonl "$OUTPUT_DIR/go-raw.txt" "$OUTPUT_DIR/go-results.jsonl"

log "Generating merged markdown tables"
python3 safere-benchmarks/scripts/compare-benchmarks.py \
  --jmh "$OUTPUT_DIR/jmh-output.txt" \
  --json "$OUTPUT_DIR/cpp-results.jsonl" "$OUTPUT_DIR/go-results.jsonl" \
  --engines safere,jdk,re2j,re2_ffm,re2_cpp,go \
  > "$OUTPUT_DIR/merged-tables.md"

log "Updating latest symlink"
ln -sfn "$OUTPUT_DIR" "$SCRIPT_DIR/benchmark-results/latest"

log "Done"
cat <<EOF
Benchmark result files are in:
  $OUTPUT_DIR

Point the agent at:
  benchmark-results/latest

Key files:
  jmh-output.txt
  cpp-results.jsonl
  go-results.jsonl
  merged-tables.md
  java-memory.txt
  java-pattern-memory.txt
EOF
