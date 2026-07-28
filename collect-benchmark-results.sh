#!/bin/bash
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.
#
# Collect benchmark outputs for updating BENCHMARKS.md.
#
# Usage:
#   ./collect-benchmark-results.sh
#   ./collect-benchmark-results.sh --long
#   ./collect-benchmark-results.sh --cross-language
#   ./collect-benchmark-results.sh --skip-openjdk-regex
#   ./collect-benchmark-results.sh --smoke
#   ./collect-benchmark-results.sh --output-dir benchmark-results/my-run
#
# The script intentionally does not run the test suite. It runs benchmark
# batches sequentially, captures raw output, and generates markdown tables.
# By default it collects the Java/JMH results and the separately licensed
# OpenJDK-derived suite from an external checkout. Use --cross-language to also
# collect C++ RE2, PCRE2 JIT, Go regexp, Rust regex, and .NET
# non-backtracking results.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEFAULT_RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
OUTPUT_DIR="$SCRIPT_DIR/benchmark-results/$DEFAULT_RUN_ID"
MODE="standard"
CROSS_LANGUAGE=false
OPENJDK_REGEX=true
OPENJDK_REGEX_REPO=""

usage() {
  cat <<EOF
Usage:
  ./collect-benchmark-results.sh
  ./collect-benchmark-results.sh --long
  ./collect-benchmark-results.sh --cross-language
  ./collect-benchmark-results.sh --skip-openjdk-regex
  ./collect-benchmark-results.sh --smoke
  ./collect-benchmark-results.sh --output-dir benchmark-results/my-run

Collects benchmark outputs for updating BENCHMARKS.md.

Options:
  --long            Use the longer Java confirmation mode.
  --cross-language  Also run C++ RE2, PCRE2 JIT, Go regexp, Rust regex, and
                    .NET non-backtracking harnesses.
  --openjdk-regex-repo PATH
                    Select the external OpenJDK-derived suite checkout.
  --skip-openjdk-regex
                    Omit that external suite; the run is not a full collection.
  --smoke           Run one small benchmark through the collection pipeline.
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --long)
      MODE="long"
      shift
      ;;
    --cross-language)
      CROSS_LANGUAGE=true
      shift
      ;;
    --openjdk-regex-repo)
      if [ $# -lt 2 ]; then
        echo "ERROR: --openjdk-regex-repo requires a path" >&2
        exit 2
      fi
      OPENJDK_REGEX=true
      OPENJDK_REGEX_REPO="$2"
      shift 2
      ;;
    --skip-openjdk-regex)
      OPENJDK_REGEX=false
      shift
      ;;
    --smoke)
      MODE="smoke"
      shift
      ;;
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

if [ "$MODE" = "smoke" ] && [ "$OUTPUT_DIR" = "$SCRIPT_DIR/benchmark-results/$DEFAULT_RUN_ID" ]; then
  OUTPUT_DIR="$SCRIPT_DIR/benchmark-results/smoke-$DEFAULT_RUN_ID"
fi

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

clean_benchmark_module() {
  log "Cleaning benchmark module before rebuild"
  mvn -pl safere-benchmarks clean -q -f "$SCRIPT_DIR/pom.xml"
}

extract_jsonl() {
  local input_file="$1"
  local output_file="$2"
  grep '^{' "$input_file" > "$output_file"
}

cd "$SCRIPT_DIR"

log "Writing benchmark outputs to $OUTPUT_DIR"
log "Mode: $MODE"
log "Cross-language: $CROSS_LANGUAGE"
log "OpenJDK regex suite: $OPENJDK_REGEX"

JAVA_MODE_ARGS=()
if [ "$MODE" = "long" ]; then
  JAVA_MODE_ARGS=(--long)
fi

if [ "$MODE" = "smoke" ]; then
  run_and_capture "$OUTPUT_DIR/java-declared.txt" \
    ./run-java-benchmarks.sh \
      --smoke \
      --declared

  log "Combining Java JMH output"
  cp "$OUTPUT_DIR/java-declared.txt" "$OUTPUT_DIR/jmh-output.txt"

  clean_benchmark_module
  run_and_capture "$OUTPUT_DIR/java-memory.txt" \
    ./run-java-memory-benchmarks.sh \
      --smoke \
      --declared
else
  run_and_capture "$OUTPUT_DIR/java-declared.txt" \
    ./run-java-benchmarks.sh \
      "${JAVA_MODE_ARGS[@]}" \
      --declared

  log "Combining Java JMH output"
  cp "$OUTPUT_DIR/java-declared.txt" "$OUTPUT_DIR/jmh-output.txt"

  clean_benchmark_module
  run_and_capture "$OUTPUT_DIR/java-memory.txt" \
    ./run-java-memory-benchmarks.sh \
      --declared
