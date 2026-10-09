# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

"""Serial paired wrapper measurements and native-unit JMH result extraction."""

import argparse
import importlib.util
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from repo_assist.maintenance import SHA, git, write_json

MANIFEST_PREFIX = "=== Requested Trials: "
TRIAL_PARAMETERS = ("crossEngineScalingTrial", "crossEngineTrial", "crossEngineNoForkTrial", "crossEngineColdStartTrial", "specializedTrial")
COMPLETE = "=== Benchmark Comparison Results ==="
GUARDED = ("pom.xml", ":(glob)**/pom.xml", ".mvn", "safere-benchmarks", "materialize-benchmark-inputs.sh", "run-java-benchmarks.sh")


def load_parser(repository):
  """Load the maintained parser without creating files in a measured checkout."""
  parser_path = repository / "safere-benchmarks/scripts/compare-benchmarks.py"
  spec = importlib.util.spec_from_file_location("repo_assist_jmh_parser", parser_path)
  parser = importlib.util.module_from_spec(spec)
  exec(compile(parser_path.read_text(encoding="utf-8"), str(parser_path), "exec"), parser.__dict__)
  return parser


def validate_trials(trials):
  if not isinstance(trials, list) or not trials or any(
    not isinstance(trial, str) or not trial or "," in trial
    or any(character.isspace() for character in trial) for trial in trials
  ):
    raise ValueError("provide one exact requested trial ID per line, without commas or whitespace")
  if len(trials) != len(set(trials)):
    raise ValueError("duplicate requested trial IDs")
  return trials


def read_trials(path):
  return validate_trials([line.strip() for line in path.read_text().splitlines() if line.strip()])


def requested_keys(parser, trials):
  keys = set()
  for trial in validate_trials(trials):
    benchmark, separator, variant = trial.rpartition("@")
    engine = parser._CROSS_ENGINE_VARIANTS.get(variant)
    if not separator or not benchmark or engine is None:
      raise ValueError(f"unsupported requested trial: {trial}")
    key = (engine, benchmark)
    if key in keys:
      raise ValueError(f"duplicate requested trial identity: {trial}")
    keys.add(key)
  return keys


def verify_trials(parser, rows, trials):
  expected = requested_keys(parser, trials)
  if rows.keys() != expected:
    missing = sorted(expected - rows.keys())
    unexpected = sorted(rows.keys() - expected)
    raise ValueError(f"JMH results do not match requested trials: missing={missing}, unexpected={unexpected}")


def trial_manifest(text, args):
  lines = [line for line in text.splitlines() if line.startswith(MANIFEST_PREFIX)]
  explicit = read_trials(args.trials) if getattr(args, "trials", None) else None
  if lines:
    if len(lines) != 1 or not lines[0].endswith(" ==="):
      raise ValueError("invalid requested trial manifest")
    manifest = json.loads(lines[0][len(MANIFEST_PREFIX):-4])
    if not isinstance(manifest, dict) or manifest.get("parameter") not in TRIAL_PARAMETERS:
      raise ValueError("invalid requested trial manifest")
    validate_trials(manifest.get("trials"))
    if explicit is not None and set(explicit) != set(manifest["trials"]):
      raise ValueError("explicit requested trials differ from log manifest")
    return manifest
  if explicit is None:
    raise ValueError("comparison lacks a requested trial manifest; supply --trials for a legacy log")
  return {"parameter": args.parameter, "trials": explicit}


