#!/usr/bin/env python3
# This file is part of a Java port of RE2 (https://github.com/google/re2).
# Original RE2 code is Copyright (c) 2009 The RE2 Authors.
# Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

"""Workspace helper for the SafeRE daily repo assist skill."""

from __future__ import annotations

import argparse
from datetime import UTC, datetime
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys


TRUSTED_AUTHORS = frozenset(("cushon", "eamonnmcmanus", "kluever"))


def utc_now() -> datetime:
    return datetime.now(UTC)


def stamp(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H%M%SZ")


def iso(dt: datetime) -> str:
    return dt.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def default_root() -> Path:
    return Path(
        os.environ.get("SAFERE_DAILY_REPO_ASSIST_ROOT", "~/.codex/safere-daily-repo-assist")
    ).expanduser()


def ensure_root(root: Path) -> None:
    for child in ("reports", "artifacts", "worktrees", "locks"):
        (root / child).mkdir(parents=True, exist_ok=True)
    state = root / "state.json"
    if not state.exists():
        state.write_text(json.dumps({"prs": {}, "issues": {}}, indent=2) + "\n", encoding="utf-8")


def begin_run(args: argparse.Namespace) -> int:
    root = args.root
    ensure_root(root)
    lock = root / "locks" / "run.lockdir"
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
    state_path = root / "state.json"
    state = json.loads(state_path.read_text(encoding="utf-8"))
    state["lastRunStartedAt"] = iso(now)
    state_path.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    report_path.write_text(
        f"# SafeRE Daily Repo Assist {run_id}\n\n"
        f"Started: {iso(now)}\n\n"
        "Status: running\n\n",
        encoding="utf-8",
    )
    (root / "LATEST.md").write_text(f"Latest run report: {report_path}\n", encoding="utf-8")
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

    completed = iso(utc_now())
    report_path = Path(metadata["reportPath"])
    if report_path.exists():
        text = report_path.read_text(encoding="utf-8")
        text = text.replace("Status: running", f"Status: completed\n\nCompleted: {completed}", 1)
        report_path.write_text(text, encoding="utf-8")

    state_path = root / "state.json"
    if state_path.exists():
        state = json.loads(state_path.read_text(encoding="utf-8"))
        state["lastRunCompletedAt"] = completed
        state_path.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")

    shutil.rmtree(lock)
    print(f"released {lock}")
    return 0


def state_path(args: argparse.Namespace) -> int:
    ensure_root(args.root)
    print(args.root / "state.json")
    return 0


def artifact_dir(args: argparse.Namespace) -> int:
    ensure_root(args.root)
    path = args.root / "artifacts" / args.kind / str(args.number) / args.identifier
    path.mkdir(parents=True, exist_ok=True)
    print(path)
    return 0


def worktree_path(args: argparse.Namespace) -> int:
    ensure_root(args.root)
    print(args.root / "worktrees" / f"pr-{args.pr}-{args.sha[:12]}")
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
            "updatedAt": pr["updatedAt"],
        }
        trusted_author = entry["author"] in TRUSTED_AUTHORS
        if trusted_author:
            entry.update(
                {
                    "headRefName": pr["headRefName"],
                    "headRefOid": pr["headRefOid"],
                    "baseRefName": pr["baseRefName"],
                }
            )
        else:
            entry["actionNeeded"] = "Human decides whether to add this contributor to the allowlist."
        if entry["isDraft"]:
            drafts.append(entry)
        elif trusted_author:
            trusted.append(entry)
        else:
            untrusted.append(entry)

    trusted.sort(key=lambda item: item["number"])
    untrusted.sort(key=lambda item: item["number"])
    drafts.sort(key=lambda item: item["number"])
    print(
        json.dumps(
            {
                "trustedAuthors": sorted(TRUSTED_AUTHORS),
                "trusted": trusted,
                "untrusted": untrusted,
                "drafts": drafts,
            },
            indent=2,
        )
    )
    return 0


def discover_issues(args: argparse.Namespace) -> int:
    ensure_root(args.root)
    search = f"updated:>={args.since}"
    command = (
        "gh",
        "issue",
        "list",
        "--state",
        "all",
        "--limit",
        str(args.limit),
        "--search",
        search,
        "--json",
        "number,state,url,author,updatedAt,closedAt",
    )
    result = subprocess.run(command, check=True, capture_output=True, text=True)
    issues = json.loads(result.stdout)
    trusted = []
    untrusted = []
    for issue in issues:
        entry = {
            "number": issue["number"],
            "url": issue["url"],
            "author": issue["author"]["login"],
            "state": issue["state"],
            "updatedAt": issue["updatedAt"],
            "closedAt": issue.get("closedAt"),
        }
        if entry["author"] in TRUSTED_AUTHORS:
            trusted.append(entry)
        else:
            untrusted.append(entry)

    trusted.sort(key=lambda item: item["number"])
    untrusted.sort(key=lambda item: item["number"])
    print(
        json.dumps(
            {
                "trustedAuthors": sorted(TRUSTED_AUTHORS),
                "since": args.since,
                "trusted": trusted,
                "untrusted": untrusted,
            },
            indent=2,
        )
    )
    return 0


