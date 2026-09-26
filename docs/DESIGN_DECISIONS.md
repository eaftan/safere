# Design decisions and rejected alternatives

Each entry records a decision that has been questioned or revisited, why it
stands, and the alternatives already evaluated. Before reopening one, check
whether its reason has changed. The contracts these decisions protect are in
[semantic invariants](INVARIANTS.md); the original discussions are in the
[design history](README.md#design-history).

## No backtracking features

**Decision:** Backreferences, lookaround, atomic groups, possessive quantifiers
over consuming operands, `\G`, and `\C` are rejected at parse time.
`Matcher.hitEnd()` and `Matcher.requireEnd()` are not exposed.

**Why:** Each requires non-regular semantics or the JDK's ordered backtracking
trace, which conflicts with worst-case linear time. Possessive modifiers over
statically zero-width operands are accepted because they normalize away without
consuming possessive semantics; see
[unsupported backtracking features](../INTENTIONAL_DIVERGENCES.md#unsupported-backtracking-features).

**Rejected:** Emulating `hitEnd()` and `requireEnd()` by reconstructing the JDK's
backtracking search order.

## Code points, not UTF-16 units

**Decision:** Matching, assertions, and position tracking operate on Unicode
scalar values over `String` or UTF-8 storage. Reported coordinates use the
storage's own units.

**Rejected:** RE2's `\C` (match any byte), which has no meaningful equivalent in
Java's string model.

## One set of engines behind a representation-neutral input

**Decision:** String and UTF-8 input share the compiler and every engine through
one code-point-oriented input contract. UTF-8 matching uses a separate
`Utf8Matcher` with byte-relative coordinates; see [UTF-8 matching](UTF8.md).

**Rejected:**

- *Decoding UTF-8 input to `String`:* performs the allocation and conversion the
  feature exists to avoid. It remains an application-level fallback.
- *Byte-oriented copies of the engines:* a second semantic implementation of the
  compiler, DFA, NFA, BitState, OnePass, boundaries, and captures.
- *A byte mode on `Matcher`:* index units, `group()` cost, replacement types, and
  reset overloads would depend on how the matcher was constructed.
- *A `byte[]`-only API, or `groupBytes()` as the primary capture API:* both force
  copies for nonzero-offset views such as Trino `Slice` values, and an
  array-specific input type would preclude direct and segmented storage.
- *RE2/J's permissive malformed-input decoding:* not a complete validity
  contract, and hard to make symmetric in reverse scans.

## Captures come from a capture-capable engine, not repair

**Decision:** The DFA proves match existence and bounds. Captures come from
OnePass, BitState, or the Pike VM, with the Pike VM as the semantic authority for
complex capture cases; see
[captures survive compilation](INVARIANTS.md#captures-survive-compilation).

**Rejected:**

- *Post-match repair of capture registers:* a second semantic layer that searches
  candidate repeat partitions after a match is chosen, risking superlinear work
  when captures are observed.
- *Disabling simplification around captures:* insufficient, because captures can
  also diverge in engine selection, deferred extraction, nullable repetitions,
  and replacement; and it gives up normalization broadly.
- *Always using the Pike VM for capturing patterns:* too broad. The DFA remains
  valuable for rejection and bounds, and OnePass and BitState are correct and
  faster where eligible. It also would not fix quantified capture retention by
  itself.
- *Re-evaluating the original, unsimplified AST:* a second matcher that must stay
  consistent with the compiled engines and risks backtracking, stack depth, or
  partition enumeration.
- *A capture-aware DFA:* capture-sensitive state grows quickly, and
  capture-capable engines already exist.
- *Documenting more quantified-capture behavior as divergent:* a last resort
  only. Capture values are public results that real code depends on.

## The DFA handles word boundaries itself

**Decision:** DFA state flags carry the word status of the last consumed
character, equivalence classes never straddle the word/non-word boundary, and
transitions re-evaluate pending word assertions before consuming the next
character. Without `UNICODE_CHARACTER_CLASS`, `\b` and `\B` use the documented
ASCII `\w` model.

**Rejected:**

- *Falling back to the NFA for patterns containing `\b` or `\B`.*
- *The JDK's undocumented rule attaching non-spacing marks to the preceding base
  character;* see
  [ASCII word boundaries around combining marks](../INTENTIONAL_DIVERGENCES.md#ascii-word-boundaries-around-combining-marks).

## Matcher state is an explicit state machine

**Decision:** Each public `Matcher` operation is a transition over named state
components; see
[matcher operations are state transitions](INVARIANTS.md#matcher-operations-are-state-transitions).

**Rejected:**

- *A second matcher implementation to model JDK lifecycle behavior.* The goal is
  to name and enforce the existing contract.
- *Reproducing inconsistent JDK state where the specification says otherwise;*
  for example
  [after a terminal empty `find()`](../INTENTIONAL_DIVERGENCES.md#match-state-after-a-terminal-empty-find).

## Parser dialect is classified, not improvised

**Decision:** Each syntax family has one policy: accepted JDK syntax, rejected
non-regular JDK syntax, an accepted SafeRE extension such as `(?P<name>...)`,
rejected syntax from other dialects, text that only resembles another dialect's
syntax (such as `[[:lower:]]`, which is ordinary character-class text in Java),
or malformed syntax that both engines reject. A new parser divergence is fixed by
classifying its family and adding tests, not by a local special case; see
[parsing preserves source meaning](INVARIANTS.md#parsing-preserves-source-meaning).

## Unicode data is versioned with SafeRE

**Decision:** Unicode regex tables are generated and checked in, and grapheme
data comes from pinned Unicode Character Database files. Only the `java*`
properties follow the running JDK; see [Unicode data](ARCHITECTURE.md#unicode-data).

**Rejected:**

- *Runtime `Character` queries for Unicode properties and grapheme classes:*
  behavior would change with the JDK running the library.
- *Generating grapheme tables from private JDK classifiers:* depends on JDK
  internals, which [AGENTS.md](../AGENTS.md) rules out.

## Iterative traversal

**Decision:** AST, program, and character-class walks use explicit stacks or the
Walker helpers, so deeply nested patterns cannot overflow the Java stack.

## OnePass action encoding

**Decision:** A OnePass action packs empty-width flags, a 32-bit capture mask,
match priority, and a 17-bit next-state offset into one `long`. Matching
allocates nothing, at the cost of limiting OnePass to 16 capture groups.
Programs that exceed that limit, the memory budget, or the offset range use
another engine.
