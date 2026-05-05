#!/bin/bash
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.
#
# Run one or more SafeRE Jazzer fuzz tests in coverage-guided mode.
#
# Usage:
#   safere-fuzz/scripts/run-fuzz-test.sh CharacterClassExpressionFuzzer
#   safere-fuzz/scripts/run-fuzz-test.sh --max-duration 10m --keep-going 5 MatchFuzzer UnicodeFuzzer

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
MAX_DURATION="30m"
KEEP_GOING="10"
TESTS=()

usage() {
  cat <<EOF
Usage: $0 [--max-duration DURATION] [--keep-going COUNT] TEST [TEST...]

Options:
  --max-duration, --max_duration  Jazzer max duration per test (default: 30m)
  --keep-going, --keep_going      Number of distinct findings before stopping (default: 10)
  -h, --help                      Show this help

Examples:
  $0 CharacterClassExpressionFuzzer
  $0 --max-duration 10m --keep-going 5 MatchFuzzer UnicodeFuzzer
EOF
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
  echo "error: at least one fuzz test is required" >&2
  usage >&2
  exit 2
fi

for test_name in "${TESTS[@]}"; do
  echo "=== Running $test_name (max_duration=$MAX_DURATION, keep_going=$KEEP_GOING) ==="
  JAZZER_FUZZ=1 mvn -f "$REPO_ROOT/pom.xml" -pl safere-fuzz -am \
    -Dtest="$test_name" \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Djazzer.max_duration="$MAX_DURATION" \
    -Djazzer.keep_going="$KEEP_GOING" \
    -Djazzer.reproducer_path=target/fuzz-reproducers \
    test
done
