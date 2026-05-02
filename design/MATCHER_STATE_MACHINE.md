# Matcher State-Machine Design

## Problem

SafeRE's `Matcher` is not just a lightweight wrapper around a pattern and an
input string.  It is a mutable public object whose state is observable through
later calls.  A search operation updates the current match, the next search
position, deferred capture state, append position, and end-state flags.  A
later `group()`, `find()`, `appendReplacement()`, `reset()`, `region()`, or
`usePattern()` call observes or mutates that state.

That matters because SafeRE aims to be a drop-in replacement for
`java.util.regex`.  Matching the JDK for a single isolated `find()` call is not
enough.  The sequence of operations on one matcher must also behave like the
JDK, while preserving SafeRE's linear-time guarantee.

Recent bugs show the risk:

- #225: after `usePattern`, SafeRE restarted `find()` from a different position
  than `java.util.regex`.
- #226: `hitEnd()` and `requireEnd()` diverged after `reset()` and `region()`
  transitions following end-sensitive matches.

The common failure mode is that local methods update the fields they need
immediately, but the full public state transition is not represented as one
contract.  That makes it easy to fix one method and leave a neighboring method
with stale match bounds, stale end-state flags, stale deferred captures, or a
wrong next-search cursor.

## Current State

`Matcher` currently carries several groups of state:

- input and pattern state: `parentPattern`, `inputSequence`, and materialized
  `text`;
- region and bounds state: `regionStart`, `regionEnd`, `transparentBounds`,
  and `anchoringBounds`;
- current result state: `hasMatch`, `groups`, `capturesResolved`,
  `groupZeroResolved`, `deferredMatchStart`, `deferredMatchEnd`, and
  `deferredEndMatch`;
- cursor state: `searchFrom`;
- replacement state: `appendPos`;
- end-state flags: `lastHitEnd` and `lastRequireEnd`;
- cached engine state: cached DFA and BitState references;
- stream and functional replacement mutation detection: `modCount`.

The implementation already has useful centralization:

- `find()` handles empty-match advancement before calling `doFind()`;
- `doFind()` is the main search dispatcher;
- `applyEngineResult()` centralizes most successful and failed match writes;
- `resolveCaptures()` centralizes deferred capture materialization;
- replacement APIs mostly consume the same `find()` sequence;
- `reset()`, `region()`, and `usePattern()` explicitly invalidate several
  match-result fields.

The weak point is that these are conventions rather than a state-machine
contract.  The code has no single place that says which state is valid after a
failed search, after `usePattern`, after `region`, after an end-sensitive
failed match, or after a functional replacement callback mutates the matcher.

## Design Principle

Every public method should be described as a transition from one matcher state
to another.

The state machine should make three facts explicit:

- which fields are inputs to the transition;
- which fields become valid, invalid, preserved, or reset;
- which public observations are legal after the transition.

The design should not add a second matcher implementation.  The goal is to
name and enforce the existing public-state contract so future engine-path or
capture work cannot accidentally leave matcher lifecycle behavior implicit.

## State Model

The matcher state can be modeled as these logical components.

| Component | Meaning |
| --- | --- |
| Pattern | The current compiled pattern and all pattern-dependent engine caches. |
| Input | The current input sequence and materialized text. |
| Region | The active search interval plus transparent and anchoring bounds. |
| Result status | Whether the matcher is reset/no-attempt, matched, or failed. |
| Result data | Current group state when the result status is matched. |
| Deferred captures | Whether group zero or inner captures still require resolution. |
| Search cursor | The stored position used as the base for parameterless `find()`. |
| Next-find derivation | The rule that derives the next `find()` start from the previous result. |
| Replacement | The append position used by `appendReplacement`/`appendTail`. |
| End state | `hitEnd()` and `requireEnd()` observations from the last match attempt. |
| Structural mutation | Whether an operation invalidates active streams or replacement callbacks. |

Those components should be represented in code either by small helper methods
or by package-private state-transition helpers.  The important requirement is
that a public method must not partially update one component without also
declaring the effect on the others.

## Transition Rules

The following table is the target contract.  It is written in terms of logical
state, not necessarily current field names.

The result status should distinguish at least:

- **reset/no-attempt:** no current match result is available because the matcher
  has not attempted a match, or a lifecycle transition cleared the result;
- **matched:** the last match attempt succeeded and group observations are
  legal;
- **failed:** the last match attempt failed, group observations are illegal,
  and end-state flags describe that failed attempt.

