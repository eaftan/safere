# Rebar collection notes

This is collection evidence for the SafeRE 1.0 benchmark run. It is not the benchmark report.

- Rebar checkout: `/home/eaftan/rebar`, branch `personal`, commit `9ee90dfb569cbcfb81b8a23a4b99b12bfff0e1d7`.
- Rebar CLI: `0.1.0 (rev 9ee90dfb56)`.
- SafeRE dependency: `0.12.0-SNAPSHOT`.
- Engines selected: `java/hotspot`, `re2j`, `re2`, `rust/regex`, `safere/string`, `safere/utf8`, `safere/utf8-vector`, `pcre2/jit`.
- Initial full command: `./target/release/rebar measure -e '^(java/hotspot|re2j|re2|rust/regex|safere/(string|utf8|utf8-vector)|pcre2/jit)$'`.
- The run used Rebar's standard default measurement settings and full default workload set, filtered to the selected engines.
- Initial output: `results.csv`, 2,120 rows. Engine row counts: JDK 115; RE2/J 266; RE2 285; Rust regex 344; SafeRE String 275; SafeRE UTF-8 275; SafeRE UTF-8 vector 275; PCRE2-JIT 285.
- Selected 36 rows that initially timed out at 10 seconds for sequential retries with `--timeout 60s`. Exact successful retry rows replace their timeout rows in `results-combined.csv`; all source CSVs are retained, including `timeout-retry-re2j-180s.csv`.
- JMH forks require localhost sockets. Early retries in the restricted execution environment failed because socket bind was denied. The `timeout-retry-permitted-*.csv` batches reran the affected Java engines with local socket permission. The JDK and RE2 retries succeeded in the initial targeted batches and are included in the combined file.

Two correctness errors remain in `results-combined.csv`. All initially timed-out rows now have completed measurements; the RE2/J dictionary search completed with a 180-second timeout after about 61 seconds:

- Rust regex count mismatches on `opt/reverse-suffix/unsound-leftmost-first` and `opt/reverse-suffix/unsound-start-literal-order-mismatch` (expected 1, got 2 in both cases). These are correctness mismatches, not timing failures.

`results-combined.csv` is a convenience merge keyed by `(name, model, engine)`. The original and retry CSV files remain authoritative evidence. Retry measurements have the same sampling configuration as the main run; only the wall-clock timeout changed. The two correctness mismatches must be considered when deciding which comparisons have complete coverage.
