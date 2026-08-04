#!/bin/bash
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.

# Runs the optional Vector API benchmark prototypes.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BENCHMARK_JAR="$SCRIPT_DIR/safere-vector-benchmarks/target/benchmarks.jar"
BENCHMARK_CORPUS="$SCRIPT_DIR/safere-benchmarks/target/benchmark-corpus"
MODE="standard"
FASTBUILD=false
NO_BUILD=false
END_TO_END=false
PROVIDER="both"
TRIAL_OVERRIDE=""
METHODS="safeRe|swar|swarProvider|vector|vectorProvider|vectorBounds|vectorCursor"
JMH_EXTRA_ARGS=()

while [ "$#" -gt 0 ]; do
  case "$1" in
    --smoke)
      MODE="smoke"
      shift
      ;;
    --long)
      MODE="long"
      shift
      ;;
    --fastbuild)
      FASTBUILD=true
      shift
      ;;
    --no-build)
      NO_BUILD=true
      shift
      ;;
    --end-to-end)
      END_TO_END=true
      shift
      ;;
    --provider)
      if [ "$#" -lt 2 ] || [[ ! "$2" =~ ^(swar|vector|both)$ ]]; then
        echo "ERROR: --provider requires swar, vector, or both" >&2
        exit 2
      fi
      PROVIDER="$2"
      shift 2
      ;;
    --trials)
      if [ "$#" -lt 2 ]; then
        echo "ERROR: --trials requires a comma-separated trial list" >&2
        exit 2
      fi
      TRIAL_OVERRIDE="$2"
      shift 2
      ;;
    --methods)
      if [ "$#" -lt 2 ]; then
        echo "ERROR: --methods requires a pipe-separated method list" >&2
        exit 2
      fi
      METHODS="$2"
      shift 2
      ;;
    --)
      shift
      JMH_EXTRA_ARGS=("$@")
      set --
      ;;
    *)
      echo "Usage: $0 [--smoke|--long] [--fastbuild|--no-build] [--end-to-end] [--provider swar|vector|both] [--trials LIST] [--methods LIST] [-- JMH arguments]" >&2
      exit 2
      ;;
  esac
done

JAVA_FEATURE="$(java -XshowSettings:properties -version 2>&1 \
  | awk -F= '/java.specification.version/ {gsub(/ /, "", $2); print $2}')"
if [ "$JAVA_FEATURE" -lt 21 ] || [ "$JAVA_FEATURE" -gt 26 ]; then
  echo "ERROR: These Vector benchmarks require a supported JDK (21 through 26); found JDK $JAVA_FEATURE" >&2
  exit 1
fi

if [ "$NO_BUILD" = false ]; then
  echo "=== Building SafeRE benchmark inputs ==="
  if [ "$FASTBUILD" = true ]; then
    mvn -pl safere-benchmarks -am install \
      -Dmaven.test.skip=true \
      -Dexec.skip=true \
      -Dmaven.javadoc.skip=true \
      -Dpmd.skip=true \
      -Dspotless.check.skip=true \
      -q \
      -f "$SCRIPT_DIR/pom.xml"
  else
    mvn -pl safere-benchmarks -am install \
      -Dmaven.test.skip=true \
      -Dpmd.skip=true \
      -Dspotless.check.skip=true \
      -q \
      -f "$SCRIPT_DIR/pom.xml"
  fi

  echo "=== Building Vector benchmark JAR ==="
  mvn clean package \
    -Dmaven.test.skip=true \
    -Dpmd.skip=true \
    -Dspotless.check.skip=true \
    -q \
    -f "$SCRIPT_DIR/safere-vector-benchmarks/pom.xml"

  echo "=== Materializing shared benchmark inputs ==="
  "$SCRIPT_DIR/materialize-benchmark-inputs.sh" --no-build
elif [ ! -f "$BENCHMARK_JAR" ] || [ ! -f "$BENCHMARK_CORPUS/manifest.json" ]; then
  echo "ERROR: --no-build requires an existing benchmark JAR and materialized corpus" >&2
  exit 1
