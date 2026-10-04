# SafeRE Fuzz Tests

This module contains Jazzer fuzz targets for SafeRE. Targets use broad or strict JDK comparisons, SafeRE properties/model expectations,
or robustness checks. An assertion signals a finding for that target's domain.
See [target domains and enrollment](TARGETS.md) for the inventory, exclusions,
and graduation policy, and [the OSS-Fuzz adapter](oss-fuzz/README.md) for hosted
integration. Regression replay is an execution mode, not a separate bucket.

## Targets

The manifest is authoritative for enrollment, including strict pattern/state,
engine/representation/model and robustness targets. `WorkBoundFuzzer` uses a
separate instrumented build and is not enrolled in the ordinary hosted build.
The existing targets below remain available for exploratory differential work:

- `CompileFuzzer` fuzzes compile/reject behavior.
- `ParserCompatibilityFuzzer` fuzzes grammar-biased compile and membership compatibility.
- `CharacterClassExpressionFuzzer` fuzzes JDK character-class expression syntax.
- `EscapeSyntaxFuzzer` fuzzes escape syntax and escaped literal compatibility.
- `DialectSyntaxFuzzer` fuzzes non-JDK-looking syntax and dialect boundary cases.
- `ParserStackSafetyFuzzer` fuzzes parser nesting depth and stack safety.
- `CaptureSemanticsFuzzer` fuzzes quantified capture semantics and capture-observing APIs.
- `MatchFuzzer` fuzzes `matches()`, `lookingAt()`, `find()`, and `find(int)`.
- `FindSequenceFuzzer` fuzzes stateful matcher API call sequences.
- `ReplacementFuzzer` fuzzes replacement APIs.
- `SplitFuzzer` fuzzes `split` and `splitWithDelimiters`.
- `RegionBoundsFuzzer` fuzzes regions and anchoring/transparent bounds.
- `DeferredRegionCaptureFuzzer` forces deferred NFA captures across region bounds and matcher reuse.
- `UnicodeFuzzer` biases input strings toward Unicode boundary cases.
- `EmojiZwjGraphemeFuzzer` checks a small grapheme-property alphabet against an independent
  UAX #29 reference, including GB11 interruptions and Prepend prefixes.
- `Utf8InputFuzzer` mixes focused JDK comparisons, representation checks, and byte-window robustness.

## Regression Mode

Without `JAZZER_FUZZ`, Jazzer runs each target as a JUnit parameterized test
over the empty input and the checked-in seed corpus. Seed inputs live under
`src/test/resources/<package-path>/<FuzzerClass>Inputs/<methodName>/`.
Most targets use `org/safere/fuzz`; targets needing package-private engine controls use `org/safere`.

```bash
mvn -pl safere-fuzz -am test
```

Run one target:

```bash
mvn -pl safere-fuzz -am -Dtest=MatchFuzzer -Dsurefire.failIfNoSpecifiedTests=false test
```

## Fuzzing Mode

Set `JAZZER_FUZZ=1` to run coverage-guided fuzzing. Jazzer runs one fuzz test
per Maven invocation, so select a single target with `-Dtest`.

The Maven configuration disables Jazzer's `RegexInjection` sanitizer for these
targets. SafeRE fuzzers intentionally compile generated patterns with both
SafeRE and `java.util.regex`; sanitizer findings on the JDK oracle are noise for
this differential-testing workflow.

```bash
JAZZER_FUZZ=1 mvn -pl safere-fuzz -am -Dtest=MatchFuzzer \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Limit a local run with Jazzer options:

```bash
JAZZER_FUZZ=1 mvn -pl safere-fuzz -am -Dtest=MatchFuzzer \
  -Dsurefire.failIfNoSpecifiedTests=false -Djazzer.max_duration=2m test
```

Collect multiple findings from one run:

```bash
JAZZER_FUZZ=1 mvn -pl safere-fuzz -am -Dtest=CharacterClassExpressionFuzzer \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Djazzer.max_duration=30m \
  -Djazzer.keep_going=10 \
  -Djazzer.internal.arg.0=jazzer \
  -Djazzer.internal.arg.1=-artifact_prefix=target/fuzz-reproducers/ \
  -Djazzer.reproducer_path=target/fuzz-reproducers \
  test
