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

if ! command -v python3 >/dev/null 2>&1; then
  echo "error: python3 is required for target selection and finding reports" >&2
  exit 2
fi

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
RUN_STATUS=0
SUMMARY_LINES=()

for test_name in "${TESTS[@]}"; do
  BUILD_ARGS=(-f "$REPO_ROOT/pom.xml" -pl safere-fuzz -am)
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
    JAZZER_FUZZ=1 mvn "${BUILD_ARGS[@]}" \
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
  pipeline_status=("${PIPESTATUS[@]}")
  test_status="${pipeline_status[0]}"
  set -e
  process_status="SUCCEEDED"
  if [ "$test_status" -ne 0 ]; then
    process_status="FAILED"
    RUN_STATUS=1
  fi
  # Inspect evidence even after exit 0: Jazzer keep_going can report findings and still succeed.
  # A broken classifier or log capture must not turn unknown evidence into a clean result.
  if [ "${pipeline_status[1]}" -eq 0 ] && \
      result=$(python3 "$SCRIPT_DIR/report-fuzz-result.py" "$LOG_DIR" "$test_name" "$test_status"); then
    if [[ "$result" == *"findings=UNTRIAGED"* || "$result" == *"reporting=FAILED"* ]]; then
      RUN_STATUS=1
    fi
  else
    result="$test_name: process=$process_status (exit $test_status); findings=UNKNOWN; reporting=FAILED"
    RUN_STATUS=1
    # Evidence storage may remain unwritable. Preserve console/Actions reporting and keep going.
    if ! printf '=== Completed %s ===\n' "$result" >> "$log_file"; then
      echo "error: could not append target summary to $log_file" >&2
    fi
  fi
  echo "=== Completed $result ==="
  SUMMARY_LINES+=("$result")
done

SUMMARY_REPORTING="OK"
if ! {
  echo "=== Fuzz run summary ==="
  echo "Findings are untriaged and may include known intentional differences; they are not confirmed new bugs."
  echo "Use the divergence workflow and intentional reproduction registry to classify exact cases."
  echo "Process failure is reported independently; a nonzero exit can also be caused by findings."
  printf '  %s\n' "${SUMMARY_LINES[@]}"
} | tee "$LOG_DIR/summary.txt"; then
  RUN_STATUS=1
  SUMMARY_REPORTING="FAILED (could not write archived summary)"
  echo "run_reporting: $SUMMARY_REPORTING"
fi

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  if {
    echo '### Fuzz run summary'
    echo
    echo 'Findings are **untriaged**, may include known intentional differences, and require review.'
    echo 'No findings are automatically classified as known or suppressed. Nonzero process exits may be caused by findings.'
    echo
    echo '```text'
    printf '%s\n' "${SUMMARY_LINES[@]}"
    echo '```'
    echo
    echo "See the run's logs, result JSON, and reproducers in the fuzz evidence artifact."
    echo
    echo "Run summary reporting: $SUMMARY_REPORTING"
  } >> "$GITHUB_STEP_SUMMARY"; then
    :
  else
    RUN_STATUS=1
    echo "run_reporting: FAILED (could not write Actions summary)"
  fi
fi

if [ "$RUN_STATUS" -eq 0 ]; then
  echo "=== Fuzz run completed without observed findings or process/reporting failures ==="
else
  echo "=== Fuzz run requires attention (findings, process failures, or reporting failures) ==="
fi

exit "$RUN_STATUS"
