# Direct UTF-8 Matching

SafeRE can match text that is already stored as UTF-8 without first decoding
the entire input to a Java `String`. This API is useful for storage engines,
network services, parsers, and data-processing systems whose native text
representation is UTF-8.

Patterns are still compiled from Java strings and use SafeRE's documented
Java-oriented syntax and semantics. Only the input representation and match
coordinates change.

## Who This API Is For

Direct UTF-8 matching is intended for JVM applications whose text is already
stored as bytes and would otherwise be decoded solely to call a String-based
regex API. The opportunity is largest when regex execution is repeated across
many values or large inputs:

- **SQL and stream-processing engines.** Systems such as Trino, Apache Spark,
  Apache Flink, Apache Pinot, and Apache Druid commonly keep table values in
  UTF-8-oriented containers but use String-based regex engines. Direct input
  can remove per-value decoding on raw scans and high-cardinality columns.
- **Columnar and buffer-oriented systems.** Apache Arrow and similar formats
  describe UTF-8 values with byte buffers and offsets. Byte-relative match and
  capture bounds allow an engine to preserve its own slice representation
  instead of allocating Java strings for intermediate values. The current
  SafeRE adapter accepts byte arrays; other storage adapters remain future
  work.
- **Storage, indexing, and log-processing services.** Records read from files,
  caches, or network buffers can be filtered and parsed without first creating
  an input-sized UTF-16 copy.
- **Protocol and parser pipelines.** Services that already validate UTF-8 at an
  ingestion boundary can use trusted views for later matching, extraction, and
  redaction without repeating validation or decoding.
- **Security-sensitive regex execution.** These systems often run user- or
  administrator-supplied patterns over large datasets. The direct API retains
  SafeRE's linear-time guarantee while avoiding a representation round trip.

The API can avoid more than the initial decode. Capture bounds let a caller
slice its existing value without copying, and `Utf8Sink` lets replacement
output flow directly into a byte-oriented builder. This is especially useful
for extraction, splitting, redaction, and SQL regex functions that need to
return UTF-8 again.

It is usually not useful when the application already owns a Java `String`,
when inputs are small and matched only once, or when an existing dictionary or
regex index has already amortized matching across repeated values. It is also
not a binary-pattern API: inputs are UTF-8 text, not arbitrary bytes.

## Quick Start

```java
import static java.nio.charset.StandardCharsets.UTF_8;

import org.safere.Pattern;
import org.safere.Utf8Input;
import org.safere.Utf8Matcher;

byte[] bytes = "id=42; name=café".getBytes(UTF_8);
Utf8Input input = Utf8Input.validated(bytes);

Pattern pattern = Pattern.compile("name=(\\p{L}+)");
Utf8Matcher matcher = pattern.matcher(input);
if (matcher.find()) {
    int matchStart = matcher.start();
    int matchEnd = matcher.end();
    int nameStart = matcher.start(1);
    int nameEnd = matcher.end(1);
}
```

All four positions in this example are UTF-8 byte offsets relative to `input`,
not UTF-16 indices. A position returned for well-formed input never falls
inside a Unicode scalar value.

When only a yes/no answer is needed, use the capture-free convenience method:

```java
boolean found = pattern.find(input);
```

## Constructing Input Views

`Utf8Input` is a borrowed view over caller-owned storage. The current public
input adapter supports whole byte arrays and byte-array windows:

```java
Utf8Input whole = Utf8Input.validated(bytes);
Utf8Input window = Utf8Input.validated(bytes, offset, length);
```

Window coordinates are relative to the logical view. For example, a match at
the first byte of `window` has `start() == 0`, regardless of the window's
physical offset in the backing array.

The caller retains ownership of the storage and must not mutate the covered
bytes while the view or a matcher retaining it is in use. SafeRE does not copy
the input.

### Validated and Trusted Input

Choose construction according to where the UTF-8 validity guarantee belongs:

| Factory | Validation | Intended use |
|---|---|---|
| `Utf8Input.validated(...)` | Strict RFC 3629 validation at construction | Untrusted or unchecked input |
| `Utf8Input.trusted(...)` | No validation pass | Storage whose producer already guarantees valid UTF-8 |

`validated` rejects the first malformed sequence with an
`IllegalArgumentException` whose message includes the relative byte offset.

For malformed trusted input, exact match results are unspecified. Execution
nevertheless remains bounded, memory-safe, and monotonic. Trusted input is not
an arbitrary byte-regex mode, and SafeRE does not support RE2's `\C` operator.

## Matching and Captures

The UTF-8 API deliberately uses a separate matcher type instead of adding byte
mode to the `java.util.regex`-compatible `Matcher` API.

| API | Purpose |
|---|---|
| `Pattern.find(Utf8Input)` | Capture-free existence check |
| `Pattern.matcher(Utf8Input)` | Create a stateful `Utf8Matcher` |
| `Utf8Matcher.find()` | Find the next match |
| `start()`, `end()` | Group-zero byte bounds |
| `start(int)`, `end(int)` | Numbered capture byte bounds |
| `groupCount()` | Number of capturing groups |

An unmatched optional group has start and end positions of `-1`, as it does in
the String API. SafeRE returns bounds rather than allocating byte arrays for
captured text. Callers can use those bounds to slice their existing storage or
decode only the capture they need.

`Utf8Matcher` is stateful and is not thread-safe. A compiled `Pattern` remains
thread-safe and reusable across String and UTF-8 inputs. An immutable
`Utf8Input` view may be shared only while its caller-owned storage remains
unchanged.

## Byte-Native Replacement

`Utf8Matcher` supports Java-style replacement templates without decoding the
subject to a `String`. Output is sent synchronously to a caller-provided
`Utf8Sink`:

```java
import java.io.ByteArrayOutputStream;

byte[] bytes = "Ada Lovelace; Grace Hopper".getBytes(UTF_8);
Utf8Input input = Utf8Input.validated(bytes);
Utf8Input replacement = Utf8Input.validated("$2, $1".getBytes(UTF_8));
Utf8Matcher matcher = Pattern.compile("(\\p{L}+) (\\p{L}+)").matcher(input);

ByteArrayOutputStream output = new ByteArrayOutputStream();
while (matcher.find()) {
    matcher.appendReplacement(output::write, replacement);
}
matcher.appendTail(output::write);
byte[] result = output.toByteArray();
```

Replacement templates use the same group-reference and escaping syntax as
SafeRE's String replacement methods. `Utf8Sink.append` receives borrowed byte
ranges and must consume each range before returning; it must not retain or
mutate the supplied storage. The sink also has a `ByteBuffer` convenience
method for transferring bounded buffer ranges.

## Current Scope

The UTF-8 API currently provides capture-free search, repeated `find()`,
numbered capture bounds, and append-style replacement. It does not expose every
operation from the String `Matcher` API: there is currently no UTF-8 `matches`,
`lookingAt`, region, reset, split, stream, or direct group-value method.

Input storage is currently adapted from byte arrays. `Utf8Input` is a separate
abstraction so additional storage adapters can be added without changing
matching semantics or the matcher API.

Like every SafeRE engine path, direct UTF-8 matching retains the linear-time
guarantee. See [UTF-8 Input Design](design/UTF8_BYTE_INPUT.md) for architecture
and rationale, [Testing](TESTING.md) for validation strategy, and
[Benchmarks](BENCHMARKS.md) for performance evidence.
