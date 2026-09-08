# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

import json
from argparse import Namespace

import pytest

from repo_assist.cli import begin, end, ensure_root, parser


def test_existing_pr_scout_state_is_migrated_in_place(tmp_path):
  (tmp_path / "state.json").write_text(json.dumps({
      "prs": {"7": {"head": "abc"}},
      "issues": {"8": {"status": "reviewed"}},
  }))
  ensure_root(tmp_path)
  state = json.loads((tmp_path / "state.json").read_text())
  assert state["prs"] == {"7": {"head": "abc"}}
  assert "issues" not in state


def test_commands_accept_only_pr_specific_arguments():
  assert parser().parse_args(["discover"]).func.__name__ == "discover"
  assert parser().parse_args(["snapshot", "7"]).number == 7
  artifact = parser().parse_args(["artifact-dir", "7", "abc"])
  assert (artifact.number, artifact.identifier) == (7, "abc")
  with pytest.raises(SystemExit):
    parser().parse_args(["discover", "issue"])


def test_run_lifecycle_updates_state_and_releases_lock(tmp_path, capsys):
  assert begin(Namespace(root=tmp_path)) == 0
  metadata = json.loads(capsys.readouterr().out)
  state = json.loads((tmp_path / "state.json").read_text())
  assert state["lastRunStartedAt"] == metadata["startedAt"]
  assert end(Namespace(root=tmp_path, token=metadata["token"])) == 0
  state = json.loads((tmp_path / "state.json").read_text())
  assert "lastRunCompletedAt" in state
  assert not (tmp_path / "locks" / "run.lockdir").exists()
