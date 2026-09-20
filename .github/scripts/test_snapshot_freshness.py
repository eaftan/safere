# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).
"""Regression checks for publishing when main CI finishes out of push order."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class SnapshotFreshnessTest(unittest.TestCase):
    def test_successful_run_ordering(self):
        for numbers, expected in [
            ("10\n9", "true"),
            ("11\n10", "false"),
            ("9\n11\n10", "false"),
            ("9", "true"),
            ("", "true"),
        ]:
            with self.subTest(numbers=numbers):
                result, output = self.check(numbers)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(output, f"current={expected}\n")

    def test_api_failure_blocks_publication(self):
        result, output = self.check("", exit_code=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(output, "")

    def test_newer_run_requires_successful_validation_gate(self):
        for gate in ["queued", "in_progress", "failure", "cancelled", "success"]:
            with self.subTest(gate=gate):
                result, output = self.check("11\n10", gate=gate)
                self.assertEqual(result.returncode, 0, result.stderr)
                expected = "false" if gate == "success" else "true"
                self.assertEqual(output, f"current={expected}\n")

    def test_job_api_failure_blocks_publication(self):
        result, output = self.check("11\n10", job_exit=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(output, "")

    def check(self, numbers, exit_code=0, gate="success", job_exit=0):
        with tempfile.TemporaryDirectory() as directory:
            stub = Path(directory) / "gh"
            stub.write_text(
                '#!/bin/bash\n'
                '[[ "$*" == *"--paginate"* ]] || exit 2\n'
                'if [[ "$*" == *"/jobs?filter=latest"* ]]; then\n'
                '  [[ "$*" == *"ci-gate"* && "$*" == *"success"* ]] || exit 2\n'
                '  if [[ "$TEST_GATE" == success ]]; then echo 123; fi\n'
                '  exit "$TEST_JOB_EXIT"\n'
                'fi\n'
                '[[ "$*" == *"branch=main&event=push&per_page=100"* ]] || exit 2\n'
                'while read -r number; do\n'
                '  if [[ -n "$number" ]]; then printf "%s\\t%s\\n" "$number" "$number"; fi\n'
                'done <<< "$TEST_RUN_NUMBERS"\n'
                'exit "$TEST_EXIT"\n'
            )
            stub.chmod(0o755)
            output = Path(directory) / "output"
            output.touch()
            environment = dict(
                os.environ,
                PATH=directory + os.pathsep + os.environ["PATH"],
                CI_RUN_NUMBER="10",
                GITHUB_REPOSITORY="eaftan/safere",
                GITHUB_OUTPUT=str(output),
                TEST_RUN_NUMBERS=numbers,
                TEST_EXIT=str(exit_code),
                TEST_GATE=gate,
                TEST_JOB_EXIT=str(job_exit),
            )
            script = Path(__file__).with_name("check-snapshot-freshness.sh")
            result = subprocess.run(
                ["bash", str(script)], env=environment, capture_output=True, text=True
            )
            return result, output.read_text()


if __name__ == "__main__":
    unittest.main()
