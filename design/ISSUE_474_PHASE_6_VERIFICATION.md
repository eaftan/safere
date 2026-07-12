# Issue 474 Phase 6 Verification

Phase 6 is complete as of 2026-07-12. This report records the verification evidence without test
counts, which would become stale as the suite evolves.

## Verification layers

### Selection and diagnostics tests

`DiagnosticsTest` covers:

- static feature, capability, and limitation classification;
- listener registration after compilation, replacement, reset, volatile cross-thread visibility,
  and one listener snapshot per operation;
- opaque pattern identity and the absence of pattern/input text in events;
- literal, character-class, keyword, OnePass, DFA, BitState, and NFA routing;
- separate boundary and capture strategies, including deferred capture extraction;
- DFA candidate verification and authoritative reject-prefilter behavior;
- DFA state-budget exhaustion followed by an exact-engine fallback, compared with the JDK oracle;
- nullable-loop and grapheme cases that require an exact engine;
- literal-prefix candidate failure followed by DFA continuation;
- small and large input routing, including the BitState input-size decision;
- region and anchoring-bounds behavior on a forced exact path;
- replacement fast paths, ordinary replacement loops, functional replacements, and aggregate match
  counts with one event per public operation;
- listener exception behavior after matcher state finalization; and
- concurrent listener aggregation using `LongAdder`.

### Engine conformance and unsupported paths

`EnginePathEquivalenceTest` remains the engine-conformance layer. It forces optimized and exact
paths and compares their observable traces with the canonical engine. Its internal matrix includes
OnePass, forward DFA, reverse DFA, BitState, NFA, replacement paths, lazy capture extraction,
nullable loops, ambiguous reverse starts, and boundary-sensitive cases. Reverse DFA remains an
internal path and is intentionally not exposed as a public `MatchStrategy`.

`DfaTest` independently verifies the DFA state-budget bailout contract. `DiagnosticsTest` verifies
that the same bailout is surfaced by a public operation as `DFA_BUDGET_EXCEEDED` and that fallback
preserves the JDK result.

## Commands run

The following commands completed successfully and were run serially:

```bash
mvn -pl safere spotless:check -q
mvn -pl safere -Dtest=DiagnosticsTest test -q
mvn -pl safere test -q
mvn verify -pl safere,safere-crosscheck -am -Pcrosscheck-public-api-tests -q
```

After adding the final DFA-budget diagnostics case, the focused diagnostics command was rerun and
passed. The added change was test-only; the implementation covered by the full suite and public
crosscheck did not change afterward.

## Disabled-path allocation check

A standalone JFR probe warmed a literal match, installed `SafeReMatchDiagnostics.NONE`, and then
recorded allocation events while repeatedly creating matchers and calling `matches()`. The
recording contained no allocations of `DiagnosticAccumulator`, `OperationDiagnostics`,
`PatternCompiledEvent`, `StrategyDecision`, or `StrategyParticipation`.

The probe artifacts are intentionally outside the repository:

- `/tmp/safere-disabled-diagnostics.jfr`
- `/tmp/safere-disabled-diagnostics-allocations.txt`

Production-mode throughput and enabled-listener overhead remain Phase 7 work.
