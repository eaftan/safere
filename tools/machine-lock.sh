#!/bin/bash
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.

# Source with the entry point's original argv, then call safere_reserve_machine
# after lightweight validation and before builds, measurements, or output writes.
SAFERE_MACHINE_RUNNER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/machine-run"
SAFERE_MACHINE_ENTRYPOINT="$(cd "$(dirname "$0")" && pwd)/${0##*/}"
SAFERE_MACHINE_ORIGINAL_ARGS=("$@")
safere_reserve_machine() {
  if ! command -v python3 >/dev/null 2>&1; then
    echo "error: python3 is required for exclusive machine access" >&2
    exit 2
  fi
  if ! "$SAFERE_MACHINE_RUNNER" --check; then
    exec "$SAFERE_MACHINE_RUNNER" --label "${0##*/}" -- "$BASH" "$SAFERE_MACHINE_ENTRYPOINT" \
      ${SAFERE_MACHINE_ORIGINAL_ARGS[@]+"${SAFERE_MACHINE_ORIGINAL_ARGS[@]}"}
  fi
}
