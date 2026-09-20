#!/usr/bin/env python3
"""Reproduce pairwise SafeRE ratios from the archived Rebar CSV."""

import argparse
import csv
import math
import re
from pathlib import Path


TIME_UNITS_NS = {"ns": 1, "us": 1_000, "ms": 1_000_000, "s": 1_000_000_000}
TIME_RE = re.compile(r"([0-9]+(?:\.[0-9]+)?)(ns|us|ms|s)")


def nanoseconds(value):
    match = TIME_RE.fullmatch(value)
    if match is None:
        raise ValueError(f"unrecognized Rebar duration: {value!r}")
    return float(match.group(1)) * TIME_UNITS_NS[match.group(2)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "csv_file",
        nargs="?",
        type=Path,
        default=Path(__file__).with_name("measurements.csv"),
    )
    parser.add_argument(
        "--exclude",
        action="append",
        default=[],
        metavar="WORKLOAD",
        help="omit an exact Rebar workload name for a sensitivity check",
    )
    args = parser.parse_args()

    with args.csv_file.open(newline="", encoding="utf-8") as source:
        rows = list(csv.DictReader(source))

    measurements = {}
    for row in rows:
        if row["err"]:
            raise ValueError(f"failed measurement: {row['name']} / {row['engine']}")
        key = (row["name"], row["model"], row["engine"])
        if key in measurements:
            raise ValueError(f"duplicate measurement: {key}")
        measurements[key] = nanoseconds(row["median"])

    engines = sorted({row["engine"] for row in rows})
    for target in ("safere/string", "safere/utf8"):
        print(f"\n{target} median time / competitor median time")
        print("| Competitor | Shared workloads | Geomean ratio | SafeRE wins |")
        print("|---|---:|---:|---:|")
        comparisons = []
        for competitor in engines:
            if competitor == target:
                continue
            ratios = [
                duration / measurements[(name, model, competitor)]
                for (name, model, engine), duration in measurements.items()
                if engine == target
                and name not in args.exclude
                and (name, model, competitor) in measurements
            ]
            if not ratios:
                continue
            geomean = math.exp(sum(map(math.log, ratios)) / len(ratios))
            wins = sum(ratio < 1 for ratio in ratios)
            comparisons.append((geomean, competitor, len(ratios), wins))
        for geomean, competitor, count, wins in sorted(comparisons):
            print(f"| {competitor} | {count} | {geomean:.3f} | {wins}/{count} |")


if __name__ == "__main__":
    main()
