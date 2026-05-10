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
