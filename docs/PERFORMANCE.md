# Performance

SafeRE's matching time is linear in the input for a compiled pattern, so the
JDK advice about avoiding catastrophic backtracking does not apply. What still
matters is how often patterns are compiled, what the pattern asks the engine to
do, and which operation is called. For how SafeRE executes a match, see
[architecture](ARCHITECTURE.md); for measurements, see the
[benchmark report](../BENCHMARKS.md).

## Compile each pattern once

`Pattern.compile` does more work in SafeRE than in `java.util.regex`. It
analyzes the pattern and prepares automata and search accelerators so that
later matches are fast, and that work is repeated every time a pattern is
compiled. A compiled `Pattern` is immutable and can be shared between threads;
a `Matcher` cannot.

For a fixed regex, store the pattern in a `static final` field, so it is
compiled once, when the class is initialized:

```java
private static final Pattern EMAIL =
    Pattern.compile("(?<user>\\w+)@(?<domain>\\w+\\.\\w+)");
```

Look for code that compiles on every call. The static
`Pattern.matches(regex, input)` compiles `regex` each time, and so does a
`Pattern.compile` call inside a loop or a request handler. For a fixed regex,
use a constant and `EMAIL.matcher(input).matches()` instead.

## Patterns built at runtime

If patterns are built at runtime, for example from configuration or user input,
and the same ones recur, keep compiled patterns in a bounded cache keyed by the
regex and flags. SafeRE does not cache compiled patterns internally. This
example uses [Caffeine](https://github.com/ben-manes/caffeine):

```java
private record PatternKey(String regex, int flags) {}

private static final LoadingCache<PatternKey, Pattern> PATTERNS =
    Caffeine.newBuilder()
        .maximumSize(1_000)
        .recordStats()
        .build(key -> Pattern.compile(key.regex(), key.flags()));

static Pattern compile(String regex, int flags) {
  return PATTERNS.get(new PatternKey(regex, flags));
}
```

Choosing the bound:

- Always bound the cache. An unbounded map keyed by user-supplied regexes grows
  without limit.
- Size it for the distinct patterns that are in use at the same time, with some
  headroom, then check the hit rate (`PATTERNS.stats().hitRate()`) under real
  traffic. If the hit rate stays low with a generous bound, the patterns do not
  recur and a cache will not help.
- Memory per entry varies with the pattern, so measure it for your workload
  rather than relying on a fixed figure. A cached pattern retains its compiled
  form, and each thread that uses it also builds its own matching state, such
  as DFA state caches. That state is bounded, but it lives at least as long as
  the pattern does, so a pattern used from many threads holds one copy per
  thread.
- Invalid regexes throw `PatternSyntaxException` and are not cached, so a
  repeated invalid regex is parsed again each time.
- A pattern used only once gains nothing from a cache; compile it directly.

### Untrusted regexes

Linear-time matching does not make compilation cheap. Compile time and
compiled size grow with the pattern, and counted repetition multiplies them:
`\d{1,1000}` expands into about a thousand copies of `\d`, where `\d+` needs
one.
Counts above 1,000 are rejected, but a single allowed count can still take far
longer to compile than to match. When regexes come from users, limit their
length and their repetition counts before compiling, and consider weighing
cache entries by regex length (Caffeine's `maximumWeight` and `weigher`).

## Writing cheaper patterns

- **Prefer unbounded or small counts.** Use `+` or `{n,}` instead of a large
  upper bound such as `{1,200}` when the bound is not a requirement. Large
  bounds mostly cost compile time and memory.
- **Search instead of wrapping in `.*`.** To test whether a line contains
  `error`, call `find()` with `error` rather than `matches()` with `.*error.*`.
  `find()` can use a fast literal search and stop at the first occurrence; the
  wrapped form has to read the whole input.
- **Use non-capturing groups `(?:…)` when you do not need the group.** Capture
  groups are part of the pattern's observable result, so they can limit which
  internal paths are available.
- **Use case-insensitive matching only when you need it.** Under `(?i)`, literal
  text matches several forms, which gives the engine a less specific string to
  search for.

Some rewrites that help backtracking engines make no meaningful difference in
SafeRE, such as replacing a reluctant `.*?` with a negated class or rewriting
an alternation of single characters such as `(?:a|e|i)` as a class `[aei]`.
Prefer the clearest form.

## Choosing the operation

- Use `find()` to search, `matches()` for whole-input validation, and
  `lookingAt()` to match at the beginning. `matches()` and `lookingAt()` only
  try the start of the input, so prefer them over an anchored `find()` when
  they say what you mean.
- Read captures only when you need them. SafeRE can postpone capture extraction
  until a group is observed, so code that only needs match bounds can use
  `start()` and `end()`.
- If text is already stored as UTF-8 bytes, use the
  [direct UTF-8 API](UTF8.md) instead of decoding to a `String` first.
- To classify text against many patterns, use a
  [`PatternSet`](PATTERN_SET.md) instead of trying each pattern in turn.

## Measuring

Measure on warmed code. The first calls run in the JVM interpreter, and
first-use costs inside SafeRE, such as building per-thread matching state, are
paid once per thread. For microbenchmarks, use [JMH](https://github.com/openjdk/jmh)
and a fixed heap size (`-Xms` equal to `-Xmx`), so heap growth does not show up
as matching time.

To find out what happened for a slow operation, use
[runtime diagnostics](DIAGNOSTICS.md), which report the strategies that
actually ran. Static pattern capabilities do not predict the path a particular
operation takes.
