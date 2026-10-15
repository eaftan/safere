# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

import json
import subprocess

import pytest
from repo_assist.github import GitHub, fingerprint


class FakeRunner:
  def __init__(self, responses):
    self.responses = list(responses)
    self.commands = []

  def __call__(self, command):
    self.commands.append(command)
    response = self.responses.pop(0)
    if isinstance(response, Exception):
      raise response
    return json.dumps(response)


def collaborators(*entries):
  return [list(entries)]


def test_trust_comes_from_write_collaborators_plus_explicit_user():
  runner = FakeRunner(
    [
      [
        [{"login": "owner", "type": "User", "permissions": {"admin": True}}],
        [
          {"login": "writer", "type": "User", "permissions": {"push": True}},
          {"login": "reader", "type": "User", "permissions": {"pull": True}},
          {"login": "robot", "type": "Bot", "permissions": {"push": True}},
        ],
      ]
    ]
  )
  assert GitHub("o/r", runner).trusted_users() == frozenset({"owner", "writer", "wendigo"})
  assert "--paginate" in runner.commands[0]


def test_collaborator_discovery_fails_closed():
  error = subprocess.CalledProcessError(1, ["gh"])
  with pytest.raises(subprocess.CalledProcessError):
    GitHub("o/r", FakeRunner([error])).trusted_users()


def test_empty_collaborator_response_does_not_fall_back_to_explicit_users():
  with pytest.raises(RuntimeError):
    GitHub("o/r", FakeRunner([[[]]])).trusted_users()


def test_discovery_exposes_no_untrusted_body_or_title():
  runner = FakeRunner(
    [
      [
        {
          "number": 1,
          "isDraft": False,
          "url": "u",
          "updatedAt": "t",
          "author": {"login": "stranger"},
          "title": "CANARY",
          "body": "SECRET",
        }
      ]
    ]
  )
  result = GitHub("o/r", runner).discover(frozenset({"writer"}))
  rendered = json.dumps(result)
  assert "CANARY" not in rendered
  assert "SECRET" not in rendered
  assert result["untrusted"][0]["number"] == 1


def test_untrusted_root_item_body_is_never_requested():
  metadata = {
    "data": {
      "repository": {
        "pullRequest": {
          "id": "I",
          "number": 9,
          "updatedAt": "t",
          "author": {"login": "stranger"},
        }
      }
    }
  }
  runner = FakeRunner([metadata])
  with pytest.raises(PermissionError):
    GitHub("o/r", runner).trusted_pr(9, frozenset({"writer"}))
  assert len(runner.commands) == 1


def test_only_trusted_comment_bodies_are_requested_and_pages_are_combined():
  core_metadata = {
    "data": {
      "repository": {
        "pullRequest": {
          "id": "I",
          "number": 7,
          "updatedAt": "t",
          "author": {"login": "writer"},
        }
      }
    }
  }
  comment_pages = [
    {
      "data": {
        "repository": {
          "pullRequest": {
            "comments": {
              "pageInfo": {"hasNextPage": True, "endCursor": "c"},
              "nodes": [
                {"id": "trusted-id", "updatedAt": "a", "author": {"login": "writer"}},
              ],
            }
          }
        }
      }
    },
    {
      "data": {
        "repository": {
          "pullRequest": {
            "comments": {
              "pageInfo": {"hasNextPage": False, "endCursor": None},
              "nodes": [
                {
                  "id": "untrusted-id",
                  "updatedAt": "b",
                  "author": {"login": "stranger"},
                  "body": "UNTRUSTED-CANARY",
                },
              ],
            }
          }
        }
      }
    },
  ]
  core_body = {
    "title": "Safe title",
    "body": "Safe body",
    "user": {"login": "writer"},
    "labels": [],
    "milestone": None,
    "assignees": [],
  }
  trusted_body = {"data": {"node": {"body": "trusted words", "author": {"login": "writer"}}}}
  linked_pages = [
    {
      "data": {
        "repository": {
          "pullRequest": {
            "timelineItems": {"nodes": [], "pageInfo": {"hasNextPage": False, "endCursor": None}}
          }
        }
      }
    }
  ]
  review_pages = [
    {
      "data": {
        "repository": {
          "pullRequest": {
            "reviews": {"nodes": [], "pageInfo": {"hasNextPage": False, "endCursor": None}}
          }
        }
      }
    }
  ]
  review_thread_pages = [
    {
      "data": {
        "repository": {
          "pullRequest": {
            "reviewThreads": {"nodes": [], "pageInfo": {"hasNextPage": False, "endCursor": None}}
          }
        }
      }
    }
  ]
  runner = FakeRunner(
    [
      core_metadata,
      core_body,
      comment_pages,
      trusted_body,
      linked_pages,
      review_pages,
      review_thread_pages,
    ]
  )
  item = GitHub("o/r", runner).trusted_pr(7, frozenset({"writer"}))
  rendered = json.dumps(item)
  assert "trusted words" in rendered
  assert "untrusted-id" in rendered
  assert "UNTRUSTED-CANARY" not in rendered
  body_calls = [command for command in runner.commands if any("id=" in part for part in command)]
  assert len(body_calls) == 1
  assert "trusted-id" in " ".join(body_calls[0])
  assert "untrusted-id" not in " ".join(body_calls[0])


