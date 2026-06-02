# SafeRE Exhaustive Validation

This module contains deterministic, long-running differential validation tools.
These tools are not ordinary unit tests: they stream large bounded search spaces,
compare SafeRE with `java.util.regex`, and write machine-readable divergence
reports so a failed or interrupted run still leaves useful repro data.

## Compact Sweep Output

Generated indexed sweeps write durable compact divergence logs under
`--output-dir`. Each divergence is stored as a fixed 9-byte
`(caseIndex, classificationId)` record in a per-worker file. This preserves
every divergence without retaining replay JSON in memory or writing hundreds of
gigabytes of expanded JSONL during a sweep.

The compact output is checkpointed approximately every 30 seconds:

- `run-manifest.json`: sweep identity, index range, thread count, compact-format
  version, and classification name/status table.
- `progress.json`: atomically replaced progress snapshot with exact counts,
  per-classification counts, and the next case index and durable byte count for
  each worker.
- `divergence-indices/worker-NN.bin`: fixed-size compact divergence records.

Use a new output directory for each generated sweep. A generated sweep refuses
to overwrite an existing compact archive.

Expand a completed or interrupted compact archive with the separate expander
command:

```bash
./run-exhaustive-expander.sh \
  --input-dir=target/exhaustive-reports/zero-width-quantifier-sweep-full
```

The default writes exact `expanded/class-counts.tsv`, expands every `UNKNOWN`
and `EXPECTED_ZERO` divergence into replayable JSONL under `expanded/`, and
writes up to 1000 sampled `KNOWN_INTENTIONAL` divergences per classification
for audit. Known-intentional exact counts and compact indices remain available
without producing large JSONL files.

Use `--sample-limit=N` to write up to `N` deterministic samples per
classification, including `KNOWN_INTENTIONAL`. Use `--sample-limit=all` to
explicitly stream every recorded divergence in every classification into
expanded JSONL. Sweep replay mode writes bounded diagnostic reports directly
because its input is already explicit JSONL.

## Compact Known-Divergence Audit

Use compact audit after changing parser behavior, matcher behavior, or sweep
classifiers, and when reviewing whether a known-intentional bucket is hiding
bugs. It currently supports zero-width quantifier archives. It samples every
`KNOWN_INTENTIONAL` class in an existing compact archive, replays those case
indices through the current sweep classifier, and writes a transition table from
archived classification to current classification:

```bash
./run-exhaustive-audit.sh \
  --input-dir=target/exhaustive-reports/zero-width-quantifier-sweep-full
```

By default the audit samples 1000 records from each known-intentional class and
writes reports under `audit/` inside the archive:

- `source-counts.tsv`: exact archived counts and sampled counts per source
  classification.
- `transition-counts.tsv`: sampled
  `sourceClass -> replayClass` counts, including `NO_DIVERGENCE` for cases that
  no longer diverge under the current code.

Use `--sample-limit=N` for a larger audit sample, and `--output-dir=...` to keep
multiple audit runs. Suspicious transitions are source known-intentional classes
that replay as `UNKNOWN` or as an `EXPECTED_ZERO` class. A transition to
`NO_DIVERGENCE` usually means the old bucket contained cases fixed by newer
code. A transition to another `KNOWN_INTENTIONAL` class usually means the old
classifier was broader than the current one; review the target class to confirm
the remaining mismatch shape is still intentional.

The audit is intentionally classification-focused. It does not replace expanding
or replaying specific JSONL examples when a suspicious transition needs a small
human-readable reproducer.

## Full Sweep Review Procedure

Use this workflow when running a full exhaustive sweep for review:

1. Run the full sweep with a fresh `--output-dir`.
2. Run the expander on that compact archive.
3. Inspect `progress.json` and `expanded/class-counts.tsv`.
4. Fix any nonzero `EXPECTED_ZERO` classes. These are known bug classes that
   should disappear in a clean run.
5. Review and classify any `UNKNOWN` examples. Fix real bugs, or add a narrow
   classifier and documentation when the divergence is intentional.
6. Review representative `KNOWN_INTENTIONAL` samples from the expanded output.
   Confirm that the actual trace mismatch matches the documented reason for
   that class; do not assume the stored classification is correct.
7. Replay only the affected expanded JSONL files after fixes or classifier
   changes. Avoid rerunning the full sweep until targeted replay shows the
   actionable and unknown classes are clean.
8. Optionally run compact audit mode against older archives to compare archived
   known-intentional buckets with the current classifier.

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

Generated reports should stay out of git.

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
divergences, or newly discovered unknown divergences. Generated sweep mode
stores all of them in the shared compact archive. Run the expander and inspect
`expanded/class-counts.tsv` first.

This differs from the character-class and control-escape sweeps because the
grapheme sweep intentionally covers compatibility gray areas around regions,
transparent bounds, repeated `find()`, `\X`, and explicit `\b{g}` composition.
Some observed JDK traces are known implementation details rather than SafeRE
compatibility targets; see
[Intentional Divergences from java.util.regex](../INTENTIONAL_DIVERGENCES.md).
Those classes can occur in very large numbers. The shared compact archive keeps
those exact results cheap and complete. The expander controls how many replayable
JSONL examples are materialized later.

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
zero-width operands followed by bounded quantifier chains.
It is exhaustive over the committed bounded grammar: zero-width operands (single
atoms plus ordered two-atom concatenations and alternations), wrappers, first
quantifier-chain spellings, contexts, and flag/trivia modes. The chain grammar
includes greedy, reluctant, possessive, and counted quantifier spellings up to
the configured maximum chain length, then deduplicates identical concrete regex
strings. It also includes targeted sentinel cases for stack-safety and capture
semantics that are too deep or too specific for the Cartesian grammar.

Each case compares compile acceptance plus public matcher behavior:
`matches()`, `lookingAt()`, bounded repeated `find()`, capture group
start/end/text, and replacement APIs for each group. It intentionally does not
compare `hitEnd()` or `requireEnd()` because SafeRE documents those APIs as
best-effort rather than exact JDK-compatible state. It classifies known
intentional divergences according to
[Intentional Divergences from java.util.regex](../INTENTIONAL_DIVERGENCES.md).
Inputs exercise empty text, literals, line endings, word boundaries, and
grapheme-boundary-sensitive strings, including a ZWJ grapheme followed by a
literal to exercise repeated `find()` after mixed leading alternatives. The
JSONL bucket labels include operand, wrapper, quantifier chain, context, and
flag mode so repeated-quantifier parser neighborhoods are visible in reports.
Range bounds and replay files use the same conventions as the other exhaustive
sweeps.

Generated sweep mode writes the shared compact archive described above. Use the
expander to materialize exact counts and bounded or complete replayable JSONL
after the sweep.
