# Trino SafeRE Compatibility Report

## Status and Scope

This report records the semantic-feasibility and final direct-input
substitutions for replacing Trino's fork of RE2/J with SafeRE. The final
validation gate is complete; the resulting UTF-8 API does not promise to
reproduce every RE2/J behavior.

The feasibility run used a temporary adapter that decoded each Trino `Slice` to
`String`, ran SafeRE, and converted well-formed UTF-16 result boundaries back to
relative UTF-8 byte offsets in one linear pass. This deliberately isolates
semantic compatibility from the direct-byte implementation.

## Revisions and Environment

- SafeRE base: PR 530 head
  `055e31acf5653122a0ab98242cdd20e6b01778f2`; Stage 1–3 implementation
  `4b03d3d002ec82eb4b8d7f135368f37a40b5e902` on local branch
  `issue-516-pr530`; Stage 4 validation artifact
  `387a3a1d2afae33b666ef24cfd021f882aa024fb` on the same local branch.
- Trino base: `8e023609041cd8e4999aea0ecceb1e81ed887ca1`;
  temporary feasibility adapter
  `e8c7bb1393f3cf39d391bf1665c8e5f20a4aa008` on local branch
  `safere-utf8-feasibility`; direct UTF-8 interface substitution
  `129d91dbc8e0ca5c067c7321f39c1f99df2eecac` on the same local branch.
- JDK: OpenJDK 25.0.2+10-69.
- Maven: 3.9.14.
- SafeRE artifact: `org.safere:safere:0.9.0-SNAPSHOT`, installed from the
  local worktree.

Nothing from either branch has been pushed or published.

## Validation Commands

The following commands were run locally:

```bash
# SafeRE workspace
mvn install -DskipTests -q

# Trino workspace: first make reactor dependencies available locally
./mvnw -pl core/trino-main -am install -DskipTests -q

# Run the complete RE2/J-backed scalar-function test class
./mvnw -pl core/trino-main -Dtest=TestRe2jRegexpFunctions test -q
```

The final Trino command passed. `TestRe2jRegexpFunctions` inherits the common
regex-function suite and exercises compilation/error translation, casts,
`regexp_like`, extraction, extract-all, split, position, count, ordinary
replacement, and lambda replacement.

The first run found the scalar-boundary difference `UTF8-001` below. The
temporary adapter was then made boundary-aware and the complete class passed.
Stage 4 subsequently replaced that adapter with direct `Utf8Input` execution,
as recorded below.

The Stage 1–3 implementation was also verified locally with:

```bash
mvn -pl safere test -q
mvn -pl safere -Pwork-counters -Dtest=Utf8LinearTimeTest test -q
mvn -pl safere-fuzz -am -Dtest=MatchFuzzer \
  -Dsurefire.failIfNoSpecifiedTests=false test -q
mvn -pl safere-crosscheck -Pcrosscheck-public-api-tests test -q
mvn -pl safere javadoc:javadoc -q
mvn -pl safere-benchmarks -am package -DskipTests -q
```

All commands passed on the recorded environment. The full SafeRE and generated
crosscheck runs included the existing RE2-derived exhaustive suites and the
String/JDK differential matrices. The work-counter run covered valid and
malformed forward/reverse UTF-8 progress. Long-running fuzzing was not launched;
the deterministic `MatchFuzzer` regression mode passed.

## Stage 4 Direct Trino Interface Validation

The Stage 4 branch removes the decode-to-String matcher and uses the
provisional API directly:

- `Slice.byteArray()`, `byteArrayOffset()`, and `length()` construct a trusted
  `Utf8Input` array window without copying;
- `Pattern.find(Utf8Input)` implements capture-free `regexp_like`;
- `Utf8Matcher` supplies relative byte bounds for extraction, extract-all,
  split, position, count, and lambda replacement;
- extracted groups are `Slice` views over the original backing array;
- ordinary replacement adapts `DynamicSliceOutput` to `Utf8Sink`, transferring
  unmatched and captured source ranges without decoding the subject; and
- `trino-re2j` is absent from both `core/trino-main` and root dependency
  management on the validation branch.

This was an intentionally strong substitution checkpoint. The final shareable
branch restores RE2/J and exposes SafeRE as an additional independent option.

The final Stage 4 source was verified locally with:

```bash
# SafeRE workspace
mvn -pl safere install -DskipTests -q

# Trino workspace
./mvnw -pl core/trino-main -am install -DskipTests -q
./mvnw -pl core/trino-main -Dtest=TestRe2jRegexpFunctions test -q
```

