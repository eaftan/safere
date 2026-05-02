# Parser Dialect Compatibility Design

## Problem

SafeRE aims to be a drop-in replacement for `java.util.regex.Pattern`, while
using a parser lineage that came from RE2.  That creates a recurring risk:
source syntax can be parsed according to the wrong dialect before any matching
engine runs.

This matters because parser mistakes are silent semantic bugs.  A pattern can
compile successfully, but mean something different from the JDK.  Users then
observe wrong matches even though the matching engines are behaving correctly
for the AST they were given.

Recent bugs show the class:

- #216: POSIX bracket spellings such as `[[:lower:]]` were interpreted with
  RE2/POSIX semantics instead of JDK character-class text semantics.
- #217: `(?P<name>...)` was accepted even though the JDK named-capture spelling
  is `(?<name>...)`.
- #220: empty left-side character-class intersections such as `[&&abc]` and
  `[a&&&&b]` diverged from the JDK.
- #224: octal escape acceptance and interpretation drifted from
  `java.util.regex`.

The common problem is not any one syntax feature.  It is the absence of an
explicit dialect policy that every parser feature must pass through.

This design does not claim that SafeRE can prove complete equivalence with the
JDK parser.  The JDK parser is not exposed as a formal grammar or structured
oracle, and some edge behavior is only observable by compiling and matching
examples.  The achievable contract is an engineering one: every known syntax
family has an explicit policy, and that policy is backed by compile/error and
membership tests against `java.util.regex`.

## Design Principle

SafeRE should not have regex syntax extensions in `Pattern.compile`.

SafeRE-only public APIs such as `PatternSet` are fine.  The regex language
accepted by `Pattern.compile` should follow one of two policies:

- **JDK-compatible:** SafeRE accepts the syntax and gives it the same
  membership, capture, flag, and compile/reject behavior as `java.util.regex`,
  subject to documented linear-time rejections.
- **Rejected:** SafeRE rejects the syntax because the JDK rejects it, or because
  supporting it would violate SafeRE's linear-time guarantee.

There should not be a third implicit category of "accepted because RE2 accepts
it" or "accepted because it was easy to parse."  RE2 source compatibility is a
source of implementation ideas, not a product dialect.

## Current State

SafeRE already has significant JDK syntax coverage:

- `JdkSyntaxCompatibilityTest` is organized around JDK `Pattern` syntax
  families.
- Generated public API crosscheck exercises many compiled patterns through
  both SafeRE and the JDK.
- Unicode property tests compare SafeRE's property spellings and membership
  against the running JDK.
- Unsupported non-regular features such as backreferences and lookaround are
  rejected rather than emulated.
- The recent bugs in this design's scope are closed with focused regression
  coverage.

The weak point is that the policy is still mostly encoded in tests and parser
branches.  There is no central compatibility matrix that tells a future parser
change which dialect owns a spelling, which spellings are deliberately
rejected, and which membership tests must be run before a change is safe.

## Dialect Policy

The parser should classify every syntax feature into one explicit category.

| Category | Meaning | Examples |
| --- | --- | --- |
| Accepted JDK syntax | JDK accepts the spelling and SafeRE implements the same syntax and semantics. | literals, character classes, quantifiers, `(?<name>...)`, `\p{Lu}`, `\Q...\E`, `\R`, `\X`, JDK flags |
| JDK-compatible with linear implementation | JDK accepts the spelling, and SafeRE implements the same observable behavior with a linear engine rather than backtracking. | alternation priority, captures in supported regular syntax, anchors, regions |
| Rejected non-regular JDK syntax | JDK accepts the spelling, but supporting it would violate SafeRE's linear-time guarantee or architecture. | backreferences, lookahead, lookbehind, possessive quantifiers |
| Rejected non-JDK syntax | Another regex dialect accepts the spelling, but JDK does not. | `(?P<name>...)`, RE2/Python-only or POSIX-only spellings |
| JDK accepted literal text | A spelling resembles another dialect's metasyntax but is ordinary text in the JDK. | POSIX bracket fragments inside Java character classes such as `[[:lower:]]` |
| JDK rejected malformed syntax | Both JDK and SafeRE should throw `PatternSyntaxException`. | malformed octal escapes such as `\0`, bad property names, invalid group syntax |
| Documented divergence | SafeRE intentionally differs for a stated reason and has regression coverage. | unsupported non-regular features, known capture behavior that would require non-linear backtracking state |

