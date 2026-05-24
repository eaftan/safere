# Grapheme And Region Matching Design

## Problem

SafeRE supports `\X` and `\b{g}` while also trying to be a drop-in
`java.util.regex` replacement.  These constructs are unusually sensitive to
Java's indexing model:

- public `Matcher` APIs use UTF-16 indices;
- ordinary regex atoms should consume a Unicode scalar when a surrogate pair is
  visible;
- a matcher region can split a surrogate pair or a grapheme cluster;
- transparent bounds let boundary matchers inspect text outside the region;
- opaque bounds make region edges visible to boundary matchers;
- repeated `find()` is defined in terms of Java character indices, not an
  abstract code-point cursor.

SafeRE's current implementation handles many of these cases with local
compatibility logic around the matcher and NFA.  Some of that logic is
principled, but some is patchwork: post-hoc candidate rejection, verifier
matchers, and repeated retries over nearby start positions.

That patchwork is the wrong direction.  It may match a reported JDK behavior,
but it risks turning one public match operation into many engine searches.  The
goal of this design is to replace that compatibility layer with engine-native
semantics that remain linear.  When exact observed JDK behavior cannot be
represented by those semantics, SafeRE should document an intentional
compatibility divergence rather than add more search loops.

## Specification Baseline

The JDK `Pattern` specification names both `\X` and `\b{g}`:

- `\X` matches a Unicode extended grapheme cluster.
- `\b{g}` matches a Unicode extended grapheme cluster boundary.
- Unicode support includes extended grapheme clusters.

The JDK `Matcher` specification defines regions using Java indices and says
that searches are limited to `[regionStart, regionEnd)`.  It also says that
transparent bounds allow lookaround and boundary constructs to see beyond the
region, while opaque bounds prevent those constructs from seeing beyond it.

Those rules are enough to define SafeRE's target model, but they do not specify
every observed JDK edge case involving:

- regions that split surrogate pairs;
- regions that split grapheme clusters;
- `\X` implemented internally using boundary logic;
- repeated `find()` after boundary-starting alternatives;
- transparent bounds combined with explicit trailing `\b{g}`.

For unspecified cases, SafeRE may match observed JDK behavior only when doing so
preserves the linear-time guarantee and fits the engine model below.

## Design Goal

The goal is not "match every JDK quirk at any cost."  The goal is a principled
design:

> Support grapheme matching with UTF-16 public coordinates, region-aware
> Unicode decoding, and pure boundary predicates, while preserving a single
> linear pass per engine search.

This implies three negative requirements:

- no verifier `Matcher` calls inside the match engine;
- no unbounded post-hoc retries after an engine has selected a candidate;
- no compatibility rule whose cost depends on repeatedly re-running the NFA
  over neighboring start positions.

## Supported Grapheme-Region Contract

SafeRE's supported grapheme-region behavior is the intersection of the JDK
specification, observed JDK behavior that fits this engine model, and SafeRE's
linear-time guarantee.  The active compatibility contract is:

1. Public positions are UTF-16 indices.
2. Candidate starts for `find()` remain inside the matcher region.
3. Ordinary consuming atoms never report bounds that end inside a valid
   surrogate pair.
4. A consuming `\X` may complete a surrogate pair whose high surrogate starts
   inside the region.
5. `matches()` remains strict about the logical region end, even when `\X`
   completion lets `find()` or `lookingAt()` report a bound past `regionEnd`.
6. End anchors in `find()` and `lookingAt()` may accept the `\X` scalar
   completion end when the logical region end splits that scalar.
7. Opaque bounds make region edges grapheme-boundary context edges.
8. Transparent bounds let grapheme boundary predicates and `\X` segmentation
   see context outside the region, while still keeping candidate starts inside
   the region.
9. Regional-indicator parity, extended-pictographic/ZWJ context, and
   Indic-conjunct linker state are computed relative to the active grapheme
   context, not by rescanning from each candidate start.
