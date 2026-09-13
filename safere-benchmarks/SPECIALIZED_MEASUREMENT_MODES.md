# Specialized benchmark measurement modes

Specialized workloads use the same declarations, capability join, stable trial IDs, and
materialized inputs as ordinary cross-engine workloads. Separate runners exist only where the
measurement boundary requires different JMH or process machinery.

| Declared mode or constraint | Generic runner | Boundary |
| --- | --- | --- |
| `averageTime` | `CrossEngineBenchmark`, `CrossEngineScalingBenchmark` | Normal forked JMH execution |
| `noFork` | `CrossEngineNoForkBenchmark` | In-process JMH execution (`-f 0`) |
| `singleShotColdStart` | `CrossEngineColdStartBenchmark`; native C++, Go, and Rust runners | One invocation in each fresh process |
| SafeRE-specific `averageTime` operation | `SpecializedBenchmark` | One operation adapter selected from the plan |
| `retainedMemory` | `MemoryBenchmark` | Standalone heap-delta process with retained objects |

`run-java-benchmarks.sh` obtains each runner's trial parameter values from the plan. No-fork
scheduling therefore depends on the `noFork` constraint, not on a benchmark family or class-name
substring. Cold-start setup resolves a declaration but does not compile its pattern before the
single measured invocation.

The C++, Go, and Rust runners execute native `singleShotColdStart` rows in a fresh child for
each sample (one for smoke, five for a normal trial). The parent selects the exact pattern from
the materialized plan, requires an empty option list, and passes the pattern to the child. The
child starts its timer immediately before compilation and sends the elapsed time back after
compilation. Process launch, executable loading, manifest parsing, corpus materialization, and
result transport are outside the timed interval. PCRE2 JIT's interval includes both
`pcre2_compile` and `pcre2_jit_compile` with `PCRE2_JIT_COMPLETE`; the child checks that JIT code
was generated.
Unsupported flag sets and properties remain durable plan exclusions. These cold measurements
are cross-runtime startup context and must stay separate from steady-state compile and match
aggregates.

The schema also validates `subprocessMemory`. The current suite has no RSS or other
subprocess-memory workload, so there is no active runner invocation for that mode. Process launch,
output capture, and OS-specific resident-set observation are measurement infrastructure rather
than a hidden workload; a future declaration using this mode must add its generic process observer
before it can be included in the implemented-operation set.

## Specialized operations

The generic SafeRE-specific runner implements PatternSet matching, direct UTF-8 capture bounds,
decode-inclusive UTF-8 matching, byte-native replacement, borrowed UTF-8 windows, UTF-8 view
construction, static pattern analysis, and diagnostics listener modes. Retained-memory execution
implements compiled-pattern size and DFA-cache growth. Cases, patterns, parameters, inputs,
listener choices, anchors, and timing boundaries are all declared in `benchmark-data.json`.

`CrosscheckOverheadBenchmark` is the only remaining non-generic JMH class. It measures optional
crosscheck instrumentation rather than a regex workload and remains excluded from normal benchmark
collection. Allocation profiling is applied to declared generic trials by
`run-java-memory-benchmarks.sh --declared`; cold-start trials use
`CrossEngineColdStartBenchmark`.
