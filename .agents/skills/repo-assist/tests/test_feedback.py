# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

import json
import subprocess
from pathlib import Path

import pytest
from repo_assist.feedback import WorktreeError, collect_feedback, parse_worktrees, resolve_worktree
from repo_assist.github import GitHub
from test_github import FakeRunner


class FeedbackSource(GitHub):
  def __init__(self):
    super().__init__("o/r")
    self.reads = []
    self.viewer = "owner"
    self.external = True
    self.head = "a" * 40

  def trusted_users(self):
    return frozenset({"owner", "reviewer"})

  def authenticated_login(self):
    return self.viewer

  def discover_items(self, kind, trusted, limit=1000):
    return {
      "trusted": [{"number": 2, "author": {"login": "reviewer"}}],
      "drafts": [{"number": 1, "author": {"login": "owner"}}],
      "untrusted": [],
    }

  def repository_metadata(self):
    return {"owner": "owner"}

  def authored_pr_metadata(self, number, login, trusted):
    assert number == 1
    return {
      "number": number,
      "author": login,
      "head_oid": self.head,
      "head_ref": "feature",
      "head_repository": "o/r",
      "is_draft": True,
    }

  def _metadata_nodes(self, kind, number, connection):
    if connection != "comments":
      return []
    return [
      {"id": "own", "updatedAt": "t", "author": {"login": "owner"}, "_type": "IssueComment"}
    ] + (
      [
        {
          "id": "trusted",
          "updatedAt": "t",
          "author": {"login": "reviewer"},
          "_type": "IssueComment",
        },
        {
          "id": "untrusted",
          "updatedAt": "t",
          "author": {"login": "stranger"},
          "_type": "IssueComment",
          "body": "UNTRUSTED-CANARY",
        },
      ]
      if self.external
      else []
    )

  def _review_comments(self, number):
    return []

  def _trusted_core(self, kind, number, trusted):
    self.reads.append(number)
    return {"title": "Owned draft", "body": "Intent"}

  def _trusted_nodes(self, nodes, trusted):
    responses = [
      {"data": {"node": {"body": "feedback", "author": node["author"]}}}
      for node in nodes
      if node["author"]["login"] in trusted
    ]
    runner = FakeRunner(responses)
    result = GitHub("o/r", runner)._trusted_nodes(nodes, trusted)
    assert all("id=untrusted" not in command for command in runner.commands)
    return result


def test_authored_drafts_with_feedback_use_independent_comment_trust_gates():
  source = FeedbackSource()
  result = collect_feedback(source, {})
  assert [pr["metadata"]["number"] for pr in result["prs"]] == [1]
  assert result["prs"][0]["metadata"]["is_draft"]
  assert "UNTRUSTED-CANARY" not in json.dumps(result)
  assert source.reads == [1]
  assert result["prs"][0]["snapshot"]["comments"]["untrustedMetadata"][0]["id"] == "untrusted"


def test_pr_without_external_feedback_does_not_fetch_any_body():
  source = FeedbackSource()
  source.external = False
  assert collect_feedback(source, {})["prs"] == []
  assert source.reads == []


def test_feedback_freshness_includes_pr_head_and_full_discussion():
  source = FeedbackSource()
  first = collect_feedback(source, {})["prs"][0]
  state = {"1": {"fingerprint": first["fingerprint"]}}
  assert not collect_feedback(source, state)["prs"][0]["changed"]
  source.head = "b" * 40
  assert collect_feedback(source, state)["prs"][0]["changed"]


def test_untrusted_authenticated_author_cannot_fetch_feedback():
  source = FeedbackSource()
  source.viewer = "stranger"
  with pytest.raises(PermissionError):
    collect_feedback(source, {})
  assert source.reads == []


def test_authored_metadata_rejects_another_author_before_fetching_content():
  runner = FakeRunner(
    [
      {
        "data": {
          "repository": {
            "pullRequest": {
              "number": 1,
              "author": {"login": "reviewer"},
              "body": "CANARY",
            }
          }
        }
      }
    ]
  )
  with pytest.raises(PermissionError):
    GitHub("o/r", runner).authored_pr_metadata(1, "owner", frozenset({"owner", "reviewer"}))
  assert len(runner.commands) == 1
  assert "body" not in " ".join(runner.commands[0])


