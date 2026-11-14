# Capture recovery and anchored eligibility

This is the follow-up to [issue #1075](https://github.com/eaftan/safere/issues/1075).
It asks whether cheaper capture replay changes the tradeoff behind the 512-native-unit
capture-demand eligibility limit for `matches()`, `lookingAt()`, and start-anchored `find()`.
It is an opt-in investigation, excluded from release collections.

[Results and assessment](results/README.md) records the measured tradeoffs and the
decision to retain 512. [Prototype patch](policies/README.md) preserves the runtime
candidate independently of the separate implementation PR.

The corpus owns both patterns, exact input recipes, and hit/read schedules. The stack-frame
expression is derived from the release suite's `ApplicationBenchmark.stackTraceExtract`,
with multiline mode removed so `^` anchors at the input start. Both diagnostic expressions
omit the end anchor to keep end-anchored reverse probing from bypassing the cutoff
at larger sizes. Rejections retain the required literals and some digits, and fail
inside the expression. The second expression is a
simple whitespace/key/integer parser that serves as a OnePass control. Public
operation diagnostics show that the prepared OnePass runner handles all three
operations at the measured sizes; changing this capture-demand limit does not
change those engine paths. These are constructed
inputs, not application traffic. Whitespace padding produces exact ASCII lengths: String
UTF-16 units and UTF-8 bytes agree. The study does not establish a multibyte cutoff.

Each invocation scans a declared batch with one retained cursor for the positive input and
one for the rejection input, sharing a compiled Pattern. Every cursor resets before use.
`text` reads group 1 on every hit; `mixed` reads on one in four hits; `rare` has one hit per
batch and reads it; `reject` has no hits; `boolean` has all hits and no timed capture reads.
All profiles prime actual capture-text consumption before measurement, including rejection
and Boolean controls. They measure steady state after capture demand, not cold Patterns.
The protocol validates independent tasks against JDK and then creates a fresh measurement
Pattern. Pattern compilation, input creation, and encoding are outside timing; capture-text
materialization is inside timing. JMH reports time per batch, not per document.

Use the shared wrapper, which owns all timing settings:

```bash
./run-java-benchmarks.sh --fastbuild --investigation capture-recovery \
  '^org\.safere\.benchmark\.CaptureCutoffBenchmark\.run$' -- \
  -p cutoffTrial=CaptureCutoffBenchmark.stack.512@safere-string \
  -p operation=matches,lookingAt,find -p profile=text,mixed,rare,reject,boolean \
  -foe true -rf json -rff /tmp/capture-cutoff.json
```

Use standard mode to screen and `--long` for confirmation. For cutoff comparisons, use
isolated source snapshots differing only in `Matcher.CAPTURE_DEMAND_TEXT_LIMIT`, with
identical frozen harness/data and explicit source hashes and patches. Do not add a runtime
policy switch to production. Compare the existing 512 limit with disabled preference (0)
and extended eligibility; the BitState capacity guard still applies. Keep before/after
recovery comparisons separate from comparisons that also change the limit.

Protocol tests run explicitly:

```bash
mvn -pl safere-benchmarks -am -Dbenchmark.investigation=capture-recovery \
  -Dexec.skip=true -Dtest=CaptureCutoffBenchmarkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The regular corpus adds only `CaptureRecoveryBenchmark`: first-frame text extraction
(ASCII and a multibyte filename),
one-in-four participation queries, primed scheme rejection, and anchored text extraction
at 511/512/513 units. Existing `ApplicationBenchmark.stackTraceExtract` retains the full
multi-frame extraction case. The mixed standing case queries capture participation rather
than materializing text; the opt-in protocol measures mixed capture text. Neither promotes
the previous full synthetic policy matrix into the release suite.

The driver records exact sources, commands, source limit, expected membership, and raw results:

```bash
python3 safere-benchmarks/investigations/capture-recovery/run.py \
  benchmark-results/capture-cutoff-current --family stack --sizes 511 512 513 \
  --profiles text mixed rare reject
```

Each output directory must be new. Source snapshots in `sources.tar.gz` include the selected
corpus and harness; `command.json` identifies every archived source by SHA-256. A successful
process is also checked for complete, unique result membership. A separate JVM runs
`CaptureCutoffPaths` after timing and saves public operation diagnostics in `paths.json`;
no timing JVM installs a diagnostics listener. Add `--jfr` for a diagnostic profile
collection, whose timings must be kept separate from comparison evidence.
