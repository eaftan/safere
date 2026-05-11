# SafeRE Exhaustive Validation

This module contains deterministic, long-running differential validation tools.
These tools are not ordinary unit tests: they stream large bounded search spaces,
compare SafeRE with `java.util.regex`, and write every divergence to JSONL so a
failed or interrupted run still leaves useful repro data.

## Character Class Sweep

Run through the wrapper script:

```bash
tools/exhaustive/run-character-class-sweep.sh \
  --output-dir=target/exhaustive-reports/character-class-sweep-full
```

For a smaller ad hoc local check, run a generated-case index range:

```bash
tools/exhaustive/run-character-class-sweep.sh --range=:1000000 \
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

## Grapheme Cluster Sweep

Run through the wrapper script:

```bash
tools/exhaustive/run-grapheme-cluster-sweep.sh \
  --output-dir=target/exhaustive-reports/grapheme-cluster-sweep-full
```

For a smaller ad hoc local check, run a generated-case index range:

```bash
tools/exhaustive/run-grapheme-cluster-sweep.sh --range=:250 \
  --output-dir=target/exhaustive-reports/grapheme-cluster-sweep-smoke
```

The grapheme-cluster sweep compares SafeRE with `java.util.regex` for `\X` and
`\b{g}` compile acceptance, `matches()`, `lookingAt()`, and repeated `find()`
traces including match bounds and captured group text. It covers bounded
combinations of leading combining marks, base-plus-extend clusters, CRLF,
Prepend characters, Hangul sequences, regional indicators, emoji modifiers, ZWJ
emoji sequences, supplementary code points, invalid character-class contexts,
and regions that start at or near grapheme and surrogate boundaries.

Use this sweep before review when changing grapheme-cluster parsing or boundary
behavior. A run may report known or newly discovered divergences; inspect the
JSONL output and bucket summary to triage them. Range bounds and replay files
use the same conventions as the other exhaustive sweeps.

## Control Escape Sweep

Run through the wrapper script:

```bash
tools/exhaustive/run-control-escape-sweep.sh \
  --output-dir=target/exhaustive-reports/control-escape-sweep-full
```

For a smaller ad hoc local check, run a generated-case index range:

```bash
tools/exhaustive/run-control-escape-sweep.sh --range=:100000 \
  --output-dir=target/exhaustive-reports/control-escape-sweep-smoke
```

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
