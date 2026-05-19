# SafeRE Exhaustive Validation

This module contains deterministic, long-running differential validation tools.
These tools are not ordinary unit tests: they stream large bounded search spaces,
compare SafeRE with `java.util.regex`, and write every divergence to JSONL so a
failed or interrupted run still leaves useful repro data.

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
parity, emoji ZWJ chains, Hangul composition chains, and surrogate boundaries.
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
behavior. A run may report known or newly discovered divergences; inspect the
JSONL output to triage them. Range bounds and replay files use the same
conventions as the other exhaustive sweeps.

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