fi

PATTERN_MEMORY_COMMAND=(
  java -Xms256m -Xmx256m
  -Dsafere.benchmark.corpus="$SCRIPT_DIR/safere-benchmarks/target/benchmark-corpus"
  -cp safere-benchmarks/target/benchmarks.jar
  org.safere.benchmark.MemoryBenchmark
)
if [ "$MODE" = "smoke" ]; then
  RETAINED_TRIALS="$(
    java \
      -Dsafere.benchmark.corpus="$SCRIPT_DIR/safere-benchmarks/target/benchmark-corpus" \
      -cp safere-benchmarks/target/benchmarks.jar \
      org.safere.benchmark.SpecializedBenchmarkPlan retained-memory
  )"
  PATTERN_MEMORY_COMMAND+=("${RETAINED_TRIALS%%,*}")
fi
run_and_capture "$OUTPUT_DIR/java-pattern-memory.txt" "${PATTERN_MEMORY_COMMAND[@]}"

log "Writing declared report plan"
REPORT_PLAN_ARGS=(report-plan)
if [ "$MODE" = "smoke" ]; then
  REPORT_PLAN_ARGS+=(--smoke)
fi
java \
  -Dsafere.benchmark.corpus="$SCRIPT_DIR/safere-benchmarks/target/benchmark-corpus" \
  -cp safere-benchmarks/target/benchmarks.jar \
  org.safere.benchmark.BenchmarkCollectionPlan "${REPORT_PLAN_ARGS[@]}" \
  > "$OUTPUT_DIR/declared-report-plan.json"

if [ "$OPENJDK_REGEX" = true ]; then
  OPENJDK_REGEX_ARGS=()
  if [ -n "$OPENJDK_REGEX_REPO" ]; then
    OPENJDK_REGEX_ARGS+=(--repo "$OPENJDK_REGEX_REPO")
  fi
  if [ "$MODE" = "smoke" ]; then
    OPENJDK_REGEX_ARGS+=(
      --smoke
      'org.safere.bench.openjdk.FindPatternComparison.*'
    )
  fi
  OPENJDK_REGEX_ARGS+=(
    --
    -rf json
    -rff "$OUTPUT_DIR/openjdk-regex-results.json"
  )

  run_and_capture "$OUTPUT_DIR/openjdk-regex-output.txt" \
    ./run-openjdk-regex-benchmarks.sh "${OPENJDK_REGEX_ARGS[@]}"
fi

COMPARE_ENGINES="safere,safere_utf8,jdk,re2j,re2_ffm"
COMPARE_ARGS=(
  --jmh "$OUTPUT_DIR/jmh-output.txt"
)

if [ "$CROSS_LANGUAGE" = true ]; then
  if [ "$MODE" = "smoke" ]; then
    NATIVE_SMOKE_TRIALS="$(
      java \
        -Dsafere.benchmark.corpus="$SCRIPT_DIR/safere-benchmarks/target/benchmark-corpus" \
        -cp safere-benchmarks/target/benchmarks.jar \
        org.safere.benchmark.BenchmarkCollectionPlan trials \
        --variant re2-ffm-string-conversion
    )"
    NATIVE_SMOKE_WORKLOAD="${NATIVE_SMOKE_TRIALS%%@*}"
    run_and_capture "$OUTPUT_DIR/cpp-raw.txt" \
      ./run-cpp-benchmarks.sh "$NATIVE_SMOKE_WORKLOAD"
  else
    run_and_capture "$OUTPUT_DIR/cpp-raw.txt" \
      ./run-cpp-benchmarks.sh
  fi

  log "Extracting C++ JSONL"
  extract_jsonl "$OUTPUT_DIR/cpp-raw.txt" "$OUTPUT_DIR/cpp-results.jsonl"

  if [ "$MODE" = "smoke" ]; then
    run_and_capture "$OUTPUT_DIR/go-raw.txt" \
      ./run-go-benchmarks.sh "$NATIVE_SMOKE_WORKLOAD"
  else
    run_and_capture "$OUTPUT_DIR/go-raw.txt" \
      ./run-go-benchmarks.sh
  fi

  log "Extracting Go JSONL"
  extract_jsonl "$OUTPUT_DIR/go-raw.txt" "$OUTPUT_DIR/go-results.jsonl"

  if [ "$MODE" = "smoke" ]; then
    run_and_capture "$OUTPUT_DIR/rust-raw.txt" \
      ./run-rust-benchmarks.sh "$NATIVE_SMOKE_WORKLOAD"
    run_and_capture "$OUTPUT_DIR/dotnet-raw.txt" \
      ./run-dotnet-benchmarks.sh --smoke "$NATIVE_SMOKE_WORKLOAD"
  else
    run_and_capture "$OUTPUT_DIR/rust-raw.txt" \
      ./run-rust-benchmarks.sh
    run_and_capture "$OUTPUT_DIR/dotnet-raw.txt" \
      ./run-dotnet-benchmarks.sh
  fi

  log "Extracting Rust JSONL"
  extract_jsonl "$OUTPUT_DIR/rust-raw.txt" "$OUTPUT_DIR/rust-results.jsonl"

  log "Extracting .NET JSONL"
  extract_jsonl "$OUTPUT_DIR/dotnet-raw.txt" "$OUTPUT_DIR/dotnet-results.jsonl"

  COMPARE_ARGS+=(
    --json "$OUTPUT_DIR/cpp-results.jsonl" "$OUTPUT_DIR/go-results.jsonl" \
      "$OUTPUT_DIR/rust-results.jsonl" "$OUTPUT_DIR/dotnet-results.jsonl"
  )
  COMPARE_ENGINES="safere,safere_utf8,jdk,re2j,re2_ffm,re2_cpp,pcre2_jit,go,rust,dotnet_nonbacktracking"
