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
# The script builds a shaded (fat) JAR containing all dependencies and runs
# it with `java -jar`. This is required for JMH fork mode to work — forked
# JVMs need a self-contained classpath. Running via `mvn exec:java` breaks
# fork mode because the forked child cannot find JMH classes.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BENCHMARK_JAR="$SCRIPT_DIR/safere-benchmarks/target/benchmarks.jar"
RE2_SHIM_DIR="$SCRIPT_DIR/safere-ffm-re2/build"

# JMH options (can be overridden via JMH_OPTS env var)
# Default: no flags, letting JMH use its built-in defaults
# (5 forks, 5 warmup iters x 10s, 5 measurement iters x 10s).
JMH_OPTS="${JMH_OPTS:-}"

# JVM args for FFM native access and native library path.
JVM_ARGS="--enable-native-access=ALL-UNNAMED -Dre2shim.library.path=$RE2_SHIM_DIR"

echo "=== Building safere + benchmark JAR ==="
mvn install -DskipTests -q -f "$SCRIPT_DIR/pom.xml"

if [ $# -eq 0 ]; then
  echo "=== Running all benchmarks ==="
  java $JVM_ARGS -jar "$BENCHMARK_JAR" -jvmArgs "$JVM_ARGS" $JMH_OPTS
else
  for bench in "$@"; do
    echo "=== Running $bench ==="
    java $JVM_ARGS -jar "$BENCHMARK_JAR" -jvmArgs "$JVM_ARGS" $JMH_OPTS "$bench"
  done
fi
