#!/usr/bin/env python3
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.

"""Exercise reservations with small subprocesses; no builds or benchmarks run."""

import fcntl
import json
import os
from pathlib import Path
import select
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "tools" / "machine-run"
HOLD = "import sys; print('ready', flush=True); sys.stdin.read()"


class MachineRunTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.lock_path = self.directory / "machine.lock"
        self.env = dict(os.environ, SAFERE_MACHINE_LOCK_FILE=str(self.lock_path))
        self.env.pop("SAFERE_MACHINE_RUN_CONTEXT", None)

    def start(self, args, cwd=None, env=None):
        process = subprocess.Popen(args, cwd=cwd, env=env or self.env,
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True, start_new_session=True)

        def cleanup():
            if process.poll() is None:
                process.terminate()
            try:
                process.communicate(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate(timeout=5)

        self.addCleanup(cleanup)
        return process

    def start_job(self, code=HOLD, runner=RUNNER, **kwargs):
        return self.start([str(runner), "--label", "test workload", "--",
                           sys.executable, "-c", code], **kwargs)

    def line(self, stream):
        readable, _, _ = select.select([stream], [], [], 10)
        self.assertTrue(readable, "timed out waiting for subprocess output")
        return stream.readline().strip()

    def invoke(self, args, **kwargs):
        return subprocess.run([str(RUNNER), *args], env=self.env, capture_output=True,
                              text=True, timeout=10, **kwargs)

    def test_commands_in_separate_worktrees_exclude_each_other_and_resume(self):
        runners = []
        for name in ("worktree-a", "worktree-b"):
            directory = self.directory / name
            shutil.copytree(ROOT / "tools", directory / "tools")
            runners.append(directory / "tools" / "machine-run")
        first = self.start_job(runner=runners[0], cwd=runners[0].parents[1])
        self.assertEqual(self.line(first.stdout), "ready")
        second = self.start_job(runner=runners[1], cwd=runners[1].parents[1])
        self.assertIn("waiting", self.line(second.stderr))
        self.assertFalse(select.select([second.stdout], [], [], 0)[0])
        first.stdin.write("done")
        first.stdin.flush()
        first.stdin.close()
        first.stdin = None
        self.assertEqual(first.wait(timeout=10), 0)
        self.assertEqual(self.line(second.stdout), "ready")

    def test_status_distinguishes_idle_from_running_and_reports_owner(self):
        self.assertEqual(self.invoke(["--status"]).stdout.strip(), "machine-run: idle")
        job = self.start_job()
        self.assertEqual(self.line(job.stdout), "ready")
        status = self.invoke(["--status"])
        self.assertEqual(status.returncode, 0, status.stderr)
        self.assertIn("test workload", status.stdout)
        self.assertIn(str(job.pid), status.stdout)
        self.assertIn("cwd", status.stdout)
        job.terminate()
        self.assertEqual(job.wait(timeout=10), 143)
        self.assertEqual(self.invoke(["--status"]).stdout.strip(), "machine-run: idle")

    def test_preserves_arguments_working_directory_io_and_failure_status(self):
        code = ("import json, os, sys; "
                "print(json.dumps([os.getcwd(), sys.argv[1:], sys.stdin.read()])); "
                "print('child stderr', file=sys.stderr); sys.exit(23)")
        result = self.invoke(["--", sys.executable, "-c", code, "a b", "$(literal)", ""],
                             cwd=self.directory, input="input text")
        self.assertEqual(result.returncode, 23, result.stderr)
        self.assertEqual(json.loads(result.stdout),
                         [str(self.directory), ["a b", "$(literal)", ""], "input text"])
        self.assertIn("child stderr", result.stderr)
        self.assertEqual(self.invoke(["--status"]).stdout.strip(), "machine-run: idle")

    def test_nested_runner_survives_python_closing_inherited_descriptors(self):
        code = ("import subprocess, sys; "
                f"sys.exit(subprocess.call([{str(RUNNER)!r}, '--', sys.executable, "
                "'-c', 'print(42)'], close_fds=True))")
        result = self.invoke(["--", sys.executable, "-c", code])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "42")
        self.assertEqual(result.stderr.count("machine-run: running"), 1)

    def test_context_from_another_process_group_cannot_bypass_the_lock(self):
        code = ("import os; print(os.environ['SAFERE_MACHINE_RUN_CONTEXT'], flush=True); "
                "import sys; sys.stdin.read()")
        owner = self.start_job(code)
        context = self.line(owner.stdout)
        env = dict(self.env, SAFERE_MACHINE_RUN_CONTEXT=context)
        waiter = self.start_job(env=env)
        self.assertIn("waiting", self.line(waiter.stderr))
        waiter.terminate()
        self.assertEqual(waiter.wait(timeout=10), 143)
        self.assertIsNone(owner.poll())

    def test_cancelling_a_waiter_does_not_release_the_running_job(self):
        owner = self.start_job()
        self.assertEqual(self.line(owner.stdout), "ready")
        waiter = self.start_job()
        self.assertIn("waiting", self.line(waiter.stderr))
        waiter.send_signal(signal.SIGINT)
        self.assertEqual(waiter.wait(timeout=10), 130)
        self.assertIn("test workload", self.invoke(["--status"]).stdout)
        self.assertIsNone(owner.poll())

    def test_cancellation_kills_grandchildren_even_if_they_ignore_termination(self):
        grandchild = ("import os, signal; signal.signal(signal.SIGTERM, signal.SIG_IGN); "
                      "print(os.getpid(), flush=True); signal.pause()")
        code = (f"import subprocess, sys; subprocess.run([sys.executable, '-c', "
                f"{grandchild!r}], check=True)")
        owner = self.start_job(code)
        grandchild_pid = int(self.line(owner.stdout))
        owner.terminate()
        self.assertEqual(owner.wait(timeout=10), 143)
        # An orphan may briefly remain as a zombie until the OS reaps it.
        deadline = time.monotonic() + 5
        while True:
            try:
                process_stat = Path(f"/proc/{grandchild_pid}/stat").read_text()
            except FileNotFoundError:
                break
            if process_stat.split(") ", 1)[1].startswith("Z"):
                break
            self.assertLess(time.monotonic(), deadline, "grandchild survived cancellation")
            time.sleep(0.01)
        result = self.invoke(["--", sys.executable, "-c", "print('next job')"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "next job")

    def test_missing_command_releases_the_lock(self):
        result = self.invoke(["--", "/no-such-safere-command"])
        self.assertEqual(result.returncode, 127, result.stderr)
        self.assertEqual(self.invoke(["--status"]).stdout.strip(), "machine-run: idle")

    def test_cancellation_escalates_if_the_foreground_command_ignores_the_signal(self):
        code = ("import signal; signal.signal(signal.SIGTERM, signal.SIG_IGN); "
                "print('ready', flush=True); signal.pause()")
        owner = self.start_job(code)
        self.assertEqual(self.line(owner.stdout), "ready")
        owner.terminate()
        self.assertEqual(owner.wait(timeout=10), 143)
        self.assertEqual(self.invoke(["--status"]).stdout.strip(), "machine-run: idle")

    def test_entry_points_wait_before_starting_any_work(self):
        entry_points = [
            "collect-benchmark-results.sh", "materialize-benchmark-inputs.sh",
            "run-java-benchmarks.sh", "run-java-memory-benchmarks.sh",
            "run-openjdk-regex-benchmarks.sh", "run-vector-benchmarks.sh",
            "run-cpp-benchmarks.sh", "run-rust-benchmarks.sh",
            "run-go-benchmarks.sh", "run-dotnet-benchmarks.sh",
            "safere-benchmarks/scripts/compare-branch.sh",
            "safere-benchmarks/scripts/evaluate-java-benchmark-configs.py",
            "safere-fuzz/scripts/run-fuzz-test.sh",
        ]
        with self.lock_path.open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            for entry in entry_points:
                with self.subTest(entry=entry):
                    interpreter = sys.executable if entry.endswith(".py") else "/bin/bash"
                    job = self.start([interpreter, str(ROOT / entry)], cwd=ROOT)
                    self.assertIn("waiting", self.line(job.stderr))
                    self.assertFalse(select.select([job.stdout], [], [], 0)[0])
                    job.terminate()
                    self.assertEqual(job.wait(timeout=10), 143)


if __name__ == "__main__":
    unittest.main()
