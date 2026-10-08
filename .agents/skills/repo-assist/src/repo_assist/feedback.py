# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

"""Trusted collection of authored-PR feedback and discovery of original Git worktrees."""

from __future__ import annotations

import re
from collections.abc import Callable
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from repo_assist.github import GitHub, fingerprint, subprocess_runner


class WorktreeError(RuntimeError):
  """The original authored-PR worktree cannot safely be used for local repairs."""


def collect_feedback(github: GitHub, previous: dict[str, Any], limit: int = 1000) -> dict[str, Any]:
  """Collect sanitized feedback; processing state advances only after actual assessment."""
  trusted = github.trusted_users()
  login = github.authenticated_login()
  if login not in trusted:
    raise PermissionError("authenticated PR author is not trusted")
  discovery = github.discover_items("pr", trusted, limit)
  result: dict[str, Any] = {"authenticatedLogin": login, "prs": []}
  for entry in sorted(discovery["trusted"] + discovery["drafts"], key=lambda pr: pr["number"]):
    if entry["author"]["login"] != login:
      continue
    number = entry["number"]
    prior = previous.get(str(number), {})
    before = github.authored_pr_metadata(number, login, trusted)
    # Metadata identifies feedback without requesting text from a PR with none.
    nodes = {
      "comments": github._metadata_nodes("pr", number, "comments"),
      "reviews": github._metadata_nodes("pr", number, "reviews"),
      "reviewComments": github._review_comments(number),
    }
    external = [
      node
      for group in nodes.values()
      for node in group
      if (node.get("author") or {}).get("login") != login and node.get("state") != "PENDING"
    ]
    if not external and not prior:
      continue
    snapshot = github._trusted_core("pr", number, trusted)
    for kind, group in nodes.items():
      snapshot[kind] = github._trusted_nodes(
        [node for node in group if node.get("state") != "PENDING"], trusted
      )
    after = github.authored_pr_metadata(number, login, trusted)
    if after != before:
      raise RuntimeError("authored PR changed during feedback collection; retry")
    digest = fingerprint({"metadata": before, "snapshot": snapshot})
    result["prs"].append(
      {
        "metadata": before,
        "snapshot": snapshot,
        "fingerprint": digest,
        "changed": digest != prior.get("fingerprint"),
        "hasExternalFeedback": bool(external),
      }
    )
  return result


def _github_repository(url: str) -> str | None:
  if url.startswith("git@github.com:"):
    path = url[len("git@github.com:") :]
  else:
    parsed = urlsplit(url)
    if parsed.hostname != "github.com" or parsed.scheme not in {"https", "ssh"}:
      return None
    path = parsed.path.lstrip("/")
  path = path.removesuffix(".git")
  return path if re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", path) else None


def parse_worktrees(output: str) -> list[dict[str, str]]:
  """Parse NUL-delimited porcelain records without splitting paths on whitespace."""
  result, current = [], {}
  for field in output.split("\0"):
    if not field:
      if current:
        if "worktree" not in current:
          raise WorktreeError("malformed Git worktree record")
        result.append(current)
        current = {}
      continue
    key, _, value = field.partition(" ")
    if key in current:
      raise WorktreeError("duplicate Git worktree field")
    current[key] = value
  if current:
    raise WorktreeError("incomplete Git worktree output")
  return result


def resolve_worktree(
  checkout: Path,
  metadata: dict[str, Any],
  checkpoint: dict[str, Any] | None = None,
  runner: Callable[[list[str]], str] = subprocess_runner,
) -> dict[str, str]:
  """Find a unique clean original branch; never create, switch, reset, or mutate a worktree."""

  def git(path: Path, *args: str) -> str:
    return runner(["git", "-C", str(path), *args])

  branch = metadata["head_ref"]
  remote_head = metadata["head_oid"]
  head_repository = metadata["head_repository"]
  if not isinstance(branch, str) or not branch:
    raise WorktreeError("PR head branch is unavailable")
  if not isinstance(remote_head, str) or not re.fullmatch(r"[0-9a-f]{40}", remote_head):
    raise WorktreeError("PR head SHA is invalid")
  if not isinstance(head_repository, str) or not head_repository:
    raise WorktreeError("PR head repository is unavailable")
  records = parse_worktrees(git(checkout, "worktree", "list", "--porcelain", "-z"))
  matches = [entry for entry in records if entry.get("branch") == f"refs/heads/{branch}"]
  if len(matches) != 1:
    raise WorktreeError(f"expected one original worktree for the PR branch, found {len(matches)}")
  record = matches[0]
  if "locked" in record or "prunable" in record:
    raise WorktreeError("original worktree is locked or prunable")
  path = Path(record["worktree"]).resolve()
  if not path.is_dir():
    raise WorktreeError("original worktree no longer exists")
  common = Path(git(checkout, "rev-parse", "--path-format=absolute", "--git-common-dir").strip())
  actual_common = Path(git(path, "rev-parse", "--path-format=absolute", "--git-common-dir").strip())
  if common.resolve() != actual_common.resolve():
    raise WorktreeError("worktree belongs to a different Git repository")

  def ensure_editable() -> None:
    if git(path, "symbolic-ref", "--quiet", "HEAD").strip() != f"refs/heads/{branch}":
      raise WorktreeError("worktree branch changed during discovery")
    git_dir = Path(git(path, "rev-parse", "--absolute-git-dir").strip())
    if any(
      (git_dir / name).exists()
      for name in (
        "MERGE_HEAD",
        "CHERRY_PICK_HEAD",
        "REVERT_HEAD",
        "BISECT_LOG",
        "rebase-merge",
        "rebase-apply",
        "sequencer",
      )
    ):
      raise WorktreeError("worktree has an active Git operation")
    if git(path, "status", "--porcelain=v1", "-z", "--untracked-files=all"):
      raise WorktreeError("original worktree has uncommitted changes")

  ensure_editable()
  remotes = git(path, "remote").splitlines()
  repositories = {
    _github_repository(url)
    for remote in remotes
    for url in git(path, "remote", "get-url", "--all", remote).splitlines()
  }
  if head_repository.casefold() not in {repo.casefold() for repo in repositories if repo}:
    raise WorktreeError("worktree has no remote for the PR head repository")
  local_head = git(path, "rev-parse", "HEAD").strip()
  if local_head != remote_head:
    prior = checkpoint or {}
    if not (
      prior.get("lastRemoteHeadSha") == remote_head
      and prior.get("lastLocalHeadSha") == local_head
      and prior.get("worktree") == str(path)
      and prior.get("branch") == branch
    ):
      raise WorktreeError("original worktree HEAD differs from the published PR head")
    git(path, "merge-base", "--is-ancestor", remote_head, local_head)
  if git(path, "rev-parse", "HEAD").strip() != local_head:
    raise WorktreeError("worktree HEAD changed during discovery")
  ensure_editable()
  return {
    "worktree": str(path),
    "branch": branch,
    "remoteHeadSha": remote_head,
    "localHeadSha": local_head,
  }
