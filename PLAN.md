# SafeRE — Linear-Time Regular Expression Library for Java

## Problem

Java's built-in `java.util.regex` uses a backtracking NFA engine that can exhibit
exponential worst-case time complexity. We want a regular expression library that
guarantees linear-time matching, modeled on Russ Cox's RE2 (C++ reference in
`re2-reference/`).

## Approach

Port RE2's architecture to idiomatic Java, preserving its core pipeline:

```
Pattern string → Parse → Simplify → Compile → Execute
                  ↓         ↓          ↓          ↓
               Regexp     Regexp      Prog     NFA/DFA/...
               (AST)    (simpler)  (bytecode)   (match)
```

### Key Design Decisions

- **Package**: `dev.eaftan.safere`
- **Java version**: Java 21 (LTS), leveraging sealed classes, records, pattern matching, etc.
- **Code style**: Google Java Style Guide
- **Build system**: Maven (standard, well-understood), with JaCoCo for coverage
- **Input encoding**: Java Strings are UTF-16. We operate on Unicode code points
  (like RE2 operates on runes). No byte-level matching (`\C` not supported).
- **API style**: Drop-in replacement for `java.util.regex.Pattern`/`Matcher`. Same
  class names and method signatures so users can switch by changing imports only.
- **No backtracking features**: No backreferences, no lookahead/lookbehind, no
  possessive quantifiers — these violate linear-time guarantees.

### RE2 Components → Java Mapping

| RE2 C++ Component | Java Equivalent | Notes |
|---|---|---|
| `regexp.h` (RegexpOp enum) | `RegexpOp` enum | Sealed hierarchy or enum |
| `regexp.h/cc` (Regexp class) | `Regexp` class | AST node |
| `parse.cc` (2529 lines) | `Parser` class | Recursive descent parser |
| `simplify.cc` (689 lines) | `Simplifier` class | AST simplification pass |
| `compile.cc` (1265 lines) | `Compiler` class | Thompson NFA construction |
| `prog.h/cc` (1674 lines) | `Prog` + `Inst` classes | Bytecode program |
| `nfa.cc` (714 lines) | `NFA` class | Pike VM / NFA simulator |
| `dfa.cc` (2135 lines) | `DFA` class | Lazy DFA construction |
| `onepass.cc` (623 lines) | `OnePass` class | One-pass NFA (fast path) |
| `bitstate.cc` (389 lines) | `BitState` class | Bit-parallel backtracking |
| `re2.h/cc` (2429 lines) | `RE2` class | Public API facade |
| `unicode_casefold.cc` | `UnicodeTables` | Generated Unicode data |
| `unicode_groups.cc` | `UnicodeTables` | Generated Unicode data |
| `perl_groups.cc` | `UnicodeTables` | Perl character class data |
| `walker-inl.h` | `Regexp.Walker<T>` | Non-recursive tree walker |

### Instruction Set (from `prog.h`)

8 opcodes:
- `Alt` — branch to one of two successors
- `AltMatch` — optimized Alt when one branch is a match
- `ByteRange` → `CharRange` — match a character in [lo, hi]
- `Capture` — mark submatch boundary
- `EmptyWidth` — zero-width assertions (^, $, \b, \A, \z)
- `Match` — accept
- `Nop` — no-op
- `Fail` — fail

## Implementation Phases

### Phase 1: Project Skeleton & Unicode Tables
Set up Maven project structure, establish the package, create core enums and
Unicode data tables.

- Maven `pom.xml` with JUnit 6 (6.0.3), Java 21
- Package structure: `src/main/java/dev/eaftan/safere/`
- `RegexpOp` enum (21 operators from `regexp.h`)
- `Inst.Op` enum (8 opcodes from `prog.h`)
- `EmptyOp` flags
- `UnicodeTables` class — Perl groups, Unicode categories/scripts, case folding
- `Utils` class — utility methods (isalnum, runeToString, etc.)

### Phase 2: Core Data Structures
Implement the AST and compiled program representations.

