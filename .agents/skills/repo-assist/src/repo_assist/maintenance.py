# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

"""Reusable local review/report mechanics; GitHub trust remains in the existing helper."""

import argparse
from datetime import UTC, datetime
import itertools
import json
from pathlib import Path
import re
import subprocess
import sys

from repo_assist.cli import root_default

SHA = re.compile(r"[0-9a-f]{40}(?:[0-9a-f]{24})?\Z")
ANCHOR = re.compile(r'<a id="pr-(\d+)"></a>')
TERMINAL = {"reviewed", "blocked", "defer"}


def read_json(path):
  return json.loads(Path(path).read_text(encoding="utf-8"))


def atomic_text(path, value):
  path = Path(path)
  path.parent.mkdir(parents=True, exist_ok=True)
  temporary = path.with_name(path.name + ".tmp")
  temporary.write_text(value, encoding="utf-8")
  temporary.replace(path)


def write_json(path, value):
  atomic_text(path, json.dumps(value, indent=2) + "\n")


def git(repository, *args, allowed=(0,)):
  result = subprocess.run(
    ["git", "-C", str(repository), *args], capture_output=True, text=True
  )
  if result.returncode not in allowed:
    raise ValueError(result.stderr.strip() or result.stdout.strip() or "git command failed")
  return result


def eligible(discovery):
  """Use only the contributor queue produced by repo-assist discover."""
  numbers = {}
  excluded = {discovery["repositoryOwner"], discovery["authenticatedLogin"]}
  for item in discovery["trusted"]:
    number = item["number"]
    if not isinstance(number, int) or isinstance(number, bool) or number <= 0:
      raise ValueError("invalid PR number in discovery")
    if item["isDraft"] or item["author"]["login"] in excluded or number in numbers:
      raise ValueError("discovery contributor queue contains excluded or duplicate PR")
    numbers[number] = item
  return numbers


def outside_fences(text):
  """Mask fenced examples while keeping original offsets for report editing."""
  masked = []
  fence = None
  for line in text.splitlines(keepends=True):
    marker = re.match(r"^[ \t]*(`{3,}|~{3,})", line)
    if fence is None and marker:
      fence = marker[1]
      masked.append(re.sub(r"[^\n]", " ", line))
    elif fence is not None:
      masked.append(re.sub(r"[^\n]", " ", line))
      if re.fullmatch(r"[ \t]*" + re.escape(fence[0]) + "{" + str(len(fence)) + r",}[ \t]*\n?", line):
        fence = None
    else:
      masked.append(line)
  return "".join(masked)


def section_span(report, number):
  visible = outside_fences(report)
  matches = [m for m in ANCHOR.finditer(visible) if int(m[1]) == number]
  if len(matches) > 1:
    raise ValueError(f"duplicate PR anchor: {number}")
  if not matches:
    return None
  start = matches[0].start()
  heading = re.search(r"^## PR #[^\n]+", visible[matches[0].end():], re.M)
  if not heading or report[matches[0].end():heading.start() + matches[0].end()].strip():
    raise ValueError(f"PR anchor lacks its heading: {number}")
  after_heading = matches[0].end() + heading.end()
  boundary = re.search(r'^<a id="|^## ', visible[after_heading:], re.M)
  return start, after_heading + boundary.start() if boundary else len(report)


def copy_text(section):
  matches = list(re.finditer(
    r"^### Copy/Paste PR Review\s+(?P<fence>`{3,})markdown\n(?P<copy>.*?)\n(?P=fence)[ \t]*(?:\n|$)",
    section, re.M | re.S,
  ))
  if len(matches) != 1 or not matches[0]["copy"].strip():
    raise ValueError("PR section must contain one nonempty fenced author review")
  return matches[0]["copy"]


def summary_span(report):
  visible = outside_fences(report)
  match = re.search(r"^## PR Summary\s*\n", visible, re.M)
  if not match:
    raise ValueError("report lacks PR Summary")
  end = re.search(r"^## ", visible[match.end():], re.M)
  return match.end(), match.end() + end.start() if end else len(report)