def is_trusted_author(item: dict) -> bool:
    author = item.get("author") or {}
    return author.get("login") in TRUSTED_AUTHORS


def strip_untrusted_body(item: dict) -> dict:
    filtered = dict(item)
    if not is_trusted_author(filtered):
        filtered.pop("body", None)
        filtered["bodyOmitted"] = "author not on trusted contributor allowlist"
    return filtered


def run_json(command: tuple[str, ...]) -> dict:
    result = subprocess.run(command, check=True, capture_output=True, text=True)
    return json.loads(result.stdout)


def view_pr(args: argparse.Namespace) -> int:
    metadata = run_json(
        (
            "gh",
            "pr",
            "view",
            str(args.pr),
            "--json",
            "number,url,author",
        )
    )
    if not is_trusted_author(metadata):
        print(
            json.dumps(
                {
                    "number": metadata["number"],
                    "url": metadata["url"],
                    "author": metadata["author"],
                    "trusted": False,
                    "bodyOmitted": "author not on trusted contributor allowlist",
                },
                indent=2,
            )
        )
        return 0

    command = (
        "gh",
        "pr",
        "view",
        str(args.pr),
        "--json",
        "number,title,url,body,labels,author,headRefName,headRefOid,baseRefName,updatedAt,comments,reviews",
    )
    pr = run_json(command)
    pr["trusted"] = True
    pr["comments"] = [strip_untrusted_body(comment) for comment in pr.get("comments", [])]
    pr["reviews"] = [strip_untrusted_body(review) for review in pr.get("reviews", [])]
    print(json.dumps(pr, indent=2))
    return 0


def view_issue(args: argparse.Namespace) -> int:
    metadata = run_json(
        (
            "gh",
            "issue",
            "view",
            str(args.issue),
            "--json",
            "number,url,state,author",
        )
    )
    if not is_trusted_author(metadata):
        print(
            json.dumps(
                {
                    "number": metadata["number"],
                    "url": metadata["url"],
                    "author": metadata["author"],
                    "state": metadata["state"],
                    "trusted": False,
                    "bodyOmitted": "author not on trusted contributor allowlist",
                },
                indent=2,
            )
        )
        return 0

    command = (
        "gh",
        "issue",
        "view",
        str(args.issue),
        "--json",
        "number,title,url,state,body,author,labels,createdAt,updatedAt,closedAt,comments",
    )
    issue = run_json(command)
    issue["trusted"] = True
    issue["comments"] = [strip_untrusted_body(comment) for comment in issue.get("comments", [])]
    print(json.dumps(issue, indent=2))
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

    artifact = subparsers.add_parser("artifact-dir")
    artifact.add_argument("--kind", choices=("pr", "issue"), required=True)
    artifact.add_argument("--number", required=True)
    artifact.add_argument("--identifier", required=True)
    artifact.set_defaults(func=artifact_dir)

    worktree = subparsers.add_parser("worktree-path")
    worktree.add_argument("--pr", required=True)
    worktree.add_argument("--sha", required=True)
    worktree.set_defaults(func=worktree_path)

    discover_prs_parser = subparsers.add_parser("discover-prs")
    discover_prs_parser.add_argument("--base", default="main")
    discover_prs_parser.add_argument("--limit", type=int, default=1000)
    discover_prs_parser.set_defaults(func=discover_prs)

    discover_issues_parser = subparsers.add_parser("discover-issues")
    discover_issues_parser.add_argument("--since", required=True)
    discover_issues_parser.add_argument("--limit", type=int, default=1000)
    discover_issues_parser.set_defaults(func=discover_issues)

    view_pr_parser = subparsers.add_parser("view-pr")
    view_pr_parser.add_argument("--pr", required=True)
    view_pr_parser.set_defaults(func=view_pr)

    view_issue_parser = subparsers.add_parser("view-issue")
    view_issue_parser.add_argument("--issue", required=True)
    view_issue_parser.set_defaults(func=view_issue)

    args = parser.parse_args()
    args.root = args.root.expanduser()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
