# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

from argparse import Namespace
from pathlib import Path
import json
import subprocess

import pytest
from repo_assist import benchmark_tools as b

REPOSITORY = Path(__file__).resolve().parents[4]
A, B = 'a'*40, 'b'*40


def log_text(unit='us/op', baseline=10, current=5):
  manifest = json.dumps({'parameter': 'crossEngineScalingTrial', 'trials': ['Example.compile@safere-string']})
  return f'''=== Requested Trials: {manifest} ===
=== Running Baseline: {A} ===
Benchmark Mode Cnt Score Error Units
Example.compile_safere avgt 10 {baseline} ± 1 {unit}
=== Running Current: {B} ===
Benchmark Mode Cnt Score Error Units
Example.compile_safere avgt 10 {current} ± 1 {unit}
=== Benchmark Comparison Results ===
'''


def arguments(tmp_path, text):
  log=tmp_path/'comparison.log'; log.write_text(text)
  return Namespace(repository=REPOSITORY, log=log, output=tmp_path/'results.json', mode='standard')


@pytest.mark.parametrize('unit', ['ns/op','us/op','ops/s','B/op'])
def test_extract_preserves_native_units_ratios_and_revisions(tmp_path, unit):
  args=arguments(tmp_path, log_text(unit)); result=b.extract(args)
  row=result['rows'][0]
  assert row['baseline']['unit']==unit and row['baseline']['score']==10
  assert row['ratio']==0.5 and not row['reportedIntervalsOverlap']
  assert result['baselineSha']==A and json.loads(args.output.read_text())==result


@pytest.mark.parametrize('broken', ['incomplete','duplicate','mismatch','units','zero'])
def test_extract_rejects_invalid_comparison_without_output(tmp_path, broken):
  text=log_text()
  if broken=='incomplete': text=text.replace(b.COMPLETE,'')
  elif broken=='duplicate': text=text.replace('=== Running Current:', 'Example.compile_safere avgt 10 10 ± 1 us/op\n=== Running Current:')
  elif broken=='mismatch': text=text.replace('Example.compile_safere avgt 10 5', 'Example.other_safere avgt 10 5')
  elif broken=='units': text=text.replace('5 ± 1 us/op','5 ± 1 ns/op')
  else: text=log_text(baseline=0)
  args=arguments(tmp_path,text)
  with pytest.raises(ValueError): b.extract(args)
  assert not args.output.exists()


def git(repo,*args):
  return subprocess.check_output(['git','-C',str(repo),*args],text=True).strip()


@pytest.fixture
def pair(tmp_path):
  repo=tmp_path/'worktree'; repo.mkdir(); git(repo,'init','-q'); git(repo,'config','user.name','Review Test'); git(repo,'config','user.email','test@example.invalid')
  wrapper=repo/'run-java-benchmarks.sh'; wrapper.write_text('#!/bin/sh\nprintf "wrapper:%s:%s\\n" "$(git rev-parse HEAD)" "$*"\n'); wrapper.chmod(0o755)
  with wrapper.open('a') as output:
    output.write("printf 'Benchmark (crossEngineScalingTrial) Mode Cnt Score Error Units\\nCrossEngineScalingBenchmark.run CompileBenchmark.compileSimple@safere-string avgt 10 5 ± 1 us/op\\n'\n")
  parser = repo/'safere-benchmarks/scripts/compare-benchmarks.py'; parser.parent.mkdir(parents=True)
  parser.write_bytes((REPOSITORY/'safere-benchmarks/scripts/compare-benchmarks.py').read_bytes())
  source=repo/'source.txt'; source.write_text('base\n'); git(repo,'add','.'); git(repo,'commit','-qm','base'); base=git(repo,'rev-parse','HEAD')
  git(repo,'checkout','-qb','codex/review/test'); source.write_text('current\n'); git(repo,'commit','-qam','current'); current=git(repo,'rev-parse','HEAD')
  maven=tmp_path/'fake-maven'; maven.write_text('#!/bin/sh\nprintf "clean:%s\\n" "$(git rev-parse HEAD)"\n'); maven.chmod(0o755)
  trials=tmp_path/'trials.txt'; trials.write_text('CompileBenchmark.compileSimple@safere-string\n')
  return Namespace(worktree=repo, log=tmp_path/'paired.log', baseline=base, experiment=current, trials=trials, mode='long', filter='CrossEngineScalingBenchmark.run', parameter='crossEngineScalingTrial', maven=str(maven))


