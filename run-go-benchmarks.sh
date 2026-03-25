#!/bin/bash
# Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.
#
# Build and run Go regexp benchmarks.
#
# Usage:
#   ./run-go-benchmarks.sh                    # run all benchmarks
#   ./run-go-benchmarks.sh RegexBenchmark     # run matching benchmarks
#
# Prerequisites: Go >= 1.21.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GO_DIR="$SCRIPT_DIR/safere-benchmarks/go"
DATA_FILE="$SCRIPT_DIR/safere-benchmarks/benchmark-data.json"

echo "=== Building Go regexp benchmarks ==="
(cd "$GO_DIR" && go build -o regexp_benchmark .)

echo "=== Running Go regexp benchmarks ==="
"$GO_DIR/regexp_benchmark" --data "$DATA_FILE" "$@"
