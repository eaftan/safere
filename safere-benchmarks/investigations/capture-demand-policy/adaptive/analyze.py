#!/usr/bin/env python3
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the BSD 3-Clause License.
# See LICENSE file in the project root for details.

"""Audit adaptive-policy cohorts and derive reproducible tables from raw JMH JSON."""

import argparse
import hashlib
import json
import math
from pathlib import Path
import statistics


CANDIDATES = ("fraction", "joint", "replay", "work", "periodic", "random")
POLICIES = ("main", "dfa", "localSticky", *CANDIDATES, "patchedMain", "observe")


def geometric_mean(values):
    return math.exp(math.fsum(math.log(value) for value in values) / len(values))


def table(headers, rows):
    lines = ["| " + " | ".join(headers) + " |", "|" + "|".join(["---"] + ["---:"] * (len(headers) - 1)) + "|"]
    lines += ["| " + " | ".join(map(str, row)) + " |" for row in rows]
    return "\n".join(lines) + "\n"


def read_cohort(path):
    audit = json.loads((path / "validated.json").read_text())
    if (path / "exit.txt").read_text().strip() != "0":
        raise ValueError("Failed cohort: " + path.name)
    source = json.loads((path / "sources.json").read_text())
    commands = json.loads((path / "commands.json").read_text())
    long_mode = "--long" in commands[0]
    rows = json.loads((path / "results.json").read_text())
    expected = {(p, s, r, str(seed)) for p in source["policies"] for s in source["scenarios"]
                for r in source["representations"] for seed in source["seeds"]}
    actual = [(r["params"]["policy"], r["params"]["scenario"], r["params"]["representation"], r["params"]["seed"]) for r in rows]
    if len(actual) != len(set(actual)) or set(actual) != expected or len(rows) != audit["rows"]:
        raise ValueError("Unexpected or duplicate cohort membership: " + path.name)
    for row in rows:
        if row["forks"] != 2:
            raise ValueError("Smoke/profile data cannot enter performance comparisons")
        expected_warmup = (3, "1 s") if long_mode else (2, "500 ms")
        expected_measurement = (5, "1 s") if long_mode else (5, "500 ms")
        if (row["warmupIterations"], row["warmupTime"]) != expected_warmup:
            raise ValueError("Unexpected warmup settings")
        if (row["measurementIterations"], row["measurementTime"]) != expected_measurement:
            raise ValueError("Unexpected measurement settings")
        if row["mode"] != ("sample" if source["stage"] == "latency" else "avgt"):
            raise ValueError("Unexpected benchmark measurement mode")
        metric = row["primaryMetric"]
        if not math.isfinite(metric["score"]) or metric["score"] <= 0:
            raise ValueError("Invalid primary score")
        if row["threads"] != (4 if source["stage"] == "shared" else 1):
            raise ValueError("Unexpected worker count")
    return source, rows


def normalize(cohort, source, row):
    metric = row["primaryMetric"]
    units = {"ns/op": 1, "us/op": 1000, "ms/op": 1_000_000}
    documents = 1 if source["stage"] == "latency" else 1536
    scale = units[metric["scoreUnit"]] / documents
    params = row["params"]
    result = {"cohort": cohort, "stage": source["stage"], "benchmark": row["benchmark"],
              "policy": params["policy"], "scenario": params["scenario"],
              "representation": params["representation"], "seed": int(params["seed"]),
              "mode": row["mode"], "threads": row["threads"],
              "documentsPerInvocation": documents,
              "nsPerDocument": metric["score"] * scale,
              "scoreErrorNsPerDocument": metric["scoreError"] * scale,
              "libraryBase": source["libraryBase"],
              "investigationRevision": source["investigationRevision"],
              "bareControl": source.get("bareControl"),
              "matcherBytecodeSha256": source["matcherBytecodeSha256"]}
    if source["stage"] == "latency":
        result["percentilesNsPerDocument"] = {key: value * scale for key, value in metric["scorePercentiles"].items()}
    if source["stage"] == "phases":
        phases = []
        for phase in range(3):
            nanos = row["secondaryMetrics"][f"phase{phase}Nanos"]
            docs = row["secondaryMetrics"][f"phase{phase}Documents"]
            values = []
            if len(nanos["rawData"]) != len(docs["rawData"]):
                raise ValueError("Phase counter fork mismatch")
            for times, counts in zip(nanos["rawData"], docs["rawData"]):
                if len(times) != len(counts) or any(count <= 0 for count in counts):
                    raise ValueError("Phase counter iteration mismatch")
                values.extend(time / count for time, count in zip(times, counts))
            phases.append({"meanNsPerDocument": statistics.mean(values),
                           "minIterationNsPerDocument": min(values),
                           "maxIterationNsPerDocument": max(values)})
        result["phaseDiagnostics"] = phases
    return result


def key(row):
    return row["scenario"], row["representation"]


