# Specialized benchmark measurement modes

Specialized workloads use the same declarations, capability join, stable trial IDs, and
materialized inputs as ordinary cross-engine workloads. Separate runners exist only where the
measurement boundary requires different JMH or process machinery.

| Declared mode or constraint | Generic runner | Boundary |
| --- | --- | --- |
| `averageTime` | `CrossEngineBenchmark`, `CrossEngineScalingBenchmark` | Normal forked JMH execution |
| `noFork` | `CrossEngineNoForkBenchmark` | In-process JMH execution (`-f 0`) |
| `singleShotColdStart` | `CrossEngineColdStartBenchmark` | One invocation in each fresh fork |
| SafeRE-specific `averageTime` operation | `SpecializedBenchmark` | One operation adapter selected from the plan |
| `retainedMemory` | `MemoryBenchmark` | Standalone heap-delta process with retained objects |

`run-java-benchmarks.sh` obtains each runner's trial parameter values from the plan. No-fork
scheduling therefore depends on the `noFork` constraint, not on a benchmark family or class-name
substring. Cold-start setup resolves a declaration but does not compile its pattern before the
single measured invocation.

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