def checkpoint(args):
  root = args.root.resolve()
  metadata = read_json(root / "locks/run.lockdir/metadata.json")
  if metadata["token"] != args.token:
    raise ValueError("active run token mismatch")
  report_path = Path(metadata["reportPath"])
  report = report_path.read_text(encoding="utf-8")
  if "\nStatus: running\n" not in report:
    raise ValueError("checkpoint requires the active running report")
  spec = read_json(args.spec)
  number = spec["number"]
  item = eligible(read_json(args.discovery)).get(number)
  if item is None:
    raise ValueError("PR is not in sanitized contributor discovery")
  snapshot = read_json(args.snapshot)
  pr = snapshot.get("pr")
  if pr is None or pr["number"] != number:
    raise ValueError("provide a full sanitized snapshot (snapshot --force if unchanged)")
  record = spec["record"]
  if record["status"] not in TERMINAL or record["classification"] not in {"other", "optimization"}:
    raise ValueError("invalid terminal status or classification")
  for field in ("lastHeadSha", "lastBaseSha", "lastTrunkSha"):
    if not SHA.fullmatch(record[field]):
      raise ValueError(f"{field} must be an immutable commit SHA")
  if record["status"] == "reviewed":
    for field in ("preparedHeadSha", "lastFixCommit", "lastFixBranch"):
      if field not in record:
        raise ValueError(f"reviewed record must explicitly include {field}")
    if not SHA.fullmatch(record["preparedHeadSha"]):
      raise ValueError("preparedHeadSha must be an immutable final review SHA")
    if record["lastFixCommit"] is not None and not SHA.fullmatch(record["lastFixCommit"]):
      raise ValueError("lastFixCommit must be null or an immutable SHA")
  if record["lastHeadSha"] != item["headRefOid"]:
    raise ValueError("record head does not match discovery")
  if record["lastSeenUpdatedAt"] != item["updatedAt"] or pr["updatedAt"] != item["updatedAt"]:
    raise ValueError("snapshot/discovery/record discussion timestamps disagree")
  if record["fingerprint"] != snapshot["fingerprint"]:
    raise ValueError("record fingerprint does not match sanitized snapshot")
  section = args.section.read_text(encoding="utf-8").strip()
  author_copy = args.author_copy.read_text(encoding="utf-8").strip()
  if not section.startswith(f"## PR #{number}:") or ANCHOR.search(section):
    raise ValueError("section must start with its PR heading and contain no PR anchors")
  if "### Copy/Paste PR Review" in section:
    raise ValueError("provide section and unfenced author review separately")
  fence = "`" * max(3, max((len(run) + 1 for run in re.findall(r"`{3,}", author_copy)), default=3))
  replacement = (
    f'<a id="pr-{number}"></a>\n{section}\n\n### Copy/Paste PR Review\n\n'
    f"{fence}markdown\n{author_copy}\n{fence}\n\n"
  )
  copy_text(replacement)
  span = section_span(report, number)
  report = report[:span[0]] + replacement + report[span[1]:] if span else report.rstrip() + "\n\n" + replacement
  start, end = summary_span(report)
  table = report[start:end]
  row = re.compile(r'^\|([^|]*)\|[^\n]*\]\(#pr-' + str(number) + r'\)[^\n]*$', re.M)
  rows = list(row.finditer(table))
  if len(rows) != 1:
    raise ValueError("PR must already have exactly one summary row")
  def cell(value):
    if "\n" in value or "\r" in value:
      raise ValueError("summary cells must be single-line text")
    return value.replace("|", "\\|")
  new_row = (
    f"|{rows[0][1]}| [PR #{number}](#pr-{number}) | {cell(spec['assessment'])} | "
    f"{cell(spec['recommendation'])} |"
  )
  table = table[:rows[0].start()] + new_row + table[rows[0].end():]
  report = report[:start] + table + report[end:]
  state_path = root / "state.json"
  state = read_json(state_path)
  previous = state.setdefault("prs", {}).get(str(number), {})
  state["prs"][str(number)] = {
    **previous, **record, "lastReport": str(report_path),
    "lastReviewedAt": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
  }
  artifact = root / "artifacts" / metadata["runId"] / f"pr-{number}"
  # Save the complete proposed update first. Repeating the same command replaces the section;
  # it does not append duplicates and can recover a report/state write interrupted mid-checkpoint.
  write_json(artifact / "checkpoint.json", {"spec": spec, "reportPath": str(report_path), "record": state["prs"][str(number)]})
  atomic_text(artifact / "copy-paste-review.md", author_copy + "\n")
  atomic_text(report_path, report)
  write_json(state_path, state)
  return {"number": number, "status": record["status"], "report": str(report_path)}


def freshness_problems(number, item, entry):
  """Require the saved assessment to describe the current head and discussion."""
  return [
    f"PR #{number}: {field} is missing or differs from current discovery"
    for field, key in (("lastHeadSha", "headRefOid"), ("lastSeenUpdatedAt", "updatedAt"))
    if not entry.get(field) or entry[field] != item[key]
  ]


