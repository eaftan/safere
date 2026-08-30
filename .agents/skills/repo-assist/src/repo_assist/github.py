# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

"""GitHub access with a fail-closed content trust boundary."""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import subprocess
from typing import Any, Protocol


EXPLICIT_TRUSTED_USERS = frozenset({"wendigo"})


class Runner(Protocol):
  def __call__(self, command: list[str]) -> str: ...


def subprocess_runner(command: list[str]) -> str:
  return subprocess.run(command, check=True, capture_output=True, text=True).stdout


@dataclass(frozen=True)
class GitHub:
  repository: str
  runner: Runner = subprocess_runner

  def _json(self, command: list[str]) -> Any:
    return json.loads(self.runner(command))

  def trusted_users(self) -> frozenset[str]:
    pages = self._json([
        "gh", "api", "--paginate", "--slurp",
        f"repos/{self.repository}/collaborators?affiliation=all&per_page=100",
    ])
    if not isinstance(pages, list):
      raise RuntimeError("collaborator discovery returned an unexpected response")
    collaborators = [entry for page in pages for entry in page]
    trusted = {
        entry["login"]
        for entry in collaborators
        if entry.get("type") == "User"
        and any(entry.get("permissions", {}).get(level) for level in ("push", "maintain", "admin"))
    }
    if not trusted:
      raise RuntimeError("collaborator discovery produced no write-capable trusted users")
    trusted.update(EXPLICIT_TRUSTED_USERS)
    return frozenset(trusted)

  def discover(self, kind: str, trusted: frozenset[str], limit: int = 1000) -> dict[str, Any]:
    if kind == "pr":
      fields = "number,isDraft,headRefName,headRefOid,baseRefName,updatedAt,url,author"
      command = ["gh", "pr", "list", "--state", "open", "--limit", str(limit), "--json", fields]
    else:
      fields = "number,state,updatedAt,url,author"
      command = ["gh", "issue", "list", "--state", "open", "--limit", str(limit), "--json", fields]
    entries = self._json(command)
    accepted, rejected, drafts = [], [], []
    for entry in entries:
      author = (entry.get("author") or {}).get("login")
      if author in trusted:
        safe = {key: value for key, value in entry.items() if key not in ("title", "body")}
        if kind == "pr" and safe.get("isDraft"):
          drafts.append(safe)
        else:
          accepted.append(safe)
      else:
        # Keep untrusted user-controlled strings out of model-visible output.
        rejected.append({
            "number": entry["number"], "url": entry["url"], "author": {"login": author},
            "updatedAt": entry.get("updatedAt"), "state": entry.get("state"),
            "isDraft": entry.get("isDraft", False),
        })
    key = lambda item: item["number"]
    return {"trusted": sorted(accepted, key=key), "untrusted": sorted(rejected, key=key),
            "drafts": sorted(drafts, key=key)}

  def trusted_item(self, kind: str, number: int, trusted: frozenset[str]) -> dict[str, Any]:
    """Fetch content only after independently confirming that its author is trusted."""
    owner, repo = self.repository.split("/", 1)
    metadata = self._json([
        "gh", "api", "graphql", "-f", "query=query($owner:String!,$repo:String!,$n:Int!){"
        "repository(owner:$owner,name:$repo){"
        + ("pullRequest(number:$n)" if kind == "pr" else "issue(number:$n)")
        + "{id number updatedAt author{login}}}}", "-F", f"owner={owner}",
        "-F", f"repo={repo}", "-F", f"n={number}",
    ])
    node = metadata["data"]["repository"]["pullRequest" if kind == "pr" else "issue"]
    author = (node.get("author") or {}).get("login")
    if author not in trusted:
      raise PermissionError(f"refusing to fetch content for untrusted author {author!r}")

    core = self._json(["gh", "api", f"repos/{self.repository}/issues/{number}"])
    if (core.get("user") or {}).get("login") not in trusted:
      raise PermissionError("author changed during trusted fetch")
    result = {
        "number": number, "title": core["title"], "body": core.get("body") or "",
        "author": author, "updatedAt": node["updatedAt"], "labels": core.get("labels", []),
        "milestone": core.get("milestone"), "assignees": core.get("assignees", []),
        "comments": self._trusted_nodes(
            self._metadata_nodes(kind, number, "comments"), trusted),
        "linkedItems": self._linked_items(kind, number),
    }
    if kind == "pr":
      result["reviews"] = self._trusted_nodes(
          self._metadata_nodes(kind, number, "reviews"), trusted)
      result["reviewComments"] = self._trusted_nodes(
          self._review_comments(number), trusted)
    result["fingerprint"] = fingerprint(result)
    return result

  def _metadata_nodes(self, kind: str, number: int, connection: str) -> list[dict[str, Any]]:
    owner, repo = self.repository.split("/", 1)
    item = "pullRequest" if kind == "pr" else "issue"
    fields = "id updatedAt author{login}" + (" state" if connection == "reviews" else "")
    query = (
        "query($owner:String!,$repo:String!,$n:Int!,$endCursor:String){"
        f"repository(owner:$owner,name:$repo){{{item}(number:$n){{"
        f"{connection}(first:100,after:$endCursor){{nodes{{{fields}}}"
        "pageInfo{hasNextPage endCursor}}}}}"
    )
    pages = self._json([
        "gh", "api", "graphql", "--paginate", "--slurp", "-f", f"query={query}",
        "-F", f"owner={owner}", "-F", f"repo={repo}", "-F", f"n={number}",
    ])
    node_type = "PullRequestReview" if connection == "reviews" else "IssueComment"
    return [
        node | {"_type": node_type}
        for page in pages
        for node in page["data"]["repository"][item][connection]["nodes"]
    ]

  def _review_comments(self, number: int) -> list[dict[str, Any]]:
    owner, repo = self.repository.split("/", 1)
    query = (
        "query($owner:String!,$repo:String!,$n:Int!,$endCursor:String){"
        "repository(owner:$owner,name:$repo){pullRequest(number:$n){"
        "reviewThreads(first:100,after:$endCursor){nodes{comments(first:100){nodes{"
        "id updatedAt author{login} path line originalLine}}}pageInfo{hasNextPage endCursor}}}}}"
    )
    pages = self._json([
        "gh", "api", "graphql", "--paginate", "--slurp", "-f", f"query={query}",
        "-F", f"owner={owner}", "-F", f"repo={repo}", "-F", f"n={number}",
    ])
    return [
        node | {"_type": "PullRequestReviewComment"}
        for page in pages
        for thread in page["data"]["repository"]["pullRequest"]["reviewThreads"]["nodes"]
        for node in thread["comments"]["nodes"]
    ]

  def _linked_items(self, kind: str, number: int) -> list[dict[str, Any]]:
    owner, repo = self.repository.split("/", 1)
    item = "pullRequest" if kind == "pr" else "issue"
    query = (
        "query($owner:String!,$repo:String!,$n:Int!,$endCursor:String){"
        f"repository(owner:$owner,name:$repo){{{item}(number:$n){{"
        "timelineItems(first:100,after:$endCursor,itemTypes:[CROSS_REFERENCED_EVENT]){"
        "nodes{... on CrossReferencedEvent{source{"
        "... on Issue{number url state updatedAt author{login}}"
        "... on PullRequest{number url state updatedAt isDraft headRefOid author{login}}}}}"
        "pageInfo{hasNextPage endCursor}}}}}"
    )
    pages = self._json([
        "gh", "api", "graphql", "--paginate", "--slurp", "-f", f"query={query}",
        "-F", f"owner={owner}", "-F", f"repo={repo}", "-F", f"n={number}",
    ])
    return [
        node["source"]
        for page in pages
        for node in page["data"]["repository"][item]["timelineItems"]["nodes"]
        if node.get("source")
    ]

  def _trusted_nodes(
      self, nodes: list[dict[str, Any]], trusted: frozenset[str]
  ) -> dict[str, Any]:
    content, untrusted_activity = [], []
    for node in nodes:
      author = (node.get("author") or {}).get("login")
      safe = {
          key: node[key]
          for key in ("id", "updatedAt", "state", "path", "line", "originalLine")
          if key in node
      } | {"author": author}
      if author in trusted:
        fragment = node["_type"]
        body = self._json([
            "gh", "api", "graphql", "-f",
            f"query=query($id:ID!){{node(id:$id){{... on {fragment}{{body}}}}}}",
            "-F", f"id={node['id']}",
        ])["data"]["node"].get("body", "")
        content.append(safe | {"body": body})
      else:
        untrusted_activity.append(safe)
    return {"trusted": content, "untrustedMetadata": untrusted_activity}


def fingerprint(value: Any) -> str:
  encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
  return hashlib.sha256(encoded.encode()).hexdigest()
