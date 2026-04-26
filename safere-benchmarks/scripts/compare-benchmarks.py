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
  python3 compare-benchmarks.py --jmh jmh-output.txt --json cpp.jsonl go.jsonl

  # Specify engine column order
  python3 compare-benchmarks.py --jmh jmh.txt --json cpp.jsonl \
      --engines safere,jdk,re2j,re2_cpp

JSON-lines format (one object per line):
  {"engine":"re2_cpp","benchmark":"RegexBenchmark.literalMatch",
   "score":40.674,"error":0.830,"unit":"ns/op"}

JMH text format (whitespace-separated):
  Benchmark                          Mode  Cnt   Score   Error  Units
  RegexBenchmark.literalMatch_jdk    avgt    5  23.456 ± 1.234  ns/op
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
}


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


def parse_jmh(path):
    """Parse a JMH text-output file and return a list of ``Result`` objects."""
    results = []
    with open(path) as fh:
        for line in fh:
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


def generate_tables(results, engines):
    """Generate markdown tables from *results*, one per benchmark class.

    Args:
        results: iterable of ``Result`` objects.
        engines: ordered list of engine names for column layout.

    Returns:
        A string containing the full markdown output.
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

    # If no explicit engine list, discover from data (preserving order).
    if not engines:
        engines = [e for e in (_DEFAULT_ENGINE,) if e in seen_engines]
        for suffix_engine in _ENGINE_SUFFIXES.values():
            if suffix_engine in seen_engines and suffix_engine not in engines:
                engines.append(suffix_engine)
        for e in sorted(seen_engines):
            if e not in engines:
                engines.append(e)

    # Group benchmarks by class.
    groups = collections.OrderedDict()
    for bench in all_benchmarks:
        cls = _benchmark_class(bench)
        groups.setdefault(cls, []).append(bench)

    lines = []
    first_group = True
    for cls, benchmarks in groups.items():
        if not first_group:
            lines.append("")
        first_group = False

        lines.append(f"### {cls}")
        lines.append("")

        # Determine the unit from the first available result for this group.
        unit = ""
        for bench in benchmarks:
            for eng in engines:
                r = index.get((bench, eng))
                if r and r.unit:
                    unit = r.unit
                    break
            if unit:
                break

        # Build header.
        header_bench = "Benchmark"
        engine_headers = [f"{eng} ({unit})" if unit else eng for eng in engines]

        # Compute cell contents so we can measure column widths.
        rows = []
        for bench in benchmarks:
            method = _benchmark_method(bench)
            cells = []
            for eng in engines:
                r = index.get((bench, eng))
                if r:
                    cells.append(_fmt_cell(r.score, r.error))
                else:
                    cells.append("—")
            rows.append((method, cells))

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
        for method, cells in rows:
            parts = [_pad(method, bench_width)]
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

    engines = [e.strip() for e in args.engines.split(",")] if args.engines else []
    print(generate_tables(results, engines))


if __name__ == "__main__":
    main()
