# UTF-8 Input Implementation Plan

## Purpose

This plan describes how to evolve the prototype in
[PR #530](https://github.com/eaftan/safere/pull/530) into the UTF-8 input feature
specified by the [UTF-8 input design](UTF8_BYTE_INPUT.md).  PR #530 is the
implementation base, not a discarded experiment.  It has already done the
broad engine refactoring needed to prove that SafeRE can share matching loops
between String and UTF-8 input.

The work below should preserve that contribution and add the contracts,
validation, integration evidence, and performance work required for a public
API.  Trino is the first release gate.  The resulting abstraction must remain
usable by the broader category of JVM applications that already store text as
UTF-8 bytes or buffers.

This plan was written against PR #530 head
`055e31acf5653122a0ab98242cdd20e6b01778f2`.  Recheck the inventory if that head
changes before implementation begins.

## Working Agreement

- Build from PR #530 rather than recreating its scanner and engine changes.
- Preserve its commits and authorship in branch history.
- Prefer focused refactoring of the prototype over replacement with a parallel
  implementation.
- Create one experimental integration branch from PR #530's head and perform
  the work there.  That branch may be shared with Trino before it is ready to
  merge.
- Treat intermediate APIs and commits as freely revisable.  They do not create
  compatibility promises merely because they are public in an experimental
  artifact.
- Do not merge or release the experimental branch until the final Trino,
  correctness, and performance validation is complete.
- Keep PR #530 open.  Update its branch when access and coordination permit, or
  use a dependent experimental branch and feed the completed work back into the
  existing PR according to its owner's preferred workflow.  Do not close and
  replace the existing PR merely to reset review.
- Keep each change reviewable and keep String and UTF-8 verification green at
  every stage.
- Fix every SafeRE bug discovered by Trino validation using the repository's
  external-validation workflow before continuing.

## What PR #530 Already Provides

At the recorded head, PR #530 contributes:

- package-private `InputScanner`, `StringInputScanner`, and
  `Utf8InputScanner` abstractions;
- shared scanner-based loops in `Dfa`, `Nfa`, `BitState`, and `OnePass`;
- byte-aware `EngineContext` support through `ByteEngineContext`;
- whole-array `Pattern.matcher(byte[])` and byte-mode construction in
  `Matcher`;
- byte-coordinate matching, group bounds, and allocating `groupBytes`
  conveniences;
- forward and reverse on-demand UTF-8 decoding with deterministic one-byte
  recovery for malformed sequences;
- byte-aware grapheme and Unicode word-boundary work;
- RE2-derived byte-search tests, exhaustive String/byte helpers, and
  crosscheck plumbing;
- `ByteMatchingBenchmark` for initial byte-versus-String measurements;
- passing build, fuzz-regression, and Java/C++/Go benchmark-smoke checks at the
  recorded PR head.

The core asset is the engine migration.  The remaining work should make its
input abstraction semantic, safe, windowed, storage-neutral at the public
boundary, and validated in a real consumer.

## Gap Summary

- **Shared engines:** PR #530 converts DFA, NFA, BitState, and OnePass.  Preserve
  that work and strengthen it with forced-path engine-equivalence tests.
- **Separate UTF-8 API:** PR #530 mixes byte mode into `Matcher`.  Add
  `Utf8Input` and `Utf8Matcher`, then remove public representation switches
  from the JDK-compatible matcher.
- **Logical windows:** PR #530 accepts only whole arrays.  Add offset/length
  array views with relative coordinates.
- **Representation neutrality:** PR #530's public entry point is `byte[]`.
  Make `Utf8Input` the durable abstraction and retain arrays as its first
  adapter.
- **Semantic internal input:** `InputScanner` exposes `charOrByteAt`.  Replace
  raw-unit assumptions with bounded decoding and semantic operations.
- **Strict validation:** PR #530 recovers from malformed input.  Add strict RFC
  3629 validated construction while retaining explicit trusted recovery.
- **Zero-copy captures:** `groupBytes` copies.  Expose byte-relative group
  bounds through `Utf8Matcher` so callers can slice native storage.
- **Capture-free search:** PR #530 requires matcher construction.  Add direct
  `Pattern.find(Utf8Input)` and a raw array-window entry point only if measured.
- **Byte-native replacement:** replacement remains String-oriented.  Validate
  the Trino dialect, then add bounds-based adapter replacement or `Utf8Sink`.
- **Matcher reset:** the prototype has one matcher with hidden byte mode.
  Defer UTF-8 reset until it is benchmarked and used by an integration.
- **Trino semantics and integration:** neither the compatibility decision nor
  dependency replacement has run.  Perform the feasibility, early interface,
  and final optimized substitutions.
- **Broader storage:** the scanner is array-specific.  Keep the public input
  neutral and internal implementations specialized; defer concrete buffer
  adapters.
- **Correctness depth:** byte tests exist, but full validator, window,
  forward/reverse, forced-engine, and progress fuzzing remain.
- **Performance evidence:** PR #530 has an initial microbenchmark.  Freeze
  workloads and measure SafeRE, decode-inclusive, Trino RE2/J, and Trino
  SafeRE paths end to end.

## Deliverables

The complete effort produces:

1. the PR #530 engine refactor, retained in history;
2. `design/TRINO_SAFERE_COMPATIBILITY.md`, containing the semantic inventory,
   policy decisions, exact revisions, validation commands, and unresolved
   blockers;
3. the public `Utf8Input` and `Utf8Matcher` contracts selected by the early
   Trino substitution;
4. the array-window input implementation, trusted decoder, and strict
   validator;
5. storage-specialized shared engine execution for String and UTF-8;
6. zero-copy group-bound integration and the selected byte-native replacement
   path;
7. differential, state-machine, engine-equivalence, fuzz, scaling, and
   allocation coverage;
8. a Trino validation branch with `io.trino:trino-re2j` removed from the
   relevant module;
9. recorded before/after SafeRE and Trino benchmark evidence;
10. final API documentation and a follow-up roadmap for buffer and segmented
    adapters.

## Scope Boundaries Inherited From The Design

- Inputs are UTF-8 text, not arbitrary binary data; `\C` remains unsupported.
- Pattern compilation remains String-based.
- SafeRE core does not depend on Airlift `Slice` or another application's
  storage type.
- Phase one does not reproduce the complete upstream or Trino RE2/J API.
- `PatternSet` UTF-8 input, regions, transparent bounds, anchoring bounds,
  decoded group strings, and general split conveniences remain deferred unless
  the Trino inventory proves one is required.
- The implementation does not add separate UTF-8 DFA, NFA, BitState, or
  OnePass engines.
- Callers do not implement arbitrary per-byte accessors in hot engine loops.
- SafeRE's JDK-oriented semantics remain the default policy subject to the
  linear-time guarantee.  Trino RE2/J behavior is evidence, not automatic
  authority.

## Stage 0: Establish The PR #530 Baseline

### Objective

Make the prototype head reproducible and characterize it before changing its
API or decoder.

### Work

- Refresh this plan's PR-head SHA if PR #530 has advanced.
- Check out or create the implementation branch from the PR #530 head.
- Rebase or merge current `main` according to the PR owner's preferred branch
  workflow, preserving the prototype commits.
- Record the SafeRE SHA, JDK, Maven, and Trino SHA in the compatibility report.
- Run the existing PR checks locally at the narrowest useful scope.
- Run `ByteMatchingBenchmark` through `./run-java-benchmarks.sh` and retain the
  raw baseline output outside the source tree.
- Review scanner calls in all four engines and record any path that still reads
  `String` directly or assumes UTF-16 units.
- Profile one ASCII and one multibyte prototype workload to establish whether
  scanner dispatch or temporary `int[]` cursor holders are material.

### Verification

```bash
mvn -pl safere test -q
./run-java-benchmarks.sh \
  '^org\.safere\.benchmark\.ByteMatchingBenchmark\.'
```

Also run the repository checks affected by the prototype's crosscheck changes.
Record exactly which checks were and were not run.

### Exit gate

- The PR #530 head and baseline results are reproducible.
- Existing String tests pass.
- Existing byte tests pass.
- Every remaining String-only engine access is listed for later stages.
- No prototype code has been replaced merely to obtain a preferred naming
  scheme.

## Stage 1: Trino Semantic Feasibility Before API Commitment

### Objective

Determine whether SafeRE's JDK-oriented semantics can replace Trino RE2/J
without bending SafeRE around unprincipled fork behavior.

### Work

- Create `design/TRINO_SAFERE_COMPATIBILITY.md` with an open-question register.
- In a temporary Trino branch based on the recorded Trino revision, replace
  regex execution with PR #530 SafeRE using a deliberately temporary adapter.
- Decode each `Slice` to String for this checkpoint.  Map well-formed UTF-16
  boundaries back to UTF-8 boundaries in one linear pass when positions or
  captures must be compared.
- Run the complete Trino RE2/J function surface, including compilation, cast,
  extraction, extract-all, split, position, count, ordinary replacement,
  lambda replacement, and error cases.
- For every difference, record:

  - the smallest representative pattern and input;
  - JDK, SafeRE, and Trino RE2/J results;
  - whether Trino documentation or a test establishes a SQL contract;
  - whether the behavior is principled and implementable in linear time;
  - the proposed resolution: SafeRE bug, Trino adapter behavior, intentional
    migration change, unsupported feature, or blocker.

- Resolve SafeRE/JDK bugs using the divergence bug-fixing workflow before
  continuing the inventory.
- Select or explicitly defer the pattern, match, capture, error, and
  configuration policies described in the design.  Select the replacement
  policy before the early interface substitution because that checkpoint must
  exercise complete replacement semantics.
- Freeze the benchmark workload membership and quantitative merge gates before
  implementation measurements begin.

### Trino seams

- `core/trino-main/src/main/java/io/trino/type/Re2JRegexp.java`
- `core/trino-main/src/main/java/io/trino/type/Re2JRegexpType.java`
- `core/trino-main/src/main/java/io/trino/operator/scalar/Re2JRegexpFunctions.java`
- `core/trino-main/src/main/java/io/trino/operator/scalar/Re2JRegexpReplaceLambdaFunction.java`
- `core/trino-main/src/test/java/io/trino/operator/scalar/TestRe2jRegexpFunctions.java`
- `core/trino-main/pom.xml`
- the root dependency-management entry for `trino-re2j`

### Exit gate

- The compatibility report contains every observed difference and its status.
- No unresolved difference is silently assumed to require Trino RE2/J
  compatibility.
- Any blocker that could invalidate the migration is resolved before public API
  work proceeds.
- Benchmark sets and acceptance gates are committed.

## Stage 2: Harden The Prototype Input Core

### Objective

Retain PR #530's shared engine plumbing while replacing its accidental
array/raw-unit contracts with the bounded semantic input model from the design.

### Test-first work

Add focused tests before changing the decoder:

- exhaustive valid one-, two-, three-, and four-byte scalar decoding;
- overlong encodings, continuation bytes, truncated sequences, surrogates, and
  values above `U+10FFFF`;
- identical forward and reverse boundaries;
- one-byte trusted recovery and monotonic progress;
- nonzero-offset windows and sentinels outside both ends;
- empty, single-byte, and maximum-length windows;
- literal and boundary operations at window edges.

### Implementation

- Preserve `InputScanner` as the starting point, then evolve it into the
  package-private semantic input contract selected in the design.  Rename it
  only as part of that refactoring, not as a parallel abstraction.
- Remove `charOrByteAt` from general engine use.  Add semantic decode,
  boundary, and literal-search operations so engines do not infer that an
  encoding unit is a character.
- Replace duplicate `codePointAt` overloads and temporary position arrays with
  the measured allocation-free decode-result shape.
- Add immutable window bounds to the UTF-8 scanner and make all internal
  positions relative to the logical window.
- Keep forward and reverse decode bounded to one scalar.
- Make the trusted malformed-input recovery rule identical in both directions
  and safe at arbitrary window boundaries.
- Add strict RFC 3629 validation over the same canonical decoder.
- Keep the String scanner specialized and verify that the JIT can inline its
  hot operations.
- Ensure `ByteEngineContext`, grapheme handling, boundary logic, and trailing
  line-terminator detection operate in logical coordinates.

### Likely SafeRE files

- `InputScanner.java`
- `StringInputScanner.java`
- `Utf8InputScanner.java`
- `ByteEngineContext.java`
- `EngineContext.java`
- `GraphemeSupport.java`
- `Dfa.java`
- `Nfa.java`
- `BitState.java`
- `OnePass.java`

### Verification

- New decoder and window unit tests.
- Existing `RE2ByteSearchTest` and exhaustive byte helpers.
- Existing boundary, grapheme, DFA, NFA, BitState, and OnePass suites.
- Work-counter assertions for forward/reverse progress and window safety.
- `mvn -pl safere test -q`.

### Exit gate

- There is one canonical UTF-8 decoder and one String/UTF-8 engine contract.
- Whole-array and window behavior agree when given the same logical text.
- Trusted malformed input terminates safely and monotonically.
- Validated input rejects every non-RFC-3629 sequence at the documented
  relative offset.
- String matching shows no correctness regression.

## Stage 3: Introduce The Provisional Public API

### Objective

Replace PR #530's hidden byte mode in the JDK-compatible `Matcher` with the
representation-neutral public contracts required by Trino and future JVM
consumers.

### Test-first work

- Write `Utf8InputTest` for trusted and validated array factories, bounds,
  nulls, mutation preconditions, and relative lengths.
- Write `Utf8MatcherStateMachineTest` from the applicable transitions in
  `MATCHER_STATE_MACHINE.md`.
- Cover repeated `find`, failed-find invalidation, terminal empty matches,
  participating and nonparticipating groups, group-index errors, and
  supplementary code points.
- Add paired String/UTF-8 traces that compare results after converting UTF-16
  positions to byte positions.
- Add selected-policy replacement tests for numbered and named references,
  escapes, nonparticipating groups, invalid syntax, empty matches, and
  nonzero-offset windows.

### Implementation

- Add the representation-neutral sealed `Utf8Input` public type.
- Add its SafeRE-owned array-window implementation over the hardened PR #530
  scanner.
- Add a separate final `Utf8Matcher` with only the operations selected by the
  Trino inventory:

  - repeated `find()`;
  - `start()` and `end()` with numeric group overloads;
  - `groupCount()`.

- Add `Pattern.matcher(Utf8Input)`.
- Add capture-free `Pattern.find(Utf8Input)`.
- Add a raw array-window boolean convenience only if its benchmark shows that
  avoiding a view allocation matters in Trino.
- Return byte-relative bounds.  Do not expose a backing array or an allocating
  group method as the primary capture contract.
- Remove or make package-private the prototype's public
  `Pattern.matcher(byte[])` and `Matcher.groupBytes` API before release.
- Remove byte-mode fields and representation-dependent behavior from the
  JDK-compatible `Matcher`; share only the package-private matching core and
  result structures.
- Keep matcher reset deferred unless a benchmark and validated integration use
  it.
- Update crosscheck generation so the String API remains JDK-crosschecked and
  the SafeRE-only UTF-8 API is accounted for separately.
- Implement the selected replacement path completely but without premature
  optimization.  It may use a provisional SafeRE sink or adapter-level
  assembly from bounds, but it must support numbered and named references,
  escapes, nonparticipating groups, and the selected error behavior before the
  Trino checkpoint.  Add any minimal named-group lookup required by that
  selected shape rather than assuming numeric bounds are sufficient.

### Likely SafeRE files

- new `Utf8Input.java`
- new package-private array input implementation
- new `Utf8Matcher.java`
- `Pattern.java`
- `Matcher.java`
- `MatcherTransitionInventory.java`
- crosscheck `Pattern` and `Matcher` generation policy and tests

### Exit gate

- Public coordinate units and ownership are static and documented.
- `Matcher` no longer changes representation or return behavior based on
  construction history.
- Boolean matching can avoid public matcher and capture allocation.
- All supported SafeRE patterns work through the provisional UTF-8 matcher or
  route to a correct canonical engine.
- The API is still marked provisional and is not released.

## Stage 4: Early Trino Interface Substitution

### Objective

Validate the API shape against Trino before completing every optimization.

### Work

- Install the provisional SafeRE artifact locally.
- In the Trino validation branch, replace the `Re2JRegexp` wrapper's engine
  calls with `Utf8Input.trusted` and `Utf8Matcher`.
- Retain the original `Slice` and construct extraction and split results from
  SafeRE's relative bounds.
- Exercise the semantically complete, unoptimized replacement path selected in
  Stage 1.  Ordinary and lambda replacement may assemble output from repeated
  matches and bounds, but numbered and named references, escapes,
  nonparticipating groups, and errors must already follow the selected policy.
- Remove `io.trino:trino-re2j` from `core/trino-main` for this checkpoint.
- Compile Trino and run its complete RE2/J regex test surface.
- Add nonzero-offset `Slice`, multibyte capture, empty-match, and
  nonparticipating-group integration cases.
- Record every missing operation, unexpected allocation, and semantic
  difference in the compatibility report.
- Revise the provisional API only for principled, demonstrated requirements.

### Exit gate

- Trino can express every required regex operation through the provisional API
  and adapter code.
- Input matching and group extraction require no input-wide String conversion
  or byte copy.
- Ordinary and lambda replacement are semantically complete, including named
  and numbered references and error cases, even if output assembly is not yet
  optimized.
- All failures are classified; SafeRE bugs have regression tests and fixes.
- The public API shape is frozen for engine completion, subject only to an
  explicitly recorded blocker.

## Stage 5: Complete Engine And Accelerator Equivalence

### Objective

Turn PR #530's broad engine conversion into proof that every reachable engine
and accelerator has identical UTF-8 semantics.

### Work

- Extend `EnginePathEquivalenceTest`, `CrossEngineTest`, and exhaustive helpers
  with paired UTF-8 inputs and forced engine paths.
- Exercise DFA forward search, reverse bound discovery, anchored DFA, NFA,
  BitState, and OnePass independently.
- Verify deferred capture extraction in byte coordinates.
- Verify cache keys contain every representation-independent semantic
  dimension and never retain an input view.
- Implement UTF-8 literal and prefix search over precomputed UTF-8 literals, or
  guard those fast paths to a canonical engine until proven.
- Validate character-class acceleration, anchors, line boundaries, Unicode word
  boundaries, and grapheme boundaries.
- Include empty matches and malformed trusted input in engine equivalence.
- Profile before adding representation-specialized ASCII loops.
- Preserve PR #530's shared loops; do not create separate byte DFA/NFA engines.

### Exit gate

- Forced paths agree on match success, byte bounds, captures, and find traces.
- Unsupported optimized paths fall back correctly rather than approximating
  behavior.
- All work remains bounded or linear under existing engine policies.
- String engine results and benchmark gates remain green.

### Completion record

Completed locally in the Stage 5 branch. Deterministic forced-path coverage now
compares UTF-8 traces across OnePass, forward/reverse/anchored DFA, BitState,
Pike NFA, and eager/deferred capture extraction. The same coverage exercises
nonzero-offset input views, multibyte captures, empty-match scalar progress,
malformed trusted input, line and word boundaries, and grapheme boundaries.

String-only literal, character-class, keyword-alternation, and start
accelerators remain explicitly guarded by the absence of a String view. Tests
compare their enabled and disabled configurations on UTF-8 input to prove that
the guarded path falls back to the canonical engines. No byte-specialized
accelerator or ASCII loop was added, so profiling remains a prerequisite for
any future specialization rather than a justification invented after it.

The cache audit also found and fixed an input-retention risk in the reusable
BitState cache: input scanners and grapheme context are now cleared before the
state enters the Pattern-level cache. Reusing the same Pattern across distinct
array windows is covered to detect cache-key or input-view contamination.

## Stage 6: Finalize And Optimize Byte-Native Replacement

### Objective

Optimize and finalize the semantically complete replacement path exercised by
the early Trino substitution, without decoding the subject or publishing an
unprincipled second replacement dialect.

### Decision gate

Confirm the Stage 1 selection and Stage 4 evidence for one of two shapes:

1. publish the design's dependency-free `Utf8Sink` and SafeRE replacement
   expansion because Trino can use SafeRE's existing replacement semantics; or
2. keep expansion in the Trino adapter and expose only bounds plus the minimum
   range-output primitive because Trino's SQL contract differs.

Do not switch shapes merely for implementation convenience, and do not publish
both dialects.  If Stage 4 disproves the selected shape, update the compatibility
decision and its tests before continuing.

### Completion tests

- Literal replacement with ASCII and multibyte bytes.
- Numbered and named group references.
- Nonparticipating groups.
- Escapes and invalid replacement syntax.
- Empty matches and repeated replacement.
- Nonzero-offset input and replacement windows.
- Sink failure, callback mutation, and append-position state.
- Captures and unmatched ranges that cross future storage boundaries in the
  storage-neutral contract tests.

### Implementation

- Reuse the String replacement-template parser and group-reference rules.
- Compile a replacement template once per operation.
- Copy literal replacement bytes unchanged.
- Transfer unmatched and captured ranges directly from the original input.
- Keep sink types independent of Airlift.
- Preserve array transfer for Trino and buffer dispatch for future adapters if
  `Utf8Sink` is selected.
- Do not claim zero-copy replacement for an adapter whose sink path coalesces or
  copies storage.

### Exit gate

- Trino ordinary and lambda replacement tests pass without subject decoding.
- Replacement syntax and errors follow the selected, documented policy.
- Output allocation is explicit; no input-sized intermediate String exists.
- The unused public replacement alternative is not shipped.

### Completion record

Completed locally with the dependency-free `Utf8Sink` shape selected in Stage
1 and validated by Trino in Stage 4. SafeRE's existing replacement parser is
the only dialect: it is compiled once per matcher/replacement operation, and
literal fragments are encoded once and reused across repeated matches.
Unmatched ranges and participating captures are dispatched directly from the
original subject storage using relative byte bounds. Only the replacement
template is decoded for syntax parsing; the subject is never decoded or copied.

The completion matrix covers ASCII and multibyte literals, numbered and named
groups, nonparticipating groups, escapes and malformed syntax, repeated and
empty matches, source and replacement windows, sink failure and reentrancy,
append-position state, array identity, and array/direct-buffer sink dispatch.
The complete Trino regexp-function test class passes with the locally installed
artifact, including ordinary and lambda replacement.

Allocation profiling of the frozen literal, numbered, and named workloads
shows matcher/engine state, capture-bound arrays, and one-time template
compilation, with no subject-sized String or byte copy and no per-match literal
encoding. The standard benchmark harness records approximately 327 ns/op for
literal replacement, 768 ns/op for numbered groups, and 830 ns/op for named
groups on this branch. Broader matcher-allocation work is intentionally left to
the performance stage rather than being mixed into replacement finalization.

## Stage 7: Correctness, Fuzzing, And Linear-Time Completion

### Objective

Close the correctness and safety obligations that are broader than PR #530's
prototype tests.

### Work

- Complete the API contract, paired String oracle, engine equivalence,
  malformed-input, window, replacement, and Trino integration suites from the
  design.
- Add byte-oriented fuzz targets for arbitrary arrays, offsets, lengths, and
  patterns within SafeRE's supported regular language.
- Assert strict validator agreement, first-error offsets, monotonic cursor
  movement, bounded reverse decode, no out-of-window access, and termination.
- Add mutation tests that mark mutation as a documented precondition boundary,
  not supported concurrent behavior.
- Add work-counter scaling tests for hard failure, empty matches, captures,
  boundaries, graphemes, and malformed trusted input.
- Add allocation checks proving boolean search and repeated `find` do not
  create input-sized decoded objects.
- Run the whole SafeRE suite and affected crosscheck suites.

### Verification

```bash
mvn -pl safere test -q
```

Run additional repository fuzz-regression and crosscheck commands identified
by the final touched-file set.  Do not manually launch long fuzzing as a
substitute for deterministic regression coverage.

### Exit gate

- Every public UTF-8 transition is represented in the state-machine inventory.
- Every engine and malformed-input invariant has deterministic coverage.
- Scaling evidence supports the end-to-end linear-time guarantee.
- No known SafeRE bug from external validation remains unfixed.

### Completion record

Completed locally with deterministic coverage at each layer. The public
transition inventory covers every `Utf8Matcher` method; paired exhaustive tests
compare valid UTF-8 with the String API; forced paths cover every reachable
engine; and arbitrary-window fuzz regression exercises trusted and validated
construction, boolean search, repeated find, captures, bounds, and termination.

Strict validation is checked against the JDK reporting decoder across every
two-byte combination and deterministic arbitrary longer windows, including the
relative first-error offset. Scanner tests prove forward and reverse monotonic
recovery, bounded windows, and scalar boundaries. The borrowed-storage mutation
test makes the documented no-mutation precondition visible without promising
concurrent mutation behavior.

Work-counter scaling covers valid and malformed decoding, hard failure, empty
matches, captures, word boundaries, graphemes, and malformed trusted matching.
HotSpot allocation accounting compares inputs with an order-of-magnitude size
difference and proves that capture-free boolean search and repeated `find` do
not allocate input-sized decoded objects. The full SafeRE suite, UTF-8 fuzz
regression, affected crosscheck suite, Javadoc, and formatting gates pass. No
known SafeRE bug from the Trino validation remains open.

## Stage 8: Performance Completion

### Objective

Demonstrate that the generalized abstraction retains PR #530's benefit and
improves the real Trino path without regressing String matching.

### Work

- Evolve `ByteMatchingBenchmark` into the design's shared-data
  `Utf8MatchingBenchmark`, retaining useful PR #530 cases.
- Add capture-free search, repeated find, group bounds, split-style iteration,
  and the selected replacement path.
- Cover whole arrays and nonzero-offset windows, trusted and validated input,
  ASCII and multiple Unicode shapes, early/late/no match, and hard failure.
- Measure:

  - SafeRE on predecoded String;
  - SafeRE including UTF-8-to-String decoding;
  - PR #530 baseline where still comparable;
  - final trusted and validated UTF-8 paths;
  - Trino RE2/J on `Slice`;
  - unmodified Trino functions;
  - SafeRE-enabled Trino functions;
  - SafeRE String before and after the shared-input refactor.

- Report time, throughput, allocation, objects per operation, and the geometric
  means specified by the design.
- Profile CPU and allocation before optimizing scanner dispatch, decoding,
  literal search, matcher construction, captures, or replacement.
- Keep an optimization only when the relevant benchmark improves.

### Verification

Run benchmarks sequentially through `./run-java-benchmarks.sh`.  Use the
default configuration for routine evidence and `--long` for close, surprising,
or important results.  Run Trino benchmarks separately and record exact
revisions and configurations.

### Exit gate

- Trusted UTF-8 eliminates input-sized decode allocation.
- Final results satisfy the precommitted SafeRE String and Trino regression
  gates.
- Large inputs preserve linear scaling.
- Trino-level results confirm that adapter and output costs do not erase the
  matcher-level benefit.

### Completion record

Completed locally at SafeRE revision
`3d0b19358d0fe7a0c2ccb3a1cabd3f3684c94d26`. CPU profiling identified the
capture-free Pike NFA path as the short-search bottleneck. Unanchored supported
programs now use the cached DFA for boolean search, while end-anchored programs
retain the bounded-allocation NFA path. Fully literal patterns use a precomputed
UTF-8 KMP search, preserving linear time even for overlapping prefixes.

The shared `Utf8MatchingBenchmark` covers the frozen capture-free,
decode-inclusive, predecoded String, repeated-find, capture-bound, empty-match,
window, construction, and hard-failure workloads; byte-native replacement is
covered by `ByteReplacementBenchmark`. Across the six capture-free cases,
trusted byte search has a time geomean of approximately 0.61 relative to
predecoded String and allocates effectively zero bytes per operation. The
legacy email workload improved from approximately 5.18 microseconds to 115
nanoseconds before the literal specialization; the final frozen literal cases
range from approximately 6 to 18 nanoseconds.

The SafeRE String regression set remained within its gates. At Trino revision
`7ec953a0619`, the existing function benchmark, run with the SafeRE-backed
legacy `benchmarkLikeRe2J` method, measured literal-like, phone-like, and
replacement paths against Joni.
The three time ratios have a geometric mean of approximately 0.87. The
replacement no-match case is slower but allocates approximately 152 bytes per
operation rather than 2,384 bytes. Thus adapter and output costs do not erase
the aggregate benefit. Full Trino publication benchmarking remains a Stage 9
release gate.

## Stage 9: Final Trino Validation And Publication

### Objective

Prove the completed feature in its first consumer and publish only the API that
survived that validation.

### Work

- Reinstall the final SafeRE artifact.
- Repeat the Trino dependency substitution from a clean recorded revision.
- Remove `io.trino:trino-re2j` from the relevant module and run the full regex
  function, cast/type, extraction, split, position, count, replacement, and
  lambda replacement coverage.
- Run final Trino before/after benchmarks.
- Resolve every remaining compatibility-report entry.
- Update Trino configuration behavior for `regex-library`, DFA-state, and retry
  properties according to the selected migration policy.
- Update SafeRE API Javadocs, design cross-references, testing documentation,
  and benchmark documentation with exact verification and revisions.
- Update Trino function/configuration documentation, release notes,
  deprecations, and intentional SQL-contract tests in the validation branch.
- Remove provisional annotations or warnings only after all gates pass.

### Exit gate

- The Trino validation branch has no `io.trino:trino-re2j` dependency in the
  migrated path.
- All selected Trino tests and benchmarks pass at the recorded revisions.
- Every intentional semantic or configuration change is documented.
- No unsupported runtime operation remains on the public UTF-8 API.
- The PR description lists exactly what validation ran and did not run.

### Completion record

Completed locally at SafeRE revision
`90531d3e551bc04d470ef4b170c7a98d1a6b10d6` and Trino revision
`b16a460dab90042a9487cb75a53dce34ed78b8f8`. The final Trino branch removes
`io.trino:trino-re2j`, selects SafeRE explicitly with `SAFERE`, removes the
inapplicable RE2/J DFA properties, and uses SafeRE names for the migrated
internal type and function implementation.

The complete Trino regex function suite and the adjacent configuration, type,
and connector-expression tests pass. A full `trino-main` run was attempted but
could not complete in this environment because Docker-dependent OAuth tests
fail without Docker and an unrelated node-state poller remained alive after
those failures. This limitation is recorded rather than presented as a passing
full-module run.

The final deterministic Trino matrix passes the precommitted performance gate:
SafeRE / Trino RE2/J time has a 0.63 overall geometric mean, with 0.46 for
capture-free matching and 0.87 for replacement; every individual ratio is at
most 1.09. Profiling the initially failing matrix identified missing UTF-8
prefix acceleration in the capture-free path. The general fix adds bounded
linear-time literal, single-byte, and ASCII-prefix searches to both boolean and
stateful UTF-8 matching. It does not add a Trino semantic exception.

## Follow-Up Storage Adapters

Buffer, segmented, off-heap, and project-specific adapters are follow-up work,
not conditions for the first Trino release.  The phase-one API must not prevent
them.

For each adapter:

- start from a concrete integration in Spark, Flink, Druid, Pinot, Arrow, or
  another JVM consumer;
- add a SafeRE-owned storage-specialized input implementation rather than a
  caller-defined per-byte callback;
- define ownership, mutation, liveness, validation, reverse traversal, and sink
  dispatch;
- run the complete representation-independent UTF-8 contract and fuzz suites;
- test physical boundary cases such as buffer positions, segment crossings,
  empty segments, and released storage;
- run the owning project's regex suite;
- prove that its hot path no longer creates an input-sized Java String;
- benchmark against that project's existing decode-to-String path and verify
  that the array and String paths do not regress.

Do not add Spark unsafe base-object access, Flink memory-segment types, Airlift
`Slice`, or Arrow buffers to SafeRE core merely to make an adapter convenient.
Add stable general storage factories only after a real integration validates
their shape.

## Suggested Work Boundaries

The stages are organizational checkpoints within one experimental branch, not
an instruction to merge intermediate public APIs.  Use commits or a temporary
stack to make review manageable, but expect to revise and squash boundaries as
the Trino experiment exposes dependencies.

1. **PR #530 foundation:** shared scanners, engine migration, initial tests,
   and benchmark, retaining the existing contribution.
2. **Compatibility and baseline:** Trino feasibility report, frozen workloads,
   and baseline evidence; primarily documentation and harness work.
3. **Input hardening:** semantic scanner contract, windows, trusted recovery,
   strict validation, and decoder tests.
4. **Provisional API and complete replacement semantics:** `Utf8Input`,
   `Utf8Matcher`, capture-free search, matcher separation, state-machine tests,
   and the selected semantically complete but unoptimized replacement path.
5. **Early Trino adapter:** interface validation and resulting minimal API
   corrections.
6. **Engine completion:** forced-path equivalence, accelerators, graphemes,
   boundaries, fuzzing, and scaling.
7. **Replacement optimization and finalization:** optimize only the replacement
   shape already selected and exercised by the early Trino adapter.
8. **Performance and final integration:** benchmark evidence, final Trino
   substitution, documentation, and API publication.

The completed branch should be presented for merge only after every completion
checklist item is resolved.  Intermediate exit gates guide the work and expose
problems early; they are not separate release events.

## Completion Checklist

- [x] PR #530 commits and engine refactoring are retained as the foundation.
- [x] The PR #530 head and baseline are recorded.
- [x] The Trino semantic feasibility inventory is complete.
- [x] All compatibility decisions are principled, documented, and linear-time.
- [x] `Utf8Input` is representation-neutral and its first adapter supports
      trusted and validated array windows.
- [x] `Utf8Matcher` has static byte-coordinate and ownership contracts.
- [x] The JDK-compatible `Matcher` has no hidden public byte mode.
- [x] Captures can be consumed without copying or decoding the source.
- [x] Capture-free search avoids matcher and capture allocation.
- [x] Every reachable engine and accelerator is equivalent or safely guarded.
- [x] Trusted malformed input is bounded, monotonic, and safe.
- [x] Strict validation implements the documented RFC 3629 contract.
- [x] Replacement follows the single selected dialect and stays byte-native.
- [x] Differential, state-machine, fuzz, scaling, and allocation coverage pass.
- [x] Early and final Trino dependency substitutions pass their recorded tests.
- [x] SafeRE and Trino benchmark gates pass with recorded revisions.
- [x] Public API and Trino migration documentation are complete.
- [x] No provisional unsupported operation remains at publication.
