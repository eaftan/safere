# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
"""Exercise runner reporting with fake processes; no Java build or fuzzing needed."""

import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPTS = Path(__file__).resolve().parent


class FuzzRunnerTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.scripts = self.root / 'safere-fuzz/scripts'
        self.scripts.mkdir(parents=True)
        for name in ('run-fuzz-test.sh', 'targets.py', 'report-fuzz-result.py'):
            if (SCRIPTS / name).exists():
                shutil.copy(SCRIPTS / name, self.scripts)
        source = self.root / 'safere-fuzz/src/test/java/org/safere/fuzz/ExampleFuzzer.java'
        source.parent.mkdir(parents=True)
        source.write_text('@FuzzTest(maxDuration = "30s")\nvoid first(\n'
                          '@FuzzTest(maxDuration = "30s")\nvoid second(\n')
        (self.root / 'safere-fuzz/targets.json').write_text(json.dumps([{
            'class': 'org.safere.fuzz.ExampleFuzzer', 'kind': 'broad',
            'oss_fuzz': False, 'seeds': None, 'methods': ['first', 'second'],
        }]))
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        # Deliberately exclude rg and grep: classification must use the declared Python dependency.
        for name in ('bash', 'dirname', 'date', 'mkdir', 'tee'):
            (self.bin / name).symlink_to(shutil.which(name))
        (self.bin / 'python3').symlink_to(sys.executable)
        self.executable('git', '#!/bin/bash\nif [[ "$*" != *status* ]]; then echo abc123; fi\n')
        self.executable('java', '#!/bin/bash\necho "test JDK" >&2\n')
        self.executable('mvn', '''#!/usr/bin/env python3
import json, os, pathlib, sys
args = dict(arg[2:].split('=', 1) for arg in sys.argv[1:] if arg.startswith('-D') and '=' in arg)
case = json.loads(os.environ['FAKE_CASES'])[args['test']]
print(case.get('log', 'No findings'), flush=True)
if case.get('json'):
    directory = pathlib.Path(args['safere.fuzz.findingsDir'])
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'finding.json').write_text(json.dumps(case['json']))
if case.get('reproducer'):
    directory = pathlib.Path(args['jazzer.reproducer_path'])
    (directory / 'crash-abc123').write_bytes(b'input')
if case.get('broken_findings_directory'):
    pathlib.Path(args['safere.fuzz.findingsDir']).write_text('not a directory')
if case.get('broken_log_file'):
    log = pathlib.Path(args['safere.fuzz.findingsDir']).parent / (args['test'] + '.log')
    log.unlink(missing_ok=True)
    log.mkdir()
if case.get('broken_result_file'):
    result = pathlib.Path(args['safere.fuzz.findingsDir']).parent / (args['test'] + '-result.json')
    result.mkdir()
sys.exit(case.get('exit', 0))
''')
        self.summary = self.root / 'step-summary.md'

    def executable(self, name, content):
        path = self.bin / name
        path.write_text(content)
        path.chmod(0o755)

    def run_runner(self, first, second=None):
        cases = {'ExampleFuzzer#first': first, 'ExampleFuzzer#second': second or {}}
        env = dict(os.environ, PATH=str(self.bin), FAKE_CASES=json.dumps(cases),
                   GITHUB_STEP_SUMMARY=str(self.summary))
        result = subprocess.run(
            [str(self.bin / 'bash'), str(self.scripts / 'run-fuzz-test.sh'),
             '--suite', 'broad', '--max-duration', '1s', '--keep-going', '3'],
            env=env, capture_output=True, text=True)
        return result

    def assert_report(self, result, process, findings, reporting='OK'):
        line = (f'ExampleFuzzer#first: process={process}; '
                f'findings={findings}; reporting={reporting}')
        self.assertIn(line, result.stdout)
        # The target, final console summary, and Actions summary must all report it.
        self.assertGreaterEqual(result.stdout.count(line), 2)
        self.assertIn(line, self.summary.read_text())
        self.assertIn('ExampleFuzzer#second: process=SUCCEEDED (exit 0); findings=NONE',
                      result.stdout)
        self.assertNotIn('PASS', result.stdout)
        logs = self.root / 'safere-fuzz/target/fuzz-logs'
        latest = max(logs.iterdir(), key=lambda p: p.stat().st_mtime_ns)
        self.assertIn(line, (latest / 'summary.txt').read_text())
        self.assertIn(line, (latest / 'ExampleFuzzer#first.log').read_text())
        record = json.loads((latest / 'ExampleFuzzer#first-result.json').read_text())
        self.assertEqual(record['process'], process.split()[0])
        self.assertEqual(record['exit_code'], int(process.split()[-1].rstrip(')')))
        self.assertEqual(record['findings'], findings)
        self.assertEqual(record['reporting'], reporting)
        return line

    def test_clean_process(self):
        result = self.run_runner({})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_report(result, 'SUCCEEDED (exit 0)', 'NONE')

    def test_old_evidence_does_not_contaminate_current_run(self):
        old_log = self.root / 'safere-fuzz/target/fuzz-logs/old/ExampleFuzzer#first.log'
        old_log.parent.mkdir(parents=True)
        old_log.write_text('== Java Exception: java.lang.AssertionError')
        reports = self.root / 'safere-fuzz/target/surefire-reports'
        reports.mkdir()
        (reports / 'old.txt').write_text('DEDUP_TOKEN: old-finding')
        result = self.run_runner({})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_report(result, 'SUCCEEDED (exit 0)', 'NONE')

    def test_findings_for_successful_and_failed_processes(self):
        for exit_code in (0, 1):
            for marker in ('java.lang.AssertionError: divergence',
                           '== Java Exception: java.lang.StringIndexOutOfBoundsException',
                           'FuzzerSecurityIssueHigh', 'DEDUP_TOKEN: abc',
                           'ERROR: libFuzzer: deadly signal'):
                with self.subTest(exit_code=exit_code, marker=marker):
                    result = self.run_runner({'exit': exit_code, 'log': marker})
                    self.assertEqual(result.returncode, 1, result.stderr)
                    process = 'SUCCEEDED' if exit_code == 0 else 'FAILED'
                    self.assert_report(result, f'{process} (exit {exit_code})', 'UNTRIAGED')

    def test_json_only_finding_is_untriaged_even_with_intentional_path_signal(self):
        result = self.run_runner({'json': {'divergenceSignals': 'intentional-path'}})
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assert_report(result, 'SUCCEEDED (exit 0)', 'UNTRIAGED')
        self.assertIn('may include known intentional differences', result.stdout)

    def test_raw_reproducer_without_semantic_json(self):
        result = self.run_runner({'reproducer': True})
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assert_report(result, 'SUCCEEDED (exit 0)', 'UNTRIAGED')

    def test_execution_failure_without_findings(self):
        result = self.run_runner({'exit': 1, 'log': '[ERROR] Compilation failure'})
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assert_report(result, 'FAILED (exit 1)', 'NONE')

    def test_execution_failure_and_findings_are_both_visible(self):
        result = self.run_runner({'exit': 1, 'json': {'operation': 'find'},
                                  'log': '[ERROR] Could not resolve dependencies'})
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assert_report(result, 'FAILED (exit 1)', 'UNTRIAGED')

    def test_missing_classifier_is_reporting_failure_and_other_targets_still_run(self):
        (self.scripts / 'report-fuzz-result.py').unlink(missing_ok=True)
        result = self.run_runner({})
        self.assertEqual(result.returncode, 1, result.stderr)
        line = ('ExampleFuzzer#first: process=SUCCEEDED (exit 0); '
                'findings=UNKNOWN; reporting=FAILED')
        self.assertIn(line, result.stdout)
        self.assertIn(line, self.summary.read_text())
        self.assertIn('Completed ExampleFuzzer#second', result.stdout)

    def test_classifier_error_is_not_misreported_as_execution_failure(self):
        result = self.run_runner({'broken_findings_directory': True})
        self.assertEqual(result.returncode, 1, result.stderr)
        line = ('ExampleFuzzer#first: process=SUCCEEDED (exit 0); '
                'findings=UNKNOWN; reporting=FAILED')
        self.assertIn(line, result.stdout)
        self.assertIn(line, self.summary.read_text())
        self.assertIn('Completed ExampleFuzzer#second', result.stdout)

    def test_result_json_write_failure_preserves_observed_statuses(self):
        for exit_code in (0, 1):
            for has_findings in (False, True):
                with self.subTest(exit_code=exit_code, has_findings=has_findings):
                    log = '== Java Exception: java.lang.AssertionError' if has_findings else 'Clean'
                    result = self.run_runner({'exit': exit_code, 'log': log,
                                              'broken_result_file': True})
                    self.assertEqual(result.returncode, 1, result.stderr)
                    process = 'SUCCEEDED' if exit_code == 0 else 'FAILED'
                    findings = 'UNTRIAGED' if has_findings else 'NONE'
                    line = (f'ExampleFuzzer#first: process={process} (exit {exit_code}); '
                            f'findings={findings}; reporting=FAILED')
                    self.assertGreaterEqual(result.stdout.count(line), 2)
                    self.assertIn(line, self.summary.read_text())
                    self.assertIn('could not write result JSON', result.stderr)
                    logs = self.root / 'safere-fuzz/target/fuzz-logs'
                    latest = max(logs.iterdir(), key=lambda p: p.stat().st_mtime_ns)
                    self.assertIn(line, (latest / 'summary.txt').read_text())
                    self.assertIn(line, (latest / 'ExampleFuzzer#first.log').read_text())
                    self.assertIn('Completed ExampleFuzzer#second', result.stdout)

    def test_log_capture_failure_is_reporting_failure(self):
        real_tee = shutil.which('tee')
        (self.bin / 'tee').unlink()
        self.executable('tee', f'''#!/usr/bin/env python3
import subprocess, sys
result = subprocess.run([{real_tee!r}, *sys.argv[1:]])
if len(sys.argv) == 2 and sys.argv[1].endswith('.log'):
    sys.exit(1)
sys.exit(result.returncode)
''')
        result = self.run_runner({})
        self.assertEqual(result.returncode, 1, result.stderr)
        line = ('ExampleFuzzer#first: process=SUCCEEDED (exit 0); '
                'findings=UNKNOWN; reporting=FAILED')
        self.assertIn(line, result.stdout)
        self.assertIn(line, self.summary.read_text())
        self.assertIn('Completed ExampleFuzzer#second', result.stdout)

    def test_persistent_log_failure_does_not_abort_remaining_targets_or_summaries(self):
        result = self.run_runner({'broken_log_file': True})
        self.assertEqual(result.returncode, 1, result.stderr)
        line = ('ExampleFuzzer#first: process=SUCCEEDED (exit 0); '
                'findings=UNKNOWN; reporting=FAILED')
        self.assertIn(line, result.stdout)
        self.assertIn(line, self.summary.read_text())
        self.assertIn('could not append target summary', result.stderr)
        self.assertIn('Completed ExampleFuzzer#second', result.stdout)

    def test_archive_summary_failure_does_not_prevent_actions_summary(self):
        real_tee = shutil.which('tee')
        (self.bin / 'tee').unlink()
        self.executable('tee', f'''#!/usr/bin/env python3
import subprocess, sys
if sys.argv[-1].endswith('/summary.txt'):
    sys.stdout.write(sys.stdin.read())
    sys.exit(1)
sys.exit(subprocess.run([{real_tee!r}, *sys.argv[1:]]).returncode)
''')
        result = self.run_runner({})
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn('FAILED (could not write archived summary)', result.stdout)
        self.assertIn('FAILED (could not write archived summary)', self.summary.read_text())
        self.assertIn('ExampleFuzzer#second: process=SUCCEEDED', self.summary.read_text())
        self.assertIn('Fuzz run requires attention', result.stdout)

    def test_actions_summary_failure_is_reported_in_console(self):
        self.summary.mkdir()
        result = self.run_runner({})
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn('FAILED (could not write Actions summary)', result.stdout)
        self.assertIn('Completed ExampleFuzzer#second', result.stdout)
        self.assertIn('Fuzz run requires attention', result.stdout)

    def test_later_target_findings_are_in_final_summary(self):
        result = self.run_runner({}, {'log': '== Java Exception: java.lang.IllegalStateException'})
        self.assertEqual(result.returncode, 1, result.stderr)
        line = ('ExampleFuzzer#second: process=SUCCEEDED (exit 0); '
                'findings=UNTRIAGED; reporting=OK')
        self.assertGreaterEqual(result.stdout.count(line), 2)
        self.assertIn(line, self.summary.read_text())

    def test_missing_python_is_explicit_prerequisite_failure(self):
        (self.bin / 'python3').unlink()
        result = self.run_runner({})
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn('python3 is required', result.stderr)


class FuzzReporterTest(unittest.TestCase):
    def test_log_append_failure_retains_observed_findings_in_result_json(self):
        spec = importlib.util.spec_from_file_location('reporter', SCRIPTS / 'report-fuzz-result.py')
        reporter = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(reporter)
        with tempfile.TemporaryDirectory() as directory:
            logs = Path(directory)
            (logs / 'ExampleFuzzer#first.log').write_text('== Java Exception: java.lang.AssertionError')
            (logs / 'ExampleFuzzer#first-reproducers').mkdir()
            original_open = Path.open

            def fail_log_append(path, mode='r', *args, **kwargs):
                if path.suffix == '.log' and mode == 'a':
                    raise PermissionError('log remains unwritable')
                return original_open(path, mode, *args, **kwargs)

            with patch.object(Path, 'open', fail_log_append), patch('sys.stderr'):
                result = reporter.report(logs, 'ExampleFuzzer#first', 0)
            self.assertIn('findings=UNTRIAGED; reporting=FAILED', result)
            record = json.loads((logs / 'ExampleFuzzer#first-result.json').read_text())
            self.assertEqual(record['findings'], 'UNTRIAGED')
            self.assertEqual(record['reporting'], 'FAILED')


if __name__ == '__main__':
    unittest.main()
