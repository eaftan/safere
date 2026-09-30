# SafeRE 1.0 benchmark report

This report presents the performance measurements for SafeRE 1.0.0. It compares SafeRE with the JDK, RE2/J,
RE2-FFM, native RE2, PCRE2 JIT, and Rust regex using two benchmark suites.

We have used **SafeRE's own suite to guide optimization**. Going forward,
we will use **Rebar as a holdout set**: we will run it for reporting, rather
than tune SafeRE against its workloads. This gives us an independent check
on whether improvements generalize beyond the benchmarks we develop against.
Rebar is not a pristine holdout for this release: a few earlier optimization
PRs used Rebar workloads. The policy applies going forward.

## Results at a glance

Across the full SafeRE suite and the curated Rebar subset, SafeRE String is
**1.54× faster than the JDK in the SafeRE suite and 6.37× faster in Rebar**.
It also leads RE2/J in both suites.
**SafeRE is modestly faster than native C++ RE2 on these measured workloads**:
it is 1.66× faster overall in its own suite and 1.22× faster on curated Rebar
workloads.
PCRE2 JIT and Rust are faster than SafeRE String on their overall comparisons.

Each value below is the geometric mean of **SafeRE String time / engine
time**. Below 1 means SafeRE String is faster; above 1 means it takes longer.
The number in parentheses is the number of measurements shared by both engines.

| Engine compared with SafeRE String | SafeRE suite: overall ratio (paired rows) | Rebar curated: overall ratio (paired rows) |
|---|---:|---:|
| JDK | **0.647 (695)** | **0.157 (34)** |
| RE2/J | **0.043 (589)** | **0.072 (31)** |
| RE2-FFM | **0.184 (539)** | — |
| Native C++ RE2 | 0.603 (580) | 0.822 (31) |
| PCRE2 JIT | 1.216 (403) | 1.426 (33) |
| Rust regex | 1.355 (589) | 3.027 (34) |
| SafeRE UTF-8 | 0.799 (530) | 0.864 (34) |
| SafeRE UTF-8 Vector | — | 0.985 (34) |

Every shared measurement has equal weight. The SafeRE-suite total includes
compilation; the Rebar total excludes it. The breakdowns below explain what
each suite measures. Engines support different subsets: the SafeRE suite has
759 String measurements and curated Rebar has 34, but each comparison uses
only those both engines completed. These are separate suite results;
we do not combine them into one score.

Compilation has a large effect on the SafeRE-suite JDK average. Excluding
116 compilation measurements and one compile-and-find measurement, SafeRE
is **5.62× faster across the remaining 578 shared workloads**. The full
overview retains these costs; it measures more than matching throughput.

Coverage can affect the result. For example, SafeRE/PCRE2 JIT is 0.820 on
SafeRE's 319 measurements supported by every tested engine, compared with
1.216 on their broader shared subset. On curated Rebar's 30 measurements
supported by every engine, SafeRE/native RE2 is 0.966 instead of 0.822.
Overall results should therefore be read alongside the workload breakdown.

SafeRE deliberately trades more compilation work for better matching
throughput. It analyzes patterns and prepares matching shortcuts that can
pay off when a compiled pattern is reused. Compilation time and compiled
pattern memory are meaningful costs of that design.

[Raw results, source definitions, and calculation script](benchmark-results/published/5bfcec92acda26fcde50fc8bc510f3a4afe855ea/)
are included with this report.

## SafeRE suite: deep dive

The suite covers searches, replacements, captures, compilation, and focused
scaling tests. Its 759 String measurements include 307 real-world cases,
98 search-scaling cases, 76 Unicode-compilation cases, 36 first-use Unicode
compilation cases, and 242 other focused cases.

### Matching and replacement

SafeRE's largest advantage over the JDK is in real-world searches, especially
when there is no match. Across 142 search measurements, SafeRE is **12.7×
faster than the JDK**, **22.9× faster than RE2/J**, and **8.96× faster than
RE2-FFM**.

