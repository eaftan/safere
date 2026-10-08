# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

"""GitHub access with a fail-closed content trust boundary."""

from __future__ import annotations

import hashlib
import json
import subprocess
from dataclasses import dataclass
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
    result = json.loads(self.runner(command))
    responses = result if isinstance(result, list) else [result]
    if any(isinstance(response, dict) and response.get("errors") for response in responses):
      raise RuntimeError("GitHub returned GraphQL errors; refusing partial data")
    return result

  @staticmethod
  def _connection_nodes(pages: Any, item: str, connection: str) -> list[dict[str, Any]]:
    if not isinstance(pages, list) or not pages:
      raise RuntimeError("pagination returned no pages")
    nodes = []
    for index, page in enumerate(pages):
      value = page["data"]["repository"][item][connection]
      info = value["pageInfo"]
      if info["hasNextPage"] != (index < len(pages) - 1):
        raise RuntimeError("pagination is incomplete")
      if info["hasNextPage"] and not info["endCursor"]:
        raise RuntimeError("pagination has no continuation cursor")
      nodes.extend(value["nodes"])
    return nodes

  def trusted_users(self) -> frozenset[str]:
    pages = self._json(
      [
        "gh",
        "api",
        "--paginate",
        "--slurp",
        f"repos/{self.repository}/collaborators?affiliation=all&per_page=100",
      ]
    )
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

  def authenticated_login(self) -> str:
    result = self._json(
      [
        "gh",
        "api",
        "graphql",
        "-f",
        "query=query{viewer{login}}",
      ]
    )["data"]["viewer"]["login"]
    if not isinstance(result, str) or not result:
      raise RuntimeError("authenticated GitHub login is unavailable")
    return result

  def authored_pr_metadata(
    self, number: int, login: str, trusted: frozenset[str]
  ) -> dict[str, Any]:
    """Confirm identity and head repository before authored-PR content or worktree access."""
    owner, repo = self.repository.split("/", 1)
    query = (
      "query($owner:String!,$repo:String!,$n:Int!){"
      "repository(owner:$owner,name:$repo){pullRequest(number:$n){"
      "number url state updatedAt author{login} isDraft baseRefName headRefName "
      "baseRefOid headRefOid headRepository{nameWithOwner}}}}"
    )
    raw = self._json(
      [
        "gh",
        "api",
        "graphql",
        "-f",
        f"query={query}",
        "-F",
        f"owner={owner}",
        "-F",
        f"repo={repo}",
        "-F",
        f"n={number}",
      ]
    )["data"]["repository"]["pullRequest"]
    if raw is None or raw.get("number") != number:
      raise RuntimeError("authored PR metadata is unavailable")
    author = (raw.get("author") or {}).get("login")
    if author != login or author not in trusted:
      raise PermissionError("PR is not authored by the trusted authenticated user")
    if raw["state"] != "OPEN":
      raise RuntimeError("authored PR is no longer open")
    result = _normalize_pr_metadata(raw)
    result["head_repository"] = (raw.get("headRepository") or {}).get("nameWithOwner")
    return result

  def repository_metadata(self) -> dict[str, str]:
    owner, repo = self.repository.split("/", 1)
    result = self._json(
      [
        "gh",
        "api",
        "graphql",
        "-f",
        "query=query($owner:String!,$repo:String!){"
        "repository(owner:$owner,name:$repo){nameWithOwner owner{login} defaultBranchRef{name}}}",
        "-F",
        f"owner={owner}",
        "-F",
        f"repo={repo}",
      ]
    )["data"]["repository"]
    if result.get("nameWithOwner") != self.repository:
      raise RuntimeError("repository metadata identity mismatch")
    default_branch = (result.get("defaultBranchRef") or {}).get("name")
    if not isinstance(default_branch, str) or not default_branch:
      raise RuntimeError("repository has no default branch")
    return {"owner": result["owner"]["login"], "name": repo, "default_branch": default_branch}

  def item_metadata(self, kind: str, number: int) -> dict[str, Any]:
    owner, repo = self.repository.split("/", 1)
    item = "pullRequest" if kind == "pr" else "issue"
    extra = " isDraft baseRefName headRefName baseRefOid headRefOid" if kind == "pr" else ""
    result = self._json(
      [
        "gh",
        "api",
        "graphql",
        "-f",
        "query=query($owner:String!,$repo:String!,$n:Int!){"
        f"repository(owner:$owner,name:$repo){{{item}(number:$n){{"
        f"number url state updatedAt author{{login}}{extra}}}}}}}",
        "-F",
        f"owner={owner}",
        "-F",
        f"repo={repo}",
        "-F",
        f"n={number}",
      ]
    )["data"]["repository"][item]
    if result is None or result.get("number") != number:
      raise RuntimeError(f"{kind} {number} metadata is unavailable")
    metadata = {
      "number": number,
      "url": result["url"],
      "author": (result.get("author") or {}).get("login"),
      "state": result["state"],
      "updated_at": result["updatedAt"],
    }
    if kind == "pr":
      metadata.update(
        is_draft=result["isDraft"],
        base_ref=result["baseRefName"],
        head_ref=result["headRefName"],
        base_oid=result["baseRefOid"],
        head_oid=result["headRefOid"],
      )
    return metadata

  def pull_request_closing_issues(self, number: int) -> frozenset[int]:
    owner, repo = self.repository.split("/", 1)
    query = (
      "query($owner:String!,$repo:String!,$n:Int!,$endCursor:String){"
      "repository(owner:$owner,name:$repo){pullRequest(number:$n){"
      "closingIssuesReferences(first:100,after:$endCursor){"
      "nodes{number repository{nameWithOwner}}"
      "pageInfo{hasNextPage endCursor}}}}}"
    )
    pages = self._json(
      [
        "gh",
        "api",
        "graphql",
        "--paginate",
        "--slurp",
        "-f",
        f"query={query}",
        "-F",
        f"owner={owner}",
        "-F",
        f"repo={repo}",
        "-F",
        f"n={number}",
      ]
    )
    nodes = self._connection_nodes(pages, "pullRequest", "closingIssuesReferences")
    foreign = {
      node["repository"]["nameWithOwner"]
      for node in nodes
      if node["repository"]["nameWithOwner"] != self.repository
    }
    if foreign:
      repositories = ", ".join(sorted(foreign))
      raise RuntimeError(
        f"pull request has unsupported cross-repository closing issues: {repositories}"
      )
    return frozenset(node["number"] for node in nodes)

  def discover(self, trusted: frozenset[str], limit: int = 1000) -> dict[str, Any]:
    """Preserve the existing PR discovery interface."""
    return self.discover_items("pr", trusted, limit)

  def trusted_pr(self, number: int, trusted: frozenset[str]) -> dict[str, Any]:
    """Preserve PR snapshots while exposing metadata for linked issues."""
    result = self.trusted_item("pr", number, trusted)
    result["linkedPullRequests"] = [
      item for item in result["linkedItems"] if item.get("__typename") == "PullRequest"
    ]
    result.pop("fingerprint", None)
    result["fingerprint"] = fingerprint(result)
    return result

  def discover_items(self, kind: str, trusted: frozenset[str], limit: int = 1000) -> dict[str, Any]:
    if kind == "pr":
      fields = "number,isDraft,headRefName,headRefOid,baseRefName,updatedAt,url,author"
      command = [
        "gh",
        "pr",
        "list",
        "--repo",
        self.repository,
        "--state",
        "open",
        "--limit",
        str(limit),
        "--json",
        fields,
      ]
    else:
      fields = "number,state,updatedAt,url,author"
      command = [
        "gh",
        "issue",
        "list",
        "--repo",
        self.repository,
        "--state",
        "open",
        "--limit",
        str(limit),
        "--json",
        fields,
      ]
    entries = self._json(command)
    if len(entries) >= limit:
      raise RuntimeError("discovery limit reached; increase it before continuing")
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
        rejected.append(
          {
            "number": entry["number"],
            "url": entry["url"],
            "author": {"login": author},
            "updatedAt": entry.get("updatedAt"),
            "state": entry.get("state"),
            "isDraft": entry.get("isDraft", False),
          }
        )

    def key(item: dict[str, Any]) -> Any:
      return item["number"]

    return {
      "trusted": sorted(accepted, key=key),
      "untrusted": sorted(rejected, key=key),
      "drafts": sorted(drafts, key=key),
    }

  def all_issue_metadata(self) -> list[dict[str, Any]]:
    """Discover every issue state with complete, body-free pagination."""
    owner, repo = self.repository.split("/", 1)
    query = (
      "query($owner:String!,$repo:String!,$endCursor:String){"
      "repository(owner:$owner,name:$repo){issues(first:100,after:$endCursor){"
      "nodes{number url state createdAt updatedAt author{login}}"
      "pageInfo{hasNextPage endCursor}}}}"
    )
    pages = self._json(
      [
        "gh",
        "api",
        "graphql",
        "--paginate",
        "--slurp",
        "-f",
        f"query={query}",
        "-F",
        f"owner={owner}",
        "-F",
        f"repo={repo}",
      ]
    )
    if not isinstance(pages, list) or not pages:
      raise RuntimeError("issue pagination returned no pages")
    entries = []
    for index, page in enumerate(pages):
      connection = page["data"]["repository"]["issues"]
      if connection["pageInfo"]["hasNextPage"] != (index < len(pages) - 1):
        raise RuntimeError("issue pagination is incomplete")
      for node in connection["nodes"]:
        safe = {key: node[key] for key in ("number", "url", "state", "createdAt", "updatedAt")}
        safe["author"] = {"login": (node.get("author") or {}).get("login")}
        entries.append(safe)
    if len({node["number"] for node in entries}) != len(entries):
      raise RuntimeError("duplicate issue metadata during pagination")
    return sorted(entries, key=lambda node: node["number"])

  def issue_state_events(self, number: int) -> list[dict[str, Any]]:
    """Return body-free closure/reopening history, including transitions between sweeps."""
    owner, repo = self.repository.split("/", 1)
    query = (
      "query($owner:String!,$repo:String!,$n:Int!,$endCursor:String){"
      "repository(owner:$owner,name:$repo){issue(number:$n){"
      "timelineItems(first:100,after:$endCursor,itemTypes:[CLOSED_EVENT,REOPENED_EVENT]){"
      "nodes{__typename ... on ClosedEvent{createdAt actor{login}} "
      "... on ReopenedEvent{createdAt actor{login}}}pageInfo{hasNextPage endCursor}}}}}"
    )
    pages = self._json(
      [
        "gh",
        "api",
        "graphql",
        "--paginate",
        "--slurp",
        "-f",
        f"query={query}",
        "-F",
        f"owner={owner}",
        "-F",
        f"repo={repo}",
        "-F",
        f"n={number}",
      ]
    )
    return [
      {
        "type": node["__typename"],
        "createdAt": node["createdAt"],
        "actor": (node.get("actor") or {}).get("login"),
      }
      for node in self._connection_nodes(pages, "issue", "timelineItems")
    ]

  def trusted_item(self, kind: str, number: int, trusted: frozenset[str]) -> dict[str, Any]:
    """Fetch content only after independently confirming that its author is trusted."""
    result = self._trusted_core(kind, number, trusted)
    result["comments"] = self._trusted_nodes(
      self._metadata_nodes(kind, number, "comments"), trusted
    )
    result["linkedItems"] = self._linked_items(kind, number)
    if kind == "pr":
      result["reviews"] = self._trusted_nodes(
        self._metadata_nodes(kind, number, "reviews"), trusted
      )
      result["reviewComments"] = self._trusted_nodes(self._review_comments(number), trusted)
    result["fingerprint"] = fingerprint(result)
    return result

  def trusted_pr_summary(self, number: int, trusted: frozenset[str]) -> dict[str, Any]:
    """Fetch only trusted PR fields retained by issue-triage context."""
    return self._trusted_core("pr", number, trusted)

  def _trusted_core(self, kind: str, number: int, trusted: frozenset[str]) -> dict[str, Any]:
    owner, repo = self.repository.split("/", 1)
    metadata = self._json(
      [
        "gh",
        "api",
        "graphql",
        "-f",
        "query=query($owner:String!,$repo:String!,$n:Int!){"
        "repository(owner:$owner,name:$repo){"
        + ("pullRequest(number:$n)" if kind == "pr" else "issue(number:$n)")
        + "{id number updatedAt author{login}}}}",
        "-F",
        f"owner={owner}",
        "-F",
        f"repo={repo}",
        "-F",
        f"n={number}",
      ]
    )
    node = metadata["data"]["repository"]["pullRequest" if kind == "pr" else "issue"]
    author = (node.get("author") or {}).get("login")
    if author not in trusted:
      raise PermissionError(f"refusing to fetch content for untrusted author {author!r}")

    core = self._json(["gh", "api", f"repos/{self.repository}/issues/{number}"])
    if (core.get("user") or {}).get("login") != author:
      raise PermissionError("author changed during trusted fetch")
    result = {
      "number": number,
      "title": core["title"],
      "body": core.get("body") or "",
      "author": author,
      "updatedAt": node["updatedAt"],
      "labels": core.get("labels", []),
      "milestone": core.get("milestone"),
      "assignees": core.get("assignees", []),
    }
    return result

  def _metadata_nodes(self, kind: str, number: int, connection: str) -> list[dict[str, Any]]:
    owner, repo = self.repository.split("/", 1)
    item = "pullRequest" if kind == "pr" else "issue"
    fields = "id url createdAt updatedAt author{login}" + (
      " state" if connection == "reviews" else ""
    )
    query = (
      "query($owner:String!,$repo:String!,$n:Int!,$endCursor:String){"
      f"repository(owner:$owner,name:$repo){{{item}(number:$n){{"
      f"{connection}(first:100,after:$endCursor){{nodes{{{fields}}}"
      "pageInfo{hasNextPage endCursor}}}}}"
    )
    pages = self._json(
      [
        "gh",
        "api",
        "graphql",
        "--paginate",
        "--slurp",
        "-f",
        f"query={query}",
        "-F",
        f"owner={owner}",
        "-F",
        f"repo={repo}",
        "-F",
        f"n={number}",
      ]
    )
    node_type = "PullRequestReview" if connection == "reviews" else "IssueComment"
    return [node | {"_type": node_type} for node in self._connection_nodes(pages, item, connection)]

  def _review_comments(self, number: int) -> list[dict[str, Any]]:
    owner, repo = self.repository.split("/", 1)
    threads_query = (
      "query($owner:String!,$repo:String!,$n:Int!,$endCursor:String){"
      "repository(owner:$owner,name:$repo){pullRequest(number:$n){"
      "reviewThreads(first:100,after:$endCursor){nodes{id isResolved isOutdated}"
      "pageInfo{hasNextPage endCursor}}}}}"
    )
    thread_pages = self._json(
      [
        "gh",
        "api",
        "graphql",
        "--paginate",
        "--slurp",
        "-f",
        f"query={threads_query}",
        "-F",
        f"owner={owner}",
        "-F",
        f"repo={repo}",
        "-F",
        f"n={number}",
      ]
    )
    threads = self._connection_nodes(thread_pages, "pullRequest", "reviewThreads")
    comments_query = (
      "query($threadId:ID!,$endCursor:String){node(id:$threadId){"
      "... on PullRequestReviewThread{comments(first:100,after:$endCursor){nodes{"
      "id url createdAt updatedAt author{login} path line originalLine "
      "pullRequestReview{state}}"
      "pageInfo{hasNextPage endCursor}}}}}"
    )
    comments = []
    for thread in threads:
      thread_id = thread["id"]
      comment_pages = self._json(
        [
          "gh",
          "api",
          "graphql",
          "--paginate",
          "--slurp",
          "-f",
          f"query={comments_query}",
          "-F",
          f"threadId={thread_id}",
        ]
      )
      if not isinstance(comment_pages, list) or not comment_pages:
        raise RuntimeError("review comment pagination returned no pages")
      for index, page in enumerate(comment_pages):
        connection = page["data"]["node"]["comments"]
        info = connection["pageInfo"]
        if info["hasNextPage"] != (index < len(comment_pages) - 1):
          raise RuntimeError("review comment pagination is incomplete")
        if info["hasNextPage"] and not info["endCursor"]:
          raise RuntimeError("review comment pagination has no continuation cursor")
        comments.extend(
          node
          | {
            "_type": "PullRequestReviewComment",
            "threadId": thread_id,
            "isResolved": thread["isResolved"],
            "isOutdated": thread["isOutdated"],
            "state": (node.get("pullRequestReview") or {}).get("state"),
          }
          for node in connection["nodes"]
        )
    return comments

  def _linked_items(self, kind: str, number: int) -> list[dict[str, Any]]:
    owner, repo = self.repository.split("/", 1)
    item = "pullRequest" if kind == "pr" else "issue"
    query = (
      "query($owner:String!,$repo:String!,$n:Int!,$endCursor:String){"
      f"repository(owner:$owner,name:$repo){{{item}(number:$n){{"
      "timelineItems(first:100,after:$endCursor,itemTypes:[CROSS_REFERENCED_EVENT]){"
      "nodes{... on CrossReferencedEvent{source{__typename "
      "... on Issue{number url state updatedAt author{login} repository{nameWithOwner}}"
      "... on PullRequest{number url state updatedAt isDraft headRefOid author{login} "
      "repository{nameWithOwner}}}}}"
      "pageInfo{hasNextPage endCursor}}}}}"
    )
    pages = self._json(
      [
        "gh",
        "api",
        "graphql",
        "--paginate",
        "--slurp",
        "-f",
        f"query={query}",
        "-F",
        f"owner={owner}",
        "-F",
        f"repo={repo}",
        "-F",
        f"n={number}",
      ]
    )
    return [
      node["source"]
      for node in self._connection_nodes(pages, item, "timelineItems")
      if node.get("source")
    ]

  def _trusted_nodes(self, nodes: list[dict[str, Any]], trusted: frozenset[str]) -> dict[str, Any]:
    content, untrusted_activity = [], []
    for node in nodes:
      author = (node.get("author") or {}).get("login")
      safe = {
        key: node[key]
        for key in (
          "id",
          "url",
          "threadId",
          "isResolved",
          "isOutdated",
          "createdAt",
          "updatedAt",
          "state",
          "path",
          "line",
          "originalLine",
        )
        if key in node
      } | {"author": author}
      if author in trusted:
        fragment = node["_type"]
        fetched = self._json(
          [
            "gh",
            "api",
            "graphql",
            "-f",
            f"query=query($id:ID!){{node(id:$id){{... on {fragment}{{body author{{login}}}}}}}}",
            "-F",
            f"id={node['id']}",
          ]
        )["data"]["node"]
        if fetched is None or (fetched.get("author") or {}).get("login") != author:
          raise PermissionError("comment author changed during trusted fetch")
        content.append(safe | {"body": fetched.get("body", "")})
      else:
        untrusted_activity.append(safe)
    return {"trusted": content, "untrustedMetadata": untrusted_activity}


def _normalize_pr_metadata(value: dict[str, Any]) -> dict[str, Any]:
  return {
    "number": value["number"],
    "url": value["url"],
    "author": (value.get("author") or {}).get("login"),
    "state": value["state"],
    "is_draft": value["isDraft"],
    "base_ref": value["baseRefName"],
    "head_ref": value["headRefName"],
    "base_oid": value["baseRefOid"],
    "head_oid": value["headRefOid"],
    "updated_at": value["updatedAt"],
  }


def fingerprint(value: Any) -> str:
  encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
  return hashlib.sha256(encoded.encode()).hexdigest()
