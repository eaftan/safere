# Default UTF-8 literal scanning

Focused controls for [opportunity 7 in #1185](https://github.com/safere/safere/issues/1185).
The motivating workloads are `Utf8MatchingBenchmark.literalScan.*` in the regular corpus.
Use the default provider; the experimental Vector provider is outside this investigation.

The shared runner prepares compiled patterns and UTF-8 inputs outside timing and consumes the
operation's result. Boolean expectations and capture bounds are validated by the shared harness.
Input sizes in IDs are UTF-16 units; multibyte recipes produce more UTF-8 bytes.

```bash
./run-java-benchmarks.sh --fastbuild --investigation utf8-literal-scan \
  CrossEngineBenchmark.run -- \
  -p crossEngineTrial=Utf8LiteralScan.randomMiss.32768@safere-utf8

./run-java-benchmarks.sh --fastbuild --investigation utf8-literal-scan \
  SpecializedBenchmark.run -- \
  -p specializedTrial=Utf8LiteralScan.captureLate@safere-utf8
```

Measure identical declarations on baseline and candidate, sequentially, with the standard runner.
Use `--long` to confirm close or important comparisons. Keep raw measurements and profiles local.

Controls cover setup and short-tail costs, early/late/absent literals, scaling, dense endpoint
candidates, verification-budget fallback, multibyte text and literals, prefix acceleration,
capture bounds, repeated finds, replacement, single-byte dispatch, and long-literal dispatch.
These paths can regress through changes to filtering, verification, or shared scan selection.
The capture-bounds operation uses `SpecializedBenchmark`; all other controls use
`CrossEngineBenchmark`.
