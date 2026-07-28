#!/bin/bash
# Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.
#
# Build and run native C++ RE2 and PCRE2 JIT benchmarks.
#
# Usage:
#   ./run-cpp-benchmarks.sh                    # run all benchmarks
#   ./run-cpp-benchmarks.sh RegexBenchmark     # run matching benchmarks
#   ./run-cpp-benchmarks.sh --engine pcre2-jit RegexBenchmark
#
# Prerequisites: CMake >= 3.15, C++17 compiler.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CPP_DIR="$SCRIPT_DIR/safere-benchmarks/cpp"
BUILD_DIR="$CPP_DIR/build"
MANIFEST_FILE="$SCRIPT_DIR/safere-benchmarks/target/benchmark-corpus/manifest.json"
ENGINE="all"

if [ "${1:-}" = "--engine" ]; then
  if [ $# -lt 2 ]; then
    echo "ERROR: --engine requires all, re2, or pcre2-jit" >&2
    exit 2
  fi
  ENGINE="$2"
  shift 2
fi

case "$ENGINE" in
  all|re2|pcre2-jit) ;;
  *)
    echo "ERROR: unknown native C++ benchmark engine: $ENGINE" >&2
    exit 2
    ;;
esac

echo "=== Materializing shared benchmark inputs ==="
"$SCRIPT_DIR/materialize-benchmark-inputs.sh"

echo "=== Building native C++ regex benchmarks ==="
mkdir -p "$BUILD_DIR"
cmake -S "$CPP_DIR" -B "$BUILD_DIR" -DCMAKE_BUILD_TYPE=Release -Wno-dev 2>&1 | tail -3
cmake --build "$BUILD_DIR" -j8 2>&1 | tail -3

if [ "$ENGINE" = "all" ] || [ "$ENGINE" = "re2" ]; then
  echo "=== Running C++ RE2 benchmarks ==="
  "$BUILD_DIR/re2_benchmark" --manifest "$MANIFEST_FILE" "$@"
fi

if [ "$ENGINE" = "all" ] || [ "$ENGINE" = "pcre2-jit" ]; then
  echo "=== Running PCRE2 JIT benchmarks ==="
  "$BUILD_DIR/pcre2_jit_benchmark" --manifest "$MANIFEST_FILE" "$@"
fi
