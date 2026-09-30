# SafeRE 1.0 benchmark evidence

These artifacts support the performance report for unreleased SafeRE 1.0.
The measured SafeRE source is the public commit
[`5bfcec92acda26fcde50fc8bc510f3a4afe855ea`](https://github.com/eaftan/safere/commit/5bfcec92acda26fcde50fc8bc510f3a4afe855ea),
committed 2026-09-26T00:51:20Z. The build's Maven version during collection
was `0.12.0-SNAPSHOT`.

Original harness output is authoritative. Normalized results and comparison
tables are derived from it. `SHA256SUMS` covers every file except this README
and the root checksum file.

## Reproducing the SafeRE suite

Check out the measured SafeRE commit and apply the supplied
[`safere/benchmark-source.patch`](safere/benchmark-source.patch).
The patch fixes benchmark collection and declares engine-specific pattern
syntax; it does not change the matching library. Follow the repository's
build instructions for the Java, RE2, PCRE2, and Rust prerequisites.

Run these wrappers sequentially from the SafeRE repository root:

```bash
./run-java-benchmarks.sh --declared
./run-java-memory-benchmarks.sh \
  --cross-engine-prefix 'RegexBenchmark.' \
  --cross-engine-scaling-prefix 'SearchScalingBenchmark.'
java -Xms256m -Xmx256m \
  -Dsafere.benchmark.corpus=safere-benchmarks/target/benchmark-corpus \
  -cp safere-benchmarks/target/benchmarks.jar \
  org.safere.benchmark.MemoryBenchmark
./run-cpp-benchmarks.sh --engine re2
./run-cpp-benchmarks.sh --engine pcre2-jit
./run-rust-benchmarks.sh
```

The measured collection used standard mode and the same wrappers, with
`--fastbuild` for the Java timing run after the library was built. The
wrappers control warmup, sampling, and specialized measurement settings.
Preserve their output when collecting a new comparison.

`safere/benchmark-data.json` is the exact workload declaration.
`safere/declared-report-plan.json` records the expanded trials and exclusions.
The raw timing files are `jmh-output.txt`, `re2-raw.txt`,
`pcre2-jit-raw.txt`, and `rust-raw.txt` under `safere/`. Native adapter
results are also available as `*-results.jsonl`. Memory and first-use output
are retained alongside the timing files.

`safere/normalized-results.jsonl` contains 4,859 unique `(engine, benchmark)`
results with `score`, `error`, and `unit` fields. The declared plan includes
26 Java trials that failed before measurement: citation-replacement patterns
and a case-folding pattern unsupported by RE2/J and RE2-FFM. Larger JDK
citation and `recitation` trials are explicitly excluded because its
matcher throws `StackOverflowError`. The report excludes these cases from
ratios; the original logs retain the failures.

The generated input corpus supplied in `safere/reconstructed-corpus.tar.gz`
and `safere/reconstructed-manifest.json` was reconstructed from the exact
workload declaration and recorded materializer source. It is a reproduction
aid, not original captured output. The original manifest's byte hash is
recorded in `safere/source-checksums.txt`; the reconstructed manifest has a
different byte hash. Independent reconstructions produced identical input
files and semantically equal manifests with different JSON field ordering.

## Reproducing Rebar

Use the publicly available
[`personal` branch of eaftan/rebar](https://github.com/eaftan/rebar/tree/personal).
For the exact measured source, check out
[`9ee90dfb569cbcfb81b8a23a4b99b12bfff0e1d7`](https://github.com/eaftan/rebar/commit/9ee90dfb569cbcfb81b8a23a4b99b12bfff0e1d7)
and apply [`rebar/rebar-runner.patch`](rebar/rebar-runner.patch). This selects
the measured SafeRE Maven artifact, accepts its version string, and repairs
a malformed benchmark declaration. Build and install the measured SafeRE
source before building Rebar's SafeRE runners.

Follow Rebar's build instructions for the selected engines, then run:

```bash
./target/release/rebar measure \
  -e '^(java/hotspot|re2j|re2|rust/regex|safere/(string|utf8|utf8-vector)|pcre2/jit)$' \
  --timeout 180s
```

To collect only the subset used in this report, add `-f '^curated/'` to
the command above. The archived collection used the full engine-selected
command; the calculation script applies the curated filter to its results.

The original full run used Rebar's default 10-second timeout; 36 timed-out
measurements were rerun with 60- or 180-second limits. Using 180 seconds
up front allows those slower cases to complete with the same sampling settings.

`rebar/results.csv` contains the original run and `timeout-retry-*.csv`
contains the reruns. `rebar/results-combined.csv` replaces only the 36
successfully retried timeout rows. It has 2,120 unique `(name, model, engine)`
rows, including two Rust expected-count failures and no remaining timeouts.
The combined CSV is the report's calculation input; all source CSVs are retained.

The report and calculation script use only successful `curated/` workload
rows shared with SafeRE String. This gives 34 SafeRE workloads. Rebar's
[contributor guide](https://github.com/BurntSushi/rebar/blob/master/CONTRIBUTING.md#adding-a-new-benchmark)
identifies this subset as its public comparison set, intended to provide a
broad overview of engine performance. The full collection remains available
as raw evidence; it does not enter the report's Rebar comparisons.

Curated compilation workloads omit Java and SafeRE because of the repeated
compilation concern described in Rebar's
[Java runner documentation](https://github.com/eaftan/rebar/blob/9ee90dfb569cbcfb81b8a23a4b99b12bfff0e1d7/engines/java/README.md).
The report excludes compilation from its Rebar comparisons. The
`test/model/compile` smoke test is retained in the full CSV, outside the
curated subset.

## Checking the calculations

From this artifact directory, run:

```bash
python3 recalculate.py
sha256sum --check SHA256SUMS
```

The script prints the overall geomeans, operation and model breakdowns,
common-engine subsets, compilation exclusions, and the direct UTF-8 Vector
comparison. Every average gives equal
weight to each successful shared timing result. Overall results combine
included operations within each suite; detailed results separate them. The
SafeRE suite includes compilation; Rebar uses only its curated subset and
excludes compilation. The
suites are never pooled. Non-timing measurements and failed trials do not
enter an overall ratio.

The SafeRE suite uses the normalized `score`; Rebar uses the CSV `median`.
Both are converted to consistent time units before comparison. The
normalized JSONL and `safere/merged-tables.md` regenerate byte-for-byte
from the preserved raw outputs using
`safere-benchmarks/scripts/compare-benchmarks.py`.
