# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

from argparse import Namespace
import json
from pathlib import Path

import pytest
from repo_assist.github import fingerprint
from repo_assist.issue_reports import HEADER, SEPARATOR, audit, done_values, update


def report(done="X", running=False):
  return (
    "# Report\n\nStatus: " + ("running" if running else "completed") + "\n\n"
    "## PR Summary\n\nPR data to preserve.\n\n## Issues\n\n"
    + HEADER + "\n" + SEPARATOR + "\n"
    + f"| {done} | [Issue #1: title](#issue-1) | New | #2 (draft; partial) | More work |\n\n"
    '<a id="issue-1"></a>\n### Issue #1: title\n\n'
    "Requested outcome: full historical assessment.\n\n"
    "| PR | Coverage |\n|---|---|\n| #2 | partial; specific remaining requirement |\n\n"
    "Remaining work: benchmark the missing case.\n\n"
    "## Your PRs With Review Feedback\n\nOwner data to preserve.\n"
  )


def write(path, value):
  path.parent.mkdir(parents=True, exist_ok=True)
  path.write_text(json.dumps(value))


@pytest.fixture
def setup(tmp_path):
  root = tmp_path
  old = root / "reports/old.md"
  old.parent.mkdir()
  old.write_text(report())
  current = root / "reports/current.md"
  current.write_text("# Report\n\nStatus: running\n\n## PR Summary\n\nPR data.\n")
  record = {
    "lastReport": str(old), "lastReviewedAt": "2026-10-01T00:00:00Z",
    "evidenceFingerprint": "evidence", "contentFingerprint": "content",
    "recommendation": "More work",
  }
  state = {"issues": {"1": record}, "issueActivity": {"untouched": True},
           "prs": {"99": {"status": "reviewed"}}, "lastIssueActivityCutoff": "old"}
  write(root / "state.json", state)
  item = {
    "metadata": {"number": 1}, "assessmentAction": "reuse", "contentStatus": "unchanged",
    "evidenceFingerprint": "evidence", "contentFingerprint": "content",
    "assessmentSource": str(old),
  }
  args = Namespace(root=root, token="token", activity=root / "artifacts/activity.json",
                   command="carry-forward", issues=None)
  write(root / "locks/run.lockdir/metadata.json", {
    "token": "token", "startedAt": "cutoff", "reportPath": str(current),
  })
  publish(args, item)
  return args, item, old, current, state


def publish(args, item):
  activity = {"cutoff": "cutoff", "issues": [item]}
  activity["fingerprint"] = fingerprint(activity)
  write(args.activity, activity)
  write(args.root / "locks/run.lockdir/issue-activity.json", {
    "cutoff": "cutoff", "artifactFingerprint": activity["fingerprint"],
    "checkpoint": {"1": {key: item[key] for key in ("evidenceFingerprint", "contentFingerprint")}},
  })


def test_carry_preserves_full_assessment_done_and_review_time(setup):
  args, _, old, current, previous = setup
  result = update(args)
  text = current.read_text()
  assert result["issues"] == [1]
  assert "| X | [Issue #1" in text
  assert "Unchanged; assessment reused" in text
  assert "full historical assessment" in text
  assert "partial; specific remaining requirement" in text
  assert "benchmark the missing case" in text
  assert "Last reviewed: 2026-10-01T00:00:00Z" in text
  state = json.loads((args.root / "state.json").read_text())
  assert state["issues"]["1"]["lastReviewedAt"] == previous["issues"]["1"]["lastReviewedAt"]
  assert state["issues"]["1"]["lastReport"] == str(current)
  for category in ("issueActivity", "prs", "lastIssueActivityCutoff"):
    assert state[category] == previous[category]
  assert old.read_text() == report()
  update(args)
  assert current.read_text() == text  # Repeating a copy never appends another section/note.


def test_activity_record_is_separate_from_brief_and_uses_main_run_status(setup):
  args, _, _, current, _ = setup
  metadata_path = args.root / 'locks/run.lockdir/metadata.json'
  metadata = json.loads(metadata_path.read_text())
  detail = args.root / 'reports/current/issue-activity.md'
  detail.parent.mkdir(); detail.write_text('## Issues\n\n' + HEADER + '\n' + SEPARATOR + '\n')
  metadata['issueReportPath'] = str(detail); write(metadata_path, metadata)
  before = current.read_bytes()
  update(args)
  assert current.read_bytes() == before
  assert 'full historical assessment' in detail.read_text()
  assert audit(args)['mechanicalChecksPassed']
  state = json.loads((args.root / 'state.json').read_text())
  assert state['issues']['1']['lastReport'] == str(detail)
  current.write_text(current.read_text().replace('Status: running', 'Status: completed'))
  with pytest.raises(ValueError, match='running report'):
    update(args)


def test_current_done_cell_and_other_categories_are_preserved(setup):
  args, _, _, current, _ = setup
  current.write_text(report(done="", running=True))
  update(args)
  text = current.read_text()
  assert "|  | [Issue #1" in text
  assert "| X |" not in text
  assert "Owner data to preserve." in text
  assert "PR data to preserve." in text


