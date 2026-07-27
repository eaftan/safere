#!/bin/bash
# Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.
#
# Run SafeRE JMH benchmarks.
#
# Usage:
#   ./run-java-benchmarks.sh '^org\.safere\.benchmark\.CrossEngineBenchmark\.'
#   ./run-java-benchmarks.sh --long '^org\.safere\.benchmark\.CrossEngineBenchmark\.'
#   ./run-java-benchmarks.sh --smoke '^org\.safere\.benchmark\.CrossEngineBenchmark\.'
#   ./run-java-benchmarks.sh --first-compile \
#     '^org\.safere\.benchmark\.UnicodeFirstCompileBenchmark\.'
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
#
# Arguments after the mode flag are passed directly to JMH as benchmark regex
# filters. Use `--` to pass additional options directly to JMH, for example:
#
#   ./run-java-benchmarks.sh CrossEngineBenchmark.run -- \
#     -p crossEngineTrial=RegexBenchmark.emailFind@safere-utf8

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BENCHMARK_JAR="$SCRIPT_DIR/safere-benchmarks/target/benchmarks.jar"
BENCHMARK_CORPUS="$SCRIPT_DIR/safere-benchmarks/target/benchmark-corpus"
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
  ./run-java-benchmarks.sh [--long|--smoke|--first-compile] [--fastbuild] [JmhBenchmarkRegex ...] [-- JmhArg ...]

Modes:
  default          Standard benchmark run.
  --long           Longer confirmation run for close or important comparisons.
  --smoke          Minimal compile-and-run smoke test.
  --first-compile  Fresh-fork single-shot cold compile signal.

Options:
  --fastbuild      Skip FFM native C++ builds and target only benchmark modules (saves ~1 minute).
EOF
}

# Parse options and arguments.
MODE="standard"
FASTBUILD=false
BENCHMARKS=()
JMH_EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --long)
      MODE="long"
      shift
      ;;
    --smoke)
      MODE="smoke"
      shift
      ;;
    --first-compile)
      MODE="first-compile"
      shift
      ;;
    --fastbuild)
      FASTBUILD=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      JMH_EXTRA_ARGS=("$@")
      set --
      ;;
    --*)
      echo "ERROR: unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
    *)
      BENCHMARKS+=("$1")
      shift
      ;;
  esac
done

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

if [ "$FASTBUILD" = true ]; then
  echo "=== Fast Building safere-benchmarks only ==="
  mvn install \
    -pl safere-benchmarks -am \
    -DskipTests \
    -Dpmd.skip=true \
    -Dcheckstyle.skip=true \
    -Dspotless.check.skip=true \
    -Dmaven.javadoc.skip=true \
    -Dexec.skip=true \
    -q \
    -f "$SCRIPT_DIR/pom.xml"
else
  echo "=== Building safere + benchmark JAR ==="
  mvn install -DskipTests -q -f "$SCRIPT_DIR/pom.xml"
fi

echo "=== Materializing shared benchmark inputs ==="
"$SCRIPT_DIR/materialize-benchmark-inputs.sh" --no-build

# JVM args for FFM native access, native library path, and the resolved corpus.
JVM_ARGS="--enable-native-access=ALL-UNNAMED -Dre2shim.library.path=$RE2_SHIM_DIR -Dsafere.benchmark.corpus=$BENCHMARK_CORPUS"

# JMH discovers benchmark methods statically, while the supported cross-engine
# workload/variant matrix comes from benchmark-data.json and the centralized
# engine registry. Supply the planned trial IDs as one parameter dimension so
# unsupported combinations never enter JMH's Cartesian parameter expansion.
CROSS_ENGINE_TRIALS="$(
  java $JVM_ARGS \
    -cp "$BENCHMARK_JAR" \
    org.safere.benchmark.CrossEngineBenchmarkPlan nanoseconds
)"
CROSS_ENGINE_SCALING_TRIALS="$(
  java $JVM_ARGS \
    -cp "$BENCHMARK_JAR" \
    org.safere.benchmark.CrossEngineBenchmarkPlan microseconds
)"
CROSS_ENGINE_PARAM_ARGS=()
if [[ ! " ${JMH_EXTRA_ARGS[*]-} " =~ [[:space:]]crossEngineTrial= ]]; then
  CROSS_ENGINE_PARAM_ARGS+=(-p "crossEngineTrial=$CROSS_ENGINE_TRIALS")
fi
if [[ ! " ${JMH_EXTRA_ARGS[*]-} " =~ [[:space:]]crossEngineScalingTrial= ]]; then
  CROSS_ENGINE_PARAM_ARGS+=(-p "crossEngineScalingTrial=$CROSS_ENGINE_SCALING_TRIALS")
fi

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
  if [ ${#JMH_EXTRA_ARGS[@]} -gt 0 ]; then
    echo "=== Running $bench ($opts ${JMH_EXTRA_ARGS[*]}) ==="
    java \
      $JVM_ARGS \
      -jar "$BENCHMARK_JAR" \
      -jvmArgs "$JVM_ARGS" \
      $opts \
      "${CROSS_ENGINE_PARAM_ARGS[@]}" \
      "${JMH_EXTRA_ARGS[@]}" \
      "$bench"
  else
    echo "=== Running $bench ($opts) ==="
    java \
      $JVM_ARGS \
      -jar "$BENCHMARK_JAR" \
      -jvmArgs "$JVM_ARGS" \
      $opts \
      "${CROSS_ENGINE_PARAM_ARGS[@]}" \
      "$bench"
  fi
}

if [ ${#BENCHMARKS[@]} -eq 0 ]; then
  echo "=== Running standard benchmarks ($DEFAULT_BENCHMARK_REGEX) ==="
  if [ ${#JMH_EXTRA_ARGS[@]} -gt 0 ]; then
    java \
      $JVM_ARGS \
      -jar "$BENCHMARK_JAR" \
      -jvmArgs "$JVM_ARGS" \
      $JMH_OPTS \
      "${CROSS_ENGINE_PARAM_ARGS[@]}" \
      "${JMH_EXTRA_ARGS[@]}" \
      "$DEFAULT_BENCHMARK_REGEX"
  else
    java \
      $JVM_ARGS \
      -jar "$BENCHMARK_JAR" \
      -jvmArgs "$JVM_ARGS" \
      $JMH_OPTS \
      "${CROSS_ENGINE_PARAM_ARGS[@]}" \
      "$DEFAULT_BENCHMARK_REGEX"
  fi
else
  for bench in "${BENCHMARKS[@]}"; do
    run_benchmark "$bench"
  done
fi
