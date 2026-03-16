# SafeRE — Agent Guidelines

## Project Overview

SafeRE is a linear-time regular expression matching library for Java, modeled on
Russ Cox's RE2. The C++ RE2 reference implementation is in `re2-reference/`.

- **Package**: `dev.eaftan.safere`
- **Java version**: 21 (LTS)
- **Build**: Maven
- **Tests**: JUnit 6 (6.0.3)
- **Coverage**: JaCoCo

## License

MIT License. All source files must include this header:

```java
// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.
```

## Code Style

Follow the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html):

- 2-space indentation, no tabs
- 100-character line limit
- Braces on same line (`if (...) {`)
- One class per file (except private inner classes)
- `static` imports grouped separately, sorted alphabetically
- Non-static imports sorted alphabetically
- No wildcard imports
- Use `@Override` on all overriding methods
- Write Javadoc for all public and protected members
- Use `{@code ...}` in Javadoc for code fragments
- Fields: `camelCase`; constants: `UPPER_SNAKE_CASE`; classes: `PascalCase`

## Project Structure

```
src/main/java/dev/eaftan/safere/   # Library source
src/test/java/dev/eaftan/safere/   # Tests
re2-reference/                      # C++ RE2 reference (read-only)
```

## Architecture

The processing pipeline mirrors RE2:

```
Pattern string → Parse → Simplify → Compile → Execute
                  ↓         ↓          ↓          ↓
               Regexp     Regexp      Prog     NFA/DFA
               (AST)    (simpler)  (bytecode)   (match)
```

Key internal classes:
- `Regexp` — AST node (operator + children)
- `CharClass` — sorted Unicode code point ranges
- `Prog` / `Inst` — compiled bytecode program
- `Parser` — recursive-descent regex parser
- `Compiler` — Thompson NFA construction
- `NFA` — Pike VM execution engine
- `DFA` — lazy DFA execution engine

Public API (drop-in for `java.util.regex`):
- `Pattern` — compiled regex (replaces `java.util.regex.Pattern`)
- `Matcher` — match state (replaces `java.util.regex.Matcher`)

## Progress Tracking

- When a phase is completed, mark it with ✅ in `PLAN.md`
  (e.g., `### Phase 1: Project Skeleton & Unicode Tables ✅`)

## Testing

- Use JUnit 6 (6.0.3) with `org.junit.jupiter.api.*` imports
- Use AssertJ (`org.assertj.core.api.Assertions.*`) for all assertions
- Test class naming: `FooTest.java` for `Foo.java`
- Use `@Test`, `@ParameterizedTest`, `@DisplayName` as appropriate
- Aim for high coverage; JaCoCo is configured in the build
- Port test cases from RE2's C++ test suite where applicable

## Key Constraints

- **Linear time**: No backreferences, no lookahead/lookbehind, no possessive
  quantifiers. These features violate linear-time guarantees and must be
  rejected at parse time with a clear error.
- **Unicode**: Operate on Unicode code points (not UTF-16 code units). Use
  `Character.codePointAt()` and related methods.
- **Stack safety**: Use iterative tree walkers (Walker pattern from RE2), not
  recursion, for Regexp tree traversal. Deeply nested regexes must not cause
  `StackOverflowError`.
- **No `\C`**: RE2's "match any byte" is not applicable to Java strings.
- **No `@SuppressWarnings`**: Do not add `@SuppressWarnings` annotations
  without explicit approval from the project owner. Fix the underlying
  issue instead.

## Benchmarking (Phase 10)

- Use JMH (Java Microbenchmark Harness)
- Compare against `java.util.regex` on the same patterns/inputs
- Compare against C++ RE2 (via subprocess invocation)
- Include pathological patterns that demonstrate exponential blowup in
  backtracking engines (e.g., `a?{n}a{n}` matched against `a{n}`)
