#!/usr/bin/env python3
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the BSD 3-Clause License.
# See LICENSE file in the project root for details.

"""Audit deterministic state traces and summarize phase-level producer choices and work."""

import argparse
import csv
import json
from pathlib import Path
import re


NAME = re.compile(r"(?P<scenario>.+\.ascii\.128)-(?P<input>string|utf8)-(?P<policy>[^-]+)-(?P<seed>[0-9]+)\.tsv")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("traces", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    checksums = {}
    summaries = []
    checkpoints = []
    for path in sorted(args.traces.glob("*.tsv")):
        identity = NAME.fullmatch(path.name)
        if identity is None:
            raise ValueError("Unknown trace identity: " + path.name)
        metadata = identity.groupdict()
        with path.open() as source:
            rows = [{key: value if key in ("mode", "representation") else int(value)
                     for key, value in row.items()} for row in csv.DictReader(source, delimiter="\t")]
        previous = {key: 0 for key in ("choices", "captureChoices", "work", "userReplayWork", "replays", "rejections", "findCalls")}
        previous_end = previous.copy()
        phases = set()
        for row in rows:
            assert row["mode"] == metadata["policy"]
            assert row["representation"] == metadata["input"]
            assert 0 <= row["reads"] <= row["successes"] <= 32
            assert 0 <= row["hits"] <= row["attempts"] <= 32
            assert -32 <= row["credit"] <= 32
            assert 0 <= row["dfaMean"] <= 1 << 20
            assert 0 <= row["captureMean"] <= 1 << 20
            assert 0 <= row["dfaSamples"] <= 4096
            assert 0 <= row["captureSamples"] <= 4096
            assert 0 <= row["captureChoices"] <= row["choices"] <= row["findCalls"]
            assert 0 <= row["userReplayWork"] <= row["work"]
            for key in previous:
                assert row[key] >= previous[key], (path, key)
                previous[key] = row[key]
            choices = row["choices"] - previous_end["choices"]
            capture = row["captureChoices"] - previous_end["captureChoices"]
            checkpoints.append({**metadata, "phase": row["phase"], "document": row["document"],
                "captureChoiceFraction": capture / choices if choices else None,
                "workPerDocument": (row["work"] - previous_end["work"]) / row["document"],
                "recentReads": row["reads"], "recentSuccesses": row["successes"],
                "dfaMean": row["dfaMean"], "captureMean": row["captureMean"]})
            if row["document"] == 512:
                phase = row["phase"]
                assert phase not in phases
                phases.add(phase)
                key = metadata["scenario"], phase
                if key in checksums:
                    assert checksums[key] == row["checksum"], (path, phase, "semantic mismatch")
                checksums[key] = row["checksum"]
                summaries.append({**metadata, "phase": phase, "documents": 512,
                    "captureChoices": capture, "choices": choices,
                    "captureChoiceFraction": capture / choices if choices else None,
                    "workPerDocument": (row["work"] - previous_end["work"]) / 512,
                    "userReplays": row["replays"] - previous_end["replays"],
                    "userReplayWorkPerDocument": (row["userReplayWork"] - previous_end["userReplayWork"]) / 512,
                    "meaningfulRejections": row["rejections"] - previous_end["rejections"]})
                previous_end = {key: row[key] for key in previous_end}
        assert phases == {0, 1, 2}
    assert summaries, "No traces"
    (args.output / "trace-summary.json").write_text(json.dumps(summaries, indent=2) + "\n")
    (args.output / "trace-checkpoints.json").write_text(json.dumps(checkpoints, indent=2) + "\n")
    lines = ["# Deterministic work and state traces", "",
        "Extra work accounting is enabled in all modes. These are work proxies, not timing measurements.", "",
        "| Scenario | Input | Policy | Seed | Phase | Capture choices / decisions | Work units/doc | User-triggered replays |",
        "|---|---|---|---:|---:|---:|---:|---:|"]
    for row in summaries:
        lines.append(f'| {row["scenario"]} | {row["input"]} | {row["policy"]} | {row["seed"]} | {row["phase"]} | '
            f'{row["captureChoices"]}/{row["choices"]} | {row["workPerDocument"]:.2f} | {row["userReplays"]} |')
    (args.output / "trace-tables.md").write_text("\n".join(lines) + "\n")
    print(f"Audited {len(summaries) // 3} traces with identical cross-policy/cross-representation checksums")


if __name__ == "__main__":
    main()