The implementation does not need a giant runtime table.  The design target is a
source-level matrix near parser tests, plus helper APIs that make every family
testable against the JDK oracle.

The matrix is not a one-time proof that no undiscovered parser divergence
exists.  It is the place where future parser divergences must be classified.
When a new dialect bug appears, the fix should add or refine a matrix row and
its tests, rather than introducing local parser behavior without naming the
syntax policy.

## Compatibility Matrix

The focused compatibility matrix should cover at least these syntax families.

| Family | Policy | Oracle |
| --- | --- | --- |
| Literal characters and quoting | JDK-compatible | compile and membership tests, including `LITERAL` flag and `\Q...\E` |
| Escaped literals and control escapes | JDK-compatible or JDK-rejected | compile/error tests and representative membership |
| Octal, numeric, hex, Unicode, and named-character escapes | JDK-compatible for accepted forms; reject JDK-rejected forms | compile/error tests plus membership for boundary values |
| Predefined character classes | JDK-compatible | membership against representative ASCII, Unicode, and line-terminator inputs |
| Java character classes | JDK-compatible | membership against `java.lang.Character` behavior |
| POSIX property escapes `\p{Lower}` etc. | JDK-compatible | membership against JDK |
| POSIX bracket fragments `[[:lower:]]` | ordinary JDK character-class text, not POSIX metasyntax | membership tests proving characters like `l`, `o`, `w`, `e`, `r`, `:`, `[`, and `]` behave like JDK |
| Unicode scripts, blocks, categories, and binary properties | JDK-compatible, tied to the running JDK where possible | property lookup and membership crosschecks |
| Character-class union, range, intersection, subtraction, and negation | JDK-compatible | generated membership tests for edge shapes, including empty operands |
| Group syntax | JDK-compatible accepted forms; reject non-JDK forms | compile/error tests for capturing, non-capturing, flags, named groups, and rejected dialect spellings |
| Quantifier syntax | JDK-compatible where regular; reject unsupported non-regular forms | compile/error tests plus membership for greedy/lazy and bounded forms |
| Boundary matchers and line terminators | JDK-compatible where supported | membership and `hitEnd`/`requireEnd` tests where observable |
| Comments and embedded flags | JDK-compatible | compile and membership tests for whitespace, comments, scoped flags, and flag restoration |
| Unsupported non-regular constructs | rejected with clear errors | compile-error tests against known feature spellings |

This matrix should live in the focused test suite or a small package-private
test helper rather than only in prose.  The doc should describe the policy; the
tests should make the policy executable.

## Parser Architecture

The parser should retain its stack-based shape.  This design is not a request
to replace the parser.

The desired changes are structural:

- Parse branches should name the JDK syntax family they implement.
- Dialect spellings from RE2, POSIX, Python, or PCRE should be accepted only
  when they are also JDK-compatible.
- Character-class parsing should have explicit handling for JDK intersection
  and subtraction edge cases, not a generic POSIX class interpretation.
- Escape parsing should separate "numeric backreference-like syntax,"
  "JDK octal syntax," and "invalid numeric escape" instead of treating them as
  one fallback.
- Error paths should throw `PatternSyntaxException` with stable enough messages
  for debugging, but tests should generally assert the exception type and
  index-sensitive behavior only when the public contract needs it.
- Unsupported-feature detection should be early and explicit so users get a
  clear rejection instead of a later malformed-AST failure.

The parser should not use the original source text after compilation to repair
syntax semantics.  Once parsed, the AST must represent the JDK-compatible
meaning or the pattern must have been rejected.

