# Fixed-offset candidate filtering

Investigation for #1185, opportunity 6. Dense interior literals can dominate
multi-offset filtering even when potential leading characters are rare.

The shared runner covers tiny and larger rejection/success inputs, early hits,
dense leading characters, sparse separators, repeated finds, capture text,
replacement, Unicode, a second separator and multiple leading characters.
The wide leading-class case exercises the fallback.

Run both representations with the default provider, selecting trial IDs from
`benchmark-data.json`:

```bash
./run-java-benchmarks.sh --fastbuild --investigation fixed-offset-filter \
  CrossEngineBenchmark.run -- \
  -p crossEngineTrial=FixedOffsetFilter.colonLate.32768@safere-string
```

Compare identical declarations on baseline and candidate, sequentially. Inputs
and patterns are prepared before timing; results are consumed and validated by
the shared operation runner. Focused profiles and measurements stay local.
