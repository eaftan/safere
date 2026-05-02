# Engine-Path And Fast-Path Equivalence Design

## Problem

SafeRE does not execute every public operation through one engine.  A single
`Matcher.find()`, `matches()`, `lookingAt()`, `replaceAll()`, or
`appendReplacement()` call may use literal fast paths, keyword alternation,
prefix acceleration, OnePass, DFA range narrowing, BitState, or the Pike VM
NFA.  That split is central to SafeRE's performance story, but it creates a
semantic risk:

> The public result can accidentally depend on which internal path happened to
> run.

That is unacceptable for a drop-in `java.util.regex` replacement.  Users should
not be able to observe a different match, replacement cursor, capture group,
`hitEnd()`, or `requireEnd()` result because an input was short enough for
BitState, long enough for the DFA sandwich, literal enough for `String.indexOf`,
or shaped enough for OnePass.

Recent bugs show this is a real class of failures:

- #251: a direct replacement fast path duplicated `find()` behavior and missed
  the start-anchor invariant.  `replaceAll` performed an extra replacement for
  `^(.*)` even though the canonical `find()` sequence should stop after the
  anchored match.
- #227: BitState crashed on an empty branch before a complex character class,
  while the public operation should have behaved like the other engines.
- #248 through #250 and #258: OnePass, DFA/deferred capture extraction,
  BitState, and NFA must agree on the observed capture result, or the optimized
  engine must be guarded out for that pattern and operation.

The common failure mode is not that fast paths exist.  It is that a fast path
can become a second implementation of public semantics instead of an
optimization of a canonical operation.

## Current State

SafeRE already has several useful pieces:

- `Matcher.doFindCore()` centralizes much of the `find()` dispatch.
- Literal and character-class full-match paths are constrained to cases with no
  user capture groups.
- Prefix and start accelerators narrow candidate start positions before a full
  engine confirms the match.
- DFA paths can return group-zero bounds and defer inner capture extraction.
- Capture-sensitive patterns can guard OnePass and BitState out when Pike VM
  NFA semantics are required.
- Generated crosscheck coverage already compares many public API operations
  against the JDK.

The weak point is that these pieces do not yet form an explicit contract.  Each
optimized path decides locally which parts of the public operation it is allowed
to compute:

- Some paths produce a complete public match.
- Some paths only reject impossible inputs.
- Some paths produce provisional bounds and defer captures.
- Some paths update matcher state directly.
- Replacement paths may loop over matches and update append cursors.

Without a common classification and test obligation, it is easy to add a local
optimization that is correct for match existence but wrong for anchors, empty
matches, replacement cursor updates, deferred capture access, regions, or
end-state flags.

## Design Principle

Fast paths may optimize canonical semantics; they must not define independent
semantics.

For a given pattern, input, region, matcher state, and public API operation, an
engine path must fit one of these roles:

- **Authoritative producer:** computes the complete public result for that
  operation.
- **Partial producer:** computes a documented subset of the result, such as
  group-zero bounds, and routes later observations through an authoritative
  path.
- **Filter:** proves that no match is possible, or narrows candidate positions,
  without committing to a public result.
- **Guarded optimization:** runs only when compile-time or runtime metadata
  proves equivalence to the authoritative path for the requested operation.

Any path that does not fit one of those roles should be removed or refactored
to share the canonical operation.

## Canonical Operations

The design should make the canonical public operation explicit before choosing
an engine.

### Search Request

Every search-like public operation should be reducible to a request containing:

- operation mode: `find`, `find(start)`, `matches`, or `lookingAt`;
- input text and active region bounds;
- transparent and anchoring bounds;
- current search cursor;
- whether a full match or anchored match is required;
- whether longest-match behavior is being requested internally;
- whether captures are needed eagerly, lazily, or not at all;
- whether replacement state will consume the result;
- the required end-state reporting for `hitEnd()` and `requireEnd()`.

This does not require a new public type.  It can be a private or package-private
internal model.  The important point is that the same facts are passed through
engine selection instead of being rediscovered by each fast path.

### Search Result

Every successful search path should return or populate a result with explicit
authority:

- match existence;
- group-zero start and end;
- whether inner captures are resolved;
- resolved capture registers when available;
- deferred-capture bounds when captures are not yet resolved;
- `hitEnd()` and `requireEnd()` observations;
- next-search cursor behavior for empty and non-empty matches.

Replacement APIs should consume this canonical result sequence.  They should
not have a separate direct replacement loop unless that loop is proven to
produce the same sequence, append positions, capture substitutions, and
zero-width advancement behavior as canonical `find()`.

## Engine-Path Contracts

### Literal And Character-Class Fast Paths

Literal and full-character-class fast paths may be authoritative producers only
when their preconditions exclude public state they do not compute.  For example,
the existing literal full-match path is appropriate for patterns with no user
capture groups because it can compute the full match result directly.

For `find()`, literal search may remain an authoritative producer when:

- there are no user capture groups;
- the match boundaries are exactly the literal occurrence;
- region and cursor bounds are applied exactly as canonical `find()` would;
- `hitEnd()` and `requireEnd()` behavior is either computed or proven irrelevant.

If a literal or character-class path cannot satisfy those conditions, it should
act only as a filter or accelerator and let an engine confirm the public result.

### Prefix And Start Acceleration

Prefix, character-class prefix, and start accelerators should be filters, not
semantic producers.  They may skip positions that cannot start a match.  They
must not decide:

- final match end;
- capture values;
- replacement append cursor;
- `hitEnd()` or `requireEnd()`;
- whether an anchored pattern can continue after its only possible start.

The #251 failure is the motivating example.  Start anchoring is a canonical
search invariant: once an anchored `find()` has searched the only possible start
position, later replacement iterations must not use a duplicated search loop
that treats an accelerated or direct path as if another start were possible.

### OnePass

OnePass may be an authoritative producer when metadata proves that its priority
model is equivalent to the public operation.  It should remain guarded out for
known non-equivalent cases, including nullable alternation and capture-retention
cases where the Pike VM NFA is the semantic authority.

OnePass eligibility should be expressed as an engine-path contract, not as
scattered local checks.  The metadata should answer: "Can OnePass produce the
same public result for this operation?" rather than only "Can this program be
executed by OnePass?"

### DFA Paths

DFA paths are excellent filters and group-zero producers, but they are not
general capture producers.  The DFA sandwich may:

- reject impossible searches;
- narrow to candidate group-zero bounds;
- produce reliable group-zero bounds when metadata proves reliability;
- defer inner captures to Pike VM NFA or another authoritative capture path.

When DFA-derived group-zero bounds are provisional, the result must mark them
as such so capture resolution can correct the match end before public APIs
observe it.  This is already part of the current design and should become an
explicit invariant.

### BitState

BitState is an optimization over small inputs, not the semantic authority for
every case.  It may be authoritative when:

- its visited-state invariant covers the requested operation;
- it computes the same captures as the Pike VM NFA for the pattern;
- its construction handles epsilon closure, empty branches, and complex
  character classes without assuming a simpler program shape.

If capture priority or program structure would require BitState's visited key
to include unbounded runtime capture values, BitState must be guarded out and
the Pike VM NFA should run instead.  This preserves both correctness and the
linear-time guarantee.

### Pike VM NFA

The Pike VM NFA should be the authoritative general-purpose search and capture
path.  Other engines may be faster, but their public result is valid only when
it matches the NFA/JDK-compatible semantics for the requested operation, or
when they defer observation back to that path.

This does not mean every operation must eagerly run the NFA.  It means the NFA
defines the general semantics for supported syntax, and every shortcut has a
clear proof or guard.

## Replacement Semantics

Replacement APIs are a high-risk area because they combine search semantics,
capture observation, append-position updates, and empty-match advancement.

`replaceAll`, `replaceFirst`, functional replacement, and
`appendReplacement` should all consume the same canonical match sequence:

- call the same match-finding machinery as `find()`;
- observe captures through the same resolution path as `group()`;
- update append positions from the canonical group-zero bounds;
- apply the same zero-width advancement rule as `find()`;
- stop after the only possible anchored match;
- preserve `hitEnd()` and `requireEnd()` state consistently with the search.

