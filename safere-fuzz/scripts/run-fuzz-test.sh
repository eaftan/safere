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
FUZZ_TARGET_DIR="$REPO_ROOT/safere-fuzz/src/test/java/org/safere/fuzz"
MAX_DURATION="30m"
KEEP_GOING="10"
TESTS=()
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
LOG_DIR="$REPO_ROOT/safere-fuzz/target/fuzz-logs/$RUN_ID"

usage() {
  cat <<EOF
Usage: $0 [--max-duration DURATION] [--keep-going COUNT] [TEST...]

Options:
  --max-duration, --max_duration  Jazzer max duration per test (default: 30m)
  --keep-going, --keep_going      Number of distinct findings before stopping (default: 10)
  -h, --help                      Show this help

Examples:
  $0
  $0 CharacterClassExpressionFuzzer
  $0 --max-duration 10m --keep-going 5 MatchFuzzer UnicodeFuzzer
EOF
}

valid_fuzz_targets() {
  find "$FUZZ_TARGET_DIR" -maxdepth 1 -type f -name '*Fuzzer.java' -printf '%f\n' \
    | sed 's/\.java$//' \
    | sort
}

is_valid_fuzz_target() {
  local test_name="$1"
  local valid_name
  while IFS= read -r valid_name; do
    if [ "$test_name" = "$valid_name" ]; then
      return 0
    fi
  done < <(valid_fuzz_targets)
  return 1
}

while [ $# -gt 0 ]; do
  case "$1" in
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

if [ "${#TESTS[@]}" -eq 0 ]; then
  while IFS= read -r test_name; do
    TESTS+=("$test_name")
  done < <(valid_fuzz_targets)
fi

for test_name in "${TESTS[@]}"; do
  if ! is_valid_fuzz_target "$test_name"; then
    echo "error: unknown fuzz test: $test_name" >&2
    echo >&2
    echo "Valid fuzz tests:" >&2
    valid_fuzz_targets | sed 's/^/  /' >&2
    exit 2
  fi
done

echo "=== Fuzz run configuration ==="
echo "max_duration: $MAX_DURATION"
echo "keep_going: $KEEP_GOING"
echo "surefire_reports: safere-fuzz/target/surefire-reports"
echo "reproducer_path: target/fuzz-reproducers"
echo "fuzz_logs: safere-fuzz/target/fuzz-logs/$RUN_ID"
echo "fuzz targets:"
printf '  %s\n' "${TESTS[@]}"

mkdir -p "$LOG_DIR"

for test_name in "${TESTS[@]}"; do
  log_file="$LOG_DIR/$test_name.log"
  {
    echo "=== Running $test_name (max_duration=$MAX_DURATION, keep_going=$KEEP_GOING) ==="
    echo "log_file: $log_file"
    JAZZER_FUZZ=1 mvn -f "$REPO_ROOT/pom.xml" -pl safere-fuzz -am \
      -Dtest="$test_name" \
      -Dsurefire.failIfNoSpecifiedTests=false \
      -Djazzer.max_duration="$MAX_DURATION" \
      -Djazzer.keep_going="$KEEP_GOING" \
      -Djazzer.reproducer_path=target/fuzz-reproducers \
      test
  } 2>&1 | tee "$log_file"
done
