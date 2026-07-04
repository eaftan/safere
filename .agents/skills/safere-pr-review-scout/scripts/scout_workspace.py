#!/usr/bin/env python3
# This file is part of a Java port of RE2 (https://github.com/google/re2).
# Original RE2 code is Copyright (c) 2009 The RE2 Authors.
# Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

"""Workspace helper for the SafeRE PR review scout skill."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import sys
import subprocess
from datetime import UTC, datetime


TRUSTED_AUTHORS = frozenset(("cushon", "eamonnmcmanus", "kluever"))


def utc_now() -> datetime:
    return datetime.now(UTC)


def stamp(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H%M%SZ")


def iso(dt: datetime) -> str:
    return dt.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def default_root() -> Path:
    return Path(os.environ.get("SAFERE_PR_REVIEW_ROOT", "~/.codex/safere-pr-review")).expanduser()


def ensure_root(root: Path) -> None:
    for child in ("reports", "artifacts", "worktrees", "locks"):
        (root / child).mkdir(parents=True, exist_ok=True)
    state = root / "state.json"
    if not state.exists():
        state.write_text(json.dumps({"prs": {}}, indent=2) + "\n", encoding="utf-8")


def begin_run(args: argparse.Namespace) -> int:
    root = args.root
    ensure_root(root)
    locks = root / "locks"
    lock = locks / "run.lockdir"
    token = secrets.token_hex(16)
    now = utc_now()
    run_id = stamp(now)
    report_path = root / "reports" / f"{run_id}.md"

    try:
        lock.mkdir()
    except FileExistsError:
        metadata = lock / "metadata.json"
        if metadata.exists():
            sys.stderr.write(metadata.read_text(encoding="utf-8"))
        else:
            sys.stderr.write(f"active lock exists at {lock}\n")
        return 2

    metadata = {
        "token": token,
        "runId": run_id,
        "startedAt": iso(now),
        "pid": os.getpid(),
        "reportPath": str(report_path),
    }
    (lock / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    report_path.write_text(
        f"# SafeRE PR Review Scout Run {run_id}\n\n"
        f"Started: {iso(now)}\n\n"
        "Status: running\n\n",
        encoding="utf-8",
    )
    latest = root / "LATEST.md"
    latest.write_text(f"Latest run report: {report_path}\n", encoding="utf-8")

    print(json.dumps(metadata, indent=2))
    return 0


def end_run(args: argparse.Namespace) -> int:
    root = args.root
    lock = root / "locks" / "run.lockdir"
    metadata_path = lock / "metadata.json"
    if not metadata_path.exists():
        sys.stderr.write(f"no active lock metadata found at {metadata_path}\n")
        return 2
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    if metadata.get("token") != args.token:
        sys.stderr.write("lock token mismatch; refusing to release lock\n")
        return 2

    report_path = Path(metadata["reportPath"])
    if report_path.exists():
        text = report_path.read_text(encoding="utf-8")
        text = text.replace("Status: running", f"Status: completed\n\nCompleted: {iso(utc_now())}", 1)
        report_path.write_text(text, encoding="utf-8")

    shutil.rmtree(lock)
    print(f"released {lock}")
    return 0


def state_path(args: argparse.Namespace) -> int:
    ensure_root(args.root)
    print(args.root / "state.json")
    return 0


def report_path(args: argparse.Namespace) -> int:
    ensure_root(args.root)
    report = args.root / "reports" / f"{stamp(utc_now())}.md"
    print(report)
    return 0


def artifact_dir(args: argparse.Namespace) -> int:
    ensure_root(args.root)
    path = args.root / "artifacts" / f"pr-{args.pr}" / args.sha
    path.mkdir(parents=True, exist_ok=True)
    print(path)
    return 0


def worktree_path(args: argparse.Namespace) -> int:
    ensure_root(args.root)
    short = args.sha[:12]
    print(args.root / "worktrees" / f"pr-{args.pr}-{short}")
    return 0


def discover_prs(args: argparse.Namespace) -> int:
    ensure_root(args.root)
    command = (
        "gh",
        "pr",
        "list",
        "--state",
        "open",
        "--base",
        args.base,
        "--limit",
        str(args.limit),
        "--json",
        "number,isDraft,headRefName,headRefOid,baseRefName,updatedAt,url,author",
    )
    result = subprocess.run(command, check=True, capture_output=True, text=True)
    prs = json.loads(result.stdout)
    trusted = []
    untrusted = []
    drafts = []
    for pr in prs:
        entry = {
            "number": pr["number"],
            "url": pr["url"],
            "author": pr["author"]["login"],
            "isDraft": pr["isDraft"],
            "headRefName": pr["headRefName"],
            "headRefOid": pr["headRefOid"],
            "baseRefName": pr["baseRefName"],
            "updatedAt": pr["updatedAt"],
        }
        if entry["isDraft"]:
            drafts.append(entry)
        elif entry["author"] in TRUSTED_AUTHORS:
            trusted.append(entry)
        else:
            untrusted.append(entry)

    trusted.sort(key=lambda item: item["number"])
    untrusted.sort(key=lambda item: item["number"])
    drafts.sort(key=lambda item: item["number"])

    untrusted_path = args.root / "UNTRUSTED_CONTRIBUTORS.md"
    if untrusted:
        lines = [
            "# Untrusted Contributor Candidates",
            "",
            "These open non-draft PRs were not inspected because the author is not on the",
            "trusted contributor allowlist.",
            "",
            "| PR | Author | URL | Action Needed |",
            "|---:|---|---|---|",
        ]
        for entry in untrusted:
            lines.append(
                f"| #{entry['number']} | `{entry['author']}` | {entry['url']} | "
                "Human decides whether to add this contributor to the allowlist. |"
            )
        untrusted_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    elif untrusted_path.exists():
        untrusted_path.unlink()

    print(
        json.dumps(
            {
                "trustedAuthors": sorted(TRUSTED_AUTHORS),
                "trusted": trusted,
                "untrusted": untrusted,
                "drafts": drafts,
                "untrustedReportPath": str(untrusted_path) if untrusted else None,
            },
            indent=2,
        )
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=default_root())
    subparsers = parser.add_subparsers(dest="command", required=True)

    begin = subparsers.add_parser("begin-run")
    begin.set_defaults(func=begin_run)

    end = subparsers.add_parser("end-run")
    end.add_argument("--token", required=True)
    end.set_defaults(func=end_run)

    state = subparsers.add_parser("state-path")
    state.set_defaults(func=state_path)

    report = subparsers.add_parser("report-path")
    report.set_defaults(func=report_path)

    artifact = subparsers.add_parser("artifact-dir")
    artifact.add_argument("--pr", required=True)
    artifact.add_argument("--sha", required=True)
    artifact.set_defaults(func=artifact_dir)

    worktree = subparsers.add_parser("worktree-path")
    worktree.add_argument("--pr", required=True)
    worktree.add_argument("--sha", required=True)
    worktree.set_defaults(func=worktree_path)

    discover = subparsers.add_parser("discover-prs")
    discover.add_argument("--base", default="main")
    discover.add_argument("--limit", type=int, default=1000)
    discover.set_defaults(func=discover_prs)

    args = parser.parse_args()
    args.root = args.root.expanduser()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
