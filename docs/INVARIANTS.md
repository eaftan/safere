# Semantic invariants

These contracts guide changes to SafeRE's parser, compiler, engines, and public
matchers. Read them with the [architecture guide](ARCHITECTURE.md) and
[intentional divergences](../INTENTIONAL_DIVERGENCES.md). The compatibility
policy in [AGENTS.md](../AGENTS.md#correctness-constraints) takes precedence over
an observed JDK implementation detail.

## Linear work and bounded state

For a fixed compiled program, matching work must be linear in input length.
An operation may use a bounded number of linear passes, such as rejection,
range narrowing, and capture extraction. It must not repeatedly run a matcher
from candidate starts, replay unbounded prefixes, or enumerate partitions to
repair a result. Repeated search and replacement need their own progress and
work bounds; a linear inner search alone does not establish those bounds.

Use explicit stacks and worklists for user-controlled nesting. Engine state,
capture bookkeeping, caches, Unicode context, and fallback work must each have
a stated bound. A cache key must include every context bit affecting its result;
cache collisions or exhausted budgets must never change semantics.

## Captures survive compilation

Two patterns recognizing the same language can have different captures.
Parsing, simplification, and compilation must preserve group numbering,
participation, bounds, alternation priority, greediness, and retained values
under repetition. A group in a zero-count repetition keeps its number but does
not participate. Unmatched captures use `-1` bounds; empty captures have equal
nonnegative bounds.

Capture results belong to the selected prioritized path. Losing paths must not
leak their writes into that result. Compiler transformations involving nested,
nullable, lazy, or bounded repetitions need capture-aware tests, including a
suffix that forces the engine to abandon a path.

Deferred extraction may fill unresolved captures for an already selected match.
It must preserve match identity and use the same input, pattern, region, bounds,
and priority as the original search. Public `group`, replacement, and snapshot
operations must not reconstruct capture history with additional searches over
candidate partitions. If an optimization cannot preserve these contracts, use
an eligible capture-capable engine.

## Engines agree on public observations

Every execution path must distinguish what it can establish:

| Role | Authority |
|---|---|
| Filter | Reject an impossible match or skip impossible starts |
| Partial result | Supply only the bounds or other fields it has proved |
| Complete result | Supply the selected match and all promised captures |

A successful filter is not proof of an exact match. DFA rejection, start
detection, group-zero bounds, and capture extraction have separate capability
requirements. Fast paths need semantic eligibility checks as well as size or
performance checks.

Disabling an optimization must preserve the observable API trace: booleans,
bounds, captures, snapshots, replacement output, and later searches.
`EnginePathContract` and `EnginePathOptions` describe and exercise these roles;
the String and UTF-8 engine equivalence tests compare forced paths. Comparisons
between engines belong in tests, not in production result selection.

## Matcher operations are state transitions

Treat pattern, input, region, bounds, current result, deferred captures, search
cursor, append position, and mutation tracking as separate state components.
Clearing a result does not necessarily reset the next-search cursor.

- Success publishes a coherent result; failure invalidates match observations
  according to the public contract and documented divergences.
- Reset and region changes clear stale deferred state and reset the applicable
  search and replacement positions.
- Pattern changes invalidate pattern-dependent caches. Preserve the overall
  match and clear inner captures as specified in the
  [usePattern policy](../INTENTIONAL_DIVERGENCES.md#match-state-after-matcherusepattern).
- Bounds changes must preserve an existing match. Resolve pending captures
  under the old bounds, or prove that deferred resolution is independent of
  the changed bounds.
- Reading a group must not advance the search or replacement cursor.
- Match snapshots must remain independent of subsequent matcher mutation.
- Result streams and functional replacements must detect prohibited callback
  mutation under their API contract.

Empty-match progress depends on the representation: String matching uses Java
indices, while UTF-8 matching advances at scalar boundaries. Test terminal empty
matches, failed full matches, and bounds changes as sequences, not only as
isolated return values. `MatcherStateMachineTraceTest` and
`Utf8MatcherStateMachineTest` exercise those transitions.

## Replacement uses the same match sequence

Replacement must use the canonical search sequence, including empty-match
progress. A fast path must produce equivalent output and final matcher state.
`appendReplacement` consumes a valid current match and advances the append
position without changing the next-search cursor. Capture substitution must
observe the same values as group access. UTF-8 sinks receive borrowed ranges
whose lifetimes and ownership follow the [UTF-8 contract](UTF8.md).

## Parsing preserves source meaning

The supported dialect is Java-oriented, with explicitly documented extensions
and exclusions in [syntax and compatibility](SYNTAX.md). An inherited RE2 parser
feature is not sufficient reason to accept RE2-specific syntax.

For character classes, distinguish source tokens from their set membership.
Normalize comments-mode trivia and zero-width quoted syntax before classifying
the next token. Only a character-class item consumes first-item state. A range
requires scalar endpoints; nested classes and property sets are expressions,
not scalar endpoints. Escaped ampersands are literals; raw intersection syntax
must retain its source role. Do not reconstruct that role from the resulting
character set.

Preserve the specified precedence of escapes, grouping, ranges, union, and
intersection. Negation and case closure must compose with those operations.
Use both compile/error tests and membership tests: acceptance alone does not
show that a class means the right thing. Differential matrices should vary
nesting, quoting, comments, ranges, intersections, flags, and malformed forms.
Use public JDK APIs as the oracle and keep SafeRE's parser model independent of
private JDK implementation structure.

## Unicode and boundary context

Character consumption, boundary visibility, and anchoring bounds are distinct.
Unicode matching uses code points while public coordinates follow the input
representation. Cache grapheme context with bounded work instead of scanning
backward through arbitrary prefixes for each query. The detailed String
contract is in [graphemes and regions](GRAPHEME_REGIONS.md).

## Verification

Use JDK differential tests for comparable behavior, SafeRE expected results for
approved divergences, forced-path tests for engine equivalence, and work
counters or scaling tests for complexity. Fuzzing and exhaustive sweeps discover
cases; systematic regression tests protect the behavioral class. See the
[testing guide](TESTING.md) for selecting and running checks.
