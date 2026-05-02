# Capture Semantics Design

## Problem

SafeRE must preserve JDK-compatible capture group behavior while maintaining an
end-to-end linear-time guarantee.

This is harder than preserving the matched text.  A match's `group(0)` bounds
tell us where the match starts and ends, but they do not fully determine the
observable state of inner captures.  For captures inside quantified expressions,
empty alternatives, optional branches, and nested repetitions, the visible
capture values depend on the prioritized path that produced the match.

Examples from recent bugs:

- #219: zero-count repetitions such as `(?:(a){0})` must preserve the group
  count even though the group cannot participate.
- #248: simplification can erase source structure that affects quantified
  capture retention.
- #249 and #250: repeated and nullable captures can produce the right match
  span but wrong `group()`, `start()`, or `end()` values.
- #258: retained-repeat capture repair can run additional NFA searches over
  candidate repeat starts, ends, and split points after the match, which risks
  superlinear behavior when captures are observed.

The common issue is that capture semantics are not just metadata attached to a
finished match.  They are part of the execution state of the regex.

This design takes the position that the problem is solvable for SafeRE's
supported regular syntax, but not by making every accelerator independently
implement full capture behavior.  The Pike VM NFA is the semantic authority for
complex capture cases.  DFA, OnePass, BitState, and literal or prefix
accelerators remain valid only when they are proven capture-equivalent for the
pattern and operation, or when they defer capture observation to the
authoritative path.

## Current State

SafeRE already has several mechanisms that preserve parts of the capture
contract:

- The parser preserves capturing group structure and group numbering.
- The compiler emits `CAPTURE` instructions for ordinary capture boundaries.
- The Pike VM NFA carries capture registers in its execution state and is the
  natural semantic reference for capture extraction.
- BitState carries capture registers for small-input searches, but its bounded
  exploration invariant makes it an optimization, not the semantic authority
  for every complex capture case.
- The DFA can determine match existence and bounds, then defer capture
  extraction until groups are observed.
- Recent work preserved source-level non-capturing groups as explicit AST nodes
  so simplification can distinguish semantic transparency from source shape
  that affects capture behavior.

The remaining weak point is retained quantified capture behavior.  The current
retained-repeat mechanism records extra metadata in the compiled `Prog`, lets
the normal engine choose the match, and then lazily repairs selected capture
registers when public APIs observe them.  That repair searches within the
matched span to rediscover how a repeated subexpression could have been
partitioned.

That approach fixed important compatibility bugs, but it has the wrong shape:

- It reconstructs capture semantics after match selection instead of computing
  them as part of the selected execution path.
- It performs additional regex searches over candidate partitions.
- Its cost is not obviously bounded by one linear pass over the input.
- It creates a second semantic layer that must stay consistent with the real
  execution engines.

## Design Principle

Capture semantics must be part of linear execution.

> Every capture-aware execution path must return final JDK-compatible capture
> state directly.  Public APIs must not repair capture values by running
> additional regex searches over candidate explanations for a match that has
> already been selected.

This does not require the DFA to carry general capture state.  The DFA may still
reject inputs or narrow a candidate range.  It also does not require capture
extraction to happen eagerly when callers only need `group(0)`.  Deferred
capture extraction remains valid.

The requirement is narrower and stricter: once captures are observed, the
capture-aware engine pass must compute them directly from bounded execution
state.  It must not infer them by enumerating repeat starts, repeat ends, or
split points after the fact.

The proof obligation is not to simulate JDK backtracking history.  It is to
show that, for SafeRE's supported regular syntax, the JDK-visible capture result
can be computed from the compiled program, the current input position, capture
registers, and any additional state whose size is bounded by the compiled
program rather than by the input length or by the number of possible repetition
partitions.

## Required Semantics

The capture-aware execution model must handle at least these cases:

