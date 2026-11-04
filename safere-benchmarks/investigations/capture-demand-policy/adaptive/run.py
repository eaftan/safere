#!/usr/bin/env python3
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the BSD 3-Clause License.
# See LICENSE file in the project root for details.

"""Collect one explicitly selected adaptive-policy stage using the shared wrapper."""

import argparse
import hashlib
import itertools
import json
import os
from pathlib import Path
import subprocess
import zipfile


CORE = [f"{family}.{name}.ascii.128" for family in ("stack", "scheme")
        for name in ("all.text", "rare.text", "all.text4", "all.boolean",
                     "rare.rotate", "half.shuffle", "reads.phase")]
CORE += ["stack.document", "scheme.document"]
TRANSITIONS = [f"{family}.{name}.ascii.128" for family in ("stack", "scheme")
               for name in ("reads.phase", "hits.phase")]
SHARED = [f"{family}.{name}.ascii.128" for family in ("stack", "scheme")
          for name in ("all.text", "rare.rotate")]
CONFIRM = [f"{family}.{name}.ascii.128" for family in ("stack", "scheme")
           for name in ("all.text", "rare.text", "rare.rotate", "reads.phase")]
CONFIRM += ["stack.document", "scheme.document"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--stage", choices=("core", "robustness", "shared", "phases", "latency", "seeds", "long"), required=True)
    parser.add_argument("--policies", nargs="+", required=True)
    parser.add_argument("--seeds", nargs="+", type=int, default=[1])
    parser.add_argument("--scenarios", nargs="+")
    parser.add_argument("--representations", nargs="+", choices=("string", "utf8"), default=["string", "utf8"])
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--smoke", action="store_true")
    mode.add_argument("--long", action="store_true")
    args = parser.parse_args()
    if args.smoke and (len(args.policies) != 1 or len(args.seeds) != 1):
        parser.error("No-fork smoke checks require one policy and seed per process")
    checkout = args.checkout.resolve()
    study = checkout / "safere-benchmarks/investigations/capture-demand-policy"
    data = json.loads((study / "benchmark-data.json").read_text())
    config = data["configuration"]["investigation"]["adaptive"]
    if not set(args.policies) <= set(config["policies"]):
        parser.error("Unknown experimental policy")
    defaults = {"core": CORE, "robustness": [s for s in config["scenarios"] if s not in CORE],
                "shared": SHARED, "phases": TRANSITIONS,
                "latency": ["scheme.all.text.ascii.128", "scheme.rare.rotate.ascii.128", "stack.all.text.ascii.128", "stack.reads.phase.ascii.128"], "seeds": TRANSITIONS + ["scheme.rare.rotate.ascii.128"],
                "long": CONFIRM}
    scenarios = args.scenarios or defaults[args.stage]
    if not set(scenarios) <= set(config["scenarios"]):
        parser.error("Unknown scenario")
    for selection in (scenarios, args.policies, args.seeds, args.representations):
        if len(selection) != len(set(selection)):
            parser.error("Repeated selection")
    baseline = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=checkout, text=True).strip()
    expected_baseline = "0e6c0069424f9e8e1bef1eb602d6fab41c706c16"
    if baseline != expected_baseline:
        parser.error("Use the pinned main baseline and the recorded benchmark overlay")
    matcher = Path("safere/src/main/java/org/safere/Matcher.java")
    original = subprocess.check_output(["git", "show", "HEAD:" + str(matcher)], cwd=checkout)
    unchanged = ((checkout / matcher).read_bytes() == original
                 and subprocess.run(["git", "diff", "--quiet", "HEAD", "--", "safere/src/main"], cwd=checkout).returncode == 0
                 and not (checkout / "safere/src/main/java/org/safere/CapturePolicyExperiment.java").exists())
    if ("main" in args.policies) != unchanged:
        parser.error("Unmodified main must be collected separately from the experimental patch")
    bare_control = None
    helper = checkout / "safere/src/main/java/org/safere/CapturePolicyExperiment.java"
    if not unchanged and not helper.exists():
        if len(args.policies) != 1:
            parser.error("Collect minimal controls separately")
        manifest = json.loads((Path(__file__).resolve().parent / "prototype/manifest.json").read_text())
        mode = args.policies[0]
        control = manifest["controls"].get(mode)
        if not control or hashlib.sha256((checkout / matcher).read_bytes()).hexdigest() != control["matcherSha256"]:
            parser.error("Unexpected minimal-control Matcher source")
        for path, hashes in manifest["trackedFiles"].items():
            if path != str(matcher) and hashlib.sha256((checkout / path).read_bytes()).hexdigest() != hashes["baseline"]:
                parser.error("Minimal control must retain other main sources: " + path)
        bare_control = mode
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    source_files = list((checkout / "safere/src/main").rglob("*.java"))
    source_files += list((study / "src").rglob("*.java"))
    source_files += [study / "benchmark-data.json", checkout / "run-java-benchmarks.sh",
                     checkout / "safere-benchmarks/pom.xml", checkout / "safere/pom.xml"]
    hashes = {str(p.relative_to(checkout)): hashlib.sha256(p.read_bytes()).hexdigest()
              for p in sorted(source_files)}
    harness_revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=Path(__file__).resolve().parent, text=True).strip()
    metadata = {"investigationRevision": harness_revision,
                "controllerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                "libraryBase": baseline, "unmodifiedMain": unchanged, "bareControl": bare_control, "stage": args.stage,
                "policies": args.policies, "scenarios": scenarios, "seeds": args.seeds,
                "representations": args.representations, "sourceSha256": hashes}
    (output / "sources.json").write_text(json.dumps(metadata, indent=2) + "\n")
    overlay = subprocess.check_output(["git", "diff", "--binary", "HEAD"], cwd=checkout)
    untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"], cwd=checkout, text=True).splitlines()
    for path in untracked:
        if path.startswith(("safere/src/main/", "safere/src/test/", "safere-benchmarks/investigations/")):
            patch = subprocess.run(["git", "diff", "--no-index", "--binary", "--", "/dev/null", path], cwd=checkout, capture_output=True)
            if patch.returncode not in (0, 1):
                raise RuntimeError("Could not preserve source overlay: " + path)
            overlay += patch.stdout
    (output / "overlay.patch").write_bytes(overlay)
    benchmark = {"shared": "AdaptiveCaptureSharedBenchmark.run",
                 "phases": "AdaptiveCapturePhasesBenchmark.phases",
                 "latency": "AdaptiveCaptureLatencyBenchmark.latency"}.get(args.stage, "AdaptiveCaptureBenchmark.run")
    command = [str(checkout / "run-java-benchmarks.sh")]
    if args.smoke:
        command.append("--smoke")
    elif args.long or args.stage == "long":
        command.append("--long")
    command += ["--fastbuild", "--investigation", "capture-demand-policy",
                "^org\\.safere\\.benchmark\\." + benchmark.replace(".", "\\.") + "$", "--",
                "-p", "scenario=" + ",".join(scenarios),
                "-p", "representation=" + ",".join(args.representations),
                "-p", "policy=" + ",".join(args.policies),
                "-p", "seed=" + ",".join(map(str, args.seeds)),
                "-t", "4" if args.stage == "shared" else "1", "-foe", "true", "-rf", "json",
                "-rff", str(output / "results.json")]
    if bare_control:
        command += ["-jvmArgsAppend", "-Dsafere.experimental.bareControl=" + bare_control]
    (output / "commands.json").write_text(json.dumps([command], indent=2) + "\n")
    environment = None
    if args.smoke and bare_control:
        # JMH's fork arguments do not apply to a no-fork launcher. Preserve the same source-
        # validated marker there without changing the shared wrapper's smoke configuration.
        environment = os.environ.copy()
        marker = "-Dsafere.experimental.bareControl=" + bare_control
        environment["JAVA_TOOL_OPTIONS"] = (environment.get("JAVA_TOOL_OPTIONS", "") + " " + marker).strip()
        metadata["smokeLauncherProperty"] = marker
    with (output / "run.log").open("w") as log:
        result = subprocess.run(command, cwd=checkout, stdout=log, stderr=subprocess.STDOUT, env=environment)
    (output / "exit.txt").write_text(str(result.returncode) + "\n")
    result.check_returncode()
    jar = checkout / "safere-benchmarks/target/investigations/capture-demand-policy/build/benchmarks.jar"
    with zipfile.ZipFile(jar) as archive:
        contains_helper = "org/safere/CapturePolicyExperiment.class" in archive.namelist()
        if contains_helper != (not unchanged and bare_control is None):
            raise RuntimeError("Stale experimental classes: clean the library after switching sources")
        metadata["matcherBytecodeSha256"] = hashlib.sha256(archive.read("org/safere/Matcher.class")).hexdigest()
    metadata["benchmarkJarSha256"] = hashlib.sha256(jar.read_bytes()).hexdigest()
    (output / "sources.json").write_text(json.dumps(metadata, indent=2) + "\n")
    rows = json.loads((output / "results.json").read_text())
    keys = ("policy", "scenario", "representation", "seed")
    identities = [tuple(row["params"][key] for key in keys) for row in rows]
    expected = set(itertools.product(args.policies, scenarios, args.representations, map(str, args.seeds)))
    if set(identities) != expected or len(identities) != len(expected):
        raise RuntimeError("JMH membership differs from the explicit stage selection")
    if any(row["forks"] != (0 if args.smoke else 2) for row in rows):
        raise RuntimeError("Unexpected JMH fork count")
    if any(row["threads"] != (4 if args.stage == "shared" else 1) for row in rows):
        raise RuntimeError("Unexpected JMH worker count")
    if args.stage == "latency" and any(row["mode"] != "sample" for row in rows):
        raise RuntimeError("Expected individual-document SampleTime measurements")
    if args.stage == "phases":
        for row in rows:
            metrics = row["secondaryMetrics"]
            for phase in range(3):
                if metrics[f"phase{phase}Documents"]["score"] <= 0 or metrics[f"phase{phase}Nanos"]["score"] <= 0:
                    raise RuntimeError("Missing phase-diagnostic counters")
    (output / "validated.json").write_text(json.dumps({"rows": len(rows), "membership": "complete", "sourceState": "verified"}) + "\n")
    print(f"Captured {len(rows)} comparable rows under {output}")


if __name__ == "__main__":
    main()
