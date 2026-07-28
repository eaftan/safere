#!/bin/bash
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.
#
# Build and run .NET non-backtracking regex benchmarks.
#
# Usage:
#   ./run-dotnet-benchmarks.sh                    # run all benchmarks
#   ./run-dotnet-benchmarks.sh --smoke            # exercise each supported workload once
#   ./run-dotnet-benchmarks.sh --list-exclusions  # explain unsupported workloads
#   ./run-dotnet-benchmarks.sh RegexBenchmark     # run matching benchmarks
#
# Prerequisites: .NET SDK 8 or newer.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DOTNET_DIR="$SCRIPT_DIR/safere-benchmarks/dotnet"
MANIFEST_FILE="$SCRIPT_DIR/safere-benchmarks/target/benchmark-corpus/manifest.json"

echo "=== Materializing shared benchmark inputs ==="
"$SCRIPT_DIR/materialize-benchmark-inputs.sh"

echo "=== Building .NET non-backtracking regex benchmarks ==="
dotnet build "$DOTNET_DIR/SafeRE.Benchmarks.csproj" \
  --configuration Release \
  --nologo \
  --verbosity quiet

echo "=== Running .NET non-backtracking regex benchmarks ==="
dotnet run \
  --project "$DOTNET_DIR/SafeRE.Benchmarks.csproj" \
  --configuration Release \
  --no-build \
  -- \
  --manifest "$MANIFEST_FILE" \
  "$@"
