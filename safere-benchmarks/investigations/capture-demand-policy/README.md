# Capture-demand producer policy

This investigation addresses [#1075](https://github.com/eaftan/safere/issues/1075):
how capture-use history should influence producer selection before DFA rejection.
It follows the opt-in investigation convention introduced in
[#1127](https://github.com/eaftan/safere/pull/1127). Its sources, tests, inputs,
protocol settings, and historical evidence are separate from the release benchmark
corpus and recurring reports.

## Workloads and provenance

The policy matrix is constructed data. Its two regexes come from the existing
`ApplicationBenchmark.stackTraceExtract` and
`Issue481ScalingBenchmark.schemeExtract.128` workloads. The inputs are newly
constructed valid/invalid frames and links padded with ASCII prose or multibyte
text. They are not production traces. Historical measurements also used the
existing four-frame stack-trace and scheme-extraction documents as controls.

This directory's `benchmark-data.json` owns patterns, input recipes, cursor reuse,
protocol parameters, and expected checksums. Base workloads describe a find and
capture-participation query using the existing schema. The specialized protocol
selects consumption through `configuration.investigation`: `text1`, `text4`,
`offset1`, `phase1`, or `boolean1`. This avoids introducing an investigation-only
operation or UTF-8 capture-text capability into the release suite.

Each invocation scans three phases, each with the declared number of rounds over
16 documents. Hit rate (all documents or one in 16) is independent of capture-read
frequency conditional on a hit. `text4` hashes capture text on one in four hits;
`phase1` reads captures in the first and third phases and uses Boolean results in
the middle phase. `offset1` reads native bounds. String input and pre-existing
UTF-8 bytes are prepared before timing; UTF-8 capture decoding is timed when used.

Retained means a cursor per input, with one shared Pattern, not one cursor rotating
through `reset(newInput)`. The default rounds produce 1,536 documents per JMH
invocation. Divide the cycle time by that number for time per document. Validation
and timing use the same protocol and cursor-lifetime factory.

## Explicit invocation

Choose an output directory that does not exist:

```bash
python3 safere-benchmarks/investigations/capture-demand-policy/run.py --smoke \
  benchmark-results/capture-policy-smoke \
  CapturePolicyBenchmark.scheme.ascii.128.rare.retained@safere-string \
  CapturePolicyBenchmark.scheme.ascii.128.rare.retained@safere-utf8 \
  --consumption text1 phase1
```

Use standard mode for screening or `--long` for confirmation. Smoke results check
execution only. Add `--shared` to use four workers sharing a Pattern, split evenly
between selected consumption and Boolean-only consumers. Their result is a worker
average, not separate role latencies. Raw JSON, logs, and exact commands are saved.
The shared wrapper owns timing settings and runs JMH sequentially.

The equivalent wrapper invocation is:

```bash
./run-java-benchmarks.sh --smoke --fastbuild \
  --investigation capture-demand-policy CapturePolicyBenchmark.run -- \
  -p captureTrial=CapturePolicyBenchmark.scheme.ascii.128.rare.retained@safere-string \
  -p consumption=text1 -foe true
```

`--investigation` requires an explicit filter and rejects `--declared`. Its corpus
and build artifacts live under `target/investigations/capture-demand-policy/`.
Normal builds and collections use the module-root data and exclude these classes.

Run protocol tests explicitly:

```bash
mvn -pl safere-benchmarks -am -Dbenchmark.investigation=capture-demand-policy \
  -Dexec.skip=true -Dtest=CapturePolicyProtocolTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## Policies and historical evidence

[Prototype patches](policies/README.md) preserve the five alternatives to the
original shared sticky preference: DFA-first, local sticky history, a local lease,
and two Pattern-seeded local leases. No alternative is applied by the runner or
adopted as production policy.

[Historical results](results/README.md) preserve the completed comparisons using
the original harness at `2c562a0a`. The no-aging ownership control also obtains the
large rejection benefit; seeding history retains rejection costs. DFA-first trades
large rejection gains for material successful-extraction costs, with different
results for String and UTF-8. The evidence does not establish a better general
default or justify selecting an aging window from these fixtures.

The isolated harness is a reorganization of that protocol, with its cycle checksums
checked against the historical declarations. Its source layout and call structure
have changed, so the historical timings are not claimed as measurements of this
new harness. This migration needs execution and isolation checks, not a repeat of
the full performance study.

For comparisons, use clean isolated snapshots with identical harnesses, inputs,
wrapper settings, and JVMs. Keep new captures under ignored `benchmark-results/`.
Broader application mixes, a single cursor with rotating input, and full
reset/region/bounds/replacement transitions remain outside the measured evidence.
