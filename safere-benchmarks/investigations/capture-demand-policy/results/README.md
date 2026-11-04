# Historical capture-policy study

These files preserve a focused investigation snapshot, not release benchmark data
or current-version performance claims. Raw JMH JSON and logs are authoritative.

The library baseline is `524eaa4cfe09a4f40302333c436d4c19e2d8f0e8`
(2026-11-03T03:54:42Z). The identical benchmark overlay is preserved in commit
`2c562a0a6c369ce69f07893624c4d18b5e53b3d6` and `historical-harness.patch`.
The sibling `policies/` patches reproduce each alternative against that baseline;
`provenance.json` records full-file hashes of the reconstructed Matcher sources.
Its other hashes identify the original local harness capture; the complete
tracked overlay is preserved here as `historical-harness.patch`.
The `dfa` entry in that provenance describes an earlier diagnostic prototype and
is excluded here. Only corrected measurements using matching validation and
measurement cursor lifetimes are included.

The environment is OpenJDK 26.0.2 / JMH 1.37, the default scan provider, and the
virtualized i7-11700K host recorded in `cpu.txt` and `java-version.txt`. Standard
runs use two forks, two 500 ms warmups and five 500 ms measurements. Long runs use
two forks, three 1 s warmups and five 1 s measurements. Shared runs use four workers;
other runs use one. The global JMH lock prevented overlapping measurements.

- `<policy>.json/.log`: standard matrix, with exact membership in `<policy>-trials.txt`.
- `<policy>-shared.json/.log`: four shared-Pattern scenarios per policy.
- `<policy>-long.json/.log`: long confirmation for baseline, DFA-first, and local4,
  with membership in `confirmation-trials.txt`.
- `<policy>-document.json/.log`: existing scheme extraction document controls,
  using the scaling runner and microseconds per document.
- `summary.txt`: derived per-scenario comparisons; `summarize.py` regenerates it.
- `profile-summary.txt`: sampled-method summaries; full JFR captures remain local.
- `historical-commands.txt`: exact original wrapper selections and profiles.
- `SHA256SUMS`: checksums of the preserved files.

The standard baseline, DFA-first, local4 and hybrid4 sets each have 70 rows;
hybrid16 has 32 and localSticky has 40. Partial sets are compared only on shared
membership. Ratios are candidate time divided by baseline time, using geometric
means with equal weight per exact row. Policy cycles contain 1,536 documents;
ordinary document controls contain one. Raw units and JMH error estimates remain
in every JSON file. Do not combine standard and long estimates in one aggregate.

The matrix uses constructed frames/links, padding and consumption schedules, not
production traffic. Original application documents provide additional controls.
Aging did not establish a better default: local sticky history also obtains the
large rejection benefit, while Pattern-seeded leases retain rejection costs.
DFA-first makes rejection-heavy String scheme cases about 42× faster but successful
extraction about 74% slower in the long confirmation. Four-frame stack extraction
takes about 65% longer for String and 25% less time for UTF-8. These conclusions are
limited to the recorded patterns, input shapes, JVM and source revisions.

The harness is now isolated and uses a separate consumption parameter. A historical
ID such as
`CapturePolicyBenchmark.stack.ascii.128.all.text1.retained@safere-string`
corresponds to `captureTrial=CapturePolicyBenchmark.stack.ascii.128.all.retained@safere-string`
and `consumption=text1`. Its migration checks preserve cycle checksums; these old
numbers must not be presented as measurements made with the reorganized harness.

To reproduce the historical source rather than the new layout, start from the
pinned library baseline and apply `historical-harness.patch`, then one policy
patch. The commands retain the original temporary paths; adjust only those paths
for the isolated checkout and fresh output directories.

The command transcript records the local source-selection controller as well as
wrapper calls. That controller is not a replay tool; use the preserved policy
patches in isolated snapshots and the recorded wrapper selectors.
