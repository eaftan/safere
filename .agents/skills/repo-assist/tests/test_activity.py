# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

import copy
import json

import pytest
from repo_assist.activity import collect_activity
from repo_assist.github import GitHub
from test_github import FakeRunner

BEFORE = "2026-10-01T00:00:00Z"
SINCE = "2026-10-02T00:00:00Z"
AFTER = "2026-10-03T00:00:00Z"


class Source:
  repository = "o/r"

  def __init__(self, author="writer", state="OPEN", updated=BEFORE):
    self.issue = {
      "number": 1,
      "url": "https://github.com/o/r/issues/1",
      "state": state,
      "author": {"login": author},
      "createdAt": BEFORE,
      "updatedAt": updated,
    }
    self.comments = [
      {
        "id": "trusted-comment",
        "author": {"login": "writer"},
        "createdAt": BEFORE,
        "updatedAt": BEFORE,
      },
      {
        "id": "untrusted-comment",
        "author": {"login": "stranger"},
        "createdAt": BEFORE,
        "updatedAt": BEFORE,
      },
    ]
    self.events = []
    self.links = []
    self.reads = []
    self.summary_reads = []
    self.sources = {}
    self.draft = False

  def trusted_users(self):
    return frozenset({"writer"})

  def all_issue_metadata(self):
    return [copy.deepcopy(self.issue)]

  def discover_items(self, kind, trusted):
    return {
      "trusted": [],
      "untrusted": [],
      "drafts": [{"number": 2, "isDraft": True}] if self.draft else [],
    }

  def pull_request_closing_issues(self, number):
    return frozenset({(self.repository, 1)})

  def for_repository(self, repository):
    return self.sources[repository]

  def _metadata_nodes(self, *args):
    return [node | {"_type": "IssueComment"} for node in copy.deepcopy(self.comments)]

  def issue_state_events(self, number):
    return copy.deepcopy(self.events)

  def _linked_items(self, *args):
    return copy.deepcopy(self.links)

  def _trusted_core(self, kind, number, trusted):
    self.reads.append((kind, number))
    assert self.issue["author"]["login"] in trusted
    return {"title": "trusted title", "body": "trusted body"}

  def _trusted_nodes(self, comments, trusted):
    # Use the real deterministic comment gate, with no fake response for the stranger.
    responses = [
      {"data": {"node": {"body": "safe comment", "author": node["author"]}}}
      for node in comments if node["author"]["login"] in trusted
    ]
    runner = FakeRunner(responses)
    result = GitHub("o/r", runner)._trusted_nodes(comments, trusted)
    assert len(runner.commands) == len(responses)
    return result

  def item_metadata(self, kind, number):
    if kind == "pr":
      return {
        "number": number,
        "url": f"https://github.com/{self.repository}/pull/{number}",
        "author": "writer",
        "state": "OPEN",
        "is_draft": True,
        "updated_at": AFTER,
        "head_oid": "abc",
      }
    return {
      "number": number,
      "author": self.issue["author"]["login"],
      "state": self.issue["state"],
      "updated_at": self.issue["updatedAt"],
    }

  def trusted_pr_summary(self, number, trusted):
    self.summary_reads.append(number)
    return {"title": "draft fix", "body": "addresses request"}


def run(source, previous=None, since: str | None = SINCE, **scope):
  return collect_activity(source, since, AFTER, previous or {}, **scope)


def test_baseline_reports_open_backlog_without_calling_it_new():
  output, checkpoint = run(Source(), since=None)
  assert output["baseline"]
  assert not output["issues"][0]["new"]
  assert checkpoint["1"]["comments"]["trusted-comment"]["updatedAt"] == BEFORE


def test_closed_issue_new_since_cutoff_is_included():
  source = Source(state="CLOSED", updated=AFTER)
  source.issue["createdAt"] = SINCE
  output, _ = run(source)
  assert output["issues"][0]["new"]
  assert output["issues"][0]["metadata"]["state"] == "CLOSED"


def test_untrusted_issue_root_is_never_read_but_comments_have_independent_gate():
  source = Source(author="stranger", updated=AFTER)
  output, _ = run(source)
  item = output["issues"][0]
  assert source.reads == []
  assert "snapshot" not in item
  assert item["comments"]["trusted"][0]["body"] == "safe comment"
  assert "body" not in item["comments"]["untrustedMetadata"][0]