`reset`, `reset(input)`, `region`, and `usePattern` should move the matcher to
reset/no-attempt for group-observation purposes without erasing the previous
`hitEnd()` and `requireEnd()` values.

| Operation | Result state | Deferred captures | Search cursor and next-find rule | Replacement state | End-state flags | Structural mutation | Caches |
| --- | --- | --- | --- | --- | --- | --- | --- |
| successful `matches()` | matched | resolved or deferred with full-match anchored bounds compatible with later `group()` and `toMatchResult()` | next `find()` derives from the full-region match result | preserved | updated from this attempt | invalidates active traversal/callback | preserved |
| failed `matches()` | failed | cleared | next-find state is set by the match-operation trace oracle for failed whole-region attempts | preserved | updated from this attempt | invalidates active traversal/callback | preserved |
| successful `lookingAt()` | matched | resolved or deferred with region-start anchored prefix bounds compatible with later `group()` and `toMatchResult()` | next `find()` derives from the prefix match result | preserved | updated from this attempt | invalidates active traversal/callback | preserved |
| failed `lookingAt()` | failed | cleared | next-find state is set by the match-operation trace oracle for failed anchored-prefix attempts | preserved | updated from this attempt | invalidates active traversal/callback | preserved |
| `find()` before first success | matched or failed | resolved, validly deferred, or cleared on failure | starts from stored search cursor | preserved | updated from this attempt | invalidates active traversal/callback | preserved |
| `find()` after non-empty success | matched or failed | resolves prior group zero if needed; new result may be resolved, validly deferred, or cleared | derives next start from previous end, then searches | preserved | updated from this attempt | invalidates active traversal/callback | preserved |
| `find()` after empty success | matched or failed | resolves prior group zero if needed; new result may be resolved, validly deferred, or cleared | derives next start from previous end plus one code unit unless at region end, then searches | preserved | updated from this attempt | invalidates active traversal/callback | preserved |
| `find(start)` | matched or failed after reset/no-attempt | reset clears old deferred state; new result may be resolved, validly deferred, or cleared | resets region to full input per JDK behavior, sets stored cursor from `start`, then searches | append position reset by reset semantics | updated from this attempt | invalidates active traversal/callback | preserved |
| `reset()` | reset/no-attempt | cleared | set to input start | set to 0 | preserved from the last match attempt until the next match attempt | invalidates active traversal/callback | engine caches may remain if pattern/input-compatible |
| `reset(input)` | reset/no-attempt | cleared | set to new input start | set to 0 | preserved from the last match attempt until the next match attempt | invalidates active traversal/callback | input-dependent cached state invalidated if needed |
| `region(start, end)` | reset/no-attempt | cleared | set to region start | set to 0 | preserved from the last match attempt until the next match attempt | invalidates active traversal/callback | pattern caches preserved; input/region assumptions invalidated |
| `usePattern(newPattern)` | reset/no-attempt | cleared | preserves the JDK-compatible next-search position derived from the prior result | preserved unless JDK requires otherwise | preserved from the last match attempt until the next match attempt | invalidates active traversal/callback | old pattern caches invalidated |
| `useTransparentBounds` | preserves result status and data | resolves unresolved captures under the pre-change bounds before clearing deferred markers unless a named predicate proves deferred resolution is bounds-independent | preserves cursor and next-find derivation | preserves append position | preserved | oracle-defined and recorded in the transition inventory | preserves caches unless bounds affect them |
| `useAnchoringBounds` | preserves result status and data | resolves unresolved captures under the pre-change bounds before clearing deferred markers unless a named predicate proves deferred resolution is bounds-independent | preserves cursor and next-find derivation | preserves append position | preserved | oracle-defined and recorded in the transition inventory | preserves caches unless bounds affect them |
| `appendReplacement` | requires matched result | resolves captures needed by replacement | preserves cursor and next-find derivation | advances append position to current match end | preserves last match end-state | oracle-defined and recorded in the transition inventory | preserved |
| `appendTail` | preserves result status and data | preserves deferred state | preserves cursor and next-find derivation | preserves append position | preserves end-state | oracle-defined and recorded in the transition inventory | preserved |
| `replaceFirst` | begins with reset/no-attempt, consumes at most one `find()` result | follows operations run | follows canonical `find()` | managed by append operations | final value follows the operations run | detects callback mutation | preserved |
| `replaceAll` | begins with reset/no-attempt, consumes canonical `find()` sequence | follows operations run | follows canonical `find()` | managed by append operations | final value follows the operations run | detects callback mutation | preserved |
| `results()` | lazily consumes canonical `find()` sequence | follows operations run | follows canonical `find()` | preserved | follows the last produced or failed match attempt | detects mutation between producing a result and returning from the action | preserved |
| `toMatchResult()` | requires matched result, resolves captures, snapshots result | resolved before snapshot | preserves cursor and next-find derivation | preserves append position | preserves end-state | not a structural mutation | preserved |
| `group()`/`start()`/`end()` | requires matched result, resolves captures as needed | resolved as needed | preserves cursor and next-find derivation | preserves append position | preserves end-state | not a structural mutation | preserved |
| observer-only methods | preserve result status and data | preserve deferred state | preserve cursor and next-find derivation | preserve append position | preserve end-state | not structural mutations | preserve caches |

