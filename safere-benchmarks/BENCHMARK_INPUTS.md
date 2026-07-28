# Shared Benchmark Inputs

The bounded recipe vocabulary and normalized input declaration schema are
described in
[DECLARATIVE_BENCHMARK_PLAN.md](DECLARATIVE_BENCHMARK_PLAN.md).

`benchmark-data.json` is the only checked-in source for benchmark patterns,
parameters, expected results, and deterministic input recipes.

Before execution, each benchmark runner invokes the central materializer. It
writes a resolved manifest and exact UTF-8 inputs under
`target/benchmark-corpus/`. Java, C++, Go, Rust, and future harnesses read only
those generated artifacts; they do not read or interpret `benchmark-data.json`.
Java string engines decode input files as UTF-8 during benchmark setup, while
byte-oriented engines use the bytes directly. Materialization and decoding are
outside the timed operation.

Patterns remain Java-canonical in `benchmark-data.json`. Explicit
engine-dialect alternatives live beside the pattern that needs them. The
materializer collects those inline definitions into the resolved manifest;
runners select their profile there and otherwise use the Java string unchanged.
See [DECLARATIVE_BENCHMARK_PLAN.md](DECLARATIVE_BENCHMARK_PLAN.md#pattern-profiles).

The normal runner scripts materialize automatically. To prepare the corpus
without starting a benchmark, run from the repository root:

```bash
./materialize-benchmark-inputs.sh
```

The manifest records each input's UTF-8 byte length, UTF-16 code-unit length,
Unicode scalar count, and SHA-256 digest, along with the resolved benchmark
configuration. The generated directory is ignored and is replaced on every
materialization, so there is no second checked-in representation to update.

Every benchmark input is an explicit declaration in `benchmark-data.json`.
The materializer evaluates only the schema's bounded, generic recipe kinds; it
contains no workload-family dispatch or hidden input defaults. These
declarations preserve the Java harness's previous generator behavior, so the
Java workloads are unchanged. C++ and Go previously
implemented their own random and Unicode generators, which differed in PRNG
and size semantics; affected cross-language results collected before this
corpus was introduced are not directly comparable with new results.