10. Opaque region-local grapheme behavior is compositional.  If an explicit
    `\b{g}` predicate is true, `\X` can consume at that position, and a trailing
    explicit `\b{g}` predicate is true, the concatenated regex can match even
    when observed JDK repeated-`find()` behavior skips that composition.
11. Unanchored `\X` chains may start at suffix positions inside a larger
    full-text grapheme cluster.  The atom-local grapheme view decides what is
    visible from that candidate start using bounded cached state.

The focused active tests for this contract are
`GraphemeRegionCompatibilityMatrixTest` for JDK-aligned cases and
`GraphemeRegionModelTest` for intentional SafeRE/JDK divergences.  The offline
exhaustive sweep remains a discovery tool, and
`GraphemeJdkImplementationDetailTest` quarantines exact JDK traces that are not
currently SafeRE compatibility requirements.

## Non-Goals

SafeRE should not try to reproduce every observed JDK edge case.  In
particular, it should not reproduce behavior that appears to fall out of the
JDK's backtracking implementation rather than from the documented regex
language or region API.

The non-goals are:

- no compatibility by verifier matcher;
- no compatibility by repeated re-search;
- no compatibility by pattern-string or input-shape exceptions;
- no support for behavior that cannot be stated as a bounded engine transition
  or a bounded zero-width predicate;
- no optimized-engine support for grapheme programs until that engine shares
  the canonical grapheme model.

The project should use judgment here.  If a JDK behavior is specified, linear,
and expressible in the model below, SafeRE should support it.  If a behavior is
unspecified, surprising, and only reproducible by adding local retry logic,
SafeRE should prefer a documented intentional divergence.

## Core Model

### Positions Are UTF-16 Indices

All engine positions, capture registers, matcher regions, search cursors, and
public result offsets are Java UTF-16 indices.  This is already close to how
SafeRE stores positions today.  The important point is to make it explicit:
positions are not code-point ordinals.

A transition may advance by one UTF-16 code unit, two code units, or more,
depending on what it consumes.  The position space remains UTF-16 either way.

### Consumption Uses A Region-Local Scalar View

For ordinary consuming atoms, SafeRE should decode a Unicode scalar at the
current UTF-16 position when the scalar is visible in the active consumption
range.

Examples:

- A valid surrogate pair fully inside the region is one scalar and is consumed
  as `[high, low]`.
- A low surrogate at `regionStart` is visible as an unpaired scalar value and
  may be consumed as one UTF-16 code unit.
- A high surrogate at `regionEnd - 1` that is paired with a low surrogate just
  outside the region is not an ordinary scalar.  Ordinary atoms must not report
  a match ending inside that surrogate pair.
- A scalar transition never consumes past `regionEnd`.

This preserves JDK-compatible public offsets without forcing the entire engine
to become UTF-16-code-unit based.  Full surrogate pairs still behave like one
Unicode scalar when they are visible.

Consuming `\X` has one extra limit: it may complete a surrogate pair whose high
surrogate starts inside the region.  That completion limit is separate from the
logical end position used by `matches()` and ordinary atoms.  End anchors may
accept the completion limit for `find()` and `lookingAt()`, but `matches()`
still performs its final whole-region check against the logical end.
For example, with region `[0,1]` over a supplementary character, `\X.find()`
may report `[0,2]`, while `\X.matches()` still fails because the logical region
end is `1`.

### Boundary Assertions Use A Separate Boundary View

Zero-width boundary predicates must not be implemented by substring
substitution.  They need an explicit boundary context:

- current UTF-16 position;
- consumption start and end;
- boundary-context start and end;
- transparent vs opaque bounds;
- anchoring bounds;
- cached grapheme state for the input.

The consumption range answers "what may this match consume?"  The boundary
context answers "what text may this assertion inspect?"

For opaque bounds, the boundary context is normally the region.  For transparent
bounds, boundary assertions may inspect the full input.  Anchoring bounds remain
separate because `^`, `$`, `\A`, `\Z`, and `\z` have their own region semantics.

## `\X` As A Primitive