```

`jazzer.keep_going` tells Jazzer to keep fuzzing after distinct findings instead
of stopping at the first one.

The same settings are available through the helper script, which can run
multiple fuzz targets sequentially:

```bash
# Default: explicitly enrolled targets only.
safere-fuzz/scripts/run-fuzz-test.sh
# Broad discovery must be selected explicitly.
safere-fuzz/scripts/run-fuzz-test.sh --suite broad
safere-fuzz/scripts/run-fuzz-test.sh CharacterClassExpressionFuzzer MatchFuzzer
safere-fuzz/scripts/run-fuzz-test.sh --max-duration 10m --keep-going 5 MatchFuzzer
```

The helper records commit, worktree status, JDK version, selected targets, and
run options in `run.json`. Each target gets its own raw reproducer directory.
`FuzzSupport` mismatch reports also write losslessly escaped JSON context when
`-Dsafere.fuzz.findingsDir=...` is set; the helper sets this automatically. Matcher
history is bounded and explicitly marks omitted events. The raw Jazzer input is
the complete replay source when history is truncated. Other target assertions
remain in the log and raw reproducer artifacts.

The helper script records each fuzzer's combined stdout/stderr stream under
`safere-fuzz/target/fuzz-logs/<run-id>/<Fuzzer>.log`. Use these logs alongside
Surefire XML reports when triaging `jazzer.keep_going` runs, since the XML does
not always preserve every console detail needed to map findings to crash inputs.
When one fuzzer exits with findings, the helper still runs the remaining
requested fuzzers and reports the failed targets at the end.

## Findings

First distinguish SafeRE failures, intentional differences, and unavailable JDK
oracles. Preserve the raw input and both outcomes before minimizing. Use the
project's divergence workflow to assess compatibility; do not infer an exemption
from similarity to a known finding.

- For a SafeRE bug, add a focused regression, fix the cause, and rerun the affected
  tests and fuzz target in regression mode.
- For an approved intentional difference, add an explicit SafeRE model regression
  and keep the exploratory reproduction. Do not put a known failing differential
  input in the passing seed corpus or add a semantic waiver to a strict target.
- A new failure in an enrolled target needs investigation, not automatic demotion
  or suppression. Document any corrected domain/property and preserve coverage.

The runner retains nonzero status for failures and labels logs with observed
finding evidence versus execution failure. The label is diagnostic, not a claim
that a semantic finding is the only failure in the run. No automatic similarity
classification or known-finding suppression is performed.

Validate the manifest and exporter independently of Java:

```bash
python3 -m unittest discover -s safere-fuzz/scripts
python3 safere-fuzz/scripts/targets.py --suite oss-fuzz --format tsv
```

Target methods and the audit are recorded in [targets.json](targets.json) and
[TARGET_AUDIT.md](TARGET_AUDIT.md). Selecting `SplitFuzzer` runs each of its fuzz
methods; `SplitFuzzer#repeatedClassSplits` selects one. The strict selection now
includes the finite existing priority family as `StrictPriorityFuzzer`.

Exploratory runs retain `run.json` (commit, dirty status, JDK and selectors),
per-method logs and raw Jazzer reproducers. Shared-comparator JSON findings also
record target, commit, reproducer directory, losslessly escaped decoded inputs,
both outcomes and bounded operation context. Direct assertions in legacy mixed
targets retain their assertion context in logs and the raw reproducer instead.
Manual Maven runs should set `safere.fuzz.target` and `safere.fuzz.commit` when
archiving standalone JSON; otherwise these fields explicitly indicate missing
run provenance. Keep the entire run directory together. Reviewed differences
are replayed through the separate [intentional reproduction registry](src/test/resources/intentional-reproductions/README.md).

The runner sets both libFuzzer’s `-artifact_prefix` (raw failing inputs) and
Jazzer’s `reproducer_path` (Java reproducers) to the per-method archive directory.
When running Maven manually, create that directory before using the options above.

## Revised policy and instrumented checks

The [current plan](../design/FUZZING_POLICY_IMPLEMENTATION_PLAN.md) incorporates
Unicode subjects, reviewed API sequences, engine-path equivalence and observed
JDK-defect exceptions with canaries. Strict grammar restrictions apply to syntax,
not to an ASCII-only input alphabet. The heuristic capture waiver and whole-input
grapheme suppression are removed from broad comparisons.

Run instrumented NFA work bounds with the manifest-aware runner:

```bash
safere-fuzz/scripts/run-fuzz-test.sh --max-duration 30s WorkBoundFuzzer
```

For regression replay:

```bash
mvn -pl safere-fuzz -am -Pwork-counters -Dtest=WorkBoundFuzzer -Dsafere.fuzz.workCounters=true -Dsurefire.failIfNoSpecifiedTests=false test
```

The exploratory workflow schedules weekly broad runs after merge. Dispatch it
before each release, or run `scripts/run-fuzz-test.sh --suite broad` locally.
Review the archived findings manually; no automated issue filing is configured.
Execution signals in finding JSON help identify exercised intentional paths but
never classify or suppress a failure on their own.
