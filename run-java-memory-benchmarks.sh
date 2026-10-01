#!/bin/bash
# Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.
#
# Run SafeRE JMH benchmarks with GC profiling to measure allocation rates.
#
# Usage:
#   ./run-java-memory-benchmarks.sh '^org\.safere\.benchmark\.CrossEngineBenchmark\.'
#   ./run-java-memory-benchmarks.sh --quick '^org\.safere\.benchmark\.CrossEngineBenchmark\.'
#   ./run-java-memory-benchmarks.sh --smoke '^org\.safere\.benchmark\.CrossEngineBenchmark\.'
#   ./run-java-memory-benchmarks.sh --declared
#   ./run-java-memory-benchmarks.sh                         # run declared allocation benchmarks
#
# This uses the same generic runners as run-java-benchmarks.sh, selecting the
# declared allocation workloads by default. JMH's GC profiler (-prof gc)
# reports gc.alloc.rate.norm (bytes allocated per operation).
#
# See run-java-benchmarks.sh for details on modes and settings.
#
# Arguments after the mode flag are passed directly to JMH as benchmark regex
# filters.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BENCHMARK_JAR="$SCRIPT_DIR/safere-benchmarks/target/benchmarks.jar"
BENCHMARK_CORPUS="$SCRIPT_DIR/safere-benchmarks/target/benchmark-corpus"
RE2_SHIM_DIR="$SCRIPT_DIR/safere-ffm-re2/build"

# Publication-quality settings: 3 forks × (3 warmup × 5s + 5 measurement × 5s).
# 15 samples per method — sufficient for meaningful confidence intervals.
PUBLISH_OPTS="-f 3 -wi 3 -w 5 -i 5 -r 5"
QUICK_OPTS="-f 1 -wi 3 -w 1 -i 5 -r 1"
SMOKE_OPTS="-f 0 -wi 1 -w 1 -i 1 -r 1"

# Parse mode flag.
MODE="publish"
DECLARED=false
SCAN_PROVIDER="default"
PROVIDER_SELECTED=false
if [ "${1:-}" = "--quick" ]; then
  MODE="quick"
  shift
elif [ "${1:-}" = "--smoke" ]; then
  MODE="smoke"
  shift
fi

BENCHMARKS=()
JMH_EXTRA_ARGS=()
CROSS_ENGINE_PREFIXES=()
CROSS_ENGINE_SCALING_PREFIXES=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --provider)
      if [ "$#" -lt 2 ] || [[ ! "$2" =~ ^(default|vector)$ ]]; then
        echo "ERROR: --provider requires default or vector" >&2
        exit 2
      fi
      SCAN_PROVIDER="$2"
      PROVIDER_SELECTED=true
      shift 2
      ;;
    --declared)
      DECLARED=true
      shift
      ;;
    --cross-engine-prefix)
      CROSS_ENGINE_PREFIXES+=("$2")
      shift 2
      ;;
    --cross-engine-scaling-prefix)
      CROSS_ENGINE_SCALING_PREFIXES+=("$2")
      shift 2
      ;;
    --)
      shift
      JMH_EXTRA_ARGS=("$@")
      break
      ;;
    *)
      BENCHMARKS+=("$1")
      shift
      ;;
  esac
done

# Declared trials are already partitioned by provider. JMH combines repeated
# parameter values, so an override could put a default trial in a Vector JVM.
if [ "$DECLARED" = true ]; then
  for argument in "${JMH_EXTRA_ARGS[@]}"; do
    case "$argument" in
      -p|-p=*)
        echo "ERROR: --declared cannot be combined with JMH -p overrides; use a focused run without --declared" >&2
        exit 2
        ;;
    esac
  done
fi

if [ "$MODE" = "smoke" ]; then
  JMH_OPTS="$SMOKE_OPTS"
  echo "=== Smoke-test mode (CI only) ==="
elif [ "$MODE" = "quick" ]; then
  JMH_OPTS="$QUICK_OPTS"
  echo "=== Quick mode (NOT for BENCHMARKS.md) ==="
