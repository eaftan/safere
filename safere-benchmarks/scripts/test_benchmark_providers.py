#!/usr/bin/env python3
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.

"""Verify provider isolation and fork settings in the collection wrappers."""

import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]

JAVA_STUB = '''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
record = {"args": args}
for arg in args:
    if arg.startswith("@"):
        record["argument_file"] = pathlib.Path(arg[1:]).read_text()
with open(os.environ["JAVA_CALLS"], "a") as output:
    output.write(json.dumps(record) + "\\n")
if "org.safere.benchmark.BenchmarkProviderCheck" in args:
    if os.environ.get("FAIL_VECTOR") and any("vectorScanProvider=vector" in a for a in args):
        print("Could not enable the experimental Vector UTF-8 scanner", file=sys.stderr)
        sys.exit(1)
if "org.safere.benchmark.CrossEngineBenchmarkPlan" in args and "cold-start" in args:
    print("No cross-engine trials for cold-start", file=sys.stderr)
    sys.exit(1)
if "org.safere.benchmark.BenchmarkCollectionPlan" in args and "runner-arguments" in args:
    print('-jar\\n"benchmark.jar"')
elif "org.safere.benchmark.BenchmarkCollectionPlan" in args:
    allocation = "allocation-execution-runners" in args
    profiles = ["standard"] if allocation else ["standard", "noFork", "coldStart"]
    for profile in profiles:
        for provider in ["default", "vector"]:
            variant = "safere-utf8-vector" if provider == "vector" else "safere-utf8"
            print(f"{profile}\\tExampleBenchmark.{profile}\\ttrial\\tExample.find@{variant}\\t{provider}")
'''


class BenchmarkProviderRunnerTest(unittest.TestCase):
    def run_wrapper(self, wrapper, fail_vector=False, arguments=None):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            shutil.copyfile(ROOT / wrapper, root / wrapper)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            for name, contents in {
                "java": JAVA_STUB,
                "mvn": "#!/bin/bash\nexit 0\n",
            }.items():
                path = bin_dir / name
                path.write_text(contents)
                path.chmod(0o755)
            materializer = root / "materialize-benchmark-inputs.sh"
            materializer.write_text("#!/bin/bash\nexit 0\n")
            materializer.chmod(0o755)
            calls_file = root / "calls.jsonl"
            env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ["PATH"],
                       JAVA_CALLS=str(calls_file))
            if fail_vector:
                env["FAIL_VECTOR"] = "1"
            result = subprocess.run(["bash", str(root / wrapper)] +
                                    (arguments or ["--smoke", "--declared"]),
                                    env=env, capture_output=True, text=True, check=False)
            calls = ([json.loads(line) for line in calls_file.read_text().splitlines()]
                     if calls_file.exists() else [])
            measurements = [call for call in calls if "-jvmArgs" in call["args"]]
            return result, measurements

    def test_timing_and_allocation_launchers_isolate_and_forward_providers(self):
        for wrapper in ["run-java-benchmarks.sh", "run-java-memory-benchmarks.sh"]:
            with self.subTest(wrapper=wrapper):
                result, measurements = self.run_wrapper(wrapper)
                self.assertEqual(result.returncode, 0, result.stderr)
                expected = 2 if "memory" in wrapper else 6
                self.assertEqual(len(measurements), expected)
                for measurement in measurements:
                    args = measurement["args"]
                    fork_args = args[args.index("-jvmArgs") + 1]
                    vector = "vectorScanProvider=vector" in fork_args
                    provider = "vector" if vector else "default"
                    self.assertIn("-Dsafere.benchmark.scanProvider=" + provider, args)
                    self.assertEqual(any(a.startswith("-Dorg.safere.experimental.vectorScanProvider=")
                                         for a in args), vector)
                    self.assertEqual("-Dorg.safere.experimental.vectorScanProvider=" in fork_args,
                                     vector)
                    self.assertEqual("--add-modules=jdk.incubator.vector" in fork_args, vector)
                    self.assertEqual("--add-modules=jdk.incubator.vector" in args, vector)
                    parameters = measurement.get("argument_file", " ".join(args))
                    self.assertEqual("@safere-utf8-vector" in parameters, vector)
                    if "noFork" in args[-1]:
                        self.assertEqual(args[args.index("-f") + 1], "0")

    def test_focused_vector_run_does_not_require_cold_start_trials(self):
        result, measurements = self.run_wrapper("run-java-benchmarks.sh", arguments=[
            "--smoke", "--provider", "vector", "ExampleBenchmark.standard", "--",
            "-p", "crossEngineTrial=Example.find@safere-utf8-vector"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(measurements), 1)
        args = measurements[0]["args"]
        self.assertIn("vectorScanProvider=vector", args[args.index("-jvmArgs") + 1])

    def test_declared_runs_reject_explicit_trial_parameters(self):
        for wrapper in ["run-java-benchmarks.sh", "run-java-memory-benchmarks.sh"]:
            for variant in ["safere-utf8", "safere-utf8-vector"]:
                for override in [["-p", "crossEngineTrial=Example.find@" + variant],
                                 ["-p=crossEngineTrial=Example.find@" + variant]]:
                    with self.subTest(wrapper=wrapper, override=override):
                        result, measurements = self.run_wrapper(wrapper, arguments=[
                            "--smoke", "--declared", "ExampleBenchmark.standard", "--"] + override)
                        self.assertEqual(result.returncode, 2)
                        self.assertIn("--declared cannot be combined with JMH -p", result.stderr)
                        self.assertEqual(measurements, [])

    def test_unavailable_vector_provider_stops_before_vector_measurement(self):
        for wrapper in ["run-java-benchmarks.sh", "run-java-memory-benchmarks.sh"]:
            with self.subTest(wrapper=wrapper):
                result, measurements = self.run_wrapper(wrapper, fail_vector=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Could not enable", result.stderr)
                self.assertTrue(all("vectorScanProvider=vector" not in
                                    call["args"][call["args"].index("-jvmArgs") + 1]
                                    for call in measurements))


if __name__ == "__main__":
    unittest.main()
