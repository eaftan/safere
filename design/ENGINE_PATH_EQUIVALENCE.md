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
- `charClassReplaceAll` is currently a replacement-only fast path that bypasses
  `find()` entirely for simple replacements over character-class runs.

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

The positive design assumption is that this problem is solvable because SafeRE
does not need every engine to implement every semantic detail.  It needs one
general semantic authority plus machine-checkable boundaries around every
shortcut.  A fast path is safe when it cannot claim more public-result authority
than its declared role permits.

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

A path's contract should name both the result fields it owns and the fields it
must leave to shared code.  For example, a candidate-start accelerator may own
only `effectiveStart`; it must not own match end, captures, replacement append
position, or end-state flags.  A complete-result fast path may own group-zero
bounds only if it also proves that no omitted public state can differ.

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

The current concrete audit target is `charClassReplaceAll`.  It bypasses
`find()` for patterns that are a single character class under a `+` quantifier
and whose replacement string has no group references or escapes.  That can be a
valid authoritative producer, but its contract must be explicit:

- the pattern must be non-nullable, so replacement cannot depend on zero-width
  match advancement;
- there must be no user-visible capture substitution in the replacement;
- active region behavior must either be unsupported by that path or proven
  identical to the canonical replacement loop;
- append position and copied text must match the sequence of canonical
  `find()` results;
- `hitEnd()` and `requireEnd()` must either be updated through shared code or
  documented as unaffected by this public operation.

If any of those conditions cannot be proven, `replaceAll` should use the
canonical loop and reserve fast-path work for replacement-template parsing or
literal copying.

## End-State Boundary

`hitEnd()` and `requireEnd()` are observable public state, but they are not
ordinary engine outputs today.  SafeRE currently recomputes them after the
match through shared `Matcher` logic, using match bounds, region bounds,
end-constraint metadata, and limited AST-derived extension samples.

This design should keep that recomputation centralized, but it must make the
boundary explicit:

- engine paths must provide authoritative match existence and group-zero bounds
  before end-state is computed;
- partial producers must mark provisional group-zero bounds so end-state is not
  computed from stale or approximate data;
- fast paths that bypass shared `Matcher` completion code must either call the
  shared end-state updater or document why the public operation cannot expose a
  different end state;
- forced-path tests must compare `hitEnd()` and `requireEnd()` after the same
  public operation, not only the immediate match result.

This boundary keeps end-state handling in the matcher state-machine layer while
still requiring every engine path to supply enough canonical facts for that
layer to be correct.

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

Initial inventory:

- literal `matches()` and `lookingAt()` paths: authoritative producers for
  group-zero-only literal patterns;
- literal `find()` path: authoritative producer for group-zero-only literal
  patterns, subject to cursor, region, and end-state completion;
- character-class `matches()` path: authoritative producer only for the
  specific whole-pattern character-class forms it recognizes;
- prefix, character-class prefix, and start acceleration: filters that only
  choose candidate start positions;
- keyword alternation path: guarded optimization that must prove equivalent
  match bounds and captures for its recognized pattern family;
- OnePass paths: guarded authoritative producers;
- DFA sandwich and reverse-first paths: filters or partial producers for
  group-zero bounds, with deferred capture authority elsewhere;
- BitState: guarded authoritative producer for small inputs when its bounded
  visited-state model is semantically sufficient;
- Pike VM NFA: general authoritative producer;
- `charClassReplaceAll`: replacement-only authoritative producer candidate
  that needs explicit proof or should be routed through the canonical loop.

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

This audit should start with `charClassReplaceAll`, because it is intentionally
not just a faster implementation of replacement-template parsing.  It computes
the entire replacement result without creating matcher results.  Keeping it is
reasonable only if its preconditions and tests prove that it is equivalent to
the canonical loop for the whole public operation.

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

The concrete mechanism should be a test-only engine configuration, exposed only
to package-private tests.  It should let tests disable or force these choices:

- literal fast paths;
- character-class match and replacement fast paths;
- keyword alternation;
- prefix and start acceleration;
- anchored and unanchored OnePass;
- DFA sandwich and reverse-first DFA;
- BitState;
- lazy versus eager capture extraction.

The test harness should run the same public API trace under multiple
configurations and compare:

- boolean operation results;
- `group`, `start`, and `end` for every group that is legal to observe;
- `toMatchResult()` snapshots;
- replacement strings and append positions;
- `hitEnd()` and `requireEnd()`;
- subsequent `find()` behavior after empty and non-empty matches.

For paths that are only filters, tests should verify that enabling the filter
does not change the canonical public result.  For paths that are authoritative
producers, tests should verify that forcing the path gives the same result as
forcing the Pike VM NFA or canonical shared loop for the same supported case.

### 6. Make Contracts Machine-Checkable

The contracts should not remain only prose.  The implementation should make
incorrect authority difficult to express and easy to test.

The preferred enforcement shape is:

- use typed internal results, or an equivalent authority wrapper, so a path
  cannot return "matched" without declaring whether it owns no-match, candidate
  start, group-zero bounds, captures, replacement result, and end-state facts;
- use named guard predicates for semantic capabilities, not issue-specific
  booleans;
- keep a package-private path inventory or registry that tests can inspect to
  require forced-path coverage for every authoritative or guarded path;
- add test-only assertions that filters only change candidate starts, partial
  producers mark unresolved fields, and complete producers populate all fields
  they claim to own;
- include negative guard tests where useful: force a guarded path on a pattern
  it should reject and verify that the public trace diverges from the canonical
  path, proving the guard has semantic content.

This is not a formal proof of Java `Matcher` compatibility.  It is a practical
machine-checkable contract: the type shape, guard predicates, path inventory,
and forced-path tests should make a new optimization fail loudly if it tries to
define independent public semantics.

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
- the current path inventory above has either code comments, named predicates,
  or tests enforcing each claimed role;
- result authority is represented in code by typed results, an authority
  wrapper, or an equivalent mechanism that tests can inspect;
- each semantic guard has positive and, where practical, negative coverage;
- replacement operations share canonical search semantics or have documented
  equivalence proofs;
- `charClassReplaceAll` is either proven equivalent to canonical replacement
  semantics or removed in favor of the canonical loop;
- engine guards are named by semantic reason rather than by issue-specific
  conditions;
- a package-private forced-path test harness covers the matrix above;
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