def test_comment_edits_and_deletions_change_fingerprint():
  base = {"title": "t", "comments": [{"id": "1", "updatedAt": "a"}]}
  edited = {"title": "t", "comments": [{"id": "1", "updatedAt": "b"}]}
  deleted = {"title": "t", "comments": []}
  assert len({fingerprint(base), fingerprint(edited), fingerprint(deleted)}) == 3


def test_closing_issue_relationship_is_metadata_only_and_paginated() -> None:
  pages = [
    {
      "data": {
        "repository": {
          "pullRequest": {
            "closingIssuesReferences": {
              "pageInfo": {"hasNextPage": True, "endCursor": "cursor"},
              "nodes": [
                {
                  "number": 6,
                  "repository": {"nameWithOwner": "o/r"},
                }
              ],
            }
          }
        }
      }
    },
    {
      "data": {
        "repository": {
          "pullRequest": {
            "closingIssuesReferences": {
              "pageInfo": {"hasNextPage": False, "endCursor": None},
              "nodes": [
                {
                  "number": 21,
                  "repository": {"nameWithOwner": "o/r"},
                }
              ],
            }
          }
        }
      }
    },
  ]
  runner = FakeRunner([pages])

  assert GitHub("o/r", runner).pull_request_closing_issues(7) == frozenset(
    {("o/r", 6), ("o/r", 21)}
  )
  command = " ".join(runner.commands[0])
  assert "--paginate" in runner.commands[0]
  assert "title" not in command
  assert "body" not in command


def test_closing_issue_identity_includes_repository_without_fetching_issue_text() -> None:
  pages = [
    {
      "data": {
        "repository": {
          "pullRequest": {
            "closingIssuesReferences": {
              "pageInfo": {"hasNextPage": False, "endCursor": None},
              "nodes": [
                {
                  "number": 21,
                  "repository": {"nameWithOwner": "o/r"},
                },
                {
                  "number": 21,
                  "repository": {"nameWithOwner": "other/repo"},
                },
              ],
            }
          }
        }
      }
    }
  ]

  runner = FakeRunner([pages])
  assert GitHub("o/r", runner).pull_request_closing_issues(7) == frozenset(
    {("o/r", 21), ("other/repo", 21)}
  )
  assert len(runner.commands) == 1
  assert "body" not in " ".join(runner.commands[0])
  assert "title" not in " ".join(runner.commands[0])


@pytest.mark.parametrize("author", ["writer", "stranger"])
def test_external_pr_summary_uses_original_trust_set_and_checks_author_before_text(author):
  metadata = {
    "data": {
      "repository": {
        "pullRequest": {"id": "I", "number": 2, "updatedAt": "t", "author": {"login": author}}
      }
    }
  }
  runner = FakeRunner(
    [metadata, {"title": "safe", "body": "safe", "user": {"login": "writer"}}]
  )
  github = GitHub("o/r", runner).for_repository("other/repo")
  if author == "writer":
    assert github.trusted_pr_summary(2, frozenset({"writer"}))["body"] == "safe"
    assert runner.commands[1] == ["gh", "api", "repos/other/repo/issues/2"]
  else:
    with pytest.raises(PermissionError):
      github.trusted_pr_summary(2, frozenset({"writer"}))
    assert len(runner.commands) == 1
  assert "owner=other" in runner.commands[0]
  assert "repo=repo" in runner.commands[0]
  assert "body" not in " ".join(runner.commands[0])
  assert not any("collaborators" in " ".join(command) for command in runner.commands)


