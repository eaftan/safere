# SafeRE

A linear-time regular expression matching library for Java.

SafeRE is built on the work of
[Russ Cox](https://swtch.com/~rsc/regexp/), whose
[RE2](https://github.com/google/re2) library proved that regular expression
matching can be done safely in time linear in the size of the input.  Cox later
brought these ideas to Go's standard library as the
[`regexp`](https://pkg.go.dev/regexp) package.
[Alan Donovan](https://github.com/adonovan) then ported RE2 to Java as
[RE2/J](https://github.com/google/re2j).  SafeRE continues this lineage,
re-implementing the core RE2 algorithms in modern Java while providing a
drop-in replacement for `java.util.regex`.

SafeRE **guarantees linear-time matching** regardless of the pattern or input.
It achieves this by using finite automata (DFA/NFA) instead of backtracking.
Patterns that require exponential time in `java.util.regex` — such as
`a?{25}a{25}` matched against `a` repeated 25 times — complete in microseconds
with SafeRE.

## Quick Start

```java
import dev.eaftan.safere.Pattern;
import dev.eaftan.safere.Matcher;

// Compile a pattern (thread-safe, reusable)
Pattern p = Pattern.compile("(\\w+)@(\\w+\\.\\w+)");

// Match against input
Matcher m = p.matcher("contact user@example.com for info");
if (m.find()) {
    System.out.println(m.group());   // "user@example.com"
    System.out.println(m.group(1));  // "user"
    System.out.println(m.group(2));  // "example.com"
}
```

SafeRE is a drop-in replacement for `java.util.regex.Pattern` and
`java.util.regex.Matcher`. Just change your imports.

## Why SafeRE?

`java.util.regex` uses a backtracking NFA that can exhibit **exponential**
time complexity on certain patterns. This is a well-known class of
[ReDoS](https://en.wikipedia.org/wiki/ReDoS) vulnerabilities. SafeRE
eliminates this risk entirely.

| Pattern | SafeRE | RE2/J | JDK | SafeRE vs JDK |
|---|--:|--:|--:|--:|
| `a?{10}a{10}` vs `aaaaaaaaaa` | 0.063 µs | 1.77 µs | 15.6 µs | 248× |
| `a?{15}a{15}` vs `aaa...` (15) | 0.084 µs | 3.85 µs | 674 µs | 8,024× |
| `a?{20}a{20}` vs `aaa...` (20) | 0.107 µs | 6.95 µs | 27,138 µs | **253,626×** |
| `a?{25}a{25}` vs `aaa...` (25) | 0.123 µs | 11.50 µs | *(hangs)* | ∞ |

SafeRE grows linearly and is 29–93× faster than RE2/J. The JDK grows
exponentially and hangs at n=25.

## Features

- **Drop-in API** — `Pattern`, `Matcher`, and `PatternSet` mirror the
  `java.util.regex` API
- **Linear-time guarantee** — No input can cause catastrophic backtracking
- **Full Unicode** — Operates on Unicode code points, supports `\p{...}`
  properties, Unicode-aware case folding
- **Named captures** — `(?P<name>...)` syntax
- **Multi-pattern matching** — `PatternSet` matches multiple patterns
  simultaneously in a single pass
- **Five execution engines** — OnePass, DFA, BitState, NFA, and reverse DFA,
  automatically selected per query

## Comparison with RE2 Family

SafeRE is part of a family of linear-time regex libraries that share RE2's
core algorithms.  Here is how they compare:

| Feature | [RE2](https://github.com/google/re2) (C++) | [Go `regexp`](https://pkg.go.dev/regexp) | [RE2/J](https://github.com/google/re2j) | **SafeRE** |
|---|:---:|:---:|:---:|:---:|
| Language | C++ | Go | Java | Java |
| Linear-time guarantee | ✅ | ✅ | ✅ | ✅ |
| Full Unicode support | ✅ | ✅ | ✅ | ✅ |
| Submatch extraction | ✅ | ✅ | ✅ | ✅ |
| Named captures | ✅ | ✅ | ✅ | ✅ |
| DFA engine | ✅ | ❌ | ❌ | ✅ |
| NFA (Pike VM) engine | ✅ | ✅ | ✅ | ✅ |
| OnePass engine | ✅ | ✅ | ❌ | ✅ |
| BitState engine | ✅ | ✅ | ❌ | ✅ |
| Reverse DFA | ✅ | ❌ | ❌ | ✅ |
| Literal optimization | ✅ | ✅ | ✅ | ✅ |
| Multi-pattern matching | ✅ (`RE2::Set`) | ❌ | ❌ | ✅ (`PatternSet`) |
| Drop-in `java.util.regex` API | — | — | ❌ | ✅ |
| Java version | — | — | 8+ | 21+ |

## Supported Syntax

SafeRE supports most of the syntax from `java.util.regex` and RE2:

| Category | Syntax |
|---|---|
| Literals | `a`, `\n`, `\t`, `\x{1F600}`, `\Q...\E` |
| Character classes | `[abc]`, `[a-z]`, `[^0-9]`, `.` |
| Perl classes | `\d`, `\D`, `\s`, `\S`, `\w`, `\W` |
| Unicode properties | `\p{L}`, `\p{Han}`, `\P{Digit}`, `\p{Greek}` |
| POSIX classes | `[:alpha:]`, `[:digit:]`, `[:space:]` (inside `[...]`) |
| Quantifiers | `*`, `+`, `?`, `{n}`, `{n,}`, `{n,m}` |
| Non-greedy | `*?`, `+?`, `??`, `{n,m}?` |
| Alternation | `a\|b` |
| Grouping | `(...)`, `(?:...)` |
| Named captures | `(?P<name>...)` |
| Anchors | `^`, `$`, `\A`, `\z`, `\b`, `\B` |
| Flags | `(?i)`, `(?m)`, `(?s)`, `(?U)` |

### Not Supported

These features violate the linear-time guarantee and are **rejected at
compile time** with a clear error:

- **Backreferences** (`\1`, `\2`, ...) — require exponential time
- **Lookahead / Lookbehind** (`(?=...)`, `(?<=...)`, `(?!...)`, `(?<!...)`)
- **Possessive quantifiers** (`*+`, `++`, `?+`)
- **Atomic groups** (`(?>...)`)

## Semantic Differences from POSIX and java.util.regex

SafeRE follows RE2/Go semantics, which differ from POSIX and
`java.util.regex` in a few cases:

### Leftmost-first alternation (vs POSIX leftmost-longest)

POSIX specifies **leftmost-longest** semantics for alternation: among all
possible matches starting at the same position, the one that matches the
longest string wins. SafeRE (like RE2) uses **leftmost-first**: the first
alternate in the pattern that matches wins.

```
Pattern:  (a|ab|c|bcd)*
Input:    "abcd"

POSIX:    group 0 = "abcd",  group 1 = "bcd"   (longest alternates)
SafeRE:   group 0 = "abc",   group 1 = "c"     (first alternates)
```

This is the same behavior as RE2, RE2/Go, and RE2/J. It also matches the
behavior of most Perl-compatible engines.

### Nullable subgroup capture in repetitions

When a capturing group inside a repetition can match the empty string, POSIX
and `java.util.regex` may record different group positions on the final
(empty-match) iteration:

```
Pattern:  (a*)*(x)
Input:    "ax"

java.util.regex:  group 1 = (1,1)   (last iteration: empty match at pos 1)
SafeRE:           group 1 = (1,1)   (same — captures the empty match)
POSIX:            group 1 = (0,1)   (records the iteration that matched "a")
```

SafeRE agrees with `java.util.regex` and RE2/Go here, differing from POSIX.

## Flags

SafeRE supports the same flag constants as `java.util.regex.Pattern`:

| Flag | Value | Description |
|---|--:|---|
| `CASE_INSENSITIVE` | 2 | Case-insensitive matching |
| `MULTILINE` | 8 | `^` and `$` match at line boundaries |
| `DOTALL` | 32 | `.` matches line terminators |
| `UNICODE_CASE` | 64 | Unicode-aware case folding |
| `UNICODE_CHARACTER_CLASS` | 256 | Unicode-aware `\w`, `\d`, `\s` |
| `COMMENTS` | 4 | Permit whitespace and `#` comments |
| `LITERAL` | 16 | Treat pattern as a literal string |
| `UNIX_LINES` | 1 | Only `\n` is a line terminator |

```java
Pattern p = Pattern.compile("hello", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
```

## API Reference

### Pattern

```java
// Compile
static Pattern compile(String regex)
static Pattern compile(String regex, int flags)
static boolean matches(String regex, CharSequence input)
static String  quote(String s)

// Use
Matcher           matcher(CharSequence input)
String[]          split(CharSequence input)
String[]          split(CharSequence input, int limit)
Predicate<String> asPredicate()
Predicate<String> asMatchPredicate()

// Inspect
String pattern()
int    flags()
```

### Matcher

```java
// Match operations
boolean matches()                        // Full match
boolean lookingAt()                      // Prefix match
boolean find()                           // Find next match
boolean find(int start)                  // Find from position

// Match results
String group()                           // Full match text
String group(int group)                  // Numbered group
String group(String name)                // Named group
int    start() / start(int group)        // Match start position
int    end()   / end(int group)          // Match end position
int    groupCount()                      // Number of capture groups

// Replacement
String        replaceFirst(String replacement)
String        replaceAll(String replacement)
Matcher       appendReplacement(StringBuilder sb, String replacement)
StringBuilder appendTail(StringBuilder sb)

// State
Matcher     reset()
Matcher     reset(CharSequence input)
Pattern     pattern()
MatchResult toMatchResult()
```

Replacement strings support `$1`, `$2`, `${name}`, `\\`, and `\$`.

### PatternSet

Match multiple patterns simultaneously in a single pass:

```java
PatternSet.Builder builder = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
int id0 = builder.add("error.*timeout");
int id1 = builder.add("warning.*disk");
int id2 = builder.add("info.*startup");
PatternSet set = builder.compile();

List<Integer> matches = set.match("error: connection timeout");
// matches contains id0
```

## Migrating from java.util.regex

SafeRE is designed as a drop-in replacement. In most cases, you only need
to change your imports:

```java
// Before
import java.util.regex.Pattern;
import java.util.regex.Matcher;

// After
import dev.eaftan.safere.Pattern;
import dev.eaftan.safere.Matcher;
```

### What works unchanged

- `Pattern.compile()`, `Pattern.matches()`, `Pattern.quote()`
- `Matcher.matches()`, `lookingAt()`, `find()`, `group()`, `start()`, `end()`
- `replaceFirst()`, `replaceAll()`, `appendReplacement()`, `appendTail()`
- `split()`, `asPredicate()`, `asMatchPredicate()`
- All flags: `CASE_INSENSITIVE`, `MULTILINE`, `DOTALL`, `UNICODE_CASE`, etc.
- Replacement strings: `$1`, `${name}`, `\\`, `\$`

### What to watch for

1. **Backreferences** (`\1`, `\2`) — not supported; will throw
   `PatternSyntaxException` at compile time.
2. **Lookahead / lookbehind** (`(?=...)`, `(?<=...)`) — not supported.
3. **Possessive quantifiers** (`*+`, `++`) — not supported.
4. **Alternation semantics** — SafeRE uses leftmost-first (like Perl), not
   leftmost-longest (POSIX). See [Semantic Differences](#semantic-differences-from-posix-and-javautilregex).
5. **Named captures** use `(?P<name>...)` syntax (RE2-style), not
   `(?<name>...)` (Java-style). Both are accepted.

## Architecture

The processing pipeline mirrors RE2:

```
Pattern string → Parse → Simplify → Compile → Execute
                  ↓         ↓          ↓          ↓
               Regexp     Regexp      Prog     Engine
               (AST)    (simpler)  (bytecode)  (match)
```

SafeRE automatically selects the fastest engine for each query:

| Engine | When Used | Capabilities |
|---|---|---|
| **Literal** | Pattern is a plain string | `String.indexOf()` — fastest |
| **OnePass** | Pattern is unambiguous | Single-pass with captures |
| **DFA** | General patterns | Fast boolean match, no captures |
| **Reverse DFA** | Multi-find on long text | Bounds match range for NFA |
| **BitState** | Small text × program | Captures via backtracking with visited bitmap |
| **NFA** | Fallback | Full Pike VM, handles everything |

For `find()` on long texts, SafeRE uses a three-DFA sandwich (like RE2):
1. Forward DFA confirms a match exists and finds the earliest match end
2. Reverse DFA scans backward to find the match start
3. Anchored forward DFA finds the actual match end
4. NFA extracts captures on just the bounded `[start, end]` range

For a detailed architecture walkthrough, see [DESIGN.md](DESIGN.md).

## Building

Requires Java 21+ and Maven.

```bash
# Build and install (library + benchmarks)
mvn install

# Run tests
mvn test -pl safere

# Generate Javadoc
mvn javadoc:javadoc -pl safere
```

## Benchmarks

SafeRE includes a [JMH](https://github.com/openjdk/jmh) benchmark suite in the
`safere-benchmarks` module, comparing against `java.util.regex`.

### Running Benchmarks

Always use the wrapper script — it runs `mvn install` first to ensure
the benchmark module picks up the latest safere code:

```bash
# Run all benchmarks
./run-benchmarks.sh

# Run specific benchmark class(es)
./run-benchmarks.sh RegexBenchmark SearchScalingBenchmark

# Override JMH options
JMH_OPTS="-f 0 -wi 3 -i 3 -w 1 -r 1" ./run-benchmarks.sh RegexBenchmark
```

### Latest Results

See [BENCHMARKS.md](BENCHMARKS.md) for full results. Highlights:

| Benchmark | SafeRE | JDK | Ratio |
|---|--:|--:|---|
| Literal match | 3 ns | 23 ns | **8× faster** |
| Capture groups (3) | 105 ns | 115 ns | **1.1× faster** |
| Capture groups (10) | 311 ns | 364 ns | **1.2× faster** |
| Find in text (`\b` pattern) | 34,215 ns | 5,615 ns | 6.1× slower |
| Hard pattern (1 MB) | 4,715 µs | 277,413 µs | **59× faster** |
| Pathological (n=20) | 0.11 µs | 27,819 µs | **265,000× faster** |

## License

[MIT](LICENSE)

## Acknowledgments

- [RE2](https://github.com/google/re2) by Russ Cox — the C++ library whose
  design and algorithms SafeRE is based on
- [Go `regexp`](https://pkg.go.dev/regexp) by Russ Cox — the Go standard
  library implementation of RE2, which informed SafeRE's semantics and
  engine selection strategy
- [RE2/J](https://github.com/google/re2j) by Alan Donovan — the Java port of
  RE2 that demonstrated these algorithms work well on the JVM.  SafeRE's test
  suite includes tests ported from RE2/J (see [TESTING.md](TESTING.md))
- [Regular Expression Matching Can Be Simple And Fast](https://swtch.com/~rsc/regexp/regexp1.html)
  — Russ Cox's article series explaining the theory