Both Trino commands passed. The test class covers the complete inherited regex
function surface plus direct integration cases for nonzero-offset array-backed
`Slice` values, multibyte numbered and named captures, zero-copy group views,
supplementary-scalar empty-match progress, nonparticipating groups, and
byte-native named/numbered replacement.

No Stage 4 semantic difference, missing operation, or SafeRE bug was found.
In particular, no Trino-specific override of the approved SafeRE semantic
policy was proposed or implemented.

Expected allocations remain: the small input view and stateful matcher,
replacement output, replacement-template processing proportional to the
replacement, and Trino's lambda argument block. Matching and group extraction
allocate no input-sized decoded `String` or subject copy. Ordinary and lambda
replacement allocate their required output but no input-sized intermediate
`String`.

The provisional `Utf8Input`, `Utf8Matcher`, bounds, and `Utf8Sink` shape can
express every required Trino operation and is frozen for the engine-completion
stages. No Stage 4 blocker remains.

## Approved Provisional Semantic Policy

The approved policy is intentionally SafeRE-first: a Trino
migration adopts SafeRE semantics rather than preserving RE2/J semantics by
default. SafeRE core does not change merely to emulate the fork. A concrete
Trino behavior may be considered separately only when it is a coherent SQL
contract with a principled linear-time implementation, and it must be
highlighted for owner review before implementation.

| Policy area | Stage 1 decision |
| --- | --- |
| Pattern language | SafeRE's JDK-oriented supported language is authoritative, subject to the linear-time exclusions. RE2/J-only spellings are not added without a principled independent case. |
| Match and capture semantics | SafeRE/JDK semantics are authoritative at Unicode scalar boundaries. The byte API never exposes a position inside a UTF-8 scalar. |
| Empty-match progress | Advance by one decoded Unicode scalar for UTF-8 input. This is required for coherent byte coordinates and matches Trino's existing observable behavior on supplementary characters. |
| Replacement | Use SafeRE's existing `$0`, `$1`, `${name}`, and backslash-escape parser and error behavior. The Trino suite passed with this policy, so the byte-native sink API is selected. |
| Nonparticipating groups | Preserve SafeRE/JDK `-1` bounds; Trino maps them to SQL null for extraction and to empty output during ordinary replacement. |
| Malformed subject UTF-8 | Trino supplies trusted input. Exact match results are unspecified, but execution must remain bounded, nonthrowing, memory-safe, and monotonic. Strictly validated construction is available to other callers. |
| Error translation | SafeRE exceptions are translated at the Trino wrapper into the existing SQL error category. Exact engine-originated exception text is incidental unless a Trino assertion establishes it. |
| DFA configuration | `re2j.dfa-states-limit` and `re2j.dfa-retries` have no principled one-to-one SafeRE meaning. The final validation branch retains them for RE2/J and does not apply them to SafeRE. |
| Dot-star rewrite | Retain it only in the feasibility adapter. Whether to remove it is a performance question, not a compatibility requirement. |
| Engine selection name | The final validation branch exposes `JONI`, `RE2J`, and `SAFERE` independently; a value named for one engine never silently selects another. |

The selected policy is therefore policy 1 from the design: migrate to SafeRE
semantics. No observed fork behavior requires changing SafeRE core semantics.

## Difference Inventory

| ID | Operation, pattern, input | JDK / SafeRE String | Trino RE2/J and SQL contract | Classification and disposition | Status |
| --- | --- | --- | --- | --- | --- |
| UTF8-001 | repeated `find`; empty pattern; input `💰` | JDK-oriented String matching can report an empty match between the UTF-16 surrogate code units. | Trino's existing tests require one empty result before and after each Unicode scalar, never inside it. | Representation boundary, not a request to change String semantics. `Utf8Matcher.find()` advances by a decoded scalar and returns only byte/scalar boundaries. The temporary Stage 1 adapter skipped non-scalar UTF-16 results. | Resolved; covered by SafeRE UTF-8 empty-progress tests and the passing Trino extract-all/split tests. |

No other difference was observed in the complete Trino RE2/J scalar-function
test class after resolving `UTF8-001`. In particular, the existing Trino tests
did not require an RE2/J-specific replacement dialect, capture repair, pattern
rewrite, or exception message.

## Open-Question Register

