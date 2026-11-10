#!/usr/bin/env python3
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the BSD 3-Clause License.
# See LICENSE file in the project root for details.

"""Capture an explicitly selected capture-demand investigation into a fresh directory."""

import argparse
import json
from pathlib import Path
import subprocess


def main():
    study = Path(__file__).resolve().parent
    definition = json.loads((study / "benchmark-data.json").read_text())
    usages = definition["configuration"]["investigation"]["usages"]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("trials", nargs="+")
    parser.add_argument("--consumption", nargs="+", choices=usages, default=["text1"])
    parser.add_argument("--shared", action="store_true", help="Use four threads split between reader roles")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--long", action="store_true")
    mode.add_argument("--smoke", action="store_true", help="Execution check, not performance evidence")
    args = parser.parse_args()
    if len(set(args.trials)) != len(args.trials) or len(set(args.consumption)) != len(args.consumption):
        parser.error("Select each trial and consumption mode once")
    root = study.parents[2]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    benchmark = "CapturePolicySharedBenchmark" if args.shared else "CapturePolicyBenchmark"
    command = [str(root / "run-java-benchmarks.sh")]
    if args.long:
        command.append("--long")
    elif args.smoke:
        command.append("--smoke")
    command += ["--fastbuild", "--investigation", "capture-demand-policy", benchmark + ".run", "--",
                "-p", "captureTrial=" + ",".join(args.trials),
                "-p", "consumption=" + ",".join(args.consumption),
                "-t", "4" if args.shared else "1", "-foe", "true", "-rf", "json",
                "-rff", str(output / "results.json")]
    (output / "commands.json").write_text(json.dumps([command], indent=2) + "\n")
    with (output / "run.log").open("w") as log:
        subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
    results = json.loads((output / "results.json").read_text())
    identities = {(row["params"]["captureTrial"], row["params"]["consumption"]) for row in results}
    expected = {(trial, usage) for trial in args.trials for usage in args.consumption}
    if identities != expected or len(results) != len(expected):
        raise RuntimeError("JMH result membership differs from the explicit selection")
    if any(row["forks"] != (0 if args.smoke else 2) for row in results):
        raise RuntimeError("Unexpected fork mode")
    print(f"Captured investigation under {output}")


if __name__ == "__main__":
    main()
