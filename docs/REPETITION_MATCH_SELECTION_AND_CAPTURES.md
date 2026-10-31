# Repetition, match selection, and captures

This document defines SafeRE's contract for repetition, match selection, and
captures. Finite repetition uses required copies followed by nested optional
copies. Unbounded repetition accepts a terminal empty iteration, and redundant
noncapturing wrappers preserve results.

The [decisions and reasonable alternatives](#decisions-and-reasonable-alternatives)
section collects the choices behind these rules, their tradeoffs, and relevant
precedents. It separates documented alternative models from observed quirks.

## Scope and governing rule

These rules apply to supported patterns in String and UTF-8 matching. Positions
use each API's coordinates; String examples below use UTF-16 indices and
half-open bounds. Region, assertion, Unicode, and search-cursor rules remain
those of the corresponding APIs and [semantic invariants](INVARIANTS.md).

The following terms distinguish pattern structure from input progress:

- An **operand** is the expression to which a quantifier applies.
- An **iteration** is one successful execution of that operand. An **empty
  iteration** leaves the input position unchanged, but may update captures.
- A **nullable** operand can succeed without consuming input. Whether a
  particular iteration is empty depends on its selected path and input context.
  A **statically zero-width** operand is one whose successful executions can
  be established from the pattern to consume no input.
- An **invocation** is one entry into a repetition, including its iterations
  until it exits. Entering that repetition again starts a new invocation.
- A **continuation** is the remaining pattern together with the operation's
  acceptance condition. A local success is usable only if its continuation
  also succeeds.

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
remaining expression. The capture map records each group's latest bounds.
Each choice has ordered children. Explore earlier children before later
children, including their remaining expressions.

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

Equivalently, let `need` be the remaining minimum count, `state` contain the
input position and captures, and `outside` be the continuation after this
invocation. The initial `need` is 0 for `*`, 1 for `+`, and `m` for `{m,}`.

```text
Unbounded(R, need, greedy, state, outside):
    define iterate():
        execute R from state with continuation(next):
            remaining = max(0, need-1)
            if next.position == state.position and remaining == 0:
                outside(next)
            else:
                Unbounded(R, remaining, greedy, next, outside)

    if need > 0: try iterate()
    otherwise: choose between iterate() and outside(state)
               iterate() first if greedy; outside(state) first if reluctant
```

Each choice returns its first complete success. If its continuation fails,
restore the incoming state and try the next available path in the established
order. Recursive calls continue the same invocation; a nested repetition in
`R` starts its own invocation. As with the finite definition, this recursion
specifies results rather than a production execution strategy.

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
matter. The conceptual copies share capture identities; literal source
duplication introduces separate group numbers.

On this example, OpenJDK 26.0.2 and Perl 5.38.2 leave group 1 unmatched,
while Python 3.13.12 retains `[0,0)`. Python is a concrete observed example
of the `RR*` alternative here, not a claim of general cross-engine equivalence.

On finite input, consuming iterations are bounded by input progress, required
empty iterations by the fixed minimum, and a terminal empty iteration ends its
invocation. This makes the conceptual executions finite; it does not make
enumerating those executions an acceptable production algorithm.

Possessive quantifiers over statically zero-width operands use the greedy
repetition outcomes but commit to the first local success before trying the
outside continuation. Failure outside cannot reconsider that quantifier's
internal choices. Consuming possessive operands and other excluded features
remain outside the supported dialect.

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

Nested repetitions apply the same capture update rule at every depth. No
compiler transformation may freeze an earlier capture simply because it occurs
inside a nested quantifier. Nor may a transformation infer equivalence solely
from recognition language, absence of captures, or equality of first isolated
results. It must preserve ordered fallback outcomes in surrounding contexts.

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

## Worked examples

The required results illustrate the contract. The comparison column records
observations of OpenJDK 26.0.2. These fixed-version observations are informative;
they do not define the required behavior. Implementation snapshots belong in
the linked issues and PRs rather than this specification.

### Earlier captures in nested repetition

In each case, the first `find()` covers the whole input. The table shows group 1:

| Pattern and input | Required by this policy | Observed JDK 26.0.2 |
|---|---|---|
| `(?:(a){0,2})*` on `aaaa` | `[3,4)` | `[1,2)` |
| `(?:(?:(a)){0,2})*` on `aaaa` | `[3,4)` | `[3,4)` |
| `(?:(a){1,2}b)*` on `aabab` | `[3,4)` | `[1,2)` |

For the first two cases, later consuming iterations update group 1. A final
empty outer iteration does not participate in `(a)`, so it retains `[3,4)`.
Preserving `[1,2)` instead would discard a later accepted participation.
See [#1079][issue1079].

The third case has no empty outer iterations: the accepted chunks are `aab`
and `ab`. Group 1 necessarily participates at `[3,4)` in the second chunk.
This separates the most-recent-capture rule from nullable path selection.

Perl 5.38.2 and Python 3.13.12 also report `[3,4)` in the third case.
Python reports `[3,4)` for the first two spellings as well. These are observed
results supporting the chosen retention rule, not claims that the engines
share every repetition rule. See the [Perl contract][perl-repeat] and
[Python regex documentation][python-re] for their respective models.

### Restoring captures from an abandoned branch

For `(a)b|ac` on `ac`, the first branch captures `a` before failing at `b`.
The second branch matches the whole input at `[0,2)`. Group 1 is unmatched,
with bounds `-1`, because its only write belonged to the abandoned branch.
OpenJDK 26.0.2 also produces this required result.

### Empty captures in finite repetition

On `" y"`, each form matches the whole input at `[0,2)`:

| Pattern | Required group 1 | Observed JDK 26.0.2 group 1 |
|---|---|---|
| `(?:()\|\W+){0,2}y` | Empty at `[0,0)` | Empty at `[1,1)` |
| `(?:()\|\W+){1,2}y` | Empty at `[0,0)` | Empty at `[1,1)` |
| `(?:()\|\W+)*y` | Empty at `[1,1)` | Empty at `[1,1)` |

In the `{0,2}` case, finite expansion first tries the empty branch in the first
copy. After unsuccessful choices in the second copy, its `\W+` branch consumes
the space and the suffix matches `y`. The second iteration does not participate
in group 1, so its `[0,0)` value survives.

For the `*` form, an empty iteration at position 0 stops the repetition and
`y` then fails. Reconsidering that operand selects the consuming branch. The
next empty iteration at position 1 stops the repetition and allows `y` to
succeed, giving `[1,1)`. Counted and unbounded forms differ because their
admissible executions differ, not because capture retention changes.
See [#1051][issue1051].

### Whole-match bounds and later searches

For `(?:b?|a){0,2}b` on `abb`, the first copy can take `b?` empty; the second
then takes `a`; the final `b` consumes the middle character. That complete
success has priority over reconsidering the first copy to obtain a longer
match. The same result follows for `{1,2}` and `{2}`.

| Operation or control | Required by this policy | Observed JDK 26.0.2 |
|---|---|---|
| Counted forms: successive `find()` | `[0,2)` (`ab`), `[2,3)` (`b`) | `[0,3)` (`abb`) |
| `(?:b?\|a)?(?:b?\|a)?b`: successive `find()` | `ab`, `b` | `ab`, `b` |
| `(?:b?\|a)(?:b?\|a)b`: successive `find()` | `ab`, `b` | `ab`, `b` |
| `(?:b?\|a)*b`: successive `find()` | `abb` | `abb` |
| Counted forms: `matches()` | Success `[0,3)` | Success `[0,3)` |
| Counted forms: `replaceAll("X")` | `XX` | `X` |
| Counted forms: `split(input, -1)` | `["", "", ""]` | `["", ""]` |

The optional-copy control agrees here because it is greedy; this is not a
general equivalence between flat and nested optional copies. See
[#1091][issue1091].

### Purely empty captures and redundant wrappers

On empty input, the first `find()` matches `[0,0)` in every case:

| Pattern | Required group 1 | Observed JDK 26.0.2 group 1 |
|---|---|---|
| `()*` | Empty at `[0,0)` | Unmatched, bounds `-1` |
| `()*?` | Unmatched, bounds `-1` | Unmatched, bounds `-1` |
| `(?:())*` | Empty at `[0,0)` | Empty at `[0,0)` |
| `(){0,2}` | Empty at `[0,0)` | Unmatched, bounds `-1` |
| `(?:()){0,2}` | Empty at `[0,0)` | Empty at `[0,0)` |
| `(){2,}` | Empty at `[0,0)` | Empty at `[0,0)` |

Greedy finite repetition accepts its empty copies; greedy unbounded repetition
accepts one terminal empty iteration. In both cases group 1 participates,
regardless of the redundant wrapper.

Perl 5.38.2 and Python 3.13.12 both report group 1 at `[0,0)` for `()*`
and `(){0,2}` on empty input, supporting the chosen participation rule.

The reluctant `()*?` first chooses zero iterations. Its continuation already
succeeds, so group 1 never participates. In `(){2,}`, two empty iterations are
required: the first leaves the input position unchanged but reduces the
remaining minimum to one; the second satisfies the minimum and ends the
invocation. Input consumption is not required for this bounded progress
toward the minimum.

## Decisions and reasonable alternatives

There is no single cross-engine contract for empty iterations. Shared syntax
and the labels "greedy" and "reluctant" do not settle whether an empty
iteration is accepted, whether another iteration may follow it, or which
captures survive. Several coherent models exist. Different measured results
are evidence of disagreement, but do not establish that every result reflects
a deliberate design decision.

The comparisons below correspond to the semantic sections above. The SafeRE
column records the contract; the alternatives explain what another language
can reasonably prioritize. Linear input work and stack safety constrain every
SafeRE implementation.

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
Nested optional suffixes give greedy and reluctant counted repeats the same
prefix structure. The resulting finite-repeat paths can differ from those
selected by other engines, as the worked examples show.

This compatibility cost extends beyond Java. For `(?:b?|a){0,2}b` on
`abb` and `(?:()|\W+){0,2}y` on `" y"`, OpenJDK 26.0.2, Perl 5.38.2,
and Python 3.13.12 select `abb` as one match and place the empty capture at
`[1,1)`, rather than this policy's `ab`, `b` sequence and `[0,0)` capture.
These observations concern those zero-minimum examples; required iterations
can give different agreements between engines. The difference
therefore affects whole-match bounds, later searches, replacement, and
splitting. [.NET's documented rule][dotnet-empty] also stops finite repetition
after an empty iteration once its minimum is met.

This is not uniform across the PCRE/Perl family: PCRE2 10.42's traditional
matcher returns this policy's results on both examples. Its [repetition
manual][pcre2-repeat] describes stopping for unbounded empty iterations;
it does not establish that every bounded form follows that rule. These
versioned observations should not be generalized into universal engine
compatibility claims.

The JDK Javadoc does not expressly require the expansion contract. ECMAScript's
rejection of optional empty iterations is explicit. A flat optional expansion
is listed as a possible language design; no claim is made that a named engine
promises that contract.

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
local refactoring and compiler equivalence understandable, including for
purely empty captures.

Exact emulation of observed wrapper-dependent results is a possible
compatibility priority. However, no documented semantic purpose for those
particular differences has been established here. They are classified as
observed implementation behavior, not presented as another principled meaning
of a redundant wrapper. Likewise, freezing an earlier capture despite later
accepted participation is not an alternative allowed by this contract's
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
design alternative within these APIs.

## JDK specification, interpretation, and compatibility

The official [JDK 26 Pattern specification][jdk-pattern] defines ordered
alternation, repetition bounds, and capture retention. Its most-recent-capture
rule states:

> The captured input associated with a group is always the subsequence that the
> group most recently matched.

Its `(a(b)?)+` example retains `b` on `aba`. The [Matcher specification][jdk-matcher]
defines operation acceptance conditions, result access, and successive searches.

For `(?:(a){1,2}b)*` on `aabab`, the complete match necessarily includes a later
participation of group 1 at `[3,4)`, as shown in the
[worked example](#earlier-captures-in-nested-repetition). The observed JDK capture
at `[1,2)` conflicts with the most-recent-capture rule under this contract.
This concern does not depend on how empty iterations are selected.

The Javadocs do not give an operational algorithm for nullable repetition or
fully order its empty and consuming executions. Different capture positions
or whole-match bounds in those cases do not by themselves establish a
specification violation. Finite expansion and terminal-empty stopping are
SafeRE's explicit interpretation and compatibility choice, including their
whole-match effects. Neither rule is claimed to be forced by the JDK
specification or by linear time.

SafeRE also requires redundant wrappers to preserve results. Wrapper-dependent
JDK observations alone do not determine a complete nullable-repetition policy.

## References consulted

### Specifications and related contracts

- [Semantic invariants](INVARIANTS.md),
  [opaque region bounds](OPAQUE_REGION_BOUNDS.md), and
  [intentional divergences](../INTENTIONAL_DIVERGENCES.md): related SafeRE
  contracts and compatibility boundaries.
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

[issue1079]: https://github.com/eaftan/safere/issues/1079
[issue1051]: https://github.com/eaftan/safere/issues/1051
[issue1091]: https://github.com/eaftan/safere/issues/1091
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

[perl-repeat]: https://perldoc.perl.org/perlre#Repeated-Patterns-Matching-a-Zero-length-Substring
[python-re]: https://docs.python.org/3.13/library/re.html
