#!/bin/bash
# Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.
#
# Compare JMH benchmarks between two git branches or commits.
#
# Usage:
#   ./safere-benchmarks/scripts/compare-branch.sh [OPTIONS] [FILTER]
#
# Options:
#   --baseline <ref>    Baseline git ref to compare against (default: main)
#   --current <ref>     Target git ref to evaluate (default: current branch)
#   --long              Use the longer confirmation benchmark configuration
#   --grouped-tables    Emit separate tables grouped by benchmark class
#   --no-speedup        Do not include speedup ratio column
#   --vector            Enable the experimental vector provider for both revisions
#
# Examples:
#   ./safere-benchmarks/scripts/compare-branch.sh --baseline main
#   ./safere-benchmarks/scripts/compare-branch.sh '(jsonBlock|templateTagMatch)'

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

cd "$REPO_ROOT"

ORIGINAL_BRANCH="$(git symbolic-ref --quiet --short HEAD || git rev-parse HEAD)"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/compare_benchmarks_XXXXXX")"
COMPARE_PY="$TMP_DIR/compare-benchmarks.py"
cp "$SCRIPT_DIR/compare-benchmarks.py" "$COMPARE_PY"

cleanup() {
  rm -rf "$TMP_DIR"
  current_head="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || git rev-parse HEAD)"
  if [ "$current_head" != "$ORIGINAL_BRANCH" ]; then
    git checkout -q "$ORIGINAL_BRANCH" || true
  fi
}
trap cleanup EXIT

if [ -n "$(git status --porcelain)" ]; then
  echo "Error: compare-branch.sh requires a clean working tree" >&2
  exit 1
fi

BASELINE_REF="main"
CURRENT_REF="$ORIGINAL_BRANCH"
SINGLE_TABLE=true
SHOW_SPEEDUP=true
FORCE_VECTOR=false
LONG_MODE=false
FILTER=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --baseline)
      BASELINE_REF="$2"
      shift 2
      ;;
    --current)
      CURRENT_REF="$2"
      shift 2
      ;;
    --single-table)
      SINGLE_TABLE=true
      shift
      ;;
    --grouped-tables|--no-single-table)
      SINGLE_TABLE=false
      shift
      ;;
    --no-speedup)
      SHOW_SPEEDUP=false
      shift
      ;;
    --vector)
      FORCE_VECTOR=true
      shift
      ;;
    --long)
      LONG_MODE=true
      shift
      ;;
    -*)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
    *)
      FILTER="$1"
      shift
      ;;
  esac
done

if [ -z "$FILTER" ]; then
  FILTER='(SingleCharClassBenchmark\.findDigitAbsent|RealWorldRegexBenchmark\.runBenchmark\.(bracketCitation\.noMatch|mapFieldPath\.(noMatch|match)|caseInsensitiveKeyword\.noMatch|customProtocolLink\.noMatch|metadataBlock\.(noMatch|match)|jsonBlock\.match|templateTagMatch\.match|fixedAnchorLog\.(noMatch|match)|multiInfixLog\.(noMatch|match)|multiInfixLogCaptures\.(noMatch|match)|unanchoredLogCaptures\.(noMatch|match)|multiClauseSequence\.(noMatch|match)|structuredJsonPath\.(noMatch|match)|uuidExtraction\.(noMatch|match)|isoTimestampLog\.(noMatch|match)|factoredAlternation\.(noMatch|match)|factoredProtocol\.(noMatch|match))|RegexBenchmark\.(literalMatch|charClassMatch|captureGroups|emailFind)|ApplicationBenchmark\.(uuidValidation|secretRedaction)).*@safere-string'
fi

# Resolve relative refs while HEAD still identifies the caller's revision.
BASELINE_COMMIT="$(git rev-parse --verify "${BASELINE_REF}^{commit}")"
CURRENT_COMMIT="$(git rev-parse --verify "${CURRENT_REF}^{commit}")"
BASELINE_DATA="$(git rev-parse "${BASELINE_COMMIT}:safere-benchmarks/benchmark-data.json")"
CURRENT_DATA="$(git rev-parse "${CURRENT_COMMIT}:safere-benchmarks/benchmark-data.json")"
if [ "$BASELINE_DATA" != "$CURRENT_DATA" ]; then
  echo "Error: benchmark-data.json differs between the requested revisions" >&2
  echo "Refusing to compare scores from different workload definitions." >&2
  exit 1
fi
BASELINE_RUNNER="$(git rev-parse "${BASELINE_COMMIT}:run-java-benchmarks.sh")"
CURRENT_RUNNER="$(git rev-parse "${CURRENT_COMMIT}:run-java-benchmarks.sh")"
if [ "$BASELINE_RUNNER" != "$CURRENT_RUNNER" ]; then
  echo "Error: run-java-benchmarks.sh differs between the requested revisions" >&2
  echo "Refusing to compare results collected with different benchmark settings." >&2
  exit 1
fi
if ! git diff --quiet "$BASELINE_COMMIT" "$CURRENT_COMMIT" -- \
  pom.xml safere-benchmarks materialize-benchmark-inputs.sh run-java-benchmarks.sh \
  ':(exclude)safere-benchmarks/scripts/compare-benchmarks.py' \
  ':(exclude)safere-benchmarks/scripts/compare-branch.sh' \
  ':(exclude)safere-benchmarks/scripts/test_compare_benchmarks.py'; then
  echo "Error: the benchmark harness or its build definition differs between revisions" >&2
  echo "Refusing to attribute harness changes to library performance." >&2
  exit 1
