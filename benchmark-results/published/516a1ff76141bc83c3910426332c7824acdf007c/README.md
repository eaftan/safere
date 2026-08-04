# Published benchmark results

These are the complete artifacts supporting the claims in
[`BENCHMARKS.md`](../../../BENCHMARKS.md) for SafeRE commit
`516a1ff76141bc83c3910426332c7824acdf007c`, committed at
`2026-08-02T02:46:40Z`.

The collection command was:

```bash
./collect-benchmark-results.sh --cross-language --skip-openjdk-regex
```

This was a standard-mode collection. It includes the SafeRE Java suite and the
C++ RE2, PCRE2 JIT, Go, Rust, and .NET cross-runtime harnesses. The separately
licensed external OpenJDK-derived suite was intentionally skipped. The full
hardware, software, timing, and engine configuration is recorded in
`BENCHMARKS.md`.

## Contents

- `jmh-output.txt` and `java-declared.txt` contain the original Java/JMH output.
- `java-memory.txt` and `java-pattern-memory.txt` contain the Java memory runs.
- `cpp-raw.txt`, `go-raw.txt`, `rust-raw.txt`, and `dotnet-raw.txt` contain the
  original cross-runtime harness output.
- The engine-specific `*-results.jsonl` files contain records emitted by those
  cross-runtime harnesses.
- `declared-report-plan.json` records the resolved Java workload plan,
  including declared trials and exclusions.
- `normalized-results.jsonl` contains all 2,949 parsed measurements in one
  common schema: `engine`, `benchmark`, `score`, `error`, and `unit`. It was
  derived from the preserved JMH and engine-specific JSONL files by
  `safere-benchmarks/scripts/compare-benchmarks.py`.
- `merged-tables.md` and `cross-runtime-tables.md` are generated views of the
  collected measurements.
- `SHA256SUMS` authenticates every other file in this directory.

Raw output is authoritative. The normalized JSONL and Markdown tables are
derived conveniences for analysis and should be reproducible from the raw
files and resolved plan.
