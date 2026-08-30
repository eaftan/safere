# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

import json
from argparse import Namespace

from repo_assist.cli import begin, end, ensure_root


def test_existing_pr_scout_state_is_migrated_in_place(tmp_path):
  (tmp_path / "state.json").write_text(json.dumps({"prs": {"7": {"head": "abc"}}}))
  ensure_root(tmp_path)
  state = json.loads((tmp_path / "state.json").read_text())
  assert state["prs"] == {"7": {"head": "abc"}}
  assert state["issues"] == {}


def test_run_lifecycle_updates_state_and_releases_lock(tmp_path, capsys):
  assert begin(Namespace(root=tmp_path)) == 0
  metadata = json.loads(capsys.readouterr().out)
  state = json.loads((tmp_path / "state.json").read_text())
  assert state["lastRunStartedAt"] == metadata["startedAt"]
  assert end(Namespace(root=tmp_path, token=metadata["token"])) == 0
  state = json.loads((tmp_path / "state.json").read_text())
  assert "lastRunCompletedAt" in state
  assert not (tmp_path / "locks" / "run.lockdir").exists()