fi

VECTOR_JVM_ARGS="--add-modules=jdk.incubator.vector --add-opens=java.base/java.lang=ALL-UNNAMED -Dorg.safere.experimental.vectorScanProvider=vector"

# 1. Checkout baseline and discover trials
echo "=== Discovering benchmark trials on $BASELINE_REF / $CURRENT_REF ==="
mvn -pl safere-benchmarks -am clean -q
git checkout -q --detach "$BASELINE_COMMIT"
mvn -pl safere-benchmarks -am clean -q
mvn -pl safere-benchmarks -am package \
  -DskipTests \
  -Dpmd.skip=true \
  -Dspotless.check.skip=true \
  -Dcheckstyle.skip=true \
  -Dmaven.javadoc.skip=true \
  -Dexec.skip=true \
  -q
./materialize-benchmark-inputs.sh --no-build

TRIALS="$(java \
  -Dsafere.benchmark.corpus=safere-benchmarks/target/benchmark-corpus \
  -cp safere-benchmarks/target/benchmarks.jar \
  org.safere.benchmark.CrossEngineBenchmarkPlan nanoseconds \
  | tr ',' '\n' \
  | grep -E "$FILTER" \
  | paste -sd, - || true)"

if [ -z "$TRIALS" ]; then
  echo "Error: no baseline trials matched filter: $FILTER" >&2
  echo "Current-only benchmark definitions cannot be compared with this script." >&2
  exit 1
fi

VARIANT_COUNT="$(printf '%s' "$TRIALS" \
  | tr ',' '\n' \
  | sed -n -E 's/.*@([^@]+)$/\1/p' \
  | sort -u \
  | wc -l)"
if [ "$VARIANT_COUNT" -ne 1 ]; then
  echo "Error: filter must select exactly one execution variant (for example, @safere-string)" >&2
  exit 1
fi
VARIANT="$(printf '%s' "$TRIALS" | sed -n -E 's/.*@([^@,]+).*/\1/p')"
case "$VARIANT" in
  safere-string|safere-utf8) ;;
  *)
    echo "Error: branch comparisons support only SafeRE execution variants" >&2
    exit 1
    ;;
esac

BASELINE_TXT="$TMP_DIR/baseline.txt"
CURRENT_TXT="$TMP_DIR/current.txt"
BASELINE_JSONL="$TMP_DIR/baseline.jsonl"
CURRENT_JSONL="$TMP_DIR/current.jsonl"

VECTOR_ARGS=()
if [ "$FORCE_VECTOR" = true ]; then
  VECTOR_ARGS=(-jvmArgsPrepend "$VECTOR_JVM_ARGS")
fi
MODE_ARGS=()
if [ "$LONG_MODE" = true ]; then
  MODE_ARGS=(--long)
fi

# 2. Run Baseline
echo "=== Running Baseline: $BASELINE_REF ==="
git checkout -q --detach "$BASELINE_COMMIT"
./run-java-benchmarks.sh ${MODE_ARGS[@]+"${MODE_ARGS[@]}"} --fastbuild CrossEngineBenchmark.run -- \
  -p crossEngineTrial="$TRIALS" \
  ${VECTOR_ARGS[@]+"${VECTOR_ARGS[@]}"} | tee "$BASELINE_TXT"
python3 "$COMPARE_PY" --jmh "$BASELINE_TXT" --output-jsonl "$BASELINE_JSONL"
sed -E 's/"engine"[[:space:]]*:[[:space:]]*"[^"]+"/"engine":"baseline"/' "$BASELINE_JSONL" > "$TMP_DIR/baseline_norm.jsonl" && mv "$TMP_DIR/baseline_norm.jsonl" "$BASELINE_JSONL"

# 3. Run Current
echo "=== Running Current: $CURRENT_REF ==="
mvn -pl safere-benchmarks -am clean -q
git checkout -q --detach "$CURRENT_COMMIT"
mvn -pl safere-benchmarks -am clean -q
./run-java-benchmarks.sh ${MODE_ARGS[@]+"${MODE_ARGS[@]}"} --fastbuild CrossEngineBenchmark.run -- \
  -p crossEngineTrial="$TRIALS" \
  ${VECTOR_ARGS[@]+"${VECTOR_ARGS[@]}"} | tee "$CURRENT_TXT"
python3 "$COMPARE_PY" --jmh "$CURRENT_TXT" --output-jsonl "$CURRENT_JSONL"
sed -E 's/"engine"[[:space:]]*:[[:space:]]*"[^"]+"/"engine":"current"/' "$CURRENT_JSONL" > "$TMP_DIR/current_norm.jsonl" && mv "$TMP_DIR/current_norm.jsonl" "$CURRENT_JSONL"

# 4. Render Table
echo ""
echo "=== Benchmark Comparison Results ==="
EXTRA_ARGS=()
if [ "$SHOW_SPEEDUP" = true ]; then
  EXTRA_ARGS+=(--speedup)
fi
if [ "$SINGLE_TABLE" = true ]; then
  EXTRA_ARGS+=(--single-table)
fi

python3 "$COMPARE_PY" \
  --json "$BASELINE_JSONL" "$CURRENT_JSONL" \
  --engines baseline,current \
  ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}
