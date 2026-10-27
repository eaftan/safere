# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

"""Deterministic issue activity collection; only sanitized output reaches the reviewer."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

from repo_assist.github import GitHub, fingerprint


def timestamp(value: str) -> datetime:
  result = datetime.fromisoformat(value.replace("Z", "+00:00"))
  if result.tzinfo is None:
    raise ValueError("activity timestamps must include a timezone")
  return result


def collect_activity(
  github: GitHub, since: str | None, cutoff: str, previous: dict[str, Any],
  *, recent_days: int | None = None, assessments: dict[str, Any] | None = None,
  reassess: frozenset[int] = frozenset(),
) -> tuple[dict[str, Any], dict[str, Any]]:
  """Return a report input and pending checkpoint without modifying persistent state."""
  assessments = assessments or {}
  lower = timestamp(since) if since else None
  # First runs cover recent activity, rather than expanding into an open backlog audit.
  activity_lower = lower or timestamp(cutoff) - timedelta(days=7)
  if lower is not None and lower > timestamp(cutoff):
    raise ValueError("activity cutoff precedes the last successful run")
  if recent_days is not None and recent_days <= 0:
    raise ValueError("recent issue window must be positive")
  recent_lower = (
    timestamp(cutoff) - timedelta(days=recent_days) if recent_days is not None else None
  )
  if any(number <= 0 for number in reassess):
    raise ValueError("reassessment issue numbers must be positive")
  trusted = github.trusted_users()
  issues = github.all_issue_metadata()
  # Include drafts and owner PRs for issue coverage even if excluded from code review.
  prs = github.discover_items("pr", trusted)
  open_prs = prs["trusted"] + prs["drafts"] + prs["untrusted"]
  closing: dict[tuple[str, int], list[dict[str, Any]]] = {}
  for pr in open_prs:
    for identity in github.pull_request_closing_issues(pr["number"]):
      closing.setdefault(identity, []).append(pr)
  output: dict[str, Any] = {
    "since": since,
    "cutoff": cutoff,
    "baseline": since is None,
    "trustedAuthors": sorted(trusted),
    "issues": [],
  }
  if recent_lower is not None:
    output["scope"] = {
      "recentDays": recent_days,
      "since": recent_lower.astimezone(UTC).isoformat().replace("+00:00", "Z"),
    }
  else:
    output["scope"] = {
      "mode": "activity",
      "since": activity_lower.astimezone(UTC).isoformat().replace("+00:00", "Z"),
    }
  # Inactive issues retain their checkpoints without entering the report or prose pulls.
  checkpoint: dict[str, Any] = dict(previous)
  repositories = {github.repository: github}
  pr_cache: dict[tuple[str, int], dict[str, Any]] = {}
  for meta in issues:
    number = meta["number"]
    comments = github._metadata_nodes("issue", number, "comments")
    comment_state = {
      node["id"]: {key: node[key] for key in ("createdAt", "updatedAt", "author")}
      for node in comments
    }
    if len(comment_state) != len(comments):
      raise RuntimeError("duplicate comment metadata during pagination")
    if recent_lower is not None and not (
      (meta["state"] == "OPEN" and timestamp(meta["createdAt"]) >= recent_lower)
      or any(timestamp(node["createdAt"]) >= recent_lower for node in comments)
    ):
      continue
    old = previous.get(str(number))
    old_comments = (old or {}).get("comments", {})
    # Check comment metadata even when the root timestamp is unchanged. Detect deletions
    # against the previous checkpoint, but do not fetch old issue links or text merely
    # because an issue remains open. Active closing PRs can supply new coverage context.
    active = (
      number in reassess
      or timestamp(meta["updatedAt"]) >= activity_lower
      or timestamp(meta["createdAt"]) >= activity_lower
      or any(timestamp(node["updatedAt"]) >= activity_lower for node in comments)
      or bool(old and comment_state != old_comments)
      or any(
        timestamp(pr["updatedAt"]) >= activity_lower
        for pr in closing.get((github.repository, number), [])
      )
    )
    if recent_lower is None and not active:
      continue
    events = github.issue_state_events(number)
    links = github._linked_items("issue", number)
    # Closing-only links can disappear from open-PR discovery after merging or closing.
    # Prior identities are discovery seeds; re-read current metadata and author trust below.
    links.extend(
      {
        "__typename": "PullRequest",
        "number": pr["number"],
        "repository": {"nameWithOwner": pr.get("repository", github.repository)},
      }
      for pr in (old or {}).get("pullRequests", [])
    )
    for pr in closing.get((github.repository, number), []):
      links.append(
        {
          "__typename": "PullRequest",
          **pr,
          "repository": {"nameWithOwner": github.repository},
        }
      )
    # Resolve linked PRs with metadata-only requests; never trust embedded text or authors.
    contexts = []
    seen = set()
    for link in links:
      if link.get("__typename") != "PullRequest":
        continue
      repository = (link.get("repository") or {}).get("nameWithOwner")
      if repository not in repositories:
        repositories[repository] = github.for_repository(repository)
      linked_github = repositories[repository]
      pr_number = link["number"]
      identity = (repository, pr_number)
      if identity in seen:
        continue
      seen.add(identity)
      if identity not in pr_cache:
        pr_meta = linked_github.item_metadata("pr", pr_number)
        pr_closing = linked_github.pull_request_closing_issues(pr_number)
        pr_cache[identity] = {
          "metadata": pr_meta,
          "closingIssues": sorted(pr_closing),
        }
      pr = pr_cache[identity]
      safe_pr = {
        key: pr["metadata"][key]
        for key in (
          "number",
          "url",
          "author",
          "state",
          "updated_at",
          "is_draft",
          "head_oid",
        )
      }
      contexts.append(
        {
          **safe_pr,
          "repository": repository,
          "relationship": (
            "fixes" if (github.repository, number) in pr["closingIssues"] else "references"
          ),
        }
      )
    contexts.sort(key=lambda item: (item["repository"], item["number"]))
    current = {
      "metadata": meta,
      "comments": comment_state,
      "pullRequests": contexts,
      "stateEvents": events,
    }
    evidence = fingerprint({
      "repository": github.repository, **current, "trustedAuthors": sorted(trusted),
    })
    current["evidenceFingerprint"] = evidence
    checkpoint[str(number)] = current
    old_comments = (old or {}).get("comments", {})
    added = (
      sorted(set(comment_state) - set(old_comments))
      if old
      else sorted(
        key
        for key, node in comment_state.items()
        if lower and timestamp(node["createdAt"]) >= lower
      )
    )
    edited = (
      sorted(
        key
        for key in comment_state.keys() & old_comments.keys()
        if comment_state[key] != old_comments[key]
      )
      if old
      else sorted(
        key
        for key, node in comment_state.items()
        if lower is not None
        and timestamp(node["createdAt"]) < lower <= timestamp(node["updatedAt"])
      )
    )
    deleted = sorted(set(old_comments) - set(comment_state)) if old else []
    new = lower is not None and timestamp(meta["createdAt"]) >= lower
    recent = lower is not None and (
      timestamp(meta["updatedAt"]) >= lower
      or any(timestamp(node["updatedAt"]) >= lower for node in comments)
      or any(timestamp(event["createdAt"]) >= lower for event in events)
    )
    changed = old is not None and any(
      current[key] != old.get(key)
      for key in ("metadata", "comments", "pullRequests", "stateEvents")
    )
    if old and old.get("evidenceFingerprint") and old["evidenceFingerprint"] != evidence:
      changed = True
    item = {
      "metadata": meta,
      "trustedIssueAuthor": meta["author"]["login"] in trusted,
      "new": new,
      "changed": changed,
      "recent": recent,
      "stateEvents": [
        event for event in events if lower and timestamp(event["createdAt"]) >= lower
      ],
      "previousMetadata": old.get("metadata") if old else None,
      "commentChanges": {"added": added, "edited": edited, "deleted": deleted},
      "commentChangeBasis": "checkpoint" if old else "timestamps" if lower else "baseline",
      "pullRequests": [],
      "evidenceFingerprint": evidence,
      "assessmentAction": "review",
      "contentStatus": "fresh",
    }
    assessment = assessments.get(str(number), {})
    can_reuse = bool(
      number not in reassess
      and old and old.get("evidenceFingerprint") == evidence
      and old.get("contentFingerprint")
      and assessment.get("evidenceFingerprint") == evidence
      and assessment.get("contentFingerprint") == old["contentFingerprint"]
      and assessment.get("lastReport")
    )
    if can_reuse:
      # No cached GitHub prose enters the artifact. The report helper uses this
      # binding only after current metadata, coverage and trust have been checked.
      item.update(
        assessmentAction="reuse", contentStatus="unchanged",
        assessmentSource=assessment["lastReport"],
      )
      item["pullRequests"] = [
        context | {"trustedAuthor": context["author"] in trusted} for context in contexts
      ]
      current["contentFingerprint"] = old["contentFingerprint"]
    if not can_reuse and item["trustedIssueAuthor"]:
      item["snapshot"] = github._trusted_core("issue", number, trusted)
    # Comments have their own author gate, independently of the issue author's gate.
    if not can_reuse:
      item["comments"] = github._trusted_nodes(comments, trusted)
    for context in ([] if can_reuse else contexts):
      value = dict(context)
      value["trustedAuthor"] = value["author"] in trusted
      if value["trustedAuthor"]:
        linked_github = repositories[value["repository"]]
        cache = pr_cache[(value["repository"], value["number"])]
        if "summary" not in cache:
          cache["summary"] = linked_github.trusted_pr_summary(value["number"], trusted)
        value["snapshot"] = cache["summary"]
      item["pullRequests"].append(value)
    # Recheck identities even on the no-text reuse path before exposing old analysis.
    for context in contexts:
      linked_github = repositories[context["repository"]]
      cache = pr_cache[(context["repository"], context["number"])]
      if linked_github.item_metadata("pr", context["number"]) != cache["metadata"]:
        raise RuntimeError("linked PR changed during activity collection; retry")
    after = github.item_metadata("issue", number)
    if (
      after["author"] != meta["author"]["login"]
      or after["updated_at"] != meta["updatedAt"]
      or after["state"] != meta["state"]
    ):
      raise RuntimeError(f"issue {number} changed during activity collection; retry")
    # Persist hashes, not issue prose, for later edit/deletion comparison.
    if not can_reuse:
      current["contentFingerprint"] = fingerprint(
        {key: item.get(key) for key in ("snapshot", "comments", "pullRequests")}
      )
    item["contentFingerprint"] = current["contentFingerprint"]
    item["changed"] = changed or bool(
      old and old.get("contentFingerprint") != current["contentFingerprint"]
    )
    output["issues"].append(item)
  output["fingerprint"] = fingerprint(output)
  return output, checkpoint