SafeRE should stop compiling `\X` as a regex expansion plus a synthetic
grapheme boundary.  That expansion couples a consuming construct to
empty-width machinery, which is where region and transparent-bound semantics are
most complicated.

Instead, `\X` should compile to a primitive consuming instruction, for example
`GRAPHEME_CLUSTER`.

The transition is:

```
end = nextGraphemeClusterEnd(text, pos, consumptionContext, graphemeContext)
if end >= 0:
  add thread at end
```

The primitive owns the cluster-consumption rule.  It does not ask the
empty-width assertion machinery whether a synthetic boundary happens to hold.

This separates two concepts that are different in the public language:

- `\X` consumes one grapheme cluster within the matchable region.
- `\b{g}` is a zero-width boundary assertion affected by boundary visibility.

### Linear-Time Requirement For `\X`

`nextGraphemeClusterEnd` must be linear over a full match operation.  There are
two acceptable implementation strategies:

- compute a grapheme-boundary table for the active input once per matcher input;
- compute boundaries lazily while caching enough per-position state that each
  UTF-16 position is classified a bounded number of times.

The result must be bounded by:

```
O(pattern_size * input_utf16_length) + O(input_utf16_length)
```

The grapheme segmenter may keep finite-state context such as regional-indicator
parity, extended-pictographic/ZWJ state, and Indic-conjunct linker state.  It
must not scan backward through an unbounded prefix for each boundary query.

That finite-state context is scoped to the active grapheme view.  For example,
regional-indicator parity for an opaque region starts at the boundary-context
start, not at the beginning of the full input.  A transparent boundary predicate
may use full-input parity.  Consuming `\X` still starts only at candidate
positions inside the region, but under transparent bounds its grapheme
segmentation context can see text outside the region.

## Explicit `\b{g}` As A Pure Predicate

Explicit `\b{g}` should remain an `EMPTY_WIDTH` predicate, but its input should
be a named boundary context rather than ad hoc matcher fields.

The predicate should answer:

```
isGraphemeBoundary(text, pos, boundaryContext, graphemeContext)
```

It should not run a matcher.  It should not retry from a different start
position.  It should not depend on which engine path selected the candidate.

For transparent bounds, the predicate may inspect text outside the region.  For
opaque bounds, region edges act as boundaries for predicate purposes where the
JDK specification assigns that role to opaque bounds.  That remains true when a
region edge cuts through a non-surrogate grapheme cluster such as a base-plus-mark
or Hangul sequence.

## Search Starts And Repeated `find()`

Repeated `find()` must use UTF-16 cursor semantics because the Java API is
defined in Java indices.  After a non-empty match, the next search starts from
the prior match end.  After an empty match, it advances by one UTF-16 code unit,
subject to the region end.

For grapheme programs, the engine may need to consider candidate starts at
UTF-16 offsets that are not Unicode-scalar boundaries in the full input.  That
does not mean every match should consume one code unit.  It means the
region-local scalar view must decide what is visible at that candidate start.

The important invariant is:

> Candidate starts may be UTF-16 positions, but confirming a candidate is still
> an engine-native operation with bounded local context.

This rules out scanning a list of candidate starts and launching fresh
matchers until one reproduces observed JDK behavior.

## Engine Context Object

The matcher should pass one explicit context object into all engines that can
execute grapheme or boundary constructs.

The context should contain at least:

- `String text`;
- `int searchStart`;
- `int searchLimit`;
- `int consumeStart`;
- `int consumeEnd`;
- `int boundaryStart`;
- `int boundaryEnd`;
- `boolean transparentBounds`;
- `boolean anchoringBounds`;
- cached grapheme segmentation data.

`consumeEnd` is not the same as `boundaryEnd`.  Transparent trailing-boundary
cases require exactly that distinction: the match may be unable to consume past
the region, while an explicit boundary assertion may still inspect the
following text.

Similarly, `consumeStart` is not always the same as `boundaryStart`.  A region
can begin inside a surrogate pair or a larger grapheme cluster.  The consuming
view may treat the visible low surrogate as a standalone unit, while a
transparent boundary predicate can still see the paired high surrogate before
the region.

