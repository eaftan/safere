#!/bin/bash
# Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.
#
# Run SafeRE JMH benchmarks.
#
# Usage:
#   ./run-java-benchmarks.sh RegexBenchmark         # publication-quality (default)
#   ./run-java-benchmarks.sh --quick RegexBenchmark  # fast dev iteration
#   ./run-java-benchmarks.sh --smoke RegexBenchmark  # CI smoke test (minimal)
#   ./run-java-benchmarks.sh                         # run all benchmarks
#
# The script builds a shaded (fat) JAR containing all dependencies and runs
# it with `java -jar`. This is required for JMH fork mode to work — forked
# JVMs need a self-contained classpath. Running via `mvn exec:java` breaks
# fork mode because the forked child cannot find JMH classes.
#
# The benchmark classes have no @Fork/@Warmup/@Measurement annotations, so
# ALL statistical rigor settings come from this script. This avoids confusion
# between annotation values and command-line overrides.
#
# Modes:
#   Default (no flags):  Publication-quality — 3 forks, 3 warmup × 5s,
#                        5 measurement × 5s. Use for BENCHMARKS.md.
#   --quick:             Dev iteration — 1 fork, 3 warmup × 1s,
#                        5 measurement × 1s. NOT for BENCHMARKS.md.
#   --smoke:             CI smoke test — 0 forks, 1 warmup × 1s,
#                        1 measurement × 1s. Just verifies benchmarks compile
#                        and run without errors.
#
# Pathological benchmarks (PathologicalBenchmark, PathologicalComparisonBenchmark)
# always run with -f 0 (no forking) because the JDK engine can hang on large
# inputs, making forked JVM processes unrecoverable.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BENCHMARK_JAR="$SCRIPT_DIR/safere-benchmarks/target/benchmarks.jar"
RE2_SHIM_DIR="$SCRIPT_DIR/safere-ffm-re2/build"

# Publication-quality settings: 3 forks × (3 warmup × 5s + 5 measurement × 5s).
# 15 samples per method — sufficient for meaningful confidence intervals.
PUBLISH_OPTS="-f 3 -wi 3 -w 5 -i 5 -r 5"
QUICK_OPTS="-f 1 -wi 3 -w 1 -i 5 -r 1"
SMOKE_OPTS="-f 0 -wi 1 -w 1 -i 1 -r 1"

# Pathological benchmarks must run without forking (JDK can hang).
PATHOLOGICAL_PUBLISH_OPTS="-f 0 -wi 3 -w 5 -i 5 -r 5"
PATHOLOGICAL_QUICK_OPTS="-f 0 -wi 3 -w 1 -i 5 -r 1"
PATHOLOGICAL_SMOKE_OPTS="-f 0 -wi 1 -w 1 -i 1 -r 1"

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
  PATHOLOGICAL_JMH_OPTS="$PATHOLOGICAL_SMOKE_OPTS"
  echo "=== Smoke-test mode (CI only) ==="
elif [ "$MODE" = "quick" ]; then
  JMH_OPTS="$QUICK_OPTS"
  PATHOLOGICAL_JMH_OPTS="$PATHOLOGICAL_QUICK_OPTS"
  echo "=== Quick mode (NOT for BENCHMARKS.md) ==="
else
  JMH_OPTS="$PUBLISH_OPTS"
  PATHOLOGICAL_JMH_OPTS="$PATHOLOGICAL_PUBLISH_OPTS"
  echo "=== Publication mode (for BENCHMARKS.md) ==="
fi

# JVM args for FFM native access and native library path.
JVM_ARGS="--enable-native-access=ALL-UNNAMED -Dre2shim.library.path=$RE2_SHIM_DIR"

echo "=== Building safere + benchmark JAR ==="
mvn install -DskipTests -q -f "$SCRIPT_DIR/pom.xml"

# Returns true if the benchmark name matches a pathological benchmark.
is_pathological() {
  case "$1" in
    *Pathological*) return 0 ;;
    *) return 1 ;;
  esac
}

run_benchmark() {
  local bench="$1"
  local opts="$JMH_OPTS"
  if is_pathological "$bench"; then
    opts="$PATHOLOGICAL_JMH_OPTS"
  fi
  echo "=== Running $bench ($opts) ==="
  java $JVM_ARGS -jar "$BENCHMARK_JAR" -jvmArgs "$JVM_ARGS" $opts "$bench"
}

if [ $# -eq 0 ]; then
  echo "=== Running all benchmarks ==="
  java $JVM_ARGS -jar "$BENCHMARK_JAR" -jvmArgs "$JVM_ARGS" $JMH_OPTS
else
  for bench in "$@"; do
    run_benchmark "$bench"
  done
fi