def compare(args):
  worktree = args.worktree.resolve()
  log = args.log.resolve()
  if log.exists():
    raise ValueError("comparison log exists; choose a new artifact path")
  branch = git(worktree, "symbolic-ref", "--short", "HEAD").stdout.strip()
  if not branch.startswith("codex/review/"):
    raise ValueError("use an isolated codex/review/ branch, never an authored original worktree")
  if git(worktree, "status", "--porcelain").stdout:
    raise ValueError("benchmark worktree must be clean, including untracked files")
  try:
    log.relative_to(worktree)
  except ValueError:
    pass
  else:
    raise ValueError("comparison log must be outside the benchmark worktree")
  for sha in (args.baseline, args.experiment):
    if not SHA.fullmatch(sha):
      raise ValueError("benchmark revisions must be full immutable commit SHAs")
    git(worktree, "cat-file", "-e", sha + "^{commit}")
  diff = git(worktree, "diff", "--exit-code", args.baseline, args.experiment, "--", *GUARDED, allowed=(0, 1))
  if diff.returncode:
    raise ValueError("benchmark workload/harness/build/wrapper differs; construct a controlled baseline first")
  trials = read_trials(args.trials)
  log.parent.mkdir(parents=True, exist_ok=True)
  with log.open("x", encoding="utf-8") as output:
    output.write(MANIFEST_PREFIX + json.dumps({"parameter": args.parameter, "trials": trials}) + " ===\n")
    try:
      for label, sha in (("Baseline", args.baseline), ("Current", args.experiment)):
        marker = f"=== Running {label}: {sha} ===\n"
        output.write(marker)
        output.flush()
        git(worktree, "checkout", "--quiet", "--detach", sha)
        subprocess.run([args.maven, "-pl", "safere-benchmarks", "-am", "clean", "-q"], cwd=worktree, stdout=output, stderr=subprocess.STDOUT, check=True)
        command = ["./run-java-benchmarks.sh"]
        if args.mode == "long":
          command.append("--long")
        command += ["--fastbuild", args.filter, "--", "-p", args.parameter + "=" + ",".join(trials)]
        subprocess.run(command, cwd=worktree, stdout=output, stderr=subprocess.STDOUT, check=True)
        if git(worktree, "status", "--porcelain").stdout:
          raise ValueError("measurement modified nonignored worktree files; preserve and inspect them")
        output.flush()
        parser = load_parser(worktree)
        rows = parse_rows(parser, log.read_text(encoding="utf-8").split(marker, 1)[1])
        verify_trials(parser, rows, trials)
    finally:
      # Never stash/reset/discard files. A failed restoration is surfaced rather than hidden.
      git(worktree, "checkout", "--quiet", branch)
    output.write(COMPLETE + "\n")
  return {"log": str(log), "baseline": args.baseline, "experiment": args.experiment, "mode": args.mode}


def parse_rows(parser, text):
  with tempfile.TemporaryDirectory(prefix="repo-assist-jmh-") as directory:
    path = Path(directory) / "arm.txt"
    path.write_text(text, encoding="utf-8")
    parsed = parser.parse_jmh(path)
  # The shared parser intentionally skips rows it cannot recognize. A paired comparison must
  # account for every summary measurement, including malformed/NaN/negative-score rows.
  candidates = []
  for line in text.splitlines():
    parts = line.split()
    if parts and not parts[0].startswith("#") and "." in parts[0]:
      if any(part in {"avgt", "thrpt", "sample", "ss", "all"} for part in parts[1:]):
        candidates.append(line)
  if len(candidates) != len(parsed):
    raise ValueError("JMH summary contains an unsupported or invalid row; comparison is incomplete")
  rows = {}
  for row in parsed:
    key = (row.engine, row.benchmark)
    if key in rows:
      raise ValueError(f"duplicate JMH result: {key}")
    if not all(math.isfinite(value) for value in (row.score, row.error)) or row.score <= 0 or row.error < 0:
      raise ValueError(f"invalid JMH score/error: {key}")
    rows[key] = {"score": row.score, "error": row.error, "unit": row.unit}
  if not rows:
    raise ValueError("comparison arm has no JMH summary rows")
  return rows


