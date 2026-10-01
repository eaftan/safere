#!/usr/bin/env python3

# Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.

"""Merge benchmark results from multiple engines and produce markdown tables.

Reads JMH text output and/or JSON-lines files, normalises them into a
common internal representation, and emits side-by-side markdown tables
grouped by benchmark class.

Usage examples:

  # JMH results only
  python3 compare-benchmarks.py --jmh jmh-output.txt

  # JMH + one or more JSON-lines files
  python3 compare-benchmarks.py --jmh jmh-output.txt \
      --json cpp.jsonl go.jsonl rust.jsonl

  # Specify engine column order
  python3 compare-benchmarks.py --jmh jmh.txt --json cpp.jsonl \
      --engines safere,jdk,re2j,re2_cpp

JSON-lines format (one object per line):
  {"engine":"re2_cpp","benchmark":"ExampleSuite.literal",
   "score":40.674,"error":0.830,"unit":"ns/op"}

JMH text format (whitespace-separated):
  Benchmark                          Mode  Cnt   Score   Error  Units
  ExampleSuite.literal_jdk           avgt    5  23.456 ± 1.234  ns/op
  Benchmark                          (engine)  (inputSize)  Mode  Cnt  Score  Error  Units
  ExampleSuite.scaling              SafeRE  1000         avgt    5  23.456 ± 1.234 us/op
"""

import argparse
import collections
import json
import re
import sys

# ---------------------------------------------------------------------------
# Internal data model
# ---------------------------------------------------------------------------

# One benchmark measurement.
Result = collections.namedtuple("Result", ["engine", "benchmark", "score", "error", "unit"])

# Engine suffix → engine name mapping for JMH methods.
_ENGINE_SUFFIXES = collections.OrderedDict([
    ("_safere", "safere"),
    ("_jdk", "jdk"),
    ("_re2ffm", "re2_ffm"),
    ("_re2j", "re2j"),
])
_DEFAULT_ENGINE = "safere"
_ENGINE_ALIASES = {
    "go_regexp": "go",
    "rust_regex": "rust",
}
_JMH_ENGINE_PARAMS = {
    "SafeRE": "safere",
    "JDK": "jdk",
    "RE2J": "re2j",
    "RE2_FFM": "re2_ffm",
}
_CROSS_ENGINE_VARIANTS = {
    "safere-string": "safere",
    "safere-utf8": "safere_utf8",
    "safere-utf8-vector": "safere_utf8_vector",
    "jdk-string": "jdk",
    "re2j-string": "re2j",
    "re2-ffm-string-conversion": "re2_ffm",
}
_DECLARED_TRIAL_PARAMS = (
    "crossEngineTrial",
    "crossEngineScalingTrial",
    "crossEngineNoForkTrial",
    "crossEngineColdStartTrial",
    "specializedTrial",
)
_JMH_MODES = {"avgt", "thrpt", "sample", "ss", "all"}


def _engine_from_method(method):
    """Derive engine name and base benchmark name from a JMH method name.

    Methods ending with a known suffix (e.g. ``_jdk``, ``_re2j``) are mapped
    to the corresponding engine.  Methods with no recognised suffix are
    assigned to the default engine (``safere``).

    Returns:
        (engine, base_benchmark) tuple.
    """
    for suffix, engine in _ENGINE_SUFFIXES.items():
        if method.endswith(suffix):
            return engine, method[: -len(suffix)]
    return _DEFAULT_ENGINE, method


# ---------------------------------------------------------------------------
# Parsers
# ---------------------------------------------------------------------------

# Regex for the data rows of JMH text output.  Handles optional Cnt column and
# the optional ``±`` separator between score and error.
_JMH_LINE_RE = re.compile(
    r"^(?P<bench>\S+)"       # Fully-qualified benchmark name
    r"\s+\S+"                # Mode (avgt, thrpt, …)
    r"(?:\s+\d+)?"           # Optional Cnt
    r"\s+(?P<score>[\d.]+)"  # Score
    r"(?:\s+[±]"             # Optional ± separator
    r"\s+(?P<error>[\d.]+))?"  # Optional error
    r"\s+(?P<unit>\S+)"      # Units
    r"\s*$"
)


