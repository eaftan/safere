# Trino SafeRE Compatibility Report

## Status and Scope

This report records the Stage 1 semantic-feasibility substitution for replacing
Trino's fork of RE2/J with SafeRE. It is evidence for the provisional UTF-8 API;
it is not a promise to reproduce every RE2/J behavior.

The feasibility run used a temporary adapter that decoded each Trino `Slice` to
`String`, ran SafeRE, and converted well-formed UTF-16 result boundaries back to
relative UTF-8 byte offsets in one linear pass. This deliberately isolates
semantic compatibility from the direct-byte implementation.

## Revisions and Environment

- SafeRE base: PR 530 head
  `055e31acf5653122a0ab98242cdd20e6b01778f2`; Stage 1–3 implementation
  `4b03d3d002ec82eb4b8d7f135368f37a40b5e902` on local branch
  `issue-516-pr530`.
- Trino base: `8e023609041cd8e4999aea0ecceb1e81ed887ca1`;
  temporary feasibility adapter
  `e8c7bb1393f3cf39d391bf1665c8e5f20a4aa008` on local branch
  `safere-utf8-feasibility`.
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
The direct `Utf8Input` integration is intentionally a later checkpoint; this
Stage 1 run used decoding by design.

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

## Provisional Experimental Policies

Whether a published Trino migration adopts SafeRE semantics or preserves a
documented subset of RE2/J behavior remains a project-owner decision. For this
local experiment, the implementation policy is intentionally SafeRE-first: it
does not change SafeRE core semantics merely to emulate the fork. The passing
suite shows that this policy is technically feasible; it does not close the
broader product-scope question.

| Policy area | Stage 1 decision |
| --- | --- |
| Pattern language | SafeRE's JDK-oriented supported language is authoritative, subject to the linear-time exclusions. RE2/J-only spellings are not added without a principled independent case. |
| Match and capture semantics | SafeRE/JDK semantics are authoritative at Unicode scalar boundaries. The byte API never exposes a position inside a UTF-8 scalar. |
| Empty-match progress | Advance by one decoded Unicode scalar for UTF-8 input. This is required for coherent byte coordinates and matches Trino's existing observable behavior on supplementary characters. |
| Replacement | Use SafeRE's existing `$0`, `$1`, `${name}`, and backslash-escape parser and error behavior. The Trino suite passed with this policy, so the provisional byte-native sink API is selected. |
| Nonparticipating groups | Preserve SafeRE/JDK `-1` bounds; Trino maps them to SQL null for extraction and to empty output during ordinary replacement. |
| Malformed subject UTF-8 | Trino supplies trusted input. Exact match results are unspecified, but execution must remain bounded, nonthrowing, memory-safe, and monotonic. Strictly validated construction is available to other callers. |
| Error translation | SafeRE exceptions are translated at the Trino wrapper into the existing SQL error category. Exact engine-originated exception text is incidental unless a Trino assertion establishes it. |
| DFA configuration | `re2j.dfa-states-limit` and `re2j.dfa-retries` have no principled one-to-one SafeRE meaning. Treat them as migration-time deprecated no-ops rather than altering SafeRE engines. |
| Dot-star rewrite | Retain it only in the feasibility adapter. Whether to remove it is a performance question, not a compatibility requirement. |
| Engine selection name | Open product/configuration question. Do not silently make a value named `RE2J` select SafeRE in a published Trino change. |

The experimental recommendation is therefore policy 1 from the design:
migrate to SafeRE semantics, with small adapter behavior only where Trino has a
principled SQL/storage contract. Formal selection remains open for review. No
observed fork behavior requires changing SafeRE core semantics.

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
| Is compatibility with all Trino RE2/J semantics in scope? | Open owner decision. The experimental branch recommends SafeRE semantics because the complete current suite passes without fork emulation. Any request to preserve another behavior needs a concrete SQL contract and principled linear-time formulation. |
| Must all Trino RE2/J semantics be retained? | No. The suite supports migration to SafeRE semantics, and no principled requirement for blanket compatibility was found. Reopen only for a concrete SQL contract. |
| Does Trino require RE2/J replacement parsing? | No observed requirement. The complete ordinary and lambda replacement tests passed with SafeRE's parser. Selected: SafeRE replacement semantics. |
| Are malformed UTF-8 results stable SQL behavior? | No evidence. Selected: trusted recovery safety guarantees only; exact results remain unspecified. |
| Must the dot-star rewrite remain? | No semantic evidence. Deferred to end-to-end Trino benchmarks. |
| Must DFA limits/retries/listeners be reproduced? | No semantic evidence and no equivalent contract. Selected: migration-time deprecated no-ops. |
| May matching resume inside a UTF-8 scalar? | No. `UTF8-001` demonstrates that doing so is incoherent for the byte API and conflicts with Trino's current tests. |
| Should RE2/J-only pattern syntax be added? | No observed case. Open only for a concrete, independently principled and linear-time feature request. |
| Are exact exception messages contractual? | Only messages directly asserted by Trino tests are treated as adapter contracts. Engine text is otherwise incidental. |
| What configuration value selects SafeRE? | Open product decision for a future Trino change; not needed to validate the SafeRE API locally. |
| Should a raw `findUtf8(byte[], offset, length)` convenience be public? | Deferred until a benchmark demonstrates that the `Utf8Input` view allocation materially affects Trino. |

There is no unresolved semantic blocker to the provisional public API. The two
remaining questions concern publication/configuration and optional performance
convenience, not whether the API can express Trino's regex operations.

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
