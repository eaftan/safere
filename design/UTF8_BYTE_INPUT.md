# UTF-8 Input Design

## Status

Implemented and validated locally at SafeRE revision
`5ac880b373aa718c3591dc0625bd85d4e482ac2b` and Trino revision
`e4936f532b804a7bd782ad62768049fec02c4f60`. The final evidence and remaining
follow-up adapters are recorded in the
[implementation plan](UTF8_BYTE_INPUT_IMPLEMENTATION_PLAN.md) and
[Trino compatibility report](TRINO_SAFERE_COMPATIBILITY.md).

## Problem

SafeRE accepts `CharSequence` input and exposes match positions in UTF-16 code
units, like `java.util.regex`.  Some applications already store text as UTF-8
bytes.  Converting each value to a `String` before matching adds a full decoding
pass, allocates a UTF-16 or compact-string representation, and temporarily keeps
both representations live.

Issue #516 was raised for Trino, whose `varchar` values are represented by
Airlift `Slice` objects over UTF-8 storage.  This is a material application
requirement rather than only a convenience overload: regex functions run over
many values in query-engine hot paths, and converting every value to `String`
would defeat the purpose of accepting the existing storage.

Supporting UTF-8 input affects more than the consuming loop.  It defines a
second public coordinate system and touches:

- matcher construction and reset;
- forward and reverse decoding;
- `find()` advancement after empty matches;
- regions, anchors, word boundaries, line boundaries, and grapheme boundaries;
- group positions and group materialization;
- replacement and splitting;
- literal and character-class accelerators;
- DFA cache safety and all fallback engines;
- malformed-input behavior and input ownership.

This design makes UTF-8 a first-class input representation for Java systems
that already store text as bytes or buffers.  Replacing
`io.trino:trino-re2j` in Trino is the first implementation and release gate,
not the permanent boundary of the feature.  The durable API must also admit
future contiguous, segmented, and off-heap storage adapters without changing
matching semantics or duplicating engines.

SafeRE should meet that target without adding an Airlift dependency to its core
artifact and without duplicating the matching engines.

## Goals

- Provide a broadly usable UTF-8 matching API for JVM applications that keep
  text in byte-oriented storage.
- Let Trino replace `io.trino:trino-re2j` with SafeRE as the first validated
  integration.
- Match Trino `varchar` storage without first creating a `String`.
- Preserve SafeRE's end-to-end linear-time guarantee.
- Keep existing `String` behavior and performance independent of the new API.
- Accept nonzero-offset array windows and return positions relative to the
  logical window so Trino can slice its original `Slice`.
- Support the operations Trino uses: unanchored search, repeated `find`, group
  bounds and zero-copy source slicing, split construction, and byte-native
  replacement.
- Give every SafeRE engine and accelerator the same UTF-8 semantics.
- Make the public input abstraction representation-neutral and keep the
  internal input contract capable of supporting array, contiguous-buffer,
  segmented, or off-heap UTF-8 storage without changing regex semantics.
- Make malformed input, input windows, mutation, and offset units explicit.
- Provide stable differential, engine-equivalence, fuzz, integration, scaling,
  and benchmark coverage.

## Product Target And Success Criteria

The product category is JVM software that already owns UTF-8 text and should
not have to decode it to `String` merely to run a regex.  The first delivery
target is the regex-function integration in the checked-out Trino source, not
the complete public surface of either RE2/J distribution.  Phase one is
successful only when its public contracts do not assume heap-array storage and
a Trino validation branch can remove its
`io.trino:trino-re2j` dependency and implement its `Re2JRegexp` wrapper with
SafeRE while preserving:

- all existing Trino RE2/J regex function cases are inventoried, and each
  semantic difference is resolved under the compatibility decision described
  below rather than silently inherited from either engine;
- direct matching over the backing storage of a `Slice`, including nonzero
  offsets, without an input copy;
- byte-relative match and capture bounds;
- zero-copy extraction and split slices;
- byte-native ordinary and lambda replacement;
- repeated-find behavior used by extraction, count, position, split, and
  replacement;
- bounded-memory, linear-time execution;
- performance competitive with Trino's current DFA/NFA implementation on
  Trino workloads;
- no material regression to SafeRE's existing String API.

Later adapters for `ByteBuffer`, segmented storage, or off-heap memory have
their own release gates.  Each must demonstrate zero-copy subject matching,
the same semantic and engine-equivalence suite as the array adapter, bounded
ownership and lifetime rules, and a measured improvement in its target
application.  They are not required to release the Trino adapter.

This does not require source or binary compatibility with Trino RE2/J.  A small
Trino-side adapter and changes to `Re2JRegexp` are expected.  It does require
the selected semantic policy to be enforced consistently at Trino's SQL
function boundary, with every intentional behavior change documented, and it
requires acceptable end-to-end performance.

## Non-Goals

- Matching arbitrary binary data.  The input is UTF-8 text, not a byte regex
  dialect, and `\C` remains unsupported.
- Compiling the pattern from UTF-8 bytes.  Pattern syntax remains a Java
  `String` API and is not on the per-row Trino hot path.
- Adding Airlift `Slice` or another third-party buffer type to SafeRE's public
  API.
- Shipping segmented-memory or off-heap adapters in phase one.  The public
  abstraction must permit them, but each concrete adapter needs independent
  ownership, lifetime, correctness, and performance validation.
- Reproducing the full public API of upstream RE2/J or Trino RE2/J.
- Allowing arbitrary external callers to select Trino RE2/J's DFA state limits,
  retry count, or fallback listener.  Trino may remove or deprecate those
  settings when it migrates if SafeRE's bounded engine policy makes them
  unnecessary.
- Preserving `java.util.regex.Matcher` source compatibility for the UTF-8 API.
  The JDK has no byte-input matcher and its UTF-16 position contract cannot
  describe byte offsets.
- Maintaining separate UTF-8 DFA, NFA, BitState, or OnePass implementations.
- Returning a decoded `String` from an operation that is documented as
  zero-copy.

## Evidence And Compatibility Target

There are two relevant RE2/J interfaces.  They should not be conflated.  The
Trino fork defines the requirements for the first integration; the broader
storage-neutral requirements come from the JVM consumers reviewed below.

This review used upstream RE2/J commit
`84237cbbd0fbd637c6eb6856717c1e248daae729`, Trino commit
`8e023609041cd8e4999aea0ecceb1e81ed887ca1`, and the Trino RE2/J 1.7 source
referenced by that Trino build.

### Upstream RE2/J

The reference copy in `re2j-reference/` exposes these public entry points:

```java
Pattern.matches(String regex, byte[] input)
pattern.matches(byte[] input)
pattern.matcher(byte[] input)
matcher.reset(byte[] input)
```

The returned object is the ordinary RE2/J `Matcher`.  On a byte input:

- `matches`, `lookingAt`, `find`, and `find(int)` operate directly on UTF-8;
- `start` and `end`, including group overloads, are byte offsets;
- `group()` decodes the selected byte range into a new `String`;
- repeated `find()` stores and advances a byte cursor;
- the matcher retains the caller's array rather than copying it;
- the execution machine decodes UTF-8 to code points on demand.

RE2/J's lower-level, package-private `RE2` interface also has Go-derived
`findUTF8`, index, submatch, and find-all variants.  Those methods return byte
arrays or byte indices, but they are not part of the public `Pattern` and
`Matcher` surface used by ordinary clients.

The upstream API is implementation evidence, not a compatibility target.  Its
whole-array input, allocating `group()`, and String-oriented replacement do not
meet Trino's requirements.  SafeRE does not need to reproduce its overload set
or malformed-input behavior merely because it exists.

The upstream decoder is also not a specification to copy.  It consumes bytes
according to the leading-byte shape and does not fully validate continuation
bytes, overlong encodings, surrogate scalar values, or values above
`U+10FFFF`.  SafeRE must define its own malformed-input contract.

### Trino's RE2/J Fork

Trino depends on `io.trino:trino-re2j:1.7`.  That fork is byte-native rather
than a thin use of upstream's `byte[]` overload:

