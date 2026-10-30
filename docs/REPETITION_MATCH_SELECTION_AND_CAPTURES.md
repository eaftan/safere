# Repetition, match selection, and captures

**Draft for review.** This document defines the intended semantics, rather than
describing every current SafeRE result. The owner selected finite expansion with
nested optional copies, acceptance of a terminal empty iteration in unbounded
repetition, and transparent redundant noncapturing wrappers during the policy
discussion. Implementation and coverage changes are separate work.

The motivation is the [review of PR #1092][review1092] and the related examples
in [#1079][issue1079], [#1051][issue1051], and [#1091][issue1091]. As with
[opaque region bounds](OPAQUE_REGION_BOUNDS.md) and [PR #1039][pr1039], the aim
is a model that resolves a behavioral class. Existing divergence entries are
evidence of earlier decisions, not premises that this policy must preserve.

The [decisions and reasonable alternatives](#decisions-and-reasonable-alternatives)
section collects the choices behind these rules, their tradeoffs, and relevant
precedents. It separates documented alternative models from observed quirks.

## Scope and governing rule

These rules apply to supported patterns in String and UTF-8 matching. Positions
use each API's coordinates; String examples below use UTF-16 indices and
half-open bounds. Region, assertion, Unicode, and search-cursor rules remain
those of the corresponding APIs and [semantic invariants](INVARIANTS.md).

**Select the first complete success in the ordered executions defined below.
Report its whole-match bounds and its capture state.** In particular, neither
the longest match nor an independently preferred capture vector determines the
winner. The empty-iteration rules define which executions exist; ordinary
branch priority alone is insufficient for nullable repetition.

This is a specification of results, not a requirement to execute a backtracking
search. Production matching must preserve linear work for a fixed compiled
program and stack safety. No rule authorizes replaying unbounded prefixes,
enumerating repeat partitions, or repairing captures with repeated searches.

## Ordered executions and complete success

Conceptually, an execution carries an input position, a capture map, and the
remaining expression. Each choice has ordered children. Explore earlier
children before later children, including their remaining expressions.

1. `A|B` tries executions through `A` before executions through `B`.
2. `AB` tries an outcome of `A` together with `B`. If that continuation fails,
   another outcome of `A` may be tried. A locally successful subexpression does
   not commit the surrounding expression to success.
3. A greedy optional choice tries participation before omission. A reluctant
   optional choice tries omission before participation. Operand alternative
   order is unchanged by a reluctant quantifier.
4. A choice starts each child with the same incoming capture and repetition
   state. Abandoned children contribute neither capture writes nor progress
   state to later children.
5. The operation's acceptance condition is part of the continuation.
   `lookingAt()` accepts a complete pattern match at the region start;
   `matches()` additionally requires the region end. `find()` selects the first
   successful start permitted by its cursor and candidate-start rules, then
   the first complete success at that start.

For example, `a|aa` finds `a` in `aa`, but `matches()` selects `aa`. Greediness
orders local decisions; it does not globally maximize match length or iteration
count across different operand alternatives.

The execution tree is explanatory. Equivalent worklists or automata may merge
states only when doing so preserves the selected complete result.

## Finite repetition: required copies and nested optional copies

`R{m,n}` means `m` required copies of `R`, followed by `n-m` **nested** optional
copies. Each conceptual copy refers to the original capture identities; copies
do not create new group numbers. `R{0}` retains the source groups' numbering but
executes none of them. `R?` is `R{0,1}` and `R{n}` is exactly `n` required copies.

For example, the structural expansion of `R{2,5}` is:

```text
R R (R (R (R)?)?)?
```

The notation uses grouping for structure, without adding captures. All the
generated optional choices use the quantifier's greedy or reluctant order.
Omitting an optional suffix omits that entire suffix. In particular, reluctant
counted repetition does not mean a flat sequence of independent reluctant
optionals.

An equivalent recursive definition, with state and continuation implicit, is:

```text
Finite(R, m, n):
    if n == 0: succeed without changing state
    if m > 0:  execute R, then Finite(R, m-1, n-1)
    otherwise: choose between
                   execute R, then Finite(R, 0, n-1)
                   succeed without changing state
               participation first if greedy; omission first if reluctant
```

An empty iteration is a real iteration. It may update captures, counts toward
the bound, and may be followed by another iteration. No progress guard applies:
the finite bound already ensures termination of this repetition. An unbounded
repetition nested inside its operand still obeys its own progress rule.

The distinction between nested and separate optionals is observable even with
nonnullable operands. On `aab`, `(a|aa){0,2}?b` captures the second `a` at
`[1,2)`. Its first successful execution takes two `a` iterations. Separate
reluctant optional copies can instead select one `aa` iteration. This is why
"equivalent explicit copies" must include the placement of optional choices.

This definition does not require literal code expansion in an implementation.
It does require any compact representation to preserve the same ordering.

## Unbounded repetition: accept empty, then stop

For `R*`, `R+`, and `R{m,}`, use the following source-level rule:

1. While the minimum count is unmet, an iteration is required. Empty iterations
   count toward that minimum.
2. Once stopping is allowed, choose between another iteration and stopping,
   ordered by greediness or reluctance.
3. After an iteration consumes input, continue the repetition with its updated
   position and capture state.
4. After an iteration consumes no input and the minimum has now been met,
   accept that iteration, retain its capture updates, and continue **outside**
   this repetition. Do not offer another iteration on that execution.
5. An empty iteration before the minimum is met must continue toward the
   minimum. It cannot make an otherwise insufficient iteration count succeed.

Stopping after empty does not commit the entire pattern. If the outside
continuation fails, the selected iteration's other operand outcomes, earlier
iterations, or other surrounding choices remain available in their defined
order, with state restored. Thus an empty-first operand can ultimately take a
consuming alternative when the suffix requires it.

Each invocation owns its progress state. Entering an inner repetition again
from a later outer iteration starts a fresh invocation, even at the same input
position. A nested empty iteration does not stop an enclosing iteration that
consumed input. Measure progress across the whole operand of the repetition
being checked, not an arbitrary internal instruction.

`R*` and `R{0,}` agree; `R+` and `R{1,}` agree, including reluctance. In general,
`R+` is **not** interchangeable with `RR*` when `R` is nullable: an empty first
iteration can stop the former, while the latter enters a fresh repetition.
For `R = (?:^()|a)` followed by `b` on `ab`, `R+` must abandon its first empty
iteration and consume `a`. The empty alternative is unavailable at position 1,
so group 1 is unmatched. A conceptual `RR*` with shared capture identities can
instead keep the first copy's empty capture at position 0 while its fresh star
consumes `a`. Source-group identity and repetition invocation boundaries both
matter. Literal source duplication introduces separate group numbers; the
shared-identity example describes a proposed compiler rewrite.

On finite input, consuming iterations are bounded by input progress, required
empty iterations by the fixed minimum, and a terminal empty iteration ends its
invocation. This makes the conceptual executions finite; it does not make
enumerating those executions an acceptable production algorithm.

Already-supported possessive quantifiers over statically zero-width operands
use the greedy repetition outcomes but commit to the first local success before
trying the outside continuation. Failure outside cannot reconsider that
quantifier's internal choices. Consuming possessive operands and other excluded
features remain outside the supported dialect.

## Capture updates, retention, and restoration

Each match attempt begins with unmatched captures. A completed participating
group writes its bounds, including an empty interval. A later completed
participation replaces the earlier value. A later iteration that does not
participate in the group leaves its value unchanged; entering an iteration or
an enclosing capture does not implicitly clear descendant groups.

These rules distinguish retention from leakage:

- `(a(b)?)+` on `aba` retains group 2 as `b` at `[1,2)` when the final iteration
  omits `(b)?`. The retained value belongs to an earlier accepted iteration.
- If an execution writes a capture and is then abandoned, restore the incoming
  capture map at that choice. An earlier accepted value may reappear; a value
  written only on the abandoned execution must not survive.
- An empty terminal iteration retains writes it actually makes. Groups omitted
  in that iteration retain their previous accepted values.
- A new candidate start or a new match operation does not inherit captures from
  an earlier failed attempt or a previously published match.

Unmatched groups have bounds `-1`; participating empty groups have equal
nonnegative bounds. Retained descendant captures can precede the final capture
of their enclosing group. Group zero records the selected complete match, not
the span of the last repeat iteration.

## Nested repetition and transparent wrappers

Adding or removing a redundant `(?:...)` wrapper must preserve match success,
whole-match bounds, capture participation and bounds, and successive API
results. A redundant wrapper changes neither grouping precedence nor effective
flags. Adding a capturing group or changing flag scope is a different operation.

The rule applies to consuming, nullable, and purely zero-width operands. Under
this policy, both `()*` and `(?:())*` capture empty at `[0,0)` on empty input;
so do `(){0,2}` and `(?:()){0,2}`. Their reluctant zero-minimum forms omit the
operand when that already permits success, leaving the group unmatched.
These are intended outcomes, not a claim about current implementation behavior.

Nested repetitions apply the same capture update rule at every depth. No
compiler transformation may freeze an earlier capture simply because it occurs
inside a nested quantifier. Nor may a transformation infer equivalence solely
from recognition language, absence of captures, or equality of first isolated
results. It must preserve ordered fallback outcomes in surrounding contexts.

## Worked examples and observed implementations

In these examples, **current SafeRE** means the checked-out revision
`0b60ee50`, not PR #1092 or a future implementation of this policy. Each example
compares its observed behavior with the required result. PR and JDK observations
are context; agreement with either does not establish conformance. The JDK
columns report the verified OpenJDK 26.0.2 results.

### Earlier captures in nested repetition: #1079

On `aaaa`, consider the first `find()` result for each spelling:

| Pattern | Current SafeRE | Required by this policy | Observed JDK 26.0.2 |
|---|---|---|---|
| `(?:(a){0,2})*` | Whole match `[0,4)`; group 1 `[1,2)` | Whole match `[0,4)`; group 1 `[3,4)` | Whole match `[0,4)`; group 1 `[1,2)` |
| `(?:(?:(a)){0,2})*` | Whole match `[0,4)`; group 1 `[1,2)` | Whole match `[0,4)`; group 1 `[3,4)` | Whole match `[0,4)`; group 1 `[3,4)` |

**Change required:** both spellings must report the later capture at `[3,4)`.
Their whole-match bounds already agree with the policy. Later consuming
iterations update group 1. A final empty outer iteration does not participate
in `(a)`, so it retains that value.

The current capture-suppression lowering preserves the earlier value. The
verified PR #1092 revision removes that lowering and gives the required
`[3,4)` for both examples. This is distinct from preserving a legitimate earlier
capture when a later iteration omits the group. For comparison, the observed
JDK capture is `[1,2)` for the first spelling and `[3,4)` for the second.
See [#1079][issue1079] and [PR #1092][review1092].

### Empty captures in finite repetition: #1051

On `" y"`, each form matches the whole input at `[0,2)`:

| Pattern | Current SafeRE group 1 | Required group 1 | Observed JDK 26.0.2 group 1 |
|---|---|---|---|
| `(?:()\|\W+){0,2}y` | Empty at `[0,0)` | Empty at `[0,0)` | Empty at `[1,1)` |
| `(?:()\|\W+){1,2}y` | Empty at `[0,0)` | Empty at `[0,0)` | Empty at `[1,1)` |
| `(?:()\|\W+)*y` | Empty at `[1,1)` | Empty at `[1,1)` | Empty at `[1,1)` |

**No change required for these examples.** The policy preserves current SafeRE's
different capture positions for counted and unbounded repetition.

In the `{0,2}` case, finite expansion first tries the empty branch in the first
copy. After unsuccessful choices in the second copy, its `\W+` branch consumes
the space and the suffix matches `y`. The second iteration does not participate
in group 1, so its `[0,0)` value survives.

For the `*` form, an empty iteration at position 0 stops the repetition and
`y` then fails. Reconsidering that operand selects the consuming branch. The
next empty iteration at position 1 stops the repetition and allows `y` to
succeed, giving `[1,1)`. Counted and unbounded forms differ because their
admissible executions differ, not because capture retention changes. For
comparison, the observed JDK group 1 is `[1,1)` for all three forms.
See [#1051][issue1051].

### Whole-match bounds and later searches: #1091

For `(?:b?|a){0,2}b` on `abb`, the first copy can take `b?` empty; the second
then takes `a`; the final `b` consumes the middle character. That complete
success has priority over reconsidering the first copy to obtain a longer
match. The same result follows for `{1,2}` and `{2}`.

| Operation or control | Current SafeRE | Required by this policy | Observed JDK 26.0.2 |
|---|---|---|---|
| Counted forms: successive `find()` | `[0,2)` (`ab`), `[2,3)` (`b`) | `[0,2)` (`ab`), `[2,3)` (`b`) | `[0,3)` (`abb`) |
| `(?:b?\|a)?(?:b?\|a)?b`: successive `find()` | `ab`, `b` | `ab`, `b` | `ab`, `b` |
| `(?:b?\|a)(?:b?\|a)b`: successive `find()` | `ab`, `b` | `ab`, `b` | `ab`, `b` |
| `(?:b?\|a)*b`: successive `find()` | `abb` | `abb` | `abb` |
| Counted forms: `matches()` | Success `[0,3)` | Success `[0,3)` | Success `[0,3)` |
| Counted forms: `replaceAll("X")` | `XX` | `XX` | `X` |
| Counted forms: `split(input, -1)` | `["", "", ""]` | `["", "", ""]` | `["", ""]` |

**No change required for these examples**, including the `{1,2}` and `{2}`
variants. The policy preserves current SafeRE's selected whole matches and
their consequences for subsequent searches, replacement, and splitting.

For comparison, the observed JDK counted forms find only `abb`, replace it
with one `X`, and produce two empty fields with a negative split limit. The
explicit-copy and star controls give the table's results in both engines.
The optional-copy control agrees here because it is greedy; this is not a
general equivalence between flat and nested optional copies. See
[#1091][issue1091].

### Purely empty captures and redundant wrappers

On empty input, the first `find()` matches `[0,0)` in every case:

| Pattern | Current SafeRE group 1 | Required group 1 | Observed JDK 26.0.2 group 1 |
|---|---|---|---|
| `()*` | Unmatched, bounds `-1` | Empty at `[0,0)` | Unmatched, bounds `-1` |
| `(?:())*` | Empty at `[0,0)` | Empty at `[0,0)` | Empty at `[0,0)` |
| `(){0,2}` | Unmatched, bounds `-1` | Empty at `[0,0)` | Unmatched, bounds `-1` |
| `(?:()){0,2}` | Empty at `[0,0)` | Empty at `[0,0)` | Empty at `[0,0)` |

**Change required:** the unwrapped spellings must participate and capture empty,
just as the wrapped spellings already do. The verified PR #1092 revision still
has the current behavior here; its nested-capture fix is insufficient to meet
this part of the policy. The observed JDK results also match current SafeRE in
this table, but the policy chooses consistent wrapper transparency.

### Revision and runtime basis

The issue reports describe historical observations. Local probes reproduced
their central results with OpenJDK 26.0.2, including archived SafeRE revisions
`be81e170` (#1051), `7c1b3833` (#1079), and `3292aa7d` (#1091).

The following also records the verified PR #1092 revision alongside the policy
and JDK results:

| Case | Required by this policy | PR #1092 head `7d665161` | OpenJDK 26.0.2 |
|---|---|---|---|
| #1079, direct spelling, group 1 | `[3,4)` | `[3,4)` | `[1,2)` |
| #1079, wrapped spelling, group 1 | `[3,4)` | `[3,4)` | `[3,4)` |
| #1051, counted form, group 1 | `[0,0)` | `[0,0)` | `[1,1)` |
| #1051, star form, group 1 | `[1,1)` | `[1,1)` | `[1,1)` |
| #1091, counted find sequence | `ab`, `b` | `ab`, `b` | `abb` |
| `()*` and `(){0,2}`, group 1 on empty input | Empty at `[0,0)` | Unmatched | Unmatched |

The JDK observations also reproduced on the installed JDK 21 through 25
runtimes. These are versioned observations, not guarantees about future JDKs
or a claim that a locally checked-out revision is the latest upstream release.
The archived compatibility experiment at [db84b9a5][compat-experiment] reproduces
the JDK's reduced #1051 and #1091 results; this is evidence that a compatibility
implementation is possible, not evidence of its general correctness or cost.

Current differential tests also pin some of the historical results above and
must be reassessed against this contract. The tables cover these specific
examples; unchanged results here do not establish conformance for an entire
behavioral class.

## Whole-match APIs and search progress

The chosen path controls both group zero and inner captures. Resolving captures
later must preserve the already selected match identity. Observing groups,
creating snapshots, or selecting a different eligible engine must not choose
another path or advance the search cursor.

After a nonempty `find()` result, the next search starts at its end. After an
empty result, use the API's existing empty-match cursor advancement: one UTF-16
position for String matchers, a scalar boundary for UTF-8 matchers. A terminal
empty match is reported once before exhaustion. This cursor rule is separate
from progress within one repetition. It does not retry consuming alternatives
at the same start after publishing an empty match.

Replacement uses this same canonical match sequence and these capture values,
including functional replacements and `appendReplacement`. Splitting derives
its delimiters from the same sequence, with the API's own limit, leading
zero-width delimiter, and trailing-empty-field rules. Different whole-match
selection can change output even without a capturing group. Region resets,
append state, and callback mutation remain governed by their existing APIs.

## Decisions and reasonable alternatives

There is no single cross-engine contract for empty iterations. Shared syntax
and the labels "greedy" and "reluctant" do not settle whether an empty
iteration is accepted, whether another iteration may follow it, or which
captures survive. Several coherent models exist. Different measured results
are evidence of disagreement, but do not establish that every result reflects
a deliberate design decision.

The comparisons below correspond to the semantic sections above. The SafeRE
column records this draft's choices; the alternatives explain what another
language could reasonably prioritize. None of these alternatives is proposed
as an additional SafeRE mode. Linear input work and stack safety remain project
constraints under every considered design.

### Scope, ordered executions, and complete success

| Question | SafeRE decision | Reasonable alternative and tradeoff |
|---|---|---|
| Which complete match wins at the earliest successful start? | The first success under ordered alternatives and local greedy/reluctant choices. | Leftmost-longest selection chooses the longest whole match at that start before resolving internal ambiguity. It is useful when maximal text extent should dominate branch order, but changes ordinary Java results such as `a\|aa` on `aa`. |

This is an established Java compatibility choice, rather than a new
empty-iteration decision. [Go's `CompilePOSIX` documentation][go-posix]
explicitly describes the alternative whole-match rule. Submatch selection
requires further rules in either model; "longest" alone does not settle
captures. The different acceptance conditions of `find()`, `lookingAt()`, and
`matches()` follow their existing API contracts.

### Finite repetition

| Question | SafeRE decision | Reasonable alternative and tradeoff |
|---|---|---|
| Can an empty iteration be followed by another copy? | Yes. Each iteration counts toward a finite bound; no extra progress guard is needed for termination. | Reject optional empty iterations, as specified by [ECMAScript][ecma-repeat]. This gives finite and unbounded quantifiers a shared progress check, but changes which finite-copy paths exist. |
| Should finite repetition accept empty and then stop? | An accepted empty copy does not stop the remaining finite suffix. | Apply terminal-empty stopping to finite as well as unbounded repetition. This gives the constructs a common stopping model, but sacrifices explicit-copy equivalence. [.NET's documentation][dotnet-empty] applies an empty-stop rule to bounded quantifiers too. |
| How are optional copies ordered? | A nested optional suffix: omitting a copy omits the remaining suffix. | A flat sequence of independent optionals is another possible expansion contract. It permits skipping earlier copies while using later ones, and changes reluctant fallback order even with nonnullable operands. The `(a\|aa){0,2}?b` example distinguishes the choices. |

SafeRE chooses finite expansion because the count already supplies a termination
bound and the resulting source-level model has a direct implementation. The
choice avoids requiring additional finite-repeat progress state; it is not a
claim that every alternative is expensive or cannot be implemented linearly.
Nested optional suffixes also preserve the current ordering of ordinary
reluctant counted repeats. These benefits come at a compatibility cost: #1051
and #1091 intentionally retain differences from observed JDK behavior.

The JDK Javadoc does not expressly require the expansion contract. Nor does
ECMAScript leave optional empty iterations open to implementation choice: its
rejection rule is explicit. Agreement with Rust or RE2 on the worked finite
examples supports our choice as an implementation precedent, not a universal
semantic consensus. A flat optional expansion is listed as a possible language
design; no claim is made that a named engine promises that contract.

### Unbounded repetition

| Question | SafeRE decision | Reasonable alternative and tradeoff |
|---|---|---|
| What happens when an iteration matches empty after satisfying the minimum? | Accept its capture updates and stop this invocation. | Reject optional empty iterations, as in [ECMAScript][ecma-repeat]. Both policies terminate; they differ on participation and captures even for `()*` on empty input. |
| Does the first iteration of `R+` belong to its progress rule? | Yes. If it is empty, the minimum has been met and this invocation stops. | Define `R+` as a required `R` followed by a fresh `R*`. This preserves a convenient expansion identity but lets that fresh star continue after the required empty copy. The `(?:^()\|a)` example above shows the capture consequence under retention semantics. |

Accepting a terminal empty iteration makes its successful capture writes
observable and fits Java-style retention. [PCRE2][pcre2-repeat] and
[.NET][dotnet-empty] document empty-stop behavior, although this is not a claim
that their complete quantifier semantics equal ours. Rejecting an optional
empty iteration is equally coherent: it removes an optional iteration that
contributes no input progress and supports a uniform optional-progress rule.
Required empty iterations still have to be accounted for to respect the minimum.

The difference between finite continuation and unbounded stopping is deliberate.
SafeRE prioritizes explicit finite expansion and termination of unbounded
invocations over giving both constructs exactly the same empty-iteration rule.
One engine's results should not decide both questions by default.

### Capture updates, retention, and restoration

| Question | SafeRE decision | Reasonable alternative and tradeoff |
|---|---|---|
| What if a later accepted iteration omits a group? | Retain its previous accepted value, following the Java capture contract. | Clear the operand's captures on each iteration, as [ECMAScript][ecma-repeat] specifies. A group then describes participation in that iteration rather than retaining earlier accepted participation. On `(a(b)?)+` and `aba`, these policies yield `b` and unmatched, respectively, for group 2. |
| What capture history does the API expose? | One final retained interval per group, as in the Java API. | Expose all accepted captures, as [.NET's `Group.Captures` examples][dotnet-empty] do. That can support richer parsing results, but changes the API and introduces history-storage costs. A semantic account of execution history does not by itself require SafeRE to store it. |

Restoration on abandoning an ordinary branch is a correctness invariant for
this dialect, not a competing capture-retention preference. A later iteration
omitting a group differs from abandoning the execution that wrote it. The
[old PCRE1 manual][pcre1-assertions] documents incomplete restoration around
assertions as an implementation limitation. That is evidence of a known quirk,
not a well-considered alternative to restoring ordinary failed branches.
Conditional assertions can have their own capture rules; SafeRE does not
support that feature.

### Nested repetition and transparent wrappers

SafeRE chooses source-level transparency for redundant noncapturing wrappers
and applies the same capture-update rules at every nesting depth. This makes
local refactoring and compiler equivalence understandable. It requires changing
some current pure-empty special cases and accepting the documented JDK
differences.

Exact emulation of observed wrapper-dependent results is a possible
compatibility priority. However, no documented semantic purpose for those
particular differences has been established here. They are classified as
observed implementation behavior, not presented as another principled meaning
of a redundant wrapper. Likewise, freezing an earlier capture despite later
accepted participation is not an alternative allowed by this draft's
most-recent-capture contract. Legitimate retention and reset alternatives are
described in the preceding subsection.

### Whole-match APIs and search progress

| Question | SafeRE decision | Reasonable alternative and tradeoff |
|---|---|---|
| What follows a published empty match? | Advance the search cursor according to the input API, without retrying consuming alternatives at that start. | Allow a subsequent nonempty match to start at the same position. [Python's `finditer()` documentation][python-finditer] permits this. It exposes consuming alternatives after a preferred empty result, but produces a different match sequence. |

For `(?:|a)` on `a`, the chosen String search rule reports `[0,0)` and `[1,1)`.
Python 3.13 additionally reports the consuming `[0,1)` match between them. This
is a deliberate difference between search APIs, separate from empty iterations
inside one match. SafeRE preserves its Java-oriented search behavior. Its
String-versus-UTF-8 cursor units follow the existing representation contracts.

Replacement and splitting must use the chosen sequence with their documented
API rules. Independently selecting a more convenient match sequence for a fast
replacement or split path would violate this contract; it is not an additional
design alternative within the current APIs.

### Evidence, implementation, and coverage

The worked examples and JDK assessment distinguish required behavior, chosen
interpretation, and observed quirks. The implementation and coverage sections
then constrain how to realize and verify those decisions. Different algorithms
or oracle combinations may be appropriate, but they must implement the same
contract on their declared overlap. Agreement by several engines is useful
evidence, not a substitute for choosing and stating the rules.

## JDK specification, interpretation, and compatibility

The official [JDK 26 Pattern specification][jdk-pattern] defines ordered
alternation, repetition bounds, capturing groups, and retention of a previous
capture when a later quantified evaluation fails. Its `(a(b)?)+` example
explicitly retains `b` on `aba`. The [Matcher specification][jdk-matcher]
defines operation acceptance conditions, result access, and successive searches.

The Javadocs do not give an operational algorithm for nullable repetition or
fully order its empty and consuming executions. Therefore the #1051 and #1091
results alone do not establish a specification violation. Finite expansion and
terminal-empty stopping are SafeRE's explicit interpretation and compatibility
choice. They are not claimed to be forced by the JDK specification or by linear
time. The owner selected retaining this boundary, including whole-match effects.

Reporting an earlier value despite later participation on the selected path
conflicts with the documented most-recent-capture rule under this interpretation.
Wrapper-dependent results reinforce that concern, but are not themselves a
complete proof of the JDK's selected internal execution. SafeRE chooses the
stated capture and wrapper rules rather than reproducing those implementation
details. The owner also selected that choice for purely empty captures.

## Implementation implications and cost limits

The policy fixes results, not an engine architecture. Relevant current code
includes `Simplifier`'s nested finite expansion and pure-empty special cases,
`Compiler`'s capture-retention lowering and nullable progress instructions,
`Nfa`'s closure/restore logic, and `BitState`'s progress and capture restoration.
PR #1092 changes part of that landscape; its optimizations need independent
assessment rather than becoming part of the semantic definition.

- Remove or replace transformations that suppress genuine later capture writes
  or distinguish redundant wrappers. Recheck repeat coalescing and nullable
  `+` lowering against ordered outcomes, even without captures.
- Bound progress context and include every relevant distinction in merging and
  cache correctness arguments. A program-counter visitation rule must preserve
  this source contract; changing visited-state treatment is not a policy fix.
- Keep per-path capture restoration separate from progress termination. The
  semantic execution history does not require a persistent runtime history.
  Capture arrays, bounded registers, or other representations need explicit
  time and space bounds; persistent structures need justification for their
  allocation, retention, and maintenance costs.
- Require every result-producing engine and deferred extraction pass to select
  the same path. A language-only DFA may remain a rejection filter where it
  cannot prove selected bounds. Fallback must preserve results and work bounds.
- Preserve iterative compilation and execution, bounded compilation resources,
  and work bounds for complete find/replacement sequences. Linear input work
  for a fixed expanded program is distinct from linear compilation in the
  source length or numeric repeat bounds.

The JavaScript literature demonstrates bounded progress-state techniques for
its own semantics, not a drop-in proof for this policy. The 2026 PikeVM proof
has a restricted compiler language and leaves a formal linear execution bound
to future work. Neither paper proves SafeRE's current engines correct.

Possible JDK-compatible linear algorithms do not automatically justify their
state, complexity, or performance costs. This policy deliberately selects
finite expansion semantics. Any future proposal to change that boundary must
describe class-wide behavior, implementation bounds, and measured costs before
adoption; it must not arrive as another isolated capture exception.

## Focused coverage and oracle plan

### Assert the policy directly

Add direct expected-result coverage for finite empty-then-consuming iterations;
terminal empty iterations in unbounded repeats; required empty iterations before
the minimum; greedy and reluctant nesting; retained captures; suffix rollback;
failed candidate starts; and purely empty captures with redundant wrappers.
Include the three worked examples and the reluctant nested-copy control above.

Vary zero/exact/bounded/unbounded counts, alternative order, consuming versus
nullable operands, nested invocation re-entry, named and numbered groups, and
suffixes that reject earlier choices. Assert the full find trace, participating
and unmatched bounds, snapshots, replacement, and splitting where affected.
Include String and UTF-8 coordinates, supplementary characters, regions, and
different capture-demand paths. Check forced eligible engines against the same
expected observations in the existing engine-path equivalence suites.

Existing starting points include `QuantifiedCaptureSemanticsTest`,
`NullableCountedCaptureDivergenceTest`, `AlternationCaptureSemanticsTest`,
`MatcherDeferredCaptureStateTest`, and matcher-state trace tests. Extend
`CaptureSemanticsFuzzer` and the relevant match/find generators with behavioral
shapes, not just minimized issue strings. Use stable work or scaling checks for
long inputs and deep nesting; avoid elapsed-time thresholds as the proof of
linear behavior.

### Keep JDK comparison where the contract agrees

Continue differential checks for ordinary ordered alternatives, nonnullable
quantifiers, capture retention such as `(a(b)?)+`, and unaffected API state.
Known semantic disagreements need direct policy assertions, not JDK-derived
expected values. Keep separate versioned observations of JDK differences so
upstream changes are visible without redefining SafeRE's contract.

### Use an independent RE2-style oracle within a declared overlap

RE2/RE2J can provide an independent check of ordered alternatives, finite
expansion, nested consuming captures, and failed-path restoration on their
shared dialect. They are particularly useful for detecting SafeRE compiler
mistakes that internal engine comparisons all inherit. Select their ordered,
leftmost-first mode, not a longest-match mode.

They are not authorities for all nullable loops: in the local comparison,
RE2 and RE2J leave group 1 unmatched for `(?:()|\W+)*y` on `" y"`, while this
policy requires `[1,1)`. Java syntax, flags, Unicode classes, assertions,
regions, coordinates, and iteration APIs also need explicit normalization or
exclusion. A translated pattern is an oracle only when the translation
preserves ordered outcomes and capture identities.

A small independent source-tree interpreter is a possible additional oracle
for short generated patterns and inputs. It can implement the definitions here
without sharing SafeRE's lowering or instruction graph. Its deliberately
bounded test workload may enumerate executions; production matching may not.
Direct examples still anchor the interpreter's intended behavior.

### Do not hide other defects

Do not exclude every nullable, counted, or captured pattern from differential
testing. Classify a disagreement by the semantic rule and affected observation.
Keep unaffected fields checked where justified; #1091 shows that equal bounds
cannot be assumed merely because an earlier issue involved captures alone.
Unmatched-group status, bounds validity, operation success, cursor progress,
and engine agreement remain independently testable.

If a generated case is outside an oracle's overlap, route it to policy checks
or a supported independent oracle and record the reason. Bound that routing by
structural capabilities and explicit decisions, rather than broad string
filters or a fallback oracle chosen only after the preferred answer disagrees.

## Consolidating the existing divergence documentation

After review, replace the separate "Failed-Path Capture Leakage" and "Empty
Captures in Nullable Counted Repetitions" discussions with one concise
"Repetition, match selection, and captures" entry linking here. Retain
[#52](https://github.com/eaftan/safere/issues/52), #1051, #1079, #1091, the
compatibility experiment, and their useful minimal examples. Add a summary of
the whole-match/find/replacement/split consequences, rather than describing
the boundary as capture positions only.

Keep the distinctions inside the unified entry: rollback of abandoned paths;
replacement of earlier captures by later participation; finite versus
unbounded path selection; and wrapper transparency. Preserve the existing
`FAILED_PATH_CAPTURE_LEAKAGE` classification name or explicitly migrate its
references. Link `INVARIANTS.md` here for the precise meaning of prioritized
paths, while retaining its concise capture and linear-work requirements.

Remove unsupported claims that a particular JDK result necessarily requires
backtracking replay or cannot be matched linearly. Preserve prior decisions as
history where useful, but base the replacement entry on the reviewed model.
This draft does not itself change those entries, tests, or oracle exclusions.

## Review status and remaining work

The owner selected these policy directions during drafting:

- Finite repetition follows explicit expansion with nested optional suffixes,
  including empty iterations followed by consuming ones.
- Unbounded repetition accepts an empty iteration and then stops once its
  minimum is satisfied, retaining accepted capture updates.
- Redundant noncapturing wrappers are transparent throughout, including purely
  empty captures and the resulting differences from current SafeRE and JDK.

The general capture rule recommends updating on every later participation,
retaining on nonparticipation, and restoring on abandonment. Implementation
review must establish the bounds and engine eligibility needed to realize the
whole contract; source-level termination is not that proof. No blanket RE2
oracle switch or compatibility-state expansion is approved by this document.
If review exposes a further observable compatibility choice, add a labeled
proposal with examples before adopting it. No additional semantic choice is
currently left open in this draft; implementation design and measured cost
remain future work.

## References consulted

### Project context and official specifications

- [PR #1092 review][review1092], [#1079][issue1079], [#1051][issue1051], and
  [#1091][issue1091]: motivation and versioned implementation observations.
  GitHub discussions were retrieved through the repository's trusted-author
  snapshot helper.
- [PR #1039][pr1039] and [opaque region bounds](OPAQUE_REGION_BOUNDS.md): the
  precedent for stating an independent model and identifying interpretation.
- [Semantic invariants](INVARIANTS.md) and
  [intentional divergences](../INTENTIONAL_DIVERGENCES.md): existing contracts
  and decisions assessed by this draft.
- [JDK 26 Pattern][jdk-pattern] and [Matcher][jdk-matcher]: the Java
  specification basis, separately assessed from implementation observations.
- [ECMAScript 2026, RepeatMatcher][ecma-repeat]: a normative operational model
  with required versus optional iterations, progress checks, ordered
  continuations, and capture reset. Its choices are not automatically Java's.

### Academic semantics and algorithms

- [Greedy Regular Expression Matching (2004)][greedy-paper]: structured parses,
  priority, the absence of a best finite parse with unrestricted empty star
  iterations, and a linear matching algorithm for its chosen semantics.
- [On the Semantics of Regular Expression Parsing in the Wild (2017)][wild-paper]:
  prioritized capture transducers and Java-oriented semantics; its main
  construction restricts nullable stars and its Java observations concern an
  older runtime, not JDK 26.
- [Tagged Deterministic Finite Automata with Lookahead (2017)][tdfa-paper]:
  capture events, disambiguation, and register-based extraction. Its lexer
  whole-match policy must not be confused with ordered Java search.
- [Linear Matching of JavaScript Regular Expressions (2024)][linearjs-paper]:
  progress-state techniques and capture-representation cost tradeoffs. Its
  nullable repetition and capture-reset model differs from this policy.
- [A Coq Mechanization of JavaScript Regular Expression Semantics (2024)][warblre-paper]:
  an auditable mechanization of ECMAScript 2023, termination, and counterexamples
  to apparently obvious optional-quantifier rewrites.
- [Formal Verification for JavaScript Regular Expressions: A Proven Mechanized
  Semantics and Its Applications (2026, extended version)][tree-paper]: ordered
  execution trees, contextual equivalence, and PikeVM correctness. Its compiled
  language and proof scope are narrower than its full matching semantics.

### Other documented contracts and algorithm descriptions

- [Perl: combining regex pieces](https://perldoc.perl.org/perlre#Combining-RE-Pieces)
  and [zero-length repetition](https://perldoc.perl.org/perlre#Repeated-Patterns-Matching-a-Zero-length-Substring):
  detailed prose rules, with qualifications needed when combining expansion
  recipes and empty-loop behavior.
- [PCRE2: repetition](https://www.pcre2.org/doc/pcre2pattern/#repetition) and
  [.NET: quantifiers and empty matches](https://learn.microsoft.com/en-us/dotnet/standard/base-types/quantifiers-in-regular-expressions#quantifiers-and-empty-matches):
  explicit stopping rules, rather than merely greedy/reluctant labels.
- [PCRE1: assertions][pcre1-assertions]: a documented historical capture
  restoration limitation, distinguished from a deliberate alternative model.
- [Go: `CompilePOSIX`][go-posix]: a documented leftmost-longest whole-match
  alternative; its submatch rules have their own qualifications.
- [Python: `finditer()`][python-finditer]: the documented ability to report a
  nonempty match after an empty result at the same position.
- [XPath/XQuery 3.1: regex groups](https://www.w3.org/TR/xpath-functions-31/#regex-group):
  an explicit allowance for implementation-dependent captures in difficult cases.
- [Rust regex-automata: preference-order matching](https://docs.rs/regex-automata/latest/regex_automata/enum.MatchKind.html)
  and [the Pike VM account](https://swtch.com/~rsc/regexp/regexp2.html): useful
  priority and execution descriptions, not a substitute for the complete
  nullable-repetition contract above.

[review1092]: https://github.com/eaftan/safere/pull/1092#pullrequestreview-5679857952
[pr1039]: https://github.com/eaftan/safere/pull/1039
[issue1079]: https://github.com/eaftan/safere/issues/1079
[issue1051]: https://github.com/eaftan/safere/issues/1051
[issue1091]: https://github.com/eaftan/safere/issues/1091
[compat-experiment]: https://github.com/eaftan/safere/commit/db84b9a5618c4ad299b666fd0311a8a65720b457
[jdk-pattern]: https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/util/regex/Pattern.html
[jdk-matcher]: https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/util/regex/Matcher.html
[ecma-repeat]: https://tc39.es/ecma262/2026/multipage/text-processing.html#sec-repeatmatcher
[pcre2-repeat]: https://www.pcre2.org/doc/pcre2pattern/#repetition
[dotnet-empty]: https://learn.microsoft.com/en-us/dotnet/standard/base-types/quantifiers-in-regular-expressions#quantifiers-and-empty-matches
[pcre1-assertions]: https://pcre.org/original/doc/html/pcrepattern.html
[go-posix]: https://pkg.go.dev/regexp#CompilePOSIX
[python-finditer]: https://docs.python.org/3.13/library/re.html#re.finditer
[greedy-paper]: https://www.cduce.org/papers/icalp04.pdf
[wild-paper]: https://www.cair.org.za/sites/default/files/2020-01/On%20the%20semantics%20.pdf
[tdfa-paper]: https://re2c.org/2017_trofimovich_tagged_deterministic_finite_automata_with_lookahead.pdf
[linearjs-paper]: https://aurele-barriere.github.io/papers/linearjs.pdf
[warblre-paper]: https://arxiv.org/html/2403.11919v3
[tree-paper]: https://arxiv.org/html/2507.13091v3
