# Semantic Invariants Design

## Problem

SafeRE has two public contracts that must hold at the same time:

- Matching must be linear in the size of the pattern and input.
- Observable behavior must be compatible with `java.util.regex`, except for
  explicit documented divergences required by SafeRE's linear-time model.

Those contracts are easy to state and hard to preserve because SafeRE does not
execute every pattern through one simple interpreter.  It parses and simplifies
the source pattern, compiles it into bytecode, applies metadata extraction and
accelerators, chooses among multiple execution engines, and sometimes defers
capture extraction until public APIs observe captures.

That architecture is necessary for performance, but it creates a recurring
failure mode:

> Internal rewrites, fast paths, or deferred work compute a result that is
> locally plausible, but no longer preserves the public semantics of the
> original pattern and `Matcher` state.

Recent bugs show that this is not one isolated defect.  They are not all the
same implementation problem, and they should not all be solved with one
mechanism.  They are related because they expose the same architectural risk:
public semantics are sometimes implicit in local code paths instead of being
defined as invariants that every internal representation and execution path must
preserve.

## Evidence From Recent Bugs

The last few rounds of bug reports fall into five related groups.

### Capture Semantics Across Rewrites And Engines

Issues #219, #248, #249, #250, and #258 are all variants of the same problem:
JDK-visible capture state must survive simplification, compilation, engine
selection, and deferred capture extraction.

Examples:

- #219: zero-count repetitions such as `(?:(a){0})` preserved no capture group
  count even though the JDK reports the group.
- #248: quantified capture semantics could be erased by simplification unless
  the compiler retained source-level structure.
- #249 and #250: repeated and nullable captures produced `group()` results that
  diverged from the JDK.
- #258: retained-repeat capture repair can run additional post-match NFA
  searches over candidate repeat partitions, putting the linear-time guarantee
  at risk when captures are observed.

The common invariant is that capture groups are part of the public result, not
debug metadata.  A path that returns the right `group(0)` but the wrong
`group(1)` is semantically wrong.  A path that finds a match in linear time but
repairs captures later by searching candidate explanations for the match has not
preserved SafeRE's end-to-end linear-time contract.

### Engine-Path And Fast-Path Equivalence

Issues #251, #227, and the capture bugs above also show that public behavior
must not depend on which internal path happened to run.

Examples:

- #251: a direct replacement fast path duplicated `find()` behavior and missed
  the start-anchor invariant, causing `replaceAll` to perform an extra
  replacement.
- #227: BitState crashed for an empty branch before a complex character class,
  while the public operation should have behaved like the other engines.
- #248 through #250: OnePass, DFA/deferred capture extraction, BitState, and NFA
  must agree on the public capture result once captures are observed.

The common invariant is that accelerators may narrow or reject work, but they
must not define independent semantics.  If an optimized path implements its own
search loop, cursor update, anchor handling, replacement behavior, or capture
repair, it needs either to share the canonical implementation or be tested as an
equivalent implementation of the same public contract.

### Matcher Lifecycle State

Issues #225 and #226 exposed that `Matcher` is not just a stateless view of a
pattern and input.  It has observable lifecycle state.

Examples:

- #225: after `usePattern`, SafeRE restarted `find()` from a different position
  than `java.util.regex`.
- #226: `hitEnd()` and `requireEnd()` diverged after `reset()` and `region()`
  transitions following end-sensitive matches.

The common invariant is that every public transition must define how it updates
the matcher cursor, region bounds, append position, cached match result,
deferred capture state, `hitEnd`, `requireEnd`, and engine caches.  Those state
updates must be compatible with the JDK even when the next operation uses a
different SafeRE engine path.

### Parser Dialect And Source Semantics

Issues #216, #217, #220, and #224 show that compatibility can fail before
execution begins.

Examples:

- #216: POSIX bracket class spellings inside Java character classes were parsed
  with RE2/POSIX semantics rather than JDK semantics.
- #217: `(?P<name>...)` was accepted even though JDK-compatible named captures
  use `(?<name>...)`.
- #220: empty left side character-class intersections behaved differently from
  the JDK.
- #224: octal escape acceptance and interpretation diverged from
  `java.util.regex`.

The common invariant is that parsing must have an explicit dialect policy.
SafeRE should not have regex syntax extensions.  This does not prohibit
SafeRE-only public APIs such as `PatternSet`; it means the regular expression
language accepted by `Pattern.compile` should remain JDK-compatible.  A syntax
form is either JDK-compatible, or it is rejected because SafeRE cannot support
it while preserving its linear-time and API-compatibility guarantees.  RE2
source compatibility must not silently override the drop-in `java.util.regex`
contract.

