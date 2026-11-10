#!/usr/bin/env python3
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the BSD 3-Clause License.
# See LICENSE file in the project root for details.

"""Preserve completed investigation evidence, excluding smoke timings and binary profiles."""

import argparse
import json
from pathlib import Path
import shutil


FILES = ("commands.json", "sources.json", "overlay.patch", "results.json", "run.log", "exit.txt", "validated.json")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    for marker in ("collection.exit", "after.exit"):
        if (args.capture / marker).read_text().strip() != "0":
            raise ValueError("Investigation is incomplete: " + marker)
    args.destination.mkdir(parents=True, exist_ok=False)
    cohorts = args.destination / "cohorts"
    cohorts.mkdir()
    inventory = {}
    for source in sorted(args.capture.iterdir()):
        if not source.is_dir() or source.name.startswith("smoke-") or not (source / "sources.json").exists():
            continue
        if (source / "exit.txt").read_text().strip() != "0":
            raise ValueError("Failed cohort: " + source.name)
        validated = json.loads((source / "validated.json").read_text())
        destination = cohorts / source.name
        destination.mkdir()
        for name in FILES:
            shutil.copyfile(source / name, destination / name)
        inventory[source.name] = validated["rows"]
    for name in ("selection.json", "baseline.txt", "collection.exit", "after.exit"):
        shutil.copyfile(args.capture / name, args.destination / name)
    environment = args.destination / "environment"
    environment.mkdir()
    for name in ("cpu.txt", "java-version.txt", "maven-version.txt", "host.txt", "memory.txt"):
        shutil.copyfile(args.capture / name, environment / name)
    shutil.copytree(args.capture / "traces", args.destination / "work-traces")
    profiles = args.destination / "profiles"
    profiles.mkdir()
    for name in ("baseline-profile-summary.txt", "baseline-profile-command.json", "baseline-profile.json", "baseline-profile.log"):
        shutil.copyfile(args.capture / name, profiles / name)
    for name in ("commands.json", "profile-results.json", "run.log", "exit.txt", "summary.txt"):
        shutil.copyfile(args.capture / "candidate-profiles" / name, profiles / ("candidate-" + name))
    (args.destination / "cohort-inventory.json").write_text(json.dumps(inventory, indent=2) + "\n")
    print(f"Preserved {len(inventory)} complete cohorts and {sum(inventory.values())} raw rows")


if __name__ == "__main__":
    main()
