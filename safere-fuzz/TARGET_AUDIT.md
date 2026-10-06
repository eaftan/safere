# Target and exclusion audit

This audit accompanies [the enrollment policy](TARGETS.md). Exact class names,
JUnit fuzz methods, seed directories (or absence), and enrollment are in
[targets.json](targets.json); `scripts/targets.py` validates these against source.
Each method is independently selectable as `Class#method`. A class selection
expands to every listed method. Existing seeds and byte-consumption order remain
with the original targets. New identities have independent input decoding.

## Existing assertion families

All entries below remain exploratory. Unless stated otherwise, their JDK
comparisons use `FuzzSupport` and inherit its limitations listed below.

| Target | Generated domain and assertions | Extraction disposition |
| --- | --- | --- |
| CompileFuzzer | Arbitrary pattern text up to 2048 characters and sampled flags; compilation acceptance. | Unsupported syntax and arbitrary flags prevent strict enrollment. |
| ParserCompatibilityFuzzer | Atoms, prefixes, connectors, suffixes, leading counted repeats, comment terminators and malformed groups; parser flags; acceptance and fixed membership subjects. | Grammar includes intentional syntax differences. |
| CharacterClassExpressionFuzzer | Fixed regressions and composed base/ampersand/trailing/right pieces, intersections and comment separators; acceptance and membership. | Trivia selection avoids invalid separators; intentional class rules need model coverage, not JDK enrollment. |
| EscapeSyntaxFuzzer | Fixed regressions and composed prefix/escape/suffix families; acceptance and membership with parser flags. | Keep broad pending a separate escape-domain proposal. |
| DialectSyntaxFuzzer | Dialect fragments and group/class contexts; acceptance and membership, plus explicit SafeRE Python-style named-group expectations. | Mixed model and differential checks; extension assertions are not JDK comparisons. |
| ParserStackSafetyFuzzer | Fixed and sampled nesting through depth 512, classes/groups/captures/count repeats, nested quantifiers and gaps; compilation, matches and captures on small subjects. | Robustness extracts SafeRE-only bounded nesting; existing richer JDK domain remains broad. |
| CaptureSemanticsFuzzer | Empty, nullable, named and boundary captures; chained quantifiers; bounded subjects/find sequences; group text/bounds/count, snapshots, numeric/named/function replacements and append APIs. | Failed-path capture and dependent replacement differences remain visible findings. |
| MatchFuzzer | Arbitrary patterns/flags/subjects; matches, lookingAt, find and find(start). Focused families cover accelerated restart growth, Unicode boundary caches, leading classes, factored/scoped case flags, line terminators/start anchors, zero-width priority/capture retention, sandwich searches, Unicode casefolding and UTF-8 literals, multi-anchor gaps, and priority selection. Explicit casefold regression/model tables also run. | Shared CaseFamilyChecks extracted; finite PriorityCases graduated separately. Unicode, anchor, flag and capture interactions remain exploratory. |
| FindSequenceFuzzer | Arbitrary and focused delimiter/capture/anchor/CRLF/supplementary patterns; warmup reuse, repeated finds and bounded sequences of match/find/reset/new input/group/region/bounds/snapshot state operations. | Stateful and zero-width continuation expectations require their own domain review. |
| ReplacementFuzzer | Arbitrary patterns/flags/input/replacement or Unicode casefold suffix families; replaceAll/First, matcher state, appendReplacement/Tail with builders and buffers. | Invalid replacement handling and capture semantics keep this broad. |
| SplitFuzzer | `split`: arbitrary patterns/flags, inputs, signed and very large limits plus CRLF/grapheme regressions. `unassignedControlSplits`: default-ignorable grapheme controls. `repeatedClassSplits`: repeated classes over binary/ASCII/NUL/supplementary alphabets, optional captures, repeated delimiters. Both check split and splitWithDelimiters variants. | All methods now discoverable through the runner; no automatic promotion from focused generation. |
| RegionBoundsFuzzer | Arbitrary regions and bounds plus fixed grapheme/surrogate/CRLF matrices; getters, find, matches and lookingAt. Explicit SafeRE grapheme model cases coexist. | Region model and JDK observations are deliberately not enrolled together. |
| DeferredRegionCaptureFuzzer | Package-private forced deferred execution, disabled one-pass/bit-state paths; anchored/boundary/optional captures, BMP/supplementary subjects, reset/reuse and region/bounds combinations. Direct JDK success/capture/find assertions. | Inventoried despite its package; focused but complex, with an unbudgeted direct oracle. No graduation claimed. |
| UnicodeFuzzer | Fixed grapheme/Indic matrices, anchored long grapheme subjects, arbitrary patterns and Unicode-heavy inputs; finds/captures, matches and lookingAt. | Unicode and grapheme boundaries include intentional differences. |
| Utf8InputFuzzer | Literal/keyword/fixed-offset/grapheme/multi-anchor representation checks; direct JDK prefix-slice, boundary-region, start-anchor, final-terminator, position-dependent and multi-offset comparisons; random repeated literal and arbitrary pattern/byte windows, strict UTF-8 validation, bounds/progress. | RepresentationChecks and Utf8RobustnessChecks are shared with independent targets. Direct JDK families remain broad. Valid representation inputs exclude lossy surrogate conversion. |

