# safere-crosscheck

A differential-testing facade that runs both SafeRE and `java.util.regex` on
covered operations, comparing results and throwing an exception if they diverge.

## Purpose

SafeRE aims for `java.util.regex` compatibility within its linear-time contract.
The crosscheck module compares supported `Pattern` and `Matcher` operations
against the JDK and records matcher calls for diagnosing divergences. Some
methods delegate without comparison; see the coverage notes below.

## Quick Start

For covered String APIs, replace the imports:

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

Build and install the facade from the same checkout as the library:

```bash
mvn -pl safere-crosscheck -am install -DskipTests
```

The release workflow publishes the core library. For an external test project,
use the locally installed facade and set `safere.version` to the checkout's POM
version:

```xml
<dependency>
  <groupId>org.safere</groupId>
  <artifactId>safere-crosscheck</artifactId>
  <version>${safere.version}</version>
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
backreferences or lookahead). Rejection is expected for documented unsupported
syntax; rejection of supported syntax still needs investigation. Extends
`PatternSyntaxException`.

## Trace Recording

Each `Matcher` records traced operations and comparison results. The trace is automatically
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
| **Compilation** | `Pattern.compile()`, `Pattern.matches()` |
| **Matching** | `Matcher.matches()`, `lookingAt()`, `find()`, `find(int)` |
| **Groups** | `group()`, `group(int)`, `group(String)`, `start()`, `end()`, `groupCount()` |
| **Replace** | `replaceAll(String/Function)`, `replaceFirst(String/Function)`, `appendReplacement()`, `appendTail()` |
| **Split** | `Pattern.split()`, `Pattern.splitWithDelimiters()` |
| **State** | `reset()`, `region()`, `usePattern()`, `useTransparentBounds()`, `useAnchoringBounds()` |
| **Other** | `results()`, `namedGroups()`, `asPredicate()`, `asMatchPredicate()` |

`Matcher.hitEnd()` and `Matcher.requireEnd()` are intentionally not covered
because SafeRE does not support those APIs.

## Crosschecking SafeRE Tests

The `crosscheck-public-api-tests` Maven profile runs generated copies of SafeRE
public API test candidates through the crosscheck facade:

```bash
mvn verify -pl safere,safere-crosscheck -am -Pcrosscheck-public-api-tests
```

The generated sources are not checked in. The profile copies broad public API
test candidates from `safere/src/test/java/org/safere`, rewrites them into a
generated package, and imports `org.safere.crosscheck.Pattern` and
`org.safere.crosscheck.Matcher`. This keeps one source of truth for the test
logic while making JDK compatibility an executable invariant.

This single Maven reactor run tests the `safere` module once, then runs the
generated crosscheck tests in `safere-crosscheck`. The `-am` flag tells Maven to
also build required reactor dependencies, including the local `safere` module.
Without it, Maven can resolve `safere` from the local repository and
accidentally test against a stale installed artifact.

Use `@DisabledForCrosscheck("reason")` in the original SafeRE test source. On a
top-level test class, it excludes the entire source file from generation; these
classes do not appear as skipped in generated reports. On a method or nested
class, it disables only that element during generated crosscheck execution.
Original SafeRE tests remain enabled. There is no separate filename exclusion list.

Generation runs a complete, explicit annotation-processing-only pass over the
original test sources. The processor uses standard Java annotation processing
APIs and writes a fresh exclusion manifest on every generation run. It does not
run during normal SafeRE test compilation. SafeRE implementation classes and test
helpers must be available on the processing classpath; use the reactor command above.

### Delegated methods and callback behavior

`Pattern.quote()`, `splitAsStream()`, and `Matcher.toMatchResult()` delegate to
SafeRE without an independent JDK comparison. `Matcher.quoteReplacement()`
delegates to the JDK. `results()` compares streamed match snapshots, and the
predicate APIs use crosschecked matching.

Functional replacements execute the callback separately for each engine. Use
callbacks whose results depend only on the supplied match, rather than shared
mutable state or external side effects.

### UTF-8 coverage

The facade also provides `Utf8Input` and `Utf8Matcher`. Validated UTF-8 inputs
are compared with SafeRE String and JDK matchers using translated coordinates;
trusted inputs delegate without those String oracles. Some String matcher
operations additionally check a UTF-8 shadow when the input and operation can
be represented faithfully. This is conditional coverage, not a promise that
every API call runs through all representations.

## Limitations

- **Performance:** Compared operations run on multiple engines. This is
  intended for testing, not production.
- **Thread safety:** Like `java.util.regex.Matcher`, the crosscheck `Matcher`
  is not thread-safe.
- **Unsupported features:** Patterns using backreferences, lookahead,
  lookbehind, or possessive quantifiers over consuming operands will throw
  `UnsupportedPatternException` at compile time.

## Benchmarking

Crosscheck overhead is measured by `CrosscheckOverheadBenchmark` in the
`safere-benchmarks` module. It is excluded from the default no-argument Java
benchmark run because it is a diagnostic benchmark for optimizing
`safere-crosscheck`, not part of the normal SafeRE performance suite.

Run it explicitly when working on crosscheck performance:

```bash
./run-java-benchmarks.sh CrosscheckOverheadBenchmark
```
