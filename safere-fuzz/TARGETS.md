# Fuzz target domains and enrollment

[targets.json](targets.json) is the authoritative inventory. Every source target
must be listed; adding a class does not enroll it in OSS-Fuzz. The validator
rejects broad differential enrollment, missing entry points, invalid seed paths,
and duplicate names. The local runner defaults to the enrolled selection. The
[assertion and exclusion audit](TARGET_AUDIT.md) records method coverage, shared
oracle limitations and exhaustive-suite exclusions.

## Policy

| Category | Oracle and failure policy | Continuous enrollment |
| --- | --- | --- |
| Broad differential | Explore a wide input space against `java.util.regex`. Intentional differences remain findings for review. | Separate exploratory workflow; no automatic issue filing. |
| Strict differential | Require JDK agreement within explicitly generated, reviewed domains, without semantic waivers. | Explicit OSS-Fuzz enrollment after validation. |
| Property/model | Check engine/representation relationships and explicit SafeRE expectations, including intentional JDK differences. | Explicit OSS-Fuzz enrollment after validation. |
| Robustness | Check rejection, crashes, stack safety, bounds, progress and instrumented work limits without requiring JDK agreement. | Ordinary targets eligible; instrumented targets require a separate build. |

Regression replay is a mode for every category. Broad differential discovery is
explicitly selected and can report intentional JDK differences. Passing fuzz seeds
and reviewed intentional reproductions are separate collections. Classification
never makes a new input exempt from comparison.

## Target domains

| Target | Bucket | Domain and observations | Limits |
| --- | --- | --- | --- |
| `StrictAsciiFuzzer` | Strict differential | Empty expressions, literals `a`, `b`, `ab`, dot, escaped dot, `[ab]`, `[^a]`, `[a-c]`; concatenation, alternation, noncapturing grouping, greedy/lazy optional/star/plus and bounded repeats. Compare compilation, `matches()`, `lookingAt()`, and the entire `find()` sequence, including whole-match text and bounds. | No flags, captures, assertions, regions, replacements, or end-state observations. Expression depth at most 3; independent subjects at most 24 pieces, including BMP, supplementary, lone-surrogate and line-terminator pieces. ASCII describes the pattern syntax, not the input alphabet. Generated witnesses and their `!` suffix variants also exercise successful and near-miss paths. |
| `StrictPriorityFuzzer` | Strict differential | The existing finite `PriorityCases` pattern/subject pairs; compilation, matches, lookingAt and complete find traces. | No flags, captures, regions or assertions; complete finite enumeration and independent comparator validation. |
| `StrictStateFuzzer` | Strict differential | Generated noncapturing grammar with Unicode/UTF-16 subjects; find(start), selected region/bounds sequences, reset, replacement/state and usePattern/state checks. | Region ends do not split a surrogate pair (documented scalar-quantifier divergence); starts may. Failed initial lookingAt/find(start) operations reset both matchers before strict continuation to avoid the JDK #818 state leak. Successful continuations remain compared. Observed oracle defects have canaries; unaffected observations still compare. |
| `EngineEquivalenceFuzzer` | Property | Small regex families over BMP, supplementary and lone-surrogate subjects; default and controlled DFA/OnePass/BitState/NFA traces, including interior UTF-16 starts; valid-scalar UTF-8 paths. | An aggregate deterministic unit test uses diagnostics to prove distinct engines ran; individual fuzz inputs need not exercise every engine. Ordinary fuzz checks preserve the caller's diagnostics configuration. Shared parser/compiler bugs remain a blind spot; UTF-8 cannot represent lone surrogates. |
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

`WorkBoundFuzzer` requires enabled instrumentation through `-Pwork-counters` and
the `safere.fuzz.workCounters` system property. Its work bound scales with compiled
program size and input size. The counter API rejects an uninstrumented build;
prefilters may legitimately reject an input with zero measured NFA work. This
checks forced NFA full matching, excluding compilation, and does not establish
bounds for other engines or repeated-find workloads.

## Existing targets retained for exploration

These targets keep their input decoding and saved seeds. Mixed checks remain
callable there to preserve seed meaning; the extracted property entry points
reuse the same assertions without invoking the broad differential paths.

| Target | Generated surface / oracle | Disposition |
| --- | --- | --- |
| `CompileFuzzer` | Arbitrary syntax and flags; JDK compile acceptance | Broad |
| `ParserCompatibilityFuzzer` | Grammar-biased syntax and membership | Broad |
| `CharacterClassExpressionFuzzer` | Class/intersection grammar and membership, plus an explicit range-trivia model against unspaced references | Mixed, conservatively broad |
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
intentional class-syntax rejection and unsupported-feature rejection are also
reported as findings. No pattern-text recognizer suppresses unsupported syntax.
Compiler-budget compile exclusions remain confined to exploratory `FuzzSupport`.
The whole-input grapheme waiver is also removed. The #931/#933 exhausted-state
oracle exception remains with an observed-state predicate and a change-detecting
canary; it never exempts replacement output. Neither compiler-budget handling
nor the exhaustive helper's
capture/compile skips is used by `StrictDifferential`.

