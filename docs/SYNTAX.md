# Syntax and compatibility

SafeRE targets the `java.util.regex` dialect within its linear-time matching
contract. This is an overview; the
[intentional divergences](../INTENTIONAL_DIVERGENCES.md) record compatibility
boundaries and their rationale.

## Supported syntax

SafeRE supports most of the syntax from `java.util.regex`:

| Category | Syntax |
|---|---|
| Literals | `a`, `\n`, `\t`, `\x{1F600}`, `\Q...\E` |
| Character classes | `[abc]`, `[a-z]`, `[^0-9]`, `.` |
| Perl classes | `\d`, `\D`, `\s`, `\S`, `\w`, `\W` |
| Unicode properties | `\p{L}`, `\p{IsHan}`, `\P{Digit}`, `\p{Lower}` |
| Quantifiers | `*`, `+`, `?`, `{n}`, `{n,}`, `{n,m}` |
| Non-greedy | `*?`, `+?`, `??`, `{n,m}?` |
| Alternation | `a\|b` |
| Grouping | `(...)`, `(?:...)` |
| Named captures | `(?<name>...)` |
| Anchors and boundaries | `^`, `$`, `\A`, `\Z`, `\z`, `\b`, `\B`, `\b{g}` |
| Line breaks and graphemes | `\R`, `\X` |
| Flags | `(?i)`, `(?m)`, `(?s)`, `(?x)`, `(?u)`, `(?d)`, `(?U)` |

## Unsupported features

SafeRE rejects these constructs at compile time with `PatternSyntaxException`
because their semantics are outside its supported engine model:

- **Backreferences** (`\1`, `\2`, ...)
- **Lookahead / Lookbehind** (`(?=...)`, `(?<=...)`, `(?!...)`, `(?<!...)`)
- **Possessive quantifiers over consuming operands** (`a*+`, `a++`, `a?+`)
- **Atomic groups** (`(?>...)`)
- **Byte matching** (`\C`)
- **Previous-match boundary** (`\G`)

Possessive modifiers over statically zero-width operands can be normalized;
see the [compatibility policy](../INTENTIONAL_DIVERGENCES.md#unsupported-backtracking-features)
for that exception.

Additionally, the `CANON_EQ` flag is not supported.  This flag enables
matching based on Unicode canonical equivalence (e.g., treating a precomposed
character the same as its decomposed form).  It is outside the supported matching contract.

The `Matcher.hitEnd()` and `Matcher.requireEnd()` APIs are not supported.
These methods expose details of the JDK backtracking engine's search order,
including which alternatives and quantified paths the engine tried before
stopping.  SafeRE's linear-time engines explore possible states in lockstep
instead, and exactly reproducing the JDK's observer state would require
simulating backtracking-style path priority in cases that are incompatible
with SafeRE's performance model. Applications that rely on these methods,
including streaming-tokenizer integrations, must account for this limitation.

Counted repetitions are limited to 1000; nested counted repetitions are also
checked against the parser's expansion limit. Patterns exceeding these limits
are rejected at compile time.

## Match selection

SafeRE uses leftmost-first alternation: the first alternative that can complete
the match wins. Greedy and reluctant quantifiers preserve that path priority.

## Unicode version

SafeRE supports Unicode 17.0 for Unicode regex properties such as `\p{L}`,
`\p{IsHan}`, `\p{script=Latin}`, and `\p{block=BasicLatin}`. General categories,
scripts, blocks, and binary-property membership use tables bundled with SafeRE.
Not all Unicode behavior is independent of the runtime JDK: Unicode POSIX
`Blank`, `Graph`, and `Print` use runtime `Character` predicates, and Unicode
case-equivalence closure incorporates runtime Java case mappings.

The `java*` property family, such as `\p{javaLowerCase}` and
`\p{javaJavaIdentifierStart}`, continues to follow the running JDK's
`java.lang.Character` predicates because those properties are explicitly
defined by Java in terms of the runtime `Character` implementation.

## Flags

SafeRE supports these `java.util.regex.Pattern` flags; `CANON_EQ` is excluded:

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

## SafeRE extension

Named captures accept Java's `(?<name>...)` syntax and the Python-style spelling
`(?P<name>...)`. Python-style named backreferences are not supported.
