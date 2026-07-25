# Test-overlap Recording Handoff

## Context

GitHub issue #592 asks for a reorganization of SafeRE's tests. The broad goals are to keep CI
within a reasonable time, reduce duplication, schedule exhaustive validation, and decide how to
use fuzzing.

This branch starts with duplication analysis. The intended population is every test or check that
CI invokes, including classes with `ExhaustiveTest` in their names, generated crosscheck tests,
work-counter tests, fuzz-seed replay, and module smoke tests. The standalone exhaustive sweep
programs are not currently invoked by CI and are therefore outside the immediate population.

The first implementation deliberately targets the largest facade-compatible fraction of CI.
Tests tied directly to package-private parser, compiler, program, or execution-engine APIs can be
missed. The main objective is to obtain high-value overlap data without first solving general JVM
instrumentation.

Two especially large JUnit classes currently belong to the per-PR CI population:
`CrossEngineExhaustiveTest` and `RE2ExhaustiveTest`. Their location in the main `safere` test tree
means Maven and CI treat them as ordinary tests even though their purpose and runtime are closer to
offline exhaustive validation. Before investing heavily in recording and aggregating their full
case spaces, evaluate moving their large matrices into the `safere-exhaustive` module and a
scheduled or manually dispatched workflow. Normal PR CI should retain a small, representative
smoke subset plus focused regression cases for previously discovered bugs. The overlap data can
help choose that subset, but the module/tier decision should not depend on completing a full
recording run first.

## Design Discussion and Decisions

Several possible approaches were considered:

- Static Java source analysis could extract literal patterns and parameter sources, but it would
  miss dynamically generated matrices, fuzz-seed decoding, and stateful control flow.
- Per-test code coverage can identify candidates but cannot establish semantic duplication.
- Mutation testing can establish unique fault-detection value, but it is too expensive for the
  first inventory.
- A Java agent could instrument the actual API, but it adds substantial complexity.

The selected approach follows `safere-crosscheck`: generate copies of existing tests and rewrite
their imports to a facade with the same public API. Unlike crosscheck, the recording facade
delegates only to SafeRE and records only calls made by test code.

Delegation is required. Sentinel return values would fail assertions and, more importantly, alter
control flow in loops, branches, matcher state sequences, and exception assertions. Delegating
once to SafeRE preserves the native test execution while remaining cheaper than crosscheck's
SafeRE-plus-JDK execution.

The existing crosscheck trace was not reused because crosscheck intentionally performs implicit
observations. For example, a successful `find()` triggers additional match-bound and capture
comparisons. Recording that trace would make a test that checks only the boolean result appear to
observe captures as well. The separate recording facade avoids that distortion.

## Implemented Structure

The new `safere-recording` Maven module is registered in the root reactor. It is not deployed.

The `record-public-api-tests` profile:

1. Copies eligible tests from `safere/src/test/java/org/safere`.
2. Moves the copies to `org.safere.recording.generated`.
3. Imports recording versions of `Pattern`, `Matcher`, `Utf8Input`, `Utf8Matcher`, and `Utf8Sink`.
4. Reuses the public-API structural selection maintained by `safere-crosscheck`.
5. Disables JUnit-level parallelism for reliable test attribution.
6. enables an auto-detected JUnit extension.
7. Runs the overlap reporter during Maven's `verify` phase.

At the time of implementation, 43 of the 82 `*Test.java` files in the main SafeRE test directory
are generated into the recording module. The excluded files are primarily internal API, explicit
engine, work-counter, or UTF-8 implementation tests. Public UTF-8 matching remains covered through
the recording UTF-8 facade and tests such as `RE2ByteSearchTest`.

### Facade behavior

`Pattern`, `Matcher`, and the UTF-8 facade classes:

- record typed method arguments;
- invoke the corresponding SafeRE operation exactly once;
- record the returned value or exception;
- preserve the original return value and exception behavior;
- assign stable process-local object IDs and call sequence numbers.

Mutable matcher operations record a stable `"this"` result instead of introspecting the returned
SafeRE matcher. This matters because SafeRE's matcher implements `MatchResult`; treating it as a
result snapshot and calling its accessors would create implicit observations and can throw after
state-resetting operations.

`toMatchResult()`, `results()`, and functional replacement results are wrapped so subsequent
`MatchResult` accessor calls made by test code are recorded. Stream elements from
`splitAsStream()` are also recorded as they are consumed.

### JUnit attribution

`RecordingTestExtension` uses JUnit's before- and after-test-execution callbacks to associate
events with the current JUnit unique ID. The reporter removes only parameterized invocation and
repetition suffixes, aggregating individual cases under their owning test-template method.