def _parse_jmh_header(line):
    parts = line.split()
    if not parts or parts[0] != "Benchmark" or "Mode" not in parts:
        return None
    mode_index = parts.index("Mode")
    return [part[1:-1] for part in parts[1:mode_index]
            if part.startswith("(") and part.endswith(")")]


def _parse_jmh_parameterized_line(line, param_names):
    if not param_names:
        return None
    parts = line.split()
    if not parts or parts[0] == "Benchmark":
        return None
    try:
        mode_index = next(i for i, part in enumerate(parts[1:], 1) if part in _JMH_MODES)
    except StopIteration:
        return None
    if mode_index != 1 + len(param_names):
        return None

    full_name = parts[0]
    params = dict(zip(param_names, parts[1:mode_index]))
    mode = parts[mode_index]
    del mode

    value_index = mode_index + 1
    if value_index < len(parts) and parts[value_index].isdigit():
        value_index += 1
    if value_index >= len(parts):
        return None
    try:
        score = float(parts[value_index])
    except ValueError:
        return None
    value_index += 1

    error = 0.0
    if value_index < len(parts) and parts[value_index] == "±":
        value_index += 1
        if value_index >= len(parts):
            return None
        try:
            error = float(parts[value_index])
        except ValueError:
            return None
        value_index += 1

    if value_index >= len(parts):
        return None
    unit = parts[value_index]

    dot = full_name.rfind(".")
    if dot == -1:
        return None
    class_name = full_name[: dot]
    method = full_name[dot + 1 :]

    trial = next(
        (params.get(name) for name in _DECLARED_TRIAL_PARAMS
         if params.get(name) and params.get(name) != "N/A"),
        None,
    )
    if trial and trial != "N/A":
        try:
            benchmark, variant = trial.rsplit("@", 1)
        except ValueError:
            return None
        engine = _CROSS_ENGINE_VARIANTS.get(variant)
        if engine is None:
            return None
        return Result(
            engine=engine,
            benchmark=benchmark,
            score=score,
            error=error,
            unit=unit,
        )

    engine, base_method = _engine_from_method(method)
    if "engine" in params and params["engine"] != "N/A":
        engine = _JMH_ENGINE_PARAMS.get(params["engine"], params["engine"].lower())

    benchmark = _parameterized_benchmark_name(class_name, base_method, params, param_names)
    return Result(engine=engine, benchmark=benchmark, score=score, error=error, unit=unit)


def _parameterized_benchmark_name(class_name, method, params, param_names):
    active_params = {
        name: params[name] for name in param_names
        if name != "engine" and params.get(name) != "N/A"
    }
    suffixes = [active_params[name] for name in param_names if name in active_params]
    if suffixes:
        return f"{class_name}.{method}." + ".".join(suffixes)
    return f"{class_name}.{method}" if class_name else method


def parse_jmh(path):
    """Parse a JMH text-output file and return a list of ``Result`` objects."""
    results = []
    param_names = []
    with open(path) as fh:
        for line in fh:
            parsed_header = _parse_jmh_header(line)
            if parsed_header is not None:
                param_names = parsed_header
                continue

            parameterized_result = _parse_jmh_parameterized_line(line, param_names)
            if parameterized_result is not None:
                results.append(parameterized_result)
                continue

            m = _JMH_LINE_RE.match(line)
            if not m:
                continue
            full_name = m.group("bench")
            score = float(m.group("score"))
            error = float(m.group("error") or 0)
            unit = m.group("unit")

            # Split "ClassName.method" – keep ClassName prefix for grouping.
            dot = full_name.rfind(".")
            if dot == -1:
                continue
            class_name = full_name[: dot]
            method = full_name[dot + 1 :]

            engine, base_method = _engine_from_method(method)
            benchmark = f"{class_name}.{base_method}" if class_name else base_method
            results.append(Result(engine=engine, benchmark=benchmark,
                                  score=score, error=error, unit=unit))
    return results


