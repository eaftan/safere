# Graphemes and regions

This guide defines the String matching model for `\X`, `\b{g}`, regions, and
Unicode coordinates. It complements the [intentional divergences](../INTENTIONAL_DIVERGENCES.md),
which record approved differences from observed JDK behavior. UTF-8 matching
uses the byte-coordinate contract in [Direct UTF-8 matching](UTF8.md).

`\X` consumes an extended grapheme cluster. `\b{g}` is a zero-width predicate
on a boundary. Their composition must have a consistent meaning independent
of the execution strategy used to evaluate it.

## Supported contract

For String matching, the supported contract is:

1. Public positions are UTF-16 indices.
2. Candidate starts for `find()` remain inside the matcher region.
3. Ordinary consuming atoms never report bounds that end inside a valid
   surrogate pair.
4. A consuming `\X` may complete a surrogate pair whose high surrogate starts
   inside the region.
5. `matches()` remains strict about the logical region end, even when `\X`
   completion lets `find()` or `lookingAt()` report a bound past `regionEnd`.
6. End anchors in `find()` and `lookingAt()` may accept the `\X` scalar
   completion end when the logical region end splits that scalar.
7. Opaque bounds make region edges grapheme-boundary context edges.
8. Transparent bounds let grapheme boundary predicates and `\X` segmentation
   see context outside the region, while still keeping candidate starts inside
   the region.
9. Regional-indicator parity, extended-pictographic/ZWJ context, and
   Indic-conjunct linker state are computed relative to the active grapheme
   context, not by rescanning from each candidate start.
10. Opaque region-local grapheme behavior is compositional.  If an explicit
    `\b{g}` predicate is true, `\X` can consume at that position, and a trailing
    explicit `\b{g}` predicate is true, the concatenated regex can match even
    when observed JDK repeated-`find()` behavior skips that composition.
11. Unanchored `\X` chains may start at suffix positions inside a larger
    full-text grapheme cluster.  The atom-local grapheme view decides what is
    visible from that candidate start using bounded cached state.

The focused active tests for this contract are
`GraphemeRegionCompatibilityMatrixTest` for JDK-aligned cases and
`GraphemeRegionModelTest` for intentional SafeRE/JDK divergences.  The offline
exhaustive sweep remains a discovery tool, and
`GraphemeJdkImplementationDetailTest` quarantines exact JDK traces that are not
currently SafeRE compatibility requirements.

## Consumption and boundary visibility

The engine separates the consumption range from the boundary context. Ordinary
atoms consume Unicode scalars visible in the active range. A valid surrogate
pair inside the range is one scalar. A low surrogate at the region start can
be visible as an unpaired value. An ordinary consuming atom cannot finish
between a valid pair split by the region end.

Grapheme consumption has a separate completion limit: `\X` can complete a
surrogate pair whose high surrogate starts inside the region. For region
`[0, 1]` over a supplementary character, `\X.find()` may report `[0, 2]`;
`\X.matches()` still fails the strict logical region-end check.

Opaque bounds make the region the boundary context. Transparent bounds allow
boundary predicates and grapheme segmentation to inspect outside it. Anchoring
bounds are a separate setting for line and text anchors. Candidate starts
remain inside the region in either mode.

## Progress and context

After a nonempty String match, `find()` starts from the prior end. After an
empty match, it advances by one UTF-16 code unit, subject to the region end.
This can expose a candidate start inside a full-input scalar or grapheme
cluster. The engine must determine what the region-local view can consume
without re-running another matcher to validate that start.

Grapheme context includes regional-indicator parity, extended-pictographic/ZWJ
state, and Indic-conjunct linker state. Opaque context starts at the region;
transparent context may include the full input. Segmentation must account for
that distinction when it caches state.

Compute boundaries in a linear pass or cache enough state that each input
position is classified a bounded number of times. Never scan an unbounded
prefix for each boundary query. The consuming grapheme instruction and explicit
boundary assertion must use the same context model without treating one as a
synthetic expansion of the other.

## Verification

Exercise split surrogate pairs, base-plus-mark sequences, Hangul, regional
indicators, pictographic/ZWJ sequences, and Indic conjuncts. Vary region ends,
opaque/transparent bounds, full/prefix/search operations, captures, and repeated
empty matches. Test `\X` and `\b{g}` alone and in concatenations or alternatives.

Keep JDK-comparable cases separate from approved divergences and quarantined
observations of JDK internals. Check linear work with grapheme scaling tests.
Optimized engines may accept a grapheme program only when they preserve this
model; otherwise they must use an eligible fallback. See the
[testing guide](TESTING.md) and [semantic invariants](INVARIANTS.md).