JUnit parallelism is disabled for a recording run. This permits a single globally visible active
test ID to be inherited conceptually by worker threads created inside an individual exhaustive
test. Facade object IDs still distinguish concurrently used matchers.

### Event format

Raw events are written to:

```text
safere-recording/target/recording/events.tsv
```

Each event contains:

```text
kind
Base64(JUnit unique ID)
object ID
sequence number
Base64(method)
Base64(typed arguments)
Base64(typed result)
Base64(exception)
```

Base64 keeps every event on one physical line even when patterns, inputs, or results contain tabs
or line terminators. The typed encoding distinguishes null, strings, integral and floating-point
values, booleans, characters, arrays, byte arrays, maps, facade objects, and opaque match results.
Strings retain their exact UTF-16 contents, including unpaired surrogates.

Maps are sorted before encoding so their fingerprints do not depend on iteration order. Function
objects are encoded as an opaque functional value rather than by their identity-dependent
`toString()`.

## Fingerprinting

Raw events are grouped by facade object ID into ordered lifecycles. A matcher lifecycle begins
with the pattern, flags, and input and then contains the exact stateful call sequence.

Two hashes are computed:

- The interaction fingerprint covers facade kind, ordered method names, and typed arguments.
- The execution fingerprint additionally covers returned values and exceptions.

SHA-256 is used only as a compact identifier. The canonical lifecycle is the source of truth and a
human-readable representative is included for reported overlaps.

The implementation does not normalize regex syntax. For example, `a`, `[a]`, and `(?:a)` receive
different input identities even if they happen to match the same language.

The report treats each test method's fingerprints as both a set and a multiset:

- set membership supports cross-test intersection and containment;
- multiplicity exposes repeated identical lifecycles within one test or generated matrix.

The report includes:

- executions, distinct fingerprints, and internal repetitions per test method;
- directional pairwise containment candidates;
- the most frequent exact overlaps with representative call traces.

An overlap is evidence for review, not permission for automatic deletion. A focused regression may
remain valuable as readable documentation even when a broad matrix contains the same interaction.

## Current Validation

The following validation completed successfully:

```bash
mvn -pl safere-recording test -q

mvn -pl safere-recording -am \
  -Precord-public-api-tests \
  -DskipTests package -q

mvn -pl safere-recording -am \
  -Precord-public-api-tests \
  -Dtest=PatternPredicateTest,AlternationCaptureSemanticsTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  verify -q

mvn -pl safere-recording -am \
  -Precord-public-api-tests \
  -Dtest=MatcherTest,QuantifiedCaptureSemanticsTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test -q

mvn -pl safere-recording -am \
  -Precord-public-api-tests \
  -Dtest=PatternSplitAsStreamTest,MatcherResultsStreamTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test -q

mvn -pl safere-recording -am \
  -Precord-public-api-tests \
  -Dtest=RE2ByteSearchTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test -q

mvn spotless:check -q
```

The `MatcherTest` plus `QuantifiedCaptureSemanticsTest` sample produced:

```text
53,303 recorded events
9,575 facade object lifecycles
7,744 distinct execution fingerprints
256 test methods
```

That sample exposed substantial internal repetition in the large generated quantified-capture
matrix and concrete 100% lifecycle-containment candidates. These are candidates for human review,
not yet conclusions about removal.

During validation, the initial result encoder incorrectly inspected returned SafeRE matchers as
`MatchResult` values. This caused state-resetting calls such as `region()` and `reset()` to throw
from recorder-internal `group()` access. The encoder now treats returned match objects as opaque,
and all affected `MatcherTest` cases pass through the facade.

A full `mvn -pl safere-recording -am verify` was intentionally stopped because `-am` also ran the
entire native SafeRE suite, including its long exhaustive JUnit classes. The focused commands above
provide the relevant validation for this new module.

## Generated Data

Raw events and Markdown reports are generated under `safere-recording/target/`, which is ignored by
Git. They are not committed.

Regenerate a focused report with:

```bash
mvn -pl safere-recording -am \
  -Precord-public-api-tests \
  -Dtest=MatcherTest,QuantifiedCaptureSemanticsTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  verify
```

Then inspect:

```text
safere-recording/target/recording/overlap-report.md
```

## Known Limitations

### Reporter memory usage

`OverlapReporter` currently reads the complete raw event file and retains reconstructed lifecycle
and fingerprint maps in memory. This is suitable for focused groups of CI test classes but not yet
safe for the complete `CrossEngineExhaustiveTest`, which can execute millions of cases.