- ordinary captures and nested captures;
- captures inside greedy and lazy quantified expressions;
- captures inside nullable repeated bodies;
- captures inside alternation and optional branches under repetition;
- repeated captures whose later iteration fails or matches zero width;
- replacement APIs that consume group values through `$n`, `${name}`,
  functional replacements, and `appendReplacement`;
- deferred capture extraction after DFA range narrowing;
- Pike VM NFA execution as the semantic reference path;
- BitState execution where it is proven equivalent, with a principled guard to
  the Pike VM NFA where supporting the case in BitState would weaken its bounded
  exploration invariant.

The compiler must also preserve group numbering for captures inside zero-count
repetitions, even though those groups cannot participate at run time.

It should continue to honor SafeRE's documented divergence for JDK failed-start
capture leakage when that behavior would require incompatible backtracking
state.

## Proposed Direction

Move retained quantified capture semantics into the compiled execution model,
with the Pike VM NFA as the authoritative capture-aware execution path.

This is the preferred architectural direction, not yet a complete mechanical
specification.  It should be validated by first defining the exact capture state
transitions for representative quantified constructs and proving that the needed
state is bounded by the compiled program.

The compiler should lower capture-retention requirements into explicit bounded
program structure or state transitions that the authoritative capture-aware
engine executes inline.  The exact instruction shape can be refined during
implementation, but the model should look like this:

1. Identify quantified regions where existing Thompson `CAPTURE` writes are
   insufficient to represent JDK-visible retention after a later iteration
   exits, fails, or matches zero width.
2. Emit explicit program operations, annotations, or control-flow structure that
   tell engines when a capture snapshot must be retained, restored, or ignored.
3. Have the Pike VM NFA maintain that state per active thread, alongside
   existing capture registers and loop progress registers.
4. Ensure a successful `MATCH` instruction already carries final group state.
5. Allow OnePass and BitState only for cases where they are proven equivalent,
   or guard affected patterns to Pike VM NFA capture extraction.
6. Delete post-match retained-repeat repair once the authoritative path computes
   the same observable captures directly.

The important bound is that the authoritative path may keep a fixed amount of
additional state per capture group, compiled checkpoint, and active thread.  It
must not keep unbounded history for every repetition iteration.  BitState may
share the same model only if doing so preserves its bounded visited-state
argument.

### Pros

- Capture values are produced by the same execution path that selects the match.
- Post-match partition searches are eliminated.
- DFA, OnePass, and BitState acceleration can remain available when they are
  semantically reliable.
- The linear-time argument can be tied to instruction transitions plus bounded
  per-thread state.
- Replacement APIs, snapshots, and direct group access all consume the same
  capture result.
- The design avoids interpreting the original AST after a match.

### Cons

- This is likely the hardest implementation option.
- JDK quantified-capture behavior is subtle, especially around nullable bodies,
  failed iterations, and nested repetitions.
- NFA priority and epsilon-closure behavior may need careful changes because
  the NFA becomes the semantic authority for these captures.
- BitState is high risk: adding runtime capture values to its visited key would
  weaken the bounded-exploration invariant.
- Overly broad retention tracking could add overhead to ordinary capturing
  patterns.
- The exact retain/restore/clear rules still need to be specified before coding.

## Candidate Implementation Shape

One plausible implementation is to add capture-retention checkpoints to `Prog`.

Conceptually:

- `CAPTURE` continues to write the current input position into one capture
  register.
- Quantifier compilation marks regions where a capture value must survive a
  later failed or zero-width iteration.
- The engine maintains the extra bounded state needed for those marked regions.
- Entering, exiting, or retrying a marked quantified body updates that state
  according to the same priority order the engine already uses for alternatives
  and repetition.
- By the time a thread reaches `MATCH`, the normal capture array is already the
  authoritative public capture state.  An implementation may get there by
  restoring captures during execution or by folding bounded retained state into
  the capture array before acceptance.

This could be encoded as new instructions, as metadata attached to existing
`ALT`/`PROGRESS_CHECK`/`CAPTURE` instructions, or as compiler-generated control
flow.  The choice should be guided by simplicity, by the ability to make the NFA
the source of truth, and by whether BitState can share the same semantics
without expanding its visited key by runtime capture values.