Two patterns have unusually expensive JDK failed searches. At 100K input
size, `structuredJsonPath` and `wildcardSearch` take 17.9 and 40.1 seconds
per operation with the JDK, versus 12.1 and 52.8 microseconds with SafeRE.
Removing both entire patterns leaves 130 measurements and a **10.2× lead
over the JDK**.

The table separates API operations. All cells use the same time ratio and
measurement count convention as the overview.

| Category | Membership | vs JDK | vs RE2/J | vs RE2-FFM |
|---|---|---:|---:|---:|
| Real-world search | 24 `find` patterns, match/no-match inputs at 1K, 10K, and 100K; 142 rows | 0.079 (142) | 0.044 (142) | 0.112 (142) |
| Real-world replacement | 23 `replaceAll` patterns and the same input grid; 138 SafeRE rows | 0.214 (126) | 0.046 (126) | 0.406 (126) |
| Real-world capture search | Three `findGroup` patterns; 18 rows | 0.036 (18) | 0.026 (18) | 0.344 (18) |
| Real-world full match | One `matches` pattern; 6 rows | 3.159 (6) | 0.220 (6) | 0.087 (6) |
| Real-world split | One `splitLengthSum` pattern; 3 rows | 0.249 (3) | 0.075 (3) | 0.188 (3) |
| Core matching/search | Six `RegexBenchmark` rows | 0.841 (6) | 0.091 (6) | 0.430 (6) |
| Application tasks | Eight `ApplicationBenchmark` rows | 1.124 (8) | 0.122 (8) | 0.568 (8) |
| Compilation | Four `CompileBenchmark` patterns | 109.579 (4) | 21.499 (4) | 3.711 (4) |

Most real-world patterns use matching and non-matching inputs at 1K, 10K,
and 100K. One search pattern omits the 1K size. Successful searches show a
smaller advantage than failed searches:

| `find` outcome | 1K | 10K | 100K |
|---|---:|---:|---:|
| Match: SafeRE/JDK | 0.491 (23) | 0.356 (24) | 0.344 (24) |
| No match: SafeRE/JDK | 0.031 (23) | 0.014 (24) | 0.010 (24) |

SafeRE does not win every workload. It takes 10.6× the JDK time on
`cjkSearch`, 1.87× on `poisonousSpacePrefix`, and 1.52× on `emojiSearch`,
averaged across each pattern's inputs. The application category takes 12%
longer than the JDK overall.

Some patterns cannot be compared across all engines. RE2/J and RE2-FFM
reject the Java Unicode escapes in two citation-replacement patterns and
`(?iu)` in one case-folding pattern. Larger citation and `recitation`
inputs are excluded for the JDK because its matcher throws
`StackOverflowError`. Failed and excluded measurements do not enter the
averages; the published results retain their details.

### Native engines and UTF-8

On real-world searches, SafeRE is faster than native RE2, close to Rust,
and slower than PCRE2 JIT overall. The result depends on whether the search
succeeds: SafeRE takes about 2× the Rust time on matching inputs, but less
than half on non-matching inputs.

| Category | Native RE2 | PCRE2 JIT | Rust regex | SafeRE UTF-8 |
|---|---:|---:|---:|---:|
| Real-world `find` | 0.465 (142) | 1.247 (142) | 0.985 (142) | 0.716 (142) |
| Real-world `replaceAll` | 0.558 (138) | — | 0.921 (138) | 0.552 (138) |
| Real-world `findGroup` | 0.433 (18) | 1.329 (18) | 0.564 (18) | — |
| Real-world `splitLengthSum` | 0.262 (3) | 0.687 (3) | 0.696 (3) | — |
| Core matching/search | 0.567 (6) | 0.806 (6) | 1.216 (6) | 0.904 (5) |
| Application tasks | 0.996 (8) | 2.411 (7) | 1.444 (8) | 0.670 (3) |
| Compilation | 5.440 (4) | 4.254 (4) | 1.155 (4) | — |