### Coverage And Regression-Oracle Quality

Issues #223, #231, #235, #241, and #252 are not all semantic bugs in production
code, but they show how semantic bugs escape.

Examples:

- #223: disabled crosscheck tests mixed legitimate non-comparable coverage with
  real compatibility gaps.
- #231 and #235: timing-based scaling tests were unstable, making linear-time
  regression coverage noisy.
- #241: test commands risked redundant execution and unclear validation scope.
- #252: a possible JUnit migration would risk silent coverage loss without
  count-based validation.

The common invariant is that the test suite must make compatibility and
linear-time claims observable in a stable way.  Crosscheck coverage should be
disabled only for documented reasons, and performance or scaling assertions
should test algorithmic behavior without depending on fragile wall-clock
thresholds.

## Why This Matters

SafeRE's value is not just that one engine is linear.  The public library must
remain linear and JDK-compatible across the operations users actually call:
`find()`, `matches()`, `lookingAt()`, `group()`, `start()`, `end()`,
`toMatchResult()`, `replaceAll()`, `appendReplacement()`, `results()`, `reset()`,
`region()`, and `usePattern()`.

The recent bugs are concerning because they share a design smell: semantics are
distributed across point mechanisms.

- Capture behavior appears in the simplifier, compiler metadata, NFA epsilon
  traversal, BitState traversal, deferred capture resolution, and replacement
  paths.
- Search behavior appears in `find()`, prefix acceleration, OnePass paths, DFA
  sandwich paths, BitState/NFA fallback, and replacement loops.
- Matcher state behavior appears in cursor management, region handling,
  append-position tracking, cached engine state, and deferred result state.
- Parser compatibility appears in scattered syntax cases instead of a single
  dialect matrix.

Each point fix can be correct for the reported input and still leave the same
class of bug available in a neighboring path.  That is exactly what #256 is
tracking: not "one more repro," but the absence of a coherent set of invariants
that every rewrite, accelerator, engine, and public API transition must obey.

## Relationship Between These Problems

These bug groups should be treated as related design tracks under one umbrella,
not as one monolithic design.

The shared umbrella is semantic preservation:

> SafeRE may parse, simplify, compile, accelerate, defer, and dispatch through
> different engines, but each boundary must preserve the public contract.

The focused designs differ:

- Capture bugs are about regex path semantics and per-thread capture state.
- Engine-path bugs are about proving that shortcuts share or preserve canonical
  search behavior.
- Matcher lifecycle bugs are about public object state transitions.
- Parser dialect bugs are about syntax policy before execution begins.
- Crosscheck and test-oracle bugs are about making those invariants observable
  and stable.

Those tracks should share vocabulary and acceptance criteria, but their
implementation designs should remain separate.  A capture-retention instruction
does not solve octal parsing.  A parser compatibility matrix does not solve
`hitEnd()` state after `region()`.  A forced-engine test harness does not by
itself define correct quantified capture semantics.  The umbrella doc should
therefore define the common invariant and point to focused designs for the
mechanics.

## Core Design Principle

SafeRE should treat public regex behavior as a set of explicit semantic
invariants that survive every internal transformation.  The bugs cited above
are motivating evidence; some have already been fixed, but the design work is
to make their shared invariants explicit enough that neighboring paths do not
regress.

The most important invariant is:

> Optimizations may change how SafeRE proves or computes a result, but they must
> not change the public result, the public matcher state, or the asymptotic cost
> of observing that result.

This leads to four product invariants plus one testing invariant.

### 1. Capture-State Invariant

Every capture-aware execution path must return final JDK-compatible capture
state as part of linear execution.  Public APIs must not repair capture
semantics by running additional regex searches over candidate partitions after a
match has already been selected.

The DFA may still reject inputs or narrow match bounds without carrying captures.
Deferred capture extraction may still avoid work when callers only need
`group(0)`.  But once captures are observed, the capture-aware pass must compute
the result directly from the compiled semantics.  It should not infer captures
by enumerating possible explanations for a match selected elsewhere.

### 2. Engine-Path Equivalence Invariant

For a given pattern, input, region, and public API operation, OnePass,
DFA/deferred capture extraction, BitState, NFA, prefix acceleration, literal
fast paths, and replacement paths must either produce the same public result or
be guarded out for that case.

