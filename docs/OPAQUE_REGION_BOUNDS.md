# Opaque String region bounds

This is the contract for supported String patterns with
`useTransparentBounds(false)`. See [graphemes and regions](GRAPHEME_REGIONS.md)
for the implementation model and verification coverage.

Let the original input be `T`, the active region be `[s, e)`, and the independent
region text be `R = T.substring(s, e)`. Positions are UTF-16 indices.

## Rules shared by both anchoring modes

1. Character decoding and classification use only `R`. Complete surrogate pairs
   within `R` are single code points. A surrogate half exposed at either region
   edge is unpaired, regardless of characters outside the region.
2. Word and grapheme boundary assertions use only `R`. Grapheme segmentation,
   including contextual rules for regional indicators and emoji ZWJ sequences,
   cannot depend on text outside the region. The region edges act as text edges
   for these assertions.
3. Consumption remains within `[s, e)`. This includes `\X`: it matches using the
   region's segmentation and cannot complete a surrogate pair or cluster by
   consuming text outside the region.
4. `lookingAt()` starts at `s`; `matches()` must cover exactly `[s, e)`; `find()`
   searches within the region. Successful match and participating capture bounds
   lie between `s` and `e`, inclusive. Unmatched capture bounds remain `-1`.

These rules apply while the region remains active. They do not change operations
that reset the region or replacement operations over the original input.
Lookaround remains outside SafeRE's supported dialect.

## Anchoring enabled: substring equivalence

With `useAnchoringBounds(true)`, matching must behave as though the same pattern
and flags were applied to `R` as the entire input. This includes success or
failure, matched text, captures, alternative priority, and successive `find()`
results, including progress after empty matches.

Compare corresponding matcher histories. Translate every participating match or
capture position from the substring by adding `s`; retain `-1` for an unmatched
group.

The anchors `^`, `$`, `\A`, `\Z`, and `\z` use `R` as their input, with the
normal effects of pattern flags and line-terminator rules. Consequently, changes
strictly outside the region cannot affect matching under these settings.

## Anchoring disabled: original-input anchors

With `useAnchoringBounds(false)`, the shared rules still apply. The exception to
substring equivalence is the interpretation of `^`, `$`, `\A`, `\Z`, and `\z`:
evaluate these anchors at the corresponding position in the original input `T`.

`\A` and `\z` refer to the original input's beginning and end. `^`, `$`, and
`\Z` use the original input's line and end-of-input context, with their normal
flags and final-terminator rules. Anchor evaluation may therefore consult
surrounding text, including CRLF context across a region edge. The region edge
does not create an additional anchor position merely by being a region edge.

This permission applies only to those anchors. It does not expose surrounding
text to character decoding, consumption, word or grapheme assertions. Matching
operations retain their region limits.

## Examples

These are required outcomes under this contract, not a table of current JDK
behavior. Each case starts with a fresh matcher and uses `matches()` unless
stated otherwise.

| Input and region | Pattern | Anchoring enabled | Anchoring disabled |
|---|---|---|---|
| `"xcaty"`, `[1,4)` | `cat` | Match | Match |
| `"xcaty"`, `[1,4)` | `\bcat\b` | Match | Match |
| `"xcaty"`, `[1,4)` | `^cat$` | Match | No match |
| `"xcaty"`, `[1,4)` | `\Acat\z` | Match | No match |
| `"x\ncat\ny"`, `[2,5)` | `(?m)^cat$` | Match | Match |
| `"\uD83D\uDE00"`, `[0,1)` | `.` or `\p{Cs}` or `\X` | Match `[0,1)` | Match `[0,1)` |

For `"\r\n"` with region `[1,2)`, `(?m)^` with `lookingAt()` succeeds when
anchoring is enabled and fails when it is disabled: the original position lies
inside CRLF. An opaque `\b{g}` assertion at that region start succeeds in both
modes because its visible text begins with the LF.

## Specification basis

The JDK 26 documentation separates
[opaque assertion context](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/util/regex/Matcher.html#useTransparentBounds(boolean))
from [anchoring bounds](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/util/regex/Matcher.html#useAnchoringBounds(boolean)).
It does not explicitly guarantee substring equivalence or define surrogate
decoding at region edges. This document records SafeRE's chosen interpretation.
[JDK-8394894](https://bugs.openjdk.org/browse/JDK-8394894) tracks the reported
inconsistencies; the
[upstream response](https://mail.openjdk.org/archives/list/core-libs-dev@openjdk.org/thread/3QJPR27AJJXTW62W5PLL3NPRZE4XUZXN/)
identifies grapheme consumption beyond the region as a bug but does not specify
every remaining edge case. See also [#867](https://github.com/eaftan/safere/issues/867)
and the [intentional divergences](../INTENTIONAL_DIVERGENCES.md).
