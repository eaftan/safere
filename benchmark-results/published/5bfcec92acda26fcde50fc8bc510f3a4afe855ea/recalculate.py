#!/usr/bin/env python3
"""Recalculate the report's pairwise aggregate ratios from published results."""

import csv
import json
import math
import re
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SAFE = ROOT / "safere"
REBAR = ROOT / "rebar"


def geomean(values):
    return math.exp(sum(math.log(value) for value in values) / len(values))


def show(label, pairs):
    values = [ratio for _, ratio in pairs]
    if values:
        print(f"{label:48} n={len(values):3} ratio={geomean(values):.6f}")


safe_rows = [json.loads(line) for line in (SAFE / "normalized-results.jsonl").read_text().splitlines()]
safe = {(row["engine"], row["benchmark"]): row for row in safe_rows}
assert len(safe) == len(safe_rows)

data = json.loads((SAFE / "benchmark-data.json").read_text())
real_world_operations = {
    workload["id"].split(".")[2]: workload["operation"]
    for workload in data["workloads"]
    if workload["id"].startswith("RealWorldRegexBenchmark.runBenchmark.")
}


def safe_category(benchmark):
    if benchmark.startswith("RealWorldRegexBenchmark.runBenchmark."):
        return "realworld/" + real_world_operations[benchmark.split(".")[2]]
    for prefix, category in (
        ("RegexBenchmark.", "core"),
        ("ApplicationBenchmark.", "application"),
        ("CompileBenchmark.", "compile"),
    ):
        if benchmark.startswith(prefix):
            return category
    return None


categories = defaultdict(list)
for (engine, benchmark), row in safe.items():
    if engine == "safere" and safe_category(benchmark):
        categories[safe_category(benchmark)].append(benchmark)

safe_time_units = {"ns/op": 1, "us/op": 1_000, "ms/op": 1_000_000}
safe_engines = ("safere_utf8", "jdk", "re2j", "re2_ffm", "re2_cpp", "pcre2_jit", "rust")
safe_timing = {
    benchmark
    for (engine, benchmark), row in safe.items()
    if engine == "safere" and row["unit"] in safe_time_units
}


def safe_pairs(benchmarks, competitor):
    return [
        (
            benchmark,
            safe["safere", benchmark]["score"]
            * safe_time_units[safe["safere", benchmark]["unit"]]
            / (
                safe[competitor, benchmark]["score"]
                * safe_time_units[safe[competitor, benchmark]["unit"]]
            ),
        )
        for benchmark in benchmarks
        if (competitor, benchmark) in safe
        and safe[competitor, benchmark]["unit"] in safe_time_units
    ]


print(f"SafeRE suite overall: {len(safe_timing)} SafeRE String timing rows")
for competitor in safe_engines:
    show(f"overall / {competitor}", safe_pairs(safe_timing, competitor))
safe_common = {
    benchmark
    for benchmark in safe_timing
    if all(
        (competitor, benchmark) in safe
        and safe[competitor, benchmark]["unit"] in safe_time_units
        for competitor in safe_engines
    )
}
print(f"SafeRE suite all-engine common membership: {len(safe_common)} rows")
for competitor in safe_engines:
    show(f"common / {competitor}", safe_pairs(safe_common, competitor))

print("SafeRE suite: SafeRE String / competitor")
for category in (
    "realworld/find",
    "realworld/replaceAll",
    "realworld/findGroup",
    "realworld/matches",
    "realworld/splitLengthSum",
    "core",
    "application",
    "compile",
):
    for competitor in (
        "jdk",
        "re2j",
        "re2_ffm",
        "re2_cpp",
        "pcre2_jit",
        "rust",
        "safere_utf8",
    ):
        pairs = [
            (
                benchmark,
                safe["safere", benchmark]["score"] / safe[competitor, benchmark]["score"],
            )
            for benchmark in categories[category]
            if (competitor, benchmark) in safe
        ]
        show(f"{category} / {competitor}", pairs)

search = categories["realworld/find"]
for outcome in ("match", "noMatch"):
    for size in ("1000", "10000", "100000"):
        pairs = [
            (benchmark, safe["safere", benchmark]["score"] / safe["jdk", benchmark]["score"])
            for benchmark in search
            if benchmark.endswith(f".{outcome}.{size}") and ("jdk", benchmark) in safe
        ]
        show(f"find {outcome} {size} / jdk", pairs)
for omitted in (
    {"structuredJsonPath", "wildcardSearch"},
    {"structuredJsonPath", "wildcardSearch", "multiInfixLog"},
):
    pairs = [
        (benchmark, safe["safere", benchmark]["score"] / safe["jdk", benchmark]["score"])
        for benchmark in search
        if benchmark.split(".")[2] not in omitted and ("jdk", benchmark) in safe
    ]
    show(f"find except {','.join(sorted(omitted))} / jdk", pairs)