def audit(args):
  report = args.report.read_text(encoding="utf-8")
  state = read_json(args.root / "state.json")
  queue = eligible(read_json(args.discovery))
  found = [int(m[1]) for m in ANCHOR.finditer(outside_fences(report))]
  problems = []
  if len(found) != len(set(found)) or set(found) != set(queue):
    problems.append("PR detail anchors do not match the current contributor queue exactly")
  start, end = summary_span(report)
  summary_numbers = re.findall(r'\]\(#pr-(\d+)\)', report[start:end])
  if sorted(map(int, summary_numbers)) != sorted(queue):
    problems.append("PR summary rows do not match the queue exactly")
  copies = {}
  for number in queue:
    entry = state.get("prs", {}).get(str(number), {})
    if entry.get("status") not in TERMINAL:
      problems.append(f"PR #{number} has no terminal state")
    else:
      problems.extend(freshness_problems(number, queue[number], entry))
    try:
      span = section_span(report, number)
    except ValueError as error:
      problems.append(f"PR #{number}: {error}")
      continue
    if span is None:
      continue
    try:
      text = copy_text(report[span[0]:span[1]])
    except ValueError as error:
      problems.append(f"PR #{number}: {error}")
      continue
    copies[str(number)] = text
    if re.search(r"\b(?:worktrees?|artifact (?:directory|path)|review[- ]agent|mvn)\b|local[- ]only", text, re.I):
      problems.append(f"PR #{number}: author review includes internal bookkeeping")
    for paragraph in outside_fences(text).split("\n\n"):
      if paragraph.strip() and "\n" in paragraph and not re.match(r"\s*(?:\||[-*] |\d+[.)] |```)", paragraph):
        problems.append(f"PR #{number}: author prose is hard-wrapped")
    section = report[span[0]:span[1]]
    measured = re.search(r"^### Benchmark Reproduction\n(.*?)(?=^### |\Z)", section, re.M | re.S)
    if measured and re.search(r"^\|[^\n]*\d", measured[1], re.M):
      if not re.search(r"^\|\s*:?-", text, re.M):
        problems.append(f"PR #{number}: detailed benchmark table is missing from the author review")
    if args.output_dir:
      atomic_text(args.output_dir / f"author-copy-{number}.md", text + "\n")
  if args.check_worktrees:
    worktrees = git(args.repository, "worktree", "list", "--porcelain").stdout.split("\n\n")
    for number in queue:
      entry = state.get("prs", {}).get(str(number), {})
      if entry.get("status") != "reviewed" or freshness_problems(number, queue[number], entry):
        continue
      sha = entry.get("lastFixCommit") or entry.get("preparedHeadSha")
      if not sha or not SHA.fullmatch(sha):
        problems.append(f"PR #{number}: exact final review head is missing")
        continue
      matches = [block for block in worktrees if f"HEAD {sha}\n" in block + "\n"]
      if not matches:
        problems.append(f"PR #{number}: no worktree at final head")
      else:
        paths = [block.splitlines()[0].removeprefix("worktree ") for block in matches]
        if not any(not git(path, "status", "--porcelain").stdout for path in paths):
          problems.append(f"PR #{number}: all matching review worktrees are dirty")
      trunk = entry.get("lastTrunkSha")
      if not trunk or not SHA.fullmatch(trunk):
        problems.append(f"PR #{number}: recorded trunk SHA is missing")
      elif git(args.repository, "merge-base", "--is-ancestor", trunk, sha, allowed=(0, 1)).returncode:
        problems.append(f"PR #{number}: final tree does not include its recorded trunk")
  return {
    "mechanicalChecksPassed": not problems, "problems": problems,
    "authorCopies": sorted(map(int, copies)),
    "semanticAuditRequired": "Read each extracted review alone; this script cannot assess factual completeness, tone, recommendations, or numeric claims in prose.",
  }