```java
Pattern.matches(String regex, Slice input)
Pattern.find(String regex, Slice input)
pattern.matches(Slice input)
pattern.find(Slice input)
pattern.matcher(Slice input)
pattern.split(Slice input, int limit)

matcher.reset(Slice input)
matcher.start()
matcher.end()
matcher.group(int group)       // returns a Slice view
matcher.find()
matcher.find(int start)
matcher.appendReplacement(SliceOutput out, Slice replacement)
matcher.appendTail(SliceOutput out)
matcher.replaceAll(Slice replacement)
matcher.replaceFirst(Slice replacement)
```

Its `MachineInput` reads the backing array, offset, and length of the supplied
`Slice`.  Positions are relative to the logical slice, not to the backing
array.  Captures use `input.slice(start, length)`, so group extraction is a
zero-copy view.  Replacement writes unmatched input and captured groups
directly to a byte-oriented `SliceOutput`.

The fork also has configurable DFA state limits, DFA-to-NFA fallback, and a
fallback event listener.  Those controls are specific to that library.  Trino
does not need SafeRE to reproduce the configuration API if SafeRE's own engine
policy provides bounded memory and reliable fallback.

## How Trino Uses The Interface

Trino centralizes regex execution in `Re2JRegexp`.  The following is the
required behavioral surface.

| Trino operation | RE2/J operations used | Required SafeRE behavior |
| --- | --- | --- |
| `regexp_like` | `Pattern.find(Slice)` | Allocation-free unanchored boolean search. |
| `regexp_extract` | `matcher`, `find`, `group` | Byte-relative bounds and zero-copy slicing. |
| `regexp_extract_all` | repeated `find`, `group` | Correct empty and nullable capture iteration. |
| `regexp_split` | repeated `find`, bounds | Relative offsets over the logical input window. |
| `regexp_position` | sliced matcher, `find` | Byte offsets convertible to code-point positions. |
| `regexp_count` | repeated `find` | Non-overlapping iteration, including empty matches. |
| `regexp_replace` | `replaceAll(Slice)` | Byte-native group expansion and output. |
| lambda replacement | repeated `find`, bounds, groups | Groups for Trino-assembled output. |

Several consequences follow from those call sites:

1. `matcher(byte[])` alone is not enough.  Trino frequently matches a logical
   window backed by a larger array.  Copying `Slice.getBytes()` for each value
   would reintroduce the allocation the feature is intended to remove.
2. Byte-relative `start()` and `end()` are essential.  Trino slices input,
   assembles output, and converts positions to code-point indices using them.
3. An allocating `groupBytes()` is not an adequate primary capture API.
   Extraction is expected to return a view over the existing input.
4. Repeated `find()` and empty matches are core functionality, not edge cases.
5. Replacement must stay in bytes.  Decoding matches or the whole input to
   `String` would not meet the use case.
6. SafeRE does not need to depend on `Slice`.  A Trino adapter can retain its
   original `Slice` and use SafeRE's relative group bounds, provided SafeRE can
   match an array window without copying.

## Other JVM UTF-8 Regex Consumers

Trino is not the only JVM system with a byte-oriented string representation and
String-oriented regex execution.  The projects below are prospective consumers,
not phase-one compatibility targets.  Their current designs are useful evidence
for which assumptions SafeRE should and should not expose in its API.

### Apache Spark SQL

