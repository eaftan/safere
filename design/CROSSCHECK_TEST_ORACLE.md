# Crosscheck And Test Oracle Design

## Problem

SafeRE's core promise is that it is a drop-in replacement for
`java.util.regex` where the regex language is regular, while preserving
linear-time execution.  That promise is only useful if the test suite can detect
when either side of the contract drifts.

Recent issues in this area are not all production semantic bugs, but they show
how bugs can escape:

- #223: disabled crosscheck coverage mixed intentionally non-comparable tests
  with cases that still needed JDK comparison.
- #231 and #235: timing-based scaling tests were noisy because they depended on
  wall-clock thresholds rather than algorithmic behavior.
- #241: overlapping test commands made it unclear which validation surface was
  required for a change.
- #252: a possible JUnit migration could silently change coverage unless the
  generated and focused suites remain accountable.

The common problem is not one missing test.  It is the absence of an explicit
oracle policy: which behavior is compared with the JDK, which behavior is tested
with internal invariants, which tests may be disabled for crosscheck, and how
linear-time regressions are detected without fragile timing assertions.

## Design Principle

Every semantic claim needs a stable oracle.

SafeRE should use the JDK as the oracle for public API behavior whenever a test
case is comparable.  It should use internal invariant oracles when the JDK is not
the right comparator, such as engine-path equivalence, trace coverage,
transition inventory, or linear scaling shape.  A test should be excluded from
generated public API crosscheck only when the exclusion states why the JDK oracle
is not applicable.

The test suite should avoid wall-clock pass/fail thresholds for ordinary
correctness.  Scaling tests should check growth behavior, operation counts,
bounded state, or relative algorithmic shape where possible.  Benchmarks remain
the tool for reporting performance, not for proving correctness.

## Oracle Categories

Tests should fit into one of these categories.

| Category | Oracle | Examples |
| --- | --- | --- |
| Public JDK-compatible behavior | Compare SafeRE and `java.util.regex` on the same operation, inputs, flags, regions, and replacement text. | `matches`, `find`, `lookingAt`, groups, replacements, split, predicates |
| Public documented divergence | Assert SafeRE behavior directly and document why JDK comparison is not meaningful. | unsupported non-regular syntax, SafeRE-only APIs |
| Cross-engine equivalence | Compare SafeRE engine paths against each other, with public results as the output. | fast path vs NFA, DFA bounds vs capture engine, OnePass vs fallback |
| Lifecycle/state-machine invariant | Compare operation sequences against explicit matcher-state contracts. | `reset`, `region`, `usePattern`, snapshots, streams, append state |
| Parser dialect oracle | Compare accepted syntax and membership with the JDK, or assert deliberate rejection. | JDK syntax families, malformed syntax, non-JDK dialect spellings |
| Linear-time/scaling oracle | Assert algorithmic scaling or bounded state without fixed elapsed-time thresholds. | input doubling ratios, visited-state growth, timeout-free pathological cases |
| Coverage inventory | Assert that generated suites, disabled cases, or transition inventories remain explicit. | crosscheck generation, disabled annotations, matcher transition inventory |

This table is not a runtime abstraction.  It is the review checklist for adding
tests and deciding whether `@DisabledForCrosscheck` is legitimate.

## Generated Public API Crosscheck

Generated public API crosscheck should be the default for tests that exercise
JDK-compatible public behavior.  The generator should copy comparable tests into
`org.safere.crosscheck` so each test operation can be compared with the JDK.

The expected rule is:

- If the test asserts public `Pattern` or `Matcher` behavior that the JDK also
  supports, it should run in generated crosscheck.
- If the test uses a SafeRE-only API that the crosscheck facade supports and
  can compare against an equivalent JDK operation or explicit paired invariant,
  it should run in generated crosscheck.
- If the test uses SafeRE-only APIs with no meaningful JDK comparator, internal
  engine controls, or deliberately unsupported JDK features, it may be
  excluded.
- If the test depends on performance, timing, thread scheduling, randomized
  exploration, or internal instrumentation, it should usually not be generated
  as a JDK public API comparison.
- If only part of a test class is non-comparable, disable the smallest useful
  scope rather than hiding unrelated comparable tests.

Generated crosscheck can compare ordinary `results()` streams and pure
functional replacement callbacks by advancing SafeRE and the JDK through the
same public operation and comparing the observable snapshots or replacement
output.  It is not the right oracle for callbacks that intentionally mutate the
matcher being traversed or replaced: one generated wrapper owns two underlying
matchers, while the callback's natural target is a single matcher.  Those cases
belong in a matcher state-machine differential suite that runs the same
operation trace separately against SafeRE and the JDK.

`@DisabledForCrosscheck` should remain an explicit contract.  Its reason string
should say which category makes the test non-comparable, such as "SafeRE-only
API", "internal engine control", "unsupported non-regular JDK feature", or
"algorithmic scaling oracle".  These reasons do not need to follow a rigid enum
or prefix format.  The important property is that a reviewer can tell why the
JDK oracle does not apply without rediscovering the test's implementation
details.

## Disabled Coverage Accounting

The project should not rely on stale documentation or informal memory to know
why crosscheck skips exist.

Instead:

- each generated-crosscheck exclusion should carry a specific reason;
- broad class-level exclusions should be rare and reviewed carefully;
- tests should fail if an exclusion reason is empty or missing;
- reviewers should reject generic reasons that do not explain why the JDK
  oracle is not applicable;
- build output may report skipped generated tests, but documentation should not
  include hardcoded test counts;
- migration work, such as a JUnit version change, should include a before/after
  inventory of generated test sources and disabled categories as review data.

This design does not require a permanent count in the docs.  Counts change as
tests are added.  The durable invariant is that skipped coverage remains
classified and reviewable in code.

## Machine-Checkable Policy

The generated crosscheck policy should be enforced by tests where the invariant
is mechanical:

- `@DisabledForCrosscheck` reason strings must be non-empty;
- every compile-time structural exclude in the generator must point to a real
  source file with a class-level `@DisabledForCrosscheck` annotation;
- comparable public API tests should remain generated by default rather than
  being silently moved into structural excludes;
- generated coverage evidence should come from build output and generated
  sources, not hardcoded documentation counts.

These checks deliberately stop short of requiring a rigid reason taxonomy.  The
reason text is a review contract, not an enum.  A reviewer should be able to
read it and classify the skip without rediscovering the implementation details.

## Stable Linear-Time Oracles

Linear-time regression tests should avoid fixed elapsed-duration assertions.
They are unstable across machines, load, JVM warmup, and garbage collection.

Preferred approaches:

- compare scaling shape across related input sizes rather than asserting an
  absolute duration;
- use ratios with generous bounds only when the compared workloads are otherwise
  identical;
- assert bounded internal state where instrumentation already exists;
- use engine-path controls to compare behavior without depending on a slow JDK
  backtracking case;
- keep pathological JDK comparisons in benchmarks or manual validation rather
  than ordinary correctness tests when the JDK can hang.

Timing can still be used as a last-resort guard for a pathological case, but it
should be isolated, named as a coarse safety check, and not be the primary proof
of linear-time behavior.

## Required Validation Commands

The required PR validation for public API or parser/matcher semantic changes is:

```bash
mvn verify -pl safere,safere-crosscheck -am -Pcrosscheck-public-api-tests
```

The `-am` flag is part of the contract: it rebuilds the local `safere` module so
`safere-crosscheck` does not test against a stale local artifact.

Focused tests are still useful during development.  A change can start with a
targeted test command, but it should not be treated as PR-ready if it touches
public API semantics and has not run the generated crosscheck reactor.

## Acceptance Criteria

This design track is complete when:

- `@DisabledForCrosscheck` reasons are specific enough to classify every
  exclusion;
- comparable public API tests run through generated crosscheck by default;
- broad crosscheck exclusions are either removed or justified as non-comparable;
- parser dialect, engine-path, matcher lifecycle, and capture semantics suites
  make their oracle category clear at an appropriate granularity, such as a
  test class, nested class, helper, annotation, or individual test when the
  oracle is unusual;
- scaling tests avoid fixed wall-clock thresholds where a stable algorithmic
  oracle is available;
- JUnit or test-harness migration work includes machine-checkable evidence that
  generated coverage was not silently lost;
- the required PR validation command remains documented as the public semantic
  verification gate.

## Implementation Status

This design is implemented for the current crosscheck surface.  The first audit
step, merged in #270, made the existing generated oracle more trustworthy:

- comparable public API tests are generated into `safere-crosscheck` by default;
- stale SafeRE-specific API test classes were removed or moved into ordinary
  public API tests and internal metadata tests;
- `Pattern.namedGroups()` is compared against the JDK oracle instead of only
  delegating to SafeRE;
- the remaining `@DisabledForCrosscheck` annotations were audited and classify
  as internal implementation tests, SafeRE-only APIs, already-differential
  suites, lifecycle/state-machine oracles, linear-time stress oracles,
  repository validation, or implementation-specific behavior.

This follow-up closes the remaining crosscheck-oracle gap for result-stream and
policy coverage:

- ordinary `Matcher.results()` streams are compared by the crosscheck facade as
  SafeRE and JDK streams advance;
- callback mutation during `results()` traversal and functional replacement is
  intentionally covered by `MatcherStateMachineTraceTest`, where the same
  operation trace runs separately against each engine;
- structural generator excludes and blank disable reasons are covered by
  machine-checkable policy tests.

This closes the crosscheck-audit part of #223: annotations that hid comparable
coverage were removed, and the remaining annotations state why the JDK generated
oracle does not apply.

## Remaining Work

The following work is intentionally separate from this design track:

- #268: generated crosscheck source/license-header packaging;
- #269: audit `RE2PosixTest` to separate JDK-compatible corpus coverage from
  POSIX-specific naming and expectations.

Revisit this design if a future test harness change adds new broad structural
excludes, introduces a new public API facade surface, or changes JUnit test
discovery in a way that could silently drop generated coverage.

## Non-Goals

- Do not make benchmark results part of ordinary correctness pass/fail tests.
- Do not require every internal engine test to be JDK-crosschecked.
- Do not encode test counts in design documentation.
- Do not remove `@DisabledForCrosscheck`; make its use precise and auditable.
- Do not make the JDK oracle responsible for SafeRE-only APIs or internal
  invariants.
