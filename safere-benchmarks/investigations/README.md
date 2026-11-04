# Investigative benchmarks

This directory preserves focused performance experiments so they can be reproduced
and reused during an investigation. They are separate from the durable benchmark
corpus and are not scheduled by regular collections, included in recurring reports,
or expected to retain a stable reporting identity.

Each investigation has its own directory containing:

- a README describing the question, linked issue, protocol, invocation, and limitations;
- `benchmark-data.json` for its patterns and input recipes, using the existing schema;
- `src/main/java/` for any specialized harness and `src/test/java/` for protocol checks;
- an optional runner for experiment-specific ordering or orchestration;
- historical results with source revisions, environment, exact commands, and raw evidence.

Use the shared Java wrapper for timing settings and builds:

```bash
./run-java-benchmarks.sh --long --fastbuild \
  --investigation capture-demand-policy CapturePolicyBenchmark.run -- \
  -p captureTrial=CapturePolicyBenchmark.stack.ascii.128.all.retained@safere-string \
  -p consumption=text1
```

`--investigation NAME` enables only that directory's Java and test sources through
`-Dbenchmark.investigation=NAME` and materializes its data under
`target/investigations/NAME/corpus`. Investigation classes, tests, and JARs use
`target/investigations/NAME/build`, keeping them out of regular build outputs.
An explicit benchmark filter is required;
`--declared` cannot be combined with this option. Normal wrapper invocations clean
and rebuild the benchmark artifact without investigative sources and use the
regular corpus. Historical investigation results are not inputs to the collection
or report normalizers.

To run investigation tests explicitly:

```bash
mvn -pl safere-benchmarks -am -Dbenchmark.investigation=capture-demand-policy \
  -Dexec.skip=true -Dtest=CapturePolicyProtocolTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Keep new captures under ignored `benchmark-results/` by default. Preserve selected
historical evidence with an investigation when requested, identifying raw outputs
and derived summaries and recording checksums. Historical results describe their
recorded revisions and environment; they are not current library performance claims.

Specialized protocols keep their settings under `configuration.investigation`,
validated by their own harness, without adding regular collection operations.
Promotion into the durable corpus is a separate decision based on recurring value
and representative workloads. An investigation can remain useful without being
promoted.

## Preserved investigations

- [Capture-demand producer policy](capture-demand-policy/README.md): controlled
  capture consumption, matcher ownership, and producer selection (#1075).
