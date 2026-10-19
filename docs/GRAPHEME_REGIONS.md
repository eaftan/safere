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
3. Ordinary consuming atoms decode within the consumption range. A surrogate
   pair wholly inside that range is one code point; a half exposed at either
   region edge is unpaired, even if it belongs to a pair in the full input.
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
atoms decode code points within the active range. A valid surrogate pair inside
the range is one code point. A low surrogate at the region start can be visible
as an unpaired value. A high surrogate exposed by the region end is
also unpaired, and an ordinary atom may finish immediately after it. Transparent
bounds do not extend the consuming range.

Grapheme consumption has a separate completion limit: `\X` can complete a
surrogate pair whose high surrogate starts inside the region. For region
`[0, 1)` over a supplementary character, `\X.find()` may report `[0, 2)`;
`\X.matches()` still fails the strict logical region-end check.

Opaque bounds make the region the boundary context. Transparent bounds allow
boundary predicates and grapheme segmentation to inspect outside it. Anchoring
bounds are a separate setting for line and text anchors. Candidate starts
remain inside the region in either mode.

The following decision table distinguishes the bounds used by each operation:

| Operation | Opaque bounds | Transparent bounds | At or beyond the logical region end |
|---|---|---|---|
| Ordinary consuming atom | Decode within the region | Decode within the region | Cannot consume past the end; an exposed high surrogate is unpaired |
| Word assertion | Classify the characters visible within the region | Classify using full-input context | After actual grapheme completion, opaque context ends at the completed edge |
| Grapheme assertion | Region edges are boundaries; interior properties use the complete next code point in grapheme context | Use full-input grapheme context | The opaque logical end is a boundary; a completed grapheme also has its completion edge |
| Consuming `\X` | Region-local grapheme context | Full-input grapheme context | May complete a surrogate whose high half started inside the region |
| Line/text anchor | Controlled by anchoring bounds | Controlled by anchoring bounds | Existing end-anchor completion rules apply to `\X`; ordinary atoms retain the logical end |
| `matches()` | Must cover the logical region | Must cover the logical region | Completion beyond the logical end cannot satisfy a full match |

Adding an alternative that cannot match must preserve an existing branch's
`matches()` and `lookingAt()` result, including captures. This applies to nullable
and repeated atoms as well as to individual characters. It prevents a `\X`
instruction elsewhere in the program from changing ordinary assertion context.

For `find()`, candidate-start and continuation rules are also part of the contract.
Ordinary prefix searches can skip complete code points; consuming grapheme
programs can try each UTF-16 offset, including the low half of a complete pair.
Changing the grapheme constructs can therefore change the available search
candidates without changing the meaning of an anchored branch. The generic
composition comparison of complete find traces uses inputs without complete
pairs; supplementary search behavior has separate explicit expectations.
Boundary-only grapheme programs retain scalar candidate starts, including when
start acceleration is disabled.

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

`GraphemeConformanceTest` in `safere-fuzz` compares checked-in grapheme properties
with ICU4J's public property APIs and verifies the pinned Unicode version. Its
region and cached-boundary checks use ICU4J's root-locale character `BreakIterator`
as the segmentation oracle. `EmojiZwjGraphemeFuzzer` uses the same oracle for
exhaustive short sequences and generated longer sequences over representatives of
every GCB/InCB/Extended_Pictographic property combination. Both encodings exercise
`\X`, explicit boundaries, repeated searches, composition, and captures.

The production implementation remains in
[GraphemeSupport](../safere/src/main/java/org/safere/GraphemeSupport.java), using
[ICU4J-generated property tables](../safere-unicode/README.md) without an ICU4J
runtime dependency. The boundary rules are implemented as follows:

| UAX #29 rules | Production decision |
| --- | --- |
| GB1, GB2 | Text edges in `isGraphemeClusterBoundary`; active context edges in `boundaryFlags` |
| GB3–GB5 | CRLF before `isGraphemeControl`; controls break before attachment rules |
| GB6–GB8 | `isHangulGraphemeContinuation` |
| GB9, GB9a, GB9b | `isGraphemeExtend` (Extend, ZWJ, SpacingMark) and `isGraphemePrepend` |
| GB9c | InCB properties and `Context.hasIndicConjunctLinkerBefore` |
| GB11 | Extended_Pictographic, Extend, ZWJ, and `Context.hasExtendedPictographicBefore` |
| GB12, GB13 | `Context.hasEvenRegionalIndicatorsBefore` |
| GB999 | Break when no joining rule applies |

The Hangul and attachment checks can be reordered because their applicable
property values are disjoint. The contextual rules use iterative cache construction
and constant-time queries; `GraphemeLinearTimeTest` checks work scaling.

ICU4J supplies both production property data and test segmentation, so these
comparisons do not independently validate ICU's data. The existing official
`GraphemeBreakTest.txt` fixture provides additional conformance coverage and checks
the ICU oracle. Generated sequences contain Unicode scalars; lone surrogates and
regions splitting a pair follow the String contract above and retain separate
coverage in `LinebreakGraphemeTest` and the region tests.

`RegionAssertionCompositionTest` makes the decision table executable and checks
the Cartesian product of a bounded `left atom + assertion + right atom` grammar,
curated Unicode inputs, every region in those inputs, and both bounds settings.
The inputs include sliced LF and NEL regions. CRLF inputs use whole-input
regions: compositions containing grapheme assertions at sliced CRLF edges expose
a pre-existing inconsistency tracked in [#1038](https://github.com/eaftan/safere/issues/1038),
with a focused disabled regression.
The grammar includes scalar/category atoms, optional and repeated captures,
ASCII/Unicode word assertions, grapheme assertions, text anchors, and scoped
multiline/UNIX_LINES anchors. Both positions of an unreachable grapheme
alternative must preserve anchored results and captures. Comparable find traces
include empty-match progress; separate expectations pin the different candidate
policies. `GraphemeAlternativePriorityTest` checks successful competing branches
against their standalone results, including assertion-bearing branches.

`RegionBoundsFuzzer#assertionComposition` uses the same pattern grammar with
generated Unicode/context fragments and regions. As in the matrix, CRLF inputs
use whole-input regions while other line contexts may be sliced. No individual
patterns are waived.

The matrix runs with the ordinary library tests in CI. Run it directly with:

```bash
mvn -pl safere -Dtest=RegionAssertionCompositionTest,GraphemeAlternativePriorityTest test
```

Replay region fuzz seeds with:

```bash
mvn -pl safere-fuzz -am -Dtest=RegionBoundsFuzzer -Dsurefire.failIfNoSpecifiedTests=false test
```

Run a bounded model-only fuzz campaign with:

```bash
JAZZER_FUZZ=1 mvn -pl safere-fuzz -am \
  '-Dtest=RegionBoundsFuzzer#assertionComposition' \
  -Dsurefire.failIfNoSpecifiedTests=false -Djazzer.max_duration=30s test
```

Exercise split surrogate pairs, base-plus-mark sequences, Hangul, regional
indicators, pictographic/ZWJ sequences, and Indic conjuncts. Vary region ends,
opaque/transparent bounds, full/prefix/search operations, captures, and repeated
empty matches. Test `\X` and `\b{g}` alone and in concatenations or alternatives.

Keep JDK-comparable cases separate from approved divergences and quarantined
observations of JDK internals. Check linear work with grapheme scaling tests.
Optimized engines may accept a grapheme program only when they preserve this
model; otherwise they must use an eligible fallback. See the
[testing guide](TESTING.md) and [semantic invariants](INVARIANTS.md).
