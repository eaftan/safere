# Issue 474 Diagnostics Plan

I reviewed issue 474 and the full linked chain of issues and PRs, including the links introduced in their comments. The core conclusion is that the original API sketch is directionally right, but its current model is too coarse to answer several of the motivating questions.

The best design is a two-part API:

1. A stable, queryable static pattern analysis API for tooling and eligibility inspection.
2. A lightweight runtime listener that reports the strategy actually used and, in a bounded form, why more desirable strategies were unavailable or bypassed.

A single `dfaEligible` boolean or `primaryEngine` enum cannot cover all the use cases raised in the linked work.

## What the linked history adds

I reviewed:

- [Issue #474](https://github.com/eaftan/safere/issues/474): original diagnostics proposal.
- [Issue #469](https://github.com/eaftan/safere/issues/469): measuring real NFA use before prioritizing Pike VM allocation work.
- [Issue #477](https://github.com/eaftan/safere/issues/477): nullable loops, progress registers, capture costs, and possible future Pike VM extensions.
- [Issue #465](https://github.com/eaftan/safere/issues/465), [PR #468](https://github.com/eaftan/safere/pull/468), and [PR #470](https://github.com/eaftan/safere/pull/470): word-boundary patterns unexpectedly falling off the DFA path and the scrubber workload that exposed it.
- [Issue #481](https://github.com/eaftan/safere/issues/481) and [PR #500](https://github.com/eaftan/safere/pull/500): representative real-world benchmark workloads.
- [Issue #488](https://github.com/eaftan/safere/issues/488): replacement workloads that reached NFA because of semantic guards.
- [PR #494](https://github.com/eaftan/safere/pull/494): leftmost-longest support, ultimately judged low priority.
- [PR #505](https://github.com/eaftan/safere/pull/505), [PR #506](https://github.com/eaftan/safere/pull/506), [PR #531](https://github.com/eaftan/safere/pull/531), [PR #532](https://github.com/eaftan/safere/pull/532), and [issue #538](https://github.com/eaftan/safere/issues/538): latent DFA correctness problems, removal of broad semantic guards, and the distinction between a DFA candidate and authoritative match bounds.
- [Issue #518](https://github.com/eaftan/safere/issues/518): the need to separate engine-selection tests, forced-engine conformance tests, and unsupported-path bailout tests through an explicit capability matrix.
- [Issue #528](https://github.com/eaftan/safere/issues/528) and [PR #529](https://github.com/eaftan/safere/pull/529): nested nullable loops where a DFA over-approximation can reject safely but cannot supply authoritative match bounds.
- [PR #541](https://github.com/eaftan/safere/pull/541): a pattern was DFA-capable, but control flow incorrectly abandoned the DFA after prefix verification failed.
- [PR #555](https://github.com/eaftan/safere/pull/555): OnePass was theoretically eligible but disabled by a stale eligibility check.
- [PR #547](https://github.com/eaftan/safere/pull/547), [PR #556](https://github.com/eaftan/safere/pull/556), and [PR #560](https://github.com/eaftan/safere/pull/560): replacement-specific DFA, character-class, and proposed OnePass paths, including density-dependent tradeoffs.
- [PR #478](https://github.com/eaftan/safere/pull/478): no-match replacement optimization.
- [PR #510](https://github.com/eaftan/safere/pull/510): merged Pike NFA pooling and execution optimizations that substantially reduced fallback-engine allocations, making production prevalence and remaining tail-latency impact the relevant questions for further NFA work.

These items expose five distinct questions consumers want to answer:

- What semantic features does the pattern contain?
- Which engines or accelerators could ever participate?
- Which strategies were applicable for this particular operation and input?
- Which strategies actually participated?
- Why did execution use a slower or unexpected path?

Those cannot be collapsed into one eligibility flag.

## Use-case inventory

| Use case | Required information |
|---|---|
| Decide whether further Pike NFA optimization matters after #510 | Counts of operations where NFA actually performed authoritative matching or capture extraction, plus input sizes and operations; production prevalence complements forced-NFA microbenchmarks and allocation profiles |
| Static Error Prone-style analysis | Pattern features, structural hazards, and operation-sensitive capabilities without executing a match |
| Find patterns that cannot use DFA | Static DFA capability and stable limitation reasons |
| Detect regressions like #555 | Static OnePass capability compared with runtime strategy selection |
| Detect fallback bugs like #541 | Ordered runtime strategy participation, not just final engine |
| Explain nested nullable loops like #529 | `PROGRESS_CHECK`/nullable-loop feature plus “DFA reject prefilter only” capability |
| Understand replacement performance | `replaceAll`/`replaceFirst` as first-class operations and replacement strategy reporting |
| Distinguish matching from capture cost | Separate boundary-selection and capture-resolution strategies |
| Detect DFA budget behavior | A bounded skip/fallback reason and whether the budget was exhausted |
| Aggregate safely in production | No input or regex text, low-cardinality enums, synchronous listener, consumer-owned storage |
| Preserve future implementation freedom | Stable semantic roles, not reverse-DFA pass names or internal branch names |

## Recommended API shape

### 1. Static `PatternAnalysis`

Expose analysis directly from a compiled `Pattern`:

```java
public PatternAnalysis analysis();
```

I would make this a normal immutable value object rather than emit it only through `onPatternCompiled`. A compilation listener is useful for fleet-wide aggregation, but it does not satisfy the static-analysis use case well: tooling needs to ask a compiled pattern a question deterministically.

A first-cut shape:

```java
public record PatternAnalysis(
    Set<PatternFeature> features,
    Set<PatternCapability> capabilities,
    Set<PatternLimitation> limitations,
    int programSize,
    int captureCount) {
  public PatternAnalysis {
    features = Set.copyOf(features);
    capabilities = Set.copyOf(capabilities);
    limitations = Set.copyOf(limitations);
  }
}
```

Suggested capabilities:

```java
public enum PatternCapability {
  LITERAL_MATCH,
  CHARACTER_CLASS_MATCH,
  KEYWORD_MATCH,
  ONE_PASS_PRIMARY,
  ONE_PASS_CAPTURE_EXTRACTION,
  DFA_BOUNDARY_SEARCH,
  DFA_REJECT_PREFILTER,
  BIT_STATE,
  NFA
}
```

The distinction between `DFA_BOUNDARY_SEARCH` and `DFA_REJECT_PREFILTER` is essential for #529. Saying merely “DFA eligible” would misleadingly imply that the DFA can return authoritative bounds.

Capabilities mean that a strategy can participate for some supported operation and input; they are
not promises that every call will select it. Runtime conditions still matter. In particular,
BitState depends on the program/input budget, OnePass primary selection depends on the operation and
input-length policy, and DFA execution can exceed its state budget. `PatternAnalysis` must document
those conditional semantics, while `strategyDecisions` explains the decision for an actual call.

Suggested features should include the original list plus:

```java
NULLABLE,
NULLABLE_ALTERNATION,
NESTED_NULLABLE_QUANTIFIER,
PROGRESS_CHECK,
START_ANCHOR,
END_ANCHOR,
CAPTURES_IN_QUANTIFIER
```

These are more actionable for tooling than only `ANCHOR`, `BOUNDED_REPEAT`, and `CAPTURES`.

Suggested static limitations should be semantic and low-cardinality:

```java
public enum PatternLimitation {
  GRAPHEME_REQUIRES_EXACT_ENGINE,
  NULLABLE_LOOP_REQUIRES_EXACT_ENGINE,
  LAZY_SEMANTICS_LIMIT_ONE_PASS,
  NULLABLE_ALTERNATION_LIMITS_ONE_PASS,
  CAPTURE_PRIORITY_REQUIRES_EXACT_ENGINE,
  PROGRAM_TOO_LARGE_FOR_BIT_STATE
}
```

Avoid promises such as “this pattern will be expensive.” Expense depends on operation, input size, match density, replacement contents, cache state, and enabled engine paths. Static analysis should expose facts and capabilities; Error Prone or another consumer can impose policy.

### 2. Runtime diagnostics

Keep the global listener model, but report an operation summary rather than only a “primary engine”:

```java
public class SafeReMatchDiagnostics {
  public static final SafeReMatchDiagnostics NONE = new SafeReMatchDiagnostics(false);

  protected SafeReMatchDiagnostics() {}

  private SafeReMatchDiagnostics(boolean enabled) {}

  public void onPatternCompiled(PatternCompiledEvent event) {}

  public void onOperationCompleted(OperationDiagnostics event) {}
}
```

The issue sketch called this listener `SafeReDiagnostics`, but that name is already occupied by
SafeRE's public bytecode-export utility. The implementation therefore uses
`SafeReMatchDiagnostics` for the listener and preserves the existing API. Registration remains
`Pattern.setDiagnostics(...)` and `Pattern.diagnostics()`. It is a subclassable listener class
rather than an interface because Error Prone rejects an interface-owned `NONE` implementation as a
possible class-initialization deadlock under this project's warnings-as-errors build.

The event should distinguish three roles:

```java
public record OperationDiagnostics(
    PatternDescriptor pattern,
    MatchOperation operation,
    MatchOutcome outcome,
    MatchStrategy boundaryStrategy,
    MatchStrategy captureStrategy,
    List<StrategyParticipation> auxiliaryStrategies,
    Set<StrategyDecision> strategyDecisions,
    CaptureMode captureMode,
    int inputLength,
    int matchCount) {
  public OperationDiagnostics {
    auxiliaryStrategies = List.copyOf(auxiliaryStrategies);
    strategyDecisions = Set.copyOf(strategyDecisions);
  }
}

public record PatternCompiledEvent(
    PatternDescriptor pattern) {}

public record PatternDescriptor(
    long patternId,
    PatternAnalysis analysis) {}

public record StrategyParticipation(
    MatchStrategy strategy,
    StrategyRole role) {}

public record StrategyDecision(
    MatchStrategy strategy,
    StrategyDisposition disposition,
    StrategyReason reason) {}
```

All public records must validate required components and defensively copy collection components in
their canonical constructors. A record's component references are final, but the record is not
deeply immutable unless collections are copied. This is required because cached analysis and
descriptors are shared across matchers, threads, and listener invocations. Do not expose internal
mutable arrays, `EnumSet`s, lists, or sets through record accessors.

This exact record can be refined during implementation, but the conceptual separation should remain:

- `pattern`: the pattern's cached immutable descriptor, shared with `PatternCompiledEvent`. Its
  opaque process-local identifier lets a global listener correlate operations, while its analysis
  lets a listener installed or replaced after pattern compilation interpret the first operation it
  observes. The descriptor contains no pattern text, is not stable across processes, and does not
  promise to encode pattern contents.
- `boundaryStrategy`: who produced authoritative group-zero bounds.
- `captureStrategy`: who produced capturing-group bounds, or `NONE`.
- `auxiliaryStrategies`: bounded strategy/role pairs for auxiliary work in first-participation
  execution order, such as literal start acceleration followed by a DFA reject prefilter. Both
  dimensions are required: a role such as start acceleration alone would not say whether the
  literal, character-class, or keyword strategy performed it. Ordering is required to diagnose
  control-flow regressions where an accelerator runs and execution then continues through a
  different engine, as in #541.
- `strategyDecisions`: bounded explanations associated with the affected strategy. The disposition
  distinguishes a strategy that was statically inapplicable, bypassed for this operation or input,
  or attempted and fell back. A global reason without the strategy association would be ambiguous;
  for example, `INPUT_TOO_LARGE` could otherwise refer to OnePass or BitState.
- `matchCount`: particularly useful for replacement and split operations.

A single `primaryEngine` loses important cases:

- DFA finds group-zero boundaries, then OnePass extracts captures.
- DFA finds boundaries, then BitState or NFA extracts captures.
- An over-approximation DFA runs only as a negative prefilter.
- Literal-prefix acceleration proposes a candidate, but reverse DFA supplies the authoritative start.
- Replacement uses its own character-class loop and never invokes ordinary `find()`.

Assign the identifier once when constructing `Pattern`, using a thread-safe monotonic source with
defined overflow behavior. Cache one immutable `PatternDescriptor` when complete analysis is first
needed. This adds a small unconditional identifier cost and one field per pattern; constructing the
descriptor and complete analysis remains lazy when diagnostics are disabled. The production-mode
benchmark gate must measure pattern compilation as well as matching. Do not use
`System.identityHashCode`, a truncated pattern hash, or another collision-prone surrogate.

Every enabled operation event carries the cached descriptor reference, even if a compilation event
was previously emitted. This is deliberate: a global listener may be installed or replaced after a
long-lived pattern was compiled, and there is no safe bounded replay log of earlier compilation
events. Reusing the cached descriptor adds no per-operation descriptor allocation.

### 3. Operations

At minimum:

```java
public enum MatchOperation {
  MATCHES,
  LOOKING_AT,
  FIND,
  REPLACE_FIRST,
  REPLACE_ALL
}
```

If the goal is truly “all use cases mentioned,” replacement operations cannot be deferred: #488, #547, #556, and #560 are central motivating cases.

Both string and functional replacement overloads use the replacement operation values. `split`,
predicate methods, and streams can be added later, but the implementation should funnel operations
through an internal diagnostic scope so future additions do not require redesigning every event
site.

### 4. Strategies

Prefer stable externally meaningful strategies:

```java
public enum MatchStrategy {
  NONE,
  LITERAL,
  CHARACTER_CLASS,
  KEYWORD,
  ONE_PASS,
  DFA,
  BIT_STATE,
  NFA
}
```

Do not expose `REVERSE_DFA`, `DFA_SANDWICH`, thread queues, or exact fallback branches as public enums. Those are implementation mechanisms. Represent their semantic role through `boundaryStrategy` and auxiliary roles.

### 5. Runtime reasons

The original proposal rejects a fallback enum, which is sensible if the alternative is exposing every branch. But a small stable reason set is necessary for the linked use cases:

```java
public enum StrategyReason {
  INPUT_TOO_SMALL,
  INPUT_TOO_LARGE,
  CAPTURES_REQUIRED,
  AUTHORITATIVE_BOUNDS_REQUIRED,
  EXACT_NULLABLE_LOOP_SEMANTICS_REQUIRED,
  DFA_BUDGET_EXCEEDED,
  OPTIMIZED_PATH_DISABLED
}

public enum StrategyDisposition {
  INAPPLICABLE,
  BYPASSED,
  FALLBACK
}
```

These should describe semantic decisions rather than implementation locations. Emit each reason as
part of a `StrategyDecision`, not as an unassociated global set. Multiple decisions may apply to one
operation, and a strategy may have more than one relevant decision, so this should not be a single
fallback enum.

For the first release, it would be reasonable to emit reasons only when an expected major engine is skipped or fallback occurs. Do not emit a complete decision-tree trace.

## Specific examples the API must represent

The implementation tests should explicitly cover these shapes:

- A pure literal uses `LITERAL`.
- A simple character class uses `CHARACTER_CLASS`.
- An anchored HTTP-like pattern uses OnePass, protecting against a recurrence of #555.
- A normal unanchored pattern uses DFA for authoritative bounds.
- A pattern with captures uses DFA bounds followed by OnePass, BitState, or NFA capture extraction.
- A nested nullable-loop negative match uses `DFA_REJECT_PREFILTER`, reflecting #529.
- A nested nullable-loop positive candidate falls through to an exact engine.
- A literal-prefix candidate verification failure continues through DFA rather than being reported as NFA-only, reflecting #541.
- DFA budget exhaustion records the budget reason and final exact strategy.
- A word-boundary pattern records DFA usage where currently supported.
- A grapheme pattern records exact-engine use and its stable limitation.
- Character-class replacement reports its replacement fast path, reflecting #556.
- General replacement reports DFA boundary scanning and separate capture extraction, reflecting #547.
- No-match replacement produces one completed operation event, not a misleading series of public `find` events.
- Deferred captures return `DEFERRED`; a later `group()` resolution does not emit a second event in version one.

## Implementation plan

### Phase 1: Define semantics before adding event sites

1. Inventory every return path in `matches()`, `lookingAt()`, `find()`, `replaceFirst()`, and `replaceAll()`.
2. For each path, record:

   - authoritative boundary producer;
   - capture producer;
   - auxiliary accelerators/prefilters;
   - applicable skip/fallback reasons;
   - whether the operation is one public invocation or an internal repeated search.

3. Write this as an internal decision table and tests before defining public enums. This avoids shaping the API around only the most obvious `doFindCore()` branches.

### Phase 2: Build reusable internal carriers

Do not infer diagnostics after the fact from the returned group array. When diagnostics are enabled,
pass an optional accumulator through the existing engine calls:

```java
final class DiagnosticAccumulator {
  InternalStrategy boundaryStrategy;
  InternalStrategy captureStrategy;
  int[] orderedParticipation;
  int participationCount;
  long[] seenParticipationWords;
  long[] decisionReasonWords;
}
```

This is an implementation shape, not a required public class. The arrays have fixed sizes derived
only from the bounded public enums, never from the pattern or input. Encode each strategy/role pair
as an integer token, append it only on first participation, and use the seen-participation words for
bounded deduplication without losing order. Define a collision-free ordinal mapping for every
strategy/role pair and every strategy/disposition/reason tuple, and allocate enough 64-bit words for
the complete Cartesian products. Do not assume either decision space fits in one `int` or one
`long`; for example, the initially proposed strategy decisions already have more than 64 possible
tuples.

Do not allocate the accumulator unconditionally. The disabled diagnostics path must preserve the
existing engine return types and allocation behavior. Snapshot the listener once at the public
operation boundary and use the listener's bounded enabled check to gate all diagnostic bookkeeping.
The implementation uses an internal boolean on the subclassable listener class because Error Prone
rejects the originally sketched interface-owned `NONE` implementation and reference comparisons
under the warnings-as-errors build.

When diagnostics are enabled, track first participation in the fixed-size primitive token array and
track seen pairs and strategy/disposition/reason tuples with fixed-size primitive word arrays. Reuse
existing engine-selection locals where possible. Because the enums are bounded, each pair or tuple
can map to a stable position without allocating a public record during matching. Do not construct
`EnumSet`s, collections, varargs arrays, lambdas, or diagnostic records inside the engine cascade.
Convert the primitive state into an immutable ordered public participation list and decision records
only when emitting the completed operation event.

If a reusable carrier makes enabled-path implementation clearer, create it only inside the enabled
branch. It must not add an allocation to the disabled path.

The shared BitState/NFA helper must report which engine actually ran to the enabled accumulator; it
does not need to change its normal return type. DFA and replacement paths need the same treatment.

### Phase 3: Add static analysis

1. Extract feature collection from the parsed/simplified AST and compiled program.
2. Reuse existing OnePass analysis and DFA program metadata.
3. Make the analysis immutable and cached on `Pattern`.
4. Keep complete analysis lazy. Calling `Pattern.analysis()` may deliberately trigger OnePass or
   other eligibility analysis, but ordinary compilation and matching must not pay that cost unless
   it was already required by execution.
5. When diagnostics are disabled, do not force lazy engine initialization during compilation. When
   a listener is enabled, the compilation event may deliberately request and cache the complete
   `PatternAnalysis` so the listener can correlate it with later operation events. Document this as
   enabled-diagnostics overhead.
6. Cache the complete result after the first explicit request or enabled compilation event so
   repeated tooling queries do not repeat the analysis.
7. Keep `Pattern.analysis()` as the primary direct API for tools even though an enabled compilation
   listener receives the same cached analysis.
8. Include the pattern's opaque identifier in both the compilation event and every operation event
   so consumers can join static metadata to runtime behavior.
9. Carry the cached descriptor, including analysis, in every enabled operation event so listeners
   installed after compilation do not depend on replaying historical compilation events.

### Phase 4: Add runtime listener plumbing

1. Store the process-wide listener behind a synchronized mutable call site whose disabled target is
   `SafeReMatchDiagnostics.NONE`. Listener replacement must be immediately visible across threads,
   while the stable disabled target must remain optimizable by the JIT.
2. Snapshot the listener once at the beginning of each public operation.
3. Pass that snapshot through the operation; do not reread the global listener at every internal step.
4. Allocate no event, `EnumSet`, varargs array, lambda, or carrier solely for diagnostics when the snapshot is `NONE`.
5. Emit exactly once through centralized normal-completion logic after the operation's matcher state
   and return value are finalized. Do not emit a `MATCH` or `NO_MATCH` event when the regex operation
   itself throws, because the first-version outcome enum cannot represent exceptional completion.
6. Do not emit public `FIND` events for internal searches performed by `replaceAll`.
7. Do not perform additional matching, scanning, capture resolution, or eligibility analysis to fill
   diagnostic fields. Every field must come from state the operation already computed or from
   bounded bookkeeping in the enabled branch.
8. Treat one stable call-site lookup and one bounded enabled check per top-level public operation as
   the intended disabled-path overhead. The operation-context null check also prevents internal
   replacement searches from rereading the global listener. Production-mode benchmarking showed
   that a volatile listener read materially regressed very cheap operations, so the implementation
   uses the mutable-call-site design above.

Listener exception policy should be explicit. My recommendation is to let listener exceptions
propagate, matching ordinary synchronous callback behavior, but invoke the listener only after the
successful regex operation has finalized its matcher state. The match result remains internally
established even if delivery fails. Silently swallowing listener exceptions makes telemetry failures
invisible and complicates testing. Reentrant matching from a listener should be permitted but
documented as the listener’s responsibility.

### Phase 5: Replacement integration

Treat replacement as its own operation scope:

- Literal replacement fast paths.
- Character-class replacement.
- DFA replacement loop.
- DFA boundary discovery plus exact capture extraction.
- Ordinary repeated-find fallback.

Emit one operation event with `matchCount`, not one event per internal match. That gives useful match-density information for the tradeoff raised in #560 without exposing inputs or storing counters in SafeRE.

Replacement loops already know their match count, so recording it must not require another pass over
the input. For ordinary single-result operations, use the existing match outcome rather than adding
separate counting work.

### Phase 6: Verification

Follow the testing separation from #518 rather than treating end-to-end routing coverage as proof of
each engine's semantic contract:

1. **Selection tests:** verify that each representative pattern, operation, and input shape reports
   the strategy participation and decisions expected from normal routing.
2. **Engine conformance tests:** force each engine for capability-matrix entries it claims to
   support, compare observable behavior with the appropriate JDK/SafeRE oracle, and verify that the
   reported strategy agrees with the forced path.
3. **Unsupported-path tests:** force or directly exercise unsupported engine/feature combinations
   and verify that they bail out or fall back with the expected decision instead of returning trusted
   incorrect bounds or captures.

The capability matrix should cover literal and character-class fast paths, OnePass, forward and
reverse DFA roles, the complete DFA boundary-search path, BitState, and NFA across pattern features,
public API modes, region/bounds configurations, and small versus large inputs. Reverse DFA remains an
internal test dimension, not a public `MatchStrategy` value.

Tests should cover:

- disabled diagnostics use `NONE` and allocate no public event objects;
- listener registration, replacement, and reset;
- immediate listener visibility across threads after call-site synchronization;
- one listener snapshot per operation;
- exactly one event per public operation;
- listener exception behavior;
- no regex text or input text in events;
- feature and capability classification;
- all representative strategies;
- distinct boundary and capture strategies;
- DFA reject-prefilter reporting;
- DFA-budget fallback;
- replacement paths and match counts;
- OnePass eligibility regression coverage;
- literal-prefix fallback coverage;
- concurrent listener aggregation with `LongAdder`.

For zero-allocation verification, prefer an allocation-sensitive JFR/JMH check outside the unit suite rather than a brittle elapsed-time or heap-delta assertion.

Run focused unit tests first, then:

```bash
mvn -pl safere test -q
mvn verify -pl safere,safere-crosscheck -am -Pcrosscheck-public-api-tests
```

### Phase 7: Production-mode performance regression gate

Before implementation, collect a baseline from the target base commit. After implementation, rerun
the same benchmark filters on the final branch. Always use `./run-java-benchmarks.sh` with its default
production benchmark configuration: two forks, two 500 ms warmup iterations, and five 500 ms
measurement iterations. Do not use smoke mode, fast-build shortcuts, direct JMH invocation, or
parallel benchmark runs for this regression decision.

Run benchmark classes sequentially in small batches and include at least:

- tiny literal `matches()` and successful and unsuccessful `find()` operations, where the fixed
  listener check will be the largest fraction of total work;
- representative OnePass, DFA, BitState, and NFA paths;
- short and long inputs;
- no-match, sparse-match, and dense-match replacement workloads;
- character-class and general DFA replacement fast paths.

Measure four configurations against the pre-change baseline:

1. Diagnostics implementation present and disabled with `SafeReMatchDiagnostics.NONE`.
2. Diagnostics enabled with a no-op listener.
3. Diagnostics enabled with representative `LongAdder` aggregation.
4. Explicit `Pattern.analysis()` calls measured separately from pattern compilation and matching.

Use allocation profiling or JFR alongside the production-mode run to verify that disabled
diagnostics allocate no event objects or diagnostic bookkeeping objects. Enabled diagnostics should
allocate at most one bounded event per public operation, independent of the number of internal
matches or engine passes.

The merge gate is:

- disabled diagnostics introduce no diagnostic allocations;
- disabled diagnostics show no statistically convincing material throughput regression across the
  benchmark matrix;
- complete static analysis adds no cost unless explicitly requested or already needed by execution;
- enabled no-op and aggregation overhead is measured and documented;
- any surprising or close result is confirmed with the script's `--long` mode before deciding.

If the disabled volatile read materially regresses tiny operations, stop and reconsider the global
listener design, such as a per-pattern listener snapshot or an explicitly instrumented pattern,
rather than accepting the regression without review.

## Scope recommendation

I would deliver this in two PRs:

1. `PatternAnalysis` and its static features/capabilities.
2. Runtime diagnostics listener and operation events.

That keeps the static API independently reviewable and gives runtime instrumentation a settled vocabulary. It also addresses the caution in issue 474: stable concepts can be added incrementally, while avoiding premature exposure of reverse-DFA details, budget implementation structure, or internal fallback branches.

The main change I recommend relative to the original sketch is therefore:

> Keep the listener-only aggregation model, but do not make the listener the only inspection API, and do not model execution as one primary engine. Expose static capabilities separately, then report authoritative boundary strategy, capture strategy, ordered auxiliary strategy/role participation, and a small set of strategy-associated decisions at runtime.
