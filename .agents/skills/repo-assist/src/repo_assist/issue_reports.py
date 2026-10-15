# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

"""Checkpoint fresh issue assessments and mechanically carry unchanged ones forward."""

import argparse
from datetime import UTC, datetime
import json
from pathlib import Path
import re

from repo_assist.cli import root_default
from repo_assist.github import fingerprint
from repo_assist.maintenance import atomic_text, outside_fences, read_json, write_json

HEADER = "| Done | Issue | Activity | Addressing PRs / Coverage | Action Needed |"
SEPARATOR = "|---|---|---|---|---|"


def issue_block(report):
  visible = outside_fences(report)
  headings = list(re.finditer(r"^## Issues[ \t]*\n", visible, re.M))
  if len(headings) != 1:
    raise ValueError("report requires exactly one ## Issues section")
  start = headings[0].end()
  end = re.search(r"^## ", visible[start:], re.M)
  return start, start + end.start() if end else len(report)


def issue_section(report, number):
  start, end = issue_block(report)
  visible = outside_fences(report)[start:end]
  anchors = list(re.finditer(r'^<a id="issue-' + str(number) + r'"></a>[ \t]*$', visible, re.M))
  if len(anchors) > 1:
    raise ValueError(f"duplicate issue anchor: {number}")
  if not anchors:
    return None
  anchor = anchors[0]
  heading = re.match(r"\s*### Issue #" + str(number) + r"(?:[: \t][^\n]*)?\n", visible[anchor.end():])
  if not heading:
    raise ValueError(f"issue anchor lacks its matching heading: {number}")
  after = anchor.end() + heading.end()
  boundary = re.search(r'^<a id="issue-|^### Issue #', visible[after:], re.M)
  return start + anchor.start(), start + after + boundary.start() if boundary else end


def issue_row(report, number):
  start, end = issue_block(report)
  matches = list(re.finditer(
    r'^\|[^\n]*\]\(#issue-' + str(number) + r'\)[^\n]*$',
    outside_fences(report)[start:end], re.M,
  ))
  if len(matches) > 1:
    raise ValueError(f"duplicate issue summary row: {number}")
  if not matches:
    return None
  match = matches[0]
  cells = re.split(r"(?<!\\)\|", report[start + match.start():start + match.end()])
  if len(cells) != 7 or cells[0].strip() or cells[-1].strip():
    raise ValueError(f"issue summary row must have five columns: {number}")
  return start + match.start(), start + match.end(), cells


def report_path(root, value):
  path = Path(value).resolve()
  if not path.is_relative_to((root / "reports").resolve()):
    raise ValueError("issue assessments must refer to reports within the review root")
  return path


def context(args):
  root = args.root.resolve()
  lock = root / "locks/run.lockdir"
  metadata = read_json(lock / "metadata.json")
  if metadata["token"] != args.token:
    raise ValueError("active run token mismatch")
  activity = read_json(args.activity)
  digest = fingerprint({key: value for key, value in activity.items() if key != "fingerprint"})
  pending = read_json(lock / "issue-activity.json")
  if (not activity.get("fingerprint") or digest != activity["fingerprint"]
      or pending.get("artifactFingerprint") != digest
      or activity["cutoff"] != metadata["startedAt"]
      or pending["cutoff"] != metadata["startedAt"]):
    raise ValueError("activity artifact is not the latest successful collection for this run")
  path = report_path(root, metadata["reportPath"])
  report = path.read_text(encoding="utf-8")
  if "\nStatus: running\n" not in report:
    raise ValueError("issue updates require the active running report")
  state = read_json(root / "state.json")
  items = {}
  for item in activity["issues"]:
    number = item["metadata"]["number"]
    if number in items:
      raise ValueError("duplicate issue in activity artifact")
    saved = pending["checkpoint"].get(str(number), {})
    if any(not item.get(field) or item[field] != saved.get(field)
           for field in ("evidenceFingerprint", "contentFingerprint")):
      raise ValueError("issue evidence does not match pending collection")
    items[number] = item
  return root, path, report, state, items