else
  JMH_OPTS="$PUBLISH_OPTS"
  echo "=== Publication mode (for BENCHMARKS.md) ==="
fi

echo "=== Building safere + benchmark JAR ==="
mvn -pl safere-benchmarks clean -q -f "$SCRIPT_DIR/pom.xml"
mvn install -DskipTests -q -f "$SCRIPT_DIR/pom.xml"

echo "=== Materializing shared benchmark inputs ==="
"$SCRIPT_DIR/materialize-benchmark-inputs.sh" --no-build

# Keep provider selection identical in launcher and measurement forks.
BASE_JVM_ARGS="--enable-native-access=ALL-UNNAMED -Dre2shim.library.path=$RE2_SHIM_DIR -Dsafere.benchmark.corpus=$BENCHMARK_CORPUS"
select_scan_provider() {
  local provider="$1"
  JVM_ARGS="$BASE_JVM_ARGS -Dsafere.benchmark.scanProvider=$provider"
  if [ "$provider" = "vector" ]; then
    JVM_ARGS="$JVM_ARGS --add-modules=jdk.incubator.vector -Dorg.safere.experimental.vectorScanProvider=vector"
  fi
  java $JVM_ARGS -cp "$BENCHMARK_JAR" org.safere.benchmark.BenchmarkProviderCheck
}
select_scan_provider "$SCAN_PROVIDER"
GENERATED_JMH_ARGUMENT_FILE="$(mktemp "${TMPDIR:-/tmp}/safere-memory-jmh-args.XXXXXX")"
trap 'rm -f -- "$GENERATED_JMH_ARGUMENT_FILE"' EXIT

# Output/profiler options do not change workload selection; explicit parameters do.
has_parameter_override=false
for argument in "${JMH_EXTRA_ARGS[@]}"; do
  if [ "$argument" = "-p" ]; then
    has_parameter_override=true
  fi
done

