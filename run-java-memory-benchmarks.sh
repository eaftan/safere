#!/bin/bash
# Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.
#
# Run SafeRE JMH benchmarks with GC profiling to measure allocation rates.
#
# Usage:
#   ./run-java-memory-benchmarks.sh                    # run all benchmarks
#   ./run-java-memory-benchmarks.sh RegexBenchmark     # run a specific benchmark class
#   ./run-java-memory-benchmarks.sh Capture Regex      # run multiple benchmark classes
#
# This runs the same benchmarks as run-java-benchmarks.sh but adds JMH's
# GC profiler (-prof gc), which reports gc.alloc.rate.norm (bytes allocated
# per operation). This metric is deterministic — it counts bytes, not time —
# and is not affected by other processes on the machine.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BENCHMARK_JAR="$SCRIPT_DIR/safere-benchmarks/target/benchmarks.jar"
RE2_SHIM_DIR="$SCRIPT_DIR/safere-ffm-re2/build"

# JMH options (can be overridden via JMH_OPTS env var)
# Default: no flags, letting JMH use its built-in defaults
# (5 forks, 5 warmup iters x 10s, 5 measurement iters x 10s).
# The GC profiler is always added.
JMH_OPTS="${JMH_OPTS:-}"

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
