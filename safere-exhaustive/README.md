# SafeRE Exhaustive Validation

This module contains deterministic, long-running differential validation tools.
These tools are not ordinary unit tests: they stream large bounded search spaces,
compare SafeRE with `java.util.regex`, and write machine-readable divergence
reports so a failed or interrupted run still leaves useful repro data.

## Character Class Sweep

Run through the dispatcher script so dependency classpaths are handled by Maven:

```bash
./run-exhaustive-sweep.sh CharacterClassDivergenceSweep \
  --output-dir=target/exhaustive-reports/character-class-sweep-full
```

For a smaller ad hoc local check, run a generated-case index range:

```bash
./run-exhaustive-sweep.sh CharacterClassDivergenceSweep --range=:1000000 \
  --output-dir=target/exhaustive-reports/character-class-sweep-smoke
```

Range bounds are optional: `--range=:1000000` starts at 0, and
`--range=1000000:` runs from that index to the end.

Without `--range`, the sweep runs the committed bounded matrix completely. Use
that full run before review when character-class parser behavior changes.

The character-class sweep includes the original product matrix plus a bounded
grammar-sequence matrix. The grammar-sequence matrix composes class atoms,
intersection operators, nested classes, range tails, empty quoted literals,
comments-mode trivia, close brackets, and property classes in freer token
sequences so bugs in tail composition are not hidden by fixed templates. It also
includes a targeted comments-mode matrix for raw ampersands immediately before a
class close after range tails, where JDK syntax handling is especially sensitive
to zero-width quoted literals and skipped trivia.

The output JSONL path is printed at the end of each run. Generated reports should
stay out of git.

## Unicode Character Class Sweep

Run through the dispatcher script:

```bash
./run-exhaustive-sweep.sh UnicodeCharacterClassDivergenceSweep \
  --output-dir=target/exhaustive-reports/unicode-character-class-sweep-full
```

For a smaller ad hoc local check, run a generated-case index range:

```bash
./run-exhaustive-sweep.sh UnicodeCharacterClassDivergenceSweep --range=:1000000 \
  --output-dir=target/exhaustive-reports/unicode-character-class-sweep-smoke
```

The Unicode character-class sweep compares SafeRE with `java.util.regex` under
`UNICODE_CHARACTER_CLASS` for predefined classes, POSIX classes, and a bracketed
`\w` intersection over every Unicode scalar value. Use it before review when
changing Unicode predefined or POSIX class tables.

## Grapheme Cluster Sweep

Run through the dispatcher script:

```bash
./run-exhaustive-sweep.sh GraphemeClusterDivergenceSweep \
  --output-dir=target/exhaustive-reports/grapheme-cluster-sweep-full
```

For a smaller ad hoc local check, run a generated-case index range:

```bash
./run-exhaustive-sweep.sh GraphemeClusterDivergenceSweep --range=:250 \
  --output-dir=target/exhaustive-reports/grapheme-cluster-sweep-smoke
```

The grapheme-cluster sweep compares SafeRE with `java.util.regex` for `\X` and
`\b{g}` compile acceptance, `matches()`, `lookingAt()`, first `find()`, and
reset/reused repeated `find()` traces including match bounds and captured group
text.

The input axis is generated from Unicode grapheme-break structure rather than
only hand-picked examples. It keeps the older curated regression corpus, then
adds exhaustive short sequences over representative grapheme classes: CR, LF,
Control, Extend, ZWJ, Regional_Indicator, Prepend, SpacingMark, Hangul
L/V/T/LV/LVT, emoji modifier, Extended_Pictographic, ordinary BMP,
supplementary, and lone surrogate code units. Additional focused families cover
longer high-risk sequences around leading Extend/ZWJ runs, regional-indicator
parity, emoji ZWJ chains, Hangul composition chains, Indic conjunct linker/ZWJ
sequences, and surrogate boundaries.
Generated input labels include the grapheme-class sequence, so JSONL buckets
make missed rule neighborhoods visible instead of hiding them behind opaque
seed names.

The regex and matcher axes exercise adjacent, captured, quantified, grouped,
anchored, and source-equivalent spellings of `\X` and `\b{g}`, invalid
character-class contexts, opaque anchoring and non-anchoring bounds, and regions
that start, end, or become empty inside surrogate pairs or adjacent to grapheme
boundaries.

The sweep intentionally excludes `find()` continuation immediately after
`matches()` or `lookingAt()`: the JDK `Matcher.find()` specification defines the
starting position for the first `find()` in a region and for later successful
`find()` invocations, not for implementation state left by other match
operations.

Use this sweep before review when changing grapheme-cluster parsing or boundary
behavior. A run may report known intentional divergences, known actionable
divergences, or newly discovered unknown divergences. Inspect the class-count
summary first; it is the authoritative exact summary for a completed run.
Known intentional divergences are summarized there, but are not written into
the raw divergence JSONL.

This differs from the character-class and control-escape sweeps because the
grapheme sweep intentionally covers compatibility gray areas around regions,
transparent bounds, repeated `find()`, `\X`, and explicit `\b{g}` composition.
Some observed JDK traces are known implementation details rather than SafeRE
compatibility targets, and those classes can occur in very large numbers. The
grapheme report format therefore separates exact aggregate counts from bounded
example files: exact counts remain cheap and complete, while unknown examples
stay useful without scaling disk or heap usage with every observed divergence.

