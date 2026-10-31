# Repetition, match selection, and captures

This document defines SafeRE's contract for repetition, match selection, and
captures. Finite and unbounded repetition accept a terminal empty iteration
and stop once the minimum count is met. Required empty iterations still count
toward that minimum, and redundant noncapturing wrappers preserve results.

The [decisions and reasonable alternatives](#decisions-and-reasonable-alternatives)
section collects the choices behind these rules, their tradeoffs, and relevant
precedents. It separates documented alternative models from observed quirks.
The [appendix](#appendix-decision-evidence) collects the rationale, comparisons,
and counterexamples for each rule, with shared study methods and reproduction
instructions.

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

## Repetition: accept empty, then stop

`R{m,n}` requires `m` to `n` successful iterations; `R{n}` requires exactly
`n`, and `R{m,}` has minimum `m` and no maximum. `R?`, `R*`, and `R+` have
bounds `{0,1}`, `{0,}`, and `{1,}` respectively.
Iterations share source capture identities. `R{0}` keeps the group numbers but
executes no iterations.

Finite and unbounded repetition use the same rule:

1. An unmet minimum requires another iteration. Empty iterations count toward it.
   Reaching a finite maximum ends the invocation.
2. Once the minimum is met, greedy repetition prefers another iteration over
   stopping; reluctant repetition prefers stopping. Operand alternatives retain
   their own order.
3. Accept each successful iteration and its capture updates. If it consumes
   input, continue with the remaining bounds. If it consumes nothing and the
   minimum has now been met, **stop this invocation** and try the outside
   continuation. Otherwise, continue toward the minimum.

For `R{2,5}`, an empty first iteration still requires a second. If the second
is also empty, accept it and stop; do not offer a third iteration on that path.
Exact `R{2}` likewise executes both required iterations.

Stopping after empty does not commit the match. If the outside continuation
fails, try remaining operand outcomes and earlier choices in their established
order, restoring captures and repetition state when abandoning a choice.

Progress belongs to each invocation and measures consumption by its whole
operand. Reentering an inner repetition starts fresh, even at the same input
position. An inner empty iteration does not stop an outer iteration that
consumed input.

`R*` agrees with `R{0,}`, and `R+` with `R{1,}`, including reluctance.
For nullable `R`, `R+` need not agree with a required `R` followed by a fresh
`R*`; likewise, a nullable counted repetition with optional iterations cannot
be replaced by unguarded copies. Exact `R{n}` still executes `n` required copies
with shared capture identities. For nonnullable operands, counted repetition
orders required copies followed by a nested optional suffix, not independent
optional copies.

Possessive quantifiers over statically zero-width operands commit to the first
greedy local success before trying the outside continuation. Outside failure
cannot reconsider their internal choices. Consuming possessive operands remain
unsupported.

The [reference procedure](#common-repetition-procedure) restates these rules;
it specifies results, not a production execution strategy.

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

### Restoring captures from an abandoned branch

For `(a)b|ac` on `ac`, the first branch captures `a` before failing at `b`.
The second branch matches the whole input at `[0,2)`. Group 1 is unmatched,
with bounds `-1`, because its only write belonged to the abandoned branch.
OpenJDK 26.0.2 also produces this required result.

### Empty captures in finite repetition

On `" y"`, each form matches the whole input at `[0,2)`:

| Pattern | Required group 1 | Observed JDK 26.0.2 group 1 |
|---|---|---|
| `(?:()\|\W+){0,2}y` | Empty at `[1,1)` | Empty at `[1,1)` |
| `(?:()\|\W+){1,2}y` | Empty at `[1,1)` | Empty at `[1,1)` |
| `(?:()\|\W+){2}y` | Empty at `[0,0)` | Empty at `[1,1)` |
| `(?:()\|\W+)*y` | Empty at `[1,1)` | Empty at `[1,1)` |

For `{0,2}`, `{1,2}`, and `*`, an empty first iteration at position 0 meets
the minimum and stops this invocation. The outside `y` then fails. Reconsidering
the operand selects `\W+`, which consumes the space. Its next empty iteration
at position 1 stops and permits `y` to succeed, giving `[1,1)`.

For exact `{2}`, an empty first iteration leaves one required iteration. The
second iteration can consume the space and permit `y` to succeed, retaining the
first iteration's `[0,0)` capture. This remaining JDK difference follows the
minimum and ordered-path rules, rather than a special capture exception.
See [#1051][issue1051].

### Whole-match bounds and later searches

For `(?:b?|a){0,2}b` on `abb`, taking `b?` empty at position 0 ends the
invocation. The outside `b` then fails against `a`. Reconsidering the operand
selects `a`; the next iteration consumes the first `b`, and the outside `b`
consumes the last one. `{1,2}` selects the same complete match.

Exact `{2}` instead permits an empty first iteration followed by `a` in the
second required iteration. Its outside `b` completes the earlier-priority
`ab` match. Its next `find()` matches the remaining `b`.

| Operation or control on `abb` | Required by this policy | Observed JDK 26.0.2 |
|---|---|---|
| `{0,2}` or `{1,2}`: successive `find()` | `[0,3)` (`abb`) | `[0,3)` (`abb`) |
| `{2}`: successive `find()` | `[0,2)` (`ab`), `[2,3)` (`b`) | `[0,3)` (`abb`) |
| `(?:b?\|a)?(?:b?\|a)?b`: successive `find()` | `ab`, `b` | `ab`, `b` |
| `(?:b?\|a)(?:b?\|a)b`: successive `find()` | `ab`, `b` | `ab`, `b` |
| `(?:b?\|a)*b`: successive `find()` | `abb` | `abb` |
| All three counted forms: `matches()` | Success `[0,3)` | Success `[0,3)` |
| `{0,2}` or `{1,2}`: `replaceAll("X")` | `X` | `X` |
| `{2}`: `replaceAll("X")` | `XX` | `X` |
| `{0,2}` or `{1,2}`: `split(input, -1)` | `["", ""]` | `["", ""]` |
| `{2}`: `split(input, -1)` | `["", "", ""]` | `["", ""]` |

The written-out optional forms create separate invocations and therefore need
not agree with one counted repeat over a nullable operand. This is the chosen
cost of empty-stop, not permission to change capture identities or omit required
iterations. See [#1091][issue1091].

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

Greedy repetition accepts an empty iteration and its capture writes. Once
the minimum is met, that iteration ends its invocation. In all these greedy
examples group 1 participates, regardless of the redundant wrapper.

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
| Can an empty iteration be followed by another iteration? | Only while further iterations are required to meet the minimum. Accept the terminal empty iteration and its writes. | Finite expansion permits later copies even after the minimum is met. It preserves unguarded copy equivalence and fits a simple compiler, but differs more often from the JDK in the observed comparisons. |
| When does the empty-stop check apply? | Immediately after a successful iteration, including the iteration that satisfies the minimum. | Complete required copies before entering a separate optional phase. Python 3.13.12 exhibits that boundary in the examples in the appendix; it can continue after an empty final required copy. |
| Should optional empty iterations participate? | Yes, then stop. | Reject them, as [ECMAScript][ecma-repeat] specifies. That changes captures, including whether `(){0,2}` participates on empty input. |
| How are choices ordered when no empty-stop occurs? | Required iterations followed by a nested optional suffix, respecting the quantifier's order. | Independent optional copies have different fallback order. Even without nullable operands, `(a\|aa){0,2}?b` distinguishes nested and flat choices. |

SafeRE chooses minimum-respecting empty-stop for better observed Java
compatibility and a shared rule for finite and unbounded repetition. The
[appendix](#empty-stop-rather-than-finite-expansion) compares it with finite
expansion across different operands and contexts. This is a compatibility choice
under an incomplete operational JDK specification, not a claim of cross-engine
consensus or exact JDK emulation.

Finite expansion remains a coherent alternative. RE2 and Rust regex use it for
the finite whole-match cases examined; PCRE2 10.42 also agrees with expansion on
the two original zero-minimum examples. Their results are versioned observations,
not a claim that their full capture or unbounded-repeat policies equal one another.

The cost of the selected rule is that nullable counted repetitions with optional
iterations cannot be lowered to unguarded independent copies. Progress state
must preserve invocation boundaries, minimum and maximum counts, priority, and
capture rollback. A finite maximum already ensures termination; empty-stop is
chosen for semantics and compatibility. Linear time, stack safety, and measured
performance remain implementation requirements.

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

Finite and unbounded repetition share the minimum-respecting empty-stop rule.
They differ in whether a maximum count also ends the invocation. The common
rule preserves successful terminal empty captures without requiring exact
emulation of every engine's internal progress checks.

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
specification violation. Minimum-respecting empty-stop is SafeRE's explicit
interpretation and compatibility choice, including its whole-match effects.
It is not claimed to be forced by the JDK specification or by linear time.
Remaining JDK differences at exact counts and redundant wrappers are kept
explicit rather than incorporated as pattern-specific policy exceptions.

SafeRE also requires redundant wrappers to preserve results. Wrapper-dependent
JDK observations alone do not determine a complete nullable-repetition policy.

## Appendix: decision evidence

Each subsection records the rule, its alternatives or invariant checks, and the
supporting evidence. Observed results are pinned to OpenJDK `26.0.2+10-55` unless
another engine version is named. The normative rules above remain the contract;
agreement counts do not override documented API requirements.

In head-to-head tables, counts include only cases where the two rules predict
different results. Cases where both predictions agree, including shared misses
against Java, remain in the recorded results. These constructed examples are
not production workload samples or universal compatibility claims. The
[methods and reproduction](#study-methods-and-reproduction) subsection describes
the different counting units and validation used by the two studies.

### Ordered complete success

Choose the first complete success at the earliest permitted start, following
ordered alternatives and local greedy or reluctant choices. This follows the
[Java match-selection contract][jdk-pattern]. The alternative tested chooses
the longest whole match at that same start, with ordered priority breaking
length ties; it is not a full POSIX submatch model.

| JDK agrees with ordered first | JDK agrees with longest | JDK agrees with neither |
|---:|---:|---:|
| 1,072 | 0 | 0 |

For `(a){0,2}?` on `aa`, the selected rule and Java match `[0,0)` with group 1
unmatched. Longest selection instead matches `[0,2)` with group 1 at `[1,2)`.
The dedicated continuation, acceptance, and alternative-order controls also
agree with the selected model: full-match acceptance belongs in the
continuation, and reluctance does not reverse operand alternatives.

### Empty-stop rather than finite expansion

Finite expansion treats `R{m,n}` as required copies followed by a nested optional
suffix, permitting later copies after an empty iteration. The selected rule
instead accepts empty and stops once the minimum is met. The JDK specification
does not fully choose between these operational models.

The whole-match comparison favors empty-stop across different operand
structures; no distinguishing observation favored expansion. Combined with a
shared rule for finite and unbounded repetition, this outweighs unguarded copy
equivalence for nullable operands with optional iterations.

| Distinguishing operand family | JDK agrees with empty-stop | JDK agrees with expansion | JDK agrees with neither |
|---|---:|---:|---:|
| Optional-first alternatives | 19 | 0 | 1 |
| Overlapping alternatives | 4 | 0 | 0 |
| Reluctant operands | 12 | 0 | 0 |
| Anchors and word boundaries | 33 | 0 | 2 |
| Nested finite repetitions | 16 | 0 | 0 |
| Total | 84 | 0 | 3 |

Families absent from the table did not distinguish whole-match bounds in this
corpus. Captured variants duplicating a whole-match structure are counted with
that structure. First-capture and complete `find()`-history comparisons favor
empty-stop in the same direction, but reuse observations and are not additional
independent evidence.

| Pattern | Subject | Expansion's first match | Empty-stop's first match | Observed JDK first match |
|---|---|---|---|---|
| `(?:(?:ab)?\|a?b){0,2}a` | `baba` | `ba` | `baba` | `baba` |
| `(?:a??b?){0,2}b` | `abab` | `ab` | `abab` | `abab` |
| `(?:\b\|[ab]){0,2}a` | `baa` | `ba` | `baa` | `baa` |
| `(?:a{0,2}\|b?){0,2}a` | `baa` | `ba` | `baa` | `baa` |

For the capture-only example `(?:()|a){0,2}\z` on `a`, both models match
`[0,1)`. Expansion retains group 1 at `[0,0)`; empty-stop and Java place it at
`[1,1)`.

The cost is that finite lowering must preserve invocation boundaries, remaining
bounds, priority, progress, and capture rollback. A finite maximum already
ensures termination; empty-stop is a semantic and compatibility choice. The
study establishes neither a production implementation nor its performance.

#### Common repetition procedure

This procedure restates the selected rule for both finite and unbounded forms.
Initially `need` and `limit` are the minimum and maximum, with infinity for an
unbounded maximum. `state` holds the input position and captures; `outside` is
the continuation after this invocation.

```text
Repeat(R, need, limit, greedy, state, outside):
    if limit == 0: return outside(state)

    define iterate():
        execute R from state with continuation(next):
            remainingNeed = max(0, need-1)
            remainingLimit = infinity if limit == infinity else limit-1
            if next.position == state.position and remainingNeed == 0:
                outside(next)
            else:
                Repeat(R, remainingNeed, remainingLimit, greedy, next, outside)

    if need > 0: try iterate()
    otherwise: choose between iterate() and outside(state)
               iterate() first if greedy; outside(state) first if reluctant
```

Each choice returns its first complete success and restores incoming state when
abandoned. Recursive calls continue the same invocation; a nested repetition
starts its own invocation. This is a result specification, not a production
execution strategy.

### Stop when the minimum is satisfied

The empty-stop check includes the iteration that satisfies the minimum. The
alternative completes required copies before applying the check in a fresh
optional phase.

| JDK agrees with the selected boundary | JDK agrees with a fresh optional phase | JDK agrees with neither |
|---:|---:|---:|
| 503 | 0 | 9 |

For `(?:()|a){1,2}b` on `ab`, the selected rule and Java place group 1 at
`[1,1)`. The optional-phase alternative retains `[0,0)`. Remaining differences
are explicit: for `(?:b?|(a)){2,3}b` on `abbb`, the selected rule matches
`[0,3)`, the alternative `[0,2)`, and Java `[0,4)`. On `a`, both empty-stop and
finite expansion accept `(?:\A|a){2}\z`, but Java rejects it. Required counts
must still be respected; these observations do not authorize bypassing them.

Versioned cross-engine controls illustrate why matching one example does not
establish a common policy. For `(?:b?|a)QUANTIFIERb` on `abb`, the first match is:

| Engine or policy | `{0,2}` | `{1,2}` | `{2}` |
|---|---|---|---|
| Selected minimum-respecting empty-stop | `abb` | `abb` | `ab` |
| Finite expansion | `ab` | `ab` | `ab` |
| RE2 2025-11-05 | `ab` | `ab` | `ab` |
| Rust regex 1.13.1 | `ab` | `ab` | `ab` |
| Perl 5.38.2 | `abb` | `abb` | `ab` |
| Python 3.13.12 `re` | `abb` | `ab` | `ab` |
| JavaScript, Node 24.13.1 | `abb` | `ab` | `ab` |
| OpenJDK 26.0.2 | `abb` | `abb` | `abb` |

The [RE2][re2-finite] and [Rust][rust-finite] implementations describe copy-based
finite lowering. Perl matched minimum-respecting empty-stop in the finite
whole-match comparisons examined. Python's [repeat implementation][python-repeat]
completes required copies before applying its optional-phase progress check.
JavaScript instead rejects optional empty iterations and clears captures between
iterations, as specified by [RepeatMatcher][ecma-repeat]. These observations do
not equate the engines' full repetition or capture models.

PCRE2 10.42 also agreed with expansion on the original zero-minimum examples:
`(?:b?|a){0,2}b` on `abb` first matched `ab`, and `(?:()|\W+){0,2}y` on `" y"`
placed group 1 at `[0,0)`. [.NET's documentation][dotnet-empty] describes
accepting empty and stopping once the minimum is met; .NET was not executed.

### Accept terminal empty iterations and their writes

The selected rule accepts an optional terminal empty iteration. Rejecting such
outcomes is coherent, but changes capture participation. The comparison changes
only that rule, retaining the selected handling of required empty iterations.

| Repetition | JDK agrees with acceptance | JDK agrees with rejection | JDK agrees with neither |
|---|---:|---:|---:|
| Finite | 2,170 | 230 | 0 |
| Unbounded | 2,163 | 230 | 0 |

For `(a?)?` on empty input, acceptance and Java capture group 1 at `[0,0)`;
rejection leaves it unmatched. Conversely, Java leaves group 1 unmatched for
`(){0,2}` and `()*`, favoring rejection. All rejection wins come from those
two spellings and their surrounding contexts and subjects; redundant wrappers
change their Java results. Seven informative operand families favor acceptance,
while the directly quantified zero-width capture family favors rejection.
Reluctant forms in this corpus do not distinguish acceptance from rejection in
the first match.

This supports a uniform acceptance rule, not a claim that Java consistently
accepts terminal empty captures. [PCRE2][pcre2-repeat] and [.NET][dotnet-empty]
document empty-stop behavior, without establishing complete semantic equivalence
with this policy.

Perl 5.38.2 and Python 3.13.12 capture group 1 at `[0,0)` for both `()*` and
`(){0,2}` on empty input, supporting participation in these versioned controls.

### Nested optional suffix order

For nonnullable operands, required copies are followed by a nested optional
suffix. Independent optional copies have different fallback order, even when
the source capture identities are shared.

| JDK agrees with nested optionals | JDK agrees with independent optionals | JDK agrees with neither |
|---:|---:|---:|
| 80 | 0 | 0 |

For `(a|aa){0,2}?b` on `aab`, the selected rule and Java take two `a` iterations
and capture group 1 at `[1,2)`. Independent reluctant optionals can omit the
first copy and select one `aa` iteration in the second, capturing `[0,2)`.
Stopping a counted repetition omits its entire remaining optional suffix.
Literal source duplication also introduces separate group numbers; the model
comparison preserves the original identities.

### Invocation-local progress

`R+` is one invocation; treating it as required `R` followed by a fresh `R*`
changes nullable behavior, even with shared capture identities.

| JDK agrees with one invocation | JDK agrees with a fresh star | JDK agrees with neither |
|---:|---:|---:|
| 210 | 0 | 0 |

For `R = (?:^()|a)` followed by `b` on `ab`, an empty first iteration stops
`R+`, whose outside `b` fails. Its successful path therefore consumes `a`,
leaving group 1 unmatched: the empty alternative is unavailable at position 1.
A conceptual `RR*` can retain the first copy's `[0,0)` capture while its fresh
star consumes `a`. OpenJDK 26.0.2 and Perl 5.38.2 leave group 1 unmatched;
Python 3.13.12 retains `[0,0)` on this example, without establishing general
equivalence to the alternative model.

The JDK-only matrix also distinguishes `(?:()|a)+?b` on `ab`: Java and one
invocation leave group 1 unmatched; a fresh star retains `[0,0)`. Nested controls
confirm that an inner empty iteration does not stop an outer consuming
iteration, except for skipped writes from directly quantified empty inner
captures, the terminal-empty issue above. Reentering an inner repetition starts
a new invocation even at the same position.

### Latest completed capture

The [Java capture contract][jdk-pattern] requires the most recently matched
subsequence. This is the primary basis for the selected update rule, rather
than a frequency comparison with freezing earlier captures.

For `(?:(a){1,2}b)*` on `aabab`, the accepted chunks are `aab` and `ab`. Group 1
necessarily participates at `[3,4)` in the second chunk. Java's observed `[1,2)`
discards that later accepted participation; no empty outer iteration is involved.
The [nested worked examples](#earlier-captures-in-nested-repetition) show the
same issue with nullable outer iterations and redundant wrappers. Perl 5.38.2
and Python 3.13.12 report `[3,4)` in the consuming-chunk example; Python does so
for both nullable spellings as well.
See the [Perl contract][perl-repeat] and [Python documentation][python-re] for
their respective models, not a claim of complete agreement with this policy.

#### Diagnostic contrast: freezing the first capture

The diagnostic comparison finds 6,368 distinguishing cases agreeing with latest
capture, none agreeing with freezing the first, and 196 agreeing with neither.
This rejects a uniform first-capture rule; it does not model Java's actual
nested-capture quirks or decide between live policy alternatives.

For `(?:(a){0,2}){0,2}` on `aaaa`, latest capture gives `[3,4)`, freezing the
first gives `[0,1)`, and Java reports `[1,2)`. Java therefore follows neither
uniform update rule on that example. The diagnostic remains reproducible in
the recorded data, but is not a policy-choice scorecard.

### Retain captures omitted by later iterations

Retain an earlier accepted value when a later iteration omits its group,
following the [Java capture contract][jdk-pattern]. The alternative clears the
operand's captures before every iteration while preserving rollback.

| JDK agrees with retention | JDK agrees with clearing | JDK agrees with neither |
|---:|---:|---:|
| 2,233 | 0 | 100 |

For `(a(b)?){0,2}` on `aba`, retention and Java leave group 2 at `[1,2)`;
clearing makes it unmatched. Neither model reproduces all nested results:
for `(?:(a){0,2})*` on `aaaa`, retention gives `[3,4)`, clearing gives unmatched,
and Java reports `[1,2)`. The clearing model isolates that choice; it does not
import the entire ECMAScript repetition model.

### Restore abandoned state

The dedicated rollback controls agree with restoration of ordinary abandoned
branches and iterations. This is a correctness invariant, not an alternative
capture-retention preference. For `(a)b|ac` on `ac`, Java and this policy leave
group 1 unmatched because its only write belonged to the failed branch.

Fresh candidate starts must likewise discard failed-attempt captures. Java
violates that invariant in counted-group controls: `(?:(a)){0,2}b` on `a b`
first matches `[2,3)` with group 1 at `[0,1)`, from a failed earlier start.
Searching a fresh region beginning at 2 leaves group 1 unmatched. These are
recorded implementation observations, not additional retention rules. See
[failed-path capture leakage](../INTENTIONAL_DIVERGENCES.md#failed-path-capture-leakage).

### Transparent wrappers

Redundant noncapturing wrappers must preserve observations. Of the wrapper
pairs, 65,118 have equal first results and 1,002 differ, all in captures.
Differences include terminal empty participation and stale captures from failed
starts. For example, Java leaves group 1 unmatched for `(){0,2}` on empty input
but captures `[0,0)` for `(?:()){0,2}`.

Exact wrapper-sensitive emulation is not a coherent alternative meaning of a
redundant wrapper. Nested-capture observations in the worked examples likewise
do not justify freezing earlier accepted values. The policy preserves local
refactoring and requires compiler transformations to preserve ordered fallback
outcomes, not just recognition language or first isolated results.

### Search progress after empty matches

Advance the cursor after a published empty match, rather than retry a consuming
outcome at that same start. This is separate from progress within repetition.

| JDK agrees with advancing | JDK agrees with same-position retry | JDK agrees with neither |
|---:|---:|---:|
| 206 | 0 | 0 |

For `(?:()|a){0,2}` on `a`, Java and the selected rule publish empty matches at
0 and 1. The alternative also publishes a consuming `[0,1)` match between them.
[Python's search API][python-finditer] permits such same-position consumption;
that is a different search contract, not an empty-iteration rule.

Supplementary astral-character controls all favor UTF-16 advancement over
scalar advancement for String matchers. UTF-8 follows its own API contract and
is not compared to a Java String matcher.

### Canonical observations, replacement, and split

Group access, snapshots, and eligible engine selection must preserve the chosen
match and search cursor. Replacement and split derive from the canonical
sequence under their API-specific rules, not independent match-selection
preferences. The [Matcher contract][jdk-matcher] supplies the API basis.

No failures occur when comparing observed with unobserved searches, reset with
a fresh operation, or canonical histories with literal, capture, and functional
replacement, `appendReplacement`, and split limits -1, 0, and 2. These controls
derive expectations from Java's actual history, even when it differs from this
policy. They check API consistency, not SafeRE conformance to the selected rule.

### Capture identities and API surface

Dedicated controls agree on group zero, empty versus unmatched groups, source
group numbering under `{0}`, and required empty counts. These are fixed
contracts. Lowering a repetition into copies must preserve its source group
identities.

The API exposes one final interval per group. Exposing all accepted captures
would change that API and its storage costs; it cannot be frequency-ranked
through Java's existing group accessors. A semantic execution history does not
require an implementation to store a capture-history collection.

### Statically zero-width possessive commitment

Possessive repetition commits to its first greedy local success. Within the
supported patterns here, commitment versus ordinary fallback cannot distinguish
success, spans, or captures: operand outcomes leave the same position, and the
outside expression cannot depend on captures.

Java nevertheless differs between ordinary and possessive spellings in 1,703
of 10,440 pairs, all in captures. `(){0,2}` omits group 1 on empty input, while
`(){0,2}+` captures empty. These observations do not choose between conceptual
commitment rules; exact emulation would add unrelated capture exceptions.

### Linear work and stack safety

Both are nonnegotiable implementation constraints. On finite input, consuming
iterations are bounded by input progress, required empty iterations by the
fixed minimum, and a terminal empty iteration ends its invocation. This makes
the conceptual executions finite, not acceptable to enumerate in production.

Semantic agreement on small examples establishes neither linear complexity nor
acceptable compilation and matching performance. The selected policy requires
a bounded linear formulation and stack safety; the studies provide no such
implementation proof and authorize no repeated matching or prefix replay.

### Study methods and reproduction

The [study directory](../research/repetition-policy/README.md) contains the
reference model, public-JDK probes, and reproducible runners.

The [finite-repetition study](../research/repetition-policy/run.py) changes only finite
empty-stop. Both models share unbounded repetition, ordered choices, capture
retention, and rollback. Operand families include optional atoms, empty-first
and optional-first alternatives, nullable concatenations, overlapping
alternatives, reluctant operands, captured nullable operands, assertions,
nested finite and unbounded repetitions, and purely zero-width operands.
The matrix varies bounds, greediness, suffixes, captures, and prefixed or
anchored contexts. Exact ASCII subjects and patterns are declared in its
generator.

Its primary observation is the first `find()` result's whole-match bounds.
Deduplication uses canonical AST plus exact subject, stripping captures and
redundant wrappers and flattening concatenations. Alternative order, invocation
boundaries, bounds, greediness, and literal values remain distinct. The scripts
record source-dependent Java outcomes before collapsing variants. Every fully
finite pattern also has an independent public-JDK control using explicit copies
and alternatives with captures removed. First bounds, search histories,
`lookingAt()`, and `matches()` agree with the expansion interpreter. This
validates the whole-match construction, not all capture or empty-stop semantics.
The [recorded classifications](../research/repetition-policy/observed-jdk26.json)
include shared disagreements as well as distinguishing results.

The [remaining-rule study](../research/repetition-policy/other_rules.py) changes one
policy dimension at a time while keeping other rules fixed. Its primary unit is
one unique source pattern and exact ASCII subject, comparing the full first-find
capture vector. The cursor comparison instead uses the complete search history.
Unlike the whole-match-only study, capture identities remain significant.
Extra wrapper spellings are reserved for the dedicated pair study. Its families
vary consuming and nullable literals and words, overlapping alternatives in
both orders, branch captures, optional prefixes and tails, reluctance, assertions,
and nested repetitions. Contexts include bare patterns, literal and alternative
suffixes, end assertions, and literal or ambiguous prefixes. Full operation
results and per-family and quantifier-regime counts are in the
[recorded classifications](../research/repetition-policy/other-rules-observed-jdk26.json).

The interpreter checks fixed distinguishing examples for each intervention and
asserts invariants for capture-only changes, cursor retry, and longest selection.
An independent public-JDK check fully expands the optional-order patterns and
reconstructs shared source identities from consuming capture writes; both nested
and flat models agree for first matches, anchored operations, and histories.
This validates that construction, not every rule of the interpreter. Different
operation views reuse examples and must not be added as independent votes.

To reproduce with Python 3.13 and JDK 26.0.2 selected by `JAVA_HOME`, run from
the repository root:

```bash
python3 research/repetition-policy/run.py \
  --java "$JAVA_HOME/bin/java" \
  --output /tmp/repetition-policy-study \
  --check-recorded

python3 research/repetition-policy/other_rules.py \
  --java "$JAVA_HOME/bin/java" \
  --output /tmp/repetition-other-policy-study \
  --check-recorded
```

No additional packages are needed. Each runner checks completion, case
identities, the actual Java runtime, and recorded classifications. Omit
`--check-recorded` to collect another installed JDK version. Generated raw
results stay outside the checkout; keep them when reporting new measurements.
The studies do not cover arbitrary patterns, regions, flags, Unicode
segmentation, `hitEnd()`/`requireEnd()`, callback mutation, or performance. The
supplementary String cursor control covers only empty-match advancement.

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

[re2-finite]: https://github.com/google/re2/blob/927f5d53caf8111721e734cf24724686bb745f55/re2/simplify.cc#L602
[rust-finite]: https://docs.rs/regex-automata/0.4.16/src/regex_automata/nfa/thompson/compiler.rs.html#1184-1241
[python-repeat]: https://github.com/python/cpython/blob/v3.13.12/Modules/_sre/sre_lib.h#L1069-L1128