The design should avoid:

- source-AST interpretation after a match;
- nested `Nfa.search()` calls during `group()` or replacement;
- scanning all possible repeat starts or split points;
- recursive tree or program walks on user-controlled nesting depth;
- state whose size grows with the number of repetition iterations.

## Implemented Approach

The implementation currently uses the compiler-generated control-flow variant,
not new `Prog` instructions or explicit NFA retention registers.

The implemented model is:

- compile-time analysis identifies quantified-capture shapes where ordinary
  Thompson `CAPTURE` writes would expose the wrong retained value;
- the compiler lowers only the cases it can model safely by preserving the
  first capture-producing copy and suppressing later overwrites for the
  retained capture boundary;
- the lowering is deliberately guarded away from cases where it would change
  the selected path's observable captures, including alternation inside the
  retained body, nullable capture-retaining inner quantifiers, and nested
  captures that should continue to be overwritten by the selected later path;
- lazy outer quantifiers use the same retained-capture lowering when it is safe,
  with an explicit zero-iteration arm for quantifiers that can legally match
  zero copies so ordinary empty `find()` behavior is preserved;
- affected capture-aware execution paths avoid OnePass and BitState and use the
  Pike VM NFA as the semantic authority;
- post-match retained-repeat repair has been removed, including
  `Prog.RetainedRepeatCapture`, `Matcher.applyCaptureDebugInfo()`, and the
  helper searches over candidate repeat starts, ends, and split points.

This approach satisfies the central invariant: public capture observation no
longer reconstructs captures by running extra regex searches after a match has
already been selected.  It also keeps the implementation simple by using the
existing Pike VM NFA capture array as the authoritative result.

It is intentionally narrower than the explicit-retention-state design.  The
compiler lowering is a proven-enough implementation strategy for the covered
regular-syntax cases, but it is not a general mechanism for modeling arbitrary
capture history.  When the compiler cannot safely lower a shape, it leaves the
ordinary compiled structure in place and relies on the Pike VM NFA path.

## Remaining Work

The following parts of the fuller design remain available but are not currently
implemented:

- explicit capture-retention instructions or checkpoint metadata in `Prog`;
- explicit bounded retention state in Pike VM NFA threads;
- a BitState implementation of the same retention model;
- a reusable generated differential suite dedicated to quantified-capture
  semantics (#260);
- benchmark results for quantified-capture workloads with and without inner
  group access, replacement APIs, BitState-sized inputs, and #258-style
  pathological inputs (#261).

At the moment these are risk-reduction items rather than known user-visible
fixes.  The current implementation already removes the superlinear repair path
and passes focused differential tests for retained quantified captures,
alternation under repetition, nested captures, nullable and lazy cases,
replacement APIs, and a broad local generated probe, along with the full
`safere` module test suite.

## Revisit Criteria

Revisit the explicit-retention-state design if any of the following happen:

- a supported regex produces JDK-incompatible captures that cannot be fixed by
  narrowing or extending compiler lowering without adding brittle case logic;
- a quantified-capture bug depends on suffix failure, nullable iteration, or
  nested alternation in a way that shows the existing Pike VM NFA result is not
  enough without additional bounded state;
- benchmarks show the compiler-generated control flow causes unacceptable
  overhead on realistic quantified-capture workloads;
- BitState support for affected patterns becomes important enough that guarding
  to Pike VM NFA is no longer an acceptable performance tradeoff;
- future engine changes make it harder to reason about which path is the
  semantic authority for capture extraction.

If none of those triggers occur, the observable behavior of the explicit
retention-state design would likely be the same as the current implementation
for supported syntax.  Its main benefit would be a stronger internal proof and
less reliance on compiler-lowering analysis, not a known public API difference.

## Alternatives Considered

### Keep Post-Match Repair

The current retained-repeat mechanism records metadata during compilation and
repairs selected capture registers after a match has already been chosen.

This is the approach this design is intended to replace.  It can fix individual
compatibility bugs, but it reconstructs public capture state by searching
candidate repeat partitions after the fact.  That creates a second semantic
layer and risks superlinear behavior when captures are observed.

### Disable Simplifications Around Captures

One way to preserve source capture behavior is to avoid AST rewrites whenever a
capturing group appears under a construct whose semantics are subtle.

This helps with simplification-induced bugs, but it is not sufficient.  Capture
semantics can still diverge during engine selection, deferred capture
extraction, nullable repetitions, and replacement APIs.  It would also give up
normalization and performance broadly instead of modeling the semantic
requirement directly.

### Always Use NFA For Capturing Patterns

Another simple rule would be to route all capture-observing operations through
the Pike VM NFA and avoid DFA/OnePass/BitState capture inconsistencies.

This is too broad.  The DFA is still valuable for rejection and range narrowing,
OnePass is correct and efficient for eligible patterns, and BitState is useful
for small inputs.  More importantly, this does not by itself solve quantified
capture retention: the NFA still needs the correct execution-time capture state.

The design does adopt a narrower version of this alternative: Pike VM NFA is the
fallback semantic authority for complex capture cases.  That is different from
using it for all capturing patterns.  Straightforward captures should still be
eligible for faster engines when equivalence is established.

### Original-AST Capture Extraction

After an accelerator finds a match range, SafeRE could re-evaluate the original
unsimplified AST to recover captures.

This would duplicate engine semantics and risks reintroducing backtracking,
stack-safety problems, or candidate-partition enumeration.  It also creates a
second matcher whose behavior must stay consistent with the compiled engines.
The capture-aware compiled engine should be the source of truth instead.

### Make The DFA Capture-Aware

SafeRE could extend DFA states to carry enough capture information to return
full submatches directly.

This is not a good fit for general capture extraction.  Capture-sensitive DFA
state can grow quickly, and SafeRE already has capture-capable engines for the
cases where captures are observed.  The DFA should continue to prove match
existence and bounds where reliable, then delegate capture extraction to a
linear capture-aware engine.

### Broaden Documented Divergence From JDK

SafeRE could declare more quantified-capture behavior intentionally different
from `java.util.regex`.

That should be a last resort.  These capture values are public API results and
the affected patterns appear in real code.  SafeRE should diverge only where JDK
behavior fundamentally depends on features or backtracking state that conflict
with the linear-time guarantee.

## Open Design Questions

This section is a provisional state-transition table for quantified captures,
not a final spec.  Rows called out as unresolved need differential examples
before implementation.  In particular, the compiler analysis that decides
whether a quantifier needs retention semantics is still an open design question.

| Event | Capture-register behavior | Notes |
|---|---|---|
| Enter quantified body | Start with the thread's current capture registers.  If compiler analysis has marked the quantifier as needing retention semantics, establish bounded save state for the groups that can be affected by this body. | Entering the body must not by itself make a group participate.  The marking predicate is unresolved. |
| Capture participates in current iteration | Write the capture start/end registers normally through `CAPTURE` instructions. | Ordinary capture writes remain the primary mechanism. |
| Iteration succeeds after consuming input | The capture writes from that iteration become the current registers for the continuing thread.  Retention state may record the previous registers if later exit/failure semantics can make them visible. | This is the common "successful iteration updates captures" case. |
| Iteration succeeds without consuming input | The engine must take the zero-width repeat exit path required for progress.  The exact effect on captures written only by the zero-width iteration is unresolved and must be specified from JDK examples. | Tricky examples include `(a?)*` on `""` and `"a"`, `((a?))*` on `"aa"`, and optional captures under `*`. |
| Iteration fails after writing captures on a losing path | Restore the capture registers to the state of the surviving prioritized thread for the same attempted start and selected match.  Captures from the failed path must not leak into this public result. | This is normal prioritized NFA behavior, but retention state must obey it too.  This does not attempt to emulate JDK failed-start capture leakage, which remains a documented divergence. |
| Greedy repeat exits | The visible capture state is the state from the prioritized path selected by JDK-compatible greedy execution.  The exact retention rule after a final failed or zero-width attempt is unresolved and must be specified from differential cases. | Candidate examples: `(?:(a){1,}){2}` on `"aaa"`, `(?:(a){1,2})*` on `"aaa"`, and `((a)+){2}` on `"aaa"`. |
| Lazy repeat exits | The visible capture state should follow the first prioritized exit that allows the surrounding pattern to match.  The exact overwrite/restore behavior for later body attempts needs differential validation. | Candidate examples: `(?:(a){1,2}?)*` on `"aaa"` and lazy repeats followed by suffixes. |
| Nested repeat exits | Inner and outer retention state must compose by thread priority.  Exiting an inner repeat updates only the groups governed by that inner construct; exiting the outer repeat must preserve the capture state visible on the selected outer path. | This is unresolved and needs focused differential tests. |
| Alternation branch loses | Discard capture writes and retention updates from the losing branch. | Retention state must be backtracked exactly like capture registers. |
| Match accepted | The normal capture array is authoritative for public APIs.  Any bounded retention state has already been applied or discarded according to the selected path. | `group()`, replacement, and snapshots must not perform semantic repair. |
| Match rejected by suffix after repeat | Restore to the next surviving prioritized thread, including both capture registers and retention state. | Suffix failure must not leak captures from rejected repeat partitions. |

Compiler-only requirement:

| Case | Requirement | Notes |
|---|---|---|
| Zero-count repetition | Preserve group numbering, but groups inside the zero-count body do not participate.  Their start/end registers remain `-1` unless an outer selected path writes them elsewhere. | This is the #219 class.  It is not a runtime transition through the zero-count body. |

The main proof obligation is:

> All JDK-visible retained capture behavior for supported regex syntax can be
> represented with state bounded by the compiled program, not by the input length
> or by the number of repetition iterations.

If that proof fails for an ordinary JDK-supported quantified-capture construct,
the first fallback should be the Pike VM NFA or another conservative
capture-aware linear engine, not syntax rejection.  Rejection is appropriate
only when supporting the construct would fundamentally violate SafeRE's
linear-time model.

If the proof fails specifically for BitState, that is not a correctness blocker:
BitState is an optimization.  The principled response is to guard the affected
patterns to Pike VM NFA capture extraction unless and until BitState can carry
the same bounded semantics.

## Linear-Time Argument

The fix preserves linear time if every engine still processes a bounded number
of states per instruction and input position.

For the NFA, this means:

- each active thread carries capture registers plus bounded retention state;
- epsilon closure still uses iterative traversal;
- any additional retention transition is tied to an existing instruction
  transition;
- no transition starts a new regex search.

For BitState, this means:

- the implementation either preserves the existing bounded `(instruction,
  position)` exploration invariant or proves that any added exploration
  dimension is statically bounded by the compiled program, not by runtime
  capture values or input-dependent repetition counts;
- backtracking jobs restore retention state the same way they restore capture
  and loop registers;
- fallback to NFA remains available when BitState's bounded work budget is
  exceeded.
- patterns whose capture semantics would require BitState to distinguish
  runtime capture values or input-dependent repetition histories are guarded to
  the Pike VM NFA instead of expanding the visited key.

The asymptotic requirement is:

```text
O(input length * compiled program size * bounded capture-state factor)
```

The bounded capture-state factor may depend on the number of capture groups or
compiled retention checkpoints, but not on the length of the input or on the
number of possible repetition partitions.

## Performance Expectations

This design intentionally prioritizes a clear linear-time guarantee over using
every accelerator for every capture-observing pattern.

Expected performance impact:

- Ordinary literal, prefix, non-capturing, and simple-capture patterns should be
  mostly unaffected.
- Complex quantified-capture patterns may lose OnePass or BitState acceleration
  when those engines are not proven capture-equivalent.  Those cases may be
  slower by a constant factor, especially on small inputs where BitState would
  otherwise be cheap.
- `find()` loops that only need match existence or `group(0)` can still benefit
  from DFA rejection or range narrowing where group-zero bounds are reliable.
  The capture-aware cost should be paid when inner captures are observed.
- Replacement APIs that reference captures should use the authoritative
  capture-aware path and may therefore pay the same cost as explicit group
  access.
- Pathological retained-repeat cases should improve asymptotically because
  public capture observation no longer launches post-match searches over
  candidate repeat partitions.

The intended tradeoff is localized constant-factor cost for subtle
quantified-capture patterns in exchange for eliminating a superlinear failure
mode and making capture semantics easier to reason about.

Before opening a PR for implementation, benchmark at least:

- quantified captures with and without inner group access;
- replacement APIs using `$1`, `${name}`, and functional replacements;
- `find()` loops that observe only match existence or `group(0)`;
- small inputs where BitState was previously selected;
- #258-style pathological inputs to confirm scaling behavior.

## Testing Strategy

The tests should verify the invariant, not just individual repros.

### Differential Capture Coverage

Maintain a generated or parameterized suite comparing SafeRE with
`java.util.regex` for:

- zero-count repetitions;
- nested quantified captures;
- nullable repeated bodies;
- alternation and optional branches under repetition;
- greedy and lazy variants;
- bounded and unbounded repeats;
- public APIs that observe captures.

### Engine-Path Coverage

Force or induce different SafeRE paths and compare public results:

- direct NFA;
- BitState;
- DFA with deferred capture extraction;
- OnePass where semantically valid;
- replacement APIs with group references and functional replacements.

The expected result should be identical whenever the engine path is allowed for
the pattern.

### Linear-Time Coverage

Avoid wall-clock assertions for ordinary tests.  Prefer instrumentation that
counts forbidden behavior:

- no calls to retained post-match repair;
- no nested `Nfa.search()` from capture observation;
- bounded engine transitions relative to input length and program size.

Benchmarks can still measure performance, but correctness tests should assert
structural behavior.

## Migration Plan

1. Add instrumentation or test hooks that prove current retained-repeat repair
   performs post-match searches.  Use this to write fail-first coverage for
   #258 without relying on elapsed time.
2. Add focused differential tests for the retained quantified capture cases that
   must keep passing throughout the refactor.
3. Implement the new execution-time retention model in the Pike VM NFA first,
   because the NFA is the semantic reference engine.
4. While BitState does not yet support the new retention model, guard affected
   patterns away from BitState and use the NFA fallback.  This is acceptable as
   a staging step because the fallback remains linear.
5. Implement the same state model in BitState, including correct save/restore
   behavior during backtracking, or document a principled permanent guard if
   BitState cannot support the required semantics without weakening its bounded
   exploration invariant.
6. Route deferred capture extraction through the new capture-aware execution
   model.
7. Remove `Prog.RetainedRepeatCapture`, `Matcher.applyCaptureDebugInfo()`, and
   the helper searches over candidate repeat partitions.
8. Run focused quantified-capture tests, engine-path tests, `mvn -pl safere
   test -q`, and the public API crosscheck reactor before opening a PR.

## Acceptance Criteria

- No public API observes captures repaired by post-match regex searches.
- Quantified capture differential tests match `java.util.regex` for supported
  syntax and documented semantics.
- Pike VM NFA is the authoritative capture path for affected patterns.
- BitState produces the same capture state for affected patterns, or affected
  patterns are guarded away from BitState with a documented linear-time
  fallback.
- DFA-deferred capture extraction produces the same result as direct
  capture-aware execution.
- Replacement APIs observe the same captures as `group()`, `start()`, and
  `end()`.
- The implementation has a clear linear-time argument with no unbounded
  partition enumeration.
- The old retained-repeat repair metadata and helper searches are removed.
