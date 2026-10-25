# Capture demand and engine selection

This audit addresses [issue #1067](https://github.com/eaftan/safere/issues/1067).
The baseline is `7c1b3833e9f75c68968a875524578589f06c13be`. The operation
matrix describes that baseline; the policy below describes the changes made
following the audit. Engine choices remain heuristics subject to the
[semantic invariants](INVARIANTS.md).

## State and ownership

Three facts need separate ownership:

| Fact | Owner and lifetime | Meaning |
|---|---|---|
| Current operation requires captures | Replacement invocation | A template references an inner group; this can justify capturing during an exact search even before the first result. |
| Captures have been consumed before | `Pattern.innerCapturesObserved` | Monotone, shared, volatile hint across matchers and threads using that compiled Pattern. It is not a promise about the next operation. |
| This result already has captures | `Matcher.capturesResolved`, `groupZeroResolved`, group bounds and deferred context | Correctness state for the current result, invalidated or preserved by matcher transitions independently of historical demand. |

The baseline also has `Matcher.eagerFallbackCaptures`, which mixes local history
and replacement prediction. It survives reset, input changes, region changes,
and bounds changes, but is cleared by `usePattern()`. Its lifetime differs
from the shared Pattern hint, and some accesses update only the local state.
The revised policy removes that second store of history. The matcher retains
only a scoped replacement requirement in addition to its current result.

Pattern history survives `reset()`, `reset(input)`, `find(start)`, region and
bounds changes. A fresh matcher sees the same history. `usePattern()` selects
the new Pattern's history, including history learned by its other matchers;
it neither clears that Pattern's hint nor transfers the old Pattern's hint.
Recompilation and deserialization create a new Pattern with no history.

## Baseline operation matrix

"Record" means historical inner-capture demand, not merely filling capture
registers. Group zero is the overall match; inner groups are numbered from one.

| Operation | Known requirement | Baseline recording | Baseline consumption |
|---|---|---|---|
| `find()` / `find(start)` | Match existence; continuation may need exact group-zero bounds | None, including internal resolution for continuation | Shared hint can select BitState on eligible inputs up to 512 scanner units, after rejection/start acceleration. Exact fallback uses local history only. DFA sandwich results may still defer captures. |
| `matches()` | Boolean full match | None | Prepared OnePass selection uses declared groups; core matching can use the shared small-input hint to avoid a DFA pass. Exact fallback already extracts all captures. |
| `lookingAt()` | Boolean anchored prefix match | None | Similar to `matches()`; anchored semantics and OnePass eligibility differ. Exact fallback already extracts all captures. |
| Numeric `start`, `end`, `group`, group zero | Overall match only | No demand; an unresolved group-zero result may nevertheless need exact extraction | Uses already published group-zero bounds when reliable. |
| Numeric inner-group `start`, `end`, `group` | Requested capture | Deferred result updates Pattern and local history, unless local history was already set; resolved result updates local history only | Resolves the current result once if necessary. |
| Named `start`, `end`, `group` | Requested capture | Maps the name to an index, then follows numeric access | Same as numeric inner-group access. |
| `toMatchResult()` | Independent snapshot containing every capture | Deferred inner captures update both hints, unless local history was already set; other cases set local history only, even without a successful match or any inner groups | Resolves a successful result before copying; snapshot getters do not update a Pattern. |
| `results()` | Complete snapshot for every emitted result | Through `toMatchResult()` when a result is emitted | Stream creation alone has no demand; stream traversal finds then snapshots. |
| String `appendReplacement` | Template references | Compiled numeric template records demand only if extraction is deferred; named references also go through group access | Template application resolves inner captures only if referenced. |
| String `replaceFirst` / `replaceAll`, literal or character-class fast path | Template references | Group-zero-only templates record no Pattern demand | Fast paths use their own semantic eligibility and preserve replacement state. Inner-reference templates leave these fast paths. |
| String anchored OnePass replacement | Template requirement is available before matching | Numeric template references can miss shared history because OnePass already filled the captures | Template requirement selects the OnePass input limit; captures are obtained in the search. |
| String DFA replacement loop | Template requirement, parsed after finding the first match | Inner references explicitly update Pattern history | Keeps DFA search/acceleration; uses OnePass or exact extraction for captures. Historical demand does not replace this producer. |
| String replacement fallback | Template requirement | Any `$` sets local history before `find()`, including `$0` and escaped dollars; after success the template may update Pattern history | This local prediction affects exact fallback; a literal-only operation can inherit local history from unrelated prior accesses. |
| UTF-8 `appendReplacement` | Cached, parsed template references | Inner references read all capture bounds through the delegate's numeric access; thus already resolved captures can miss Pattern history | Literal/group-zero templates read only group-zero bounds. This preserves the improvement from PR #1057. |
| Functional `replaceFirst` / `replaceAll` | Callback receives a complete snapshot; returned template is unknown until callback runs | Through `toMatchResult()` and then template application | Callback may read any group, even when its returned template is literal. |
| Reset, regions, bounds, pattern changes, continuation bookkeeping | Preserve or invalidate current state according to the API | No direct demand; may resolve pending captures internally | History lifetime and deferred-result lifetime are independent. |

Sources: [Matcher](../safere/src/main/java/org/safere/Matcher.java),
[Pattern](../safere/src/main/java/org/safere/Pattern.java), and
[Utf8Matcher](../safere/src/main/java/org/safere/Utf8Matcher.java).

## Confirmed gaps

1. **Consumption depends on result availability.** Reading an eager capture,
   snapshotting eager captures, or applying an anchored replacement can leave
   the shared hint unset. Setting the local hint first can also suppress a
   later update of the Pattern hint. Internal extraction during a bounds change
   exposes the same gap when the caller subsequently reads a group.
2. **Fresh exact fallback searches ignore shared history.** Above the
   small-input adaptation limit, a mandatory exact search may compute only
   group zero and repeat extraction immediately afterward. A new matcher pays
   this cost even when the Pattern has known capture consumers. PR #1066
   independently addresses this fallback decision and BitState workspace
   growth; this audit does not require its workspace change.
3. **Replacement syntax is mistaken for inner demand.** The fallback's `$`
   check predicts demand for `$0` and escaped dollars and retains that prediction
   after the replacement returns. It can also retain a requirement after an
   unsuccessful replacement. A no-match snapshot similarly sets unnecessary
   local history.

These are adaptive policy gaps. The audit found no need to change the selected
match, capture values, public exception contract, or intentional JDK
compatibility boundaries.

## Revised policy

Record successful inner-capture consumption regardless of whether extraction
was necessary. Numeric and named group access, complete successful snapshots,
and template application use one recording helper. Group zero, invalid group
access, empty/failed snapshots, and internal result preservation do not record
inner demand. Producing captures as a side effect of an engine search does not
itself record consumption.

Consume shared history when an exact fallback search is already necessary.
Track all groups in that search if history or the current replacement requires
them. With neither requirement, preserve lazy group-zero-only fallback for
eligible patterns. This reuses the existing exact search without adding a
search or bypassing a successful DFA rejection.

For String fallback replacement, use the existing nonthrowing template-demand
scan before matching and scope that requirement to the replacement invocation.
The scan understands escaping and numeric group-zero references. It selects
capture storage, not historical demand, and does not validate a replacement
before a match exists. Restore the previous requirement in `finally`, including
on failure. Template application records actual capture consumption after a
successful match.

A broader planner for all matching and replacement operations is unnecessary.
The shared hint and the scoped requirement answer different questions; result
flags retain their correctness role. The helper removes duplicated recording
logic without merging engine capability checks or replacement producers.

## Deliberate differences retained

| Path | Reason to keep its behavior |
|---|---|
| Rejection, end-anchor probes and start acceleration | Historical use predicts possible future consumption; it does not prove a match. Keep useful rejection and accelerated scanning ahead of speculative capture work. |
| Small-input adaptation | The existing 512-unit bound limits direct BitState preference. It additionally requires enabled BitState, sufficient workspace budget, no grapheme semantics, and no full-text region context. |
| Accelerated `find()` | Speculate at only one preselected start; if it fails, resume the DFA search. Do not walk all remaining starts with the capture engine because of history. |
| Large-input DFA sandwich | Retain DFA scanning and deferred extraction even with history. Avoiding a replay alone does not establish that replacing the producer is faster. |
| Prepared OnePass | Standard operations use the pattern's declared groups for the 256/65,536-unit limit; anchored replacement can use its known template requirements. These are separate measured heuristics, not correctness rules or missing history propagation. |
| Region and grapheme fallback | Keep eager extraction where changing context or unsupported DFA semantics requires it. Defer only with the original match context preserved. |
| Disabled engine paths | Engine options still determine eligibility. History cannot re-enable DFA, OnePass, BitState, or lazy extraction. |
| String versus UTF-8 | Both use the same delegate policy. Scanner sizes are UTF-16 units for String and bytes for UTF-8. The public UTF-8 API has byte bounds and append-style replacement, without named group getters, snapshot streams, or functional replacement. |
| Manual append replacement | Its template arrives after `find()`, so it cannot inform that earlier search. UTF-8 template caching informs subsequent template application; no new API is needed. |
| Functional replacement | Its snapshot promises all captures, so capturing is justified even if the callback ultimately reads only group zero or returns a literal. |

No path adds recursive traversal or matching retries. Historical state is a
single Pattern boolean; the replacement requirement is one matcher boolean
restored after the operation. Capture extraction uses the existing bounded
passes and engines. Fixed-pattern matching remains linear in input length.

## Verification and measurements

[CaptureDemandTest](../safere/src/test/java/org/safere/CaptureDemandTest.java)
covers eager and deferred numeric/named access, snapshots, streams, functional
replacement, String/UTF-8 template consumption, unnecessary group-zero demand,
fresh fallback matchers, and state lifetime. Existing deferred-state, engine
path, diagnostics, replacement, and scaling suites protect result semantics and
rejection/acceleration independently of the hint.

`CaptureDemandBenchmark` declarations in
[benchmark-data.json](../safere-benchmarks/benchmark-data.json) use the generic
`findSampledGroup` operation. Each invocation searches once and optionally reads
a group's start. The sampling interval distinguishes consuming every result
from mixed boolean/capture use. A separate successful full-match primer
models historical demand from already resolved captures; group-zero and
boolean controls do not prime inner history. Retained matchers reset between
invocations; fresh matchers share their compiled Pattern. Input construction
and UTF-8 encoding occur outside timed work.

The matrix includes a nullable-loop pattern requiring exact fallback and a
log pattern with accelerated candidates and rejection. Both cover early/late
matches, rejection, small/large inputs, both matcher lifetimes, and both
representations. The focused comparison uses the project runner's standard
settings, with longer confirmation for important or surprising differences.
Raw measurements and profiles stay local, as required for focused changes;
this audit is not a corpus-wide benchmark report.

### Measured outcome and policy decision

The standard matrix pairs the declared SafeRE String/UTF-8 trials using the
default scanner provider; the separate Vector variant is excluded. Its
per-family, per-usage, per-outcome geometric means give equal weight to both
sizes, matcher lifetimes, and representations. Boolean and group-zero buckets
range from 0.948 to 1.019 times baseline time; this does not establish that
every individual control is unchanged. The largest repeatable improvement is
fresh-matcher capture consumption on an early exact-fallback match.

Long confirmation uses two forks, three one-second warmups and five one-second
measurements per fork, on OpenJDK 26.0.2 and an Intel Core i7-11700K under WSL2.
The baseline production commit is the one identified above; benchmark sources,
workload definitions, and runner settings are identical on both sides.
Values below are ns/op with JMH's 99.9% confidence intervals. The nullable
cases use 1,024-unit inputs; accelerated rejection uses 512-unit inputs.
All matrix rows in this table use fresh matchers. The existing date-capture
control also creates a fresh matcher per invocation.

| Workload | Baseline | Revised policy | Time change |
|---|---:|---:|---:|
| Early captures, String | 4,106.7 ± 71.9 | 787.5 ± 46.8 | -80.8% |
| Early captures, UTF-8 | 4,052.2 ± 79.4 | 820.6 ± 82.4 | -79.7% |
| Early mixed use, String | 1,548.7 ± 11.7 | 771.0 ± 19.5 | -50.2% |
| Early mixed use, UTF-8 | 1,629.5 ± 46.3 | 804.7 ± 84.7 | -50.6% |
| Accelerated rejection after capture use, String | 648.5 ± 6.6 | 746.3 ± 13.7 | +15.1% |
| Accelerated rejection after capture use, UTF-8 | 849.1 ± 12.1 | 939.5 ± 12.1 | +10.6% |
| Late boolean search, String | 88,786.0 ± 1,322.7 | 92,101.2 ± 1,011.5 | +3.7% |
| Late boolean search, UTF-8 | 89,631.7 ± 1,038.8 | 91,857.1 ± 4,079.5 | +2.5% |
| Existing date-capture control, String | 83.2 ± 3.0 | 83.7 ± 0.9 | +0.6% |

The tradeoff is explicit: propagating previously missed eager capture use also
activates the existing small-input speculation on rejection. The diagnostic
regression verifies that a failed accelerated speculation still reaches DFA
rejection for String and UTF-8. Keep that bounded heuristic and its existing
threshold; the capture wins do not justify extending it to large inputs or
removing the DFA producer. The late boolean control is slightly slower in the
long run, and the standard matrix shows no consistent boolean trend; these
measurements do not support a general boolean-speedup claim.

The before/after JFR and source trace connect the early capture improvement
to avoiding deferred extraction and repeated BitState workspace construction.
No BitState allocation policy was changed here. The audit therefore supports
uniform recording and reuse of mandatory exact-search work, while retaining
operation-specific producers and documenting the rejection cost.