def parse_jsonl(path):
    """Parse a JSON-lines file and return a list of ``Result`` objects."""
    results = []
    with open(path) as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError as exc:
                print(f"WARNING: {path}:{lineno}: skipping invalid JSON: {exc}",
                      file=sys.stderr)
                continue
            engine = _ENGINE_ALIASES.get(obj["engine"], obj["engine"])
            results.append(Result(
                engine=engine,
                benchmark=obj["benchmark"],
                score=float(obj["score"]),
                error=float(obj.get("error", 0)),
                unit=obj.get("unit", ""),
            ))
    return results


def generate_jsonl(results):
    """Serialize normalized results as stable JSON Lines."""
    lines = []
    for result in results:
        lines.append(json.dumps({
            "engine": result.engine,
            "benchmark": result.benchmark,
            "score": result.score,
            "error": result.error,
            "unit": result.unit,
        }, separators=(",", ":")))
    return "\n".join(lines) + ("\n" if lines else "")


# ---------------------------------------------------------------------------
# Formatting helpers
# ---------------------------------------------------------------------------


def _fmt_score(value):
    """Format a numeric score for display.

    * >= 10   → integer (e.g. ``42``)
    * 1–10    → one decimal (e.g. ``3.1``)
    * < 1     → two decimals (e.g. ``0.83``)
    """
    if value >= 10:
        return f"{value:.0f}"
    if value >= 1:
        return f"{value:.1f}"
    return f"{value:.2f}"


def _fmt_cell(score, error):
    """Format a table cell as ``score ± error``."""
    return f"{_fmt_score(score)} ± {_fmt_score(error)}"


# ---------------------------------------------------------------------------
# Markdown table generation
# ---------------------------------------------------------------------------


_GENERIC_RUNNER_METHODS = frozenset({
    "run",
    "runBenchmark",
    "runDeclared",
    "runScaling",
    "runNoFork",
    "runColdStart",
    "runSpecialized",
})


def _simplify_benchmark_name(benchmark):
    """Dynamically strip package, class, and generic harness runner prefixes."""
    parts = benchmark.split(".")

    # 1. Skip leading lowercase package segments (e.g., 'org.safere.benchmark')
    start_idx = 0
    while start_idx < len(parts) and parts[start_idx] and parts[start_idx][0].islower():
        if start_idx + 1 < len(parts) and parts[start_idx + 1] and parts[start_idx + 1][0].isupper():
            start_idx += 1
            break
        start_idx += 1

    remaining = parts[start_idx:]
    if not remaining:
        return benchmark

    # 2. If the first segment is a Java class name (starts with uppercase)
    if remaining[0] and remaining[0][0].isupper():
        # If followed by a generic harness runner method, strip class + runner
        if len(remaining) >= 3 and remaining[1] in _GENERIC_RUNNER_METHODS:
            return ".".join(remaining[2:])
        # Otherwise retain the class so methods shared by multiple classes remain distinct.
        elif len(remaining) >= 2:
            return ".".join(remaining)

    return ".".join(remaining)


def _benchmark_class(benchmark):
    """Return the class portion of a ``ClassName.method`` benchmark name."""
    dot = benchmark.rfind(".")
    if dot == -1:
        return benchmark
    return benchmark[: dot]


def _benchmark_method(benchmark):
    """Return the method portion of a ``ClassName.method`` benchmark name."""
    dot = benchmark.rfind(".")
    if dot == -1:
        return benchmark
    return benchmark[dot + 1 :]


def _pad(text, width):
    """Left-align *text* in a field of *width* characters."""
    return text + " " * max(0, width - len(text))


def _rpad(text, width):
    """Right-align *text* in a field of *width* characters."""
    return " " * max(0, width - len(text)) + text


def load_declared_plan(path):
    """Load declared trial and exclusion identities emitted by BenchmarkCollectionPlan."""
    with open(path) as fh:
        plan = json.load(fh)
    statuses = collections.OrderedDict()
    for trial in plan.get("trials", []):
        engine = _CROSS_ENGINE_VARIANTS.get(trial["executionVariant"])
        if engine:
            statuses[(trial["workloadId"], engine)] = "declared"
    for exclusion in plan.get("exclusions", []):
        engine = _CROSS_ENGINE_VARIANTS.get(exclusion["executionVariant"])
        if engine:
            statuses.setdefault((exclusion["workloadId"], engine), "excluded")
    return statuses


