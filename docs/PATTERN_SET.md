# Multi-pattern matching

SafeRE includes `PatternSet`, a SafeRE-only feature that matches multiple
patterns using a shared automaton. Use it to classify text against a set of rules:

```java
import java.util.List;
import org.safere.PatternSet;

PatternSet.Builder builder = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
int id0 = builder.add("error.*timeout");
int id1 = builder.add("warning.*disk");
int id2 = builder.add("info.*startup");
PatternSet set = builder.compile();

List<Integer> matches = set.match("error: connection timeout");
// matches contains id0
```

`add` returns the pattern ID used in the result list. `match` returns matching
IDs; `matches` answers whether any pattern matches. `size` and `pattern(id)`
let callers inspect the compiled set. The set reports pattern membership,
not capture groups or individual match positions.

Choose an anchor mode when creating the builder:

| Mode | Meaning |
|---|---|
| `UNANCHORED` | A pattern may match anywhere in the input. |
| `ANCHOR_START` | A pattern must match from the beginning. |
| `ANCHOR_BOTH` | A pattern must match the entire input. |

The shared DFA normally checks the patterns in one pass. If its state budget
is exhausted, SafeRE falls back to checking individual patterns with the NFA.
Matching remains linear in input length for a fixed set of patterns.
