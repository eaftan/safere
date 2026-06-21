#!/usr/bin/env python3

# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.

"""Evaluate JMH configuration stability for SafeRE Java benchmarks.

This script answers: what is the cheapest JMH configuration that gives stable
enough results for engineering decisions?

It runs selected Java/JMH benchmarks repeatedly under named candidate
configurations, stores JMH JSON outputs, and writes a Markdown report with:

  * wall-clock time
  * per-result coefficient of variation across independent invocations
  * SafeRE/competitor ratio coefficient of variation
  * winner flips outside a configurable deadband

The repeated invocations are intentionally process-level repeats. JMH's
internal samples are useful, but this script measures the question we care
about during development: "if I rerun this command, does the conclusion hold?"
"""

from __future__ import annotations

import argparse
import collections
import json
import math
import os
import re
import shutil
import statistics
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
BENCHMARK_JAR = REPO_ROOT / "safere-benchmarks" / "target" / "benchmarks.jar"
RE2_SHIM_DIR = REPO_ROOT / "safere-ffm-re2" / "build"

JVM_ARGS = [
    "--enable-native-access=ALL-UNNAMED",
    f"-Dre2shim.library.path={RE2_SHIM_DIR}",
]

ENGINE_SUFFIXES = collections.OrderedDict(
    [
        ("_safere", "safere"),
        ("_jdk", "jdk"),
        ("_re2ffm", "re2ffm"),
        ("_re2j", "re2j"),
    ]
)
COMPETITORS = ("jdk", "re2j", "re2ffm")


@dataclass(frozen=True)
class Config:
    name: str
    jmh_args: tuple[str, ...]


@dataclass(frozen=True)
class Task:
    name: str
    pattern: str
    params: tuple[tuple[str, str], ...] = ()


CONFIGS = {
    "A0": Config("A0", ("-f", "0", "-wi", "0", "-i", "1", "-r", "100ms")),
    "A1": Config("A1", ("-f", "0", "-wi", "1", "-w", "100ms", "-i", "1", "-r", "100ms")),
    "A2": Config("A2", ("-f", "1", "-wi", "0", "-i", "1", "-r", "100ms")),
    "B": Config("B", ("-f", "1", "-wi", "1", "-w", "200ms", "-i", "3", "-r", "200ms")),
    "C": Config("C", ("-f", "1", "-wi", "2", "-w", "500ms", "-i", "5", "-r", "500ms")),
    "D": Config("D", ("-f", "1", "-wi", "3", "-w", "1", "-i", "5", "-r", "1")),
    "E": Config("E", ("-f", "2", "-wi", "2", "-w", "500ms", "-i", "5", "-r", "500ms")),
    "F": Config("F", ("-f", "2", "-wi", "3", "-w", "1", "-i", "5", "-r", "1")),
    "P": Config("P", ("-f", "3", "-wi", "3", "-w", "5", "-i", "5", "-r", "5")),
}

CALIBRATION_TASKS = [
    Task("literalMatch", "RegexBenchmark.literalMatch_"),
    Task("emailFind", "RegexBenchmark.emailFind_"),
    Task("urlExtraction", "ApplicationBenchmark.urlExtraction_"),
    Task("pigLatinReplaceAll", "ReplaceBenchmark.pigLatinReplaceAll_"),
    Task("tagFind128", "Issue481ScalingBenchmark.tagFind_", (("textSize", "128"),)),
    Task("tagFind1MiB", "Issue481ScalingBenchmark.tagFind_", (("textSize", "1048576"),)),
    Task("schemeExtract128", "Issue481ScalingBenchmark.schemeExtract_", (("textSize", "128"),)),
    Task("schemeExtract1MiB", "Issue481ScalingBenchmark.schemeExtract_", (("textSize", "1048576"),)),
    Task("splitWords10KiB", "Issue481ScalingBenchmark.splitWords_", (("textSize", "10240"),)),
    Task("searchEasyFail100KiB", "SearchScalingBenchmark.searchEasyFail_", (("textSize", "102400"),)),
]


def run_command(cmd: list[str], cwd: Path, log_path: Path | None = None) -> None:
    with subprocess.Popen(
        cmd,
        cwd=cwd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    ) as proc:
        fh = log_path.open("w") if log_path else None
        try:
          for line in proc.stdout or []:
              print(line, end="")
              if fh:
                  fh.write(line)
        finally:
            if fh:
                fh.close()
        if proc.wait() != 0:
            raise subprocess.CalledProcessError(proc.returncode, cmd)


def build_benchmarks() -> None:
    run_command(["mvn", "install", "-DskipTests", "-q", "-f", str(REPO_ROOT / "pom.xml")], REPO_ROOT)


def selected_configs(names: str) -> list[Config]:
    result = []
    for name in [part.strip() for part in names.split(",") if part.strip()]:
        if name not in CONFIGS:
            raise SystemExit(f"Unknown config {name}. Known configs: {', '.join(CONFIGS)}")
        result.append(CONFIGS[name])
    return result


