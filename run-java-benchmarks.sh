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
#   ./run-java-benchmarks.sh --declared
#   ./run-java-benchmarks.sh --first-compile \
#     '^org\.safere\.benchmark\.CrossEngineColdStartBenchmark\.'
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
#   --declared:          Discover and run every generic runner and trial from
#                        the declarative collection plan.
#
# Workloads that declare the noFork constraint run through the generic
# CrossEngineNoForkBenchmark entry point with -f 0.
#
# CrosscheckOverheadBenchmark is excluded from default no-argument runs. Run it
# explicitly when working on safere-crosscheck performance.
#
# Arguments after the mode flag are passed directly to JMH as benchmark regex
# filters. Use `--` to pass additional options directly to JMH, for example:
#
#   ./run-java-benchmarks.sh CrossEngineBenchmark.run -- \
#     -p crossEngineTrial=ExampleSuite.find@safere-utf8

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BENCHMARK_JAR="$SCRIPT_DIR/safere-benchmarks/target/benchmarks.jar"
BENCHMARK_CORPUS="$SCRIPT_DIR/safere-benchmarks/target/benchmark-corpus"
RE2_SHIM_DIR="$SCRIPT_DIR/safere-ffm-re2/build"
DEFAULT_BENCHMARK_REGEX="^(?!org\\.safere\\.benchmark\\.(CrosscheckOverheadBenchmark|CrossEngineNoForkBenchmark|CrossEngineColdStartBenchmark)\\.).*$"

# Empirically selected Java benchmark settings. See
# safere-benchmarks/CONFIGURATION_EVALUATION.md.
STANDARD_OPTS="-f 2 -wi 2 -w 500ms -i 5 -r 500ms"
LONG_OPTS="-f 2 -wi 3 -w 1 -i 5 -r 1"
SMOKE_OPTS="-f 0 -wi 1 -w 1 -i 1 -r 1"
FIRST_COMPILE_OPTS="-f 10 -wi 0 -i 1 -r 1 -bm ss"
COLD_START_SMOKE_OPTS="-f 1 -wi 0 -i 1 -r 1 -bm ss"

# Generic options for workloads whose declarations require no-fork execution.
NO_FORK_STANDARD_OPTS="-f 0 -wi 2 -w 500ms -i 5 -r 500ms"
NO_FORK_LONG_OPTS="-f 0 -wi 3 -w 1 -i 5 -r 1"
NO_FORK_SMOKE_OPTS="-f 0 -wi 1 -w 1 -i 1 -r 1"

usage() {
  cat <<EOF
Usage:
  ./run-java-benchmarks.sh [--long|--smoke|--first-compile] [--declared] [--fastbuild] [JmhBenchmarkRegex ...] [-- JmhArg ...]

Modes:
  default          Standard benchmark run.
  --long           Longer confirmation run for close or important comparisons.
  --smoke          Minimal compile-and-run smoke test.
  --first-compile  Fresh-fork single-shot cold compile signal.
  --declared       Discover generic runners and trials from the benchmark plan.

Options:
  --provider default|vector  Select a provider (declared runs include both by default).
  --fastbuild      Skip FFM native C++ builds and target only benchmark modules (saves ~1 minute).
EOF
}

# Parse options and arguments.
MODE="standard"
FASTBUILD=false
DECLARED=false
SCAN_PROVIDER="default"
PROVIDER_SELECTED=false
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

# Normalize attached JMH parameter forms so selection and rejection use one syntax.
# Keep profiler options intact; -prof=gc is not a parameter override.
NORMALIZED_JMH_ARGS=()
for argument in ${JMH_EXTRA_ARGS[@]+"${JMH_EXTRA_ARGS[@]}"}; do
  case "$argument" in
    -prof*) NORMALIZED_JMH_ARGS+=("$argument") ;;
    -p=*|-p?*=*)
      parameter_override="${argument#-p}"
      NORMALIZED_JMH_ARGS+=(-p "${parameter_override#=}")
      ;;
    *) NORMALIZED_JMH_ARGS+=("$argument") ;;
  esac
done
JMH_EXTRA_ARGS=(${NORMALIZED_JMH_ARGS[@]+"${NORMALIZED_JMH_ARGS[@]}"})

# Declared trials are already partitioned by provider. JMH combines repeated
# parameter values, so an override could put a default trial in a Vector JVM.
if [ "$DECLARED" = true ]; then
  for argument in ${JMH_EXTRA_ARGS[@]+"${JMH_EXTRA_ARGS[@]}"}; do
    case "$argument" in
      -p|-p=*)
        echo "ERROR: --declared cannot be combined with JMH -p overrides; use a focused run without --declared" >&2
        exit 2
        ;;
    esac
  done
fi