def extract(args):
  # Reuse the repository's maintained declared-trial parser; do not invent benchmark identities.
  parser = load_parser(args.repository)
  text = args.log.read_text(encoding="utf-8")
  manifest = trial_manifest(text, args)
  baseline_markers = list(re.finditer(r"^=== Running Baseline: ([0-9a-f]+) ===$", text, re.M))
  current_markers = list(re.finditer(r"^=== Running Current: ([0-9a-f]+) ===$", text, re.M))
  completed = list(re.finditer(r"^" + re.escape(COMPLETE) + r"$", text, re.M))
  if not all(len(markers) == 1 for markers in (baseline_markers, current_markers, completed)):
    raise ValueError("comparison log is incomplete or contains multiple comparisons")
  baseline, current, done = baseline_markers[0], current_markers[0], completed[0]
  if not baseline.end() < current.start() < done.start() or not all(SHA.fullmatch(marker[1]) for marker in (baseline, current)):
    raise ValueError("invalid comparison marker order/revisions")
  before = parse_rows(parser, text[baseline.end():current.start()])
  after = parse_rows(parser, text[current.end():done.start()])
  verify_trials(parser, before, manifest["trials"])
  verify_trials(parser, after, manifest["trials"])
  if before.keys() != after.keys():
    raise ValueError("baseline and experiment benchmark/engine sets differ")
  rows = []
  for (engine, benchmark), b in before.items():
    e = after[(engine, benchmark)]
    if b["unit"] != e["unit"]:
      raise ValueError(f"unit mismatch for {benchmark}")
    rows.append({"engine": engine, "benchmark": benchmark, "baseline": b, "experiment": e, "ratio": e["score"] / b["score"], "reportedIntervalsOverlap": max(b["score"] - b["error"], e["score"] - e["error"]) <= min(b["score"] + b["error"], e["score"] + e["error"])})
  result = {"baselineSha": baseline[1], "experimentSha": current[1], "mode": args.mode, "requestedTrials": manifest["trials"], "trialParameter": manifest["parameter"], "ratioMeaning": "experiment score / baseline score; lower elapsed time is better; other metrics require their own direction", "rows": rows}
  write_json(args.output, result)
  print("| Benchmark | Engine | Unit | Baseline | Experiment | Experiment/base score | Reported intervals overlap |")
  print("|---|---|---|---:|---:|---:|---|")
  for row in rows:
    b, e = row["baseline"], row["experiment"]
    benchmark = row["benchmark"].replace("|", "\\|")
    print(f"| `{benchmark}` | {row['engine']} | {b['unit']} | {b['score']:.3f} ± {b['error']:.3f} | {e['score']:.3f} ± {e['error']:.3f} | {row['ratio']:.3f} | {row['reportedIntervalsOverlap']} |")
  return result


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  commands = parser.add_subparsers(dest="command", required=True)
  run = commands.add_parser("compare", help="Run serial baseline/current arms through the repository wrapper")
  run.add_argument("--worktree", type=Path, required=True)
  run.add_argument("--baseline", required=True)
  run.add_argument("--experiment", required=True)
  run.add_argument("--trials", type=Path, required=True)
  run.add_argument("--log", type=Path, required=True)
  run.add_argument("--mode", choices=("standard", "long"), default="standard")
  run.add_argument("--filter", default="CrossEngineScalingBenchmark.run")
  run.add_argument("--parameter", choices=TRIAL_PARAMETERS, default="crossEngineScalingTrial")
  run.add_argument("--maven", default="mvn")
  run.set_defaults(func=compare)
  read = commands.add_parser("extract", help="Extract a complete paired log without unit normalization")
  read.add_argument("--repository", type=Path, required=True)
  read.add_argument("--log", type=Path, required=True)
  read.add_argument("--output", type=Path, required=True)
  read.add_argument("--trials", type=Path, help="Expected trial list for legacy logs, or an additional manifest check")
  read.add_argument("--parameter", choices=TRIAL_PARAMETERS, default="crossEngineScalingTrial")
  read.add_argument("--mode", choices=("standard", "long"), required=True)
  read.set_defaults(func=extract)
  args = parser.parse_args()
  try:
    result = args.func(args)
  except (ValueError, OSError, subprocess.CalledProcessError) as error:
    parser.exit(2, f"{error}\n")
  if args.command != "extract":
    print(json.dumps(result, indent=2))
  return 0
