# Cross-Engine Java Benchmark Execution

Ordinary cross-engine regex measurements use a shared execution model. A
workload declares its stable `id` in `benchmark-data.json`; Java class and
method names are not used to infer workload identity. The same ID joins Java,
C++, Go, generated reports, and historical results.

A materially changed operation, input, result-consumption rule, or timing
boundary requires a new workload ID. Display labels and the shared JMH entry
point may change without changing an otherwise equivalent workload's identity.

## Execution variants

The Java comparison matrix is declared in one engine registry:

| Variant ID | Report label | Input at timed boundary |
|---|---|---|
| `safere-string` | `safere` | Pre-existing Java `String` |
| `safere-utf8` | `safere_utf8` | Pre-existing UTF-8 corpus bytes wrapped as trusted `Utf8Input` during setup |
| `jdk-string` | `jdk` | Pre-existing Java `String` |
| `re2j-string` | `re2j` | Pre-existing Java `String` |
| `re2-ffm-string-conversion` | `re2_ffm` | Java `String`; UTF-8 conversion at the FFM API boundary is timed |

Materialization, UTF-8 validation, Java decoding, `Utf8Input` construction,
pattern compilation, operation binding, and expected-result validation happen
in JMH setup, outside the timed operation.

Each variant declares native capabilities. The planner emits only supported
workload/variant trials, so an unsupported operation is different from a
missing implementation. In particular, SafeRE UTF-8 participates in direct
`find` and repeated-`find` operations; it does not emulate String-only
`matches`, group-text, replacement, or split APIs.

## JMH trials and result names

JMH receives one planned parameter rather than independent engine and workload
parameters. This avoids an invalid Cartesian product. Trial IDs have the form:

```text
<workload-id>@<variant-id>
```

For example:

```text
RegexBenchmark.emailFind@safere-utf8
```

The report normalizer splits that trial ID into the stable workload row and
execution-variant column. Existing workload IDs remain stable even though the
raw JMH entry points are now `CrossEngineBenchmark.run` and
`CrossEngineScalingBenchmark.run`.

The runner supplies the complete supported plan automatically. A focused run
can override it:

```bash
./run-java-benchmarks.sh CrossEngineBenchmark.run -- \
  -p crossEngineTrial=RegexBenchmark.emailFind@safere-utf8
```

The nanosecond and microsecond entry points remain separate so units continue
to align with native benchmark results and historical Java reports.

Specialized benchmarks remain separate when their mechanics are themselves
under measurement, including compilation, memory, splitting, capture scaling,
replacement-focused workloads, pathological safeguards, matcher state,
PatternSet, and dedicated UTF-8 API behavior.