_TIME_UNIT_SECONDS = {
    "ns/op": 1e-9,
    "us/op": 1e-6,
    "ms/op": 1e-3,
    "s/op": 1.0,
}
_THROUGHPUT_UNIT_PER_SECOND = {
    "ops/ns": 1e9,
    "ops/us": 1e6,
    "ops/ms": 1e3,
    "ops/s": 1.0,
}


def _canonical_measurement(result):
    unit = (result.unit or "").lower().replace("µ", "u").replace("μ", "u")
    if unit in _TIME_UNIT_SECONDS:
        return "time", result.score * _TIME_UNIT_SECONDS[unit]
    if unit in _THROUGHPUT_UNIT_PER_SECOND:
        return "throughput", result.score * _THROUGHPUT_UNIT_PER_SECOND[unit]
    return None


def _format_speedup(r_base, r_curr):
    """Computes and formats a unit-normalized speedup ratio."""
    if not r_base or not r_curr or r_base.score <= 0 or r_curr.score <= 0:
        return "—"

    base = _canonical_measurement(r_base)
    current = _canonical_measurement(r_curr)
    if base is None or current is None or base[0] != current[0]:
        return "—"
    if base[0] == "throughput":
        ratio = current[1] / base[1]
    else:
        ratio = base[1] / current[1]
    if ratio >= 10.0:
        return f"{ratio:.1f}x"
    elif ratio >= 1.0:
        return f"{ratio:.2f}x"
    elif ratio < 0.01:
        return f"{ratio:.2g}x"
    else:
        return f"{ratio:.2f}x"