def merge_order(args):
  state = read_json(args.root / "state.json")
  queue = eligible(read_json(args.discovery))
  heads = {}
  files = {}
  for number in queue:
    entry = state.get("prs", {}).get(str(number), {})
    if entry.get("status") != "reviewed":
      continue
    stale = freshness_problems(number, queue[number], entry)
    if stale:
      raise ValueError("; ".join(stale))
    sha = entry.get("lastFixCommit") or entry.get("preparedHeadSha")
    trunk = entry.get("lastTrunkSha")
    if not sha or not SHA.fullmatch(sha) or not trunk or not SHA.fullmatch(trunk):
      raise ValueError(f"PR #{number} lacks immutable prepared head/trunk")
    git(args.repository, "cat-file", "-e", sha + "^{commit}")
    heads[number] = sha
    files[number] = set(git(args.repository, "diff", "--name-only", trunk + "..." + sha).stdout.splitlines())
  pairs = []
  for a, b in itertools.combinations(heads, 2):
    merged = git(args.repository, "merge-tree", "--write-tree", heads[a], heads[b], allowed=(0, 1))
    ancestry = []
    for lower, upper in ((a, b), (b, a)):
      if not git(args.repository, "merge-base", "--is-ancestor", heads[lower], heads[upper], allowed=(0, 1)).returncode:
        ancestry.append([lower, upper])
    shared = sorted(files[a] & files[b])
    if merged.returncode or ancestry or shared:
      pairs.append({"a": a, "b": b, "conflict": bool(merged.returncode), "details": merged.stdout, "ancestry": ancestry, "sharedFiles": shared})
  return {"preparedFinalHeads": heads, "pairs": pairs, "excludedWithoutReviewedTree": sorted(set(queue) - set(heads)), "note": "Textual conflicts and ancestry only; shared files do not establish a semantic dependency. merge-tree writes Git objects but does not modify checkouts or refs."}


def refresh(args):
  state = read_json(args.root / "state.json")
  # Invoke the loaded skill's CLI in-process via a child interpreter; no alternative content API.
  invocation = [sys.executable, "-c", f"import sys; sys.path.insert(0, {str(Path(__file__).resolve().parents[1])!r}); from repo_assist.cli import main; raise SystemExit(main())", "--root", str(args.root), "--repository", args.repository_name]
  discovery = json.loads(subprocess.check_output(invocation + ["discover", "--limit", "1000"], text=True))
  queue = eligible(discovery)
  if args.output_dir:
    write_json(args.output_dir / "discovery.json", discovery)
  checks = []
  for number, item in queue.items():
    entry = state.get("prs", {}).get(str(number), {})
    command = invocation + ["snapshot", str(number)]
    if entry.get("fingerprint"):
      command += ["--previous-fingerprint", entry["fingerprint"]]
    snapshot = json.loads(subprocess.check_output(command, text=True))
    if args.output_dir:
      write_json(args.output_dir / f"snapshot-{number}.json", snapshot)
    checks.append({"number": number, "new": not entry, "discussionChanged": snapshot["changed"], "headChanged": item["headRefOid"] != entry.get("lastHeadSha"), "updatedChanged": item["updatedAt"] != entry.get("lastSeenUpdatedAt")})
  return {"checks": checks, "changed": any(any(item[key] for key in ("new", "discussionChanged", "headChanged", "updatedChanged")) for item in checks), "note": "Changed entries require delta triage, not an automatic full re-review. Base/trunk and official stack metadata must also be refreshed separately."}


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--root", type=Path, default=root_default())
  commands = parser.add_subparsers(dest="command", required=True)
  save = commands.add_parser("checkpoint", help="Idempotently checkpoint one eligible contributor assessment")
  for name in ("discovery", "snapshot", "spec", "section", "author-copy"):
    save.add_argument("--" + name, type=Path, required=True)
  save.add_argument("--token", required=True)
  save.set_defaults(func=checkpoint)
  check = commands.add_parser("audit", help="Check structure/state and extract author reviews for semantic reading")
  check.add_argument("--discovery", type=Path, required=True)
  check.add_argument("--report", type=Path, required=True)
  check.add_argument("--repository", type=Path, default=Path.cwd())
  check.add_argument("--check-worktrees", action="store_true")
  check.add_argument("--output-dir", type=Path)
  check.set_defaults(func=audit)
  order = commands.add_parser("merge-order", help="Check pairwise prepared heads; write no branch/checkouts")
  order.add_argument("--discovery", type=Path, required=True)
  order.add_argument("--repository", type=Path, default=Path.cwd())
  order.set_defaults(func=merge_order)
  fresh = commands.add_parser("refresh", help="Refresh discovery and sanitized snapshots through the trust helper")
  fresh.add_argument("--repository-name", default="eaftan/safere")
  fresh.add_argument("--output-dir", type=Path)
  fresh.set_defaults(func=refresh)
  args = parser.parse_args()
  args.root = args.root.expanduser()
  try:
    result = args.func(args)
  except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
    parser.exit(2, f"{error}\n")
  print(json.dumps(result, indent=2))
  return int(result.get("changed", False) or result.get("mechanicalChecksPassed") is False)
