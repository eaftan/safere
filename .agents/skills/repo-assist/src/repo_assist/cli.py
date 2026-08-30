# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

"""Command-line support for the repo-assist skill."""

from __future__ import annotations

import argparse
from datetime import UTC, datetime
import json
import os
from pathlib import Path
import secrets
import shutil
import sys

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
  else:
    state = {"prs": {}, "issues": {}}
  state_path.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")


def begin(args: argparse.Namespace) -> int:
  ensure_root(args.root)
  lock = args.root / "locks" / "run.lockdir"
  try:
    lock.mkdir()
  except FileExistsError:
    metadata = lock / "metadata.json"
    sys.stderr.write(metadata.read_text() if metadata.exists() else f"active lock at {lock}\n")
    return 2
  started = now()
  run_id = started.strftime("%Y-%m-%dT%H%M%SZ")
  metadata = {"token": secrets.token_hex(16), "runId": run_id, "startedAt": iso(started),
              "pid": os.getpid(), "reportPath": str(args.root / "reports" / f"{run_id}.md")}
  (lock / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
  report = Path(metadata["reportPath"])
  report.write_text(f"# Repo Assist {run_id}\n\nStarted: {iso(started)}\n\nStatus: running\n",
                    encoding="utf-8")
  (args.root / "LATEST.md").write_text(f"Latest run report: {report}\n", encoding="utf-8")
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
  if report.exists():
    text = report.read_text(encoding="utf-8").replace(
        "Status: running", f"Status: completed\n\nCompleted: {iso(now())}", 1)
    report.write_text(text, encoding="utf-8")
  state_path = args.root / "state.json"
  state = json.loads(state_path.read_text(encoding="utf-8"))
  state["lastRunCompletedAt"] = iso(now())
  state_path.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
  shutil.rmtree(lock)
  return 0


def trust(args: argparse.Namespace) -> int:
  users = GitHub(args.repository).trusted_users()
  print(json.dumps({"trustedAuthors": sorted(users)}, indent=2))
  return 0


def discover(args: argparse.Namespace) -> int:
  github = GitHub(args.repository)
  trusted = github.trusted_users()
  result = github.discover(args.kind, trusted, args.limit)
  print(json.dumps({"trustedAuthors": sorted(trusted), **result}, indent=2))
  return 0


def snapshot(args: argparse.Namespace) -> int:
  github = GitHub(args.repository)
  trusted = github.trusted_users()
  item = github.trusted_item(args.kind, args.number, trusted)
  changed = item["fingerprint"] != args.previous_fingerprint
  output = {"changed": changed, "fingerprint": item["fingerprint"]}
  if changed or args.force:
    output[args.kind] = item
  print(json.dumps(output, indent=2))
  return 0


def path_command(args: argparse.Namespace) -> int:
  ensure_root(args.root)
  if args.path_kind == "state":
    value = args.root / "state.json"
  elif args.path_kind == "artifact":
    value = args.root / "artifacts" / f"{args.item_kind}-{args.number}" / args.identifier
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
  finish.set_defaults(func=end)
  trusted = commands.add_parser("trusted-users")
  trusted.set_defaults(func=trust)
  listing = commands.add_parser("discover")
  listing.add_argument("kind", choices=("pr", "issue"))
  listing.add_argument("--limit", type=int, default=1000)
  listing.set_defaults(func=discover)
  snap = commands.add_parser("snapshot")
  snap.add_argument("kind", choices=("pr", "issue"))
  snap.add_argument("number", type=int)
  snap.add_argument("--previous-fingerprint")
  snap.add_argument("--force", action="store_true")
  snap.set_defaults(func=snapshot)
  state = commands.add_parser("state-path")
  state.set_defaults(func=path_command, path_kind="state")
  artifact = commands.add_parser("artifact-dir")
  artifact.add_argument("item_kind", choices=("pr", "issue"))
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