def selected_tasks(names: str) -> list[Task]:
    if names == "calibration":
        return CALIBRATION_TASKS
    wanted = {part.strip() for part in names.split(",") if part.strip()}
    tasks = [task for task in CALIBRATION_TASKS if task.name in wanted]
    missing = wanted - {task.name for task in tasks}
    if missing:
        raise SystemExit(
            f"Unknown task(s): {', '.join(sorted(missing))}. "
            f"Known tasks: {', '.join(task.name for task in CALIBRATION_TASKS)}"
        )
    return tasks


def run_jmh(config: Config, task: Task, repeat: int, output_dir: Path) -> float:
    json_path = output_dir / f"{config.name}-{task.name}-r{repeat}.json"
    log_path = output_dir / f"{config.name}-{task.name}-r{repeat}.log"
    cmd = [
        "java",
        *JVM_ARGS,
        "-jar",
        str(BENCHMARK_JAR),
        "-jvmArgs",
        " ".join(JVM_ARGS),
        *config.jmh_args,
        "-foe",
        "true",
        "-rf",
        "json",
        "-rff",
        str(json_path),
        task.pattern,
    ]
    for key, value in task.params:
        cmd.extend(["-p", f"{key}={value}"])
    start = time.monotonic()
    print(f"\n=== {config.name} {task.name} repeat {repeat} ===")
    print(" ".join(cmd))
    run_command(cmd, REPO_ROOT, log_path)
    return time.monotonic() - start


def parse_engine(benchmark: str) -> tuple[str, str]:
    method = benchmark.rsplit(".", 1)[-1]
    for suffix, engine in ENGINE_SUFFIXES.items():
        if method.endswith(suffix):
            return engine, benchmark[: -len(suffix)]
    return "safere", benchmark


def params_key(params: dict[str, str]) -> str:
    if not params:
        return ""
    return ",".join(f"{key}={params[key]}" for key in sorted(params))


def load_result_file(path: Path):
    with path.open() as fh:
        data = json.load(fh)
    for item in data:
        engine, base = parse_engine(item["benchmark"])
        metric = item["primaryMetric"]
        yield {
            "benchmark": base,
            "engine": engine,
            "params": params_key(item.get("params", {})),
            "score": float(metric["score"]),
            "error": float(metric.get("scoreError") or 0.0),
            "unit": metric["scoreUnit"],
        }


def mean(values: list[float]) -> float:
    return sum(values) / len(values)


def cv(values: list[float]) -> float:
    if len(values) < 2:
        return 0.0
    m = mean(values)
    if m == 0:
        return 0.0
    return statistics.stdev(values) / m


def verdict(ratio: float, deadband: float) -> str:
    if ratio < 1.0 - deadband:
        return "safere"
    if ratio > 1.0 + deadband:
        return "competitor"
    return "same"