def test_pair_is_serial_uses_wrapper_and_restores_branch(pair):
  result=b.compare(pair); text=pair.log.read_text()
  trace=[line for line in text.splitlines() if line.startswith(('clean:','wrapper:'))]
  assert [line.split(':')[0] for line in trace]==['clean','wrapper','clean','wrapper']
  assert pair.baseline in trace[0] and pair.experiment in trace[2]
  assert '--long --fastbuild CrossEngineScalingBenchmark.run' in text
  assert b.COMPLETE in text and result['mode']=='long'
  assert git(pair.worktree,'symbolic-ref','--short','HEAD')=='codex/review/test'
  extracted = b.extract(Namespace(repository=pair.worktree, log=pair.log, output=pair.log.with_suffix('.json'), mode='long'))
  assert extracted['requestedTrials'] == ['CompileBenchmark.compileSimple@safere-string']
  assert extracted['rows'][0]['engine'] == 'safere'
  assert extracted['trialParameter'] == pair.parameter


def test_pair_failure_restores_branch_and_has_no_success_marker(pair):
  Path(pair.maven).write_text('#!/bin/sh\nexit 3\n')
  with pytest.raises(subprocess.CalledProcessError): b.compare(pair)
  assert b.COMPLETE not in pair.log.read_text()
  assert git(pair.worktree,'symbolic-ref','--short','HEAD')=='codex/review/test'


@pytest.mark.parametrize('kind', ['dirty','harness','branch','ref','inside'])
def test_pair_rejects_unsafe_or_incomparable_inputs_before_switching(pair, kind):
  if kind=='dirty': (pair.worktree/'untracked').write_text('keep')
  elif kind=='harness':
    (pair.worktree/'run-java-benchmarks.sh').write_text('#!/bin/sh\nexit 0\n');git(pair.worktree,'commit','-qam','changed harness');pair.experiment=git(pair.worktree,'rev-parse','HEAD')
  elif kind=='branch': git(pair.worktree,'branch','-m','ordinary-review')
  elif kind=='ref': pair.baseline='main'
  else: pair.log=pair.worktree/'results.log'
  head=git(pair.worktree,'rev-parse','HEAD')
  with pytest.raises(ValueError): b.compare(pair)
  assert git(pair.worktree,'rev-parse','HEAD')==head and not pair.log.exists()


@pytest.mark.parametrize('score', ['-2.000', 'NaN', 'Infinity'])
def test_extract_rejects_skipped_invalid_rows_even_with_valid_measurements(tmp_path, score):
  text=log_text().replace('=== Running Current:', f'Example.other_safere avgt 10 {score} ± 1 us/op\n=== Running Current:')
  text=text.replace(b.COMPLETE, f'Example.other_safere avgt 10 {score} ± 1 us/op\n'+b.COMPLETE)
  args=arguments(tmp_path,text)
  with pytest.raises(ValueError, match='unsupported or invalid row'): b.extract(args)
  assert not args.output.exists()


def test_pair_preserves_changed_files_when_restoration_is_blocked(pair):
  Path(pair.maven).write_text("#!/bin/sh\nprintf 'preserve this\\n' > source.txt\nexit 3\n")
  with pytest.raises(ValueError, match='local changes'): b.compare(pair)
  assert (pair.worktree/'source.txt').read_text()=='preserve this\n'
  assert b.COMPLETE not in pair.log.read_text()
  assert git(pair.worktree,'rev-parse','HEAD')==pair.baseline


