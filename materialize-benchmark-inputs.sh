#!/bin/bash
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.
#
# Materialize the resolved benchmark manifest and shared UTF-8 input corpus.
#
# Usage:
#   ./materialize-benchmark-inputs.sh
#   ./materialize-benchmark-inputs.sh --no-build

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BENCHMARK_DIR="$SCRIPT_DIR/safere-benchmarks"
BENCHMARK_JAR="$BENCHMARK_DIR/target/benchmarks.jar"
CORPUS_DIR="$BENCHMARK_DIR/target/benchmark-corpus"
BUILD=true

if [ "${1:-}" = "--no-build" ]; then
  BUILD=false
  shift
fi
if [ $# -ne 0 ]; then
  echo "Usage: $0 [--no-build]" >&2
  exit 2
fi

if [ "$BUILD" = true ]; then
  mvn package \
    -pl safere-benchmarks -am \
    -DskipTests \
    -Dpmd.skip=true \
    -Dcheckstyle.skip=true \
    -Dspotless.check.skip=true \
    -Dmaven.javadoc.skip=true \
    -Dexec.skip=true \
    -q \
    -f "$SCRIPT_DIR/pom.xml"
fi
if [ ! -f "$BENCHMARK_JAR" ]; then
  echo "ERROR: benchmark JAR not found: $BENCHMARK_JAR" >&2
  echo "Run without --no-build to build it first." >&2
  exit 1
fi

java -cp "$BENCHMARK_JAR" \
  org.safere.benchmark.BenchmarkInputMaterializer "$BENCHMARK_DIR" "$CORPUS_DIR"
