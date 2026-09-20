# Rebar curated benchmarks, 19 September 2026

This is an archive of one exploratory [Rebar](https://github.com/eaftan/rebar)
run comparing SafeRE's String and UTF-8 paths with seven other engines. The
complete retained results are in [`measurements.csv`](measurements.csv). The
best guess for the SafeRE commit is
[`4e079bf8a9f168e77f965337c61f13d56a4c5729`](https://github.com/eaftan/safere/commit/4e079bf8a9f168e77f965337c61f13d56a4c5729).
The exact JAR used by this run cannot be established, so these numbers are
separate from the commit-attributed results in
[`BENCHMARKS.md`](../../BENCHMARKS.md).

## What was run

- Rebar identified itself as `0.1.0 (rev cb3395d8a5)`, corresponding to
  [this fork commit](https://github.com/eaftan/rebar/commit/cb3395d8a5f8306b437a82a14f3e9c477559d2a3).
- The run started at `2026-09-19T23:28:43-07:00` and covered 52 workloads from
  Rebar's [curated definitions](https://github.com/eaftan/rebar/tree/cb3395d8a5f8306b437a82a14f3e9c477559d2a3/benchmarks/definitions/curated).
  There are 350 measured workload/engine rows across nine engines. SafeRE
  String and UTF-8 each have 34 rows. Rebar's workload allowlists and runner
  capabilities account for different coverage; these are measured rows, not
  failed attempts.
- Rebar's verification pass returned `OK` for all 350 rows. The original
  [verification log](verify.log) includes the expected result checks.
- Both SafeRE modes report `0.12.0-SNAPSHOT`, OpenJDK 26.0.2, and
  `scanner=vector`. At this Rebar revision, the SafeRE runner used JMH 1.37
  `SampleTime`, one fork and one thread for timed matching, with its window
  lengths derived from Rebar's budget. Other engines used their own Rebar
  runners. The Vector provider was enabled in both SafeRE modes.
- The machine was an Intel Core i7-11700K under WSL2. Full CPU, OS, and
  toolchain details are in [`environment.txt`](environment.txt); exact engine
  versions are in the CSV's `engine_version` column.

## Summary

The table uses Rebar's **median time per workload**. For each competitor, it
includes only workload/model rows measured by both that engine and SafeRE
String or SafeRE UTF-8. The ratio is `SafeRE time / competitor time`; a value
below 1 means SafeRE was faster. Each shared workload gets equal weight in
the geometric mean. “Wins” counts rows with a lower SafeRE median. Ratios
are calculated from the rounded medians preserved in the CSV. Rows are ordered
by increasing geometric mean ratio within each table.

### SafeRE String

| Competitor | Shared workloads | Geomean ratio | SafeRE String wins |
|---|---:|---:|---:|
| `pcre2` | 33 | 0.071 | 24/33 |
| `java/hotspot` | 34 | 0.162 | 28/34 |
| `javascript/v8` | 32 | 0.699 | 15/32 |
| `re2` | 31 | 0.872 | 11/31 |
| `safere/utf8` | 34 | 0.903 | 25/34 |
| `pcre2/jit` | 33 | 1.509 | 8/33 |
| `rust/regex` | 34 | 3.205 | 3/34 |
| `hyperscan` | 24 | 4.714 | 5/24 |

### SafeRE UTF-8

| Competitor | Shared workloads | Geomean ratio | SafeRE UTF-8 wins |
|---|---:|---:|---:|
| `pcre2` | 33 | 0.079 | 25/33 |
| `java/hotspot` | 34 | 0.180 | 27/34 |
| `javascript/v8` | 32 | 0.767 | 14/32 |
| `re2` | 31 | 0.959 | 11/31 |
| `safere/string` | 34 | 1.108 | 9/34 |
| `pcre2/jit` | 33 | 1.676 | 9/33 |
| `rust/regex` | 34 | 3.551 | 3/34 |
| `hyperscan` | 24 | 5.234 | 4/24 |

SafeRE String's aggregate time is about 6.2× lower than `java/hotspot` on the
34 shared rows. It is about 3.2× higher than Rust `regex` on those same 34
rows. PCRE2's JIT setting changes the comparison substantially: SafeRE String
is faster than `pcre2` on 24 of 33 shared rows, but slower than `pcre2/jit` on
25 of 33.

The RE2 comparison needs care. SafeRE String has a 0.872 geometric mean ratio
over 31 shared rows, yet is slower on 20 of them. One large SafeRE win on
`curated/12-dictionary/single` contributes heavily: SafeRE's median is
81.02 µs versus RE2's 12.97 ms. Omitting that one row changes the geometric
mean ratio to 1.028 across the remaining 30 rows. The aggregate therefore
does not describe a consistent advantage across workloads.

Across the 34 workloads supported by both SafeRE modes, String has a 0.903
geometric mean time ratio to UTF-8 and wins 25 rows. UTF-8 is faster on nine
rows, including `curated/02-literal-alternate/sherlock-en`, where its advantage
is large. Both modes consume input prepared before timed execution; the String
path receives a decoded Java `String` and the UTF-8 path receives bytes. This
comparison reflects those APIs and workload implementations, not input
conversion cost.

These are cross-runtime comparisons on one machine with each engine's Rebar
runner. They are useful for workload-specific investigation, but the
geometric means are sensitive to workload membership and outliers. No
confidence interval for a pairwise aggregate was retained. Rebar's CSV has
per-workload summary statistics, not SafeRE's individual JMH samples. The
selected workloads also omit SafeRE compilation benchmarks.

## Files and reproduction

- [`measurements.csv`](measurements.csv) is Rebar's complete retained
  measurement output, including workload, model, engine version, timing
  summaries, and error fields.
- [`verify.log`](verify.log) and [`build.log`](build.log) are the original
  correctness and build logs. The first build attempt failed a version-string
  check; the subsequent build and verification succeeded.
- [`environment.txt`](environment.txt) records the run's system information.
- [`cmp-nine-intersection.txt`](cmp-nine-intersection.txt) and
  [`cmp-safere-java-rust.txt`](cmp-safere-java-rust.txt) are saved Rebar
  comparison views. They are derived from the CSV.
- [`measure.log`](measure.log) is the original, empty measurement log; the
  measurements went to CSV.
- [`analyze.py`](analyze.py) reproduces the pairwise tables from the CSV using
  Python's standard library. [`SHA256SUMS`](SHA256SUMS) covers every other file
  in this directory.

Run `python3 analyze.py` in this directory to regenerate both SafeRE mode
tables. Use `python3 analyze.py --exclude curated/12-dictionary/single` to
check how the RE2 aggregate changes without that workload. To rerun the
benchmarks themselves, check out the linked Rebar commit and use its curated
definitions and engine runners; a new run should pin and record the exact
SafeRE JAR hash.