## Shared exploratory comparator boundaries

- Both engines rejecting syntax ends the comparison. SafeRE-only rejection of
  lexically recognized lookaround, backreferences or possessive syntax, and
  compiler-size rejection, remains excluded. These encode unsupported features
  or workload limits, but lexical recognition can overexclude. Strict generators
  do not use this path; their valid patterns must compile in both engines.
- The old capture, dependent replacement, and parser-marked intentional class
  rejection waivers are removed. Exact reviewed outcomes are replayed separately;
  arbitrary related failures are still findings.
- Successful matches compare captures, including count, text and bounds.
  `hitEnd()` and `requireEnd()` remain outside these comparison domains. Stateful
  targets exercise selected operations, not all legal API sequences.
- Replacement operations accept paired invalid-replacement exceptions without
  requiring equal exception classes/messages. This is an observation limit,
  not evidence of full exception compatibility.
- JDK timeout/stack exhaustion makes subsequent comparisons unavailable.
  The worker uses an interruptible subject; results after loss of the oracle
  must not be counted as agreement. Split checks can return before running
  SafeRE when the JDK oracle is unavailable; the independent robustness target
  supplies SafeRE execution coverage, not split equivalence.
- Direct JDK assertions in mixed targets are not all routed through the shared
  timeout/reporting layer. Their assertion context and Jazzer raw reproducer
  remain evidence; enrollment does not rely on these direct checks.

## Exhaustive coverage and exclusions

These suites are retained unchanged. Their passes are not used as strict-domain
proof. The replacement column identifies available coverage, not equivalence of
coverage or a claim that each old exclusion is justified.