# Without an explicit selection, measure the declared allocation workload set.
# JMH cannot run every discovered method because generic runners have empty @Param defaults.
if [ ${#BENCHMARKS[@]} -eq 0 ] && [ "$has_parameter_override" = false ] \
  && [ ${#CROSS_ENGINE_PREFIXES[@]} -eq 0 ] \
  && [ ${#CROSS_ENGINE_SCALING_PREFIXES[@]} -eq 0 ]; then
  DECLARED=true
fi

if [ "$DECLARED" = true ]; then
  COLLECTION_QUERY=(allocation-execution-runners)
  if [ "$MODE" = "smoke" ]; then
    COLLECTION_QUERY+=(--smoke)
  fi
  matched_runner=false
  while IFS=$'\t' read -r profile benchmark parameter trial_ids provider; do
    if [ "$PROVIDER_SELECTED" = true ] && [ "$provider" != "$SCAN_PROVIDER" ]; then
      continue
    fi
    if [ ${#BENCHMARKS[@]} -gt 0 ]; then
      matches_filter=false
      for filter in "${BENCHMARKS[@]}"; do
        if [[ "$benchmark" =~ $filter ]]; then
          matches_filter=true
          break
        fi
      done
      if [ "$matches_filter" = false ]; then
        continue
      fi
    fi
    matched_runner=true
    select_scan_provider "$provider"
    echo "=== Running declared allocation trials for $benchmark ==="
    # The plan already selected the exact workload/provider group, including smoke rows.
    escaped_jar="${BENCHMARK_JAR//\\/\\\\}"
    escaped_jar="${escaped_jar//\"/\\\"}"
    printf '%s\n' '-jar' "\"$escaped_jar\"" '-p' "\"$parameter=$trial_ids\"" \
      > "$GENERATED_JMH_ARGUMENT_FILE"
    RUNNER_COMMAND=(java \
      $JVM_ARGS \
      "@$GENERATED_JMH_ARGUMENT_FILE" \
      -jvmArgs "$JVM_ARGS" \
      -prof gc \
      $JMH_OPTS)
    if [ ${#JMH_EXTRA_ARGS[@]} -gt 0 ]; then
      RUNNER_COMMAND+=("${JMH_EXTRA_ARGS[@]}")
    fi
    RUNNER_COMMAND+=("^${benchmark//./\\.}$")
    "${RUNNER_COMMAND[@]}"
  done < <(
    java $BASE_JVM_ARGS \
      -cp "$BENCHMARK_JAR" \
      org.safere.benchmark.BenchmarkCollectionPlan \
      "${COLLECTION_QUERY[@]}"
  )
  if [ "$matched_runner" = false ]; then
    echo "ERROR: no declared allocation runner matches the requested filters" >&2
    exit 1
  fi
  exit 0
fi

# Generate only the selected runners' parameters. Prefix selections and explicit
# JMH parameter overrides replace the corresponding defaults.
write_generated_jmh_arguments() {
  local bench="$1"
  local overridden_parameters=()
  local override_timing=false
  local override_scaling=false
  local index=0
  while [ "$index" -lt "${#JMH_EXTRA_ARGS[@]}" ]; do
    if [ "${JMH_EXTRA_ARGS[$index]}" = "-p" ] \
      && [ "$((index + 1))" -lt "${#JMH_EXTRA_ARGS[@]}" ]; then
      local parameter="${JMH_EXTRA_ARGS[$((index + 1))]%%=*}"
      overridden_parameters+=("$parameter")
      case "$parameter" in
        crossEngineTrial) override_timing=true ;;
        crossEngineScalingTrial) override_scaling=true ;;
      esac
      index=$((index + 2))
    else
      index=$((index + 1))
    fi
  done
  if [ ${#CROSS_ENGINE_PREFIXES[@]} -gt 0 ]; then
    overridden_parameters+=(crossEngineTrial)
  fi
  if [ ${#CROSS_ENGINE_SCALING_PREFIXES[@]} -gt 0 ]; then
    overridden_parameters+=(crossEngineScalingTrial)
  fi
  java $JVM_ARGS \
    -cp "$BENCHMARK_JAR" \
    org.safere.benchmark.BenchmarkCollectionPlan \
    runner-arguments "$bench" "$BENCHMARK_JAR" "${overridden_parameters[@]}" \
    > "$GENERATED_JMH_ARGUMENT_FILE"
  if [ ${#CROSS_ENGINE_PREFIXES[@]} -gt 0 ] && [ "$override_timing" = false ]; then
    java $JVM_ARGS \
      -cp "$BENCHMARK_JAR" \
      org.safere.benchmark.CrossEngineBenchmarkPlan \
      --argument-file nanoseconds "${CROSS_ENGINE_PREFIXES[@]}" \
      >> "$GENERATED_JMH_ARGUMENT_FILE"
  fi
  if [ ${#CROSS_ENGINE_SCALING_PREFIXES[@]} -gt 0 ] && [ "$override_scaling" = false ]; then
    java $JVM_ARGS \
      -cp "$BENCHMARK_JAR" \
      org.safere.benchmark.CrossEngineBenchmarkPlan \
      --argument-file microseconds "${CROSS_ENGINE_SCALING_PREFIXES[@]}" \
      >> "$GENERATED_JMH_ARGUMENT_FILE"
  fi
}

if [ ${#BENCHMARKS[@]} -eq 0 ]; then
  BENCHMARKS=('CrossEngine(?:Scaling)?Benchmark\.run')
fi
for bench in "${BENCHMARKS[@]}"; do
  echo "=== Running $bench with GC profiling ==="
  write_generated_jmh_arguments "$bench"
  java \
    $JVM_ARGS \
    "@$GENERATED_JMH_ARGUMENT_FILE" \
    -jvmArgs "$JVM_ARGS" \
    -prof gc \
    $JMH_OPTS \
    "${JMH_EXTRA_ARGS[@]}" \
    "$bench"
done