- `Regexp` class — AST node with op, subs, runes, flags, charclass, etc.
- `CharClass` class — sorted list of rune ranges, union/intersect/negate
- `Prog` class — instruction array, start index, match count
- `Inst` class — single bytecode instruction
- `RE2Flags` — parse flags (FOLD_CASE, LITERAL, DOT_NL, etc.)

### Phase 3: Parser
Port the recursive-descent parser from `parse.cc`.

- `Parser` class — the big one (~2500 lines C++)
- Handles: literals, char classes, quantifiers, groups, flags, escapes
- Character class builder (merging ranges, case folding)
- Proper error reporting with `PatternSyntaxException`
- Unit tests for parser

### Phase 4: Simplifier & Walker
Port the AST simplification pass and the non-recursive tree walker.

- `Simplifier` — collapse nested quantifiers, character class simplification
- `Walker<T>` — generic iterative tree walker (avoids stack overflow on deep ASTs)
- `Regexp.toString()` — AST back to string (useful for testing)
- Unit tests for simplification

### Phase 5: Compiler
Port the Thompson NFA compiler from `compile.cc`.

- `Compiler` class — convert Regexp AST → Prog bytecode
- Handle all RegexpOps → sequences of Inst
- Fragment-based compilation (each subexpression becomes a fragment)
- Character class compilation (ranges → CharRange instructions, or byte ranges)
- Unit tests for compilation

### Phase 6: NFA Execution Engine
Port the Pike VM (NFA simulator) from `nfa.cc`. This is the baseline engine
that handles all cases correctly.

- `NFA` class — Thompson/Pike NFA simulation
- Support for submatch tracking
- Support for all anchoring modes (unanchored, anchored start, full match)
- Integration tests: can now match simple patterns end-to-end

### Phase 7: Public API & End-to-End Testing
Build the user-facing API and comprehensive test suite.

- `Pattern` class — drop-in for `java.util.regex.Pattern`
  - `Pattern.compile(regex)`, `Pattern.compile(regex, flags)`
  - `matcher(input)`, `matches(regex, input)`, `split(input)`
  - `pattern()`, `flags()`, `toString()`
- `Matcher` class — drop-in for `java.util.regex.Matcher`
  - `matches()`, `find()`, `find(start)`, `lookingAt()`
  - `group()`, `group(int)`, `group(name)`, `groupCount()`
  - `start()`, `start(int)`, `end()`, `end(int)`
  - `replaceFirst(replacement)`, `replaceAll(replacement)`
  - `reset()`, `reset(input)`
- `PatternSyntaxException` for parse errors
- Comprehensive test suite ported from RE2's tests
- Performance smoke tests

### Phase 8: DFA Execution Engine
Port the lazy DFA from `dfa.cc` for fast boolean/location matching.

- `DFA` class — lazy DFA state construction
- State caching with bounded memory
- ByteMap optimization (character equivalence classes)
- Integration with engine selection in RE2 class

### Phase 9: Additional Engines & Optimizations
Port OnePass and BitState engines, add optimizations.

- `OnePass` class — fast path for non-ambiguous patterns with captures
- `BitState` class — bit-parallel backtracking for medium patterns
- Engine selection logic (choose fastest applicable engine)
- Prefix acceleration (literal prefix → fast skip)
- `RE2.Set` — match against multiple patterns simultaneously

### Phase 10: Polish
Final hardening, documentation, benchmarks.

- Javadoc for all public APIs
- Benchmark suite comparing against `java.util.regex` and C++ RE2 (via JNI or
  subprocess)
- README, usage examples, syntax reference
- Publish to Maven Central

## Notes

- The existing RE2/J (google/re2j) is a prior Java port. SafeRE is a fresh
  implementation leveraging modern Java features (sealed classes, records,
  pattern matching, etc.).
- The DFA (Phase 8) is the most complex single component (~2100 lines C++) and
  also the biggest performance win. It can be deferred until after a working
  MVP with NFA only.
- Unicode tables (`unicode_groups.cc` = 6517 lines) are largely generated data.
  We should write a generator or adapt the tables, not hand-port them.
- `walker-inl.h` implements a crucial pattern: iterative tree walking to avoid
  stack overflow. This is important in Java too.
