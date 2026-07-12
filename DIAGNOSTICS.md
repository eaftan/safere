# SafeRE Diagnostics

SafeRE provides two complementary diagnostics APIs:

- **Static analysis** describes a compiled pattern's features, possible execution strategies, and
  known limitations without matching an input.
- **Runtime diagnostics** reports which strategies actually participated in a public matching or
  replacement operation.

Static analysis answers “what can this pattern do?” Runtime diagnostics answers “what happened for
this operation and input?” Engine selection can depend on the operation, input length, match result,
regions and bounds, captures, and runtime engine budgets, so static capabilities do not predict one
specific strategy for every call.

## Static pattern analysis

Call `Pattern.analysis()` on a compiled pattern:

```java
import org.safere.Pattern;
import org.safere.PatternAnalysis;
import org.safere.PatternCapability;
import org.safere.PatternFeature;
import org.safere.PatternLimitation;

Pattern pattern = Pattern.compile("^(?:(a)?)*$");
PatternAnalysis analysis = pattern.analysis();

if (analysis.features().contains(PatternFeature.CAPTURES_IN_QUANTIFIER)) {
  System.out.println("The pattern captures inside a quantified expression");
}

if (analysis.capabilities().contains(PatternCapability.DFA_REJECT_PREFILTER)) {
  System.out.println("A DFA may reject non-matches before an exact engine runs");
}

if (analysis.limitations().contains(PatternLimitation.NULLABLE_LOOP_REQUIRES_EXACT_ENGINE)) {
  System.out.println("Successful matches require exact nullable-loop semantics");
}

System.out.printf(
    "program instructions=%d, capturing groups=%d%n",
    analysis.programSize(), analysis.captureCount());
```

`PatternAnalysis` is immutable and contains:

| Field | Meaning |
|---|---|
| `features()` | Semantic and structural properties such as literals, captures, alternation, anchors, nullable expressions, and grapheme operations. |
| `capabilities()` | Strategies that can participate in at least one operation or input for this pattern. |
| `limitations()` | Stable reasons certain optimized strategies cannot be authoritative. |
| `programSize()` | Size of the compiled SafeRE instruction program. |
| `captureCount()` | Number of user capturing groups, excluding group 0. |

The returned sets and analysis object are safe to retain and share between threads. Complete
analysis is computed lazily when explicitly requested or when an enabled listener needs a
compilation event, then cached on the `Pattern`. Repeated `analysis()` calls return the cached
immutable result.

Capabilities are possibilities, not execution promises. For example, a pattern can support both
DFA boundary search and NFA matching. A long search might use a DFA to discover group 0 boundaries,
while a capture access or a semantically ambiguous case can require an exact engine.

## Runtime diagnostics

Runtime diagnostics use one process-wide synchronous listener. Subclass `SafeReMatchDiagnostics`,
install it with `Pattern.setDiagnostics`, and restore the previous listener when the observation
scope ends:

```java
import org.safere.OperationDiagnostics;
import org.safere.Pattern;
import org.safere.PatternCompiledEvent;
import org.safere.SafeReMatchDiagnostics;

SafeReMatchDiagnostics listener =
    new SafeReMatchDiagnostics() {
      @Override
      public void onPatternCompiled(PatternCompiledEvent event) {
        System.out.printf(
            "compiled patternId=%d capabilities=%s%n",
            event.pattern().patternId(),
            event.pattern().analysis().capabilities());
      }

      @Override
      public void onOperationCompleted(OperationDiagnostics event) {
        System.out.printf(
            "%s outcome=%s boundary=%s captures=%s/%s matches=%d%n",
            event.operation(),
            event.outcome(),
            event.boundaryStrategy(),
            event.captureStrategy(),
            event.captureMode(),
            event.matchCount());
      }
    };

SafeReMatchDiagnostics previous = Pattern.diagnostics();
Pattern.setDiagnostics(listener);
try {
  Pattern pattern = Pattern.compile("(a+)b");
  pattern.matcher("xxaaab").find();
} finally {
  Pattern.setDiagnostics(previous);
}
```

Use `SafeReMatchDiagnostics.NONE` to disable runtime diagnostics explicitly:

```java
Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
```

The listener is process-wide rather than attached to one pattern or matcher. Replacing it is
immediately visible across threads. Each public operation snapshots the listener once, so a
listener change does not split one operation between two listeners. Applications and libraries
sharing a process should coordinate listener ownership rather than independently replacing it.

### Compilation events

`onPatternCompiled` runs after a pattern is compiled while the listener is installed. Its
`PatternDescriptor` contains:

- `patternId()`: an opaque, positive, process-local identity;
- `analysis()`: the same cached immutable analysis returned by `Pattern.analysis()`.

The descriptor intentionally contains no pattern text. Use `patternId` to join compilation metadata
to later operation events without exposing a regex or using it as a high-cardinality metric label.

