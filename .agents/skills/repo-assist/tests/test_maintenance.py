# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

from argparse import Namespace
import json
from pathlib import Path
import subprocess

import pytest
from repo_assist import maintenance as m

A, B = 'a' * 40, 'b' * 40


def discovery(number=7, head=A):
  return {'repositoryOwner': 'owner', 'authenticatedLogin': 'owner', 'trusted': [
    {'number': number, 'author': {'login': 'contributor'}, 'isDraft': False,
     'headRefOid': head, 'updatedAt': '2026-10-09T00:00:00Z'}]}


def put(path, value):
  path.parent.mkdir(parents=True, exist_ok=True)
  path.write_text(json.dumps(value))
  return path


@pytest.fixture
def assessment(tmp_path):
  report = tmp_path / 'reports/run.md'
  report.parent.mkdir()
  report.write_text('Status: running\n\n## PR Summary\n\n| Done | PR | Brief Assessment | Recommendation |\n|---|---|---|---|\n| Y | [PR #7](#pr-7) | pending | pending |\n\n## Merge Ordering\n\nPending.\n')
  # The actual report always has a title before its status.
  report.write_text('# Run\n\n' + report.read_text())
  put(tmp_path / 'locks/run.lockdir/metadata.json', {'token': 'token', 'runId': 'run', 'reportPath': str(report)})
  put(tmp_path / 'state.json', {'issues': {'8': {'keep': True}}, 'authoredPrFeedback': {'9': {}}, 'lastIssueActivityCutoff': 'prior', 'prs': {'7': {'stackContext': {'keep': True}, 'lastFixCommit': B}}})
  d = put(tmp_path / 'discovery.json', discovery())
  snapshot = put(tmp_path / 'snapshot.json', {'fingerprint': 'fingerprint', 'pr': {'number': 7, 'updatedAt': '2026-10-09T00:00:00Z'}})
  record = {'status': 'reviewed', 'classification': 'other', 'lastHeadSha': A, 'lastBaseSha': B, 'lastTrunkSha': B, 'lastSeenUpdatedAt': '2026-10-09T00:00:00Z', 'fingerprint': 'fingerprint', 'preparedHeadSha': A, 'lastFixCommit': None, 'lastFixBranch': None}
  spec = put(tmp_path / 'spec.json', {'number': 7, 'assessment': 'Complete.', 'recommendation': 'Can merge', 'record': record})
  section = tmp_path / 'section.md'; section.write_text('## PR #7: A change\n\n### Intent\n\nAccurate source analysis.')
  copy = tmp_path / 'copy.md'; copy.write_text('The change preserves its invariant.\n\nLGTM.')
  return Namespace(root=tmp_path, token='token', discovery=d, snapshot=snapshot, spec=spec, section=section, author_copy=copy), report


def test_checkpoint_repeat_preserves_done_categories_and_context(assessment):
  args, report = assessment
  m.checkpoint(args); m.checkpoint(args)
  text = report.read_text(); state = m.read_json(args.root / 'state.json')
  assert text.count('<a id="pr-7"></a>') == 1
  assert '| Y | [PR #7]' in text
  assert '## Merge Ordering' in text
  assert state['issues'] == {'8': {'keep': True}}
  assert state['lastIssueActivityCutoff'] == 'prior'
  assert state['prs']['7']['stackContext'] == {'keep': True}
  assert state['prs']['7']['lastFixCommit'] is None
  # Updating an existing section preserves later unrelated report content.
  with report.open('a') as out: out.write('\n## Issues\n\nAn independent category.\n')
  m.checkpoint(args)
  assert report.read_text().endswith('## Issues\n\nAn independent category.\n')


def test_checkpoint_replay_after_state_write_interruption(assessment, monkeypatch):
  args, report = assessment
  original = m.write_json
  def interrupted(path, value):
    if path == args.root / 'state.json': raise OSError('interrupted state write')
    original(path, value)
  monkeypatch.setattr(m, 'write_json', interrupted)
  with pytest.raises(OSError): m.checkpoint(args)
  assert (args.root / 'artifacts/run/pr-7/checkpoint.json').exists()
  monkeypatch.setattr(m, 'write_json', original)
  m.checkpoint(args)
  assert report.read_text().count('<a id="pr-7"></a>') == 1
  assert m.read_json(args.root / 'state.json')['prs']['7']['status'] == 'reviewed'


@pytest.mark.parametrize('kind', ['token', 'draft', 'owner', 'snapshot', 'finished'])
def test_checkpoint_rejects_invalid_input_before_writing(assessment, kind):
  args, report = assessment
  if kind == 'token': args.token = 'wrong'
  elif kind in {'draft', 'owner'}:
    d = m.read_json(args.discovery)
    if kind == 'draft': d['trusted'][0]['isDraft'] = True
    else: d['trusted'][0]['author']['login'] = 'owner'
    put(args.discovery, d)
  elif kind == 'snapshot': put(args.snapshot, {'changed': False, 'fingerprint': 'fingerprint'})
  else: report.write_text(report.read_text().replace('Status: running', 'Status: completed'))
  before = report.read_bytes(); state = (args.root / 'state.json').read_bytes()
  with pytest.raises(ValueError): m.checkpoint(args)
  assert report.read_bytes() == before and (args.root / 'state.json').read_bytes() == state