| Question | Evidence and current disposition |
| --- | --- |
| Is compatibility with all Trino RE2/J semantics in scope? | Resolved: no. SafeRE semantics are the approved policy. Any proposed exception requires a concrete SQL contract, a principled linear-time formulation, and explicit owner review before implementation. |
| Must all Trino RE2/J semantics be retained? | No. The suite supports migration to SafeRE semantics, and no principled requirement for blanket compatibility was found. Reopen only for a concrete SQL contract. |
| Does Trino require RE2/J replacement parsing? | No observed requirement. The complete ordinary and lambda replacement tests passed with SafeRE's parser. Selected: SafeRE replacement semantics. |
| Are malformed UTF-8 results stable SQL behavior? | No evidence. Selected: trusted recovery safety guarantees only; exact results remain unspecified. |
| Must the dot-star rewrite remain? | No semantic evidence. Deferred to end-to-end Trino benchmarks. |
| Must DFA limits/retries/listeners be reproduced? | No semantic evidence and no equivalent contract. Resolved: retain the properties for RE2/J while SafeRE neither accepts nor silently interprets them. |
| May matching resume inside a UTF-8 scalar? | No. `UTF8-001` demonstrates that doing so is incoherent for the byte API and conflicts with Trino's current tests. |
| Should RE2/J-only pattern syntax be added? | No observed case. Open only for a concrete, independently principled and linear-time feature request. |
| Are exact exception messages contractual? | Only messages directly asserted by Trino tests are treated as adapter contracts. Engine text is otherwise incidental. |
| What configuration value selects SafeRE? | Resolved in the final validation branch: `SAFERE`. `RE2J` continues to select RE2/J and is not an alias. |
| Should a raw `findUtf8(byte[], offset, length)` convenience be public? | Deferred until a benchmark demonstrates that the `Utf8Input` view allocation materially affects Trino. |

There is no unresolved semantic or configuration blocker to the public API.
The raw-array convenience remains deliberately deferred because the measured
view allocation did not justify another public entry point.

## Final Trino Validation

The final local integration uses SafeRE revision
`8e2394facf9c50ac3d51a15b6485b496cb591d2c` and Trino revision
`4e070738fd759ef7cb909177bda24884b7d00dc1`. Trino exposes `JONI`, `RE2J`, and
`SAFERE` as independent engine selectors. It retains `io.trino:trino-re2j` and
its DFA properties for the RE2/J path while using separately named types and
functions for SafeRE.

The complete SafeRE and RE2/J regular-expression function classes passed, including like,
ordinary and lambda replacement, extraction, extract-all, split, count, and
position behavior. The adjacent feature-configuration, type-coercion, and
connector-expression translation tests also passed. A full `trino-main` run
was attempted; it could not complete because Docker-dependent OAuth tests fail
without a Docker environment, after which an unrelated node-state poller kept
the failed run alive. No regex-related failure occurred before termination.

The final deterministic JMH matrix runs SafeRE, RE2/J, and Joni from the same
Trino checkout using identical inputs. It covers matching and replacement over
five pattern families and two input sizes. All three engines ran in the same
invocation with two forks, three 1-second warmups, and five 1-second
measurements. The geometric mean of SafeRE time divided by Trino RE2/J time is
0.47 overall, 0.40 for capture-free matching, and 0.57 for
replacement. Every individual ratio is at most 1.05, satisfying the
precommitted 1.10 aggregate and 1.20 individual gates. Values below 1.0 mean
SafeRE is faster.

## Frozen Benchmark Membership

The exact shared microbenchmark inputs are recorded under `utf8Matching` in
`safere-benchmarks/benchmark-data.json`. Membership is frozen before optimized
implementation measurements:

- capture-free find: ASCII and multibyte, with early, late, and no-match cases;
- repeated find/count: ASCII and multibyte;
- capture-bound extraction: numbered, named, and nonparticipating groups;
- split-style empty-match iteration over supplementary input;
- replacement: literal, numbered, and named references;
- whole-array and nonzero-offset windows;
- trusted and validated construction;
- hard-failure scaling.

The Trino-level set is the corresponding function surface:
`regexp_like`, `regexp_extract`, `regexp_extract_all`, `regexp_split`,
`regexp_position`, `regexp_count`, ordinary `regexp_replace`, and lambda
replacement, each with ASCII and multibyte subjects where applicable.

## Precommitted Acceptance Gates

- Existing String workloads: SafeRE-after / SafeRE-before geometric mean must
  be at most `1.05`; no individual workload may exceed `1.10` without an
  explained, approved tradeoff.
- Trino application workloads: SafeRE-enabled / existing Trino RE2/J geometric
  mean must be at most `1.10`; no individual workload may exceed `1.20` without
  an explained, approved tradeoff.
- Trusted UTF-8 matching must not allocate an input-sized decoded `String` or
  byte copy. Capture bounds must let Trino slice its original storage.
- Validated construction cost is reported separately from trusted matching.
- Hard-failure and repeated-empty-match workloads must retain linear scaling.
- The raw-array boolean convenience remains absent unless its measured benefit
  over `Pattern.find(Utf8Input)` is material in the Trino path.

These are gates for later performance stages, not claims about measurements
already completed.
