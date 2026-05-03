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
| Character-class union, range, intersection, subtraction, and negation | JDK-compatible | generated membership tests for edge shapes, including empty class items and malformed range endpoints |
| Group syntax | JDK-compatible accepted forms; reject non-JDK forms | compile/error tests for capturing, non-capturing, flags, named groups, and rejected dialect spellings |
| Quantifier syntax | JDK-compatible where regular; reject unsupported non-regular forms | compile/error tests plus membership for greedy/lazy and bounded forms |
| Boundary matchers and line terminators | JDK-compatible where supported | membership and `hitEnd`/`requireEnd` tests where observable |
| Comments and embedded flags | JDK-compatible | compile and membership tests for whitespace, comments, scoped flags, and flag restoration |
| Unsupported non-regular constructs | rejected with clear errors | compile-error tests against known feature spellings |

This matrix should live in the focused test suite or a small package-private
test helper rather than only in prose.  The doc should describe the policy; the
tests should make the policy executable.

## Parser Architecture

The top-level regex parser should retain its stack-based shape.  This design is
not a request to replace or delegate the whole parser.  It does allow replacing
or substantially refactoring the character-class subparser when that is the
principled way to model JDK character-class expression precedence.

### Character-Class Terminology

This design uses these character-class terms consistently:

- **Trivia**: whitespace and comments skipped by `COMMENTS` mode.  Trivia never
  consumes first-item state and never contributes characters to the class.
- **Zero-width class syntax**: source syntax that consumes characters from the
  pattern but contributes no class item, such as an empty quoted literal
  sequence (`\Q\E`).  Zero-width class syntax does not consume first-item state.
- **Class item**: one source-level item in a character class that can contribute
  characters or a nested class expression to the current class.  Examples
  include literal characters, escaped characters, predefined classes, Unicode
  property classes, POSIX bracket spellings when JDK-compatible, and nested
  character classes.
- **First item**: the first class item after the opening `[` or `[^`, ignoring
  trivia and operator tokens that do not contribute a class item.  First-item
  state controls JDK edge syntax such as leading `-` and leading `]`.
- **Range endpoint**: a scalar character endpoint on either side of a range
  operator (`-`).  A range endpoint is narrower than a class item: a nested
  class opener (`[`) may begin a class item, but it is not a valid unescaped
  range endpoint.
- **Operator token**: syntax that combines class operands, such as range (`-`)
  and intersection (`&&`).  Operator validation belongs to character-class
  expression parsing, not to later AST repair.  An operator token contributes no
  characters by itself and does not consume first-item state.

### Character-Class Parser Contract

The character-class parser should satisfy this contract:

- The parser consumes source text as a sequence of trivia, class items,
  operator tokens, and zero-width class syntax.
- Trivia is skipped before deciding whether the next source token is a class
  item, range endpoint, operator token, or class terminator.
- Trivia and zero-width class syntax must be normalized to a fixed point before
  token classification, because either can expose the other.  For example, in
  `COMMENTS` mode, `\Q\E` followed by whitespace before an operator is still
  zero-width syntax plus trivia, not a literal whitespace class item.
- Operator tokens do not contribute characters and do not consume first-item
  state unless the JDK grammar treats the surrounding syntax as a literal class
  item.
- Zero-width class syntax does not contribute characters and does not consume
  first-item state.
- Only class items consume first-item state.
- Leading intersection syntax must be parsed according to the JDK
  character-class expression grammar.  If an initial `&&` is interpreted as an
  intersection operator, it must not consume first-item state for the following
  class item; malformed leading forms such as `&&]`, `&&&&`, or `&&&` must be
  rejected only when the JDK rejects them.
- A leading literal `-` is a class item when it appears in first-item position
  or before the class terminator.
- A `-` is a range operator only when it appears between two valid range
  endpoints.  Immediate class termination after `-`, including after immediate
  zero-width quoted syntax, keeps `-` literal; comments-mode trivia after `-`
  follows JDK behavior and does not by itself make the `-` trailing literal.
