# Java Benchmark Configuration Evaluation

## Goal

The goal of this experiment was to find the fastest Java benchmark
configuration that gives stable enough results for engineering decisions.

The benchmark question we care about is not just whether an individual raw JMH
score is stable. Most SafeRE benchmark decisions are comparative: did SafeRE get
faster or slower relative to JDK, RE2/J, or RE2-FFM? For that reason, this
experiment evaluates stability of SafeRE/competitor ratios across independent
benchmark invocations.

## Experiment

- Benchmarked commit: `6b89c5360ae07956d07a5366d999b20b33072b44`
- Commit date/time: 2026-06-19T23:02:22Z
- Repeats: 3 independent process-level invocations per config/task
- Winner deadband: +/- 5%
- Script: `safere-benchmarks/scripts/evaluate-java-benchmark-configs.py`

The repeated invocations are intentionally process-level repeats. JMH's internal
samples are useful, but the development workflow question is: if we rerun the
benchmark command, does the conclusion hold?

## Metrics

The primary decision metric is **p90 ratio CV**.

Ratio CV is the coefficient of variation of the SafeRE/competitor ratio across
independent invocations. It directly measures whether the comparative benchmark
result is stable. The p90 value is used because median ratio CV is too forgiving:
it can hide a small number of noisy benchmark/competitor pairs that still affect
the interpretation.

The supporting metrics are:

| Metric | Use |
|---|---|
| p90 ratio CV | Primary quality metric. Lower means SafeRE/competitor conclusions are more stable. |
| Winner flips | Sanity check. Nonzero flips mean a config is suspect even if CV looks acceptable. |
| Wall-clock runtime | Cost metric for comparing configs. |
| Median ratio CV | Secondary quality metric, but too forgiving on its own. |
| Score CV | Diagnostic metric for raw score noise, not the main decision metric. |

## Candidate Configurations

| Config | Label | Forks | Warmup iterations | Warmup time | Measurement iterations | Measurement time |
|---|---|---:|---:|---:|---:|---:|
| A0 | absolute floor | 0 | 0 | - | 1 | 100ms |
| A1 | floor + tiny warmup | 0 | 1 | 100ms | 1 | 100ms |
| B | very cheap forked | 1 | 1 | 200ms | 3 | 200ms |
| C | cheap candidate | 1 | 2 | 500ms | 5 | 500ms |
| D | former quick | 1 | 3 | 1s | 5 | 1s |
| E | adopted standard | 2 | 2 | 500ms | 5 | 500ms |
| F | adopted long | 2 | 3 | 1s | 5 | 1s |
| P | former default/production | 3 | 3 | 5s | 5 | 5s |

## Broader Validation

The broader validation run used 10 representative tasks:

| Task | Benchmark coverage |
|---|---|
| `literalMatch` | Tiny literal fast path |
| `emailFind` | Ordinary find workload |
| `urlExtraction` | Application extraction workload |
| `pigLatinReplaceAll` | Replacement/allocation-heavy workload |
| `tagFind128` | Short Issue 481 find workload |
| `tagFind1MiB` | Large Issue 481 find workload |
| `schemeExtract128` | Short Issue 481 extraction workload |
| `schemeExtract1MiB` | Large Issue 481 extraction workload |
| `splitWords10KiB` | Mid-size split workload |
| `searchEasyFail100KiB` | Larger search-scaling workload |

The clean broader run compared only the practical contenders: C, D, E, and F.
It did not include P because the earlier calibration showed P dominates runtime
and is not a practical candidate for routine feedback.

Report: `benchmark-results/config-eval-broader-cdef/report.md`

| Config | Label | Runtime | Relative to E | p90 ratio CV | Winner flips |
|---|---|---:|---:|---:|---:|
| C | cheap candidate | 471.2s | 0.51x | 5.5% | 0/28 |
| D | former quick | 1009.0s | 1.08x | 5.9% | 1/27 |
| E | adopted standard | 931.5s | 1.00x | 4.5% | 0/28 |
| F | adopted long | 2011.7s | 2.16x | 2.2% | 0/27 |

Full stability results:

| Config | Median score CV | p90 score CV | Median ratio CV | p90 ratio CV | Winner flips |
|---|---:|---:|---:|---:|---:|
| C | 0.7% | 2.3% | 1.8% | 5.5% | 0/28 |
| D | 0.6% | 2.0% | 1.1% | 5.9% | 1/27 |
| E | 0.7% | 1.4% | 1.4% | 4.5% | 0/28 |
| F | 0.7% | 1.8% | 0.9% | 2.2% | 0/27 |

## Initial Calibration