@pytest.mark.parametrize("change", ["action", "evidence", "content", "source"])
def test_ineligible_reuse_is_rejected_before_opening_any_saved_report(setup, monkeypatch, change):
  args, item, old, _, _ = setup
  if change == "action":
    item.update(assessmentAction="review", contentStatus="fresh")
    args.issues = [1]
  elif change == "source":
    item["assessmentSource"] = str(args.root / "reports/forged.md")
  else:
    item[change + "Fingerprint"] = "different"
  publish(args, item)
  original = Path.read_text

  def read(path, *positional, **kwargs):
    if path == old or path.name == "forged.md":
      pytest.fail("old prose must not be read for changed evidence")
    return original(path, *positional, **kwargs)

  monkeypatch.setattr(Path, "read_text", read)
  with pytest.raises(ValueError, match="not eligible"):
    update(args)


@pytest.mark.parametrize("problem", ["missing", "section", "row"])
def test_missing_or_incomplete_previous_report_requires_fresh_assessment(setup, problem):
  args, _, old, current, _ = setup
  if problem == "missing":
    old.unlink()
  elif problem == "section":
    old.write_text(report().replace('<a id="issue-1"></a>', ""))
  else:
    old.write_text(report().replace("(#issue-1)", "(#something-else)"))
  before = current.read_text()
  with pytest.raises(ValueError, match="--reassess 1"):
    update(args)
  assert current.read_text() == before


def test_stale_collection_is_rejected(setup):
  args, _, _, _, _ = setup
  pending = args.root / "locks/run.lockdir/issue-activity.json"
  value = json.loads(pending.read_text())
  value["artifactFingerprint"] = "newer collection"
  write(pending, value)
  with pytest.raises(ValueError, match="latest successful collection"):
    update(args)


def test_checkpoint_binds_written_fresh_assessment_without_touching_success_cutoff(setup):
  args, item, _, current, previous = setup
  current.write_text(report(done="", running=True))
  item.update(assessmentAction="review", contentStatus="fresh", evidenceFingerprint="new")
  publish(args, item)
  args.command = "checkpoint"
  args.issues = [1]
  update(args)
  state = json.loads((args.root / "state.json").read_text())
  saved = state["issues"]["1"]
  assert saved["fingerprint"] == saved["evidenceFingerprint"] == "new"
  assert saved["lastReport"] == str(current)
  assert saved["lastReviewedAt"] != previous["issues"]["1"]["lastReviewedAt"]
  assert saved["recommendation"] == "More work"
  assert state["issueActivity"] == previous["issueActivity"]
  assert state["lastIssueActivityCutoff"] == "old"


def test_checkpoint_cannot_relabel_reused_work_as_reviewed(setup):
  args, _, _, current, _ = setup
  current.write_text(report(running=True))
  args.command = "checkpoint"
  args.issues = [1]
  with pytest.raises(ValueError, match="only freshly assessed"):
    update(args)


def test_fenced_anchors_and_escaped_table_pipes_are_not_misparsed(setup):
  args, _, old, current, _ = setup
  source = report().replace("title](#issue-1)", r"title \| details](#issue-1)")
  source = source.replace("Remaining work:", '```markdown\n<a id="issue-1"></a>\n### Issue #1: sample\n```\n\nRemaining work:')
  old.write_text(source)
  update(args)
  assert r"title \| details" in current.read_text()
  assert "### Issue #1: sample" in current.read_text()


def test_issue_audit_rejects_unprocessed_items_then_accepts_complete_reuse(setup):
  args, _, _, _, _ = setup
  with pytest.raises(ValueError, match="assessment is missing or stale"):
    audit(args)
  update(args)
  assert audit(args)["mechanicalChecksPassed"]


def test_issue_audit_detects_deleted_detail_despite_matching_state(setup):
  args, _, _, current, _ = setup
  update(args)
  current.write_text(current.read_text().replace('<a id="issue-1"></a>', ""))
  with pytest.raises(ValueError, match="full section or summary row is missing"):
    audit(args)


def test_done_values_recovers_only_reviewer_cell_for_changed_evidence(setup):
  args, item, _, current, _ = setup
  item.update(assessmentAction="review", contentStatus="fresh", evidenceFingerprint="changed")
  publish(args, item)
  before = current.read_text()
  state_before = (args.root / "state.json").read_text()
  values = done_values(args)
  assert values == {"done": {"1": "X"}, "unavailable": []}
  assert "full historical assessment" not in json.dumps(values)
  assert current.read_text() == before
  assert (args.root / "state.json").read_text() == state_before


def test_done_values_prefers_explicit_current_cell_including_cleared_done(setup):
  args, _, _, current, _ = setup
  current.write_text(report(done="", running=True))
  assert done_values(args)["done"] == {"1": ""}


def test_done_values_flags_missing_prior_report_without_inventing_mark(setup):
  args, _, old, _, _ = setup
  old.unlink()
  assert done_values(args) == {"done": {"1": ""}, "unavailable": [1]}


def test_done_values_flags_absent_prior_row(setup):
  args, _, old, _, _ = setup
  old.write_text(report().replace("(#issue-1)", "(#something-else)"))
  assert done_values(args) == {"done": {"1": ""}, "unavailable": [1]}