def test_comment_edits_and_deletions_on_closed_issue_are_detected_even_without_root_update():
  source = Source()
  _, previous = run(source)
  source.issue["state"] = "CLOSED"
  source.comments[0]["updatedAt"] = AFTER
  source.comments.pop()
  output, _ = run(source, previous)
  changes = output["issues"][0]["commentChanges"]
  assert changes == {
    "added": [],
    "edited": ["trusted-comment"],
    "deleted": ["untrusted-comment"],
  }


def test_unchanged_open_issue_does_not_always_appear_changed():
  source = Source()
  _, previous = run(source)
  output, _ = run(source, previous)
  assert not output["issues"][0]["changed"]


def test_close_and_reopen_between_runs_are_both_reported():
  source = Source()
  _, previous = run(source)
  source.events = [
    {"type": "ClosedEvent", "createdAt": SINCE, "actor": "writer"},
    {"type": "ReopenedEvent", "createdAt": AFTER, "actor": "writer"},
  ]
  output, _ = run(source, previous)
  assert [event["type"] for event in output["issues"][0]["stateEvents"]] == [
    "ClosedEvent",
    "ReopenedEvent",
  ]


def test_draft_closing_pr_is_coverage_even_without_issue_cross_reference():
  source = Source()
  source.draft = True
  output, _ = run(source)
  context = output["issues"][0]["pullRequests"][0]
  assert context["relationship"] == "fixes"
  assert context["is_draft"]


def test_metadata_pagination_is_complete_and_sanitized():
  def page(number, more):
    return {
      "data": {
        "repository": {
          "issues": {
            "nodes": [
              {
                "number": number,
                "url": "u",
                "state": "CLOSED",
                "createdAt": BEFORE,
                "updatedAt": AFTER,
                "author": {"login": "stranger"},
                "body": "CANARY",
                "title": "CANARY",
              }
            ],
            "pageInfo": {"hasNextPage": more, "endCursor": "c"},
          }
        }
      }
    }

  runner = FakeRunner([[page(1, True), page(2, False)]])
  items = GitHub("o/r", runner).all_issue_metadata()
  assert [item["number"] for item in items] == [1, 2]
  assert "CANARY" not in json.dumps(items)
  assert "body" not in " ".join(runner.commands[0])
  with pytest.raises(RuntimeError, match="incomplete"):
    GitHub("o/r", FakeRunner([[page(1, True)]])).all_issue_metadata()


def test_author_changed_during_comment_fetch_fails_closed():
  node = {"id": "c", "_type": "IssueComment", "author": {"login": "writer"}}
  runner = FakeRunner([{"data": {"node": {"body": "CANARY", "author": {"login": "stranger"}}}}])
  with pytest.raises(PermissionError):
    GitHub("o/r", runner)._trusted_nodes([node], frozenset({"writer"}))


def test_untrusted_linked_pr_has_no_text_or_branch_names_in_output():
  source = Source()
  source.draft = True
  original_metadata = source.item_metadata

  def metadata(kind, number):
    result = original_metadata(kind, number)
    if kind == "pr":
      result.update(author="stranger", head_ref="UNTRUSTED-CANARY")
    return result

  def forbidden_summary(number, trusted):
    raise AssertionError("untrusted PR text must never be requested")

  source.item_metadata = metadata
  source.trusted_pr_summary = forbidden_summary
  output, _ = run(source)
  context = output["issues"][0]["pullRequests"][0]
  assert not context["trustedAuthor"]
  assert "snapshot" not in context
  assert "UNTRUSTED-CANARY" not in json.dumps(output)


def test_partial_graphql_data_is_rejected_before_body_fetch():
  runner = FakeRunner([{"data": {"repository": {}}, "errors": [{"message": "CANARY"}]}])
  with pytest.raises(RuntimeError, match="partial data"):
    GitHub("o/r", runner).trusted_item("issue", 1, frozenset({"writer"}))
  assert len(runner.commands) == 1


def test_migration_classifies_old_comments_edited_since_cutoff_without_prior_checkpoint():
  source = Source(state="CLOSED")
  source.comments[0]["updatedAt"] = AFTER
  output, _ = run(source)
  item = output["issues"][0]
  assert item["commentChanges"] == {"added": [], "edited": ["trusted-comment"], "deleted": []}
  assert item["commentChangeBasis"] == "timestamps"
  assert item["previousMetadata"] is None