Spark SQL represents values internally with
[`UTF8String`](https://github.com/apache/spark/blob/master/common/unsafe/src/main/java/org/apache/spark/unsafe/types/UTF8String.java),
which stores a base object, memory offset, and byte length.  Its Catalyst
[`LIKE`, `RLIKE`, extraction, and replacement expressions](https://github.com/apache/spark/blob/master/sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/expressions/regexpExpressions.scala)
use `java.util.regex.Pattern`.  Generated matching code converts each subject
with `UTF8String.toString()` before calling `Pattern.matcher`; results that
remain in Spark's string representation are encoded back to `UTF8String`.
Constant patterns are cached, so avoiding subject decoding is the principal
opportunity.

Spark is evidence for a byte-relative, borrowed-input matcher, but not for an
array-only internal design.  A `UTF8String` can be backed by an ordinary array
or another base object.  A future integration may need a safe contiguous-memory
adapter in addition to the phase-one array window.

### Apache Flink Table/SQL

Flink's
[`BinaryStringData`](https://nightlies.apache.org/flink/flink-docs-master/api/java/org/apache/flink/table/data/binary/BinaryStringData.html)
stores UTF-8 across one or more memory segments.  Its shared
[`SqlFunctionUtils.getRegexpMatcher`](https://github.com/apache/flink/blob/master/flink-table/flink-table-runtime/src/main/java/org/apache/flink/table/runtime/functions/SqlFunctionUtils.java)
caches `java.util.regex.Pattern` instances but calls `toString()` on both the
`StringData` pattern and subject before matching.  Functions that return a
matched string, such as
[`REGEXP_SUBSTR`](https://github.com/apache/flink/blob/master/flink-table/flink-table-runtime/src/main/java/org/apache/flink/table/runtime/functions/scalar/RegexpSubstrFunction.java),
then encode the Java `String` group into `BinaryStringData`.

Flink is evidence that a future input need not be contiguous.  SafeRE should
not publish segmented input before there is an integration and performance
case for it, but the shared decoding cursor and engine interface must not rely
on random access being a single array load.

### Apache Druid

Druid's ordinary
[`RegexFilter`](https://github.com/apache/druid/blob/master/processing/src/main/java/org/apache/druid/segment/filter/RegexFilter.java)
uses `java.util.regex.Pattern.matcher(input).find()` on materialized `String`
values.  Its column machinery can evaluate predicates over dictionary entries
and reuse the resulting dictionary identifiers, which amortizes matching and
decoding when many rows share a value.  Raw scans still present a potential
direct-UTF-8 use case.

Druid is evidence that the API should support both capture-free boolean search
and stateful matching, and that benchmarks must distinguish raw-value scans
from dictionary scans.  It is not evidence that SafeRE should subsume a query
engine's dictionary or column-index logic.

### Apache Pinot

Pinot already abstracts its regex engine behind
[`PatternFactory`](https://github.com/apache/pinot/blob/master/pinot-common/src/main/java/org/apache/pinot/common/utils/regex/PatternFactory.java),
which can select `java.util.regex` or upstream RE2/J.  Scalar
[`REGEXP_LIKE`](https://github.com/apache/pinot/blob/master/pinot-common/src/main/java/org/apache/pinot/common/function/scalar/regexp/RegexpLikeConstFunctions.java)
reuses a matcher but resets it with a Java `String`.  Its
[`REGEXP_LIKE` predicate evaluator](https://github.com/apache/pinot/blob/master/pinot-core/src/main/java/org/apache/pinot/core/operator/filter/predicate/RegexpLikePredicateEvaluatorFactory.java)
can eagerly scan a small dictionary, lazily cache results per dictionary ID,
or scan raw String values.  Pinot also has FST-based regex index paths that
avoid ordinary per-value matching.

Pinot is evidence for keeping compiled patterns independent of input
representation and for a low-allocation reset operation if benchmarks justify
one.  Its FST path also establishes a boundary: SafeRE's input API should
improve value matching, not attempt to replace storage-engine indexes.

### Apache Arrow Java Ecosystem

Arrow Java's
[`VarCharVector`](https://arrow.apache.org/java/main/reference/org.apache.arrow.vector/org/apache/arrow/vector/VarCharVector.html)
provides offset-addressed UTF-8 values in buffers but does not itself define a
general regex execution API.  Arrow-based JVM engines must currently choose
and adapt a matcher.  Arrow is therefore ecosystem evidence for an eventual
off-heap or buffer-backed input, not a confirmed direct SafeRE consumer.

### API Consequences

This ecosystem evidence does not broaden the initial Trino deliverable.  It
does constrain choices that would be expensive to undo:

- public positions must describe the logical UTF-8 value rather than its
  physical storage address;
- compiled `Pattern` objects and engine caches must remain independent of the
  concrete input representation;
- the internal `TextInput` contract must permit array, contiguous-memory, and
  segmented implementations without duplicating matching engines;
- capture-free boolean search deserves a direct path because SQL filters often
  need no capture state;
- matcher reset should be added only if measured reuse benefits Trino or a
  subsequent integration and its ownership contract is clear;
- SafeRE should expose storage-neutral bounds and byte sinks rather than return
  a storage-library-specific slice type;
- dictionary and FST indexes remain application concerns layered above SafeRE;
- any future `ByteBuffer`, `MemorySegment`, or segmented-input public adapter
  requires its own lifetime, mutation, validation, and performance design.

The phase-one implementation therefore ships only a `byte[]` window adapter,
but `Utf8Input` itself must be representation-neutral.  Heap-array storage is
the first adapter, not the public definition of UTF-8 input.

## Trino Migration Boundary

Replacing Trino RE2/J is an integration migration, not a jar substitution.
Trino's `Re2JRegexp` wrapper is the seam where the existing dependency should
be replaced.

The migration should:

- compile the pattern with `org.safere.Pattern`;
- wrap each source `Slice` as a trusted UTF-8 array window;
- use a SafeRE UTF-8 matcher for repeated search and byte bounds;
- construct extracted and split results by slicing the original Trino `Slice`;
- adapt Trino's `SliceOutput` to SafeRE's byte sink for replacement;
- preserve Trino's SQL-level error translation for invalid patterns,
  replacement strings, and group indices;
- remove the dot-star workaround only after benchmarks show SafeRE's prefix and
  unanchored-search paths do not need it;
- continue accepting `re2j.dfa-states-limit` and `re2j.dfa-retries` during the
  initial migration so existing deployments do not fail on unknown or unused
  configuration; map them only if SafeRE has a genuine supported equivalent,
  otherwise retain them as documented deprecated no-ops until Trino's normal
  configuration-removal process is complete.

Pattern-language and matching compatibility are migration questions, but Trino
RE2/J is not automatically the semantic authority.  SafeRE intentionally
targets `java.util.regex` semantics subject to its linear-time exclusions and
has intentionally diverged from RE2/J where those semantics differ.  Before
migration, run Trino's complete regex test suite and inventory every compile,
match, capture, replacement, and error difference.  Use that inventory to make
the explicit compatibility decision below.  Byte input is necessary for the
migration, but it is not by itself sufficient.

## Open Question: Trino Semantic Compatibility

It is intentionally unresolved whether replacing Trino RE2/J means preserving
that fork's regex semantics or migrating Trino's RE2J-configured functions to
SafeRE's JDK-compatible semantics.

The first feasibility substitution and differential inventory must separate:

- SafeRE bugs relative to documented or observed `java.util.regex` behavior;
- Trino RE2/J behavior that differs from the JDK;
- Trino SQL tests that intentionally specify a public SQL-function contract;
- incidental tests that merely encode the current engine's behavior;
- syntax or semantics SafeRE rejects to preserve linear time;
- compatibility behavior that a bounded, principled Trino adapter could
  provide without changing SafeRE's core semantics.

The project must then choose and document one policy:

1. **Migrate to SafeRE semantics.**  Trino's RE2J-configured functions adopt
   SafeRE's JDK-compatible behavior, with release notes for user-visible
   changes.
2. **Preserve selected Trino behavior at the adapter boundary.**  Only explicit
   SQL contracts that can be implemented compositionally and in linear time are
   retained; SafeRE core semantics remain JDK-compatible.
3. **Do not migrate yet.**  If required Trino behavior conflicts materially
   with SafeRE's compatibility or linear-time guarantees, keep Trino RE2/J
   until the product decision changes.

Changing SafeRE core behavior to match RE2/J is not the default.  Any proposed
core change must first be shown compatible with SafeRE's JDK contract or
approved as an intentional documented divergence.

The feasibility phase ends with a mandatory checked-in report at
`design/TRINO_SAFERE_COMPATIBILITY.md`.  It contains one stable row per difference
bucket with: bucket identifier; smallest representative pattern, input, and
operation; JDK result; Trino RE2/J result; SafeRE result; specification status;
linear-time feasibility; selected disposition; regression test or issue link;
and owner decision/status.  It also records the project owner's selection of
policy 1, 2, or 3 above, exact validation commands and revisions, frozen
benchmark membership, and merge gates.

Update this design by converting resolved open questions into decisions.  Do
not begin `TextInput` or UTF-8 engine work until the report and policy decision
are committed; an undecided policy is a hard stop.

### Compatibility Discipline

Trino behavior should be supported only when it is a coherent public contract
and has a principled linear-time formulation.  Do not add pattern-string
checks, input-shape checks, verifier matchers, repeated retries, post-hoc
capture repair, unbounded rescans, or engine-state emulation merely to preserve
an observed Trino RE2/J result.

Treat each of the following as an open question until the first substitution
and differential inventory provide evidence:

- whether Trino users expect RE2/J capture and empty-match selection where it
  differs from `java.util.regex`;
- whether Trino's named and numeric replacement parsing is an intentional SQL
  contract or an inherited engine detail;
- whether malformed UTF-8 needs any stable match result beyond non-throwing,
  bounded termination;
- whether Trino's dot-star rewrite is still useful with SafeRE or should be
  removed rather than reproduced;
- whether DFA state-limit, retry, and fallback-listener behavior is operationally
  significant or only an implementation control of the old engine;
- whether any Trino test relies on starting or resuming a match inside a UTF-8
  scalar, rather than at a scalar boundary;
- whether pattern spellings accepted only by RE2/J should be rejected, adapted
  in Trino, or deliberately added to SafeRE without weakening its JDK dialect
  policy;
- whether exact exception messages are public SQL behavior or incidental
  engine text.
- whether Trino keeps `regex-library=RE2J`, adds a `SAFERE` value while
  retaining `RE2J`, or deliberately renames the selection with a configuration
  migration; the implementation name must not change silently.

For each question, record the smallest representative case, the JDK result,
the Trino RE2/J result, the SafeRE result, the relevant SQL documentation or
tests, and the linear-time implementation shape.  If no principled contract is
identified, prefer a documented migration behavior change or declare the
migration blocked rather than bending SafeRE around the old fork.

## Public API

UTF-8 matching should use a distinct matcher type.  Reusing `Matcher` would
make methods such as `group`, `replaceAll`, `region`, and `reset` change units
or return representation based on hidden construction state.  It would also
make the JDK-compatible class claim behavior that the JDK does not define.

Every pattern accepted by `Pattern.compile` must work with every operation
exposed by `Utf8Matcher`.  An optimized engine may be guarded out in favor of a
canonical engine, but matching must not fail at runtime merely because a
pattern uses a supported SafeRE construct.  Deferred operations such as regions
do not block release because they are absent from the phase-one API.  This rule
means phase-one `find()` supports grapheme constructs as well as ordinary
patterns; otherwise the public UTF-8 API remains provisional and unpublished.

The phase-one surface is split into the representation-neutral contracts that
would be expensive to change and the concrete operations required by Trino.
Conveniences and new storage adapters remain deferred until a validated
consumer needs them.

### Input View

Add a borrowed, representation-neutral view with immutable logical bounds:

```java
public sealed interface Utf8Input permits ArrayUtf8Input {
  static Utf8Input validated(byte[] bytes);
  static Utf8Input validated(byte[] bytes, int offset, int length);
  static Utf8Input trusted(byte[] bytes);
  static Utf8Input trusted(byte[] bytes, int offset, int length);

  int length();
}
```

SafeRE owns and permits the concrete implementations.  The initial permitted
implementation is an array window.  The interface does not expose `byteAt`, a
backing array, or a caller-implemented per-byte callback.  Such an SPI would
put an unknown virtual call in the hottest decoding loop, prevent SafeRE from
enforcing storage invariants, and could erase the benefit of avoiding String
conversion.

Subject to separate design and validation, later releases may add factories
such as:

```java
static Utf8Input validated(ByteBuffer buffer);
static Utf8Input trusted(ByteBuffer buffer);
```

A segmented adapter should likewise be SafeRE-owned.  Its eventual factory
shape must be driven by a real integration; a premature `List<ByteBuffer>` API
may impose allocation or erase useful properties of Flink memory segments.
`MemorySegment` should not enter the public signature while it is unavailable
as a stable API on SafeRE's Java baseline.  SafeRE core must never expose
Spark's unsafe `(Object base, long offset)` representation.

Required for the Trino adapter are trusted window construction and `length`.
Validated construction is also phase one because SafeRE should not expose only
an unchecked public text API.  `byteAt`, `slice`, `writeTo`, and `toByteArray`
remain package-private or deferred until an independent caller demonstrates a
need; the matcher and replacement implementation can use package-private
storage access.

All public positions are relative to the logical view: zero is its physical
storage start and `length()` is its logical end.  An internal subview remains
relative to its own start.  Physical array offsets, buffer positions, segment
indices, and addresses are implementation details.

`validated` performs one strict RFC 3629 validation pass when constructing the
root view.  Package-private internal subview construction on a validated view
requires both boundaries to be code-point boundaries and otherwise throws
`IndexOutOfBoundsException`; valid subviews inherit validation without
rescanning.  `trusted` is for systems, such as a SQL engine with normally
well-formed text, that do not want a duplicate validation pass.

Array factories throw `NullPointerException` for a null array and
`IndexOutOfBoundsException` for a negative offset or length or a range outside
the array.  Validate windows without integer overflow, for example by checking
`offset > bytes.length - length` only after both values are nonnegative.  A
validated window that begins or ends inside a scalar fails validation with
`IllegalArgumentException` at the first invalid relative byte offset.

The trusted contract is explicit: exact regex results are unspecified for
malformed input, but the decoder recovery rule below, non-throwing execution,
memory safety, array bounds safety, monotonic progress, and linear time still
hold.  SafeRE never treats malformed bytes as arbitrary Unicode code points in
validated mode.

The caller must not mutate or invalidate the covered storage while an input
view or matcher is in use.  The view's bounds are immutable, but its borrowed
contents and underlying storage lifetime are not.
This is the same practical ownership model as a mutable
`CharSequence`, but it must be documented because cached match results and
deferred captures make mutation observable.  Callers needing isolation can
pass a copied array.

`Utf8Input` metadata is thread-safe under the no-mutation precondition.
`Utf8Matcher` is not thread-safe.  `Pattern.findUtf8` and
`Pattern.find(Utf8Input)` are thread-safe to the same extent as existing
`Pattern` operations.  Pattern-level caches must not retain an input or its
storage after an operation returns.  A matcher retains its view, and therefore
its underlying storage, for the matcher's lifetime.  Sink callbacks execute
synchronously on the calling thread.  Future off-heap adapters must define
liveness and fail safely rather than read released memory.

Pattern exposes the windowed entry point required by Trino:

```java
public Utf8Matcher matcher(Utf8Input input);
```

Trino uses the array-window `Utf8Input.trusted` entry point for stateful
matching so the hot path performs neither a copy nor redundant validation.  Direct
`Pattern.matcher(byte[])` convenience overloads remain deferred; the required
capture-free raw-window `Pattern.findUtf8` method is specified below.

### UTF-8 Matcher

Add a final `Utf8Matcher` returned by the overloads above.  Keep the first
release limited to the operations required by the Trino migration:

```java
public final class Utf8Matcher {
  public boolean find();

  public int start();
  public int start(int group);
  public int end();
  public int end(int group);
  public int groupCount();
}
```

`pattern()`, input-changing reset, and parameterless reset are deferred.  Add
reset only if matcher reuse wins its benchmark and a validated integration uses
it; Pinot provides evidence that such reuse may matter, but does not justify an
untested phase-one operation.  The irreducible phase-one matcher contract is
repeated `find`, group count, and numeric group bounds.

Trino retains its original `Slice` and materializes a group with
`source.slice(start(group), end(group) - start(group))`.  A nonparticipating
group has `-1` bounds.  This is zero-copy and does not require a SafeRE group
object whose backing storage would otherwise need to be exposed.

JDK-like byte APIs such as `matches`, `lookingAt`, `find(int)`, named-group
accessors, `usePattern`, regions, transparent bounds, anchoring bounds, and
decoded group strings are follow-on work.  Add one only for a demonstrated
client and only after its byte-coordinate and lifecycle contract has focused
tests.  They do not block the Trino-targeted release.

`find()` after an empty match advances by one decoded code point, not one byte.
At end of input, it permits the same single terminal empty match and exhaustion
behavior as the canonical String matcher state machine.

Phase-one `Utf8Matcher` follows every applicable transition in
`MATCHER_STATE_MACHINE.md`, expressed in byte coordinates.  It begins with no
current result and search position zero.  Successful `find` installs the
current group bounds and advances the next-search cursor.  Failed `find`
invalidates the previous result and leaves the matcher exhausted; later
parameterless `find` calls remain false until a future reset API, if added.
`start` and `end` throw `IllegalStateException` before a successful match and
after failure, and `IndexOutOfBoundsException` for a group below zero or above
`groupCount`.  A nonparticipating valid group returns `-1` bounds.  Deferred
capture resolution completes before any public group observation.

### Byte-Native Replacement

Returning only `byte[]` is useful but is not sufficient for integrations that
already own an output builder.  Add a small output abstraction independent of
Airlift and capable of preserving both array and buffer storage:

```java
public interface Utf8Sink {
  void append(byte[] bytes, int offset, int length);

  default void append(ByteBuffer buffer) {
    Utf8SinkSupport.copyBuffer(buffer, this);
  }
}
```

`Utf8SinkSupport` is package-private and copies through bounded reusable chunks;
the exact helper shape is an implementation detail.

Each array or buffer is borrowed and may expose storage outside the logical
range.  The buffer passed to `append` is a bounded duplicate whose position and
limit describe the bytes to consume; modifying its cursor does not affect the
input view.  A sink must consume synchronously and must not retain or mutate
the storage.  The default buffer method copies safely through bounded chunks;
an integration that owns a buffer-aware output overrides it for direct
transfer.  A segmented input invokes the appropriate method once per covered
segment rather than coalescing the range.

SafeRE-owned `Utf8Input` implementations dispatch their ranges to the matching
sink operation.  This keeps physical-storage inspection out of the public
input API while preserving the heap-array fast path.  Any future storage form
must define efficient sink dispatch or document that replacement copies;
zero-copy matching must not be misrepresented as zero-copy replacement.
SafeRE invokes sink code only after any match and capture state needed for that
append is resolved, and it detects matcher mutation during callbacks
consistently with its existing callback state machine.  A general
array-returning replacement convenience may be added later, but is not required
as the phase-one fallback.

Do not publish the matcher replacement methods until the feasibility report
selects the replacement integration shape.  If Trino can use SafeRE's
replacement dialect directly, the matcher provides:

```java
public Utf8Matcher appendReplacement(Utf8Sink sink, Utf8Input replacement);
public void appendTail(Utf8Sink sink);
```

If selected Trino SQL behavior requires adapter-level parsing, keep replacement
expansion in the adapter and expose only match/group bounds plus package-private
range-copy primitives.  Do not publish a second SafeRE replacement dialect.
`replaceAll`, `replaceFirst`, and array-returning replacement are general
conveniences and are deferred unless the validated adapter uses them.

SafeRE's byte replacement API uses the same `$0`, `$1`, `${name}`, and
backslash-escape contract as SafeRE's String API.  Literal non-ASCII bytes are
copied unchanged, and captured groups are copied from the original input bytes
without decoding.  If the semantic-policy decision requires preserving a
different Trino SQL replacement dialect, implement only the principled
difference in the Trino adapter; do not introduce two replacement dialects in
SafeRE core.

The implementation should compile a replacement template once per Trino
replacement operation, as the String path does.  It must share the same
group-reference parser rules rather than creating an unrelated dialect.
`appendReplacement` and `appendTail` participate in the UTF-8 matcher's own
append-position state machine.

If a sink throws, the exception propagates.  Matcher match bounds remain valid,
but replacement is failed and its append position becomes unusable until a
future reset operation, if exposed; subsequent `appendReplacement` or
`appendTail` throws `IllegalStateException`.  Reentrant calls that structurally
mutate or advance the matcher are detected with the same modification-count
rule as SafeRE's existing functional replacement callbacks.

Trino can adapt `SliceOutput` without copying:

```java
Utf8Sink sink = (bytes, offset, length) -> output.writeBytes(bytes, offset, length);
```

An implementation may additionally offer convenience overloads for `byte[]`,
but those should delegate to `Utf8Input` rather than define separate behavior.

### Capture-Free Boolean Search

Trino's wrapper already implements splitting from bounds and can cache compiled
patterns.  Two pattern entry points serve its hottest boolean path and general
view callers:

```java
public boolean find(Utf8Input input);
public boolean findUtf8(byte[] bytes, int offset, int length);
```

Both methods are capture-free and must not construct a public `Utf8Matcher` or
allocate capture arrays.  `findUtf8` uses trusted decoding and lets Trino avoid
allocating even an input-view wrapper per row; its name and Javadoc state that
the coordinates and decoding are UTF-8-specific.  Other convenience methods
are deferred until justified by a measured use case.

## Internal Design

### One Logical Input Interface

Introduce a package-private input interface used by every engine:

```java
interface TextInput {
  int length();
  long decodeForward(int position);
  long decodeBackward(int position);
  boolean isCodePointBoundary(int position);
  int indexOfLiteral(LiteralPrefix prefix, int from, int to);
}
```

Each decode packs the signed code point in the high 32 bits and the next or
previous logical position in the low 32 bits.  Code point `-1` represents the
appropriate logical boundary.  Package-private helpers unpack the fields so
engines do not duplicate bit manipulation.  This representation makes one
decode per consuming step structural and allocation-free; replace it only if
profiling demonstrates a better representation with the same invariant.

`Utf16TextInput` wraps the current materialized `String`.  Construction from a
public `Utf8Input` selects a storage-specialized package-private implementation,
initially `ArrayUtf8TextInput`, and later potentially contiguous-buffer and
segmented implementations.  Engine loops must not redispatch through the
public view or an arbitrary callback for each byte.  Position units are
deliberately representation-specific.  The compiled `Prog` remains code-point
based and independent of input encoding.

The interface should expose semantic operations, not a generic
`charOrByteAt`.  A raw-unit method encourages engine code to assume that one
unit is one character.  ASCII-specialized methods may be added after profiling,
but their contracts must state whether they inspect code points or encoding
units.

Forward UTF-8 decode examines at most four bytes.  Reverse decode walks back at
most three continuation bytes and validates the candidate sequence in
validated mode.  Both are constant work per consumed code point.  There is no
unbounded backward scan.

Avoid allocating holders or cursor results in engine loops.  ASCII-specialized
loops may compute the same packed result directly after profiling.  Regardless
of specialization, each consuming engine step obtains the code point and next
position from one decode; it must not decode a multibyte scalar twice.

Storage specialization is an optimization boundary, not a semantic boundary.
Every implementation supplies identical strict validation, trusted recovery,
forward and reverse decoding, code-point-boundary, literal-search, and relative
coordinate behavior.  A segmented implementation may cache its current
segment in a matcher-owned cursor, but movement and lookup must remain bounded
or amortized linear; it must not linearly search the segment table for every
byte.  A contiguous direct-buffer implementation must use bulk operations for
literal search where profiling supports them.

### Shared Match Request And Result

The String `Matcher` and `Utf8Matcher` should be public state machines over one
package-private matching core.  They should not call each other or convert
representations.

The core receives:

- a compiled `Pattern`/`Prog`;
- a `TextInput`;
- search, consume, boundary, anchor, and region bounds in that input's units;
- anchoring, longest-match, and capture requirements;
- optional grapheme context;
- engine-path options.

It returns match and capture bounds in the same logical coordinate system.
Public wrappers own representation-specific group materialization and
replacement state.

This separation keeps public units explicit while sharing the actual engine
semantics.  It also prevents String-only fields from becoming nullable switches
inside the existing `Matcher`.

### Engines And Accelerators

All consuming engines must use `TextInput`:

- DFA forward search;
- reverse DFA bound discovery;
- Pike VM NFA and capture extraction;
- BitState;
- OnePass;

PatternSet UTF-8 input is out of scope.  References to “all engines” in this
design mean the engines reachable from phase-one `Pattern.find(Utf8Input)` and
`Utf8Matcher.find()`, not PatternSet.

Literal and character-class fast paths must either operate through shared input
primitives or be guarded by representation.  A String-only fast path may remain
String-only, but UTF-8 must then route to the canonical engine; it must never
read a null String or approximate byte semantics.

Prefix acceleration should have representation-specific implementations
behind `indexOfLiteral`: `String.indexOf` for UTF-16 and byte search over a
precomputed UTF-8 literal for UTF-8.  Folded or nonliteral prefixes must fall
back to code-point scanning unless a separately proven accelerator exists.

Trusted malformed input makes byte-level rejection unsafe when an accelerator
depends on `U+FFFD`.  For example, the canonical decoder matches a literal
`U+FFFD` against malformed byte `FF`, while a raw byte search for `EF BF BD`
does not.  Literal, prefix, required-character, character-class, keyword, and
reverse accelerators may reject or skip only when their proof remains valid
under one-byte malformed recovery.  In particular, metadata containing
`U+FFFD` routes trusted input through decoded matching unless the input is known
validated.  Validated input may use unrestricted byte-prefix acceleration.

DFA cache keys normally remain input-encoding independent because transitions
are classified by Unicode code point.  Any cached state containing a raw
position, end-context flag, or representation-specific prefix result must name
the coordinate/context dimension explicitly.  Engine-equivalence tests must
exercise a warm DFA first on one encoding and then the other.

### Empty-Width And Boundary Semantics

Boundary predicates consume decoded scalar context, not raw neighboring bytes.
`TextInput` supplies the previous and next code points at the logical position.
Region opacity, transparency, and anchoring use the same context model as the
String matcher, expressed in representation-relative coordinates.

The following require focused treatment:

- `^`, `$`, `\A`, `\Z`, and `\z` at logical-window and region edges;
- CRLF as one line-terminator sequence even though it spans two bytes;
- ASCII and Unicode word boundaries;
- supplementary code points around empty matches;
- a region starting or ending next to a multibyte scalar;
- reverse DFA context at the start of a subview;
- transparent bounds without reading outside the root input view.

### Grapheme Semantics

The Trino RE2/J fork does not define SafeRE's broader JDK grapheme contract, so
grapheme behavior is not compared against that fork.  It is nevertheless part
of SafeRE's accepted pattern language and must work before the public UTF-8 API
is published.  The interface checkpoint may occur earlier with the API still
provisional.

UTF-8 `\X` and `\b{g}` support consumes the same
code-point and boundary-cache abstractions described in
`GRAPHEME_REGION_MATCHING.md`.  Cached tables store positions in the active
input's units and advance monotonically.  It must not decode the whole input to
`String` or add verifier searches.

### Window And Ownership Safety

Every raw array access is checked against the logical view, not merely the
backing array.  Prefix search, reverse decoding, boundary context, and trailing
line checks must not observe bytes before `offset` or after `offset + length`.

Deferred capture extraction retains the `Utf8Input` view, not a naked array.
Reset invalidates input-dependent caches and captured views exactly as the
String matcher state machine requires.  Pattern-level DFA caches may remain
when their keys are input-independent.

## Malformed UTF-8 Contract

Validated input accepts only RFC 3629 scalar encodings:

- continuation bytes must have the `10xxxxxx` form;
- two-, three-, and four-byte sequences must be complete;
- encodings must be shortest-form, rejecting overlong sequences;
- UTF-16 surrogate values are rejected;
- values above `U+10FFFF` are rejected;
- isolated continuation bytes and leading bytes outside `C2` through `F4` are
  rejected.

Validation failure throws `IllegalArgumentException` from `Utf8Input.validated`
and reports the first invalid byte offset relative to the requested view.  It
does not partially construct an input.

`Utf8Input.trusted` exists to avoid forcing a redundant pass on systems that
normally produce well-formed text.  Trino nevertheless exposes malformed
`varchar` values and has a regex regression test requiring matching to return
without throwing or hanging.  Trusted decoding therefore has a deterministic
recovery rule:

- at a valid scalar start, consume the complete shortest-form scalar;
- otherwise expose `U+FFFD` and consume exactly one byte;
- reverse decoding returns a valid scalar only when a shortest-form sequence
  ends exactly at the current position; otherwise it exposes `U+FFFD` for the
  immediately preceding byte;
- every non-end decode advances or retreats by at least one byte.

This makes forward and reverse boundaries compositional, guarantees monotonic
execution, and lets malformed Trino input return a result without an exception.
The exact match result on malformed trusted input is not a compatibility
promise, matching Trino's existing test, but termination and non-throwing
behavior are.

Validated input uses the same decoder after a one-time strict validation pass,
so its matching loop does not need a second semantic implementation.  This
deliberately does not copy upstream RE2/J's permissive leading-byte decoder.

## Trino Integration Shape

SafeRE should remain independent of Airlift.  A Trino-side adapter can map a
backed `Slice` to a trusted view:

```java
Utf8Input input =
    Utf8Input.trusted(source.byteArray(), source.byteArrayOffset(), source.length());
Utf8Matcher matcher = pattern.matcher(input);
```

`regexp_like` instead calls `pattern.findUtf8` directly on the same backing
array window so the capture-free path creates no matcher, capture array, decoded
String, or input-view object.

The adapter retains `source`.  For extraction it may return
`source.slice(matcher.start(group), matcher.end(group) - matcher.start(group))`,
which preserves Trino's existing zero-copy behavior.  For replacement it can
adapt `SliceOutput` to `Utf8Sink`.  Split and lambda replacement can continue to
assemble output from byte-relative bounds as they do today.

Before claiming Trino support, validate the adapter against every existing
Trino RE2/J regex function test, not only a standalone SafeRE suite.  This
document owns the incompatibility inventory, but the open semantic-policy
decision determines whether a difference should be fixed in SafeRE, adapted in
Trino, accepted as a migration behavior change, or treated as a blocker.

## Correctness Plan

Correctness needs three independent oracles because no one comparison covers
the complete product claim.

| Oracle | What it proves |
| --- | --- |
| SafeRE UTF-8 versus SafeRE String | Encoding-path and internal engine equivalence. |
| SafeRE UTF-8 versus Trino RE2/J | Inventory of semantic and dialect differences. |
| SafeRE-enabled Trino versus baseline Trino | SQL integration and allocation behavior. |

All three comparisons are release requirements, but they have different
authority.  SafeRE String behavior remains governed by SafeRE's JDK-first
compatibility policy.  Trino RE2/J differences are classified evidence, not
automatic SafeRE failures.  End-to-end Trino results are evaluated under the
explicit semantic policy selected after the first inventory.

### API Contract Tests

Add focused tests for:

- construction from a whole array and a nonzero-offset window;
- validated and trusted input;
- null, negative, overflowing, out-of-range, and split-scalar windows with the
  documented exception types and relative error offsets;
- relative positions for root views and internal subviews;
- repeated `find` and terminal empty-match exhaustion;
- capture bounds, named replacement resolution, unmatched groups, and
  zero-width groups;
- source slicing from capture bounds without a copy;
- append position, `appendReplacement`, and `appendTail` when sink replacement
  is selected;
- sink failure, reentrancy, copying fallback, retention/mutation preconditions,
  and post-failure append state when sink replacement is selected;
- nulls, invalid groups, invalid windows, malformed trusted bytes, and
  mutation-precondition documentation.

Add concurrency/lifetime tests showing that one `Pattern` can serve UTF-8
operations concurrently on independent inputs, matcher instances cannot be
shared concurrently, pattern caches do not retain completed inputs, and a live
matcher does retain its borrowed view.

Stateful tests should use operation traces based on
`MATCHER_STATE_MACHINE.md`, not only isolated method calls.

### String-To-UTF-8 Differential Oracle

For every well-formed Java `String` without unpaired surrogates:

1. encode it with `StandardCharsets.UTF_8`;
2. run the same pattern and operation trace through String `Matcher` and
   `Utf8Matcher`;
3. map every UTF-16 match boundary to its UTF-8 byte boundary;
4. compare booleans, complete repeated-find traces, captures, and replacement
   output after decoding the UTF-8 result.

The mapping must be precomputed in one linear pass for the test input.  Test
helpers must not hide bugs by decoding arbitrary byte prefixes with replacement
semantics.

Run this oracle over:

- the existing exhaustive regex/input generator;
- RE2's search corpus;
- SafeRE parser and matcher regression corpora;
- generated combinations of anchors, empty alternatives, captures,
  quantifiers, and flags;
- ASCII, two-byte, three-byte, supplementary, combining, emoji-ZWJ,
  regional-indicator, Indic-conjunct, CRLF, and Unicode-line inputs.

Cases with unpaired Java surrogates are not comparable to well-formed UTF-8 and
remain String-only tests.

### Engine-Path Equivalence

Extend the engine-path matrix described in `ENGINE_PATH_EQUIVALENCE.md` so each
UTF-8 case runs through applicable combinations of:

- literal and prefix accelerators enabled and disabled;
- OnePass;
- DFA forward/reverse sandwich;
- BitState;
- Pike VM NFA;
- eager and deferred capture observation;
- short and long inputs around engine-selection thresholds;
- cold and warm pattern caches, including alternating UTF-16 and UTF-8 inputs.

The Pike VM remains the semantic authority where existing SafeRE design says it
is authoritative.  Optimized paths must produce the same public trace or be
guarded away.

### Trino RE2/J Differential Inventory

Build a test adapter around `io.trino.re2j.Pattern` and run the same valid UTF-8
case through it and SafeRE.  This is a difference detector, not an oracle that
overrides SafeRE's JDK contract.  Compare:

- compile success or error category;
- boolean unanchored search;
- the complete repeated-`find` sequence;
- group-zero and every capture's byte bounds and bytes;
- unmatched versus empty captures;
- numbered and named replacement output;
- malformed replacement errors;
- matcher lifecycle traces used by the proposed adapter.

Seed this inventory with Trino's regex function cases and RE2's search corpus.
Use two bounded generated matrices: an intersection corpus expected to compile
in both engines for matching and capture comparison, and a syntax-union corpus
where compile success or error is itself the compared result.  Include explicit
unsupported non-regular syntax.  Bucket every difference as a SafeRE/JDK
compatibility bug, a documented SafeRE intentional divergence, a fork-specific
behavior that could be adapted at the
Trino SQL boundary, an accepted Trino migration behavior change, an unsupported
non-regular feature, or a migration blocker.  Do not silently exclude a case
merely because SafeRE currently rejects it or because Trino RE2/J currently
accepts it.

### Malformed And Window Fuzzing

Add byte-oriented fuzz targets that generate arbitrary arrays, offsets, and
lengths.

For validated input, compare acceptance with a strict RFC 3629 validator and
assert the first-error offset.  For trusted input, compare forward and reverse
decoding with the one-byte `U+FFFD` recovery rule.  Assert termination, bounded
work, monotonic cursor movement, no exception, and no out-of-view access.

Add a bounded malformed engine-equivalence matrix for literal and character
class `U+FFFD`, classes excluding `U+FFFD`, dot, word and line boundaries,
anchors, empty matches between malformed bytes, reverse DFA bound discovery,
and captures spanning malformed bytes.  Every enabled SafeRE path must agree
with the canonical decoder and Pike VM NFA.  Agreement with Trino RE2/J is not
required for malformed input.

Mutation fuzzing should change bytes between public matcher operations and
verify only the documented precondition boundary: validated immutable use is
fully specified; mutation is not silently presented as supported behavior.

### Trino Replacement Tests

Perform Trino substitution at three checkpoints.

The feasibility checkpoint happens before UTF-8 engine work.  In a temporary
Trino branch, adapt the current SafeRE String API by decoding each `Slice` and
mapping UTF-16 result bounds back to UTF-8 byte bounds.  The adapter may be slow
and allocation-heavy; it is throwaway diagnostic code.  Run the complete Trino
regex function suite to inventory parser, match, capture, replacement, error,
and malformed-input differences independently of the byte-input design.  Use
the results to populate the open-question register and decide whether migration
is viable under SafeRE's semantic policy.

For well-formed input, precompute the UTF-16-boundary-to-UTF-8-boundary map in
one linear pass.  Include supplementary code points and assert that reported
bounds never land inside a surrogate pair.  Do not repeatedly encode prefixes.
For malformed input, decoding destroys the original coordinate mapping, so the
feasibility adapter compares only successful non-throwing termination unless it
implements the specified one-byte recovery decoder; it must not report mapped
capture or position differences as meaningful.

The interface checkpoint happens as soon as SafeRE has a minimal windowed UTF-8
input, repeated-find matcher, capture bounds/views, and replacement path.  It
may route all matching through the Pike VM NFA.  In a Trino validation branch:

1. replace the imports and wrapper calls in `Re2JRegexp` with the provisional
   SafeRE interface;
2. remove `io.trino:trino-re2j` from the affected module's dependencies;
3. compile Trino and run its complete RE2/J regex function test surface;
4. record every missing API operation, parser difference, result difference,
   error difference, or unexpected allocation;
5. revise the SafeRE interface before migrating and optimizing every engine.

The replacement path at this checkpoint must be semantically complete under
the policy selected after the feasibility inventory, including numbered and
named references and errors, but it may be unoptimized.  This is an interface
and semantic validation, not a performance acceptance run.  It deliberately
occurs early so the public abstraction is shaped by an actual Trino
substitution rather than assumptions about call sites.

The final checkpoint uses the intended adapter after all required SafeRE engines,
accelerators, boundary behavior, and byte-native replacement are implemented.
At that checkpoint, remove the dependency again and run:

- the complete `TestRe2jRegexpFunctions` inheritance surface;
- regex cast and type tests that exercise compilation errors;
- extraction, extract-all, split, position, count, ordinary replacement, and
  lambda replacement tests;
- focused nonzero-offset `Slice` cases;
- large dictionary/block workloads if Trino has integration coverage for
  them.

At all checkpoints, run the same SQL-level cases against an unmodified Trino
baseline using Trino RE2/J and against the SafeRE branch.  Compare values,
nulls, arrays, positions, replacement output, and error category/message where
Trino exposes it.  Differences feed the semantic inventory and are evaluated
under the selected policy; they are not automatically SafeRE bugs.  A
standalone SafeRE byte matcher test is not a substitute.

The checked-in compatibility report records the exact commands, full SafeRE,
Trino, and Trino RE2/J revisions, JDK version, focused Maven modules and test
classes, required SafeRE local-install command, and all validation intentionally
not run.  At minimum it identifies the command for
`TestRe2jRegexpFunctions`, regex cast/type tests, and the containing
`trino-main` test scope.  Final validation follows the repository's external
validation workflow, including reinstalling SafeRE and rerunning each Trino
failure after its SafeRE regression test and fix.  Commit each SafeRE fix before
validation continues.

The selected policy also updates Trino's regular-expression function
documentation, `regex-library` configuration documentation, release notes,
deprecated RE2/J property documentation, and tests that intentionally define
the SQL contract.  User-visible semantic or configuration changes are not
complete until those updates are included in the Trino validation branch.

Add explicit cases where the source is a view into a larger array and where a
match or capture is multibyte.  Verify that returned `Slice` values reference
the intended bytes and that no input-wide UTF-8-to-String conversion occurs.
Trino integration is an external validation under the repository guidelines:
any SafeRE bug found during it receives a SafeRE regression test and fix before
validation continues.

### Linear-Time And Allocation Invariants

Use work-counter tests rather than elapsed-time assertions to verify:

- forward decode work grows with bytes consumed;
- reverse decode performs at most four-byte local work per scalar;
- empty-match advancement is monotonic;
- boundary and grapheme context tables are built once or amortized;
- no matcher retry loop rescans prefixes from multiple candidate positions;
- capture observation adds a bounded number of linear passes;
- malformed trusted input cannot create a non-advancing cursor;
- state and cache size remain bounded by the existing engine policies.

Allocation tests should assert the structural property that ordinary boolean
matching and repeated `find()` create no input-sized decoded object.  Group
bounds must let Trino slice its original input without copying; replacement
output is an explicit allocation point.

### Coverage Accountability

UTF-8 tests cannot be generated as direct JDK byte-API crosschecks.  They should
be marked as a SafeRE-only API whose oracle is the paired String trace.  The
crosscheck generator should still cover the String half normally.  Maintain an
inventory test ensuring that new public `Utf8Matcher` transitions are added to
the UTF-8 state-machine suite.

### Subsequent Storage Adapters

Every new storage adapter reruns the representation-independent API contract,
paired String trace, engine-equivalence, malformed-input, window, empty-match,
boundary, replacement, fuzz, and scaling suites.  Add adapter-specific cases
for its physical boundaries: direct-buffer positions and limits, values that
cross every segmented boundary, empty segments, captures spanning segments,
closed or invalidated storage, and sink dispatch over partial segments.

The first integration for an adapter must run its owning project's regex suite
and demonstrate that the hot path no longer creates an input-sized Java
`String`.  A synthetic SafeRE benchmark alone is insufficient.  Do not claim
zero-copy replacement unless both captured and unmatched ranges reach the
application's native output without coalescing or intermediate copying.

## Performance Measurement Plan

Performance work must answer four separate questions:

1. Is direct UTF-8 matching better than decoding to `String` for the target
   workload?
2. Is it competitive with Trino's current RE2/J fork on Trino operations?
3. Did the shared input abstraction regress existing String matching?
4. Does each later storage adapter beat that application's existing
   decode-to-String path without penalizing the array adapter?

### Baselines

Collect results before the implementation and after each material engine
change for:

- current SafeRE String matching on a predecoded `String`;
- SafeRE including `new String(bytes, UTF_8)` in the measured operation;
- Trino RE2/J 1.7 directly on `Slice`;
- unmodified Trino regex-function benchmarks using Trino RE2/J;
- the same Trino benchmarks using the SafeRE adapter;
- the proposed SafeRE validated UTF-8 view;
- the proposed SafeRE trusted UTF-8 view;
- SafeRE String matching after the refactor.

The predecoded String result isolates matcher throughput.  The decode-inclusive
result represents the allocation and conversion the feature is intended to
avoid.  Validated and trusted results expose the cost of the safety policy.

### Workload Matrix

Add a `Utf8MatchingBenchmark` driven by shared `benchmark-data.json` cases.  It
should cover:

- boolean unanchored search, single find, and find-all;
- no match, early match, late match, and hard failure;
- literal, character class, alternation, Unicode property, word boundary, and
  capture patterns used by Trino workloads;
- group bounds and zero-copy slicing of the original source;
- split-style bound iteration;
- replacement without references and replacement with numeric and named
  groups;
- whole arrays and nonzero-offset windows;
- matcher creation per value and, if selected, matcher reuse with `reset`;
- ASCII-heavy, Latin non-ASCII, CJK, supplementary/emoji, and combining text;
- short SQL-column values, medium application strings, and large scanning
  inputs;
- pathological/scaling patterns that demonstrate bounded execution.

Use the same pattern and logical text across every representation.  Encoding or
decoding belongs inside the benchmark only for the variants whose label says it
is included.

### Frozen Summary Sets

Freeze and commit summary membership in `benchmark-data.json` with the
feasibility report, before UTF-8 engine implementation or baseline collection.
The Trino application geomean contains exactly these logical
operations, each once for an ASCII case and once for a multibyte case:

- `regexp_like` early success;
- `regexp_like` late failure;
- `regexp_count` with multiple matches;
- `regexp_position` with a nonzero code-point start and later occurrence;
- `regexp_extract` with a participating capture;
- `regexp_extract_all` with participating and nonparticipating captures;
- `regexp_split` with multiple delimiters and a trailing empty result;
- `regexp_replace` with numbered captures;
- `regexp_replace` with named captures;
- lambda replacement observing participating and nonparticipating groups.

The SafeRE UTF-8 core geomean contains capture-free boolean search, repeated
find, capture-bound extraction, numbered replacement, named replacement,
ASCII prefix search, multibyte character-class search, and hard failure.  Each
logical member has one fixed input size recorded in `benchmark-data.json`.

Matcher construction is included for per-row Trino operations.  A separately
reported matcher-reset group measures reuse but is excluded from both primary
geomeans.  Trusted input is used in the Trino geomean.  Validated input,
decode-inclusive baselines, pathological scaling, and allocation results are
reported in separate named groups and are not mixed into the primary time
geomeans.  A failed or missing member fails the summary; it is never silently
dropped.  Changing membership or sizes after baseline collection requires
discarding and recollecting both sides.

### Metrics

Report:

- average time in ns/op;
- throughput for large scans in bytes/s;
- allocation rate and bytes/op;
- matcher/input-view objects allocated per operation;
- SafeRE UTF-8 / SafeRE predecoded String ratio;
- SafeRE UTF-8 / SafeRE decode-inclusive ratio;
- SafeRE UTF-8 / Trino RE2/J ratio;
- Trino SafeRE branch / unmodified Trino ratio at the SQL function boundary;
- before/after SafeRE String ratio.

Summarize the relevant workload groups with geometric means using the
repository's `SafeRE / competitor` convention.  Do not combine matcher-only and
decode-inclusive cases into one geomean because they answer different
questions.

### Procedure

Run SafeRE JMH benchmarks only through `./run-java-benchmarks.sh`, sequentially
and in small class batches.  Use the default configuration for iteration and
documented evidence.  Use `--long` to confirm close, surprising, or important
results.  Run Trino-level benchmarks through Trino's documented benchmark
harness or a checked-in integration script.  Never run SafeRE and Trino
benchmarks concurrently, and record the full revisions and configurations of
both projects.

Profile before optimizing:

- CPU profile ASCII and multibyte find-all cases;
- allocation profile matcher creation, group extraction, and replacement;
- inspect forward decode, reverse decode, class lookup, boundary context, and
  abstraction-dispatch costs;
- verify the JIT inlines the String and UTF-8 input implementations in hot
  loops.

Benchmark the simple shared abstraction first.  Add representation-specialized
ASCII loops or packed decode results only when profiles identify them as a
material bottleneck and engine-equivalence coverage protects the specialization.

### Acceptance Criteria

Do not use hardcoded elapsed-time thresholds in tests.  Record the quantitative
merge gates in the feasibility report before UTF-8 engine implementation or
baseline collection.  Use these defaults unless the project owner approves
different thresholds at that time:

- trusted UTF-8 eliminates input-sized decode allocation and materially reduces
  bytes allocated per operation versus decode-then-match;
- validated UTF-8 has a clearly reported validation cost and remains useful for
  callers without an existing validity contract;
- the SafeRE String core-workload geomean regresses by no more than 5%, with no
  important individual regression above 10% unless explicitly approved;
- the SafeRE-enabled Trino application-workload geomean regresses by no more
  than 10% against Trino RE2/J, with no important individual operation above
  20% unless explicitly approved;
- zero-copy group-bound operations allocate no input-sized object;
- large UTF-8 workloads preserve linear scaling;
- confidence intervals or repeated long runs distinguish close results from
  noise;
- any approved regression records the specific SafeRE safety or compatibility
  tradeoff rather than relying on an aggregate geomean to hide it.

The final evidence must include Trino-level benchmarks, not only SafeRE JMH
microbenchmarks.  The feature has not achieved its product goal if direct
matching is fast in isolation but adapter, capture, or output costs make Trino
materially slower.

An optimization that does not improve the relevant benchmark should not be
kept merely because it appears faster locally.

## Implementation Sequence

Implement this in reviewable stages.  Keep the UTF-8 APIs provisional until the
first Trino substitution validates their shape, and keep them unreleased until
the final validation is complete:

1. Perform the throwaway decode-based Trino feasibility substitution.  Build
   the semantic inventory, resolve obvious SafeRE/JDK bugs, and decide whether
   the remaining open questions permit the migration to continue.  Commit the
   compatibility report, selected policy, frozen benchmark sets, and merge
   gates before proceeding.
2. Add the representation-neutral sealed `Utf8Input`, its array-window
   implementation, and the bounded trusted decoder with deterministic one-byte
   malformed-input recovery.  Add exhaustive forward, reverse, window, and
   monotonic-advancement tests.  Verify that no public contract exposes the
   backing array or requires per-byte caller callbacks.
3. Layer strict UTF-8 validation over the same decoder for callers that want a
   validated input contract.
4. Introduce `TextInput` and migrate the Pike VM NFA first, with paired
   String/UTF-8 differential tests.
5. Add the minimal provisional `Utf8Matcher`, capture-bound, and repeated-find
   surface.  Add semantically complete but unoptimized sink replacement only if
   selected by the compatibility report; otherwise implement replacement in
   the provisional Trino adapter from match and group bounds.
6. Substitute SafeRE for `io.trino:trino-re2j` in a Trino validation branch,
   remove that dependency, compile Trino, and run its complete regex function
   tests.  Revise the interface and open-question decisions from the findings.
7. Migrate OnePass, BitState, DFA forward/reverse search, and boundary context,
   adding engine-equivalence coverage at each step.
8. Migrate literal, prefix, and character-class accelerators or guard them to
   the canonical engine until separately validated.
9. Add grapheme support before publication because SafeRE accepts grapheme
   patterns.  Leave region and bounds APIs as follow-on work unless the Trino
   inventory shows they are required.
10. Complete the `Utf8Input` and `Utf8Matcher` state machines and any operations
    shown necessary by the interface substitution.
11. Optimize the selected byte-native replacement integration without changing
    its already-tested semantics.  Publish `Utf8Sink` only if the selected
    integration uses it.
12. Run the full SafeRE suite, UTF-8 differential/fuzz/scaling suites, and
   before/after benchmarks.
13. Repeat the Trino dependency substitution with the final implementation and
    run its complete regex function suite and before/after benchmarks.
14. Resolve every open question and document each parser, matcher, replacement,
    error, configuration, and API decision from the Trino migration.
15. Update Trino function/configuration documentation, release notes,
    deprecations, and intentional SQL-contract tests for the selected policy.
16. Publish the API only after unsupported runtime feature checks have been
    eliminated and the performance evidence is recorded.

## Rejected Alternatives

### Decode The Whole Input To String

This is simple and inherits String semantics, but it performs the allocation
and conversion the feature exists to avoid.  It can remain an application
fallback, not the UTF-8 implementation.

### Duplicate Byte-Oriented Engines

Compiling UTF-8 byte transitions can make some inner loops fast, but duplicating
DFA, NFA, BitState, OnePass, compiler, boundary, and capture logic creates a
second semantic implementation.  The maintenance and correctness cost is too
high unless profiling later proves that a narrowly shared byte-transition
representation is necessary.

### Put Byte Mode In The Existing Matcher

A boolean mode makes index units, `group()` return cost, replacement types,
reset overloads, and nullable input fields depend on construction history.  A
separate public matcher with a shared internal core makes those contracts
static and reviewable.

### Expose Only `byte[]`

A whole-array-only API forces Trino to copy nonzero-offset `Slice` values.
Making the public input type itself array-specific would also preclude direct
buffers, segmented values, and off-heap column storage.  Relative logical
coordinates and a representation-neutral input type are required from the
start; the array window is only the first concrete adapter.

### Make `groupBytes()` The Primary Capture API

A `byte[]` return cannot represent a nonzero-offset view.  It must either copy
the group or expose an array containing unrelated bytes.  Caller slicing by
relative group bounds preserves the actual Trino requirement without either
tradeoff.

### Copy RE2/J's Malformed-Input Behavior

Its permissive decoding is not a complete Unicode validity contract and is
difficult to make symmetric in reverse scans.  Strict validated input plus the
explicit one-byte trusted recovery rule has a clear invariant and does not
constrain SafeRE to an accidental decoder behavior.

## Decision Summary

SafeRE should support UTF-8 through borrowed, representation-neutral
`Utf8Input` views and a distinct `Utf8Matcher` whose positions are byte-relative
to the logical value.  The first implementation is a window over `byte[]`, but
the public type and compiled pattern do not assume that storage.  All engines
share one code-point-oriented `TextInput` contract with storage-specialized,
SafeRE-owned implementations and bounded forward and reverse UTF-8 decoding.
Capture bounds let each application slice or construct its own native value
without SafeRE depending on that value's type.

Replacement either uses a dependency-free sink with array and buffer dispatch
or remains in the Trino adapter, as selected by the mandatory compatibility
report.  Later storage adapters must define their ownership, liveness,
validation, reverse traversal, range-output, and performance contracts before
publication.

This design targets the broader class of JVM applications that already store
UTF-8 text, with replacement of Trino's RE2/J fork as the first release gate;
it does not target compatibility with upstream RE2/J's lightly used `byte[]`
overloads.  It meets Trino's zero-copy `Slice` requirements without importing
Trino-specific types or maintaining duplicate matching engines, while leaving
room for measured Spark, Flink, Druid, Pinot, and Arrow integrations.  Its
initial release gate is paired String/UTF-8
correctness across all engines, strict malformed-input and window safety,
matcher-state coverage, removal of the dependency in a Trino validation
branch, conformance to the explicitly selected SQL semantic policy, linear-work
invariants, and recorded before/after Trino performance evidence.