- A range endpoint must be a scalar character or escaped scalar character.  A
  nested class opener may begin a class item, but it is not a scalar endpoint.
  When a `-` is immediately followed by a valid nested class item, the `-`
  remains a literal class item and the nested class is parsed as the next class
  item.  Malformed or unclosed nested class syntax after `-` is rejected when
  the nested class item is parsed.  In `COMMENTS` mode, trivia after `-` follows
  the JDK range-disambiguation behavior described above rather than making the
  following nested class immediate.
- Nested character classes are parsed as class items.  They are not repaired
  after being consumed as malformed range endpoints.
- Every accepted character-class spelling must either match JDK membership or
  be rejected earlier as an unsupported non-regular construct.  Every
  JDK-rejected malformed spelling in this contract must throw
  `PatternSyntaxException`.

### Character-Class Expression Parser Contract

The token contract above is the foundation for JDK-compatible character-class
parsing: trivia, zero-width syntax, operator tokens, first-item state, scalar
range endpoints, and nested class items must be classified consistently.
[Issue #273](https://github.com/eaftan/safere/issues/273) builds on that
foundation with an explicit expression parser, or an equivalent state machine,
for JDK character-class operator precedence and the edge behavior that is only
observable through the JDK oracle.

The JDK `Pattern` documentation also defines character-class operators and
their precedence:

1. literal escape;
2. grouping (`[...]`);
3. range (`a-z`);
4. union;
5. intersection (`&&`).

Some accepted but unusual JDK spellings, such as repeated, trailing, or
ambiguous `&&` around nested classes and predefined classes, depend on that
full expression model.  Examples include forms like `[a&&&&a]`, `[ [a]&&]`,
and `[ \d&&]`.  SafeRE should handle those with the character-class expression
parser, or an equivalent explicit state machine, that models grouping, range,
union, and intersection precedence directly instead of continuing to grow local
`&&` special cases.

The parser should treat literal characters, escaped scalar characters,
predefined character classes, Unicode and POSIX property classes, quoted literal
content, and nested `[...]` groups as character-class expression pieces in a
SafeRE-owned parser model.  Quoted literal content expands into literal class
items according to JDK behavior; it is not a magic scalar range endpoint.
Ranges may use only scalar range endpoints, while unions and intersections
combine class expressions according to JDK precedence.

The implementation must be independent of OpenJDK source code.  The JDK
Javadocs are the written syntax and precedence specification.  Where the
Javadocs do not fully determine edge behavior, such as trailing or repeated
`&&` mixed with nested classes, predefined classes, quoted literals, and
comments-mode trivia, SafeRE should define the behavior in its own parser-state
terms and verify it with black-box differential tests against
`java.util.regex.Pattern`.  Do not copy, transliterate, preserve private
OpenJDK parser structure, or use OpenJDK-internal names as implementation
guidance.

The equivalent explicit state machine should follow this SafeRE-owned
operational contract:

1. Normalize before classification.  Repeatedly skip comments-mode trivia and
   zero-width class syntax until the source position stops moving.
2. Classify exactly one next token kind after normalization: class terminator,
   intersection operator, nested class start, predefined/property class item,
   quoted literal class item, or scalar/range class item.
3. Maintain separate state for the accumulated class expression, the current
   intersection operand, pending scalar-literal content, and any right-hand
   intersection expression being parsed.
4. Keep pending scalar-literal content distinct from nested/predefined/property
   operands until an operator or terminator requires it to be incorporated.
   This distinction is observable for JDK-compatible trailing and repeated
   intersections, so the parser must not collapse all class items into a single
   generic "completed operand" model.
5. Parse `&&` as the start of a right-hand expression.  If that expression is
   empty before the next terminator or operator, apply the SafeRE-specified
   behavior that is covered by black-box JDK oracle tests for the corresponding
   syntax family.
6. Distinguish pending scalar-literal roles that are observable through the
   JDK oracle: ordinary scalar text after an existing class expression before
   an empty right-hand intersection is malformed; ranges and scalar items that
   the oracle treats as standalone operands participate in the intersection;
   and an ambiguous literal `&` separated from a following trailing `&&` only
   by non-item syntax, such as zero-width quoted syntax and comments-mode
   trivia, follows the repeated-intersection edge behavior covered by oracle
   tests.  That ambiguity must not remove or demote the literal `&` from the
   class contents: accepted oracle-backed cases must prove both that the
   preceding class expression is preserved and that the literal `&` still
   matches.
7. Apply negation only after the bracketed expression is complete.
8. Represent nested classes with explicit frames or another stack-safe
   mechanism; source nesting must not recurse on the Java call stack.

Negation should apply to the completed class expression after the closing `]`,
not to whichever local parser fragment happened to be active when the `^` was
seen.  This matters for cases such as negated trailing intersections, where the
observable JDK membership is the complement of the whole parsed expression.

The earlier token-state work was a staging step that fixed leading-intersection
and range-endpoint bugs without replacing the character-class subparser.  The
#273 expression-parser work should replace that local intersection handling with
grammar-level behavior.

The desired changes are structural:

- Parse branches should name the JDK syntax family they implement.
- Dialect spellings from RE2, POSIX, Python, or PCRE should be accepted only
  when they are also JDK-compatible.
- Character-class parsing should have explicit handling for JDK intersection
  and subtraction edge cases, not a generic POSIX class interpretation.
- Character-class parser state should distinguish operator tokens, trivia,
  class items, first items, and range endpoints.
  The JDK docs describe character classes in terms of union, intersection,
  range, grouping, and operand classes; SafeRE uses "class item" as an internal
  parser-state term for a source-level unit that may consume first-item state.
  For example, a leading JDK intersection operator such as `&&` is syntax that
  changes the class expression, but it is not itself the first item.  After
  consuming such an operator, the next class item must still receive first-item
  semantics, including the JDK's treatment of leading `-`, `]`, and malformed
  repeated intersections.
- First-item privileges do not permit the generic range parser to reinterpret a
  leading literal as an arbitrary range.  The parser must validate each complete
  class item and, when parsing a range, must validate that both sides are valid
  scalar range endpoints.  For example, a nested class opener can begin a class
  item, but it cannot be consumed as an unescaped range endpoint.
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
  `-`, and representative range endpoints;
- distinguish operator tokens and trivia from the first item, including cases
  such as leading intersections followed by `-`, another `&&`, a solitary `&`,
  comments-mode whitespace, or the end of the class;
- distinguish class items from range endpoints, including malformed ranges that
  try to use a nested class opener as an endpoint and accepted neighboring
  forms where the same source character begins a nested class item;
- test nested and combined character-class operations;
- include a focused differential suite for the character-class expression
  parser, covering repeated, trailing, and ambiguous intersections with
  nested classes, predefined classes, quoted literals, negation, and
  comments-mode trivia;
- include class-expression edge families that stress parser state rather than
  only spelling examples: scalar/range content before and after nested or
  predefined operands; empty quoted literals before and after comments-mode
  trivia; repeated `&&`; unbracketed intersection right-hand sides ending at
  `&` or `]`; and negated variants of those forms;
- keep the #273 expression-parser cases enabled so future changes run against
  oracle coverage rather than a disabled backlog;
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
- character-class intersection and subtraction tests include empty class-item
  and repeated-operator edge cases;
- character-class item-state tests prove that leading operators do not consume
  first-item privileges for the following class item;
- character-class range tests prove that only valid scalar endpoints are
  accepted, while nested class openers remain valid when they begin a class item;
- the #273 JDK-accepted character-class expression-precedence gaps are fixed by
  a dedicated character-class expression parser or equivalent explicit state
  machine, with examples and oracle-backed tests;
- newly discovered parser divergences outside the implemented #273 grammar are
  tracked separately only after they are classified by the dialect matrix and
  backed by oracle tests or documented as intentional divergences;
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
- Do not replace or delegate the top-level regex parser as part of this design.
