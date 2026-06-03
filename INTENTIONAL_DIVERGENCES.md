# Intentional Divergences from java.util.regex

SafeRE aims to be a drop-in replacement for `java.util.regex.Pattern` and
`java.util.regex.Matcher` where that is compatible with SafeRE's linear-time
guarantee and with a coherent specification-based regex model. Some observed
JDK behaviors are deliberately not copied.

This document records those intentional divergences and why SafeRE treats its
behavior as the compatibility target. Sweep classification names are included
where an exhaustive sweep reports the divergence.

## Compatibility Policy

SafeRE follows the documented JDK regex semantics when they can be implemented
within SafeRE's linear-time design. When the JDK specification is silent or
ambiguous, SafeRE matches observed JDK behavior only when that behavior is
coherent, compositional, and implementable without backtracking, retry loops, or
unbounded rescans. When observed JDK behavior appears to expose implementation
details rather than a stable regex rule, SafeRE keeps the simpler documented
model and records the difference here.

## Unsupported Backtracking Features

Sweep names:

- `POSSESSIVE_QUANTIFIER_UNSUPPORTED`

SafeRE rejects backreferences, lookahead, lookbehind, atomic groups, and
possessive quantifiers over consuming operands at compile time. The JDK accepts
these constructs, but they are tied to backtracking semantics or to search-order
commitments that do not fit SafeRE's worst-case linear-time guarantee. Rejecting
them is the correct SafeRE behavior because accepting them would either make the
guarantee false or require a different non-compositional engine path for a
subset of patterns.

Possessive modifiers over statically zero-width operands are not intentional
divergences. They can be normalized without adding consuming possessive
semantics, so SafeRE accepts JDK-compatible forms such as `^*+`, `$?+`, and
`()*+`.

## Canonical Equivalence

SafeRE does not support the `CANON_EQ` flag. The JDK accepts this flag and
matches canonically equivalent Unicode strings, such as precomposed characters
and decomposed base-plus-mark sequences. SafeRE does not implement that mode
because it would add substantial normalization and matching complexity to every
affected operation. The correct SafeRE behavior is to reject the flag rather
than partially emulate canonical equivalence or silently run with different
semantics.

## hitEnd and requireEnd

SafeRE does not attempt exact JDK-compatible results for `Matcher.hitEnd()` or
`Matcher.requireEnd()`. These methods expose observations about the JDK
backtracking engine's ordered search, including whether some attempted path
reached input end and whether the accepted result depended on end-sensitive
paths. SafeRE engines explore sets of states in lockstep, not one ordered
backtracking path at a time. Exact emulation would require preserving JDK
search-order state that is not part of ordinary regex match semantics and is not
compatible with SafeRE's engine model.

## Failed-Path Capture Leakage

Sweep names:

- `FAILED_PATH_CAPTURE_LEAKAGE`

Issue reference: #52.

The JDK can expose capture groups from failed backtracking paths. For example,
two source-equivalent forms such as `(a)*$` and `(?:(a))*$` can produce
different captured group state because the JDK's internal backtracking route
leaves behind different failed-path captures. SafeRE reports captures from the
accepted NFA path and does not preserve captures across failed start positions
or failed paths.

SafeRE's behavior is the correct model for this project because captures should
describe the accepted match, not incidental state from abandoned paths. Copying
the JDK trace exactly would require replaying or preserving backtracking
history that SafeRE deliberately does not have.

## ASCII Word Boundaries around Combining Marks

Sweep names:

- `ASCII_WORD_BOUNDARY_COMBINING_MARK`

Issue reference: #345.

Without `UNICODE_CHARACTER_CLASS`, Java regex documentation defines word
characters for `\w` as the ASCII set `[A-Za-z0-9_]`, and `\b`/`\B` are word
boundary predicates derived from word-character transitions. SafeRE applies that
model directly. In a string such as `e\u0301`, the combining acute accent is not
an ASCII word character, so SafeRE reports a word boundary between the base
letter and the combining mark.

Observed JDK behavior attaches a non-spacing mark to the preceding base
character for default ASCII word-boundary checks, even though that attachment
rule is not part of the documented default ASCII word-character model. SafeRE's
behavior is correct because it follows the documented predicate rather than an
extra implementation detail that makes `\b` disagree with the default `\w`
definition.

## Unicode Case-Insensitive Range Closure

Issue reference: #452.

