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
  runner = FakeRunner([[
      [{"login": "owner", "type": "User", "permissions": {"admin": True}}],
      [
          {"login": "writer", "type": "User", "permissions": {"push": True}},
          {"login": "reader", "type": "User", "permissions": {"pull": True}},
          {"login": "robot", "type": "Bot", "permissions": {"push": True}},
      ],
  ]])
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
  runner = FakeRunner([[{
      "number": 1, "isDraft": False, "url": "u", "updatedAt": "t",
      "author": {"login": "stranger"}, "title": "CANARY", "body": "SECRET",
  }]])
  result = GitHub("o/r", runner).discover("pr", frozenset({"writer"}))
  rendered = json.dumps(result)
  assert "CANARY" not in rendered
  assert "SECRET" not in rendered
  assert result["untrusted"][0]["number"] == 1


def test_untrusted_root_item_body_is_never_requested():
  metadata = {"data": {"repository": {"issue": {
      "id": "I", "number": 9, "updatedAt": "t", "author": {"login": "stranger"},
  }}}}
  runner = FakeRunner([metadata])
  with pytest.raises(PermissionError):
    GitHub("o/r", runner).trusted_item("issue", 9, frozenset({"writer"}))
  assert len(runner.commands) == 1


def test_only_trusted_comment_bodies_are_requested_and_pages_are_combined():
  core_metadata = {"data": {"repository": {"issue": {
      "id": "I", "number": 7, "updatedAt": "t", "author": {"login": "writer"},
  }}}}
  comment_pages = [
      {"data": {"repository": {"issue": {"comments": {"nodes": [
          {"id": "trusted-id", "updatedAt": "a", "author": {"login": "writer"}},
      ]}}}}},
      {"data": {"repository": {"issue": {"comments": {"nodes": [
          {"id": "untrusted-id", "updatedAt": "b", "author": {"login": "stranger"},
           "body": "UNTRUSTED-CANARY"},
      ]}}}}},
  ]
  core_body = {
      "title": "Safe title", "body": "Safe body", "user": {"login": "writer"},
      "labels": [], "milestone": None, "assignees": [],
  }
  trusted_body = {"data": {"node": {"body": "trusted words"}}}
  linked_pages = [{"data": {"repository": {"issue": {"timelineItems": {"nodes": []}}}}}]
  runner = FakeRunner([core_metadata, core_body, comment_pages, trusted_body, linked_pages])
  item = GitHub("o/r", runner).trusted_item("issue", 7, frozenset({"writer"}))
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