@pytest.mark.parametrize("terminal_state", ["MERGED", "CLOSED"])
def test_closing_only_pr_history_is_refreshed_after_leaving_open_set(terminal_state):
  source = Source()
  source.draft = True
  _, previous = run(source)
  source.draft = False
  original_metadata = source.item_metadata

  def metadata(kind, number):
    result = original_metadata(kind, number)
    if kind == "pr":
      result.update(state=terminal_state, is_draft=False)
    return result

  source.item_metadata = metadata
  output, checkpoint = run(source, previous)
  context = output["issues"][0]["pullRequests"][0]
  assert context["number"] == 2
  assert context["state"] == terminal_state
  assert not context["is_draft"]
  assert context["relationship"] == "fixes"
  assert checkpoint["1"]["pullRequests"][0]["state"] == terminal_state
  # The history must survive subsequent checkpoints as well.
  again, _ = run(source, checkpoint)
  assert again["issues"][0]["pullRequests"][0]["state"] == terminal_state


def external_context(source, repository="other/repo", number=2):
  external = Source()
  external.repository = repository
  source.sources[repository] = external
  source.links.append(
    {"__typename": "PullRequest", "number": number, "repository": {"nameWithOwner": repository}}
  )
  return external


def test_trusted_external_pr_is_issue_context_without_expanding_trust():
  source = Source()
  external = external_context(source)
  external.trusted_users = lambda: pytest.fail("external collaborators must not expand trust")
  output, checkpoint = run(source)
  context = output["issues"][0]["pullRequests"][0]
  assert context["repository"] == "other/repo"
  assert context["url"] == "https://github.com/other/repo/pull/2"
  assert context["trustedAuthor"]
  assert context["snapshot"]["body"] == "addresses request"
  assert context["relationship"] == "references"
  assert external.summary_reads == [2]
  assert checkpoint["1"]["pullRequests"][0]["repository"] == "other/repo"


def test_untrusted_external_pr_is_metadata_only_and_does_not_abort_collection():
  source = Source()
  external = external_context(source)
  original_metadata = external.item_metadata

  def metadata(kind, number):
    return original_metadata(kind, number) | {
      "author": "stranger", "title": "UNTRUSTED-CANARY", "head_ref": "UNTRUSTED-CANARY"
    }

  external.item_metadata = metadata
  external.trusted_pr_summary = lambda *args: pytest.fail("untrusted text must not be fetched")
  output, _ = run(source)
  context = output["issues"][0]["pullRequests"][0]
  assert context["repository"] == "other/repo"
  assert not context["trustedAuthor"]
  assert "snapshot" not in context
  assert "UNTRUSTED-CANARY" not in json.dumps(output)


def test_linked_pr_numbers_are_scoped_by_repository_for_cache_and_deduplication():
  source = Source()
  source.draft = True
  external = external_context(source)
  external.trusted_pr_summary = lambda number, trusted: {"title": "external", "body": "external"}
  source.links.extend(copy.deepcopy(source.links))
  output, _ = run(source)
  contexts = output["issues"][0]["pullRequests"]
  assert len(contexts) == 2
  contexts = {context["repository"]: context for context in contexts}
  assert contexts["o/r"]["snapshot"]["title"] == "draft fix"
  assert contexts["o/r"]["relationship"] == "fixes"
  assert contexts["other/repo"]["snapshot"]["title"] == "external"
  assert contexts["other/repo"]["relationship"] == "references"


@pytest.mark.parametrize("cross_referenced", [False, True])
def test_foreign_same_number_closing_issue_does_not_imply_local_coverage(cross_referenced):
  source = Source()
  source.draft = True
  source.pull_request_closing_issues = lambda number: frozenset({("other/repo", 1)})
  if cross_referenced:
    source.links = [
      {"__typename": "PullRequest", "number": 2, "repository": {"nameWithOwner": "o/r"}}
    ]
  output, _ = run(source)
  contexts = output["issues"][0]["pullRequests"]
  assert len(contexts) == int(cross_referenced)
  if cross_referenced:
    assert contexts[0]["relationship"] == "references"