This also means SafeRE should not delegate parsing to the JDK as the primary
implementation strategy.  Delegation would not produce the SafeRE AST, would
not cleanly encode SafeRE's linear-time rejections, and would still leave the
compiler and engines responsible for the accepted semantics.  The JDK is the
test oracle, not the parser implementation.

## Test Strategy

Parser compatibility needs two kinds of tests.

### Compile/Error Tests

For every syntax family, tests should say whether JDK and SafeRE both accept
or reject a spelling.  Rejected non-regular JDK syntax is the main exception:
JDK may accept it, while SafeRE rejects it for the documented linear-time
reason.

Compile/error tests should include:

- representative ordinary forms;
- malformed forms adjacent to valid ones;
- dialect spellings that are tempting because RE2 or another regex engine
  accepts them;
- flag-sensitive forms;
- forms inside and outside character classes.

### Membership Tests

Accepted syntax must also be tested for meaning.  Compile parity alone would
not have caught #216 or #220.

Membership tests should:

- compare SafeRE and JDK on representative positive and negative inputs;
- include edge characters for class syntax, such as `&`, `[`, `]`, `:`, `^`,
  and range endpoints;
- test nested and combined character-class operations;
- include escape boundary values such as octal overflow points;
- use generated small-alphabet membership checks for character-class grammar
  where hand-written examples are likely to miss cases.

Generated public API crosscheck remains useful, but parser dialect tests should
not rely on accidental future fuzzing to discover the matrix.  The suite should
intentionally cover each syntax family.

This is intentionally a test-backed compatibility contract, not a formal proof.
The success criterion is that known syntax families and newly discovered
divergences are machine-checked against the JDK oracle.  The design should
avoid language that implies the parser can be proven equivalent to all current
and future JDK behavior.

## Linear-Time Argument

Parser dialect compatibility does not weaken the matching-time guarantee.

The design either:

- parses a JDK-compatible regular construct into the existing linear execution
  model; or
- rejects constructs that would require non-linear matching semantics.

Adding membership tests for accepted syntax does not imply that SafeRE must
support every JDK feature.  The acceptance decision remains constrained by the
core linear-time contract.  When JDK accepts a non-regular feature, SafeRE's
compatible behavior is a clear `PatternSyntaxException`, not backtracking
emulation.

Parser work must still preserve stack safety.  Any new source or AST walk used
for syntax classification should be iterative or bounded by local token
structure, not recursive over user-controlled nesting.

## Acceptance Criteria

This design track is complete when:

- the parser dialect policy is documented as "JDK-compatible or rejected";
- the design explicitly states that this is not a formal proof of complete JDK
  parser equivalence;
- `JdkSyntaxCompatibilityTest` or an equivalent focused suite contains a
  syntax-family matrix matching the table above;
- every known RE2/POSIX/Python-only spelling that SafeRE might accidentally
  accept has an explicit accept/reject test;
- POSIX bracket fragments inside Java character classes are tested as ordinary
  JDK character-class text;
- named-capture tests prove `(?<name>...)` is accepted and `(?P<name>...)` is
  rejected;
- character-class intersection and subtraction tests include empty operand and
  repeated-operator edge cases;
- octal and numeric escape tests cover accepted JDK forms, rejected malformed
  forms, and boundary values;
- unsupported non-regular JDK features are rejected by clear tests tied to the
  linear-time rationale;
- generated public API crosscheck can run parser-compatible public tests unless
  a test documents why it is not comparable;
- any future parser bug can be classified by adding a row or case to the matrix,
  not by inventing a new dialect policy.

## Non-Goals

- Do not add SafeRE regex syntax extensions.
- Do not emulate backreferences, lookaround, or other non-regular JDK features.
- Do not make error message text the main compatibility target unless users
  observe it through a documented API guarantee.
- Do not preserve RE2/POSIX source compatibility when it conflicts with
  `java.util.regex`.
- Do not replace the parser as part of this design.
