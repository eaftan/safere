# Fuzz target domains and enrollment

[targets.json](targets.json) is the authoritative inventory. Every source target
must be listed; adding a class does not enroll it in OSS-Fuzz. The validator
rejects broad differential enrollment, missing entry points, invalid seed paths,
and duplicate names. The local runner defaults to the enrolled selection. The
[assertion and exclusion audit](TARGET_AUDIT.md) records method coverage, shared
oracle limitations and exhaustive-suite exclusions.

Regression replay is a mode for every bucket. Broad differential discovery is
explicitly selected and can report intentional JDK differences. Classification
never makes a new input exempt from comparison.

## Target domains

| Target | Bucket | Domain and observations | Limits |
| --- | --- | --- | --- |
| `StrictAsciiFuzzer` | Strict differential | Empty expressions, literals `a`, `b`, `ab`, dot, escaped dot, `[ab]`, `[^a]`, `[a-c]`; concatenation, alternation, noncapturing grouping, greedy/lazy optional/star/plus and bounded repeats. Compare compilation, `matches()`, `lookingAt()`, and the entire `find()` sequence, including whole-match text and bounds. | No flags, captures, assertions, regions, replacements, or end-state observations. Expression depth at most 3; independent subjects at most 24 pieces, including BMP, supplementary, lone-surrogate and line-terminator pieces. ASCII describes the pattern syntax, not the input alphabet. Generated witnesses and their `!` suffix variants also exercise successful and near-miss paths. |
| `StrictPriorityFuzzer` | Strict differential | The existing finite `PriorityCases` pattern/subject pairs; compilation, matches, lookingAt and complete find traces. | No flags, captures, regions or assertions; complete finite enumeration and independent comparator validation. |
| `StrictStateFuzzer` | Strict differential | Generated noncapturing grammar with Unicode/UTF-16 subjects; find(start), selected region/bounds sequences, reset, replacement/state and usePattern/state checks. | Region ends do not split a surrogate pair (documented scalar-quantifier divergence); starts may. Observed oracle defects have canaries; unaffected observations still compare. |
| `EngineEquivalenceFuzzer` | Property | Small regex families over BMP, supplementary and lone-surrogate subjects; default and controlled DFA/OnePass/BitState/NFA traces, including interior UTF-16 starts; valid-scalar UTF-8 paths. | Diagnostics prove distinct engines ran. Shared parser/compiler bugs remain a blind spot; UTF-8 cannot represent lone surrogates. |
| `WorkBoundFuzzer` (local/CI instrumented) | Robustness | Forced NFA full-match work bounded by compiled program size and input size. | Requires work-counters build; not enrolled in the ordinary hosted build. Not a whole-library complexity proof. |
| `RepresentationPropertyFuzzer` | Property | Existing valid-Unicode literal, keyword, fixed-offset, grapheme, and multi-anchor families compare String and UTF-8 searches, including applicable captures and byte/UTF-16 offsets. | Only the named families extracted from `Utf8InputFuzzer`; not arbitrary representation equivalence. Shared implementation bugs can affect both results. |
| `CaseFamilyModelFuzzer` | Model | Existing case-family members across literals, singleton classes/ranges, and negations under Unicode case-insensitive flags. | Explicit SafeRE families, including documented JDK differences; not a JDK vote or a proof for all Unicode. |
| `RobustnessFuzzer` | Robustness | Arbitrary pattern text and flags, bounded nested noncapturing groups, String matching and repeated finds, trusted UTF-8 windows, strict UTF-8 validation and valid-window matching. | Expected syntax rejection is accepted. Pattern/input sizes are bounded. No JDK regex comparison. Progress checks do not establish asymptotic complexity. |

The strict comparator runs SafeRE first and never suppresses SafeRE failures.
It uses a synchronous JDK `CharSequence` with a shared text-access budget, so
pathological backtracking can abort without leaving worker threads behind. JDK
budget exhaustion or stack overflow is recorded as unavailable, not agreement.
Counters are reported periodically and after local JUnit runs; a shutdown hook
also reports them when the runtime performs orderly shutdown. Completed JDK
observations are compared immediately, even if a later operation exhausts its
budget. The budget
bounds text accesses rather than wall time; bounded syntax and outer fuzz-run
resource limits still matter. Compile rejection by either engine is a finding.

The generator constructs syntax directly, rather than recognizing safe regexes
with a scanner. Its composition functions are also exercised by deterministic
small-domain enumeration. Captures are compared without waivers by the comparator
when harness tests deliberately pass capturing patterns, but capturing patterns
are not part of this enrolled generator's domain.

## Existing targets retained for exploration

These targets keep their input decoding and saved seeds. Mixed checks remain
callable there to preserve seed meaning; the extracted property entry points
reuse the same assertions without invoking the broad differential paths.

