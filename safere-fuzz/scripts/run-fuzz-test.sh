#!/bin/bash
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.
#
# Run one or more SafeRE Jazzer fuzz tests in coverage-guided mode.
#
# Usage:
#   safere-fuzz/scripts/run-fuzz-test.sh
#   safere-fuzz/scripts/run-fuzz-test.sh CharacterClassExpressionFuzzer
#   safere-fuzz/scripts/run-fuzz-test.sh --max-duration 10m --keep-going 5 MatchFuzzer UnicodeFuzzer

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SUITE="oss-fuzz"
MAX_DURATION="30m"
KEEP_GOING="10"
TESTS=()
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
LOG_DIR="$REPO_ROOT/safere-fuzz/target/fuzz-logs/$RUN_ID"

usage() {
  cat <<EOF
Usage: $0 [--suite SUITE] [--max-duration DURATION] [--keep-going COUNT] [TEST...]

Options:
  --suite                        oss-fuzz (default), broad, strict, property, robustness, all
  --max-duration, --max_duration  Jazzer max duration per test (default: 30m)
  --keep-going, --keep_going      Number of distinct findings before stopping (default: 10)
  -h, --help                      Show this help

Examples:
  $0
  $0 CharacterClassExpressionFuzzer
  $0 SplitFuzzer#repeatedClassSplits
  $0 --max-duration 10m --keep-going 5 MatchFuzzer UnicodeFuzzer
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --suite)
      if [ $# -lt 2 ]; then
        echo "error: --suite requires a value" >&2
        exit 2
      fi
      SUITE="$2"
      shift 2
      ;;
    --max-duration|--max_duration)
      if [ $# -lt 2 ]; then
        echo "error: $1 requires a value" >&2
        exit 2
      fi
      MAX_DURATION="$2"
      shift 2
      ;;
    --keep-going|--keep_going)
      if [ $# -lt 2 ]; then
        echo "error: $1 requires a value" >&2
        exit 2
      fi
      KEEP_GOING="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      while [ $# -gt 0 ]; do
        TESTS+=("$1")
        shift
      done
      ;;
    -*)
      echo "error: unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
    *)
      TESTS+=("$1")
      shift
      ;;
  esac
done

SELECTED_TARGETS=$(python3 "$SCRIPT_DIR/targets.py" --suite "$SUITE" --format selectors)
if [ "${#TESTS[@]}" -eq 0 ]; then
  while IFS= read -r test_name; do
    TESTS+=("$test_name")
  done <<< "$SELECTED_TARGETS"
fi

RESOLVED_TESTS=()
for test_name in "${TESTS[@]}"; do
  resolved=$(python3 "$SCRIPT_DIR/targets.py" --select "$test_name")
  while IFS= read -r selector; do
    RESOLVED_TESTS+=("$selector")
  done <<< "$resolved"
done
TESTS=("${RESOLVED_TESTS[@]}")

echo "=== Fuzz run configuration ==="
echo "max_duration: $MAX_DURATION"
echo "keep_going: $KEEP_GOING"
echo "surefire_reports: safere-fuzz/target/surefire-reports"
echo "reproducer_path: $LOG_DIR/<target>-reproducers"
echo "fuzz_logs: safere-fuzz/target/fuzz-logs/$RUN_ID"
echo "fuzz targets:"
printf '  %s\n' "${TESTS[@]}"

mkdir -p "$LOG_DIR"
python3 - "$REPO_ROOT" "$LOG_DIR" "$MAX_DURATION" "$KEEP_GOING" "${TESTS[@]}" <<'PYMANIFEST'
import json, pathlib, subprocess, sys
root, logs, duration, keep, *targets = sys.argv[1:]
def output(*args):
    return subprocess.check_output(args, cwd=root, stderr=subprocess.STDOUT, text=True).strip()
manifest = dict(commit=output('git', 'rev-parse', 'HEAD'),
                dirty=output('git', 'status', '--porcelain'),
                jdk=output('java', '-version'), targets=targets,
                max_duration=duration, keep_going=keep)
pathlib.Path(logs, 'run.json').write_text(json.dumps(manifest, indent=2) + '\n')
PYMANIFEST

FUZZ_COMMIT=$(git -C "$REPO_ROOT" rev-parse HEAD)
FAILED_TESTS=()

for test_name in "${TESTS[@]}"; do
  BUILD_ARGS=()
  build_profile=$(python3 "$SCRIPT_DIR/targets.py" --select "$test_name" --format profile)
  if [ "$build_profile" = "work-counters" ]; then
    BUILD_ARGS+=(-Pwork-counters -Dsafere.fuzz.workCounters=true)
  fi
  log_file="$LOG_DIR/$test_name.log"
  mkdir -p "$LOG_DIR/$test_name-reproducers"
  set +e
  {
    echo "=== Running $test_name (max_duration=$MAX_DURATION, keep_going=$KEEP_GOING) ==="
    echo "log_file: $log_file"
    JAZZER_FUZZ=1 mvn -f "$REPO_ROOT/pom.xml" -pl safere-fuzz -am \
      "${BUILD_ARGS[@]}" \
      -Dtest="$test_name" \
      -Dsurefire.failIfNoSpecifiedTests=false \
      -Djazzer.max_duration="$MAX_DURATION" \
      -Djazzer.keep_going="$KEEP_GOING" \
      -Dsafere.fuzz.target="$test_name" \
      -Dsafere.fuzz.commit="$FUZZ_COMMIT" \
      -Dsafere.fuzz.findingsDir="$LOG_DIR/$test_name-findings" \
      -Djazzer.internal.arg.0=jazzer \
      -Djazzer.internal.arg.1="-artifact_prefix=$LOG_DIR/$test_name-reproducers/" \
      -Djazzer.reproducer_path="$LOG_DIR/$test_name-reproducers" \
      test
  } 2>&1 | tee "$log_file"
  test_status="${PIPESTATUS[0]}"
  set -e
  if [ "$test_status" -eq 0 ]; then
    echo "=== Completed $test_name: PASS ===" | tee -a "$log_file"
  else
    echo "=== Completed $test_name: FAIL (exit $test_status) ===" | tee -a "$log_file"
    if rg -q 'AssertionError|FuzzerSecurityIssue|DEDUP_TOKEN|== Java Exception:' "$log_file"; then
      result_kind="FINDINGS"
    else
      result_kind="EXECUTION_FAILURE"
    fi
    echo "result_kind: $result_kind" | tee -a "$log_file"
    FAILED_TESTS+=("$test_name:$test_status:$result_kind")
  fi
done

if [ "${#FAILED_TESTS[@]}" -gt 0 ]; then
  echo
  echo "=== Fuzz run completed with failures ==="
  printf '  %s\n' "${FAILED_TESTS[@]}"
  exit 1
fi

echo
echo "=== Fuzz run completed successfully ==="