The grapheme sweep writes these files under `--output-dir`:

- `grapheme-cluster-class-counts.tsv`: exact count by divergence class,
  classification status, and rationale. Use this first to decide whether the run
  found anything actionable or unknown.
- `grapheme-cluster-divergences.jsonl`: full raw JSONL for known actionable
  divergence classes whose expected count is zero. An empty file means no
  currently-known actionable grapheme divergences were emitted; it does not mean
  there were no known intentional divergences.
- `grapheme-cluster-actionable-examples.jsonl`: bounded representative examples
  for known actionable divergence classes.
- `grapheme-cluster-unknown-first.jsonl`: first bounded examples for unknown
  divergence classes, useful for quick repros near the start of the generated
  case space.
- `grapheme-cluster-unknown-stratified.jsonl`: bounded stratified examples for
  unknown divergence classes, spread across the generated case space so a run
  with many unknown divergences does not only preserve examples from one early
  region. Configure the limit with `--unknown-stratified-samples=N`.

The default grapheme sweep progress interval is 10 million checked cases. Use
`--progress-interval=N` to override it for a particular run. Range bounds and
replay files use the same conventions as the other exhaustive sweeps.

## Control Escape Sweep

Run through the dispatcher script:

```bash
./run-exhaustive-sweep.sh ControlEscapeDivergenceSweep \
  --output-dir=target/exhaustive-reports/control-escape-sweep-full
```

For a smaller ad hoc local check, run a generated-case index range:

```bash
./run-exhaustive-sweep.sh ControlEscapeDivergenceSweep --range=:100000 \
  --output-dir=target/exhaustive-reports/control-escape-sweep-smoke
```

The first argument is the sweep class name in `org.safere.exhaustive`. All
remaining arguments are passed through unchanged to the Java sweep, which owns
option defaults and validation.

The control-escape sweep enumerates every possible Java code point as the target
of a `\cX` escape, including lone UTF-16 surrogate values. For each target it
checks bare, anchored, character-class, negated character-class, adjacent
literal, captured, and quantified contexts under no flags, comments mode,
case-insensitive mode, and both flags together. Each generated case compares
compile acceptance and full-match membership between SafeRE and
`java.util.regex`.

Use this sweep before review when changing control-escape parsing behavior. The
full matrix is bounded and has roughly 36 million generated cases. Range bounds
and replay files use the same conventions as the character-class sweep.

## Case-Folding Character-Class Sweep

Run through the dispatcher script:

```bash
./run-exhaustive-sweep.sh CaseFoldingCharacterClassDivergenceSweep \
  --output-dir=target/exhaustive-reports/case-folding-character-class-sweep-full
```

For a smaller ad hoc local check, run a generated-case index range:

```bash
./run-exhaustive-sweep.sh CaseFoldingCharacterClassDivergenceSweep --range=:100000 \
  --output-dir=target/exhaustive-reports/case-folding-character-class-sweep-smoke
```

The case-folding character-class sweep compares SafeRE with `java.util.regex`
for case-insensitive membership across every Unicode code point. It focuses on
explicit cased literals, singleton classes, high-risk ASCII ranges such as
`[h-j]`, Unicode general categories such as `\p{Lu}`, `\p{Ll}`, and `\p{Lt}`,
their common spelling variants, negated category/range forms, and a quantified
titlecase-category case. Flag axes include `CASE_INSENSITIVE`,
`CASE_INSENSITIVE | UNICODE_CASE`, and
`CASE_INSENSITIVE | UNICODE_CHARACTER_CLASS`.

Use this sweep before review when changing case-folding, Unicode category, or
character-class expansion behavior. Range bounds and replay files use the same
conventions as the other exhaustive sweeps.

## Zero-Width Quantifier Sweep

Run through the dispatcher script:

```bash
./run-exhaustive-sweep.sh ZeroWidthQuantifierDivergenceSweep \
  --output-dir=target/exhaustive-reports/zero-width-quantifier-sweep-full
```

For a smaller ad hoc local check, run a generated-case index range:

```bash
./run-exhaustive-sweep.sh ZeroWidthQuantifierDivergenceSweep --range=:10000 \
  --output-dir=target/exhaustive-reports/zero-width-quantifier-sweep-smoke
```

The zero-width quantifier sweep compares SafeRE with `java.util.regex` for
already-quantified zero-width operands followed by another quantifier suffix.
It is exhaustive over the committed bounded grammar: 253 zero-width operands
(single atoms plus all ordered two-atom concatenations and alternations), 4
wrappers, 8 first quantifiers, 21 suffix quantifier spellings, 9 contexts, and 6
flag/trivia modes, for 9,180,864 generated cases.

Each case compares compile acceptance plus `matches()`, `lookingAt()`, and a
bounded `find()` trace over short inputs that exercise empty text, literals,
line endings, word boundaries, and grapheme-boundary-sensitive strings. The
JSONL bucket labels include operand, wrapper, first quantifier, suffix
quantifier, context, and flag mode so repeated-quantifier parser neighborhoods
are visible in reports. Range bounds and replay files use the same conventions
as the other exhaustive sweeps.
