# Follow-up measurement plan

Baseline production is `4ead777e87f9209c2d9dc06fb958a23bd01349be`. Preserve exact source
patches, file hashes, wrapper commands, raw JMH JSON/logs, exit statuses, and environment under
ignored `benchmark-results/issue-1075-followups/`. The reviewed evidence is now preserved under `results/`; its source revisions and
capture-time commands remain authoritative. Collecting a result does not adopt a
production policy.

1. Profile unmodified main for stack text, occasional text, the stack document, scheme text,
   and rare scheme rotation, separately for String and UTF-8. JFR timings are not comparison
   evidence. Use samples to select a mechanism, not to claim precise time shares.
2. Screen the simple greedy BitState loop candidate against the same main and unchanged policy
   investigation harness. Include both representations and all eight preselected scenarios:
   stack text, stack one-in-four text, stack rejection, stack document, scheme text, scheme
   one-in-four text, scheme rejection, and rare rotating scheme input. Confirm the complete
   screen with `--long`, including apparent regressions. Report every row and both modes
   separately. Do not keep an optimization merely because one favorable case improved.
3. Verify observed engine paths in a separate JVM after each timing cohort. Diagnostic
   patterns omit end anchors so reverse probing at large sizes does not preempt the
   cutoff. Rejection inputs retain required literals and digits. These refinements
   were made before any cutoff measurement, following source-path review.

   Test the new cutoff protocol against independent expected checksums and JDK. Use identical
   source snapshots for cutoff comparisons, differing only in the eligibility constant.
   Screen disabled preference (0) against extended preference (2048) on stack lengths 512 and
   1024, all three operations and both representations, with `text`, `mixed`, `rare`, and
   `reject` profiles. Include the simple OnePass family with `text` and `reject` as a control. The
   observed prepared OnePass runner handles all three operations at the measured
   sizes, bypassing this capture-demand cutoff. Measure production's 512 cutoff around 511/512/513 on stack text and
   rejection. The known 512 path choices correspond to enabled preference at/below 512 and
   disabled preference above it; do not manufacture duplicate measurements for those cells.
4. Confirm important cutoff tradeoffs with `--long`, retaining their rejection or mixed
   controls. Extend lengths or profiles only to resolve observed uncertainty, identifying
   supplemental runs explicitly. A retained 512 limit is an acceptable result if workload
   tradeoffs do not justify replacing it.
5. Add only the small declared `CaptureRecoveryBenchmark` standing set. Check schema,
   materialization, and execution against String/UTF-8/JDK, with the full document extraction
   case already in `ApplicationBenchmark`. Keep diagnostic sources out of regular artifacts.

Ratios are candidate time / comparator time, below one favorable to the candidate. Show each
row's score and JMH confidence interval; summarize equal-weight cohorts only with explicit
membership and geometric means. Synthetic row weighting is not an estimate of application
frequency. No conclusion about a general optimal cutoff or deployment traffic follows from
these constructed fixtures.

The complete standard and long recovery comparisons disagree on the size of the
UTF-8 scheme-extraction cost; rejection controls also vary between runs. Confirm
stack-document extraction, scheme extraction, and scheme rejection in both
representations with one supplemental long pair, candidate first and baseline
second. Keep this cohort separate, preserving the original complete comparisons.

The initial cutoff screen shows large extraction/rejection tradeoffs and a
representation-dependent mixed-consumption result at 512. Confirm `text`, `mixed`,
and `reject` at 512 in both representations and all three operations with a long
pair in reversed order (extended preference first, disabled preference second).
Profile the current 512 behavior at 512/1024 on String with text and rejection
controls for each operation. Profiled timings remain outside comparison tables.
