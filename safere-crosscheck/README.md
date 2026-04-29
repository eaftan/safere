# safere-crosscheck

A differential-testing facade that runs both SafeRE and `java.util.regex` on
every operation, comparing results and throwing an exception if they diverge.

## Purpose

SafeRE aims to be a drop-in replacement for `java.util.regex`. The crosscheck
module validates this by acting as a transparent wrapper: every `Pattern` and
`Matcher` call is executed on **both** engines, and results are compared
automatically. This catches behavioral divergences that aren't directly covered
by unit tests.

Every API call is recorded by a trace recorder. When a divergence is detected,
the exception includes the full trace, making it easy to reproduce and report
bugs.

## Quick Start

Just change your import — the API is identical to `org.safere.Pattern` and
`org.safere.Matcher`:

```java
// Before (SafeRE direct)
import org.safere.Pattern;
import org.safere.Matcher;

// After (crosscheck mode)
import org.safere.crosscheck.Pattern;
import org.safere.crosscheck.Matcher;
```

Then use the API exactly as before:

```java
Pattern p = Pattern.compile("(\\w+)@(\\w+)");
Matcher m = p.matcher("user@host");
if (m.find()) {
    System.out.println(m.group(1)); // "user"
    System.out.println(m.group(2)); // "host"
}
```

Every call to `find()`, `group()`, etc. is silently crosschecked against
`java.util.regex`. If both engines agree, SafeRE's result is returned. If
they disagree, a `CrosscheckException` is thrown with details and a full trace.

## Installation

Add the dependency alongside SafeRE:

```xml
<dependency>
  <groupId>org.safere</groupId>
  <artifactId>safere-crosscheck</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
```

## Exception Types

### CrosscheckException

Thrown when SafeRE and `java.util.regex` produce different results. Includes:

- The method name and arguments that diverged
- Both engines' results
- A formatted API call trace

```
CrosscheckException: Crosscheck divergence in find():
  SafeRE: true
  JDK:    false

API call trace (3 calls):
  [1] matches() → false
  [2] reset() → void
  [3] find() → DIVERGENCE
        SafeRE: true
        JDK:    false
```

### UnsupportedPatternException

Thrown when SafeRE rejects a pattern that `java.util.regex` accepts (e.g.,
backreferences, lookahead). This is expected behavior, not a bug — these
features violate SafeRE's linear-time guarantee. Extends
`PatternSyntaxException`.

## Trace Recording

Every `Matcher` records a trace of all API calls. The trace is automatically
included in any `CrosscheckException`, but you can also access it directly:

```java
Matcher m = Pattern.compile("\\d+").matcher("abc123");
m.find();
m.group();

// Print the trace
System.out.println(m.getTrace().format());

// Inspect individual entries
for (var entry : m.getTrace().getEntries()) {
    System.out.printf("%s(%s) → %s%n", entry.method(), entry.args(), entry.safereResult());
}
```

## Covered API

The crosscheck facade covers:

| Category | Methods |
|---|---|
| **Compilation** | `Pattern.compile()`, `Pattern.matches()`, `Pattern.quote()` |
| **Matching** | `Matcher.matches()`, `lookingAt()`, `find()`, `find(int)` |
| **Groups** | `group()`, `group(int)`, `group(String)`, `start()`, `end()`, `groupCount()` |
| **Replace** | `replaceAll(String)`, `replaceFirst(String)`, `appendReplacement()`, `appendTail()` |
| **Split** | `Pattern.split()`, `Pattern.splitWithDelimiters()` |
| **State** | `reset()`, `region()`, `useTransparentBounds()`, `useAnchoringBounds()` |
| **Other** | `hitEnd()`, `requireEnd()`, `toMatchResult()`, `namedGroups()` |

## Crosschecking SafeRE Tests

The `crosscheck-public-api-tests` Maven profile runs generated copies of SafeRE
public API test candidates through the crosscheck facade:

```bash
mvn -pl safere-crosscheck -Pcrosscheck-public-api-tests test
```

The generated sources are not checked in. The profile copies broad public API
test candidates from `safere/src/test/java/org/safere`, rewrites them into a
generated package, and imports `org.safere.crosscheck.Pattern` and
`org.safere.crosscheck.Matcher`. This keeps one source of truth for the test
logic while making JDK compatibility an executable invariant.

Use `@DisabledForCrosscheck("reason")` in the original SafeRE test source for
test methods or classes that should be disabled only in generated crosscheck
coverage. The annotation is backed by a JUnit execution condition and is active
only when the generated crosscheck profile sets
`org.safere.crosscheck.generatedTests=true`, so the gap is visible in test
reports. Use issue references in the reason for fixable SafeRE/JDK divergences,
and plain reasons for tests that are intentionally not relevant to crosscheck,
such as SafeRE-only syntax or JDK stack-overflow stress cases.

The profile still excludes source files that are structurally not crosscheck
candidates, such as SafeRE internals, SafeRE-only APIs, or tests requiring
crosscheck facade methods that are not implemented yet.

### Not Covered (yet)

- Stream APIs: `splitAsStream()`, `results()`, `asPredicate()`,
  `asMatchPredicate()` — these are thin wrappers and can be added later.
- `replaceAll(Function)` / `replaceFirst(Function)` — the replacer function
  receives a `MatchResult` from a single engine, making crosscheck non-trivial.

## Limitations

- **Performance:** Every operation runs twice (once per engine). This is
  intended for testing, not production.
- **Thread safety:** Like `java.util.regex.Matcher`, the crosscheck `Matcher`
  is not thread-safe.
- **Unsupported features:** Patterns using backreferences, lookahead,
  lookbehind, or possessive quantifiers will throw
  `UnsupportedPatternException` at compile time.

## Benchmarking

Crosscheck overhead is measured by `CrosscheckOverheadBenchmark` in the
`safere-benchmarks` module. It is excluded from the default no-argument Java
benchmark run because it is a diagnostic benchmark for optimizing
`safere-crosscheck`, not part of the normal SafeRE performance suite.

Run it explicitly when working on crosscheck performance:

```bash
./run-java-benchmarks.sh --quick CrosscheckOverheadBenchmark
```
