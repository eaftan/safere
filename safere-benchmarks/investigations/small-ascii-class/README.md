# Small ASCII class scanning

Focused controls for [opportunity 8 in #1185](https://github.com/safere/safere/issues/1185).
The regular corpus supplies the mixed-Unicode size grid, pair/triple scans, early
hits, dense iteration, and sparse iteration. This investigation adds ASCII misses
and late hits, nearby nonmembers, alternate narrow and wide triples, tiny setup,
capture-bearing and suffix-verification callers, repeated finds, and replacement.
These exercise filter acceptance, rejection, and fallback without changing the
operation or result consumption of the motivating workload.

Use the shared runner with the default provider, for example:

```bash
./run-java-benchmarks.sh --fastbuild --provider default \
  --investigation small-ascii-class CrossEngineBenchmark.run -- \
  -p crossEngineTrial=AsciiClassScan.nearMiss.262144@safere-utf8
```

Run each selected trial on both revisions with identical declarations. The
patterns and pre-existing inputs are prepared outside measurement; the shared
runner validates and consumes each operation's result. Sizes name UTF-16 units;
inspect the resolved manifest for UTF-8 byte sizes. These are diagnostic controls,
not additions to the regular reporting corpus. Keep timing results and profiles
local.