Each primitive `\X` atom also has its own consuming view start.  In an
unanchored search, a candidate can begin at a UTF-16 low-surrogate offset inside
a supplementary code point.  The active `\X` atom should treat that visible low
surrogate as unpaired for its own cluster consumption, without changing the
boundary context used by explicit `\b{g}` assertions.

Because unanchored search can create many distinct `\X` atom starts, grapheme
state such as regional-indicator parity and Indic-conjunct linker visibility
must be cached by input position/run rather than by every possible atom start.
Boundary queries with arbitrary context starts must remain O(1).

When the NFA deduplicates active `GRAPHEME_CLUSTER` instructions, the key must
include bounded grapheme-consumption state, not just the instruction id.  For
example, a thread whose atom-local view can see an extended pictographic before
a ZWJ is not equivalent to a thread that started at the ZWJ.  The key must also
not include the exact arbitrary atom start, because long extender runs can create
many starts with equivalent future behavior.

## Engine Responsibilities

### Parser

The parser should preserve `\X` as a distinct AST operation instead of expanding
it into a normal regex tree with a synthetic boundary.

### Compiler

The compiler should emit a dedicated instruction for `\X`.  This instruction
is a consuming instruction, not an `EMPTY_WIDTH` assertion.

### Pike VM NFA

The Pike VM NFA should be the semantic authority for grapheme programs.

It must:

- consume ordinary character ranges through the region-local scalar decoder;
- consume `GRAPHEME_CLUSTER` through the grapheme segmenter;
- evaluate `\b{g}` through the boundary predicate;
- keep all of the above inside the normal thread-queue execution model.

### DFA, OnePass, BitState, And Fast Paths

No optimized path should implement independent grapheme compatibility rules.

The conservative first design is:

- grapheme programs run through Pike VM NFA;
- accelerators may only narrow candidate positions when they are proven not to
  skip valid UTF-16 starts;
- DFA, reverse DFA, OnePass, and BitState remain guarded out for grapheme
  programs until they can share the same scalar decoder, grapheme transition,
  and boundary predicate.

Later optimization is possible, but each optimized engine must use the same
context object and the same primitive grapheme operations.  It must not
duplicate compatibility logic.

## Linear-Time Invariants

The implementation must preserve these invariants:

1. Each engine search performs at most bounded work per instruction per UTF-16
   position.
2. Grapheme-boundary classification is O(1) amortized per UTF-16 position.
3. `\X` transition computation never scans an unbounded prefix per candidate.
4. Boundary predicates never invoke a regex engine.
5. Candidate filtering never reruns the full NFA over a sequence of nearby
   starts.
6. Capture resolution for grapheme programs stays inside the selected engine
   result and does not search alternate explanations after the match.
7. Engine-path selection cannot change grapheme semantics.

If a compatibility behavior requires violating one of these invariants, SafeRE
must not implement it as observed-JDK compatibility.  It should be documented as
an intentional divergence or held until a linear formulation exists.

## Compatibility Policy

SafeRE should classify grapheme-region behavior in this order:

1. If the JDK specification clearly defines the behavior and the behavior is
   compatible with linear time, implement it.
2. If the specification is silent or ambiguous, match observed JDK behavior only
   when it can be expressed by the engine context model above.
3. If observed JDK behavior requires verifier matchers, repeated re-search,
   pattern-specific exceptions, or stateful local hacks, do not match it through
   patchwork.
4. If a JDK/spec contradiction is found, stop and decide explicitly which side
   SafeRE follows under the project compatibility policy.

This policy is especially important for repeated `find()` after
boundary-starting grapheme expressions.  SafeRE intentionally does not copy
observed JDK traces where `\X`, `\b{g}`, and `\b{g}\X\b{g}` do not compose
cleanly.  Repeated `find()` resumes at the previous non-empty match end, and
the next candidate is evaluated by the same region-local grapheme predicates as
any other candidate.

## Migration Plan

### Phase 1: Freeze Patchwork

