# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

"""Command-line support for the repo-assist skill."""

from __future__ import annotations

import argparse
import json
import os
import secrets
import shutil
import sys
from datetime import UTC, datetime
from pathlib import Path

from repo_assist.activity import collect_activity
from repo_assist.feedback import collect_feedback, resolve_worktree
from repo_assist.github import GitHub


def now() -> datetime:
  return datetime.now(UTC)


def iso(value: datetime) -> str:
  return value.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def root_default() -> Path:
  # Reuse the PR scout root so existing PR history and evidence survive migration.
  configured = os.environ.get("SAFERE_REPO_ASSIST_ROOT") or os.environ.get("SAFERE_PR_REVIEW_ROOT")
  return Path(configured or "~/.codex/safere-pr-review").expanduser()


def ensure_root(root: Path) -> None:
  for child in ("reports", "artifacts", "worktrees", "locks"):
    (root / child).mkdir(parents=True, exist_ok=True)
  state_path = root / "state.json"
  if state_path.exists():
    state = json.loads(state_path.read_text(encoding="utf-8"))
    state.setdefault("prs", {})
    state.setdefault("issues", {})
    state.setdefault("authoredPrFeedback", {})
  else:
    state = {"prs": {}, "issues": {}, "authoredPrFeedback": {}}
  state_path.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")