Do not run the entire generated suite or `CrossEngineExhaustiveTest` through `verify` until the
external-sort aggregation described below is implemented.

### Public-facade coverage only

Internal parser, compiler, instruction, NFA, DFA, OnePass, and work-counter tests do not go through
the facade. This is an accepted first-stage limitation. The generated source selection is explicit
in `safere-recording/pom.xml` and should remain visible rather than silently ignoring exclusions.

### Operations returning external objects

Match results returned through matcher APIs are wrapped and observed. Other APIs that hand control
to objects outside the facade may still record less detail than an agent could. Serialization is
one example: calls to `ObjectOutputStream` are outside the facade even though serialization of the
recording `Pattern` preserves SafeRE behavior.

### CI integration

The recording profile is currently an on-demand analysis tool. It is not part of `.github/workflows/ci.yml`
and should not become a required PR check until its runtime, storage, and report stability are
understood.

## Next Steps

### 1. Reconsider the exhaustive JUnit test tier

Decide whether `CrossEngineExhaustiveTest` and `RE2ExhaustiveTest` should remain ordinary
per-commit tests. A likely split is:

- keep small deterministic smoke matrices and readable bug regressions in `safere` for every PR;
- move the complete generated matrices or corpus replay into `safere-exhaustive`;
- run the full forms on a schedule and through manual workflow dispatch;
- upload summaries and actionable divergence artifacts from scheduled runs.

Preserve one source of truth for generators and cases so the PR smoke subset and scheduled full
run cannot drift semantically. Migration should also preserve test attribution and make it obvious
which full validation has or has not run for a PR.

This decision may reduce the immediate need to make the overlap reporter consume the entire
multi-million-case matrices. The recorder should still support representative subsets so it can
identify overlap between ordinary regressions and the exhaustive generators.

### 2. Make aggregation disk-scalable

Implement an external-sort pipeline before recording the largest exhaustive JUnit class:

1. Sort raw events by object ID and sequence number.
2. Stream one lifecycle at a time and write compact `(execution hash, origin)` records.
3. Sort those fingerprint records by hash and origin.
4. Stream equal-hash groups to compute execution counts, per-origin distinct counts, internal
   repetitions, pairwise intersections, and containment.
5. Retain only the highest-ranked overlap groups in memory.
6. Make a final pass over sorted lifecycles to recover representative traces for those hashes.

GNU `sort` is available in the current Linux development and CI environments, but a bounded Java
external merge sort would be more portable. Whichever implementation is chosen must pass exact
typed records and must not use probabilistic fingerprints for deletion decisions.

### 3. Separate setup overlap from behavioral overlap

The current report includes compile-only pattern lifecycles. This is useful for identifying
repeated compilation coverage, but it can dominate exact-overlap lists when several test methods
share a parameter source.

Add separate report sections for:

- compile/setup-only overlap;
- matcher interaction overlap;
- complete stateful lifecycle overlap;
- input-only overlap.

Containment used for potential test removal should prioritize matcher interactions over repeated
setup.

### 4. Improve coverage inventory

Generate a durable included/excluded class table as part of the report. For each excluded CI test,
state whether it is:

- internal API;
- explicit engine-path validation;
- work-counter/scaling;
- unsupported facade surface;
- another intentional category.

This will quantify the fraction of CI covered by the recording analysis.

### 5. Add timing data

Parse Surefire XML or add timing callbacks so containment candidates can be ranked by estimated CI
savings. Report both test-method runtime and generated invocation counts. Avoid inferring savings
solely from event counts.

### 6. Audit initial candidates

Regenerate the `MatcherTest` and `QuantifiedCaptureSemanticsTest` report and inspect:

- 100% containment pairs;
- generated quantified-capture internal repetitions;
- cases repeated between `MatcherTest` regression sections and systematic capture matrices.

For each candidate, verify whether the smaller test adds readable regression intent, a distinct
JDK oracle comparison, performance/scaling assertions, or source traceability before removing or
merging it.

### 7. Expand to other CI jobs selectively

After the main SafeRE public-API report is useful:

- generate recording copies of facade-compatible fuzz targets and decode checked-in seeds through
  normal execution;
- assess whether `safere-exhaustive` CLI smoke tests contain public regex interactions worth
  recording;
- keep internal work-counter and explicit engine tests in separate analysis categories rather
  than forcing them through this facade.

### 8. Decide long-term automation

Once reports are scalable and stable, choose whether to run them:

- manually for issue #592;
- on a schedule;
- only when test sources or generators change.

The tool should remain advisory. CI should not fail merely because two tests overlap unless the
project later defines a narrow, deterministic duplication policy.
