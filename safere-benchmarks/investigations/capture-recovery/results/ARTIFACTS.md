# Preserved evidence map

- `README.md`: readable outcome, methods, tradeoffs, and cutoff disposition.
- `summary.txt`: short plain-text version.
- `recovery-table.md`: complete standard/long recovery comparisons with raw scores and JMH errors.
- `recovery-confirm-rows.json`: separate reversed-order confirmation; not pooled into the main cohort.
- `cutoff-table.md`: complete standard cutoff comparison and 511/512/513 boundary measurements.
- `cutoff-long-table.md` / `cutoff-long-rows.json`: long 512-unit confirmation.
- `base-*` / `greedy-*`: raw recovery JMH JSON/logs and commands. “Greedy” names the character-loop fusion candidate.
- `cutoff-*/`: raw JMH output, exact command/source identities, frozen selected sources, and separate public-diagnostics path audits.
- `resolved-cutoff-corpus/`: exact generated input bytes and resolved manifest, verified identical across all source variants.
- `benchmark-test-support.tar.gz`: matching common benchmark tests needed when replaying Maven timing builds, which compile tests despite skipping their execution.
- `baseline-jfr/` / `cutoff-512-profile/jfr/`: diagnostic recordings. The profiler retains the final fork's recording per parameter set. Profiled scores are excluded from comparison tables.
- `profiles-derived.json` / `cutoff-profile-summary.txt`: approximate sample classifications and top frames, with inlining limitations.
- `tools/`: comparison and JFR-summary regeneration scripts; the collection driver lives one directory above.

Frozen source archives preserve capture-time content. Their provisional README/PLAN
wording described the deterministic control's anchored `find()` as reaching the
cutoff. The actual path audits showed that the prepared OnePass runner handles all
three operations at 512/1024. The current repository documentation and `README.md`
contain the corrected interpretation. This documentation correction changes no
Java harness, input, or measurement.

Only completed comparison and profile cohorts are archived here. Smoke output,
unit-test logs, failed builds, and interrupted runs remain local and are not
performance evidence. Historical policy-study evidence is unchanged.

`SHA256SUMS` covers every other published evidence file. Raw JMH output is
authoritative; derived tables are regenerated with `python3 tools/analyze.py --check`.
Source archives overlay the pinned baseline; also extract the common benchmark
test-support archive before building. See the reproduction commands in README.md.
