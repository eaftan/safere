#!/bin/bash
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
JDK_26_HOME="${JDK_26_HOME:-$HOME/jdks/jdk-26.0.1}"
OUTPUT="${1:-$REPO_ROOT/safere/src/main/resources/org/safere/unicode-tables.bin}"
UNICODE_VERSION="${2:-unknown}"
BUILD_DIR="$REPO_ROOT/target/unicode-generator"

mkdir -p "$BUILD_DIR"
"$JDK_26_HOME/bin/javac" -d "$BUILD_DIR" "$SCRIPT_DIR/UnicodeTableGenerator.java"
"$JDK_26_HOME/bin/java" -cp "$BUILD_DIR" UnicodeTableGenerator "$OUTPUT" "$UNICODE_VERSION"
