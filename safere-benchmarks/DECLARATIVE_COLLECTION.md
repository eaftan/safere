# Declarative benchmark collection

`BenchmarkCollectionPlan` is the collection and reporting query surface for the expanded benchmark
plan. It discovers generic JMH runners and their exact trial parameters; collection scripts do not
maintain workload-family or legacy benchmark-class lists.

The supported queries are:

```text
BenchmarkCollectionPlan runners [--smoke]
BenchmarkCollectionPlan allocation-runners [--smoke]
BenchmarkCollectionPlan trials [--mode MODE] [--timing UNIT]
                               [--prefix PREFIX] [--variant VARIANT]
BenchmarkCollectionPlan report-plan [--smoke]
```

`runners` emits tab-separated execution profile, JMH entry point, parameter name, and planned trial
IDs. `run-java-benchmarks.sh --declared` consumes those rows sequentially and applies standard,
no-fork, or fresh-process settings from the selected profile. `allocation-runners` uses the
declarative `configuration.collection.allocationWorkloadPrefixes` selection and is consumed by
`run-java-memory-benchmarks.sh --declared`.

`report-plan` emits JSON containing every scheduled workload/variant pair and every declared
exclusion. With `--smoke`, it selects the same representative workloads and compatible variants as
`runners --smoke`. The result normalizer uses this catalog to render `missing` when a supported
trial did not produce a result and `excluded` when the plan intentionally rejected that variant.
An em dash continues to mean that the column is outside the declared Java execution matrix, such
as a cross-runtime context column.

JMH parameter names are an execution detail. The normalizer recognizes all generic runner
parameters and splits each `<workload-id>@<execution-variant>` value into its stable report row and
engine column. `safere-utf8` remains a distinct column from `safere-string`, and
`re2-ffm-string-conversion` remains distinct from native cross-runtime RE2 results, so input
representation and timed conversion boundaries are not erased.

C++ RE2, PCRE2 JIT, Go `regexp`, Rust `regex`, and .NET non-backtracking harnesses run outside the
JVM and emit JSONL with the same stable workload identities. RE2 and PCRE2 JIT share one C++
workload harness but emit distinct engine IDs. The materialized manifest includes one versioned
`executionPlan` for Java and native consumers. It contains the complete workload-by-engine join;
each entry is runnable with exact engine-selected syntax and arguments, or excluded with a durable
reason. Native runners implement generic operations and expose planned exclusions through
`--list-exclusions`; they do not select profiles, dispatch workload families, or infer
capabilities. Cross-runtime results are not treated as missing or excluded Java execution variants. Per-engine
workload, toolchain, and smoke-test details are documented in
[`CROSS_RUNTIME_ENGINES.md`](CROSS_RUNTIME_ENGINES.md).
