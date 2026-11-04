# String region profile history

This investigation addresses [#1119](https://github.com/eaftan/safere/issues/1119):
whether String region use changes the performance of subsequent ordinary `find()`
operations in the same JVM. It is an investigative benchmark, excluded from the
regular corpus, collection plan, and recurring reports.

This draft branch also contains a diagnostic DFA specialization: ordinary String
input enters a separate forward-search method from regions and UTF-8 input. It
preserves a successful experiment in isolating JIT receiver profiles. The method
duplicates roughly 500 lines of search logic and is a demonstrator, not a proposed
production design. The PR description records its measured benefits and limits.

Run the paired experiment from the repository root, choosing an output directory
that does not exist:

```bash
python3 safere-benchmarks/investigations/string-region-profile/run.py --long \
  benchmark-results/region-profile-literal32 \
  StringRegionProfile.literal.32.plain StringRegionProfile.literal.32.region
```

Omit `--long` for standard screening. Use `--smoke` only to check execution;
smoke timings are not performance evidence. Substitute `nullable` and/or `4096`
in both workload IDs for the other pairs. The runner invokes
`run-java-benchmarks.sh --investigation string-region-profile` and retains raw
JMH JSON, logs, and exact commands. The shared wrapper owns fork, warmup, and
measurement settings; benchmarks run sequentially.

During each JMH warmup invocation, both histories search the target and an
equivalent companion. In `plain`, both use independent String input; in `mixed`,
the companion exposes exactly the same text through a region of a padded String.
Warmup and measurement share a JVM. Measurement performs only the ordinary search.
Fresh forks separate the two histories. Each pair runs in `plain,mixed` order and
then in `mixed,plain` order.

Patterns, input lengths, and input recipes are declared in this directory's
`benchmark-data.json`. The pairs cover literal and nullable search starts at 32
and 4,096 UTF-16 code units with a match near the end. Input preparation and pattern
compilation occur outside timing. The recorded comparisons use a new matcher per
search. The protocol measures persistent history effects after mixed warmup;
continuous region traffic, transient first-search latency, absent matches, UTF-8
contamination, and retained-matcher histories are not covered by those results.

For revision comparisons, use isolated snapshots with identical investigation
sources, data, build configuration, and wrapper settings. Record the exact production
revisions and any benchmark-only overlays. Use separate diagnostic JIT/JFR or CPU
profiling runs to investigate a reproduced difference; exclude their timings from
unprofiled comparisons.

[Historical results](results/README.md) preserve the initial comparison of the
original #1095 base and its repaired head. These are a dated investigation snapshot,
not results expected in regular benchmark reports. They predate the specialization
on this branch. Later disassembly confirmed that region use changes C2 compilation
of the shared DFA method, including its code size and loop register allocation.
Isolating the full String search recovers much of the original slowdown; smaller
refactorings did not preserve that gain. The contribution of individual machine
instructions remains unresolved, and some input histories still slow down.
