#!/bin/bash
# Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.
#
# Build and run C++ RE2 benchmarks.
#
# Usage:
#   ./run-cpp-benchmarks.sh                    # run all benchmarks
#   ./run-cpp-benchmarks.sh RegexBenchmark     # run matching benchmarks
#
# Prerequisites: CMake >= 3.14, C++17 compiler.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CPP_DIR="$SCRIPT_DIR/safere-benchmarks/cpp"
BUILD_DIR="$CPP_DIR/build"
DATA_FILE="$SCRIPT_DIR/safere-benchmarks/benchmark-data.json"

echo "=== Building C++ RE2 benchmarks ==="
mkdir -p "$BUILD_DIR"
cmake -S "$CPP_DIR" -B "$BUILD_DIR" -DCMAKE_BUILD_TYPE=Release -Wno-dev 2>&1 | tail -3
cmake --build "$BUILD_DIR" -j8 2>&1 | tail -3

echo "=== Running C++ RE2 benchmarks ==="
"$BUILD_DIR/re2_benchmark" --data "$DATA_FILE" "$@"
