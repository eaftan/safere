# Unicode 17 extended grapheme audit

This assessment addresses [issue #954](https://github.com/eaftan/safere/issues/954).
The segmentation oracle is Unicode 17.0.0,
[UAX #29 revision 47, section 3.1.1](https://www.unicode.org/reports/tr29/tr29-47.html#Grapheme_Cluster_Boundary_Rules).
Word and sentence segmentation are outside scope. The
[JDK 26 Pattern specification](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/util/regex/Pattern.html#unicode)
specifies extended grapheme clusters for `\X` and `\b{g}`. Observed JDK results
supplement the Unicode oracle; they do not override it. API context follows
[graphemes and regions](GRAPHEME_REGIONS.md).

## Rule coverage

Production segmentation is in
[GraphemeSupport](../safere/src/main/java/org/safere/GraphemeSupport.java).
Both scalar encodings use the same generated property tables and cached state.
The reference interpreter in
[GraphemeReference](../safere-fuzz/src/test/java/org/safere/fuzz/GraphemeReference.java)
uses official UCD data and literal, ordered boundary rules, with backward scans
instead of production caches. It does not call SafeRE classification or JDK regex.

| Rule | Production classification and decision | Independent coverage |
| --- | --- | --- |
| GB1, GB2 | Text edges in `isGraphemeClusterBoundary`; active context edges in `boundaryFlags` | Empty/nonempty input, complete boundary find traces, clipped regions; empty regex input has a boundary at zero but no `\X` cluster |
| GB3 | CR followed by LF before control handling | CR/LF pairs, surrounding controls, scalar/boundary composition |
| GB4, GB5 | `isGraphemeControl`; the Control table includes CR and LF | Controls before Extend, SpacingMark, ZWJ, and after Prepend; every property tuple in short sequences |
| GB6 | `isHangulGraphemeContinuation`: L before L/V/LV/LVT | Every Hangul GCB representative and interrupted runs |
| GB7 | LV/V before V/T | Same exhaustive matrix and generated longer sequences |
| GB8 | LVT/T before T | Same matrix, including intervening attachment properties |
| GB9 | `isGraphemeExtend`: GCB Extend or ZWJ | Extend with InCB Extend, Linker, or None; ZWJ with InCB Extend |
| GB9a | `isGraphemeExtend`: GCB SpacingMark | SpacingMark attaches but interrupts GB9c and GB11 prefixes |
| GB9b | `isGraphemePrepend` | Repeated Prepend; Prepend before Hangul, Indic, emoji, RI, and controls |
| GB9c | InCB Consonant/Linker/Extend; `Context.ensureIndicConjunctData` and `hasIndicConjunctLinkerBefore` | Consonant–Linker–Consonant, intervening Extend/ZWJ, renewed consonants, ZWNJ and SpacingMark interruptions, clipped starts |
| GB11 | Extended_Pictographic and GCB Extend; `ensureExtendedPictographicData` queried before ZWJ | Prepend prefixes, interruptions, renewed chains, supplementary and BMP pictographs |
| GB12, GB13 | RI scalar range; `ensureRegionalIndicatorRunData` and clipped prefix-count parity | Even/odd runs, Prepend, interrupted runs, and every region start in curated inputs |
| GB999 | Final break when no earlier rule applies | Every property tuple pair in the short-sequence matrix |

The production Hangul check follows attachment checks. This reordering is
semantically equivalent: the Hangul right-hand properties are disjoint from
Extend, ZWJ, and SpacingMark, and its left-hand properties exclude Prepend.
Control handling still precedes all attachment rules.

## Property provenance

The production generator uses ICU4J's supported public Unicode property APIs,
with the recorded version `UnicodeGeneratedTables.UNICODE_DATA_VERSION`.
GCB, InCB, and Extended_Pictographic all come from that same ICU Unicode
version, independently of the generator JDK's other character properties.
See the [generator guide](../safere-unicode/README.md).

The audit independently extracts these properties from the official Unicode 17
[GraphemeBreakProperty.txt](https://www.unicode.org/Public/17.0.0/ucd/auxiliary/GraphemeBreakProperty.txt),
[DerivedCoreProperties.txt](https://www.unicode.org/Public/17.0.0/ucd/DerivedCoreProperties.txt), and
[emoji-data.txt](https://www.unicode.org/Public/17.0.0/ucd/emoji/emoji-data.txt).
The compact checked-in fixture covers the entire code point range, including
default Other/None values and unassigned pictographic reservations. Source
SHA-256 hashes are pinned in the extraction script and fixture. The Unicode
license accompanies the fixture.

`GraphemeUnicodeAuditTest` compares every code point of every generated grapheme
table and Extended_Pictographic against this independent fixture, and verifies
the dedicated ZWJ/RI scalar predicates. The reference interpreter is separately
calibrated against the existing official `GraphemeBreakTest.txt` fixture.
Thus a shared ICU classification error cannot silently validate itself through
both the production segmenter and the reference interpreter.

## Findings and compatibility boundaries

The audit found a stale explicit-boundary fallback that inferred a break from
consumed input and a nonzero search start, even when Unicode forbids it.
For example, repeated `find()` with `(?s).\b{g}` on `xy\r\n` returned the CR
as a separate match. The same fallback split base-plus-Extend, base-plus-SpacingMark,
and Prepend clusters. Removing it makes explicit assertions use the actual
boundary decision. The scalar/boundary find regression agrees with both Unicode and the JDK.
Other compositions expose the JDK exception itself: `\r\b{g}\n` in region
`[1, 3)` of `"#\r\n$"`, and a transparent trailing boundary before a hidden
extender. SafeRE now rejects those compositions under its existing approved
Unicode boundary-predicate policy; the examples are recorded in
[intentional divergences](../INTENTIONAL_DIVERGENCES.md#grapheme-cluster-composition). The removed helper also duplicated an existing
split-surrogate branch.

The full property comparison and scalar reference checks found no additional
data or segmentation-rule discrepancies in the tested domain. Existing JDK
exceptions are classified as follows:

- Unassigned Other code points, Ahom vowel signs, and GB11 interruptions/Prepend
  prefixes follow the pinned Unicode rules. The approved specification-based
  differences remain documented in [intentional divergences](../INTENTIONAL_DIVERGENCES.md).
- Opaque contexts clip boundary visibility; transparent contexts expose the
  full input. Anchoring bounds affect anchors independently. Region boundaries
  are API context, not additional UAX rules.
- `\X` may begin inside a full-input cluster during unanchored search. Its
  atom-local start clips the contextual rules; an explicit `\b{g}` still uses
  the active boundary context. Existing suffix-composition regressions remain
  in `GraphemeRegionModelTest`.
- Lone UTF-16 surrogates and regions splitting a pair are outside the Unicode
  scalar reference alphabet. SafeRE preserves its existing String contract:
  lone surrogates break from following extenders, opaque split starts can expose
  a low surrogate, and `\X` can complete a pair split by a region end.
  `LinebreakGraphemeTest`, `GraphemeRegionCompatibilityMatrixTest`, and
  `GraphemeRegionModelTest` pin these cases separately. UTF-8 uses valid scalar
  input and byte coordinates, without inventing surrogate equivalents.

The remaining `boundaryFlags` compatibility branches were assessed separately
from scalar segmentation:

| Branch/helper family | Domain and disposition |
| --- | --- |
| `isGraphemeBoundaryContextEdge` | Active API context edges; retained for opaque regions and full-input transparent context |
| `isRegionStartSplitSurrogateBoundary`, `isRegionEndSplitSurrogateBoundary` | Split UTF-16 pairs; retained under the documented completion/context contract |
| `isAfterRegionStartSplitLowSurrogateBoundary`, `suppressesConsumedSplitLowSurrogateExplicitBoundary` | Region-local low surrogate followed by an extender or RI; bounded String compatibility, checked by existing split-region traces |
| `isLowHighSurrogateBoundary`, `startsAtLowSurrogate`, `suppressesRegionStartSplitExplicitBoundary` | Candidate starts inside UTF-16 pairs; retained separately from the scalar reference oracle |
| `isLowSurrogateBeforeZwjBoundary`, `isStandaloneZwjAfterLowSurrogateBoundary`, `isAfterRegionStartSplitLowSurrogate` | Local surrogate/ZWJ adjacency; retained in the same String-only contract |
| `isStandaloneZwjBeforePictographicBoundary`, `isAfterPairedExtendedPictographicZwj` | Distinguish a standalone ZWJ from a cached GB11 prefix; the scalar reference matrix checks both BMP and supplementary pictographs |
| `startsAtStandaloneZwj` | Atom-local suffix visibility; retained with the established suffix-composition tests and transparent-region reference checks |
| Removed `isExplicitGraphemeClusterBoundary` | Invented scalar boundaries after consumed prefixes; no Unicode rule or API visibility rule justified this override |

These branches use fixed local checks and cached context queries. Surrogate
branches are not evidence of Unicode scalar conformance and are not silently
included in the reference alphabet.

## Reproducible checks and limits

Run from the repository root:

```bash
# Independent property verification, reference calibration, region/engine checks,
# exhaustive short sequences, deterministic longer sequences, and saved fuzz seeds.
mvn -pl safere-fuzz -am \
  -Dtest=GraphemeUnicodeAuditTest,EmojiZwjGraphemeFuzzer \
  -Dsurefire.failIfNoSpecifiedTests=false test

# Public API regressions, Unicode conformance, and established surrogate/context coverage.
mvn -pl safere -Dtest='*Grapheme*Test' test

# Instrumented scaling, including mixed rules and changing cache visibility thresholds.
mvn -pl safere -Pwork-counters -Dtest=GraphemeLinearTimeTest test
```

To regenerate the independent property fixture, download the three linked UCD
files into one directory and run:

```bash
python3 safere-fuzz/scripts/generate-grapheme-reference.py /path/to/ucd-files
```

`EmojiZwjGraphemeFuzzer` exhaustively enumerates sequences through length four
over one scalar representative of every distinct GCB/InCB/Extended_Pictographic
tuple, including overlaps. Longer sequences use a fixed random seed and the
same expanded property alphabet, including additional supplementary examples.
The Jazzer entry point generates variable-length sequences over that alphabet.
Checks exercise repeated `\X` finds, captures, leading/trailing `\b{g}`,
scalar/boundary composition, quantified and consecutive cluster captures,
standalone boundary traces, and splitting in both
representations. Region checks enumerate code-point-aligned intervals in inputs
covering each contextual rule, String transparent/anchoring bounds settings,
captures, and disabled engine shortcuts. UTF-8 checks use its public opaque-region
API; `Utf8Matcher` does not expose transparent or anchoring bounds setters.

Grapheme programs are guarded away from DFA, OnePass, and BitState execution;
engine-selection checks verify equivalent results with shortcuts enabled and
disabled, and diagnostics confirm that the exact NFA actually runs. They do not claim independent segmentation implementations in those
engines. Scalar property equivalence and bounded sequence enumeration are strong
checks of rule interaction, not a proof for arbitrary inputs or arbitrary regex
syntax. No timing-based or JDK-majority oracle is used.

## Work and stack assessment

The lazy caches make a bounded number of forward passes over the input. RI
stores run starts and prefix counts; GB11 stores the active pictograph start;
GB9c stores the active consonant sequence start and whether a linker has been
seen. Each query compares a cached position or count with the visible start in
constant time. Changing the active region or atom start does not replay the
prefix. Cache storage is linear in UTF-16 units or UTF-8 bytes.

Segmentation and cache construction use iterative loops. The NFA's grapheme
visit key has a fixed set of context bits (surrogate visibility, pictograph
visibility, RI parity, and Indic sequence/linker visibility), rather than an
unbounded set of historical start offsets. This bounds contextual state per
instruction and position. The fallback removal adds no scans or matching
passes. `GraphemeLinearTimeTest` checks work scaling for long runs, failed
suffix searches, mixed rule interactions, and repeated cache queries with
changing visible starts. These checks support the inspected linear formulation;
the deliberately scanning reference interpreter is test-only.
