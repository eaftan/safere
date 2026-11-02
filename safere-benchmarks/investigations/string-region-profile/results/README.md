# Historical String region history results

These measurements were collected for [#1119](https://github.com/eaftan/safere/issues/1119) with the harness initially preserved at [`7bdf853d`](https://github.com/eaftan/safere/commit/7bdf853d4460f2208b63070b83b5f52230aa2c53), before relocation into the investigations directory. They are historical evidence, excluded from recurring benchmark reports. No runtime optimization is included.

Production revisions: original #1095 base `67b5a558688b309fedb9311fd80d084bce26e670` and repaired head `fbbe9a2ca01d244326eb21183e27d8c79b63d616`. Each isolated source snapshot carried identical benchmark-only overlays, whose original paths and hashes are in [sources.json](sources.json). Those hashes describe the original layout at `7bdf853d`, not the relocated files.

Values below are ordinary `find()` ns/op, the descriptive mean of two order-reversed cycle estimates. This is not a pooled confidence interval. [summary.json](summary.json) records both estimates, each individual JMH 99.9% confidence interval, and the raw measurement iterations.

| Search start / UTF-16 length | Base, plain history | Base, mixed history | Repaired, plain history | Repaired, mixed history |
|---|---:|---:|---:|---:|
| literal / 32 | 61.53 | 72.41 | 63.40 | 77.73 |
| literal / 4096 | 144.35 | 144.00 | 139.16 | 157.94 |
| nullable / 32 | 101.62 | 117.66 | 101.02 | 129.79 |
| nullable / 4096 | 253.17 | 267.99 | 257.05 | 283.87 |

Mixed-history point estimates on the repaired head are 5.9–10.3% higher than the corresponding base estimates. Some long-search cycle estimates vary materially, so the table does not establish statistical separation for every pair. Much of the short-search history effect already exists on the original base.

Environment: OpenJDK 26.0.2+10-55, JMH 1.37, Linux on a virtualized Intel Core i7-11700K host, default String scanner provider, one thread, `-Xmx512m`. [environment.txt](environment.txt) preserves the runtime and CPU details.

All comparison invocations ran sequentially through `run-java-benchmarks.sh --long --fastbuild`: two fresh forks, three 1 s warmups, five 1 s measurements, and two cycles per case with history order `plain,mixed` then `mixed,plain`. Production revision order also alternated between cases. Input preparation and compilation were outside timing; measurement performed only ordinary searches. Standard screening and diagnostic profiling timings are excluded from these results.

Each case directory retains the original `cycle*-*.json`, corresponding `.log` files, and `commands.json`. Their bytes are preserved unchanged, including original machine-local paths. Raw JMH outputs are authoritative; summaries are derived. [SHA256SUMS](SHA256SUMS) covers every other archived file and can be checked with `sha256sum --check SHA256SUMS` from this directory.

Separate JIT/JFR diagnostics found two scanner receiver types at hot DFA calls after mixed warmup on the repaired head. Both targets were still inlined, and `Dfa.doSearchInternal` was the dominant sampled library frame in all diagnostic conditions. [profile-summary.json](profile-summary.json) preserves the derived compilation and execution-sample summaries. The larger raw JIT/JFR recordings remain local and are not included in this archive; the summary is diagnostic context rather than conclusive attribution of the additional cost.

Reproduce new measurements using the [investigation runner](../README.md), which now selects this investigation explicitly. The commands archived here refer to the original pre-relocation layout. New captures belong under ignored `benchmark-results/`; they do not update this historical snapshot automatically.
