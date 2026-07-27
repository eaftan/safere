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
#   ./run-java-memory-benchmarks.sh                         # run all benchmarks
#
# This runs the same benchmarks as run-java-benchmarks.sh but adds JMH's
# GC profiler (-prof gc), which reports gc.alloc.rate.norm (bytes allocated
# per operation). This metric is deterministic — it counts bytes, not time —
# and is not affected by other processes on the machine.
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
mvn install -DskipTests -q -f "$SCRIPT_DIR/pom.xml"

echo "=== Materializing shared benchmark inputs ==="
"$SCRIPT_DIR/materialize-benchmark-inputs.sh" --no-build

# JVM args for FFM native access, native library path, and the resolved corpus.
JVM_ARGS="--enable-native-access=ALL-UNNAMED -Dre2shim.library.path=$RE2_SHIM_DIR -Dsafere.benchmark.corpus=$BENCHMARK_CORPUS"

if [ ${#CROSS_ENGINE_PREFIXES[@]} -gt 0 ]; then
  CROSS_ENGINE_TRIALS="$(
    java $JVM_ARGS \
      -cp "$BENCHMARK_JAR" \
      org.safere.benchmark.CrossEngineBenchmarkPlan \
      nanoseconds \
      "${CROSS_ENGINE_PREFIXES[@]}"
  )"
else
  CROSS_ENGINE_TRIALS="$(
    java $JVM_ARGS \
      -cp "$BENCHMARK_JAR" \
      org.safere.benchmark.CrossEngineBenchmarkPlan \
      nanoseconds
  )"
fi
if [ ${#CROSS_ENGINE_SCALING_PREFIXES[@]} -gt 0 ]; then
  CROSS_ENGINE_SCALING_TRIALS="$(
    java $JVM_ARGS \
      -cp "$BENCHMARK_JAR" \
      org.safere.benchmark.CrossEngineBenchmarkPlan \
      microseconds \
      "${CROSS_ENGINE_SCALING_PREFIXES[@]}"
  )"
else
  CROSS_ENGINE_SCALING_TRIALS="$(
    java $JVM_ARGS \
      -cp "$BENCHMARK_JAR" \
      org.safere.benchmark.CrossEngineBenchmarkPlan \
      microseconds
  )"
fi
CROSS_ENGINE_PARAM_ARGS=()
if [[ ! " ${JMH_EXTRA_ARGS[*]-} " =~ [[:space:]]crossEngineTrial= ]]; then
  CROSS_ENGINE_PARAM_ARGS+=(-p "crossEngineTrial=$CROSS_ENGINE_TRIALS")
fi
if [[ ! " ${JMH_EXTRA_ARGS[*]-} " =~ [[:space:]]crossEngineScalingTrial= ]]; then
  CROSS_ENGINE_PARAM_ARGS+=(-p "crossEngineScalingTrial=$CROSS_ENGINE_SCALING_TRIALS")
fi
RUN_ARGS=("${CROSS_ENGINE_PARAM_ARGS[@]}")
if [ ${#JMH_EXTRA_ARGS[@]} -gt 0 ]; then
  RUN_ARGS+=("${JMH_EXTRA_ARGS[@]}")
fi

if [ ${#BENCHMARKS[@]} -eq 0 ]; then
  echo "=== Running all benchmarks with GC profiling ==="
  java \
    $JVM_ARGS \
    -jar "$BENCHMARK_JAR" \
    -jvmArgs "$JVM_ARGS" \
    -prof gc \
    $JMH_OPTS \
    "${RUN_ARGS[@]}"
else
  for bench in "${BENCHMARKS[@]}"; do
    echo "=== Running $bench with GC profiling ==="
    java \
      $JVM_ARGS \
      -jar "$BENCHMARK_JAR" \
      -jvmArgs "$JVM_ARGS" \
      -prof gc \
      $JMH_OPTS \
      "${RUN_ARGS[@]}" \
      "$bench"
  done
fi
