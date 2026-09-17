#!/usr/bin/env bash
# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).
set -euo pipefail

# Check the validation gate, not the entire workflow: newer runs can still be
# waiting for this publishing queue after their validation has passed.
RUNS=$(gh api --paginate \
  "repos/$GITHUB_REPOSITORY/actions/workflows/ci.yml/runs?branch=main&event=push&per_page=100" \
  --jq '.workflow_runs[] | [.run_number, .id] | @tsv')
CURRENT=true
while read -r RUN_NUMBER RUN_ID; do
  if [[ -n "$RUN_NUMBER" ]] && (( RUN_NUMBER > CI_RUN_NUMBER )); then
    PASSED_GATE=$(gh api --paginate \
      "repos/$GITHUB_REPOSITORY/actions/runs/$RUN_ID/jobs?filter=latest&per_page=100" \
      --jq '.jobs[] | select(.name == "ci-gate" and .conclusion == "success") | .id')
    if [[ -n "$PASSED_GATE" ]]; then
      CURRENT=false
      break
    fi
  fi
done <<< "$RUNS"
echo "current=$CURRENT" >> "$GITHUB_OUTPUT"
if [[ "$CURRENT" == false ]]; then
  echo "Skipping snapshot superseded by a newer successful main push."
fi