def git(path: Path, *arguments: str) -> str:
  return subprocess.run(
    ["git", "-C", str(path), *arguments], check=True, capture_output=True, text=True
  ).stdout.strip()


def commit(path: Path, text: str) -> str:
  (path / "file.txt").write_text(text, encoding="utf-8")
  git(path, "add", "file.txt")
  git(
    path,
    "-c",
    "user.name=Test",
    "-c",
    "user.email=test@example.com",
    "commit",
    "-qm",
    "test change",
  )
  return git(path, "rev-parse", "HEAD")


@pytest.fixture
def worktree(tmp_path):
  repository = tmp_path / "repository"
  repository.mkdir()
  git(repository, "init", "-qb", "main")
  head = commit(repository, "initial")
  git(repository, "remote", "add", "origin", "https://github.com/o/r.git")
  original = tmp_path / "original worktree"
  git(repository, "worktree", "add", "-qb", "feature", str(original))
  return repository, original, {"head_ref": "feature", "head_oid": head, "head_repository": "o/r"}


def test_resolver_finds_original_branch_worktree_with_spaces(worktree):
  repository, original, metadata = worktree
  result = resolve_worktree(repository, metadata)
  assert result["worktree"] == str(original)
  assert result["branch"] == "feature"
  assert result["localHeadSha"] == metadata["head_oid"]


@pytest.mark.parametrize("tracked", [True, False])
def test_resolver_preserves_uncommitted_user_work(worktree, tracked):
  repository, original, metadata = worktree
  target = original / ("file.txt" if tracked else "untracked.txt")
  target.write_text("user work", encoding="utf-8")
  with pytest.raises(WorktreeError, match="uncommitted"):
    resolve_worktree(repository, metadata)
  assert target.read_text(encoding="utf-8") == "user work"


def test_resolver_checks_head_repository_even_when_branch_and_commit_match(worktree):
  repository, _, metadata = worktree
  metadata["head_repository"] = "other/fork"
  with pytest.raises(WorktreeError, match="remote"):
    resolve_worktree(repository, metadata)


def test_resolver_blocks_unrecorded_local_commits_but_accepts_recorded_unpublished_fixes(worktree):
  repository, original, metadata = worktree
  local_head = commit(original, "local repair")
  with pytest.raises(WorktreeError, match="HEAD differs"):
    resolve_worktree(repository, metadata)
  checkpoint = {
    "lastRemoteHeadSha": metadata["head_oid"],
    "lastLocalHeadSha": local_head,
    "worktree": str(original),
    "branch": "feature",
  }
  assert resolve_worktree(repository, metadata, checkpoint)["localHeadSha"] == local_head
  checkpoint["lastRemoteHeadSha"] = "0" * 40
  with pytest.raises(WorktreeError, match="HEAD differs"):
    resolve_worktree(repository, metadata, checkpoint)


def test_resolver_does_not_create_replacement_when_original_branch_is_missing(worktree):
  repository, _, metadata = worktree
  metadata["head_ref"] = "missing"
  with pytest.raises(WorktreeError, match="found 0"):
    resolve_worktree(repository, metadata)


def test_resolver_rejects_ambiguous_branch_matches(worktree):
  repository, original, metadata = worktree
  record = f"worktree {original}\0HEAD {metadata['head_oid']}\0branch refs/heads/feature\0\0"
  with pytest.raises(WorktreeError, match="found 2"):
    resolve_worktree(repository, metadata, runner=lambda command: record + record)


def test_parser_rejects_incomplete_registry_data():
  with pytest.raises(WorktreeError, match="incomplete"):
    parse_worktrees("worktree /some/path\0branch refs/heads/feature")


def test_cli_discovery_routes_authored_drafts_out_of_contributor_review(monkeypatch, capsys):
  from argparse import Namespace

  from repo_assist import cli

  monkeypatch.setattr(cli, "GitHub", lambda repository: FeedbackSource())
  cli.discover(Namespace(repository="o/r", kind="pr", limit=1000))
  result = json.loads(capsys.readouterr().out)
  assert result["authenticatedLogin"] == "owner"
  assert [pr["number"] for pr in result["authored"]] == [1]
  assert [pr["number"] for pr in result["trusted"]] == [2]
  assert result["drafts"] == []


