# Optimization investigation

SafeRE revision: `a2f50788701659f6367f905f7acdb9eef59a6f3a`. Standard measurements of the declared development corpus, with SafeRE String, default SafeRE UTF-8, native RE2, and Rust regex. Native engines and SafeRE UTF-8 use pre-existing UTF-8; String uses Java String. Cross-runtime figures include different APIs and runtime overhead and are leads, not controlled implementation comparisons. The experimental Vector provider, memory, Rebar and the external OpenJDK-derived suite were excluded. Source changes and commands are captured alongside the raw files; measurements include an initial collection, native resume and four Java retries.

## Priority candidates

Times below are per operation; native comparisons represent the same declared workload, subject to engine semantics. Units and errors are in `normalized-results.jsonl`; 100000 and 1048576 are declared input sizes. These are selected large gaps, not an aggregate ranking of the entire corpus.

| Workload | SafeRE String | SafeRE UTF-8 | RE2 | Rust | Direction |
|---|---:|---:|---:|---:|---|
| `SearchScalingBenchmark.endAnchoredGreedySuccess.1048576` | 41.31 ms | 0.356 µs | 0.117 µs | 0.068 µs | Investigate why the String path scans despite the UTF-8 path finding a fast route. Pattern `(?s:.*)ABCDEFGHIJKLMNOPQRSTUVWXYZ$`. |
| `SearchScalingBenchmark.endAnchoredEarlyLiteralDot.1048576` | 43.61 ms | 43.69 ms | 1.09 ms | 1.31 ms | Investigate end-anchored search and matching in both representations. |
| `RealWorldRegexBenchmark.runBenchmark.customProtocolLinkReplace.falseCandidates.100000` | 7.21 ms | 7.27 ms | 0.272 ms | 0.278 ms | Investigate rejection and capture costs for false replacement candidates. |
| `RealWorldRegexBenchmark.runBenchmark.mapFieldPath.match.100000` | 334 µs | 160 µs | 0.116 µs | 0.068 µs | Investigate search acceleration for `([\\S]+)\\b\\[(.+)\\]`; matching position and semantics should be examined before interpreting the large ratio. |

JFR diagnostic recordings sampled the String end-anchored case mainly in NFA search and stepping, the UTF-8 field-path case in DFA search, and UTF-8 replacement in NFA search and stepping. These samples identify where time was spent; they do not by themselves identify a safe optimization. Recordings are `profile-{end,field,replace}.jfr`, with diagnostic JMH output in the corresponding `.log` files. Measurements used for comparisons are from the standard collection, not these non-forked profiling runs.

Compilation is included in the data. First-compile rows include process/runtime startup costs and should not be read as ordinary steady-state compilation comparisons. Prioritize individual steady-state compilation workloads separately if compilation cost matters to an application.

## Coverage and limitations

The declared Java plan has 2,267 selected trials; all have normalized results after four tokenizer trials were retried with a null-mode harness fix. All 927 planned RE2 timing rows are present. Rust has 935 of 936: `RepeatedClassFindBenchmark.lines.1024` produces a different count because Rust's iterator suppresses an empty match immediately following a nonempty match. That row is excluded from comparison. Both native adapters needed an implementation of the planned `compileAndFind` operation before resuming. No memory collection was performed.

The RE2 and Rust collections were resumed after harness failures and may span different machine conditions. `initial-exit-status` and `rust-initial-exit-status` preserve those failures; the raw and resume outputs retain the measured segments. The workload plan and normalized records should be checked before using other comparisons. This is an incomplete, optimization-focused collection, not an update to the published benchmark report.