| Config | Label | Runtime | Relative to E | p90 ratio CV | Winner flips |
|---|---|---:|---:|---:|---:|
| A0 | absolute floor | 7.7s | 0.03x | 17.6% | 0/9 |
| A1 | floor + tiny warmup | 11.3s | 0.04x | 31.4% | 1/9 |
| B | very cheap forked | 44.6s | 0.16x | 14.9% | 0/9 |
| C | cheap candidate | 142.5s | 0.51x | 6.6% | 0/8 |
| D | former quick | 303.9s | 1.08x | 4.9% | 0/9 |
| E | adopted standard | 281.3s | 1.00x | 4.4% | 0/8 |
| F | adopted long | 603.4s | 2.15x | 4.1% | 0/8 |
| P | former default/production | 4359.8s | 15.50x | 5.6% | 0/8 |

## Full Stability Results

| Config | Median score CV | p90 score CV | Median ratio CV | p90 ratio CV | Winner flips |
|---|---:|---:|---:|---:|---:|
| A0 | 3.9% | 13.1% | 7.1% | 17.6% | 0/9 |
| A1 | 5.8% | 26.3% | 17.6% | 31.4% | 1/9 |
| B | 4.0% | 11.0% | 4.0% | 14.9% | 0/9 |
| C | 3.0% | 4.3% | 3.4% | 6.6% | 0/8 |
| D | 2.0% | 5.9% | 3.1% | 4.9% | 0/9 |
| E | 0.6% | 2.8% | 1.4% | 4.4% | 0/8 |
| F | 1.0% | 2.6% | 1.5% | 4.1% | 0/8 |
| P | 0.5% | 1.4% | 0.6% | 5.6% | 0/8 |

## Interpretation

The broader validation supports E as the best routine-feedback candidate. It is
slightly faster than the former quick configuration D, has better p90 ratio CV
(4.5% vs 5.9%), and had no winner flips. D had one winner flip in the broader
run, which is a direct strike against using it as the default decision-making
configuration.

F is the most stable practical configuration tested: p90 ratio CV improved to
2.2%. The cost is substantial, though: F took about 2.16x as long as E. That
makes F useful as a longer confirmation mode, but not as the first-line
development feedback configuration.

C is cheaper than E and performed better than expected in the broader run, but
its p90 ratio CV was still worse than E (5.5% vs 4.5%). Since C is only about
2x faster than E and gives weaker stability, it is better treated as a lower
bound than as the primary configuration.

In the initial three-task calibration, the absolute floor configurations were
too noisy for comparative decisions.
A0 is extremely fast, but p90 ratio CV is 17.6%. A1 is worse despite adding a
tiny warmup, with p90 ratio CV of 31.4% and one winner flip.

The former default/production configuration P is much more expensive and did
not improve the primary decision metric in the initial calibration run. It took
about 15.5x as long as E, and its p90 ratio CV was worse than E because
`urlExtraction_safere` had a slow fork in one repeat. This does not prove that
longer runs are never useful, but it does show that the former default settings
are not automatically more decision-stable for the kind of comparative ratios we
care about.

## Adopted Procedure

Use E as the standard Java benchmark configuration:

```text
-f 2 -wi 2 -w 500ms -i 5 -r 500ms
```

This means:

| Setting | Value |
|---|---:|
| Forks | 2 |
| Warmup iterations | 2 |
| Warmup time | 500ms each |
| Measurement iterations | 5 |
| Measurement time | 500ms each |

Use F as the longer confirmation mode when a result is close, surprising, or
important enough to justify roughly doubling the runtime:

```text
-f 2 -wi 3 -w 1 -i 5 -r 1
```

The benchmark runner exposes these as:

```bash
./run-java-benchmarks.sh RegexBenchmark
./run-java-benchmarks.sh --long RegexBenchmark
```

## Reproducing

The initial calibration run used:

```bash
python3 safere-benchmarks/scripts/evaluate-java-benchmark-configs.py \
  --no-build \
  --configs A0,A1,B,C,D \
  --tasks literalMatch,emailFind,urlExtraction \
  --repeats 3 \
  --output-dir benchmark-results/config-eval-initial
```

The longer configurations were added with:

```bash
python3 safere-benchmarks/scripts/evaluate-java-benchmark-configs.py \
  --no-build \
  --configs E,F,P \
  --tasks literalMatch,emailFind,urlExtraction \
  --repeats 3 \
  --output-dir benchmark-results/config-eval-initial \
  --resume
```

The broader validation run used:

```bash
python3 safere-benchmarks/scripts/evaluate-java-benchmark-configs.py \
  --configs C,D,E,F \
  --tasks calibration \
  --repeats 3 \
  --output-dir benchmark-results/config-eval-broader-cdef
```

The combined report was regenerated from saved JSON outputs with:

```bash
python3 safere-benchmarks/scripts/evaluate-java-benchmark-configs.py \
  --no-build \
  --configs A0,A1,B,C,D,E,F,P \
  --tasks literalMatch,emailFind,urlExtraction \
  --repeats 3 \
  --output-dir benchmark-results/config-eval-initial \
  --resume
```