A replacement-only fast path is allowed only if it can be described as an
authoritative producer for the whole replacement operation and has equivalence
tests against the canonical loop.  Otherwise replacement should share the
canonical search loop and optimize only inside well-bounded pieces such as
literal copying or replacement-template expansion.

## Proposed Work

### 1. Classify Existing Paths

Document every `Matcher` execution path in code as one of:

- authoritative producer;
- partial producer;
- filter;
- guarded optimization.

The classification should live near engine dispatch, not only in this document.
Comments should state the public result fields the path owns and the fields it
must not decide.

### 2. Centralize Result Authority

Introduce a small internal representation for search results or make the
existing `groups`/deferred-capture state follow the same structure explicitly.
The goal is to make it impossible for a path to return "matched" without also
declaring whether group-zero, inner captures, and end-state flags are
authoritative.

This can be incremental.  The first version can wrap existing state transitions
without changing engine internals.

### 3. Share Replacement Search

Audit replacement APIs so they consume the canonical `find()` result sequence.
Any direct replacement path should either be removed or proven equivalent with
the same forced-path tests described below.

### 4. Consolidate Guards

Move scattered engine guards toward named predicates that encode semantic
questions:

- can this path produce authoritative captures?
- can this path preserve leftmost-first priority?
- can this path handle nullable alternation?
- can this path honor region and boundary semantics?
- can this path report end-state flags correctly?

This reduces the chance that a new fast path remembers one guard but misses
another.

### 5. Add Forced-Path Equivalence Tests

Tests should be able to force or simulate engine choices in package-private
coverage so the same public operation can be compared across paths.  The
minimum matrix should cover:

- anchors: `^`, `\A`, `$`, `\z`, multiline boundaries, and CRLF handling;
- empty matches and zero-width advancement;
- active regions, anchoring bounds, and transparent bounds;
- literal and prefix acceleration candidates;
- nullable alternation and optional branches;
- capture access after direct match, deferred capture resolution, and
  replacement substitution;
- `hitEnd()` and `requireEnd()` after successful and failed searches;
- replacement APIs, including `replaceAll`, `replaceFirst`, functional
  replacement, and `appendReplacement`.

Generated crosscheck remains the public oracle against `java.util.regex`.
Forced-path tests are the internal oracle that all SafeRE engine paths are
equivalent to one another where they claim to be authoritative.

## Linear-Time Argument

This design must not weaken SafeRE's linear-time guarantee.

The production rule is simple: equivalence checks are not production fallback
searches.  Production engine dispatch may run a bounded sequence of linear
passes, as it does today with DFA range narrowing plus deferred capture
extraction, but it must not compare multiple engines to choose a public result.

The proposed guards are compile-time or operation-local metadata checks.  The
proposed forced-engine comparisons live in tests.  In production:

- filters may scan the input linearly or less;
- authoritative producers may run one bounded engine pass for the operation;
- partial producers may defer to one authoritative capture pass when captures
  are observed;
- no path may enumerate candidate explanations, replacement partitions, or
  engine alternatives as a function of input length.

If an engine cannot prove equivalence without adding unbounded runtime state,
the principled answer is to guard it out for that case and use the Pike VM NFA
or another authoritative linear path.

## Acceptance Criteria

This design is complete when:

- each `Matcher` engine path has an explicit contract;
- replacement operations share canonical search semantics or have documented
  equivalence proofs;
- engine guards are named by semantic reason rather than by issue-specific
  conditions;
- forced-path tests cover the matrix above;
- generated public API crosscheck remains enabled for comparable behavior;
- no public operation repairs semantics by running post-match searches over
  candidate explanations;
- targeted regressions for #251 and #227 remain covered by the broader
  equivalence tests, not only by issue-shaped tests.

## Non-Goals

- Do not remove DFA, OnePass, BitState, literal, or prefix acceleration.
- Do not make the DFA carry general capture state.
- Do not run multiple engines in production just to compare answers.
- Do not emulate unsupported JDK features such as backreferences or lookaround.
- Do not add guards for individual regex strings; guards must correspond to
  semantic capabilities of an engine path.