@pytest.mark.parametrize("terminal_state", ["CLOSED", "MERGED"])
def test_external_closing_pr_identity_and_history_survive_multiple_checkpoints(terminal_state):
  source = Source()
  external = external_context(source)
  external.pull_request_closing_issues = lambda number: frozenset({("o/r", 1)})
  _, previous = run(source)
  source.links.clear()
  original_metadata = external.item_metadata
  external.item_metadata = lambda kind, number: original_metadata(kind, number) | {
    "state": terminal_state, "is_draft": False
  }
  output, checkpoint = run(source, previous)
  context = output["issues"][0]["pullRequests"][0]
  assert context["repository"] == "other/repo"
  assert context["state"] == terminal_state
  assert context["relationship"] == "fixes"
  again, _ = run(source, checkpoint)
  assert again["issues"][0]["pullRequests"][0]["repository"] == "other/repo"
  assert again["issues"][0]["pullRequests"][0]["state"] == terminal_state


def test_legacy_linked_pr_checkpoint_defaults_to_local_repository():
  source = Source()
  source.draft = True
  _, previous = run(source)
  previous["1"]["pullRequests"][0].pop("repository", None)
  source.draft = False
  output, _ = run(source, previous)
  assert output["issues"][0]["pullRequests"][0]["repository"] == "o/r"


def test_external_author_change_during_collection_still_fails_closed():
  source = Source()
  external = external_context(source)
  original_metadata = external.item_metadata
  calls = 0

  def metadata(kind, number):
    nonlocal calls
    calls += 1
    return original_metadata(kind, number) | {"author": "writer" if calls == 1 else "stranger"}

  external.item_metadata = metadata
  with pytest.raises(RuntimeError, match="linked PR changed"):
    run(source)


@pytest.mark.parametrize("state", ["OPEN", "CLOSED"])
def test_recent_scope_includes_new_open_issues_without_comments_but_excludes_closed_ones(state):
  source = Source(state=state)
  source.comments.clear()
  output, _ = run(source, recent_days=14)
  assert len(output["issues"]) == int(state == "OPEN")
  assert output["scope"] == {"recentDays": 14, "since": "2026-09-19T00:00:00Z"}


@pytest.mark.parametrize("state", ["OPEN", "CLOSED"])
def test_recent_scope_includes_old_issues_with_recent_posts_despite_unchanged_root(state):
  source = Source(state=state)
  source.issue.update(createdAt="2026-01-01T00:00:00Z", updatedAt="2026-01-01T00:00:00Z")
  output, _ = run(source, recent_days=14)
  assert len(output["issues"]) == 1
  # This comment predates the last successful run, but qualifies for the requested window.
  assert source.comments[0]["createdAt"] < SINCE


def test_recent_scope_uses_post_time_rather_than_an_edit_to_an_old_comment():
  source = Source()
  source.issue["createdAt"] = "2026-01-01T00:00:00Z"
  source.comments = [{
    "id": "old-comment", "author": {"login": "writer"},
    "createdAt": "2026-01-01T00:00:00Z", "updatedAt": AFTER,
  }]
  source._linked_items = lambda *args: pytest.fail("excluded issue links must not be fetched")
  source.issue_state_events = lambda *args: pytest.fail("excluded issue events must not be fetched")
  source._trusted_nodes = lambda *args: pytest.fail("excluded comment text must not be fetched")
  output, _ = run(source, recent_days=14)
  assert not output["issues"]
  assert not source.reads


def test_recent_scope_preserves_checkpoints_for_issues_outside_the_requested_window():
  source = Source()
  _, previous = run(source)
  source.issue["createdAt"] = "2026-01-01T00:00:00Z"
  source.comments.clear()
  output, checkpoint = run(source, previous, recent_days=14)
  assert not output["issues"]
  assert checkpoint == previous


@pytest.mark.parametrize("posted_at, included", [
  ("2026-09-18T23:59:59Z", False), ("2026-09-19T00:00:00Z", True),
])
def test_recent_scope_includes_comments_at_the_window_boundary(posted_at, included):
  source = Source()
  source.issue["createdAt"] = "2026-01-01T00:00:00Z"
  source.comments = [{
    "id": "comment", "author": {"login": "writer"},
    "createdAt": posted_at, "updatedAt": posted_at,
  }]
  output, _ = run(source, recent_days=14)
  assert bool(output["issues"]) == included


@pytest.mark.parametrize("days", [0, -1])
def test_recent_scope_rejects_nonpositive_windows(days):
  with pytest.raises(ValueError, match="positive"):
    run(Source(), recent_days=days)