def generate_tables(
    results,
    engines=None,
    declared_statuses=None,
    show_speedup=False,
    single_table=False,
):
    """Generate markdown comparison tables from a list of Results.

    Args:
        results: list of Result namedtuples.
        engines: list of engine names in column order, or None to auto-detect.
        declared_statuses: optional dict from (benchmark, engine) to status.
        show_speedup: if True and exactly 2 engines are compared, include Speedup column.
        single_table: if True, render all results in one unified table with full names.

    Returns:
        Markdown string containing one or more tables.
    """
    # Index: (benchmark, engine) → Result
    index = {}
    all_benchmarks = collections.OrderedDict()
    seen_engines = set()
    for r in results:
        key = (r.benchmark, r.engine)
        index[key] = r
        all_benchmarks[r.benchmark] = True
        seen_engines.add(r.engine)
    if declared_statuses:
        for benchmark, engine in declared_statuses:
            all_benchmarks.setdefault(benchmark, True)
            seen_engines.add(engine)

    # If no explicit engine list, discover from data (preserving order).
    if not engines:
        engines = [e for e in (_DEFAULT_ENGINE,) if e in seen_engines]
        for suffix_engine in _ENGINE_SUFFIXES.values():
            if suffix_engine in seen_engines and suffix_engine not in engines:
                engines.append(suffix_engine)
        for e in sorted(seen_engines):
            if e not in engines:
                engines.append(e)

    # Group benchmarks by class (or single group if single_table is True).
    if single_table:
        groups = collections.OrderedDict([("", list(all_benchmarks.keys()))])
    else:
        groups = collections.OrderedDict()
        for bench in all_benchmarks:
            cls = _benchmark_class(bench)
            groups.setdefault(cls, []).append(bench)

    include_speedup = show_speedup and len(engines) == 2

    lines = []
    first_group = True
    for cls, benchmarks in groups.items():
        if not first_group and cls:
            lines.append("")
        first_group = False

        if cls:
            lines.append(f"### {cls}")
            lines.append("")

        # A grouped table normally has one unit. A unified table may combine benchmark families,
        # so preserve mixed units explicitly per row rather than mislabeling every measurement with
        # the first result's unit.
        units = {
            r.unit
            for bench in benchmarks
            for eng in engines
            if (r := index.get((bench, eng))) and r.unit
        }
        mixed_units = len(units) > 1
        unit = next(iter(units), "") if not mixed_units else ""

        # Build header.
        header_bench = "Benchmark"
        engine_headers = [f"{eng} ({unit})" if unit else eng for eng in engines]
        if include_speedup:
            engine_headers.append("Speedup")

        # Compute cell contents so we can measure column widths.
        rows = []
        for bench in benchmarks:
            name = _simplify_benchmark_name(bench) if single_table else _benchmark_method(bench)
            cells = []
            for eng in engines:
                r = index.get((bench, eng))
                if r:
                    cell = _fmt_cell(r.score, r.error)
                    cells.append(f"{cell} {r.unit}" if mixed_units and r.unit else cell)
                elif declared_statuses and declared_statuses.get((bench, eng)) == "excluded":
                    cells.append("excluded")
                elif declared_statuses and declared_statuses.get((bench, eng)) == "declared":
                    cells.append("missing")
                else:
                    cells.append("—")
            if include_speedup:
                r1 = index.get((bench, engines[0]))
                r2 = index.get((bench, engines[1]))
                cells.append(_format_speedup(r1, r2))
            rows.append((name, cells))

        # Column widths.
        bench_width = max(len(header_bench), *(len(row[0]) for row in rows))
        col_widths = []
        for i, eh in enumerate(engine_headers):
            w = len(eh)
            for _, cells in rows:
                w = max(w, len(cells[i]))
            col_widths.append(w)

        # Emit header row.
        parts = [_pad(header_bench, bench_width)]
        for i, eh in enumerate(engine_headers):
            parts.append(_rpad(eh, col_widths[i]))
        lines.append("| " + " | ".join(parts) + " |")

        # Emit separator row.
        sep_parts = ["-" * bench_width]
        for w in col_widths:
            sep_parts.append("-" * w)
        lines.append("| " + " | ".join(sep_parts) + " |")

        # Emit data rows.
        for name, cells in rows:
            parts = [_pad(name, bench_width)]
            for i, cell in enumerate(cells):
                parts.append(_rpad(cell, col_widths[i]))
            lines.append("| " + " | ".join(parts) + " |")

    lines.append("")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Merge benchmark results from multiple engines into markdown tables.",
    )
    parser.add_argument(
        "--declared-plan",
        metavar="FILE",
        help="JSON report plan emitted by BenchmarkCollectionPlan.",
    )
    parser.add_argument(
        "--jmh",
        metavar="FILE",
        help="Path to JMH text-output file.",
    )
    parser.add_argument(
        "--json",
        metavar="FILE",
        nargs="+",
        help="Path(s) to JSON-lines result file(s).",
    )
    parser.add_argument(
        "--engines",
        metavar="LIST",
        help="Comma-separated engine names in desired column order "
             "(e.g. safere,jdk,re2j,re2_cpp).",
    )
    parser.add_argument(
        "--output-jsonl",
        metavar="FILE",
        help="Write all parsed results in the normalized JSON-lines format.",
    )
    parser.add_argument(
        "--speedup",
        action="store_true",
        help="Include a Speedup column when comparing exactly two engines.",
    )
    parser.add_argument(
        "--single-table",
        action="store_true",
        help="Emit all results in a single unified table with full benchmark names.",
    )
    args = parser.parse_args(argv)

    if not args.jmh and not args.json:
        parser.error("at least one of --jmh or --json is required")

    results = []
    if args.jmh:
        results.extend(parse_jmh(args.jmh))
    if args.json:
        for path in args.json:
            results.extend(parse_jsonl(path))

    if not results:
        print("No benchmark results found.", file=sys.stderr)
        sys.exit(1)

    if args.output_jsonl:
        with open(args.output_jsonl, "w", encoding="utf-8") as output:
            output.write(generate_jsonl(results))

    engines = [e.strip() for e in args.engines.split(",")] if args.engines else []
    declared_statuses = (
        load_declared_plan(args.declared_plan) if args.declared_plan else None
    )
    print(
        generate_tables(
            results,
            engines,
            declared_statuses,
            show_speedup=args.speedup,
            single_table=args.single_table,
        )
    )


if __name__ == "__main__":
    main()