These comparisons include each runtime's API costs. SafeRE String, JDK,
and RE2/J use Java strings. SafeRE UTF-8 and the native engines use
pre-existing UTF-8 input. RE2-FFM includes converting a Java string to UTF-8
and calling the native engine. The SafeRE suite uses the default UTF-8
scanner; Rebar also measures the experimental Vector scanner.

### Compilation, memory, and difficult inputs

Across four ordinary compile workloads, SafeRE takes **110× the JDK
compilation time**. Its measured compile times range from about 11 to
46 microseconds. This is an intentional tradeoff: SafeRE does more pattern
analysis and prepares accelerators to improve repeated matching. Applications
that compile once and match many times can benefit; applications that compile
a new pattern for every match pay the compilation cost repeatedly. Reusing
compiled patterns is especially valuable with SafeRE. For a fixed regex used
repeatedly, store its `Pattern` in a `static final` field. This follows the
Pattern-reuse advice in *Effective Java*, third edition,
[Item 6: Avoid creating unnecessary objects](https://www.oreilly.com/library/view/effective-java-3rd/9780134686097/ch2.xhtml).

Compiled patterns also retain more memory. For a simple pattern, the measured
sizes were 7,380 bytes for SafeRE, 756 for JDK, and 652 for RE2/J. For an
alternation pattern, they were 22,116, 964, and 3,500 bytes respectively.
These figures measure retained pattern data, rather than whole-process memory.

The difficult-input tests illustrate why bounded matching behavior matters.
For `a?{20}a{20}` on `a{20}`, SafeRE takes 0.093 microseconds per operation,
compared with about 16 milliseconds for the JDK. Native RE2 and Rust are
also fast on this case, at 0.108 and 0.058 microseconds respectively.

## Rebar curated subset: deep dive

Rebar's curated workloads are selected for broad comparisons between engines.
Its [contributor guide](https://github.com/BurntSushi/rebar/blob/master/CONTRIBUTING.md#adding-a-new-benchmark)
explains that these workloads form its public comparison set and face a
higher inclusion threshold than the rest of the collection. We use this
subset throughout the report. The full collection is retained as raw evidence.

We use the publicly available
[`personal` branch of eaftan/rebar](https://github.com/eaftan/rebar/tree/personal),
which adds RE2/J and SafeRE runners to upstream Rebar. The JDK runner is
already available upstream. SafeRE supports 34 curated workloads covering match counts, match spans, capture counts, line searches,
and line searches with captures.

Across the shared curated workloads, SafeRE String is **6.37× faster than
the JDK**, **14.0× faster than RE2/J**, and **1.22× faster than native RE2**.
It takes **43% longer than PCRE2 JIT** and **3.03× as long as Rust**.

<details>
<summary>Results by operation for the curated subset</summary>

| Rebar model (SafeRE rows) | JDK | RE2/J | Native RE2 | PCRE2 JIT | Rust regex | SafeRE UTF-8 | UTF-8 Vector |
|---|---:|---:|---:|---:|---:|---:|---:|
| `count` (18) | 0.197 (18) | 0.069 (18) | 0.707 (18) | 2.770 (17) | 4.076 (18) | 0.828 (18) | 1.067 (18) |
| `count-spans` (9) | 0.053 (9) | 0.045 (6) | 1.221 (6) | 0.250 (9) | 2.038 (9) | 0.902 (9) | 0.897 (9) |
| `count-captures` (1) | 2.523 (1) | 0.074 (1) | 0.460 (1) | 5.007 (1) | 3.747 (1) | 0.975 (1) | 0.999 (1) |
| `grep` (1) | 0.153 (1) | 0.853 (1) | 1.380 (1) | 1.483 (1) | 2.752 (1) | 1.412 (1) | 1.366 (1) |
| `grep-captures` (5) | 0.282 (5) | 0.085 (5) | 0.891 (5) | 2.645 (5) | 2.064 (5) | 0.826 (5) | 0.815 (5) |

</details>

### Where the result varies

On 18 match-counting workloads, SafeRE is **5.08× faster than the JDK**.
One dictionary search has an especially large advantage; excluding it still
leaves a **3.25× lead** across the other 17 workloads.

The native RE2 comparison is also sensitive to that dictionary workload.
Removing it changes SafeRE/RE2 from 0.822 across 31 shared workloads to
0.966 across the remaining 30: close to parity.

SafeRE loses to the JDK on some curated workloads. It takes 2.72× as long
on `curated/11-unstructured-to-json/extract`, 2.52× on
`curated/05-lexer-veryl/single`, and 1.34× on
`curated/08-words/all-russian`.

### UTF-8 and coverage

Across all 34 workloads, String takes **13.6% less time than default UTF-8**.
Vector UTF-8 takes **12.3% less time than default UTF-8**, bringing it close
to String. These are direct comparisons; the overview table compares each
UTF-8 variant against String.

Vector helps some searches substantially: `curated/02-literal-alternate/sherlock-en`
takes 168 microseconds versus 2.56 milliseconds without Vector. It also
loses on some workloads, so enabling it is not a universal speedup.

The JDK, Rust, and both SafeRE UTF-8 variants share all 34 workloads with
SafeRE String. RE2 and RE2/J share 31; PCRE2 JIT shares 33. Each comparison
uses its actual shared subset, rather than treating missing results as losses.

Compilation is excluded from this Rebar comparison. Rebar's
[Java runner documentation](https://github.com/eaftan/rebar/blob/9ee90dfb569cbcfb81b8a23a4b99b12bfff0e1d7/engines/java/README.md)
reports implausibly fast repeated compilation and suspects JVM optimization.
The curated workloads therefore omit both Java and SafeRE compilation.
Compilation costs are measured separately in the SafeRE suite.

## Reproducing the measurements

The exact SafeRE source measured was
[`5bfcec92acda26fcde50fc8bc510f3a4afe855ea`](https://github.com/eaftan/safere/commit/5bfcec92acda26fcde50fc8bc510f3a4afe855ea),
committed **2026-09-26T00:51:20Z**.

Rebar was measured at
[`9ee90dfb569cbcfb81b8a23a4b99b12bfff0e1d7`](https://github.com/eaftan/rebar/commit/9ee90dfb569cbcfb81b8a23a4b99b12bfff0e1d7)
on its `personal` branch. The
[artifact README](benchmark-results/published/5bfcec92acda26fcde50fc8bc510f3a4afe855ea/README.md)
provides the benchmark harness patches, exact workload definitions, commands,
and checksums needed to reproduce both collections. These patches affect
benchmark setup, not SafeRE's matching implementation.

| Environment | Version |
|---|---|
| CPU | Intel Core i7-11700K, 8 cores / 16 threads |
| OS and memory | Linux 6.6.87.2 on WSL2, 15 GiB visible memory |
| Java and JMH | OpenJDK 26.0.2+10-55; JMH 1.37 |
| RE2/J / RE2 / PCRE2 | 1.8 / 2025-11-05 / 10.47 with JIT |
| Rust regex | 1.13.1 in the SafeRE suite; 1.12.4 in Rebar |
| Build tools | g++ 13.3, CMake 4.3, rustc 1.97.1 |

The SafeRE suite uses standard-mode JMH averages: two forks, two 500 ms
warmup iterations, and five 500 ms measurement iterations per fork. Native
harnesses use two 2-second warmup rounds and ten 2-second measurement rounds.
Rebar reports medians, with up to 1.5 seconds of warmup and 3 seconds of
sampling. Specialized first-use and memory measurements have their own
settings, recorded in the artifacts.

The overview gives equal weight to each included shared timing measurement.
SafeRE-suite compilation is included; Rebar compilation is excluded as
explained above. Memory measurements are separate. Java and
native confidence intervals are 99.9%; Rebar CSVs include dispersion measures.
Close results in this standard-mode collection should be treated as approximate;
we have not run longer confirmation measurements.