The current case-folding character-class sweep records unclassified divergence
labels only. Issue #452 is the project record for the intentional range-closure
family found by that sweep and by targeted probes.

SafeRE treats Unicode case-insensitive character classes as sets closed under
Unicode case folding and casing equivalence. Under
`CASE_INSENSITIVE | UNICODE_CASE`, a singleton class and an equivalent singleton
range denote the same pre-folding set, so they should have the same membership
after folding. For example, `[K]` and `[K-K]` should both match U+212A KELVIN
SIGN, and ranges containing `I` should include U+0130 LATIN CAPITAL LETTER I
WITH DOT ABOVE.

Observed JDK behavior is syntax-sensitive for some ranges: selected singleton
classes and lowercase ranges include compatibility code points, while equivalent
singleton ranges or uppercase ranges miss them, and negated ranges can include
the same code point as a consequence. SafeRE's behavior is correct because
character classes should be interpreted as sets. Two spellings that denote the
same set before Unicode case closure should not diverge after closure.

The case-folding character-class sweep and targeted tests cover this family.
Known examples include Kelvin sign, Turkish dotted I, ohm sign, and angstrom
sign range cases.

## Grapheme Cluster Composition

Sweep names:

- `BOUNDARY_CLUSTER_BOUNDARY_COMPOSITION`
- `REPEATED_GRAPHEME_BOUNDARY_COMPOSITION`

Issue reference: #451.

SafeRE treats `\X` as one Unicode extended grapheme cluster and `\b{g}` as a
boundary predicate over the same grapheme segmentation model. Therefore
composed forms such as `\b{g}\X\b{g}` should behave like a boundary-delimited
cluster: after one non-empty `find()` match, the next `find()` starts at the
first character not matched by the previous result and can match the next
cluster when one exists.

Observed JDK traces skip some valid repeated `find()` candidates for
boundary-cluster-boundary forms. SafeRE's behavior is correct because it composes
the documented meanings of `\X`, `\b{g}`, and repeated `find()`. Skipping a later
complete cluster appears to expose matcher implementation state rather than a
stable grapheme rule.

## Grapheme Boundary Alternatives and find Cursor State

Sweep names:

- `BOUNDARY_ONLY_ALTERNATIVE_FIND_CURSOR`
- `BOUNDARY_CLUSTER_ALTERNATIVE_FIND_CURSOR`
- `GRAPHEME_BOUNDARY_ALTERNATIVE_FIND_CURSOR`
- `GRAPHEME_BOUNDARY_ALTERNATIVE_GRAPHEME_MODEL`
- `GRAPHEME_BOUNDARY_CAPTURE_GRAPHEME_MODEL`

Issue reference: #451.

SafeRE treats alternatives involving `\b{g}` and `\X` using the same
leftmost-first regex semantics as other alternatives: branch order decides which
match wins at a candidate position, but it should not suppress later valid
candidates after the repeated `find()` cursor advances. A zero-width grapheme
boundary alternative at a later position remains observable when `find()` starts
there.

Observed JDK behavior sometimes skips valid boundary-only or
boundary-plus-cluster alternatives after a previous match. SafeRE's behavior is
correct because the documented `find()` cursor rule is position-based, and
ordinary zero-width constructs remain observable under the same pattern shape.
The JDK trace looks like a special interaction between its grapheme
implementation and repeated-find state, not a general regex rule.

The same underlying model difference can be visible through captures around a
zero-width `\b{g}` assertion. SafeRE keeps captures consistent with its
grapheme-boundary predicate; observed JDK traces can expose captures from
positions that SafeRE does not treat as grapheme boundaries, such as positions
inside a full surrogate-pair grapheme view.

## Region-Local Grapheme Continuation Clusters

Sweep names:

- `REGION_LOCAL_CONTINUATION_CLUSTER`

Issue reference: #451.

With opaque matcher bounds, SafeRE treats a region as the visible text for
grapheme-boundary purposes. A region that starts inside a larger full-input
grapheme cluster can therefore begin with a valid degenerate grapheme cluster,
such as a standalone ZWJ, because the boundary predicate cannot see the hidden
pre-region context.

Observed JDK behavior sometimes uses hidden pre-region context for grapheme
continuation decisions even under opaque bounds. SafeRE's behavior is correct
because opaque bounds are supposed to prevent boundary constructs from looking
outside the region. If the hidden prefix cannot be observed, the region start is
the coherent start of the grapheme boundary context.

## Transparent Grapheme Boundary Details

