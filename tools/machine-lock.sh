#!/bin/bash
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.

# Source before any build or measurement, with the entry point's original argv.
# Re-enter the script under the supervisor unless this workload already owns it.
if ! command -v python3 >/dev/null 2>&1; then
  echo "error: python3 is required for exclusive machine access" >&2
  exit 2
fi
SAFERE_MACHINE_RUNNER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/machine-run"
if ! "$SAFERE_MACHINE_RUNNER" --check; then
  exec "$SAFERE_MACHINE_RUNNER" --label "${0##*/}" -- "$BASH" "$0" "$@"
fi