def test_resolver_blocks_active_git_operations(worktree):
  repository, original, metadata = worktree
  directory = Path(git(original, "rev-parse", "--absolute-git-dir"))
  (directory / "CHERRY_PICK_HEAD").write_text(metadata["head_oid"] + "\n", encoding="utf-8")
  with pytest.raises(WorktreeError, match="active Git operation"):
    resolve_worktree(repository, metadata)


def test_pending_reviews_do_not_trigger_content_fetch():
  source = FeedbackSource()
  source.external = False
  original = source._metadata_nodes

  def nodes(kind, number, connection):
    if connection == "reviews":
      return [
        {
          "id": "pending",
          "updatedAt": "t",
          "author": {"login": "reviewer"},
          "state": "PENDING",
          "_type": "PullRequestReview",
        }
      ]
    return original(kind, number, connection)

  source._metadata_nodes = nodes
  assert collect_feedback(source, {})["prs"] == []
  assert source.reads == []


def test_collection_rejects_a_head_change_before_publishing_feedback():
  source = FeedbackSource()
  original = source._trusted_core

  def core(kind, number, trusted):
    result = original(kind, number, trusted)
    source.head = "b" * 40
    return result

  source._trusted_core = core
  with pytest.raises(RuntimeError, match="changed during"):
    collect_feedback(source, {})


def test_worktree_remote_accepts_github_ssh_form(worktree):
  repository, original, metadata = worktree
  git(original, "remote", "set-url", "origin", "git@github.com:o/r.git")
  assert resolve_worktree(repository, metadata)["worktree"] == str(original)


def test_removed_feedback_retains_prior_assessment_and_unpublished_repairs():
  source = FeedbackSource()
  first = collect_feedback(source, {})["prs"][0]
  prior = {"1": {"fingerprint": first["fingerprint"], "lastLocalHeadSha": "b" * 40}}
  source.external = False
  result = collect_feedback(source, prior)
  assert len(result["prs"]) == 1
  assert result["prs"][0]["changed"]
  assert not result["prs"][0]["hasExternalFeedback"]
  assert result["prs"][0]["snapshot"]["comments"]["untrustedMetadata"] == []


@pytest.mark.parametrize("change", ["branch", "dirty", "operation"])
def test_resolver_detects_changes_after_initial_checks(worktree, change):
  from repo_assist.github import subprocess_runner

  repository, original, metadata = worktree
  triggered = False

  def runner(command):
    nonlocal triggered
    if not triggered and command[3] == "remote":
      triggered = True
      if change == "branch":
        git(original, "switch", "-qc", "concurrent-branch")
      elif change == "dirty":
        (original / "file.txt").write_text("concurrent user work", encoding="utf-8")
      else:
        directory = Path(git(original, "rev-parse", "--absolute-git-dir"))
        (directory / "CHERRY_PICK_HEAD").write_text(metadata["head_oid"], encoding="utf-8")
    return subprocess_runner(command)

  with pytest.raises(WorktreeError, match="branch changed|uncommitted|active Git operation"):
    resolve_worktree(repository, metadata, runner=runner)
  assert git(original, "rev-parse", "HEAD") == metadata["head_oid"]


def test_contributor_discovery_excludes_repository_owner_when_viewer_is_different(
  monkeypatch, capsys
):
  from argparse import Namespace

  from repo_assist import cli

  source = FeedbackSource()
  source.viewer = "reviewer"

  def discovery(kind, trusted, limit=1000):
    return {
      "trusted": [
        {"number": 1, "author": {"login": "owner"}},
        {"number": 2, "author": {"login": "reviewer"}},
        {"number": 3, "author": {"login": "writer"}},
      ],
      "drafts": [],
      "untrusted": [],
    }

  source.discover_items = discovery
  monkeypatch.setattr(cli, "GitHub", lambda repository: source)
  cli.discover(Namespace(repository="o/r", limit=1000))
  result = json.loads(capsys.readouterr().out)
  assert result["repositoryOwner"] == "owner"
  assert [pr["number"] for pr in result["trusted"]] == [3]
  assert [pr["number"] for pr in result["authored"]] == [2]
