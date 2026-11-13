# Optimization-focused benchmark investigation

SafeRE String is competitive across the real-world search corpus: its geometric-mean time is 0.412 of native RE2's and 0.872 of Rust regex's. Failed searches favor SafeRE; successful searches take about 1.79 times Rust's time. The default UTF-8 path is twice as fast as RE2 overall on this category, but takes 6% longer than Rust and 22% longer than SafeRE String.

These results identify specific opportunities in end-anchored searches, field-path Boolean searches, replacement, and candidate scanning. They describe the development corpus and the selected APIs, rather than a general ranking of regex libraries. This is an engineering investigation, separate from release-aligned Rebar reporting. [Raw evidence, source patches, and reproduction instructions](README.md) accompany this report.

## Scope and comparison rules

The library revision is `a2f50788701659f6367f905f7acdb9eef59a6f3a`, committed at `2026-11-12T04:25:33Z`. The collection uses SafeRE String, default SafeRE UTF-8, native C++ RE2, and Rust regex. It includes the declared development timing corpus, compilation, and first-compile workloads. It excludes the experimental Vector provider, memory measurements, Rebar, external OpenJDK-derived benchmarks, and the other comparison engines.

String matching starts with an existing Java String. SafeRE UTF-8, RE2 and Rust start with existing UTF-8 input; conversion is outside ordinary matching measurements. The UTF-8 replacement adapter produces bytes without timed decoding. Generic Boolean searches consume a Boolean; capture and replacement operations consume the declared result. String and native capture-text coordinates need not have the same representation. The resolved plan records exact patterns, options, input identities, operations and representation boundaries.

All table cells below are geometric means of per-row **SafeRE time / competitor time**, with equal weight for each expanded workload row and the shared row count in parentheses. Below 1 means SafeRE is faster. Each comparison uses its actual pairwise shared membership; no missing result is imputed. Different operations are kept separate. Cross-runtime comparisons include different API and harness overhead. The archived [aggregate catalog](aggregates.json) lists every member; [analyze.py](analyze.py) recalculates the figures.

## High-level comparisons

| Category | String / RE2 | String / Rust | UTF-8 / RE2 | UTF-8 / Rust |
|---|---:|---:|---:|---:|
| Real-world find | 0.412 (142) | 0.872 (142) | 0.500 (142) | 1.060 (142) |
| Real-world find: match | 0.886 (71) | 1.794 (71) | 0.956 (71) | 1.936 (71) |
| Real-world find: no match | 0.191 (71) | 0.424 (71) | 0.262 (71) | 0.580 (71) |
| Real-world replaceAll | 0.523 (151) | 0.880 (151) | 0.825 (151) | 1.387 (151) |
| All declared find | 0.497 (449) | 1.284 (455) | 0.466 (477) | 1.244 (483) |
| Steady-state compile | 0.645 (15) | 0.352 (15) | — | — |
| First compile | 752.698 (11) | 349.830 (14) | — | — |
| Real-world find excluding mapFieldPath | 0.379 (136) | 0.823 (136) | 0.466 (136) | 1.013 (136) |

The real-world categories select `RealWorldRegexBenchmark.*` rows by declared `find` or `replaceAll` operation. Search includes match/no-match inputs at the available sizes; replacement includes the corpus's expanded scenario variants. “All declared find” additionally includes focused accelerator, scaling, and diagnostic cases, with different coverage across representations and engines. Repeated size points and related patterns each receive a full row's weight, so that category is a development diagnostic rather than a workload-frequency model.

The field-path pattern has a large unfavorable ratio for SafeRE. Removing that entire pattern, including both match and no-match cases, improves String/Rust from 0.872 to 0.823 and UTF-8/Rust from 1.060 to 1.013. The complete category remains the primary comparison; the exclusion is a sensitivity diagnostic, not a replacement headline.

The steady-state compile category contains only the shared compilation rows; it does not establish a universal compilation advantage. First-compile measurements execute one compile inside each fresh process and include initialization incurred by that call, but exclude process launch itself. They use different sampling and runtime initialization paths, and must not be combined with steady-state compilation. The first-compile costs are a separate opportunity even though steady-state compilation is favorable on the shared rows.

Across the same real-world rows, UTF-8/String is 1.215 for search and 1.577 for replacement: UTF-8 takes 22% and 58% longer respectively. Input encoding alone therefore does not predict which path is faster. Individual workload results and accelerator selection matter.

## Optimization opportunities

The following shortlist groups measurements into investigation areas. Ordering considers absolute cost, ratio and related workload evidence; it is not a mathematically weighted ranking. Times are per operation from standard measurements. A dash means no measurement exists for that representation. Exact row identities and patterns are in [opportunities.json](opportunities.json).