with (REBAR / "results-combined.csv").open(newline="") as file:
    rebar_rows = list(csv.DictReader(file))
rebar = {(row["engine"], row["name"], row["model"]): row for row in rebar_rows}
assert len(rebar) == len(rebar_rows)

unit = {"ns": 1, "us": 1_000, "µs": 1_000, "ms": 1_000_000, "s": 1_000_000_000}


def nanos(value):
    match = re.fullmatch(r"([0-9]+(?:\.[0-9]+)?)(ns|us|µs|ms|s)", value)
    assert match, value
    return float(match[1]) * unit[match[2]]


rebar_categories = defaultdict(list)
for (engine, name, model), row in rebar.items():
    if engine == "safere/string" and name.startswith("curated/") and not row["err"]:
        rebar_categories[(name.split("/")[0], model)].append((name, model))

rebar_engines = (
    "safere/utf8",
    "safere/utf8-vector",
    "java/hotspot",
    "re2j",
    "re2",
    "pcre2/jit",
    "rust/regex",
)
rebar_timing = {
    (name, model)
    for (engine, name, model), row in rebar.items()
    if engine == "safere/string"
    and name.startswith("curated/")
    and not row["err"]
    and model != "compile"
}


def rebar_pairs(keys, competitor):
    return [
        (
            (name, model),
            nanos(rebar["safere/string", name, model]["median"])
            / nanos(rebar[competitor, name, model]["median"]),
        )
        for name, model in keys
        if (competitor, name, model) in rebar and not rebar[competitor, name, model]["err"]
    ]


print(f"\nRebar curated overall: {len(rebar_timing)} SafeRE String rows")
for competitor in rebar_engines:
    show(f"overall / {competitor}", rebar_pairs(rebar_timing, competitor))
rebar_common = {
    (name, model)
    for name, model in rebar_timing
    if all(
        (competitor, name, model) in rebar
        and not rebar[competitor, name, model]["err"]
        for competitor in rebar_engines
    )
}
print(f"Rebar curated all-engine common membership: {len(rebar_common)} rows")
for competitor in rebar_engines:
    show(f"common / {competitor}", rebar_pairs(rebar_common, competitor))

print("\nRebar curated by model")
for model in ("count", "count-spans", "count-captures", "grep", "grep-captures"):
    keys = {(name, row_model) for name, row_model in rebar_timing if row_model == model}
    for competitor in rebar_engines:
        show(f"{model} / {competitor}", rebar_pairs(keys, competitor))

curated_without_dictionary = [
    (
        name,
        nanos(rebar["safere/string", name, model]["median"])
        / nanos(rebar["java/hotspot", name, model]["median"]),
    )
    for name, model in rebar_categories["curated", "count"]
    if name != "curated/12-dictionary/single"
]
show("curated/count without dictionary / java/hotspot", curated_without_dictionary)

# Separate compilation costs without replacing the full-suite headline.
compile_prefixes = (
    "CompileBenchmark.",
    "UnicodeCompileBenchmark.",
    "UnicodeFirstCompileBenchmark.",
    "JavaCharacterClassBenchmark.compileAndFind",
)
non_compile = {benchmark for benchmark in safe_timing if not benchmark.startswith(compile_prefixes)}
for competitor in ("jdk", "re2_cpp"):
    show(f"SafeRE suite excluding compilation / {competitor}", safe_pairs(non_compile, competitor))

vector_pairs = [
    (name, nanos(rebar["safere/utf8-vector", name, model]["median"])
     / nanos(rebar["safere/utf8", name, model]["median"]))
    for name, model in rebar_timing
    if ("safere/utf8-vector", name, model) in rebar
    and ("safere/utf8", name, model) in rebar
    and not rebar["safere/utf8-vector", name, model]["err"]
    and not rebar["safere/utf8", name, model]["err"]
]
show("Rebar curated UTF-8 Vector / default UTF-8", vector_pairs)

curated_without_dictionary_keys = {
    key for key in rebar_timing if key[0] != "curated/12-dictionary/single"
}
show("curated without dictionary / re2", rebar_pairs(curated_without_dictionary_keys, "re2"))
for name, model in sorted(rebar_timing):
    if name in {
        "curated/11-unstructured-to-json/extract",
        "curated/05-lexer-veryl/single",
        "curated/08-words/all-russian",
    }:
        show(f"{name} / java/hotspot", rebar_pairs({(name, model)}, "java/hotspot"))