Fast paths should share canonical search and cursor semantics wherever
possible.  If a fast path cannot share the implementation, it must have
cross-path tests that prove equivalence for anchors, empty matches, regions,
capture access, replacement templates, functional replacements, and end-state
flags.

### 3. Matcher State-Machine Invariant

`Matcher` state transitions must be explicit.  Each public operation should
define its effects on:

- search position and previous match bounds;
- active region and transparent/anchoring bounds;
- append position and replacement state;
- deferred capture and group-zero resolution state;
- `hitEnd()` and `requireEnd()`;
- cached engine instances and pattern-dependent metadata.

Changing pattern, input, region, or operation mode must invalidate or preserve
that state according to JDK-compatible rules, not according to incidental
implementation convenience.

### 4. Parser Dialect Invariant

The parser must classify every regex syntax feature under one of two policies:

- JDK-compatible syntax and semantics;
- rejected syntax because supporting it would violate SafeRE's guarantees or
  because it is not part of the JDK regex dialect.

This policy should be table-driven enough that syntax work is not rediscovered
by fuzzing or generated crosscheck one case at a time.

### 5. Test-Oracle Invariant

Compatibility and linear-time guarantees must be tested by stable oracles.
Generated crosscheck coverage should be enabled for public API behavior unless
there is a documented reason it is not comparable.  Scaling tests should assert
algorithmic invariants or use instrumentation rather than fragile wall-clock
thresholds.  Test-suite migrations or harness changes must validate that
coverage was not silently lost.

## Focused Design Documents

This umbrella document should stay high-level.  The concrete design work should
be split as follows.

### Capture Semantics Design

Scope: #219, #248, #249, #250, #258.

This design should define how JDK-visible capture participation and retention
survive simplification, compilation, engine selection, and deferred capture
extraction while preserving linear time.  It should address whether capture
retention belongs in explicit `Prog` instructions, per-thread engine state, or
some other bounded execution-time representation.  It should remove post-match
regex searches over candidate repeat partitions as a semantic repair mechanism.

### Engine-Path Equivalence Design

Scope: #251, #227, and cross-engine portions of the capture bugs.

#251 is already fixed by the #259 consolidation work and should be treated as a
motivating example: a replacement fast path duplicated `find()` semantics and
missed an anchor invariant.  This design should define which search and
replacement semantics are canonical, which fast paths are allowed to bypass
shared code, and what guardrails or forced-engine tests prove equivalence.  It
should cover anchors, empty matches, regions, replacement cursor updates, group
access, and end-state flags.

### Matcher State-Machine Design

Scope: #225, #226, and related public API lifecycle behavior.

This design should model `Matcher` as an explicit state machine.  It should
define state transitions for `find`, `matches`, `lookingAt`, `reset`, `region`,
`usePattern`, replacement operations, `results`, `hitEnd`, and `requireEnd`,
then map each transition to JDK-compatible behavior.

### Parser Dialect Compatibility Design

Scope: #216, #217, #220, #224, and future syntax compatibility issues.

This design should define the dialect policy for every syntax family: accepted
JDK-compatible syntax and rejected syntax.  It should include a compatibility
matrix and systematic membership/error tests against `java.util.regex`.

### Crosscheck And Test Oracle Design

Scope: #223, #231, #235, #241, #252.

This design should define when generated crosscheck tests are expected to run,
when `@DisabledForCrosscheck` is legitimate, how skip counts and test counts are
validated, and how linear-time or scaling regressions are tested without
ordinary correctness tests becoming timing-sensitive.

## Design Goals

- Preserve SafeRE's end-to-end linear-time guarantee for matching, capture
  access, snapshots, streams, and replacement APIs.
- Make public semantic invariants explicit in code structure, design notes, and
  tests.
- Keep DFA, OnePass, BitState, and literal/prefix accelerators available where
  they are semantically reliable.
- Replace post-match capture or match-state repair with bounded execution-time
  state where public behavior depends on prioritized regex paths.
- Make engine-path and lifecycle equivalence testable without relying on
  wall-clock timing.
- Keep generated public API crosscheck enabled except for documented,
  intentionally non-comparable cases.

## Non-Goals

- Do not emulate JDK behavior that fundamentally requires non-linear features
  SafeRE rejects, such as backreferences or lookaround.
- Do not match known JDK implementation accidents that SafeRE has explicitly
  documented as divergences.
- Do not make the DFA responsible for general capture extraction.
- Do not store unbounded capture history for every repetition iteration.
- Do not add one-off guards for individual regex strings or individual issue
  repros.
