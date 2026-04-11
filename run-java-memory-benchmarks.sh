#!/bin/bash
# Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.
#
# Run SafeRE JMH benchmarks with GC profiling to measure allocation rates.
#
# Usage:
#   ./run-java-memory-benchmarks.sh RegexBenchmark         # publication-quality (default)
#   ./run-java-memory-benchmarks.sh --quick RegexBenchmark  # fast dev iteration
#   ./run-java-memory-benchmarks.sh --smoke RegexBenchmark  # CI smoke test
#   ./run-java-memory-benchmarks.sh                         # run all benchmarks
#
# This runs the same benchmarks as run-java-benchmarks.sh but adds JMH's
# GC profiler (-prof gc), which reports gc.alloc.rate.norm (bytes allocated
# per operation). This metric is deterministic — it counts bytes, not time —
# and is not affected by other processes on the machine.
#
# See run-java-benchmarks.sh for details on modes and settings.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BENCHMARK_JAR="$SCRIPT_DIR/safere-benchmarks/target/benchmarks.jar"
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

# JVM args for FFM native access and native library path.
JVM_ARGS="--enable-native-access=ALL-UNNAMED -Dre2shim.library.path=$RE2_SHIM_DIR"

echo "=== Building safere + benchmark JAR ==="
mvn install -DskipTests -q -f "$SCRIPT_DIR/pom.xml"

if [ $# -eq 0 ]; then
  echo "=== Running all benchmarks with GC profiling ==="
  java $JVM_ARGS -jar "$BENCHMARK_JAR" -jvmArgs "$JVM_ARGS" -prof gc $JMH_OPTS
else
  for bench in "$@"; do
    echo "=== Running $bench with GC profiling ==="
    java $JVM_ARGS -jar "$BENCHMARK_JAR" -jvmArgs "$JVM_ARGS" -prof gc $JMH_OPTS "$bench"
  done
fi