if [ "$MODE" = "first-compile" ]; then
  JMH_OPTS="$FIRST_COMPILE_OPTS"
  NO_FORK_JMH_OPTS="$FIRST_COMPILE_OPTS"
  COLD_START_JMH_OPTS="$FIRST_COMPILE_OPTS"
  echo "=== First-compile mode (cold Unicode/CLI signal) ==="
elif [ "$MODE" = "smoke" ]; then
  JMH_OPTS="$SMOKE_OPTS"
  NO_FORK_JMH_OPTS="$NO_FORK_SMOKE_OPTS"
  COLD_START_JMH_OPTS="$COLD_START_SMOKE_OPTS"
  echo "=== Smoke-test mode (CI only) ==="
elif [ "$MODE" = "long" ]; then
  JMH_OPTS="$LONG_OPTS"
  NO_FORK_JMH_OPTS="$NO_FORK_LONG_OPTS"
  COLD_START_JMH_OPTS="$FIRST_COMPILE_OPTS"
  echo "=== Long mode (confirmation run) ==="
else
  JMH_OPTS="$STANDARD_OPTS"
  NO_FORK_JMH_OPTS="$NO_FORK_STANDARD_OPTS"
  COLD_START_JMH_OPTS="$FIRST_COMPILE_OPTS"
  echo "=== Standard mode ==="
fi

if [ "$FASTBUILD" = true ]; then
  echo "=== Fast Building safere-benchmarks only ==="
  mvn -pl safere-benchmarks clean -q -f "$SCRIPT_DIR/pom.xml"
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
  mvn -pl safere-benchmarks clean -q -f "$SCRIPT_DIR/pom.xml"
  mvn install -DskipTests -q -f "$SCRIPT_DIR/pom.xml"
fi

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
GENERATED_JMH_ARGUMENT_FILE="$(mktemp "${TMPDIR:-/tmp}/safere-jmh-args.XXXXXX")"
trap 'rm -f -- "$GENERATED_JMH_ARGUMENT_FILE"' EXIT