def update(args):
  root, path, report, state, items = context(args)
  requested = args.issues
  if requested is None:
    if args.command == "checkpoint":
      raise ValueError("checkpoint requires explicit freshly assessed --issues")
    requested = [n for n, item in items.items() if item["assessmentAction"] == "reuse"]
  if len(requested) != len(set(requested)) or any(n not in items for n in requested):
    raise ValueError("requested issues must occur exactly once in the current activity")
  entries = state.setdefault("issues", {})
  now = datetime.now(UTC).isoformat().replace("+00:00", "Z")
  # Validate the entire batch in memory before writing either file. Prior reports
  # are never opened until fresh evidence and saved assessment bindings agree.
  for number in requested:
    item = items[number]
    previous = entries.get(str(number), {})
    if args.command == "carry-forward":
      if (item["assessmentAction"] != "reuse" or item["contentStatus"] != "unchanged"
          or any(previous.get(field) != item[field]
                 for field in ("evidenceFingerprint", "contentFingerprint"))
          or previous.get("lastReport") not in (item.get("assessmentSource"), str(path))):
        raise ValueError(f"issue {number} is not eligible for reuse; collect with --reassess {number}")
      source = report_path(root, item["assessmentSource"])
      try:
        old_report = source.read_text(encoding="utf-8")
        span = issue_section(old_report, number)
        row = issue_row(old_report, number)
        if span is None or row is None:
          raise ValueError("saved report lacks the full issue section and summary row")
      except (OSError, ValueError) as error:
        raise ValueError(f"issue {number}: {error}; collect with --reassess {number}") from error
      section = old_report[span[0]:span[1]]
      # Keep the saved assessment intact; clearly label its provenance this run.
      heading_end = re.search(r"^### Issue #[^\n]*\n", outside_fences(section), re.M).end()
      body = section[heading_end:].lstrip("\n")
      body = re.sub(r"\AAssessment reused[^\n]*\n\n", "", body)
      section = (section[:heading_end] + "\nAssessment reused from unchanged evidence. "
                 + f"Last reviewed: {previous.get('lastReviewedAt', 'unknown')}.\n\n"
                 + body)
      if not re.search(r"^## Issues[ \t]*\n", outside_fences(report), re.M):
        report = report.rstrip() + "\n\n## Issues\n\n" + HEADER + "\n" + SEPARATOR + "\n\n"
      target_row = issue_row(report, number)
      cells = row[2]
      if target_row is not None:
        cells[1] = target_row[2][1]  # Done belongs to the reviewer, including an empty cell.
      cells[3] = " Unchanged; assessment reused "
      replacement_row = "|".join(cells)
      if target_row is None:
        start, end = issue_block(report)
        table = re.search(
          r"^\| Done \| Issue \| Activity \| Addressing PRs / Coverage \| Action Needed \|\n"
          r"\|[-|: ]+\|\n(?:\|[^\n]*\|\n)*", outside_fences(report)[start:end], re.M,
        )
        if table is None:
          raise ValueError("current Issues section lacks the standard summary table")
        insertion = start + table.end()
        report = report[:insertion] + replacement_row + "\n" + report[insertion:]
      else:
        report = report[:target_row[0]] + replacement_row + report[target_row[1]:]
      target = issue_section(report, number)
      if target is None:
        _, end = issue_block(report)
        report = report[:end].rstrip() + "\n\n" + section.rstrip() + "\n\n" + report[end:]
      else:
        report = report[:target[0]] + section + report[target[1]:]
      entries[str(number)] = {**previous, "lastReport": str(path), "lastCarriedAt": now}
    else:
      if item["assessmentAction"] != "review" or item["contentStatus"] != "fresh":
        raise ValueError("only freshly assessed issues may be checkpointed")
      if issue_section(report, number) is None or issue_row(report, number) is None:
        raise ValueError(f"write issue {number}'s full section and summary row before checkpointing")
      entries[str(number)] = {
        **previous, "fingerprint": item["evidenceFingerprint"],
        "evidenceFingerprint": item["evidenceFingerprint"],
        "contentFingerprint": item["contentFingerprint"],
        "lastReviewedAt": now, "lastReport": str(path),
        "recommendation": issue_row(report, number)[2][-2].strip(),
      }
  atomic_text(path, report)
  write_json(root / "state.json", state)
  return {"issues": requested, "action": args.command, "report": str(path)}


def done_values(args):
  """Return only reviewer-owned cells, including for issues needing fresh assessment."""
  root, _, report, state, items = context(args)
  numbers = args.issues if args.issues is not None else sorted(items)
  if len(numbers) != len(set(numbers)) or any(number not in items for number in numbers):
    raise ValueError("requested issues must occur exactly once in the current activity")
  values = {}
  unavailable = []
  for number in numbers:
    row = (issue_row(report, number)
           if re.search(r"^## Issues[ \t]*\n", outside_fences(report), re.M) else None)
    if row is None:
      source = state.get("issues", {}).get(str(number), {}).get("lastReport")
      if source:
        try:
          # This extraction exposes only the maintainer's cell, never old GitHub
          # prose. Changed trust invalidates assessment reuse, not reviewer marks.
          row = issue_row(report_path(root, source).read_text(encoding="utf-8"), number)
          if row is None:
            unavailable.append(number)
        except (OSError, ValueError):
          unavailable.append(number)
    values[str(number)] = row[2][1].strip() if row else ""
  return {"done": values, "unavailable": unavailable}


def audit(args):
  _, path, report, state, items = context(args)
  problems = []
  for number, item in items.items():
    entry = state.get("issues", {}).get(str(number), {})
    if (entry.get("lastReport") != str(path) or not entry.get("lastReviewedAt")
        or any(entry.get(field) != item[field]
               for field in ("evidenceFingerprint", "contentFingerprint"))):
      problems.append(f"issue {number}: current report assessment is missing or stale")
    try:
      if issue_section(report, number) is None or issue_row(report, number) is None:
        problems.append(f"issue {number}: full section or summary row is missing")
    except ValueError as error:
      problems.append(f"issue {number}: {error}")
  if problems:
    raise ValueError("; ".join(problems))
  return {"issues": sorted(items), "mechanicalChecksPassed": True, "report": str(path)}


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--root", type=Path, default=root_default())
  commands = parser.add_subparsers(dest="command", required=True)
  for name in ("carry-forward", "checkpoint", "audit", "done-values"):
    command = commands.add_parser(name)
    command.add_argument("--token", required=True)
    command.add_argument("--activity", type=Path, required=True)
    command.add_argument("--issues", type=int, nargs="+", required=name == "checkpoint")
    command.set_defaults(func={"audit": audit, "done-values": done_values}.get(name, update))
  args = parser.parse_args()
  args.root = args.root.expanduser()
  try:
    result = args.func(args)
  except (ValueError, KeyError, OSError) as error:
    parser.exit(2, f"{error}\n")
  print(json.dumps(result, indent=2))
  return 0
