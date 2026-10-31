# Repetition policy study

These studies compare candidate repetition, match-selection, and capture rules
with observed JDK behavior. Their conclusions and decision rationale are in the
[specification's evidence appendix](../../docs/REPETITION_MATCH_SELECTION_AND_CAPTURES.md#appendix-decision-evidence).

## Running the studies

Use Python 3.13 and JDK 26.0.2, with `JAVA_HOME` pointing to that JDK. No
additional Python packages or Maven build are needed. Run from the repository
root:

```bash
python3 research/repetition-policy/run.py \
  --java "$JAVA_HOME/bin/java" \
  --output /tmp/repetition-finite-study \
  --check-recorded

python3 research/repetition-policy/other_rules.py \
  --java "$JAVA_HOME/bin/java" \
  --output /tmp/repetition-other-rules-study \
  --check-recorded
```

The recorded runtime is `26.0.2+10-55`. `--check-recorded` checks both the actual
runtime and result classifications. Omit it to collect observations with another
installed JDK. Both runners require generated output to stay outside the
checkout and retain individual observations for inspection.

## Contents

- `model.py` interprets the bounded patterns under alternative policies.
- `run.py` compares finite expansion with minimum-respecting empty-stop.
  Its primary comparison deduplicates whole-match bounds by canonical pattern
  structure and exact subject; it also records captures and operation histories.
- `other_rules.py` compares the remaining decisions one at a time, primarily
  using full first-match capture vectors. It separately checks search histories,
  wrapper pairs, zero-width possessive pairs, and API consistency.
- `StudyProbe.java` and `OtherRulesProbe.java` collect observations through public
  JDK APIs.
- `generate.py`, `run_models.py`, `analyze.py`, and `deduplicate.py` support the
  finite study. `validate_other_models.py` independently checks nested and flat
  optional ordering using explicit copies evaluated by the JDK.
- `observed-jdk26.json` and `other-rules-observed-jdk26.json` hold compact recorded
  classifications. Generated plans, patterns, individual observations, and
  expanded validation data are written to the chosen output directories.

## Scope

The interpreter enumerates execution paths for deliberately bounded patterns
and short ASCII subjects. A supplementary control checks String empty-match
advancement with astral characters. These studies provide semantic evidence;
they do not estimate production workload frequencies or establish implementation
complexity, stack safety, or performance. Regions, flags, Unicode segmentation,
`hitEnd()`/`requireEnd()`, and callback mutation are outside their coverage.