def summarize(output_dir: Path, configs: list[Config], tasks: list[Task], repeats: int, deadband: float) -> str:
    timings = collections.defaultdict(list)
    measurements = collections.defaultdict(list)

    for config in configs:
        for task in tasks:
            timing_path = output_dir / f"{config.name}-{task.name}-timings.json"
            if timing_path.exists():
                timings[(config.name, task.name)] = json.loads(timing_path.read_text())
            for repeat in range(1, repeats + 1):
                path = output_dir / f"{config.name}-{task.name}-r{repeat}.json"
                if not path.exists():
                    continue
                for result in load_result_file(path):
                    key = (
                        config.name,
                        task.name,
                        result["benchmark"],
                        result["params"],
                        result["engine"],
                    )
                    measurements[key].append(result["score"])

    lines = []
    lines.append("# Java Benchmark Configuration Stability\n")
    lines.append(f"Repeats per config/task: {repeats}")
    lines.append(f"Winner deadband: +/- {deadband * 100:.1f}%")
    lines.append("")
    lines.append("## Configurations")
    lines.append("")
    lines.append("| Config | JMH arguments |")
    lines.append("|---|---|")
    for config in configs:
        lines.append(f"| {config.name} | `{' '.join(config.jmh_args)}` |")

    lines.append("")
    lines.append("## Runtime")
    lines.append("")
    lines.append("| Config | Total wall time | Mean task-repeat time |")
    lines.append("|---|---:|---:|")
    for config in configs:
        values = []
        for task in tasks:
            values.extend(timings.get((config.name, task.name), []))
        total = sum(values)
        avg = mean(values) if values else 0.0
        lines.append(f"| {config.name} | {total:.1f}s | {avg:.2f}s |")

    score_rows = []
    ratio_rows = []
    flip_rows = []
    for config in configs:
        per_score_cvs = []
        ratios_by_pair = collections.defaultdict(list)
        repeated_pairs = collections.defaultdict(list)
        for key, values in measurements.items():
            cfg, task_name, benchmark, params, engine = key
            if cfg != config.name:
                continue
            per_score_cvs.append(cv(values))
            if engine == "safere":
                continue
            safe_values = measurements.get((cfg, task_name, benchmark, params, "safere"), [])
            if len(safe_values) != len(values):
                continue
            pair = (task_name, benchmark, params, engine)
            ratios = [safe / other for safe, other in zip(safe_values, values)]
            ratios_by_pair[pair] = ratios
            repeated_pairs[(benchmark, params, engine)].extend(ratios)
        ratio_cvs = [cv(values) for values in ratios_by_pair.values()]
        flips = 0
        decisions = 0
        for ratios in ratios_by_pair.values():
            labels = {verdict(ratio, deadband) for ratio in ratios}
            labels.discard("same")
            if labels:
                decisions += 1
                if len(labels) > 1:
                    flips += 1
        score_rows.append((config.name, per_score_cvs))
        ratio_rows.append((config.name, ratio_cvs))
        flip_rows.append((config.name, flips, decisions))

    def percentile(values: list[float], p: float) -> float:
        if not values:
            return 0.0
        sorted_values = sorted(values)
        index = min(len(sorted_values) - 1, math.ceil(p * len(sorted_values)) - 1)
        return sorted_values[index]

    lines.append("")
    lines.append("## Stability")
    lines.append("")
    lines.append("| Config | Median score CV | p90 score CV | Median ratio CV | p90 ratio CV | Winner flips |")
    lines.append("|---|---:|---:|---:|---:|---:|")
    ratios_by_config = dict(ratio_rows)
    flips_by_config = {name: (flips, decisions) for name, flips, decisions in flip_rows}
    for name, score_cvs in score_rows:
        ratio_cvs = ratios_by_config.get(name, [])
        flips, decisions = flips_by_config[name]
        lines.append(
            "| "
            + f"{name} | {percentile(score_cvs, 0.5) * 100:.1f}% "
            + f"| {percentile(score_cvs, 0.9) * 100:.1f}% "
            + f"| {percentile(ratio_cvs, 0.5) * 100:.1f}% "
            + f"| {percentile(ratio_cvs, 0.9) * 100:.1f}% "
            + f"| {flips}/{decisions} |"
        )

    lines.append("")
    lines.append("## Detailed Ratio CVs")
    lines.append("")
    lines.append("| Config | Task | Benchmark | Params | Competitor | Mean SafeRE/competitor | Ratio CV |")
    lines.append("|---|---|---|---|---|---:|---:|")
    for config in configs:
        for key, values in measurements.items():
            cfg, task_name, benchmark, params, engine = key
            if cfg != config.name or engine == "safere":
                continue
            safe_values = measurements.get((cfg, task_name, benchmark, params, "safere"), [])
            if len(safe_values) != len(values):
                continue
            ratios = [safe / other for safe, other in zip(safe_values, values)]
            lines.append(
                f"| {cfg} | {task_name} | {benchmark} | {params or '-'} | {engine} "
                f"| {mean(ratios):.3f} | {cv(ratios) * 100:.1f}% |"
            )

    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--configs", default="A0,A1,A2,B,C,D,E")
    parser.add_argument("--tasks", default="calibration")
    parser.add_argument("--repeats", type=int, default=5)
    parser.add_argument("--deadband", type=float, default=0.05)
    parser.add_argument("--output-dir", default=None)
    parser.add_argument("--no-build", action="store_true")
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()

    configs = selected_configs(args.configs)
    tasks = selected_tasks(args.tasks)
    if args.output_dir:
        output_dir = Path(args.output_dir)
    else:
        output_dir = REPO_ROOT / "benchmark-results" / f"config-eval-{time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())}"
    if not output_dir.is_absolute():
        output_dir = REPO_ROOT / output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    if not args.no_build:
        build_benchmarks()
    if not BENCHMARK_JAR.exists():
        raise SystemExit(f"Benchmark JAR not found: {BENCHMARK_JAR}")

    for config in configs:
        for task in tasks:
            timing_path = output_dir / f"{config.name}-{task.name}-timings.json"
            timings = json.loads(timing_path.read_text()) if args.resume and timing_path.exists() else []
            for repeat in range(1, args.repeats + 1):
                json_path = output_dir / f"{config.name}-{task.name}-r{repeat}.json"
                if args.resume and json_path.exists():
                    continue
                elapsed = run_jmh(config, task, repeat, output_dir)
                timings.append(elapsed)
                timing_path.write_text(json.dumps(timings, indent=2) + "\n")

    report = summarize(output_dir, configs, tasks, args.repeats, args.deadband)
    report_path = output_dir / "report.md"
    report_path.write_text(report)
    latest = REPO_ROOT / "benchmark-results" / "config-eval-latest"
    latest.parent.mkdir(exist_ok=True)
    if latest.exists() or latest.is_symlink():
        if latest.is_dir() and not latest.is_symlink():
            shutil.rmtree(latest)
        else:
            latest.unlink()
    latest.symlink_to(output_dir)
    print(f"\nReport written to {report_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