def test_extract_rejects_trial_omitted_from_both_arms(tmp_path):
  text = log_text().replace('["Example.compile@safere-string"]', '["Example.compile@safere-string", "Example.other@jdk-string"]')
  args = arguments(tmp_path, text)
  with pytest.raises(ValueError, match='requested trial'):
    b.extract(args)
  assert not args.output.exists()


def test_pair_rejects_successful_process_that_omits_a_requested_trial(pair):
  pair.trials.write_text(pair.trials.read_text() + 'CompileBenchmark.other@jdk-string\n')
  with pytest.raises(ValueError, match='requested trial'):
    b.compare(pair)
  assert b.COMPLETE not in pair.log.read_text()
  assert git(pair.worktree, 'symbolic-ref', '--short', 'HEAD') == 'codex/review/test'


def test_extract_requires_a_manifest_or_an_explicit_legacy_trial_list(tmp_path):
  text = '\n'.join(log_text().splitlines()[1:]) + '\n'
  args = arguments(tmp_path, text)
  with pytest.raises(ValueError, match='requested trial'):
    b.extract(args)
  assert not args.output.exists()
  args.trials = tmp_path / 'trials.txt'
  args.trials.write_text('Example.compile@safere-string\n')
  args.parameter = 'crossEngineScalingTrial'
  result = b.extract(args)
  assert result['requestedTrials'] == ['Example.compile@safere-string']


def test_pair_rejects_trial_missing_only_from_experiment(pair, monkeypatch):
  pair.trials.write_text(pair.trials.read_text() + 'CompileBenchmark.other@jdk-string\n')
  original = b.subprocess.run
  def measure(command, **kwargs):
    if command[0] != './run-java-benchmarks.sh':
      return original(command, **kwargs)
    output = kwargs['stdout']
    output.write('Benchmark (crossEngineScalingTrial) Mode Cnt Score Error Units\n')
    output.write('CrossEngineScalingBenchmark.run CompileBenchmark.compileSimple@safere-string avgt 10 5 ± 1 us/op\n')
    if git(pair.worktree, 'rev-parse', 'HEAD') == pair.baseline:
      output.write('CrossEngineScalingBenchmark.run CompileBenchmark.other@jdk-string avgt 10 6 ± 1 us/op\n')
    return subprocess.CompletedProcess(command, 0)
  monkeypatch.setattr(b.subprocess, 'run', measure)
  with pytest.raises(ValueError, match='requested trial'):
    b.compare(pair)
  assert f'=== Running Current: {pair.experiment} ===' in pair.log.read_text()
  assert b.COMPLETE not in pair.log.read_text()
  assert git(pair.worktree, 'symbolic-ref', '--short', 'HEAD') == 'codex/review/test'


@pytest.mark.parametrize('kind', ['duplicate', 'empty', 'variant', 'parameter', 'multiple', 'explicit'])
def test_extract_rejects_invalid_or_disagreeing_trial_manifests(tmp_path, kind):
  manifest = {'parameter': 'crossEngineScalingTrial', 'trials': ['Example.compile@safere-string']}
  if kind == 'duplicate': manifest['trials'] *= 2
  elif kind == 'empty': manifest['trials'] = []
  elif kind == 'variant': manifest['trials'] = ['Example.compile@unknown-engine']
  elif kind == 'parameter': manifest['parameter'] = 'unknown'
  line = '=== Requested Trials: ' + json.dumps(manifest) + ' ==='
  text = line + '\n' + '\n'.join(log_text().splitlines()[1:]) + '\n'
  if kind == 'multiple': text = line + '\n' + text
  args = arguments(tmp_path, text)
  if kind == 'explicit':
    args.trials = tmp_path / 'trials.txt'; args.trials.write_text('Example.other@jdk-string\n')
  with pytest.raises(ValueError): b.extract(args)
  assert not args.output.exists()