## Intentional reproductions

`CaptureSemanticsFuzzer.acceptedPaths` generates nested repetitions and compound
repeated captures with explicit latest-capture and whole-match expectations.
It checks String and UTF-8 results without using the JDK's inconsistent
nested-repeat captures as an oracle. The broad `quantifiedCaptures` method also
generates these shapes and continues to expose intentional JDK differences.

`SplitFuzzer` checks its fixed CRLF/interior-boundary matrix against Unicode
GB3, separately from arbitrary differential inputs. `ExploratoryGraphemeTest`
confirms that the broad split comparator still reports the intentional JDK
interior-boundary difference; no mismatch is suppressed.

`ExploratoryCaptureTest` pins SafeRE's failed-path capture model and separately
asserts that differential comparison still reports the mismatch. It includes
replacement behavior and a synthetic missing-capture defect that the old waiver
hid. `FuzzSupportOracleTimeoutTest` similarly preserves intentional syntax
rejection as a visible differential finding. Their rationale is documented under
[failed-path captures](../INTENTIONAL_DIVERGENCES.md#failed-path-capture-leakage)
and [class intersection](../INTENTIONAL_DIVERGENCES.md#character-class-intersection-and-ampersand-literals).
The [reviewed reproduction registry](src/test/resources/intentional-reproductions/README.md)
pins exact SafeRE and JDK outcomes, including comment/quoting behavior.
It also retains the bracket/range trivia differences tracked in #1070;
`CharacterClassRangeTriviaTest` pins the approved whitespace-invariant meanings,
and the class-expression generator checks unspaced references without suppressing
the subsequent broad differential comparison.
It also pins the rejected `\v` class-range spellings from `EscapeSyntaxFuzzer`;
these remain generated exploratory findings but are not unconditional agreement
probes run for every input. Compatible spellings remain fixed differential probes.
`IntentionalReproductionTest` replays it independently and requires review when
outcomes change. These are not passing fuzz seeds and never suppress other inputs.
`FuzzSupportTest` pins rejection and visible findings for unsupported syntax,
including comments-separated possessive modifiers and the numeric backreference
spellings formerly run as fixed agreement probes. Quoted and literal-mode controls
still compile and compare normally.

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

Strict targets do not waive SafeRE's intentional differences. An exception for a
defective JDK observation requires all of the following:

1. A predicate inspecting actual JDK state, rather than recognizing pattern syntax.
2. A reference to the upstream defect and the relevant project regression.
3. A canary that fails when the observed defect changes, requiring review/removal.
4. Independent assertions of SafeRE's expected state, with unaffected observations
   and later operations still compared. Outputs and entire inputs are never waived.

`OracleDefectsTest` pins the actual JDK state defects. `JDK-8390449` identifies
the usePattern problem; the terminal-empty variant retains its #931/#933
reference and separate canary. Strict state comparisons assert SafeRE's model
and omit only the defective JDK observation, count exclusions separately, and
resume comparisons afterward. Text-access budget exhaustion remains an unavailable
oracle, not agreement. A JDK update that fixes a canary requires exception review.
The upstream ticket does not specifically establish the terminal-empty variant;
resolve its upstream tracking before expanding that exception to further operations.

Ordinary exploratory runs leave diagnostics disabled. A separate run with
`-Dsafere.fuzz.divergenceEvidence=true` opts into evidence collection for the
process when no listener is installed. Existing listeners remain owned by their
caller. This companion run does not replace default-configuration regression replay.
Diagnostics report instrumented intentional-path execution, currently class-syntax,
comment quoting, exhausted-empty and usePattern group-zero paths. Finding JSON
records a bounded set of signals. A signal does not prove the mismatch is caused
by that rule, and absence does not imply that all intentional paths were excluded.
No event suppresses a comparison. Additional instrumentation requires an accurate
implementation-side hook and model coverage; no regex scanner is introduced.

Matcher signals are coalesced by kind within an operation and delivered after its
state is finalized. Parser signals are delivered after parsing; if parsing fails,
a notification failure is attached as a suppressed exception to the original
syntax error. Public diagnostics callbacks retain their exception-propagation
contract.

The exploratory workflow runs weekly and on manual dispatch.
Run it before releases as well and manually triage its archived findings.
Known capture, grapheme and class-intersection differences remain visible and
can recur. Capture and grapheme divergences have no dedicated evidence hook;
an empty signal set does not establish that a finding is a new compatibility bug. It does not file
issues automatically. Review input-format changes and seed decoding before moving
hosted corpora; new target identities do not automatically inherit old inputs.

Representation comparison checks fresh matches/lookingAt and the first find at
corresponding boundaries. Repeated finds are compared across engines within each
representation: after an empty match, String advances by UTF-16 unit and UTF-8 by
code point. Equating those complete traces would incorrectly reject valid behavior.
