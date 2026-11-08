# RE2 FFM benchmark wrapper

This module exposes C++ RE2 through Java's Foreign Function and Memory API.
It is a benchmark adapter with a small public API, not a Java regex compatibility
implementation. RE2 determines pattern syntax, match selection, captures, anchors,
and Unicode behavior. Benchmark coverage is best effort: exclude incompatible
trials instead of repairing results or adding searches to imitate another engine.

## Matcher operations

`find()`, `lookingAt()`, and `matches()` use at most one native `RE2::Match` call per
operation, with unanchored, start-anchored, and fully anchored modes respectively.
Captures come from that same operation. A successful operation advances the next
`find()` to its end; an empty match advances by one Unicode code point. Repeated
`find()` calls after a failed `find()` remain exhausted. A failed `lookingAt()` or
`matches()` discards the current match without changing the next `find()` position.
`lookingAt()` and `matches()` always restart at the region beginning. Empty-match
progression intentionally does not emulate Java's individual surrogate code units.

`region(start, end)` takes inclusive/exclusive UTF-16 indices into the original
String. Returned match and capture offsets remain absolute UTF-16 indices. RE2
sees a view of the region as its input, so anchors and word boundaries cannot see
outside it. There are no configurable anchoring or transparent bounds.

Setting a region clears match state and starts searching at its beginning.
`reset()`, `reset(String)`, `find(start)`, `replaceAll`, and `replaceFirst` restore
the whole-input region. Invalid region bounds throw `IndexOutOfBoundsException`; bounds splitting a
surrogate pair throw `IllegalArgumentException`. Input encoding uses
`String.getBytes(UTF_8)`, including its replacement of malformed UTF-16. Exact Java
behavior for malformed UTF-16 and surrogate-splitting operations is unsupported.

Append-style replacement is not exposed. The existing replacement convenience
methods do not establish Java-compatible append state, and RE2's native
replacement behavior differs for some empty matches. Those differences remain;
the wrapper does not expand or repair replacement semantics.

## Benchmark boundaries

`re2-ffm-string-conversion` receives a Java String during each timed invocation.
String-to-UTF-8 encoding, offset-map construction, native allocation and input
copying, and native calls remain timed. `MatcherApiBenchmark.lookingAt`,
`MatcherApiBenchmark.newMatcherRegionFind`, and
`MatcherApiBenchmark.newMatcherRegionMatches` use a new matcher per invocation.
`MatcherApiBenchmark.resetAndFind` was previously runnable for this variant; it
is now excluded with the other retained-matcher trials because encoding would
happen during setup.
Append replacement remains excluded because the public operation is absent.

Each match operation currently copies the entire encoded input into a confined
native arena. This wrapper does not promise linear total cost for repeated
matching or tokenization; do not introduce benchmark-only buffering or repeated
searches to conceal that boundary. Retained region tokenization stays excluded.

A separate pre-existing-UTF-8 variant could help compare byte-input APIs, but is
not implemented here. It would need a distinct identity and a byte-oriented
public API specifying ownership and mutation, UTF-8 validation, copying, and
native-memory lifetimes. Pre-existing heap bytes would remove encoding cost but
would still require native transfer with the current FFM binding. Pre-existing
native storage would be a different measurement boundary again.