fi

JVM_ARGS=(
  --add-modules=jdk.incubator.vector
  "-Dsafere.benchmark.corpus=$BENCHMARK_CORPUS"
)
echo "=== Checking packaged provider selection ==="
java -cp "$BENCHMARK_JAR" org.safere.vector.benchmark.VectorProviderSmoke
java \
  --add-modules=jdk.incubator.vector \
  -Dorg.safere.experimental.vectorScanProvider=vector \
  -cp "$BENCHMARK_JAR" \
  org.safere.vector.benchmark.VectorProviderSmoke

if [ "$MODE" = "smoke" ]; then
  PROFILE="smoke"
  JMH_OPTS="-f 1 -wi 1 -w 1 -i 1 -r 1"
elif [ "$MODE" = "long" ]; then
  PROFILE="standard"
  JMH_OPTS="-f 2 -wi 3 -w 1 -i 5 -r 1"
else
  PROFILE="standard"
  JMH_OPTS="-f 2 -wi 2 -w 500ms -i 5 -r 500ms"
fi

TRIALS="$(java "${JVM_ARGS[@]}" \
  -cp "$BENCHMARK_JAR" \
  org.safere.vector.benchmark.VectorScanTrialPlan "$PROFILE")"
if [ -n "$TRIAL_OVERRIDE" ]; then
  TRIALS="$TRIAL_OVERRIDE"
fi
if [ "$END_TO_END" = true ]; then
  END_TO_END_TRIALS=""
  IFS=',' read -ra trial_list <<< "$TRIALS"
  for trial in "${trial_list[@]}"; do
    if [[ "$trial" != singleton/* ]]; then
      if [ -n "$END_TO_END_TRIALS" ]; then
        END_TO_END_TRIALS+=","
      fi
      END_TO_END_TRIALS+="$trial"
    fi
  done
  if [ -z "$END_TO_END_TRIALS" ]; then
    echo "ERROR: End-to-end provider benchmarks require at least one non-singleton trial" >&2
    exit 2
  fi
  TRIALS="$END_TO_END_TRIALS"
fi

run_benchmarks() {
  local provider="$1"
  local benchmark_jvm_args=("${JVM_ARGS[@]}")
  if [ "$provider" = "vector" ]; then
    benchmark_jvm_args+=("-Dorg.safere.experimental.vectorScanProvider=vector")
  fi
  local fork_jvm_args=""
  local argument
  for argument in "${benchmark_jvm_args[@]}"; do
    if [[ "$argument" == *\"* ]]; then
      echo "ERROR: JMH JVM arguments cannot contain a double quote: $argument" >&2
      return 2
    fi
    fork_jvm_args+="\"$argument\" "
  done
  echo "=== Running Vector scan benchmarks ($MODE, provider=$provider) ==="
  local command=(java "${benchmark_jvm_args[@]}" -jar "$BENCHMARK_JAR"
    -jvmArgs "$fork_jvm_args"
    $JMH_OPTS
    -p "vectorScanTrial=$TRIALS")
  if [ ${#JMH_EXTRA_ARGS[@]} -gt 0 ]; then
    command+=("${JMH_EXTRA_ARGS[@]}")
  fi
  if [ "$END_TO_END" = true ]; then
    command+=(-p "scanProvider=$provider")
    command+=('^org\.safere\.vector\.benchmark\.Utf8VectorEndToEndProviderBenchmark\.safeReFind$')
  else
    command+=("^org\.safere\.vector\.benchmark\.Utf8VectorScanBenchmark\.($METHODS)$")
  fi
  "${command[@]}"
}

if [ "$END_TO_END" = true ] && [ "$PROVIDER" = "both" ]; then
  run_benchmarks swar
  run_benchmarks vector
elif [ "$END_TO_END" = true ]; then
  run_benchmarks "$PROVIDER"
else
  run_benchmarks swar
fi