| Area and representative row | String | UTF-8 | RE2 | Rust |
|---|---:|---:|---:|---:|
| End-anchored greedy success, 1 MiB | 41.31 ms | 0.356 µs | 0.117 µs | 0.068 µs |
| End-anchored early-literal dot, 1 MiB | 43.61 ms | 43.69 ms | 1.09 ms | 1.31 ms |
| Real-world field-path match, 100K | 334 µs | 160 µs | 0.116 µs | 0.068 µs |
| Protocol-link replacement, false candidates, 100K | 7.21 ms | 7.27 ms | 0.272 ms | 0.278 ms |
| Six-literal search success, 1 MiB | 598 µs | 1.91 ms | 1.10 ms | 76.0 µs |
| Fixed-offset search with colon noise | 14.8 µs | 14.5 µs | 3.09 µs | 0.589 µs |
| UTF-8 long-word literal scan | — | 6.33 µs | 5.45 µs | 0.806 µs |
| Small ASCII triple, mixed late, 1 MiB | 118 µs | 280 µs | 2.46 ms | 43.9 µs |
| Capture fallback Boolean search, 1024 | 27.3 µs | 26.3 µs | 1.15 µs | 2.83 µs |
| First compile of word property | 51.0 ms | — | 0.018 ms | 0.042 ms |

The end-anchored greedy pattern is `(?s:.*)ABCDEFGHIJKLMNOPQRSTUVWXYZ$`. Its String cost rises from about 0.411 ms at 10K to 4.06 ms at 100K and 41.3 ms at 1 MiB, while UTF-8 already has a much faster path on the largest input. This representation difference is a strong lead for engine/accelerator selection work. The early-literal end-anchored cases affect both representations and should initially be tracked separately; a shared root cause has not been established.

The field-path pattern is `([\S]+)\b\[(.+)\]`, measured as a Boolean search. Its successful-search cost grows with input size for SafeRE, whereas the native Boolean APIs complete much sooner. Establish how each API determines existence versus finding preferred match bounds before designing a fix. The comparison does not imply that capture extraction would have the same gap.

The protocol-link replacement scenarios include false candidates, alternating short/long matches and mixed inputs. Several related scenarios show substantial gaps, supporting an investigation of rejection and capture work rather than a fix for one input. The remaining scanner and capture candidates need profiling before attributing the gaps to a particular implementation choice. The small-ASCII case also illustrates why no universal ranking is warranted: SafeRE beats RE2 substantially while trailing Rust.

JFR diagnostic recordings sampled String end-anchored search predominantly in NFA search/stepping; UTF-8 field-path search predominantly in DFA search; and UTF-8 protocol-link replacement predominantly in NFA search/stepping. [Recordings and command output](raw/) identify the relevant paths, not a proven optimization design. They are non-forked profiling runs; their timing estimates do not enter these tables. Allocation or memory profiling was not indicated by the evidence gathered so far.

## Environment, measurement and limitations

The host was an Intel Core i7-11700K with 16 logical CPUs under Linux 6.6.87.2 WSL2. The run used SafeRE 1.1.0-SNAPSHOT, OpenJDK 26.0.2, JMH 1.37, Maven 3.9.14, Rust 1.97.1, and Rust regex 1.13.1. Native RE2 is pinned to 2025-11-05 and Abseil to 20250814.2 by the archived CMake source. The original capture did not record a memory-capacity snapshot or the C++ compiler version; no value is inferred from the current machine state. [environment.txt](raw/environment.txt) retains the captured details.

Standard Java timing used two forks, two 500 ms warmups and five 500 ms measurements per fork. Declared no-fork rows use the corresponding no-fork schedule; cold compilation uses ten fresh forks with a single invocation and no warmup. Native ordinary timing uses two two-second warmups and ten two-second measurements. Native first compile uses five fresh-process samples. Error fields are harness-reported 99.9% confidence intervals; estimates rounded to zero in raw output do not establish zero variance. No longer confirmation measurements were collected, as requested.

The final dataset contains 1,230 String, 1,037 UTF-8, 927 RE2, and 935 Rust measurements, with unique engine/workload identities. All selected Java trials and RE2 timing entries have results. Rust lacks `RepeatedClassFindBenchmark.lines.1024`: Rust's native iterator suppresses an empty match immediately after a nonempty match, so this workload's Java repeated-find count is incompatible. This semantic exclusion is explicit rather than a timing result.

The run required native `compileAndFind` adapter implementations and a null-mode correction for four Java tokenizer trials. Completed rows were retained while remaining rows were resumed; the four Java trials were retried. These harness fixes are separately reviewed in [PR #1183](https://github.com/safere/safere/pull/1183). The library implementation was unchanged. Original errors, successful continuations, patches, and selection lists are archived. The collection spanned multiple measurement segments and machine conditions were not controlled beyond sequential benchmark execution. Results are suitable for prioritizing large gaps, not for resolving small performance differences or claiming release performance.
