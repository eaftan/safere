#!/usr/bin/env bash
# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).
set -euo pipefail

# CI can finish out of push order. A newer successful push will publish
# through this same queue; pending or failed CI does not supersede us.
RUN_NUMBERS=$(gh api --paginate \
  "repos/$GITHUB_REPOSITORY/actions/workflows/ci.yml/runs?branch=main&event=push&status=success&per_page=100" \
  --jq '.workflow_runs[].run_number')
CURRENT=true
while read -r RUN_NUMBER; do
  if [[ -n "$RUN_NUMBER" ]] && (( RUN_NUMBER > CI_RUN_NUMBER )); then
    CURRENT=false
    break
  fi
done <<< "$RUN_NUMBERS"
echo "current=$CURRENT" >> "$GITHUB_OUTPUT"
if [[ "$CURRENT" == false ]]; then
  echo "Skipping snapshot superseded by a newer successful main push."
fi
