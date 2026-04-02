# Architecture & Design

This document describes SafeRE's internal architecture and key design
decisions.  It is aimed at contributors who want to understand how the
library works under the hood.

For user-facing documentation (API reference, supported syntax, migration
guide), see [README.md](README.md).

## Processing Pipeline

SafeRE follows the same pipeline as Russ Cox's
[RE2](https://github.com/google/re2):

```
Pattern string ──► Parse ──► Simplify ──► Compile ──► Execute
                     │          │            │           │
                   Regexp     Regexp        Prog      Result
                   (AST)    (simpler)    (bytecode)  (match)
```

### 1. Parse (`Parser`)

A stack-based operator-precedence parser converts the pattern string into
a `Regexp` AST.  It supports POSIX extended regex syntax plus Perl
extensions (`(?:...)`, `(?P<name>...)`, `\d`, `\b`, etc.).

Key details:
- Implicit concatenation between adjacent atoms.
- Alternation (`|`) uses stack markers; collapsed at right-paren.
- Quantifiers applied to top-of-stack; nested quantifiers are squashed
  (e.g., `**` → `*`).
- `MAX_REPEAT = 1000` to prevent pathological AST expansion.
- Named captures tracked and validated.

### 2. Simplify (`Simplifier`)

A two-pass tree rewrite over the `Regexp` AST:

1. **Coalesce** — Merges adjacent repetitions of the same sub-expression
   within `CONCAT` nodes (e.g., `a*a+` → `a{1,∞}`).
2. **Simplify** — Converts `REPEAT{m,n}` into `STAR`/`PLUS`/`QUEST`
   combinations; simplifies degenerate character classes (empty → `NO_MATCH`,
   full → `ANY_CHAR`); collapses single-rune `LITERAL_STRING` → `LITERAL`.

### 3. Compile (`Compiler`)

Thompson NFA construction: each AST node becomes an instruction fragment
(`Frag`) with a start instruction and a patch list of dangling outputs.
Fragments are composed via standard NFA construction rules:

| AST Node    | Construction                                  |
|-------------|-----------------------------------------------|
| `CONCAT`    | Chain: connect first's outputs to second's start |
| `ALTERNATE` | `ALT` instruction branching to both fragments |
| `STAR`      | Loop: `ALT` branching to body and skip        |
| `PLUS`      | Body followed by `ALT` looping back           |
| `QUEST`     | `ALT` branching to body and skip              |
| `CAPTURE`   | `CAPTURE` instruction wrapping body fragment  |

**Anchor handling:** The compiler detects leading `^`/`\A` and trailing
`$`/`\z`, strips them from the instruction stream, and records them as
`prog.anchorStart()` / `prog.anchorEnd()` flags.  Execution engines
check the flags rather than executing anchor instructions.

**Unanchored prefix:** If the pattern isn't anchored at the start, the
compiler prepends a `.*?` loop accessible via `prog.startUnanchored()`.
The DFA uses this for unanchored search, keeping all start positions alive
within the state machine.

### 4. Execute

The compiled `Prog` is executed by one of five engines, selected based on
the pattern and input characteristics.  See [Engine Selection](#engine-selection)
below.

## Instruction Set

The compiled program (`Prog`) is an ordered list of `Inst` instructions:

| Opcode       | Purpose                              | Key Fields            |
|--------------|--------------------------------------|-----------------------|
| `ALT`        | Branch: try `out`, then `out1`       | `out`, `out1`         |
| `ALT_MATCH`  | Optimized ALT where one branch leads to MATCH | `out`, `out1` |
| `CHAR_RANGE` | Match code point in `[lo, hi]`       | `lo`, `hi`, `foldCase`, `out` |
| `CAPTURE`    | Record position in capture register  | `arg` (register), `out` |
| `EMPTY_WIDTH`| Assert empty-width condition         | `arg` (flags), `out`  |
| `MATCH`      | Accept; report match                 | `arg` (match ID)      |
| `NOP`        | No-op; continue to `out`             | `out`                 |
| `FAIL`       | Unconditional failure                | —                     |

**Capture registers** are numbered in pairs: register `2i` is the start of
group `i`, register `2i+1` is the end.  Group 0 is the full match.

**Empty-width flags** (`EmptyOp`): `BEGIN_LINE`, `END_LINE`, `BEGIN_TEXT`,
`END_TEXT`, `WORD_BOUNDARY`, `NON_WORD_BOUNDARY`.

## Engine Selection

SafeRE uses a cascade of increasingly general (but slower) engines.  The
`Matcher` class orchestrates selection:

```
                  ┌─────────────┐
                  │ Literal?    │──yes──► String.indexOf / equals
                  └──────┬──────┘
                         no
                  ┌──────┴──────┐
                  │ OnePass?    │──yes──► OnePass (anchored only)
                  └──────┬──────┘
                         no
                  ┌──────┴──────┐
                  │ DFA match?  │──no───► return false (fast reject)
                  └──────┬──────┘
                        yes
                  ┌──────┴──────┐
                  │ Text small  │──yes──► BitState (with captures)
                  │ enough?     │
                  └──────┬──────┘
                         no
                  ┌──────┴──────┐
                  │ NFA         │──────► Pike VM (with captures)
                  └─────────────┘
```

### Literal Fast Path

If the compiled pattern is a plain literal with no metacharacters, the
`Matcher` bypasses the regex engine entirely and uses `String.indexOf()`
or direct comparison.

### OnePass Engine (`OnePass`)

A deterministic NFA executed in a single linear pass.  A pattern is
"one-pass" if, after following all epsilon transitions from any state, at
most one `CHAR_RANGE` matches a given code point and at most one `MATCH`
is reachable.

- **Limit:** 16 capture groups (captures encoded in a 20-bit mask within
  a 64-bit action word).
- **Restriction:** Anchored searches only (used by `matches()` and
  `lookingAt()`).
- **Advantage:** Extracts captures in a single O(n) pass with no
  backtracking or thread management.

### DFA Engine (`Dfa`)

A lazy (on-demand) subset-construction DFA.  States are computed as
needed and cached.  The DFA answers "does a match exist?" and "where does
it end?" but **cannot extract capture groups**.

Key design elements:

- **Equivalence classes:** Code points are grouped into classes based on
  `CHAR_RANGE` instruction boundaries.  All code points in the same class
  produce identical transitions, dramatically reducing the state space.
  When the pattern contains `\b`/`\B`, word-character boundaries
  (`[0-9A-Za-z_]` edges) are added so no class straddles the word/non-word
  divide.
- **ASCII fast path:** A 128-entry lookup table maps ASCII code points
  directly to their equivalence class, avoiding binary search for the
  most common characters.
- **State budget:** A configurable maximum (default 10,000).  When
  exceeded, `computeNext` returns `null` and the caller falls back to NFA.
  No eviction — states are cached permanently once created.
- **Word boundary support (`FLAG_LAST_WORD`):** Each state carries a flag
  indicating whether the last consumed character was a word character.
  Before consuming the next character, unsatisfied `\b`/`\B` instructions
  are re-evaluated using the flag and the incoming character's word status.
  This makes word boundary evaluation deterministic within the cached
  transition framework.

**Sandwich technique** (`Matcher.doFind`): For `find()` calls, the DFA
is used in a three-step "sandwich" pattern to narrow down the match region
before invoking a capture-capable engine:

1. **Forward DFA** — Find the earliest match end.
2. **Reverse DFA** — Scan backward from the match end to find the match
   start.
3. **Forward DFA (anchored)** — Re-scan forward from the start to get
   the precise longest-match end.
4. **BitState/NFA** — Run on just the `[start, end]` slice to extract
   captures.

The reverse DFA is built lazily on first use to amortize construction
cost.

### BitState Engine (`BitState`)

Stack-based backtracking with a visited bitmap that guarantees
O(|prog| × |text|) time.  Each `(instruction, position)` pair is visited
at most once.

- **When used:** Text length ≤ `MAX_BITMAP_BITS / prog.size()` (roughly
  256 KB / program size).
- **Epsilon-cycle optimization:** Only ALT instructions involved in
  epsilon cycles are visit-tracked.  Non-cycle ALTs can be revisited,
  which is essential for nested quantifiers where an outer repetition must
  re-enter a shared ALT entry point.

### NFA / Pike VM (`Nfa`)

The most general engine: simulates all NFA threads in lockstep, with at
most one thread per NFA state at any position.

- **Thread model:** Each thread carries an instruction ID and a capture
  array.  Threads are processed in priority order (leftmost-first).
- **Match semantics:** Supports `FIRST_MATCH` (Perl-like leftmost),
  `LONGEST_MATCH` (POSIX leftmost-longest), and `FULL_MATCH` (entire
  text must match).
- **Guarantee:** O(n × m) where n is text length and m is program size.

## Key Design Decisions

### Linear-Time Guarantee

The core constraint: **no feature that requires backtracking**.
Backreferences, lookahead, lookbehind, possessive quantifiers, and atomic
groups are all rejected at parse time with a clear error message.  This
is a deliberate trade-off — these features are useful but incompatible
with worst-case linear time.

### Unicode Code Points, Not UTF-16

Java strings are UTF-16, but SafeRE operates on Unicode code points
throughout.  All character matching, position tracking, and assertions
use `Character.codePointAt()` and related methods.  RE2's `\C` ("match
any byte") is not supported because it has no meaningful equivalent in
Java's string model.

### Iterative Tree Traversal

Regexp AST traversal uses iterative walkers (the Walker pattern from RE2)
rather than recursion.  This prevents `StackOverflowError` on deeply
nested patterns like `(((((...)))))`

### DFA Word Boundary Approach

Word boundaries (`\b`/`\B`) are challenging for the DFA because they
depend on context (the previous character) that isn't part of the
transition cache key.  SafeRE solves this with three mechanisms:

1. `FLAG_LAST_WORD` in state flags carries the word-character status of
   the last consumed character.
2. Equivalence classes are refined so no class straddles a word/non-word
   boundary.
3. `computeNext()` uses a two-phase approach: re-evaluate unsatisfied
   word boundary assertions before consuming the next character, then
   proceed with normal character transitions.

This allows the DFA to handle `\b`/`\B` natively without falling back
to NFA.

### OnePass Action Encoding

OnePass actions are packed into 64-bit `long` values:
- Bits 0–7: empty-width flags
- Bits 8–27: capture mask (20 bits for 2 × 16 registers)
- Bits 28–63: next state index

This limits OnePass to 16 capture groups but avoids object allocation
during matching.

## Relationship to RE2

SafeRE is a clean-room port of RE2's architecture to Java.  The C++
reference implementation is in `re2-reference/` for comparison.  Key
differences from the C++ version:

| Aspect | RE2 (C++) | SafeRE (Java) |
|--------|-----------|---------------|
| Input encoding | UTF-8 bytes | UTF-16 → Unicode code points |
| `\C` (any byte) | Supported | Not supported (no byte-level access) |
| DFA cache | Global with mutex | Per-instance, single-threaded |
| OnePass limit | 5 capture groups | 16 capture groups (64-bit actions) |
| Memory model | Manual, with explicit budgets | GC-managed |
| Thread safety | Mutex-protected shared DFA | Not thread-safe (like `java.util.regex.Matcher`) |

## Source Layout

```
safere/src/main/java/org/safere/
├── Pattern.java          # Public API: compiled regex
├── Matcher.java          # Public API: match state, engine orchestration
├── PatternSet.java       # Public API: multi-pattern matching
├── Parser.java           # Regex parser → Regexp AST
├── Simplifier.java       # AST simplification passes
├── Compiler.java         # Thompson NFA construction → Prog
├── Prog.java             # Compiled bytecode program
├── Inst.java             # Single instruction
├── InstOp.java           # Instruction opcodes
├── Regexp.java           # AST node
├── RegexpOp.java         # AST node types
├── Dfa.java              # Lazy DFA engine
├── OnePass.java          # One-pass engine
├── BitState.java         # BitState backtracking engine
├── Nfa.java              # Pike VM engine
├── CharClass.java        # Unicode character class (sorted ranges)
├── EmptyOp.java          # Empty-width assertion flags
├── Unicode.java          # Unicode tables and properties
├── Utils.java            # Shared utilities
└── ParseFlags.java       # Parser flag constants
```