if [ "$DECLARED" = true ]; then
  COLLECTION_QUERY=(execution-runners)
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
    runner_opts="$JMH_OPTS"
    if [ "$profile" = "noFork" ]; then
      runner_opts="$NO_FORK_JMH_OPTS"
    elif [ "$profile" = "coldStart" ]; then
      runner_opts="$COLD_START_JMH_OPTS"
    fi
    if [ "$profile" = "coldStart" ]; then
      IFS=',' read -r -a cold_start_trials <<< "$trial_ids"
      for trial in "${cold_start_trials[@]}"; do
        echo "=== Running declared $benchmark ($profile; $runner_opts; isolated trial $trial) ==="
        RUNNER_COMMAND=(java \
          $JVM_ARGS \
          -jar "$BENCHMARK_JAR" \
          -jvmArgs "$JVM_ARGS" \
          $runner_opts \
          -p "$parameter=$trial")
        if [ ${#JMH_EXTRA_ARGS[@]} -gt 0 ]; then
          RUNNER_COMMAND+=(${JMH_EXTRA_ARGS[@]+"${JMH_EXTRA_ARGS[@]}"})
        fi
        RUNNER_COMMAND+=("^${benchmark//./\\.}$")
        "${RUNNER_COMMAND[@]}"
      done
      continue
    fi
    echo "=== Running declared $benchmark ($profile; $runner_opts) ==="
    DECLARED_ARGUMENT_QUERY=(declared-runner-arguments runners "$benchmark" "$BENCHMARK_JAR")
    if [ "$MODE" = "smoke" ]; then
      DECLARED_ARGUMENT_QUERY+=(--smoke)
    fi
    java $JVM_ARGS \
      -cp "$BENCHMARK_JAR" \
      org.safere.benchmark.BenchmarkCollectionPlan \
      "${DECLARED_ARGUMENT_QUERY[@]}" \
      > "$GENERATED_JMH_ARGUMENT_FILE"
    RUNNER_COMMAND=(java \
      $JVM_ARGS \
      "@$GENERATED_JMH_ARGUMENT_FILE" \
      -jvmArgs "$JVM_ARGS" \
      $runner_opts)
    if [ ${#JMH_EXTRA_ARGS[@]} -gt 0 ]; then
      RUNNER_COMMAND+=(${JMH_EXTRA_ARGS[@]+"${JMH_EXTRA_ARGS[@]}"})
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
    echo "ERROR: no declared benchmark runner matches the requested filters" >&2
    exit 1
  fi
  exit 0
fi

# JMH discovers benchmark methods statically, while the supported cross-engine
# workload/variant matrix comes from benchmark-data.json and the centralized
# engine registry. Supply only the planned parameters for runners selected by
# each JMH filter. Store generated parameters in a Java argument file because a
# complete trial list can exceed the operating system's per-argument limit.
write_generated_jmh_arguments() {
  local bench="$1"
  local overridden_parameters=()
  local index=0
  while [ "$index" -lt "${#JMH_EXTRA_ARGS[@]}" ]; do
    if [ "${JMH_EXTRA_ARGS[$index]}" = "-p" ] \
      && [ "$((index + 1))" -lt "${#JMH_EXTRA_ARGS[@]}" ]; then
      overridden_parameters+=("${JMH_EXTRA_ARGS[$((index + 1))]%%=*}")
      index=$((index + 2))
    else
      index=$((index + 1))
    fi
  done
  java $JVM_ARGS \
    -cp "$BENCHMARK_JAR" \
    org.safere.benchmark.BenchmarkCollectionPlan \
    runner-arguments "$bench" "$BENCHMARK_JAR" "${overridden_parameters[@]}" \
    > "$GENERATED_JMH_ARGUMENT_FILE"
}


run_benchmark() {
  local bench="$1"
  local opts="$JMH_OPTS"
  case "$bench" in
    *CrossEngineNoForkBenchmark*) opts="$NO_FORK_JMH_OPTS" ;;
    *CrossEngineColdStartBenchmark*)
      local trials
      trials="$(
        java $JVM_ARGS \
          -cp "$BENCHMARK_JAR" \
          org.safere.benchmark.CrossEngineBenchmarkPlan cold-start
      )"
      local extra_args=()
      local index=0
      while [ "$index" -lt "${#JMH_EXTRA_ARGS[@]}" ]; do
        if [ "${JMH_EXTRA_ARGS[$index]}" = "-p" ] \
          && [ "$((index + 1))" -lt "${#JMH_EXTRA_ARGS[@]}" ] \
          && [[ "${JMH_EXTRA_ARGS[$((index + 1))]}" = crossEngineColdStartTrial=* ]]; then
          trials="${JMH_EXTRA_ARGS[$((index + 1))]#crossEngineColdStartTrial=}"
          index=$((index + 2))
        else
          extra_args+=("${JMH_EXTRA_ARGS[$index]}")
          index=$((index + 1))
        fi
      done
      IFS=',' read -r -a cold_start_trials <<< "$trials"
      for trial in "${cold_start_trials[@]}"; do
        echo "=== Running $bench ($COLD_START_JMH_OPTS; isolated trial $trial) ==="
        if [ ${#extra_args[@]} -gt 0 ]; then
          java \
            $JVM_ARGS \
            -jar "$BENCHMARK_JAR" \
            -jvmArgs "$JVM_ARGS" \
            $COLD_START_JMH_OPTS \
            -p "crossEngineColdStartTrial=$trial" \
            "${extra_args[@]}" \
            "$bench"
        else
          java \
            $JVM_ARGS \
            -jar "$BENCHMARK_JAR" \
            -jvmArgs "$JVM_ARGS" \
            $COLD_START_JMH_OPTS \
            -p "crossEngineColdStartTrial=$trial" \
            "$bench"
        fi
      done
      return
      ;;
  esac
  write_generated_jmh_arguments "$bench"
  if [ ${#JMH_EXTRA_ARGS[@]} -gt 0 ]; then
    echo "=== Running $bench ($opts ${JMH_EXTRA_ARGS[*]}) ==="
    java \
      $JVM_ARGS \
      "@$GENERATED_JMH_ARGUMENT_FILE" \
      -jvmArgs "$JVM_ARGS" \
      $opts \
      ${JMH_EXTRA_ARGS[@]+"${JMH_EXTRA_ARGS[@]}"} \
      "$bench"
  else
    echo "=== Running $bench ($opts) ==="
    java \
      $JVM_ARGS \
      "@$GENERATED_JMH_ARGUMENT_FILE" \
      -jvmArgs "$JVM_ARGS" \
      $opts \
      "$bench"
  fi
}

if [ ${#BENCHMARKS[@]} -eq 0 ]; then
  echo "=== Running standard benchmarks ($DEFAULT_BENCHMARK_REGEX) ==="
  write_generated_jmh_arguments "$DEFAULT_BENCHMARK_REGEX"
  if [ ${#JMH_EXTRA_ARGS[@]} -gt 0 ]; then
    java \
      $JVM_ARGS \
      "@$GENERATED_JMH_ARGUMENT_FILE" \
      -jvmArgs "$JVM_ARGS" \
      $JMH_OPTS \
      ${JMH_EXTRA_ARGS[@]+"${JMH_EXTRA_ARGS[@]}"} \
      "$DEFAULT_BENCHMARK_REGEX"
  else
    java \
      $JVM_ARGS \
      "@$GENERATED_JMH_ARGUMENT_FILE" \
      -jvmArgs "$JVM_ARGS" \
      $JMH_OPTS \
      "$DEFAULT_BENCHMARK_REGEX"
  fi
else
  for bench in "${BENCHMARKS[@]}"; do
    run_benchmark "$bench"
  done
fi
