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
  --investigation string-region-profile StringRegionProfileBenchmark.find -- \
  -p plainTrial=StringRegionProfile.literal.32.plain@safere-string \
  -p regionTrial=StringRegionProfile.literal.32.region@safere-string \
  -p history=mixed
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
mvn -pl safere-benchmarks -am -Dbenchmark.investigation=string-region-profile \
  -Dexec.skip=true -Dtest=StringRegionProfileBenchmarkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Keep new captures under ignored `benchmark-results/` by default. Preserve selected
historical evidence with an investigation when requested, identifying raw outputs
and derived summaries and recording checksums. Historical results describe their
recorded revisions and environment; they are not current library performance claims.

Specialized protocols are allowed here without extending the regular collection
schema. Promotion into the durable corpus is a separate decision based on recurring
value and representative workloads. An investigation can remain useful without
being promoted.

## Preserved investigations

- [String region profile history](string-region-profile/README.md): ordinary search
  latency after plain-only versus mixed String-region warmup in one JVM (#1119).
