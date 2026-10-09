# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

import json
from argparse import Namespace
from pathlib import Path

import pytest
from repo_assist.cli import begin, end, ensure_root, parser


def test_existing_pr_scout_state_is_migrated_in_place(tmp_path):
  (tmp_path / "state.json").write_text(
    json.dumps(
      {
        "prs": {"7": {"head": "abc"}},
        "issues": {"8": {"status": "reviewed"}},
      }
    )
  )
  ensure_root(tmp_path)
  state = json.loads((tmp_path / "state.json").read_text())
  assert state["prs"] == {"7": {"head": "abc"}}
  assert state["issues"] == {"8": {"status": "reviewed"}}


def test_existing_pr_commands_remain_compatible():
  assert parser().parse_args(["discover"]).func.__name__ == "discover"
  assert parser().parse_args(["snapshot", "7"]).number == 7
  artifact = parser().parse_args(["artifact-dir", "7", "abc"])
  assert (artifact.number, artifact.identifier) == (7, "abc")
  with pytest.raises(SystemExit):
    parser().parse_args(["discover", "issue"])


def test_recent_issue_scope_is_opt_in_and_passed_to_the_collector(tmp_path, capsys, monkeypatch):
  from repo_assist import cli

  assert parser().parse_args(["issue-activity", "--token", "t"]).recent_days is None
  begin(Namespace(root=tmp_path))
  metadata = json.loads(capsys.readouterr().out)
  args = parser().parse_args([
    "--root", str(tmp_path), "issue-activity", "--token", metadata["token"], "--recent-days", "14",
  ])
  calls = []

  def collect(*args, **kwargs):
    calls.append(kwargs)
    return {"issues": []}, {}

  monkeypatch.setattr(cli, "collect_activity", collect)
  assert cli.issue_activity(args) == 0
  assert calls == [{"recent_days": 14}]
  end(Namespace(root=tmp_path, token=metadata["token"], interrupted=True))


def test_run_lifecycle_updates_state_and_releases_lock(tmp_path, capsys):
  assert begin(Namespace(root=tmp_path)) == 0
  metadata = json.loads(capsys.readouterr().out)
  state = json.loads((tmp_path / "state.json").read_text())
  assert state["lastRunStartedAt"] == metadata["startedAt"]
  with pytest.raises(RuntimeError, match="issue activity collection"):
    end(Namespace(root=tmp_path, token=metadata["token"]))
  (tmp_path / "locks" / "run.lockdir" / "issue-activity.json").write_text(
    json.dumps({"cutoff": metadata["startedAt"], "checkpoint": {}})
  )
  assert end(Namespace(root=tmp_path, token=metadata["token"])) == 0
  state = json.loads((tmp_path / "state.json").read_text())
  assert "lastRunCompletedAt" in state
  assert not (tmp_path / "locks" / "run.lockdir").exists()


@pytest.mark.parametrize("has_prior_report", [False, True])
def test_latest_pointer_advances_only_after_successful_completion(
  tmp_path, capsys, has_prior_report
):
  latest = tmp_path / "LATEST.md"
  prior = "Latest run report: previous-completed-report.md\n" if has_prior_report else None
  if prior is not None:
    latest.write_text(prior)

  def assert_prior_pointer():
    if prior is None:
      assert not latest.exists()
    else:
      assert latest.read_text() == prior

  begin(Namespace(root=tmp_path))
  metadata = json.loads(capsys.readouterr().out)
  assert_prior_pointer()
  with pytest.raises(RuntimeError, match="issue activity collection"):
    end(Namespace(root=tmp_path, token=metadata["token"]))
  assert_prior_pointer()
  end(Namespace(root=tmp_path, token=metadata["token"], interrupted=True))
  assert_prior_pointer()

  begin(Namespace(root=tmp_path))
  metadata = json.loads(capsys.readouterr().out)
  assert_prior_pointer()
  (tmp_path / "locks" / "run.lockdir" / "issue-activity.json").write_text(
    json.dumps({"cutoff": metadata["startedAt"], "checkpoint": {}})
  )
  end(Namespace(root=tmp_path, token=metadata["token"]))
  report = metadata["reportPath"]
  assert latest.read_text() == f"Latest run report: {report}\n"
  assert "Status: completed" in Path(report).read_text()


