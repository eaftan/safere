#!/bin/bash
# Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.
#
# Run SafeRE JMH benchmarks.
#
# Usage:
#   ./run-java-benchmarks.sh RegexBenchmark         # standard benchmark run
#   ./run-java-benchmarks.sh --long RegexBenchmark  # longer confirmation run
#   ./run-java-benchmarks.sh --smoke RegexBenchmark  # CI smoke test (minimal)
#   ./run-java-benchmarks.sh --first-compile UnicodeFirstCompileBenchmark
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
#   Default (no flags):  Standard — 2 forks, 2 warmup × 500ms,
#                        5 measurement × 500ms. Use for routine benchmark
#                        evidence and BENCHMARKS.md updates.
#   --long:              Longer confirmation run — 2 forks, 3 warmup × 1s,
#                        5 measurement × 1s. Use for close, surprising, or
#                        especially important comparisons.
#   --smoke:             CI smoke test — 0 forks, 1 warmup × 1s,
#                        1 measurement × 1s. Just verifies benchmarks compile
#                        and run without errors.
#   --first-compile:     Fresh-fork first-compile signal — 10 forks, no warmup,
#                        1 single-shot measurement. Use for cold Unicode table
#                        initialization and short-lived CLI analysis.
#
# Pathological benchmarks (PathologicalBenchmark, PathologicalComparisonBenchmark)
# always run with -f 0 (no forking) because the JDK engine can hang on large
# inputs, making forked JVM processes unrecoverable.
#
# CrosscheckOverheadBenchmark is excluded from default no-argument runs. Run it
# explicitly when working on safere-crosscheck performance.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BENCHMARK_JAR="$SCRIPT_DIR/safere-benchmarks/target/benchmarks.jar"
RE2_SHIM_DIR="$SCRIPT_DIR/safere-ffm-re2/build"
DEFAULT_BENCHMARK_REGEX="^(?!org\\.safere\\.benchmark\\.CrosscheckOverheadBenchmark\\.).*$"

# Empirically selected Java benchmark settings. See
# safere-benchmarks/CONFIGURATION_EVALUATION.md.
STANDARD_OPTS="-f 2 -wi 2 -w 500ms -i 5 -r 500ms"
LONG_OPTS="-f 2 -wi 3 -w 1 -i 5 -r 1"
SMOKE_OPTS="-f 0 -wi 1 -w 1 -i 1 -r 1"
FIRST_COMPILE_OPTS="-f 10 -wi 0 -i 1 -r 1 -bm ss"

# Pathological benchmarks must run without forking (JDK can hang).
PATHOLOGICAL_STANDARD_OPTS="-f 0 -wi 2 -w 500ms -i 5 -r 500ms"
PATHOLOGICAL_LONG_OPTS="-f 0 -wi 3 -w 1 -i 5 -r 1"
PATHOLOGICAL_SMOKE_OPTS="-f 0 -wi 1 -w 1 -i 1 -r 1"

usage() {
  cat <<EOF
Usage:
  ./run-java-benchmarks.sh [--long|--smoke|--first-compile] [BenchmarkClass ...]

Modes:
  default          Standard benchmark run.
  --long           Longer confirmation run for close or important comparisons.
  --smoke          Minimal compile-and-run smoke test.
  --first-compile  Fresh-fork single-shot cold compile signal.
EOF
}

# Parse mode flag.
MODE="standard"
if [ "${1:-}" = "--long" ]; then
  MODE="long"
  shift
elif [ "${1:-}" = "--smoke" ]; then
  MODE="smoke"
  shift
elif [ "${1:-}" = "--first-compile" ]; then
  MODE="first-compile"
  shift
elif [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
elif [[ "${1:-}" == --* ]]; then
  echo "ERROR: unknown mode: $1" >&2
  usage >&2
  exit 2
fi

if [ "$MODE" = "first-compile" ]; then
  JMH_OPTS="$FIRST_COMPILE_OPTS"
  PATHOLOGICAL_JMH_OPTS="$FIRST_COMPILE_OPTS"
  echo "=== First-compile mode (cold Unicode/CLI signal) ==="
elif [ "$MODE" = "smoke" ]; then
  JMH_OPTS="$SMOKE_OPTS"
  PATHOLOGICAL_JMH_OPTS="$PATHOLOGICAL_SMOKE_OPTS"
  echo "=== Smoke-test mode (CI only) ==="
elif [ "$MODE" = "long" ]; then
  JMH_OPTS="$LONG_OPTS"
  PATHOLOGICAL_JMH_OPTS="$PATHOLOGICAL_LONG_OPTS"
  echo "=== Long mode (confirmation run) ==="
else
  JMH_OPTS="$STANDARD_OPTS"
  PATHOLOGICAL_JMH_OPTS="$PATHOLOGICAL_STANDARD_OPTS"
  echo "=== Standard mode ==="
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
  echo "=== Running standard benchmarks ($DEFAULT_BENCHMARK_REGEX) ==="
  java $JVM_ARGS -jar "$BENCHMARK_JAR" -jvmArgs "$JVM_ARGS" $JMH_OPTS "$DEFAULT_BENCHMARK_REGEX"
else
  for bench in "$@"; do
    run_benchmark "$bench"
  done
fi