A listener installed after a pattern was compiled does not receive a historical compilation event.
Operation events still carry the pattern descriptor and analysis, so late listeners do not depend
on replay.

### Operation events

`onOperationCompleted` receives one immutable `OperationDiagnostics` event after a normally
completed supported public operation:

- `matches()`
- `lookingAt()`
- `find()` and `find(int)`
- string and functional `replaceFirst()`
- string and functional `replaceAll()`

Internal searches performed by replacement methods do not emit nested `FIND` events. A replacement
emits one event whose `matchCount()` is the total number of replaced matches. An operation that
throws does not emit an event. If the listener throws, its exception propagates after the matcher
state and regex result have been finalized.

| Field | Meaning |
|---|---|
| `pattern()` | Opaque pattern descriptor and static analysis. |
| `operation()` | Public operation being summarized. |
| `outcome()` | `MATCH` when one or more matches were found; otherwise `NO_MATCH`. |
| `boundaryStrategy()` | Strategy authoritative for the reported group 0 match boundaries or rejection. |
| `captureStrategy()` | Strategy that resolved capturing groups, or `NONE`. |
| `auxiliaryStrategies()` | Ordered first participation by strategies used for acceleration, rejection, or candidate verification. |
| `strategyDecisions()` | Bounded explanations for bypasses and fallbacks. |
| `captureMode()` | Whether captures were absent, resolved eagerly, or left deferred. |
| `inputLength()` | Input length using Java `String.length()` semantics (UTF-16 code units). |
| `matchCount()` | Zero or one for single-result operations; aggregate count for replacements. |

Events contain neither regex text nor input text. They are immutable and safe for a listener to
retain, although retaining every event can itself create substantial memory and cardinality costs.

### Interpreting strategies

One operation can involve more than one engine. Do not describe an event using only the first
strategy that appears.

```java
OperationDiagnostics event = /* received by the listener */;

System.out.println("authoritative boundaries: " + event.boundaryStrategy());
System.out.println("capture extraction: " + event.captureStrategy());

event.auxiliaryStrategies()
    .forEach(
        participation ->
            System.out.printf(
                "auxiliary %s as %s%n",
                participation.strategy(), participation.role()));

event.strategyDecisions()
    .forEach(
        decision ->
            System.out.printf(
                "%s was %s because %s%n",
                decision.strategy(), decision.disposition(), decision.reason()));
```

Typical examples include:

- a literal or character-class strategy performing start acceleration before DFA candidate
  verification;
- a DFA authoritatively rejecting a non-match before an exact engine is needed;
- a DFA finding group 0 boundaries while captures remain deferred;
- a DFA exceeding its state budget and falling back to BitState or NFA;
- BitState being bypassed for a large input before NFA matching.

`MatchStrategy` deliberately exposes stable strategy-level concepts rather than every internal
pass. Reverse DFA work, cache layout, and other implementation details are not separate public
strategies.

### Thread-safe aggregation

Callbacks execute synchronously on the thread compiling or matching. SafeRE does not serialize
listener calls. Listener implementations must provide their own thread safety and should avoid
blocking the matching thread.

For low-cardinality aggregate metrics, use thread-safe counters and stable enum keys:

```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import org.safere.MatchStrategy;
import org.safere.OperationDiagnostics;
import org.safere.SafeReMatchDiagnostics;

final class AggregatingDiagnostics extends SafeReMatchDiagnostics {
  private final ConcurrentMap<MatchStrategy, LongAdder> operationsByBoundary =
      new ConcurrentHashMap<>();
  private final LongAdder matchedResults = new LongAdder();

  @Override
  public void onOperationCompleted(OperationDiagnostics event) {
    operationsByBoundary
        .computeIfAbsent(event.boundaryStrategy(), ignored -> new LongAdder())
        .increment();
    matchedResults.add(event.matchCount());
  }
}
```

Avoid using `patternId`, input length, or full event values as unbounded metric labels. Prefer
aggregating by enums such as operation, outcome, strategy, role, disposition, and reason.

## Performance guidance

Runtime diagnostics are disabled by default. With `SafeReMatchDiagnostics.NONE`, the disabled path
does not allocate diagnostic events or bookkeeping objects and has no statistically convincing
material throughput regression in the production benchmark matrix.

Enabled diagnostics construct bounded immutable state once per public operation. The measured fixed
cost is approximately 150–200 ns per operation. This is significant for tiny literal operations and
becomes a small fraction of longer DFA or NFA work. Enable runtime diagnostics for targeted
observation, controlled profiling windows, or workloads where that fixed cost is acceptable.

Static analysis has a different cost model. The first explicit `analysis()` request performs and
caches the analysis. Cached retrieval is effectively a field access, and matching does not perform
complete analysis merely because the static API exists.

Detailed benchmark methodology and results are in
[`design/ISSUE_474_PHASE_7_PERFORMANCE.md`](design/ISSUE_474_PHASE_7_PERFORMANCE.md).