def test_activity_checkpoint_advances_only_on_successful_report(tmp_path, capsys):
  begin(Namespace(root=tmp_path))
  metadata = json.loads(capsys.readouterr().out)
  pending = {"cutoff": metadata["startedAt"], "checkpoint": {"1": {"comments": {}}}}
  (tmp_path / "locks" / "run.lockdir" / "issue-activity.json").write_text(json.dumps(pending))
  end(Namespace(root=tmp_path, token=metadata["token"], interrupted=False))
  state = json.loads((tmp_path / "state.json").read_text())
  assert state["lastIssueActivityCutoff"] == metadata["startedAt"]
  assert state["issueActivity"] == pending["checkpoint"]


def test_interrupted_report_does_not_advance_activity_checkpoint(tmp_path, capsys):
  prior = {
    "prs": {},
    "issues": {},
    "lastRunCompletedAt": "2026-10-01T00:00:00Z",
    "lastIssueActivityCutoff": "2026-09-30T00:00:00Z",
    "issueActivity": {"1": {}},
  }
  (tmp_path / "state.json").write_text(json.dumps(prior))
  begin(Namespace(root=tmp_path))
  metadata = json.loads(capsys.readouterr().out)
  (tmp_path / "locks" / "run.lockdir" / "issue-activity.json").write_text(
    json.dumps({"cutoff": metadata["startedAt"], "checkpoint": {"2": {}}})
  )
  end(Namespace(root=tmp_path, token=metadata["token"], interrupted=True))
  state = json.loads((tmp_path / "state.json").read_text())
  for key in ("lastRunCompletedAt", "lastIssueActivityCutoff", "issueActivity"):
    assert state[key] == prior[key]
  assert not (tmp_path / "locks" / "run.lockdir").exists()


def test_failed_activity_collection_cannot_publish_or_advance_state(tmp_path, capsys, monkeypatch):
  from repo_assist import cli

  begin(Namespace(root=tmp_path))
  metadata = json.loads(capsys.readouterr().out)
  before = (tmp_path / "state.json").read_bytes()

  def failure(*args):
    raise PermissionError("trust check failed")

  monkeypatch.setattr(cli, "collect_activity", failure)
  with pytest.raises(PermissionError):
    cli.issue_activity(Namespace(root=tmp_path, token=metadata["token"], repository="o/r"))
  assert (tmp_path / "state.json").read_bytes() == before
  assert not (tmp_path / "locks" / "run.lockdir" / "issue-activity.json").exists()
  with pytest.raises(RuntimeError, match="issue activity collection"):
    end(Namespace(root=tmp_path, token=metadata["token"]))
  end(Namespace(root=tmp_path, token=metadata["token"], interrupted=True))


def test_failed_recollection_invalidates_pending_success(tmp_path, capsys, monkeypatch):
  from repo_assist import cli

  begin(Namespace(root=tmp_path))
  metadata = json.loads(capsys.readouterr().out)
  before = (tmp_path / "state.json").read_bytes()
  args = Namespace(root=tmp_path, token=metadata["token"], repository="o/r")
  monkeypatch.setattr(cli, "collect_activity", lambda *args: ({"issues": []}, {}))
  assert cli.issue_activity(args) == 0
  pending = tmp_path / "locks" / "run.lockdir" / "issue-activity.json"
  assert pending.exists()

  def failure(*args):
    raise PermissionError("trust check failed")

  monkeypatch.setattr(cli, "collect_activity", failure)
  with pytest.raises(PermissionError):
    cli.issue_activity(args)
  assert not pending.exists()
  assert (tmp_path / "state.json").read_bytes() == before
  with pytest.raises(RuntimeError, match="issue activity collection"):
    end(Namespace(root=tmp_path, token=metadata["token"]))
  end(Namespace(root=tmp_path, token=metadata["token"], interrupted=True))