This table intentionally separates "clears the match result" from "resets the
next search cursor."  #225 happened because those two concepts were coupled
incorrectly: losing group information after `usePattern` does not mean the next
parameterless `find()` should restart from the beginning.

It also separates the stored search cursor from the next-find derivation.  The
JDK-compatible next `find()` start can depend on the previous result, especially
for empty matches, and not only on a single stored integer.  SafeRE should make
that derivation a named transition helper so state-clearing operations cannot
accidentally erase the information needed to compute the next search start.

For the difficult failed `matches()` and failed `lookingAt()` cases, the design
does not rely on prose intuition.  The implementation should define a
match-operation trace oracle: a generated crosscheck trace that records the next
observable `find()` sequence after those operations for representative prior
states.  The transition helper is correct only if it matches that oracle.

## Observation Legality

The transition inventory must also record which public observations are legal
after each operation.  A state transition is incomplete if it updates fields but
does not say what callers may observe next.

The target legality rules are:

- in reset/no-attempt state, observer-only methods such as `groupCount()`,
  `hitEnd()`, and `requireEnd()` are legal, but `group()`, `start()`, `end()`,
  `toMatchResult()`, and `appendReplacement()` must throw the JDK-compatible
  exception;
- after a successful match operation, observer-only methods are legal, and
  match-result methods such as `group()`, `start()`, `end()`, `toMatchResult()`,
  and `appendReplacement()` are also legal;
- after a failed match operation, observer-only methods are legal, including
  `hitEnd()` and `requireEnd()`, but `group()`, `start()`, `end()`,
  `toMatchResult()`, and `appendReplacement()` must throw the JDK-compatible
  exception;
- after `reset`, `reset(input)`, `region`, and `usePattern`, previous match
  groups and snapshots are invalid for the matcher, but `hitEnd()` and
  `requireEnd()` still report the flags from the last match attempt until the
  next match attempt updates them;
- after `appendTail`, the previous match result remains the current result for
  observation purposes;
- after `toMatchResult`, the returned snapshot must remain valid even if later
  matcher operations invalidate or mutate the matcher's current result;
- during `results()` and functional replacement callbacks, matcher mutation
  must be detected according to the JDK-compatible mutation-version contract.

The mutation-detection contract should be pinned by differential tests for
operations whose behavior is not obvious from the JDK documentation, including
`useTransparentBounds`, `useAnchoringBounds`, `appendReplacement`, and
`appendTail`.  The implementation should not infer their structural-mutation
status from whether a field happens to change internally.

Bounds changes have a separate deferred-capture rule: unresolved deferred
captures must be resolved under the pre-change bounds before deferred markers
are cleared, unless a named predicate proves that later capture resolution is
independent of transparent and anchoring bounds.  This avoids resolving captures
later under different empty-width assertion or region-bound semantics than the
search that selected the match.

Observer-only methods still need transition entries even though they should not
mutate state.  The inventory should include at least `groupCount()`,
`pattern()`, `regionStart()`, `regionEnd()`, `hasTransparentBounds()`,
`hasAnchoringBounds()`, `namedGroups()`, and `toString()`.  Their contract is to
report current matcher state without changing result status, next-search
behavior, replacement position, end-state flags, or active traversal validity.

## End-State Contract

`hitEnd()` and `requireEnd()` are matcher state, not engine-local details.  A
public match attempt must leave them in the JDK-compatible state for that
attempt, whether the attempt succeeds or fails.

The state-machine design should treat end-state flags as part of the completion
step for `find()`, `matches()`, and `lookingAt()`.  Engine paths may provide
facts that help compute the flags, but they should not leave those flags stale
from an earlier operation.

The contract should answer these cases explicitly:

- successful match that reaches the region end;
- failed search that scanned to the region end;
- `reset()`, `reset(input)`, and `region()` after an end-sensitive prior match,
  which should preserve the previous flags until the next match attempt;
