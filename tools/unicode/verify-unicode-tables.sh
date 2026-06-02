#!/bin/bash
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
EXPECTED="$REPO_ROOT/safere/src/main/resources/org/safere/unicode-tables.bin"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

"$SCRIPT_DIR/generate-unicode-tables.sh" "$TMP_DIR/unicode-tables.bin" "${1:-unknown}"
cmp "$EXPECTED" "$TMP_DIR/unicode-tables.bin"
