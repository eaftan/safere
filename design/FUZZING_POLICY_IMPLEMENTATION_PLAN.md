# Fuzzing policy implementation plan

This revision follows the accepted four-category proposal and subsequent feedback
in [issue #872](https://github.com/eaftan/safere/issues/872#issuecomment-5809756227).
The implementation is local and unpublished. The branch incorporates current local
`main`, including surrogate-interior reverse-DFA behavior and exhausted matcher
state model coverage. Rollout and hosted corpus migration remain outside this task.

## Policy

| Category | Oracle and failure policy | Continuous enrollment |
| --- | --- | --- |
| Broad differential | Explore arbitrary syntax, subjects and state. Intentional differences remain findings for triage. | Separate exploratory workflow; no automatic issue filing. |
| Strict differential | Generate a reviewed compatibility domain and compare every declared observation. | Explicit enrollment after validation. |
| Property/model | Check engine/representation equivalence and explicit SafeRE semantic models. | Explicit enrollment after validation. |
| Robustness | Exercise rejection, crashes, stack safety, bounds, progress and instrumented work limits without a JDK oracle. | Ordinary targets eligible; instrumented targets need a separate build. |

Regression replay applies to every category. Passing differential seeds and exact
intentional reproductions are separate collections. A known label never suppresses
another finding. Remove the heuristic capture waiver, including from broad runs.
The newer whole-input grapheme waiver likewise becomes model coverage plus visible
exploratory findings; it cannot establish that every comparison in a case is safe
to omit.

## Phase 1: Inventory and execution policy

- Maintain [targets.json](../safere-fuzz/targets.json), including every class,
  individual fuzz method, seed location, category, enrollment and special build
  profile. Package-private engine access must not prevent enrollment.
- Validate missing/duplicate classes, methods, seeds and invalid enrollment.
  Account for new upstream targets and all SplitFuzzer methods.
- Use the manifest for local selection and generated OSS-Fuzz launchers.
  Generated launchers retain the required discovery marker. An instrumented
  target cannot accidentally enroll in a normal hosted build.
- Retain the [target/exclusion audit](../safere-fuzz/TARGET_AUDIT.md). Exhaustive
  compile skips, capture skips, omitted observations, oracle fallbacks and known
  classifications do not count as strict agreement.
- Run broad discovery weekly through a separate scheduled/manual workflow and
  before releases through workflow dispatch or the local runner. Preserve logs,
  raw crashes, Java reproducers and provenance; do not automatically file bugs.
  The schedule becomes active only when this change lands.

## Phase 2: Independently runnable checks

### Strict domains

Keep pattern syntax small, not the subject alphabet. The generated noncapturing
grammar contains literals, simple classes, dot, concatenation, alternation and
bounded greedy/reluctant quantification. Subjects include BMP characters,
supplementary characters, lone surrogates and line terminators. Bounds keep both
pattern and input sizes finite. Generated witnesses supplement unrelated inputs.

Retain the finite graduated priority domain. Add a separate reviewed state target:
`find(int)` at arbitrary UTF-16 indices, regions/bounds, matches and lookingAt,
find continuation, reset, replaceAll followed by state access, and usePattern
followed by state access and another find. A small pattern grammar does not make
all API combinations equivalent; each sequence has explicit observations and
model assertions. Additional captures, boundary assertions and flags still
require their own review.

SafeRE must execute even when the oracle is unavailable. Bound JDK text access,
record budget/stack exhaustion as unavailable, preserve any earlier mismatches,
and do not leave timed-out worker threads accumulating. Resource unavailability
is separate from an observed oracle defect and from successful agreement.

### Oracle defects

No exemptions for SafeRE's intentional semantics in strict comparison. Allow
reviewed exceptions for a defective JDK observation only when:

1. The predicate inspects actual JDK state and does not recognize pattern syntax.
2. The exception cites the upstream bug and relevant project regression.
3. A canary fails when the observed defect changes, requiring review/removal.
4. SafeRE's expected state is asserted independently and unaffected observations
   and later operations still compare. Output and entire inputs are never waived.

`JDK-8390449` tracks incoherent state after usePattern. The terminal-empty-find
variant is the already-approved #931/#933 behavior; retain its separate canary
and project reference, and do not claim the upstream ticket specifically covers
that variant. Upstream tracking for that variant should be resolved before
expanding this exception to further operations.

### Property/model checks

- Shared String/UTF-8 families and explicit case-family models remain independent
  entry points. Valid scalar text is required for representation equivalence;
  unpaired UTF-16 surrogates cannot be losslessly encoded as UTF-8.
- Make engine-path equivalence a first-class target. Exercise the default cascade
  and controlled DFA, OnePass, BitState and NFA configurations. Compare complete
  declared result traces and use diagnostics tests to prove distinct engines ran.
- Include surrogate-interior starts in String engine comparisons and multibyte
  subjects in UTF-8 engine comparisons. Respect each API's progression rules.
- Preserve the upstream independent emoji/ZWJ model target. Enrollment remains
  explicit rather than inferred from its name or property category.
- Shared parser/compiler code remains a blind spot of engine equivalence; retain
  independent JDK/model checks alongside it.

### Robustness and work accounting

Keep arbitrary syntax/bytes, bounded deep nesting, progress and bounds checks.
Add a separately instrumented WorkBoundFuzzer using `-Pwork-counters`. Require
positive instrumentation, and bound operation work as a function of compiled
program size and input size. The initial domain forces NFA full matching; this
is not a global proof for every engine or repeated-find workload. The runner
selects the required build profile; ordinary runs do not pretend that disabled
counters supplied evidence. Hosted instrumented builds are a rollout decision.

## Phase 3: Evidence, models and waiver migration

Preserve raw libFuzzer crash inputs as well as Java reproducers in each run
archive, rather than depositing discoveries into passing regression resources.
Record decoded text losslessly, flags, state/operation context, outcomes, target,
commit and runtime. Keep operation history bounded and mark truncation.

The diagnostics SPI exposes intentional-path execution events. Initial events
come from existing semantic branches: class-syntax rejection, quoting inside
comments, terminal empty-match exhaustion, and usePattern group-zero preservation.
The collector retains a bounded set of event identities and includes it in
findings. Events are evidence, never suppression or proof of causation. They do
not run a second recognizer over regex text. Not every documented divergence has
a distinct branch; absence means no instrumented signal, not “definitely unknown.”
Extend instrumentation only where the implementation can accurately report an
exercised rule, with an accompanying model test.

Keep exact reviewed reproduction data separate, pin both outcomes, link the
intentional rule and model test, and fail replay on changed outcomes. Preserve
comment/quoting model coverage instead of adopting the scanner proposed in #864.
No automatic similarity classification or semantic suppression is introduced.

## Validation

Run affected fuzz targets in regression mode, strict finite enumeration, oracle
canaries, engine participation checks, diagnostics model tests, manifest/exporter
checks, and the instrumented work target. Follow with bounded discovery through
the actual runner for candidate enrolled targets. A quiet run is evidence, not
proof; record discovered domain limits instead of silently exempting cases.

The earlier controlled-finding check verified raw artifact placement without
changing regression resources. Full historical fault replay and before/after
coverage comparisons remain later coverage-assessment work; neither is implied
by the smaller strict domain passing.

## OSS-Fuzz rollout and corpus continuity — stop here

Coordinate the existing integration PR with the upstream manifest. Validate the
actual container, launcher discovery, runtime, seeds and resource settings. Decide
whether to add a separate work-counter build. Audit input-format changes and seed
decoding before moving any hosted corpus. No container execution, hosted corpus
migration, external PR update or deployment is authorized by the current scope.

Representation comparison checks fresh matches/lookingAt and the first find at
corresponding boundaries. Repeated finds are compared across engines within each
representation: after an empty match, String advances by UTF-16 unit and UTF-8 by
code point. Equating those complete traces would incorrectly reject valid behavior.