- `usePattern()` after an end-sensitive prior match, which should also preserve
  the previous flags until the next match attempt;
- region bounds with anchoring enabled and disabled;
- transparent bounds, even though SafeRE rejects lookaround, because the flag
  remains public matcher state.

If SafeRE intentionally uses a conservative approximation for `requireEnd()`,
that approximation must be documented as a stable product behavior and applied
consistently after every relevant transition.

## Deferred Capture Contract

Deferred captures are an internal optimization, but their validity is tied to
matcher state.  Any transition that invalidates the current result must also
invalidate deferred capture state.  Any transition that changes pattern, input,
region, matcher bounds, or group-zero bounds must make it impossible to resolve
stale deferred captures later.

The target rules are:

- `group()`, `start()`, `end()`, `toMatchResult()`, and replacement
  substitution may resolve captures, but must not change match identity;
- `find()` may avoid resolving inner captures for the previous match when group
  zero is already authoritative, but it must resolve group zero before using
  previous bounds to advance;
- `reset()`, `reset(input)`, `find(start)`, `region()`, and `usePattern()` must
  clear deferred state because they reset the current result or replace the
  state used to derive it;
- `useTransparentBounds` and `useAnchoringBounds` must resolve unresolved
  captures under the pre-change bounds before clearing deferred markers, unless
  a named predicate proves that later capture resolution is independent of the
  changed bounds;
- snapshots must clone resolved state so later matcher transitions cannot
  mutate an existing `MatchResult`.

This ties the matcher-state design to the capture-semantics design without
making matcher lifecycle responsible for computing capture semantics itself.

## Replacement Contract

Replacement APIs combine multiple state components and therefore need explicit
rules.

`appendReplacement` is a state transition over an existing successful match:

- it requires a valid current match;
- it resolves captures needed by the replacement;
- it appends input from the previous append position to the current match
  start;
- it appends the replacement;
- it advances append position to the current match end;
- it does not change the next-search cursor.

`replaceAll` and `replaceFirst` should be specified as canonical loops over
`reset()`, `find()`, `appendReplacement`, and `appendTail`, except for fast
paths that have an explicit engine-path equivalence contract.  The replacement
contract must include empty matches, anchored matches, regions, and functional
replacers that attempt to mutate the matcher while the replacement is in
progress.

## Cache Contract

Engine caches are not public state, but stale caches can change public behavior
if they are tied to the wrong pattern, input, region, or bounds.

The cache rules should be:

- pattern-dependent caches must be invalidated by `usePattern`;
- input-dependent temporary state must not survive `reset(input)` unless it is
  proven independent of input contents and length;
- region and bounds changes must not reuse cached results that encode old
  region assumptions;
- returning borrowed engine objects to pattern-owned pools must be paired with
  matcher lifecycle transitions that can no longer use them.

The implementation should prefer helper methods with names such as
`clearMatchResult`, `clearDeferredCaptures`, `invalidatePatternCaches`, and
`resetSearchState` over open-coded field writes spread across public methods.

## Proposed Work

### 1. Add A Transition Inventory

Create a package-private transition inventory for public matcher operations.
This may begin as comments and focused tests, but the target should be
machine-checkable metadata that names:

- operation;
- preconditions;
- result effect;
- cursor effect;
- next-find derivation effect;
- append-position effect;
- deferred-capture effect;
- end-state effect;
- structural-mutation effect for active streams and callbacks;
- cache effect;
- legal and illegal public observations after the transition.

This inventory should live close to `Matcher`, not only in design docs.

### 2. Centralize State Mutations

Refactor public methods toward named transition helpers.  The first useful
helpers are likely:

- clear current result and deferred capture state;
- reset full matcher state for current input;
- reset full matcher state for new input;
- apply a successful match result;
- apply a failed match result;
- advance cursor after previous match;
- invalidate pattern-dependent caches;
- reset replacement append state.

The goal is not abstraction for its own sake.  The goal is to make incomplete
state transitions hard to write.

### 3. Add Lifecycle Trace Tests

Add generated or table-driven tests that compare SafeRE with `java.util.regex`
for operation traces, not only single operations.  Traces should include:

- `find`, `find`, `group`, `find` after empty and non-empty matches;
- `find(start)` followed by parameterless `find`;
- `matches` or `lookingAt` followed by `find`;
- `usePattern` after success, failure, empty success, and deferred capture;
- `reset`, `reset(input)`, and `region` after end-sensitive matches;
- bounds changes before and after searches;
- replacement APIs mixed with manual `find` and `appendReplacement`;
- `results()` traversal and mutation detection;
- `toMatchResult()` snapshots followed by matcher mutation.