def test_audit_checks_structure_and_keeps_semantic_review_explicit(assessment):
  args, report = assessment; m.checkpoint(args)
  check = Namespace(root=args.root, report=report, discovery=args.discovery, output_dir=args.root / 'copies', check_worktrees=False)
  assert m.audit(check)['mechanicalChecksPassed']
  assert 'cannot assess factual completeness' in m.audit(check)['semanticAuditRequired']
  assert (check.output_dir / 'author-copy-7.md').read_text().endswith('LGTM.\n')
  report.write_text(report.read_text().replace('The change preserves its invariant.', 'Run mvn in my worktree.'))
  assert not m.audit(check)['mechanicalChecksPassed']
  report.write_text(report.read_text().replace('Run mvn in my worktree.', 'The shared compiler artifacts preserve ownership.'))
  assert m.audit(check)['mechanicalChecksPassed']


def run_git(repo, *args):
  return subprocess.check_output(['git', '-C', str(repo), *args], text=True).strip()


def test_merge_order_reports_real_conflict_without_changing_head(tmp_path):
  repo = tmp_path / 'repo'; repo.mkdir(); run_git(repo, 'init', '-q')
  run_git(repo, 'config', 'user.name', 'Review Test'); run_git(repo, 'config', 'user.email', 'test@example.invalid')
  file = repo / 'source.txt'; file.write_text('base\n'); run_git(repo, 'add', '.'); run_git(repo, 'commit', '-qm', 'base'); base=run_git(repo, 'rev-parse', 'HEAD')
  heads=[]
  for name in ('first', 'second'):
    run_git(repo, 'checkout', '-qb', name, base); file.write_text(name+'\n'); run_git(repo, 'commit', '-qam', name); heads.append(run_git(repo, 'rev-parse', 'HEAD'))
  d=discovery(); d['trusted'].append({**d['trusted'][0], 'number': 8, 'headRefOid': heads[1]})
  root=tmp_path/'storage'; put(root/'state.json', {'prs': {str(n): {'status': 'reviewed', 'preparedHeadSha': sha, 'lastTrunkSha': base} for n,sha in zip((7,8), heads)}})
  path=put(tmp_path/'discovery.json', d); old=run_git(repo, 'rev-parse', 'HEAD')
  result=m.merge_order(Namespace(root=root, discovery=path, repository=repo))
  assert result['pairs'][0]['conflict'] and result['pairs'][0]['sharedFiles']==['source.txt']
  assert run_git(repo, 'rev-parse', 'HEAD')==old and not run_git(repo, 'status', '--porcelain')


def test_checkpoint_and_audit_preserve_nested_code_examples(assessment):
  args, report = assessment
  args.author_copy.write_text('The example is useful.\n\n```markdown\n## An example heading\n\n<a id="pr-999"></a>\n```\n\nLGTM.')
  m.checkpoint(args); m.checkpoint(args)
  assert '````markdown' in report.read_text()
  check = Namespace(root=args.root, report=report, discovery=args.discovery, output_dir=None, check_worktrees=False)
  assert m.audit(check)['mechanicalChecksPassed']
  assert m.copy_text(report.read_text()) == args.author_copy.read_text()


def test_audit_benchmark_table_requirement_does_not_invent_measurements(assessment):
  args, report = assessment; m.checkpoint(args)
  state = m.read_json(args.root / 'state.json'); state['prs']['7']['classification'] = 'optimization'; put(args.root / 'state.json', state)
  check = Namespace(root=args.root, report=report, discovery=args.discovery, output_dir=None, check_worktrees=False)
  assert m.audit(check)['mechanicalChecksPassed']
  report.write_text(report.read_text().replace('### Intent', '### Benchmark Reproduction\n\n| Workload | Baseline | PR |\n|---|---:|---:|\n| Compile | 10 | 5 |\n\n### Intent'))
  result=m.audit(check)
  assert not result['mechanicalChecksPassed'] and 'missing from the author review' in result['problems'][0]


def test_refresh_new_item_uses_trust_helper_and_does_not_change_assessment_state(tmp_path, monkeypatch):
  put(tmp_path / 'state.json', {'prs': {}, 'issues': {'keep': True}})
  before=(tmp_path/'state.json').read_bytes(); calls=[]
  def helper(command, **kwargs):
    calls.append(command)
    if 'discover' in command: return json.dumps(discovery())
    return json.dumps({'changed': True, 'fingerprint': 'new', 'pr': {'number': 7}})
  monkeypatch.setattr(m.subprocess, 'check_output', helper)
  result=m.refresh(Namespace(root=tmp_path, repository_name='owner/repo', output_dir=tmp_path/'fresh'))
  assert result['changed'] and result['checks'][0]['new']
  assert len(calls)==2 and all('repo_assist.cli' in call[2] for call in calls)
  assert (tmp_path/'state.json').read_bytes()==before
  assert m.read_json(tmp_path/'fresh/snapshot-7.json')['pr']['number']==7