def test_external_pr_author_changed_during_body_fetch_fails_closed():
  runner = FakeRunner(
    [
      {
        "data": {
          "repository": {
            "pullRequest": {
              "id": "I", "number": 2, "updatedAt": "t", "author": {"login": "writer"}
            }
          }
        }
      },
      {"title": "CANARY", "body": "CANARY", "user": {"login": "stranger"}},
    ]
  )
  with pytest.raises(PermissionError, match="author changed"):
    GitHub("o/r", runner).for_repository("other/repo").trusted_pr_summary(2, frozenset({"writer"}))


@pytest.mark.parametrize("repository", [None, "", "other", "other/repo/extra", "../repo", "o/.."])
def test_invalid_linked_repository_fails_closed_before_any_request(repository):
  runner = FakeRunner([])
  with pytest.raises(ValueError, match="repository"):
    GitHub("o/r", runner).for_repository(repository)
  assert runner.commands == []


@pytest.mark.parametrize("empty", [False, True])
def test_incomplete_closing_issue_pagination_fails_closed(empty: bool) -> None:
  pages = (
    []
    if empty
    else [
      {
        "data": {
          "repository": {
            "pullRequest": {
              "closingIssuesReferences": {
                "nodes": [{"number": 6, "repository": {"nameWithOwner": "o/r"}}],
                "pageInfo": {"hasNextPage": True, "endCursor": "next"},
              }
            }
          }
        }
      }
    ]
  )
  with pytest.raises(RuntimeError, match="pagination"):
    GitHub("o/r", FakeRunner([pages])).pull_request_closing_issues(7)


def test_review_comments_are_paginated_within_each_thread() -> None:
  thread_pages = [
    {
      "data": {
        "repository": {
          "pullRequest": {
            "reviewThreads": {
              "nodes": [{"id": "thread-1", "isResolved": False, "isOutdated": False}],
              "pageInfo": {"hasNextPage": False, "endCursor": None},
            }
          }
        }
      }
    }
  ]
  first_page = [
    {
      "id": f"comment-{index}",
      "updatedAt": "t",
      "author": {"login": "writer"},
      "path": "file.py",
      "line": index + 1,
      "originalLine": index + 1,
    }
    for index in range(100)
  ]
  comment_pages = [
    {
      "data": {
        "node": {
          "comments": {
            "nodes": first_page,
            "pageInfo": {"hasNextPage": True, "endCursor": "c"},
          }
        }
      }
    },
    {
      "data": {
        "node": {
          "comments": {
            "pageInfo": {"hasNextPage": False, "endCursor": None},
            "nodes": [
              {
                "id": "comment-100",
                "updatedAt": "t",
                "author": {"login": "writer"},
                "path": "file.py",
                "line": 101,
                "originalLine": 101,
              }
            ],
          }
        }
      }
    },
  ]
  runner = FakeRunner([thread_pages, comment_pages])

  comments = GitHub("o/r", runner)._review_comments(7)

  assert len(comments) == 101
  assert comments[-1]["id"] == "comment-100"
  assert all(comment["_type"] == "PullRequestReviewComment" for comment in comments)
  assert "body" not in " ".join(runner.commands[1])
  assert "threadId=thread-1" in runner.commands[1]
  assert "--paginate" in runner.commands[1]


@pytest.mark.parametrize("empty", [False, True])
def test_invalid_review_comment_pagination_fails_closed(empty: bool) -> None:
  threads = [
    {
      "data": {
        "repository": {
          "pullRequest": {
            "reviewThreads": {
              "nodes": [{"id": "thread-1", "isResolved": False, "isOutdated": False}],
              "pageInfo": {"hasNextPage": False, "endCursor": None},
            }
          }
        }
      }
    }
  ]
  comments = (
    []
    if empty
    else [
      {
        "data": {
          "node": {
            "comments": {
              "nodes": [],
              "pageInfo": {"hasNextPage": True, "endCursor": None},
            }
          }
        }
      },
      {
        "data": {
          "node": {
            "comments": {
              "nodes": [],
              "pageInfo": {"hasNextPage": False, "endCursor": None},
            }
          }
        }
      },
    ]
  )
  with pytest.raises(RuntimeError, match="pagination"):
    GitHub("o/r", FakeRunner([threads, comments]))._review_comments(7)