Generated public API crosscheck should be the primary oracle.  Package-private
tests should supplement it where internal deferred-capture or engine-cache
state needs to be forced.

### 4. Make End-State Tests Systematic

`hitEnd()` and `requireEnd()` need trace-based coverage.  The tests should
compare the flags after every relevant transition, including failed operations,
not only after successful matches.

The matrix should include:

- `$`, `\Z`, `\z`, word boundaries, multiline anchors, and CRLF-sensitive
  cases;
- full input and active regions;
- anchoring bounds enabled and disabled;
- reset and region calls after a prior end-sensitive result;
- find loops where the final failed `find()` changes the flags.

### 5. Define Public Divergences

If SafeRE intentionally differs from the JDK for any matcher lifecycle behavior
because linear-time matching cannot support the JDK behavior, document that
case and add tests for the documented divergence.  Do not let lifecycle
differences remain accidental.

## Linear-Time Argument

Making matcher transitions explicit should not add work proportional to the
number of possible regex paths.  The transition helpers only move or clear
bounded matcher fields.

The linear-time constraints are:

- no transition may resolve captures by enumerating candidate explanations;
- no lifecycle operation may rerun an unbounded search except where the public
  operation already requires a search, such as `find()` or replacement loops;
- a lifecycle operation may materialize deferred captures for an existing match
  only with the same bounded, anchored capture-resolution pass that `group()` or
  `toMatchResult()` would have used, and must not enumerate candidate
  explanations;
- trace tests may compare SafeRE with the JDK or with forced internal paths,
  but production code must not run multiple engines to vote on state;
- cache invalidation must prefer recomputation by the next normal engine pass
  over stale reuse or speculative multi-pass repair.

The design is therefore compatible with the core linear-time guarantee: it
clarifies when existing linear operations run and prevents stale state from
creating hidden repair work later.

## Machine-Checkable Contracts

The practical enforcement target is similar to the engine-path design:

- a transition inventory that tests can inspect;
- a coverage check that compares that inventory with the actual public methods
  declared by `Matcher`, so newly added methods require an explicit transition
  contract;
- named helpers for each common state mutation;
- trace tests that execute the same public method sequence against SafeRE and
  the JDK;
- package-private assertions that invalidated deferred captures cannot be
  resolved after a clearing transition;
- tests that fail if a new public matcher method is not represented in the
  transition inventory.

This is not a formal proof of JDK lifecycle compatibility.  It is a way to make
the lifecycle contract executable and hard to bypass.

## Acceptance Criteria

This design is complete when:

- every public instance `Matcher` method has an explicit transition contract,
  and public static utility methods such as `quoteReplacement` are explicitly
  categorized as having no matcher-state transition;
- `Matcher` uses named helpers for clearing result state, clearing deferred
  capture state, applying search results, resetting replacement state, and
  invalidating engine caches;
- operation-trace crosscheck tests cover matcher lifecycle behavior, not only
  isolated calls;
- the result-status model distinguishes reset/no-attempt, matched, and failed
  states in code or machine-checkable metadata;
- the transition inventory includes deferred-capture effects for every public
  instance method;
- structural mutation behavior for streams and functional replacement callbacks
  is defined by the transition inventory and pinned by differential tests;
- observer-only methods have explicit no-mutation transition contracts;
- failed `matches()` and `lookingAt()` next-find behavior is verified by
  operation-trace oracles rather than left as prose;
- `hitEnd()` and `requireEnd()` are verified after success, failure, reset,
  region, bounds, and pattern-change transitions;
- replacement append-position behavior is verified across manual and
  convenience replacement APIs;
- `usePattern` preserves the JDK-compatible next-search position while clearing
  old match results and old pattern caches;
- stale deferred captures cannot survive pattern, input, region, or group-zero
  bounds changes;
- unresolved deferred captures must be materialized under pre-change bounds
  before bounds changes clear deferred markers, unless a named predicate proves
  the deferred resolution is independent of the changed bounds;
- any documented lifecycle divergence from the JDK has an explicit reason and
  regression coverage.

## Non-Goals

- Do not replace the existing engine-path dispatch design.
- Do not make matcher lifecycle responsible for general capture semantics.
- Do not run extra production searches only to verify state.
- Do not emulate unsupported JDK regex features such as backreferences or
  lookaround.
- Do not preserve stale caches for performance unless their validity is part of
  an explicit transition contract.
