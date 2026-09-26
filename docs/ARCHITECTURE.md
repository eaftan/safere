# Architecture

SafeRE compiles a Java-oriented regex language to finite automata. The design
preserves linear matching work for a fixed compiled pattern while exposing
Java-style match selection, captures, and matcher state. See
[semantic invariants](INVARIANTS.md) for the contracts each stage must preserve.

## Compilation pipeline

```text
Pattern string → Parser → Regexp AST → Simplifier → Compiler → Prog → Execution
```

The parser uses explicit stacks to handle nested expressions without consuming
the Java call stack. It records source semantics such as scoped flags, group
numbering, alternation priority, and greedy or reluctant repetition. Unsupported
constructs are rejected before execution; accepted syntax is documented in
[syntax and compatibility](SYNTAX.md).

Simplification reduces the AST and lowers counted repetitions. Rewrites must
preserve observable captures, not only the set of matching strings. Compiler
analysis and guarded control-flow lowering handle retained capture values;
public capture access must not repair them by reinterpreting the original AST.

The compiler uses Thompson construction to connect instruction fragments.
`Prog` holds the resulting program and analysis metadata. Instructions express
ordered alternatives, character consumption, captures, assertions, repetition
progress, and acceptance. Capture registers store start/end pairs, including
group zero for the complete match. Grapheme consumption has a distinct
instruction from the zero-width grapheme-boundary assertion.

AST and program traversal use worklists or iterative walkers. Pattern nesting
must not turn into unbounded Java recursion during parsing, simplification,
compilation, or analysis.

## Execution strategies

SafeRE selects eligible strategies according to the pattern, operation, input,
region, and required result. Selection order and size thresholds are tuning
choices in the code, not API guarantees.

| Strategy | Purpose |
|---|---|
| Literal, character-class, and keyword paths | Execute recognized pattern shapes directly |
| Prefix/start acceleration | Skip positions that cannot start a match |
| OnePass | Match an unambiguous program with captures in one pass |
| Forward DFA | Reject impossible matches and, when eligible, establish bounds |
| Reverse DFA | Narrow candidate starts or reject end-constrained searches |
| BitState | Explore paths with visited-state bookkeeping and bounded work |
| Pike VM NFA | Execute prioritized threads and captures for general supported patterns |

The lazy DFA builds and caches only encountered states. Character equivalence
classes group code points with identical transition behavior. Assertion context,
including word and line boundaries, must be represented in cache identity.
Scoped line modes and CRLF context cannot be merged into a single global flag.
DFA budgets bound cache growth; exhaustion leads to bounded cache management or
fallback rather than a change in match results.

The NFA processes prioritized threads in lockstep and carries capture registers.
BitState uses an explicit stack and visited-state information to avoid
unrestricted backtracking. Work limits and semantic eligibility checks keep
these paths within the matching contract. OnePass is available only when its
path and capture choices agree with the public semantics.

An eligible search can combine a forward DFA pass, reverse range narrowing,
and an anchored pass to establish match bounds. Capture extraction can be
postponed until captures are observed. Each pass has a separate authority:
a filter cannot publish exact bounds, and bounds do not imply resolved captures.
If an optimization cannot establish the required semantics, dispatch uses an
eligible exact path. See [engine invariants](INVARIANTS.md#engines-agree-on-public-observations).

Use [diagnostics](DIAGNOSTICS.md) to observe actual execution. Static pattern
capabilities alone do not predict which path an operation will take.

## Input representations and regions

`InputScanner` provides shared code-point consumption and bounded search over
String and UTF-8 representations. String positions are UTF-16 indices; UTF-8
positions are byte offsets relative to the input view. Engine progress and
capture registers use the representation's coordinates without decoding the
whole UTF-8 subject into a String.

The public UTF-8 API has a separate matcher to make ownership, byte coordinates,
and scalar-boundary progress explicit. Captures expose bounds so callers can
slice their storage; replacement sinks consume borrowed ranges synchronously.
See [Direct UTF-8 matching](UTF8.md).

Consumption bounds, boundary visibility, anchoring bounds, and candidate starts
are separate execution context. A region substring is not a substitute for
that context. Grapheme segmentation caches bounded per-input information for
regional-indicator parity, pictographic/ZWJ sequences, and Indic linking rather
than rescanning arbitrary prefixes at each position. See
[graphemes and regions](GRAPHEME_REGIONS.md).

Storage adapters must define ownership, mutation, lifetime, validation,
forward/reverse progress, and window boundaries. Add them for a concrete
integration with correctness and performance evidence. Keep third-party buffer
and SQL-engine types out of the core public API.

## Unicode data

SafeRE bundles Unicode property and simple-fold tables with the library.
General categories, scripts, blocks, and binary-property membership use these
tables. General categories, scripts, blocks, and most binary properties are
generated using a maintainer-selected JDK's supported `Character` APIs.
Grapheme properties (`Grapheme_Cluster_Break`, `Indic_Conjunct_Break`, and
`Extended_Pictographic`) come directly from
[pinned Unicode 17.0.0 files](../safere-unicode/data/17.0.0/README.md), without
consulting private JDK classifiers. Generated data is checked into the repository,
and the generated source records its provenance.

Unicode upgrades are intentional maintenance changes: update the pinned inputs,
select a JDK with the same Unicode version for the remaining properties,
regenerate and review the output, and run focused Unicode compatibility tests.
The [Unicode generator guide](../safere-unicode/README.md) describes the workflow.

Some Unicode behavior also uses the runtime JDK: `java*` properties, Unicode
POSIX `Blank`/`Graph`/`Print`, and the Java case mappings incorporated into the
case-equivalence closure. Record the JDK when investigating these behaviors.
A difference caused by Unicode-version skew must be distinguished from a
matching-engine bug. Intentional case-fold and boundary differences are recorded
in the [compatibility policy](../INTENTIONAL_DIVERGENCES.md).

## Source map

Library sources are under [org/safere](../safere/src/main/java/org/safere/).

| Area | Starting points |
|---|---|
| Public API | `Pattern`, `Matcher`, `PatternSet`, `Utf8Input`, `Utf8Matcher`, `Utf8Sink` |
| Compilation | `Parser`, `Regexp`, `Simplifier`, `Compiler`, `Prog`, `Inst` |
| Execution | `Dfa`, `OnePass`, `BitState`, `Nfa` |
| Representation and boundaries | `InputScanner`, `EngineContext`, `GraphemeSupport` |
| Character semantics | `CharClass`, `UnicodeTables`, `UnicodeProperties`, `UnicodeCaseFolding` |
| Engine equivalence | `EnginePathContract`, `EnginePathOptions` |

SafeRE began as a Java port of RE2 and incorporates RE2/J code. It has since
evolved independently, with a focus on Java compatibility, JVM performance,
and direct UTF-8 matching. Its automata architecture retains that lineage;
Java-oriented syntax, public state, input coordinates, and captures follow
SafeRE's own compatibility contract.
