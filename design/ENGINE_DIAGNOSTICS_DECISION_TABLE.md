# Engine Diagnostics Decision Table

This table defines the stable diagnostics meaning for each public operation path. It is an
implementation inventory for issue #474, not a promise that the internal engine cascade will keep
the same branches.

| Public operation path | Authoritative boundary strategy | Capture strategy | Auxiliary participation | Representative decisions | Event scope |
|---|---|---|---|---|---|
| Literal `matches`, `lookingAt`, or `find` | `LITERAL` | `NONE` | none | disabled paths may report `OPTIMIZED_PATH_DISABLED` | one event per call |
| Character-class match/find fast path | `CHARACTER_CLASS` | `NONE` | optional reject prefilter | none | one event per call |
| Keyword-alternation find | `KEYWORD` | `KEYWORD` when it fills captures, otherwise `NONE` | none | none | one event per call |
| Anchored OnePass | `ONE_PASS` | `ONE_PASS` when captures exist | none | input/operation bypass where reported | one event per call |
| DFA rejection | `DFA` | `NONE` | `DFA/REJECT_PREFILTER` | budget fallback when applicable | one event per call |
| DFA authoritative group-zero bounds | `DFA` | `NONE` until deferred extraction | start acceleration and candidate verification in execution order | exact bounds or capture requirements where applicable | one event per call |
| DFA bounds plus deferred extraction | `DFA` | `ONE_PASS`, `BIT_STATE`, or `NFA` if extraction occurs before return | DFA prefilter/candidate verification | capture or budget fallback where applicable | one event per call; later `group()` emits no event |
| Nullable-loop DFA over-approximation rejects | `DFA` | `NONE` | `DFA/REJECT_PREFILTER` | exact nullable-loop semantics required for non-rejection | one event per call |
| BitState exact match | `BIT_STATE` | `BIT_STATE` when following authoritative DFA bounds | optional earlier DFA work | input-too-large or work-budget fallback only if BitState is bypassed | one event per call |
| Pike NFA exact match | `NFA` | `NFA` when following authoritative DFA bounds | optional earlier acceleration/DFA work | prior engine fallback reason | one event per call |
| Character-class replacement | `CHARACTER_CLASS` | `NONE` | `CHARACTER_CLASS/CANDIDATE_VERIFICATION` | none | one `REPLACE_FIRST` or `REPLACE_ALL` event with total match count |
| DFA replacement loop | `DFA` | `ONE_PASS`, `BIT_STATE`, or `NFA` when replacement references captures | start acceleration and DFA candidate verification | budget fallback where applicable | one replacement event with total match count |
| Replacement through ordinary find loop | strategy used by the authoritative internal finds | exact capture strategy if replacement observes captures | accumulated first participation order | accumulated bounded decisions | one replacement event; no nested `FIND` events |

Region and transparent-bound paths use the exact BitState/NFA selector when optimized engines cannot
provide the public contract. A public event is emitted only after a normal return. Listener failures
propagate after matcher state has been finalized; regex-operation failures do not emit a misleading
`MATCH` or `NO_MATCH` event.
