#!/bin/bash
# Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.
#
# Run SafeRE JMH benchmarks.
#
# Usage:
#   ./run-java-benchmarks.sh                    # run all benchmarks
#   ./run-java-benchmarks.sh RegexBenchmark     # run a specific benchmark class
#   ./run-java-benchmarks.sh Capture Regex      # run multiple benchmark classes
#
# IMPORTANT: This script uses `mvn install` (not `mvn package`) to ensure the
# benchmark module picks up the latest safere code. The benchmarks resolve the
# safere dependency from the local Maven repository (~/.m2/repository), so
# `mvn package` alone would leave them running against stale code.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BENCHMARK_DIR="$SCRIPT_DIR/safere-benchmarks"

# JMH options (can be overridden via JMH_OPTS env var)
# Default: no flags, letting JMH use its built-in defaults
# (5 forks, 5 warmup iters x 10s, 5 measurement iters x 10s).
JMH_OPTS="${JMH_OPTS:-}"

echo "=== Installing safere to local Maven repository ==="
mvn install -DskipTests -q -f "$SCRIPT_DIR/pom.xml"

if [ $# -eq 0 ]; then
  # No arguments: run all benchmarks
  echo "=== Running all benchmarks ==="
  mvn exec:java \
    -Dexec.mainClass="org.openjdk.jmh.Main" \
    -Dexec.args="$JMH_OPTS" \
    -q -f "$BENCHMARK_DIR/pom.xml"
else
  # Run each specified benchmark class
  for bench in "$@"; do
    echo "=== Running $bench ==="
    mvn exec:java \
      -Dexec.mainClass="org.openjdk.jmh.Main" \
      -Dexec.args="$JMH_OPTS $bench" \
      -q -f "$BENCHMARK_DIR/pom.xml"
  done
fi