- Do not add new verifier matcher calls.
- Do not add new post-hoc retry predicates around grapheme candidates.
- Keep existing regression tests and divergence sweeps as inventory, but
  classify failures by whether they fit the new model.

### Phase 2: Introduce Context Types

- Add an engine context object that separates consumption bounds from boundary
  bounds.
- Route current NFA empty-width flag computation through that object.
- Remove duplicated matcher fields once the context carries the same facts.

### Phase 3: Add Primitive `\X`

- Add a `RegexpOp` for `\X`.
- Add an `InstOp` for grapheme-cluster consumption.
- Compile `\X` directly to that instruction.
- Keep explicit `\b{g}` as an empty-width assertion.

### Phase 4: Centralize Grapheme Segmentation

- Implement a single grapheme segmenter used by both `\X` and `\b{g}`.
- Cache per-input boundary state so all boundary checks are O(1) amortized.
- Include region-local surrogate handling in the scalar decoder, not in matcher
  candidate filters.

### Phase 5: Remove Verifier Retries

- Delete verifier matcher checks from grapheme candidate validation.
- Replace accepted behaviors with engine-native semantics.
- Document any remaining observed-JDK divergences that cannot be represented
  without risking non-linear behavior.

### Phase 6: Re-enable Optimized Paths Carefully

- Keep grapheme programs on Pike VM NFA until optimized engines share the same
  context and primitive operations.
- Re-enable OnePass, BitState, or DFA support only with equivalence tests
  proving the same `\X`, `\b{g}`, region, and capture behavior.

## Testing Requirements

Regression tests should cover behavior classes, not issue-specific strings:

- `\X` on full surrogate pairs, split surrogate pairs, combining marks, ZWJ
  sequences, Hangul sequences, regional indicators, controls, prepend
  characters, and Indic conjuncts;
- explicit `\b{g}` at region starts, region ends, split surrogate positions,
  and split grapheme positions;
- transparent and opaque bounds for both consuming `\X` and explicit `\b{g}`;
- repeated `find()` after non-empty matches and empty boundary matches;
- alternatives that mix `\X`, explicit `\b{g}`, literals, and empty branches;
- capture groups around `\X` and `\b{g}`;
- forced-engine tests proving that unsupported optimized paths stay guarded out
  until they share the canonical grapheme model.

Scaling tests should assert algorithmic behavior, not wall-clock constants.
They should include inputs with long runs of combining marks, regional
indicators, ZWJ sequences, repeated low surrogates, and Indic linker sequences.

## Open Questions

- Should SafeRE intentionally diverge from observed JDK behavior when `\X` and
  explicit `\b{g}` disagree under transparent bounds, if the specification does
  not define the interaction?
- Should the grapheme boundary cache be a full boundary table, a compact
  per-position state table, or a lazy cache tied to matcher input?
- Which optimized engines should be allowed to support grapheme programs first?

## Acceptance Criteria

This redesign is complete when:

- `\X` is represented as a primitive consuming instruction;
- explicit `\b{g}` is represented as a pure boundary predicate;
- region-local consumption and transparent boundary context are separate in the
  engine API;
- grapheme matching uses no verifier matchers and no unbounded retry loops;
- focused JDK compatibility tests pass for all behaviors that fit the model;
- every remaining observed-JDK divergence is documented with the linear-time
  reason SafeRE does not emulate it;
- scaling tests show linear behavior for grapheme-heavy inputs.

## Regression Checklist

The implementation and tests should preserve these interaction points:

- When `\X` completes a split trailing surrogate under opaque bounds, a trailing
  explicit `\b{g}` uses the same effective grapheme edge.  The logical region
  end still controls ordinary atoms and strict `matches()` behavior.
- A split low surrogate can be a region-local `\X` consumption start, but a
  trailing explicit `\b{g}` after that consumed low surrogate must not become an
  unconditional boundary.  Contextual rules such as regional-indicator parity,
  extended-pictographic/ZWJ state, Hangul continuation, extender and
  spacing-mark handling, and Indic-conjunct linker state still apply.