fi

log "Generating markdown tables"
COMPARE_ARGS+=(--engines "$COMPARE_ENGINES")
COMPARE_ARGS+=(--declared-plan "$OUTPUT_DIR/declared-report-plan.json")
python3 safere-benchmarks/scripts/compare-benchmarks.py "${COMPARE_ARGS[@]}" \
  > "$OUTPUT_DIR/merged-tables.md"

if [ "$CROSS_LANGUAGE" = true ]; then
  log "Generating separate cross-runtime context tables"
  python3 safere-benchmarks/scripts/compare-benchmarks.py \
    --json "$OUTPUT_DIR/cpp-results.jsonl" "$OUTPUT_DIR/go-results.jsonl" \
      "$OUTPUT_DIR/rust-results.jsonl" \
      "$OUTPUT_DIR/dotnet-results.jsonl" \
    --engines re2_cpp,pcre2_jit,go,rust,dotnet_nonbacktracking \
    > "$OUTPUT_DIR/cross-runtime-tables.md"
fi

if [ "$MODE" = "smoke" ]; then
  log "Verifying smoke output"
  SMOKE_TABLES=("$OUTPUT_DIR/merged-tables.md")
  if [ "$CROSS_LANGUAGE" = true ]; then
    # Native harnesses intentionally cover only the cross-runtime subset, so
    # their columns contain expected gaps in the merged Java report. Validate
    # the complete Java matrix and the native subset independently.
    python3 safere-benchmarks/scripts/compare-benchmarks.py \
      --jmh "$OUTPUT_DIR/jmh-output.txt" \
      --engines safere,safere_utf8,jdk,re2j,re2_ffm \
      --declared-plan "$OUTPUT_DIR/declared-report-plan.json" \
      > "$OUTPUT_DIR/smoke-java-tables.md"
    SMOKE_TABLES=(
      "$OUTPUT_DIR/smoke-java-tables.md"
      "$OUTPUT_DIR/cross-runtime-tables.md"
    )
  fi
  missing_cell="$(printf '\342\200\224')"
  for smoke_table in "${SMOKE_TABLES[@]}"; do
    if grep -q "$missing_cell" "$smoke_table" \
      || grep -qw 'missing' "$smoke_table"; then
      echo "ERROR: smoke table contains missing result cells: $smoke_table" >&2
      exit 1
    fi
  done
fi

log "Updating latest symlink"
mkdir -p "$SCRIPT_DIR/benchmark-results"
ln -sfn "$OUTPUT_DIR" "$SCRIPT_DIR/benchmark-results/latest"

log "Done"
cat <<EOF
Benchmark result files are in:
  $OUTPUT_DIR

Point the agent at:
  benchmark-results/latest

Key files:
  jmh-output.txt
  declared-report-plan.json
  merged-tables.md
  java-memory.txt
  java-pattern-memory.txt
EOF

if [ "$CROSS_LANGUAGE" = true ]; then
  cat <<EOF
  cpp-results.jsonl
  go-results.jsonl
  rust-results.jsonl
  dotnet-results.jsonl
  cross-runtime-tables.md
EOF
fi

if [ "$OPENJDK_REGEX" = true ]; then
  cat <<EOF
  openjdk-regex-output.txt
  openjdk-regex-results.json
EOF
fi