Sweep names:

- `TRANSPARENT_BOUNDARY_JDK_DETAIL`

Issue reference: #451.

With transparent bounds, SafeRE lets grapheme boundary predicates inspect the
surrounding full-input context and then applies the Unicode grapheme boundary
rules consistently. For example, a transparent region boundary between CR and
LF is not a grapheme boundary because Unicode grapheme rule GB3 keeps CR and LF
together.

Observed JDK behavior reports some transparent region-start grapheme boundaries
even when the surrounding context should prevent a boundary. SafeRE's behavior
is correct because transparent bounds should make the surrounding context
visible, and once that context is visible the Unicode grapheme rules should be
applied normally.

## Quantified Scalar Atoms at Split Surrogate Region Ends

Sweep names:

- `QUANTIFIED_SPLIT_SURROGATE_SCALAR_COMPOSITION`

When a matcher region ends immediately after the high surrogate of a valid
surrogate pair, SafeRE treats ordinary scalar-consuming atoms compositionally:
if the unquantified atom cannot consume a Unicode scalar at that boundary, then
greedy quantified forms such as `.*`, `.+`, `[\\s\\S]*`, and `[\\s\\S]+` do not
consume that split high surrogate either.

Observed JDK behavior is non-compositional for this edge. Selected unquantified
scalar atoms reject the split high surrogate, while greedy quantified wrappers
can still consume it as a one-code-unit match. SafeRE keeps the compositional
model because it is a coherent scalar-consumption rule and avoids quantifier-
specific special cases that expose java.util.regex implementation mechanics.

## Opaque Region CRLF Pair Context

Sweep names:

- `OPAQUE_REGION_CRLF_PAIR_CONTEXT`

With opaque matcher bounds, SafeRE treats end-anchor context as region-local.
For example, a region over only the LF half of `"\r\n"` is treated as containing
a standalone visible LF for `$` and `\Z` purposes; the hidden CR before the
region is not consulted.

Observed JDK behavior exposes the hidden pre-region CR for selected `$`, `\Z`,
and anchored-empty traces, treating the visible LF as the second half of an
atomic CRLF line terminator even though opaque bounds hide that CR. SafeRE keeps
the opaque-region model because boundary constructs should not depend on
pre-region text when opaque bounds are active.

## Sweep Classification Map

| Sweep class | Status | Documentation section |
|---|---|---|
| `POSSESSIVE_QUANTIFIER_UNSUPPORTED` | Intentional | Unsupported Backtracking Features |
| `FAILED_PATH_CAPTURE_LEAKAGE` | Intentional | Failed-Path Capture Leakage |
| `ASCII_WORD_BOUNDARY_COMBINING_MARK` | Intentional | ASCII Word Boundaries around Combining Marks |
| `BOUNDARY_CLUSTER_BOUNDARY_COMPOSITION` | Intentional | Grapheme Cluster Composition |
| `REPEATED_GRAPHEME_BOUNDARY_COMPOSITION` | Intentional | Grapheme Cluster Composition |
| `BOUNDARY_ONLY_ALTERNATIVE_FIND_CURSOR` | Intentional | Grapheme Boundary Alternatives and find Cursor State |
| `BOUNDARY_CLUSTER_ALTERNATIVE_FIND_CURSOR` | Intentional | Grapheme Boundary Alternatives and find Cursor State |
| `GRAPHEME_BOUNDARY_ALTERNATIVE_FIND_CURSOR` | Intentional | Grapheme Boundary Alternatives and find Cursor State |
| `GRAPHEME_BOUNDARY_ALTERNATIVE_GRAPHEME_MODEL` | Intentional | Grapheme Boundary Alternatives and find Cursor State |
| `GRAPHEME_BOUNDARY_CAPTURE_GRAPHEME_MODEL` | Intentional | Grapheme Boundary Alternatives and find Cursor State |
| `REGION_LOCAL_CONTINUATION_CLUSTER` | Intentional | Region-Local Grapheme Continuation Clusters |
| `TRANSPARENT_BOUNDARY_JDK_DETAIL` | Intentional | Transparent Grapheme Boundary Details |
| `QUANTIFIED_SPLIT_SURROGATE_SCALAR_COMPOSITION` | Intentional | Quantified Scalar Atoms at Split Surrogate Region Ends |
| `OPAQUE_REGION_CRLF_PAIR_CONTEXT` | Intentional | Opaque Region CRLF Pair Context |