| Target | Generated surface / oracle | Disposition |
| --- | --- | --- |
| `CompileFuzzer` | Arbitrary syntax and flags; JDK compile acceptance | Broad |
| `ParserCompatibilityFuzzer` | Grammar-biased syntax and membership | Broad |
| `CharacterClassExpressionFuzzer` | Class/intersection grammar and membership | Broad |
| `EscapeSyntaxFuzzer` | Escape syntax and membership | Broad |
| `DialectSyntaxFuzzer` | Dialect boundaries, plus explicit SafeRE extension checks | Mixed, conservatively broad |
| `ParserStackSafetyFuzzer` | Nested syntax plus JDK matching/captures | Broad; SafeRE-only nesting also exercised by `RobustnessFuzzer` |
| `CaptureSemanticsFuzzer` | Quantified captures and replacements | Broad; known residue remains a finding |
| `MatchFuzzer` | Arbitrary patterns, focused JDK families, case-family model | Mixed, conservatively broad; model checks separately enrolled |
| `FindSequenceFuzzer` | Stateful matching sequences and focused regressions | Broad |
| `ReplacementFuzzer` | Replacement APIs and capture observations | Broad |
| `SplitFuzzer` | Split APIs | Broad |
| `RegionBoundsFuzzer` | Regions, flags, bounds, matching state | Broad |
| `DeferredRegionCaptureFuzzer` | Forced deferred captures, regions, matcher reuse | Focused but not yet graduated; inventoried despite its different package |
| `UnicodeFuzzer` | Unicode-biased patterns and subjects | Broad |
| `Utf8InputFuzzer` | Focused JDK comparisons, representation properties, arbitrary byte windows | Mixed, conservatively broad; property and robustness checks separately enrolled |

Capture and replacement-output waivers have been removed. Parser-marked
intentional class-syntax rejection is also reported as a finding. Existing
unsupported-feature and compiler-budget compile exclusions remain confined to
exploratory `FuzzSupport`; its text-based unsupported-feature recognition is
conservative and can overexclude. The whole-input grapheme waiver is also removed. The #931/#933 exhausted-state
oracle exception remains with an observed-state predicate and a change-detecting
canary; it never exempts replacement output. Neither unsupported-feature handling
nor the exhaustive helper's
capture/compile skips is used by `StrictDifferential`.

## Intentional reproductions

`ExploratoryCaptureTest` pins SafeRE's failed-path capture model and separately
asserts that differential comparison still reports the mismatch. It includes
replacement behavior and a synthetic missing-capture defect that the old waiver
hid. `FuzzSupportOracleTimeoutTest` similarly preserves intentional syntax
rejection as a visible differential finding. Their rationale is documented under
[failed-path captures](../INTENTIONAL_DIVERGENCES.md#failed-path-capture-leakage)
and [class intersection](../INTENTIONAL_DIVERGENCES.md#character-class-intersection-and-ampersand-literals).
The [reviewed reproduction registry](src/test/resources/intentional-reproductions/README.md)
pins exact SafeRE and JDK outcomes, including comment/quoting behavior.
`IntentionalReproductionTest` replays it independently and requires review when
outcomes change. These are not passing fuzz seeds and never suppress other inputs.

## Graduation and retained coverage

To graduate a domain, document syntax, flags, subjects, operations, and state;
resolve known differences; generate the domain directly; enumerate a bounded
slice without legacy exclusions; then run bounded fuzz discovery and review
mismatches and unavailable-oracle frequency before enrollment. Test feature
interactions explicitly. Keep the original broad coverage after graduation.

Historical coverage represented in the initial strict validation includes
alternation priority and greedy/lazy whole-match selection (`a+?b?`,
`(?:a|ab)c?`, `a??b?`). Quantified capture residue remains outside the domain;
a harness test verifies it is reported when passed to the strict comparator.
Grapheme, arbitrary flags and unrestricted capture/state combinations remain
exploratory or model domains. Unicode subjects and selected regions/state sequences
now have strict coverage; that does not graduate all Unicode or region behavior. Existing regression
seeds retain their original target and decoding. New targets have separate
corpora and identities.

No claim is made that the smaller hosted differential domain retains all prior
bug detection or code coverage. Deterministic enumeration and controlled
comparator defects establish useful coverage, not an exhaustive compatibility
proof. Full historical unfixed-revision replay and coverage-profile comparison
remain future evidence for expanding enrollment.

## Oracle exceptions, diagnostics and cadence

`OracleDefectsTest` pins the actual JDK state defects. `JDK-8390449` identifies
the usePattern problem; the terminal-empty variant retains its #931/#933
reference and separate canary. Strict state comparisons assert SafeRE's model
and omit only the defective JDK observation, count exclusions separately, and
resume comparisons afterward. Text-access budget exhaustion remains an unavailable
oracle, not agreement. A JDK update that fixes a canary requires exception review.

Diagnostics report instrumented intentional-path execution, currently class-syntax,
comment quoting, exhausted-empty and usePattern group-zero paths. Finding JSON
records a bounded set of signals. A signal does not prove the mismatch is caused
by that rule, and absence does not imply that all intentional paths were excluded.
No event suppresses a comparison. Additional instrumentation requires an accurate
implementation-side hook and model coverage; no regex scanner is introduced.

The exploratory workflow runs weekly and on manual dispatch after it lands.
Run it before releases as well and triage its archived findings. It does not file
issues automatically. Hosted rollout and corpus migration remain deferred.

Representation comparison checks fresh matches/lookingAt and the first find at
corresponding boundaries. Repeated finds are compared across engines within each
representation: after an empty match, String advances by UTF-16 unit and UTF-8 by
code point. Equating those complete traces would incorrectly reject valid behavior.