def begin(args: argparse.Namespace) -> int:
  (args.root / "locks").mkdir(parents=True, exist_ok=True)
  lock = args.root / "locks" / "run.lockdir"
  try:
    lock.mkdir()
  except FileExistsError:
    metadata = lock / "metadata.json"
    sys.stderr.write(metadata.read_text() if metadata.exists() else f"active lock at {lock}\n")
    return 2
  try:
    ensure_root(args.root)
  except Exception:
    lock.rmdir()
    raise
  started = now()
  run_id = started.strftime("%Y-%m-%dT%H%M%SZ")
  metadata = {
    "token": secrets.token_hex(16),
    "runId": run_id,
    "startedAt": iso(started),
    "pid": os.getpid(),
    "reportPath": str(args.root / "reports" / f"{run_id}.md"),
  }
  (lock / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
  report = Path(metadata["reportPath"])
  report.write_text(
    f"# Repo Assist {run_id}\n\nStarted: {iso(started)}\n\nStatus: running\n",
    encoding="utf-8",
  )
  state_path = args.root / "state.json"
  state = json.loads(state_path.read_text(encoding="utf-8"))
  state["lastRunStartedAt"] = iso(started)
  state_path.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
  print(json.dumps(metadata, indent=2))
  return 0


def end(args: argparse.Namespace) -> int:
  lock = args.root / "locks" / "run.lockdir"
  metadata_path = lock / "metadata.json"
  if not metadata_path.exists():
    raise RuntimeError("no active run")
  metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
  if metadata["token"] != args.token:
    raise RuntimeError("lock token mismatch")
  report = Path(metadata["reportPath"])
  interrupted = getattr(args, "interrupted", False)
  if interrupted:
    if report.exists():
      report.write_text(
        report.read_text(encoding="utf-8").replace("Status: running", "Status: interrupted", 1),
        encoding="utf-8",
      )
    shutil.rmtree(lock)
    return 0
  activity_path = lock / "issue-activity.json"
  if not activity_path.exists():
    raise RuntimeError("issue activity collection must complete before ending the run")
  pending = json.loads(activity_path.read_text(encoding="utf-8"))
  if pending["cutoff"] != metadata["startedAt"]:
    raise RuntimeError("pending issue activity belongs to a different run")
  if report.exists():
    text = report.read_text(encoding="utf-8").replace(
      "Status: running", f"Status: completed\n\nCompleted: {iso(now())}", 1
    )
    report.write_text(text, encoding="utf-8")
  state_path = args.root / "state.json"
  state = json.loads(state_path.read_text(encoding="utf-8"))
  state["lastIssueActivityCutoff"] = pending["cutoff"]
  state["issueActivity"] = pending["checkpoint"]
  state["lastRunCompletedAt"] = iso(now())
  state_path.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
  (args.root / "LATEST.md").write_text(f"Latest run report: {report}\n", encoding="utf-8")
  shutil.rmtree(lock)
  return 0


def trust(args: argparse.Namespace) -> int:
  users = GitHub(args.repository).trusted_users()
  print(json.dumps({"trustedAuthors": sorted(users)}, indent=2))
  return 0


def discover(args: argparse.Namespace) -> int:
  github = GitHub(args.repository)
  trusted = github.trusted_users()
  kind = getattr(args, "kind", "pr")
  result = github.discover_items(kind, trusted, args.limit)
  if kind == "pr":
    owner = github.repository_metadata()["owner"]
    login = github.authenticated_login()
    result["repositoryOwner"] = owner
    result["authenticatedLogin"] = login
    result["authored"] = []
    for group in ("trusted", "drafts"):
      result["authored"].extend(pr for pr in result[group] if pr["author"]["login"] == login)
      result[group] = [pr for pr in result[group] if pr["author"]["login"] not in {login, owner}]
    result["authored"].sort(key=lambda pr: pr["number"])
  print(json.dumps({"trustedAuthors": sorted(trusted), **result}, indent=2))
  return 0


def snapshot(args: argparse.Namespace) -> int:
  github = GitHub(args.repository)
  trusted = github.trusted_users()
  kind = getattr(args, "kind", "pr")
  item = (
    github.trusted_pr(args.number, trusted)
    if kind == "pr"
    else github.trusted_item("issue", args.number, trusted)
  )
  changed = item["fingerprint"] != args.previous_fingerprint
  output = {"changed": changed, "fingerprint": item["fingerprint"]}
  if changed or args.force:
    output[kind] = item
  print(json.dumps(output, indent=2))
  return 0


def issue_activity(args: argparse.Namespace) -> int:
  lock = args.root / "locks" / "run.lockdir"
  metadata = json.loads((lock / "metadata.json").read_text(encoding="utf-8"))
  if metadata["token"] != args.token:
    raise RuntimeError("lock token mismatch")
  # A failed retry must not inherit an earlier successful collection in this run.
  (lock / "issue-activity.json").unlink(missing_ok=True)
  state = json.loads((args.root / "state.json").read_text(encoding="utf-8"))
  since = state.get("lastIssueActivityCutoff") or state.get("lastRunCompletedAt")
  recent_days = getattr(args, "recent_days", None)
  scope = {"recent_days": recent_days} if recent_days is not None else {}
  output, checkpoint = collect_activity(
    GitHub(args.repository),
    since,
    metadata["startedAt"],
    state.get("issueActivity", {}),
    **scope,
  )
  # Publish only after all trust checks and pagination complete successfully.
  artifact = args.root / "artifacts" / metadata["runId"] / "issue-activity.json"
  artifact.parent.mkdir(parents=True, exist_ok=True)
  artifact.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
  (lock / "issue-activity.json").write_text(
    json.dumps(
      {
        "cutoff": metadata["startedAt"],
        "checkpoint": checkpoint,
      },
      indent=2,
    )
    + "\n",
    encoding="utf-8",
  )
  print(
    json.dumps(
      {
        "artifact": str(artifact),
        "since": since,
        "cutoff": metadata["startedAt"],
      },
      indent=2,
    )
  )
  return 0


def authored_feedback(args: argparse.Namespace) -> int:
  lock = args.root / "locks" / "run.lockdir"
  metadata = json.loads((lock / "metadata.json").read_text(encoding="utf-8"))
  if metadata["token"] != args.token:
    raise RuntimeError("lock token mismatch")
  state = json.loads((args.root / "state.json").read_text(encoding="utf-8"))
  output = collect_feedback(
    GitHub(args.repository), state.get("authoredPrFeedback", {}), args.limit
  )
  artifact = args.root / "artifacts" / metadata["runId"] / "authored-feedback.json"
  artifact.parent.mkdir(parents=True, exist_ok=True)
  artifact.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
  print(json.dumps({"artifact": str(artifact)}, indent=2))
  return 0


def authored_worktree(args: argparse.Namespace) -> int:
  github = GitHub(args.repository)
  trusted = github.trusted_users()
  login = github.authenticated_login()
  metadata = github.authored_pr_metadata(args.number, login, trusted)
  state_path = args.root / "state.json"
  state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.exists() else {}
  checkpoint = state.get("authoredPrFeedback", {}).get(str(args.number))
  result = resolve_worktree(args.checkout.expanduser(), metadata, checkpoint)
  if github.authored_pr_metadata(args.number, login, trusted) != metadata:
    raise RuntimeError("authored PR changed during worktree discovery; retry")
  print(json.dumps(result, indent=2))
  return 0


def path_command(args: argparse.Namespace) -> int:
  ensure_root(args.root)
  if args.path_kind == "state":
    value = args.root / "state.json"
  elif args.path_kind == "artifact":
    value = args.root / "artifacts" / f"pr-{args.number}" / args.identifier
    value.mkdir(parents=True, exist_ok=True)
  else:
    value = args.root / "worktrees" / f"pr-{args.number}-{args.identifier[:12]}"
  print(value)
  return 0


def parser() -> argparse.ArgumentParser:
  result = argparse.ArgumentParser()
  result.add_argument("--root", type=Path, default=root_default())
  result.add_argument("--repository", default="eaftan/safere")
  commands = result.add_subparsers(required=True)
  start = commands.add_parser("begin-run")
  start.set_defaults(func=begin)
  finish = commands.add_parser("end-run")
  finish.add_argument("--token", required=True)
  finish.add_argument("--interrupted", action="store_true")
  finish.set_defaults(func=end)
  trusted = commands.add_parser("trusted-users")
  trusted.set_defaults(func=trust)
  listing = commands.add_parser("discover")
  listing.add_argument("--limit", type=int, default=1000)
  listing.add_argument("--kind", choices=("pr", "issue"), default="pr")
  listing.set_defaults(func=discover)
  snap = commands.add_parser("snapshot")
  snap.add_argument("number", type=int)
  snap.add_argument("--kind", choices=("pr", "issue"), default="pr")
  snap.add_argument("--previous-fingerprint")
  snap.add_argument("--force", action="store_true")
  snap.set_defaults(func=snapshot)
  activity = commands.add_parser("issue-activity")
  activity.add_argument("--token", required=True)
  activity.add_argument(
    "--recent-days", type=int,
    help="include only newly created open issues or issues with comments posted within this window",
  )
  activity.set_defaults(func=issue_activity)
  feedback = commands.add_parser("authored-feedback")
  feedback.add_argument("--token", required=True)
  feedback.add_argument("--limit", type=int, default=1000)
  feedback.set_defaults(func=authored_feedback)
  original = commands.add_parser("authored-worktree")
  original.add_argument("number", type=int)
  original.add_argument("--checkout", type=Path, required=True)
  original.set_defaults(func=authored_worktree)
  state = commands.add_parser("state-path")
  state.set_defaults(func=path_command, path_kind="state")
  artifact = commands.add_parser("artifact-dir")
  artifact.add_argument("number", type=int)
  artifact.add_argument("identifier")
  artifact.set_defaults(func=path_command, path_kind="artifact")
  worktree = commands.add_parser("worktree-path")
  worktree.add_argument("number", type=int)
  worktree.add_argument("identifier")
  worktree.set_defaults(func=path_command, path_kind="worktree")
  return result


def main() -> int:
  args = parser().parse_args()
  args.root = args.root.expanduser()
  return args.func(args)
