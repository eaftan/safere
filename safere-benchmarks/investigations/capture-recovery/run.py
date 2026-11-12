#!/usr/bin/env python3
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the BSD 3-Clause License.
# See LICENSE file in the project root for details.

"""Run one frozen cutoff snapshot through the shared Java benchmark wrapper."""

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import tarfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--long", action="store_true")
    modes.add_argument("--smoke", action="store_true")
    parser.add_argument("--jfr", action="store_true", help="collect diagnostic JFR profiles")
    parser.add_argument("--family", choices=("stack", "onePass"), default="stack")
    parser.add_argument("--sizes", nargs="+", type=int, default=[511, 512, 513])
    parser.add_argument("--engines", nargs="+", choices=("safere-string", "safere-utf8"),
                        default=["safere-string", "safere-utf8"])
    parser.add_argument("--operations", nargs="+", choices=("matches", "lookingAt", "find"),
                        default=["matches", "lookingAt", "find"])
    parser.add_argument("--profiles", nargs="+", default=["text", "mixed", "rare", "reject"])
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    data_path = Path(__file__).resolve().with_name("benchmark-data.json")
    data = json.loads(data_path.read_text())
    known_sizes = set(data["workloads"][0]["axes"]["size"])
    known_profiles = data["configuration"]["investigation"]["profiles"]
    if not set(args.sizes) <= known_sizes or not set(args.profiles) <= known_profiles.keys():
        parser.error("unknown corpus size or profile")
    for values in (args.sizes, args.engines, args.operations, args.profiles):
        if len(set(values)) != len(values):
            parser.error("duplicate selections are not allowed")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    trials = [f"CaptureCutoffBenchmark.{args.family}.{size}@{engine}"
              for size in args.sizes for engine in args.engines]
    command = [str(root / "run-java-benchmarks.sh")]
    if args.long:
        command.append("--long")
    elif args.smoke:
        command.append("--smoke")
    command += ["--fastbuild", "--investigation", "capture-recovery",
                r"^org\.safere\.benchmark\.CaptureCutoffBenchmark\.run$", "--",
                "-p", "cutoffTrial=" + ",".join(trials),
                "-p", "operation=" + ",".join(args.operations),
                "-p", "profile=" + ",".join(args.profiles),
                "-foe", "true", "-rf", "json", "-rff", str(output / "results.json")]
    if args.jfr:
        command += ["-prof", "jfr:dir=" + str(output / "jfr")]
    files = {root / ".mvn/jvm.config", root / "pom.xml", root / "run-java-benchmarks.sh",
             root / "materialize-benchmark-inputs.sh"}
    for module in ("safere", "safere-crosscheck", "safere-benchmarks", "safere-ffm-re2"):
        files.add(root / module / "pom.xml")
        files.update(p for p in (root / module / "src/main").rglob("*") if p.is_file())
    files.update(p for p in data_path.parent.rglob("*")
                 if p.is_file() and "__pycache__" not in p.parts)
    hashes = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
              for p in sorted(files)}
    with tarfile.open(output / "sources.tar.gz", "w:gz") as archive:
        for path in sorted(files):
            archive.add(path, arcname=str(path.relative_to(root)))
    matcher = (root / "safere/src/main/java/org/safere/Matcher.java").read_text()
    limit = int(re.search(r"CAPTURE_DEMAND_TEXT_LIMIT = (\d+);", matcher).group(1))
    revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, capture_output=True,
                              text=True)
    manifest = {"cwd": str(root), "argv": command,
                "mode": "long" if args.long else "smoke" if args.smoke else "standard",
                "baseRevision": revision.stdout.strip() if revision.returncode == 0 else None,
                "eligibilityLimit": limit, "sourceSha256": hashes,
                "profiler": "jfr" if args.jfr else None,
                "trials": trials, "operations": args.operations, "profiles": args.profiles,
                "expectedRows": len(trials) * len(args.operations) * len(args.profiles)}
    audit_command = ["java", "-Dsafere.benchmark.corpus=" + str(
        root / "safere-benchmarks/target/investigations/capture-recovery/corpus"),
        "-cp", str(root / "safere-benchmarks/target/investigations/capture-recovery/build/benchmarks.jar"),
        "org.safere.benchmark.CaptureCutoffPaths", ",".join(trials),
        ",".join(args.operations), ",".join(args.profiles)]
    manifest["pathArgv"] = audit_command
    (output / "command.json").write_text(json.dumps(manifest, indent=2) + "\n")
    with (output / "run.log").open("w") as log:
        result = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT)
    (output / "exit.txt").write_text(str(result.returncode) + "\n")
    if result.returncode:
        raise SystemExit(result.returncode)
    rows = json.loads((output / "results.json").read_text())
    actual = {(row["params"]["cutoffTrial"], row["params"]["operation"],
               row["params"]["profile"]) for row in rows}
    expected = {(trial, operation, profile) for trial in trials
                for operation in args.operations for profile in args.profiles}
    if any(not math.isfinite(row["primaryMetric"]["score"])
           or row["primaryMetric"]["score"] <= 0
           or row["primaryMetric"]["scoreUnit"] != "ns/op" for row in rows):
        raise SystemExit("Invalid cutoff measurements")
    if len(rows) != len(expected) or actual != expected:
        raise SystemExit("Incomplete or duplicate cutoff results")
    with (output / "paths.json").open("w") as paths, (output / "paths.log").open("w") as log:
        audit = subprocess.run(audit_command, cwd=root, stdout=paths, stderr=log)
    (output / "paths-exit.txt").write_text(str(audit.returncode) + "\n")
    if audit.returncode:
        raise SystemExit(audit.returncode)
    observed = json.loads((output / "paths.json").read_text())
    if len(observed) != len(expected):
        raise SystemExit("Incomplete path audit")
    print(f"Completed {len(rows)} rows and path audit; evidence: {output}")


if __name__ == "__main__":
    main()
