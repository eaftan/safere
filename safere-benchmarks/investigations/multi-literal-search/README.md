# Multi-literal search controls

These constructed controls support [opportunity 5 of #1185](https://github.com/safere/safere/issues/1185).
They vary keyword families, early/late/missing matches, small/large inputs, dense initial
bytes, and ASCII/multibyte padding. Expected Boolean results are declared alongside the
workloads. Patterns and materialized inputs are identical across revisions and engines.
Compilation and input encoding occur outside the search timer.

Use the shared runner with its default scan provider and standard measurement settings:

```bash
./run-java-benchmarks.sh --fastbuild --investigation multi-literal-search \
  CrossEngineBenchmark.run -- \
  -p crossEngineTrial=MultiLiteralSearch.directions.ascii.late.262144@safere-string,MultiLiteralSearch.directions.ascii.late.262144@safere-utf8
```

Compare the same trials on the baseline with these benchmark-only declarations applied.
Use `--long` to confirm close or surprising comparisons. Complement these cases with the
regular corpus's multi-literal scaling, delayed alternation, prose, shared-prefix, near-miss,
repeated-find, replacement, and compilation controls. These focused controls do not establish
corpus-wide performance or performance with the experimental vector provider. Keep raw
measurements and profiles local.
