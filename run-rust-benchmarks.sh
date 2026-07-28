#!/bin/bash
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.
#
# Build and run Rust regex benchmarks.
#
# Usage:
#   ./run-rust-benchmarks.sh                    # run all benchmarks
#   ./run-rust-benchmarks.sh RegexBenchmark     # run matching benchmarks
#
# Prerequisites: Rust >= 1.85 and Cargo.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUST_DIR="$SCRIPT_DIR/safere-benchmarks/rust"
MANIFEST_FILE="$SCRIPT_DIR/safere-benchmarks/target/benchmark-corpus/manifest.json"

echo "=== Materializing shared benchmark inputs ==="
"$SCRIPT_DIR/materialize-benchmark-inputs.sh"

echo "=== Building Rust regex benchmarks ==="
cargo build --release --locked --manifest-path "$RUST_DIR/Cargo.toml"

echo "=== Running Rust regex timing benchmarks ==="
"$RUST_DIR/target/release/safere-regex-benchmark" --manifest "$MANIFEST_FILE" "$@"

echo "=== Building Rust regex memory benchmarks ==="
cargo build --release --locked --features memory-tracking \
  --manifest-path "$RUST_DIR/Cargo.toml"

echo "=== Running Rust regex memory benchmarks ==="
"$RUST_DIR/target/release/safere-regex-benchmark" --manifest "$MANIFEST_FILE" "$@"