| Mechanism | Executable exclusion or observation limit; rationale | Replacement or remaining gap |
| --- | --- | --- |
| ExhaustiveUtils.Config | Finite configured atoms/operators/wrappers/alphabet, automatic anchored variants and long prefixes. Cases outside configuration are never generated. | StrictDomain explicitly composes its own syntax and subjects; no hidden anchor expansion. |
| ExhaustiveUtils.testPair | Textual NESTED_REP_CAPTURE skips whole pairs, including some noncapturing syntax, to avoid capture differences. Rejection by either engine skips the pair. | Strict enumeration includes nested noncapturing repetition with no such skip. Exact capture model replay covers reviewed differences; arbitrary capturing compositions remain broad. |
| ExhaustiveUtils comparisons | Shared group-count prefix rather than equality; selected matches/first find/find-all observations, with find-all primarily whole-match positions. | Strict comparator checks complete declared traces and group counts, though enrolled domains have no captures. |
| CrossEngineExhaustiveTest | Inherits helper skips; direct variants skip either compilation rejection. Finite class/line-ending domains omit combinations. Comments describing “both reject” and unsupported intersection are not reliable policy. | Compile fuzzing still explores acceptance. No claim that strict ASCII covers intersections, arbitrary flags or byte-backed subjects. |
| RE2ExhaustiveTest | Omits byte-oriented `\\C`, multibyte `\\B`, rejected SafeRE patterns and malformed data; uses ordinary match expectations rather than longest-match fields. JDK agreement can override a RE2 mismatch. Generated crosschecks additionally exclude failed-start capture residue. | Ordinary RE2 corpus remains. Dialect/representation limits are distinct from semantic fallback; explicit capture model replay replaces waiver evidence only for exact reviewed cases. |
| RegexSweep | Finite operation traces and find limits; no end-state observations. Both rejected/error outcomes can compare equal, including caught runtime/stack errors. | Do not reuse this comparator for strict tests. Strict SafeRE execution errors always escape and valid-pattern rejection is a finding. |
| Indexed sweeps | Index ranges, worker partitioning and selected matrices restrict enumeration; these are not semantic suppression. | Preserve index/generator context when replaying archives. No full archive migration is claimed. |
| RegionScalar and RegionZeroWidth replay | Generation records mismatches, but replay may return on KNOWN_INTENTIONAL classifications. | Successful replay does not prove equality. Regions remain exploratory; reviewed model tests retain their policy. |
| Grapheme sweep | Omits find continuation immediately after matches/lookingAt; classifies known families in summaries/replay. | Explicit observation boundary, not universal Matcher agreement. Grapheme properties compare only their declared representation families. |
| ZeroWidthQuantifier sweep | Bounded/deduplicated quantifier grammar and finds; sentinel checks SafeRE stack safety rather than equality; known capture/grapheme classifications. | Strict nested noncapture enumeration bypasses classifications. Capturing/anchor domains remain broad. |
| Class/control-escape/casefold/Unicode-class/sandwich sweeps | Explicit finite feature matrices and selected observations. | Useful focused candidates, not automatically enrolled; Unicode/class compatibility decisions remain outside initial strict scope. |
| CompactDivergenceAudit | Samples the supported zero-width archive through its current classifier, with index-size checks. | Not an independent oracle or a proof that similar failures are intentional. Exact registry outcomes are checked independently. |

## Graduation evidence

`StrictPriorityFuzzer` reuses the existing `PriorityCases` rather than duplicating
its patterns. Its complete domain is the listed pattern/subject pairs: no flags,
regions, captures or mutable matcher sequences. The strict comparator checks
compilation, matches, lookingAt and every find, including whole-match text and
bounds. Deterministic tests enumerate every pair and add boundary subjects;
bounded discovery exercises the actual Jazzer entry point. The original broad
calls retain their decoding order and remain active.

The syntax and priority expectations follow the JDK 26
[Pattern contract](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/util/regex/Pattern.html)
and the declared fresh-matcher operations follow
[Matcher](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/util/regex/Matcher.html).
This supports the finite graduation, not arbitrary compositions with captures or
flags. The general ASCII domain additionally enumerates small compositions and
nested quantifiers directly, including cases the legacy textual exclusion would
skip. Controlled comparator defects cover success, bounds, group count and
missing sequence observations. Oracle unavailability is tested separately.

## Current-main reconciliation

The refreshed inventory also includes `EmojiZwjGraphemeFuzzer` (an independent
UAX model, not automatically enrolled) and `SplitFuzzer#unassignedControlSplits`
(default-ignorable grapheme controls). Upstream #930 supplies surrogate-interior
reverse-DFA correctness; #933 supplies exhausted-state assertions. The latter's
observed-state oracle exception remains, with dedicated canaries. The #959
whole-input grapheme waiver is removed: explicit unassigned-grapheme model
coverage remains and differential mismatches are findings.

Strict subjects now include arbitrary UTF-16 boundaries and multibyte characters;
`StrictStateFuzzer` adds reviewed API sequences. `EngineEquivalenceFuzzer` compares
controlled paths and diagnoses actual participation. `WorkBoundFuzzer` requires
the work-counters build profile and is not enrolled in a normal OSS-Fuzz build.
See the revised [implementation plan](../design/FUZZING_POLICY_IMPLEMENTATION_PLAN.md)
for the precise observation and oracle-exception policy.
