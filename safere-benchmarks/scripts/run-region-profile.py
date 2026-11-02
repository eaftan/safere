#!/usr/bin/env python3
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the BSD 3-Clause License.
# See LICENSE file in the project root for details.

"""Run a paired String-region history experiment in alternating fresh-fork order."""

import argparse
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("plain_workload")
    parser.add_argument("region_workload")
    parser.add_argument("--long", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    commands = []
    for cycle, histories in enumerate((("plain", "mixed"), ("mixed", "plain")), 1):
        for history in histories:
            prefix = output / f"cycle{cycle}-{history}"
            command = [str(root / "run-java-benchmarks.sh")]
            if args.long:
                command.append("--long")
            command += [
                "--fastbuild", "StringRegionProfileBenchmark.find", "--",
                "-p", f"plainTrial={args.plain_workload}@safere-string",
                "-p", f"regionTrial={args.region_workload}@safere-string",
                "-p", f"history={history}",
                "-jvmArgsAppend", "-Xmx512m",
                "-foe", "true", "-rf", "json", "-rff", str(prefix.with_suffix(".json")),
            ]
            commands.append(command)
            (output / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")
            print(f"Starting cycle {cycle}, history {history}: {prefix}.log", flush=True)
            with prefix.with_suffix(".log").open("w") as log:
                subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
            results = json.loads(prefix.with_suffix(".json").read_text())
            if len(results) != 1 or len(results[0]["primaryMetric"]["rawData"]) != 2:
                raise RuntimeError(f"Expected one successful result with two forks: {prefix}")
            print(f"Finished: {results[0]['primaryMetric']['score']:.2f} ns/op", flush=True)


if __name__ == "__main__":
    main()