def comparison(rows, baseline):
    ratios = [row["nsPerDocument"] / baseline[key(row)]["nsPerDocument"] for row in rows if key(row) in baseline]
    return [len(ratios), f"{geometric_mean(ratios):.3f}", f"{min(ratios):.3f}", f"{max(ratios):.3f}"] if ratios else [0, "–", "–", "–"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    excluded = {"baseline-profile", "validation"}
    cohorts = {}
    source_hashes = None
    normalized = []
    for path in sorted(args.results.iterdir()):
        if not path.is_dir() or path.name.startswith("smoke-") or path.name in excluded or not (path / "sources.json").exists():
            continue
        if not (path / "validated.json").exists():
            raise ValueError("Incomplete performance cohort: " + path.name)
        source, rows = read_cohort(path)
        harness = {key: value for key, value in source["sourceSha256"].items()
                   if key.startswith("safere-benchmarks/") or key == "run-java-benchmarks.sh"}
        if source_hashes is None:
            source_hashes = harness
        elif harness != source_hashes:
            raise ValueError("Benchmark source differs between performance cohorts")
        cohorts[path.name] = source
        normalized += [normalize(path.name, source, row) for row in rows]
    identities = [(r["cohort"], r["policy"], *key(r), r["seed"], r["mode"]) for r in normalized]
    if len(identities) != len(set(identities)):
        raise ValueError("Duplicate normalized identity")
    (args.output / "normalized-results.jsonl").write_text("".join(json.dumps(row, sort_keys=True, allow_nan=False) + "\n" for row in normalized))
    main_standard = {key(r): r for r in normalized if r["cohort"] == "main-standard"}
    main_long_rows = [r for r in normalized if r["policy"] == "main" and r["stage"] == "long"]
    main_long = {key(r): r for r in main_long_rows}
    if len(main_long) != len(main_long_rows):
        raise ValueError("Duplicate main long-mode comparisons")
    main_shared = {key(r): r for r in normalized if r["cohort"] == "main-shared"}
    core_rows = [r for r in normalized if r["cohort"] in ("candidates-core", "localSticky-core")]
    core_keys = {key(r) for r in core_rows}
    core_rows += [r for r in normalized if r["cohort"] in ("main-standard", "dfa-standard") and key(r) in core_keys]
    text = ["# Derived adaptive-policy tables", "", "Ratios are candidate time / main time. Geometric means weight each exact row equally.", "",
            "## Common core screen", "", table(["Policy", "Rows", "Geomean", "Minimum", "Maximum"],
                [[policy, *comparison([r for r in core_rows if r["policy"] == policy], main_standard)] for policy in POLICIES])]
    for representation in ("string", "utf8"):
        text += [f"## Core screen: {representation}", "", table(["Policy", "Rows", "Geomean", "Minimum", "Maximum"],
            [[policy, *comparison([r for r in core_rows if r["policy"] == policy and r["representation"] == representation], main_standard)] for policy in POLICIES])]
    scenarios = sorted({r["scenario"] for r in core_rows})
    text += ["## Per-scenario standard ratios", ""]
    for representation in ("string", "utf8"):
        rows = []
        for scenario in scenarios:
            values = []
            for policy in POLICIES[1:]:
                chosen = [r for r in core_rows if r["policy"] == policy and key(r) == (scenario, representation)]
                values.append(f'{chosen[0]["nsPerDocument"] / main_standard[(scenario, representation)]["nsPerDocument"]:.3f}' if chosen else "–")
            rows.append([scenario, *values])
        text += [f"### {representation}", "", table(["Scenario", *POLICIES[1:]], rows)]
    text += ["## Long confirmations, µs/document", ""]
    long_rows = [r for r in normalized if r["stage"] == "long"]
    long_policies = [p for p in POLICIES if any(r["policy"] == p for r in long_rows)]
    rows = []
    for identity in sorted(main_long):
        cells = []
        for policy in long_policies:
            chosen = [r for r in long_rows if r["policy"] == policy and key(r) == identity]
            if len(chosen) > 1:
                raise ValueError("Repeated long-mode measurement")
            cells.append(f'{chosen[0]["nsPerDocument"] / 1000:.4f} ± {chosen[0]["scoreErrorNsPerDocument"] / 1000:.4f}' if chosen else "–")
        rows.append([" / ".join(identity), *cells])
    text.append(table(["Scenario / representation", *long_policies], rows))
    for stage in ("shared", "phases", "latency", "seeds", "robustness"):
        chosen = [r for r in normalized if r["stage"] == stage]
        if not chosen:
            continue
        text += [f"## {stage}", ""]
        rows = []
        for row in sorted(chosen, key=lambda r: (*key(r), r["policy"], r["seed"])):
            values = [row["scenario"], row["representation"], row["policy"], row["seed"]]
            if stage == "phases":
                values += [f'{p["meanNsPerDocument"] / 1000:.4f}' for p in row["phaseDiagnostics"]]
            elif stage == "latency":
                values += [f'{row["nsPerDocument"] / 1000:.4f}']
                values += [f'{row["percentilesNsPerDocument"][p] / 1000:.4f}' for p in ("50.0", "95.0", "99.0")]
            else:
                reference = main_shared if stage == "shared" else main_standard
                values += [f'{row["nsPerDocument"] / 1000:.4f}', f'{row["nsPerDocument"] / reference[key(row)]["nsPerDocument"]:.3f}']
            rows.append(values)
        headings = ["Scenario", "Input", "Policy", "Seed"]
        headings += ["Phase 0 µs/doc", "Phase 1 µs/doc", "Phase 2 µs/doc"] if stage == "phases" else ["Mean µs/doc", "p50", "p95", "p99"] if stage == "latency" else ["µs/doc", "Time/main"]
        text.append(table(headings, rows))
    (args.output / "summary.txt").write_text("\n".join(text))
    inventory = {"cohorts": {name: {"stage": value["stage"], "policies": value["policies"],
                  "rows": sum(1 for r in normalized if r["cohort"] == name)} for name, value in cohorts.items()},
                 "normalizedRows": len(normalized), "benchmarkSourceSha256": source_hashes,
                 "rawSha256": {str(p.relative_to(args.results)): hashlib.sha256(p.read_bytes()).hexdigest()
                                for p in sorted(args.results.glob("*/results.json")) if not p.parent.name.startswith("smoke-")}}
    (args.output / "inventory.json").write_text(json.dumps(inventory, indent=2) + "\n")
    print(f"Audited {len(cohorts)} cohorts and {len(normalized)} unique rows")


if __name__ == "__main__":
    main()
