# SafeRE Benchmark Report

This report compares SafeRE with `java.util.regex` (JDK), RE2/J 1.8, RE2-FFM
(C++ RE2 through Java's Foreign Function & Memory API), native C++ RE2, and Go
[`regexp`](https://pkg.go.dev/regexp). Lower times are better.

## Executive summary

SafeRE is close to the JDK on the defined core and application summaries while
retaining a linear-time guarantee. Against other JVM-accessible linear-time
engines, SafeRE is 11.7× faster than RE2/J and 2.2× faster than RE2-FFM on the
core geomean; on application workloads it is 7.8× and 1.7× faster,
respectively. For cross-language context, SafeRE is 2.32× slower than native C++
RE2 and 3.99× faster than Go `regexp` on the core geomean; on application
workloads it is 1.32× slower and 2.01× faster, respectively.

Across all 150 real-world measurements, SafeRE is 1.89× faster than JDK,
10.6× faster than RE2/J, 2.54× faster than RE2-FFM, 1.44× slower than native
C++ RE2, and 6.17× faster than Go `regexp` by geometric mean.

The most important change since the previous report is alternation search:
SafeRE now takes 226 ns/op, versus 529 ns/op for JDK, 656 ns/op for RE2-FFM,
and 4,437 ns/op for RE2/J. A longer confirmation run measured 223 ± 6 ns/op.

The tradeoffs remain visible. JDK compilation is 64–87× faster in these four
cases, and it leads on several short or backtracking-friendly patterns. SafeRE
uses more retained memory per compiled pattern. In return, adversarial cases
remain bounded: the defined pathological/scaling geomean is about 13,500×
faster than JDK, 2,820× faster than RE2/J, and 23× faster than RE2-FFM.

## Environment and reproducibility

- Benchmarked commit: `6fe67606d7c7724b9b1f1736c3bee04c674eaec5`
- Commit date/time: 2026-07-21T04:17:54Z
- CPU: Intel Core i7-11700K, 8 cores / 16 threads, 3.6 GHz base
- Memory available to WSL2: 16 GiB; Windows 11 host
- OS: Ubuntu 24.04 on WSL2, Linux 6.6.87.2-microsoft-standard-WSL2
- Java: OpenJDK 25.0.2+10-69, targeting Java 21
- JMH: 1.37
- C++ compiler: g++ 13.3.0, Release build (`-O3 -DNDEBUG`)
- C++ RE2: 2025-11-05
- Go: 1.26.1 linux/amd64

The full collection command was:

```bash
./collect-benchmark-results.sh --cross-language
```

Java used the standard project configuration: 2 forks, 2 warmup iterations of
500 ms, and 5 measurement iterations of 500 ms. Pathological classes used
`-f 0` so infeasible JDK cases could not strand forked JVMs. The allocation
pass used its dedicated publication configuration and GC profiler.

C++ and Go used 2 warmup and 10 measurement iterations of 2 seconds in one
process. All harnesses read the same `benchmark-data.json`. Java results report
99.9% confidence intervals from JMH; native harnesses use Student's
t-distribution. A targeted Java confirmation used `--long` for SafeRE
alternation and log parsing; it did not replace standard-run values in summary
statistics.

Native C++ and Go operate on UTF-8, while Java engines operate on Java strings.
RE2-FFM also pays UTF-16-to-UTF-8 conversion and native-call costs. Native
results therefore provide ecosystem context, not a controlled language-runtime
comparison.

## Summary statistics

Ratios are SafeRE time / competitor time; values below 1 mean SafeRE is faster.
Geometric means are used because benchmark ratios are multiplicative and the
result is invariant under inversion.

| Category | vs JDK | vs RE2/J | vs RE2-FFM | vs C++ RE2 | vs Go `regexp` |
|---|---:|---:|---:|---:|---:|
| Core workloads (8) | 1.087 (1.09× slower) | 0.0856 (11.7× faster) | 0.456 (2.19× faster) | 2.320 (2.32× slower) | 0.250 (3.99× faster) |
| Application workloads (8) | 1.002 (approximately even) | 0.128 (7.81× faster) | 0.604 (1.66× faster) | 1.317 (1.32× slower) | 0.497 (2.01× faster) |
| Real-world matrix (150) | 0.528 (1.89× faster) | 0.0947 (10.6× faster) | 0.393 (2.54× faster) | 1.443 (1.44× slower) | 0.162 (6.17× faster) |
| Pathological/scaling (3) | 0.0000739 (13,500× faster) | 0.000354 (2,820× faster) | 0.0432 (23.2× faster) | 1.112 (1.11× slower) | 0.000656 (1,520× faster) |

Core contains literal match, email find, find-in-text, alternation find,
character-class match, capture groups, Pig Latin replacement, and full HTTP
parsing. Application contains all eight `ApplicationBenchmark` cases.
Real-world contains all 25 patterns, both match outcomes, and the 1K, 10K, and
100K inputs, with equal weight for each of the 150 measurements.
Pathological/scaling contains `a?{20}a{20}`, hard failed search at 1 MiB, and
nested quantifiers at 100 KiB.

The C++ RE2 and Go columns are cross-runtime context rather than controlled
Java comparisons. In particular, native C++ and Go consume UTF-8 while SafeRE
consumes Java strings, and the pathological/scaling summary has only three
cases and is dominated by the end-anchored hard-failure result. The real-world
JDK geomean is materially influenced by two 100K adversarial no-match cases
that take about 42 seconds each; excluding those two measurements changes the
ratio from 0.528 (1.89× faster) to 0.625 (1.60× faster).

## Core matching and application workloads

| Benchmark (ns/op) | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go |
|---|---:|---:|---:|---:|---:|---:|
| Literal match | 14 | 13 | 127 | 59 | 40 | 76 |
| Character class | 23 | 24 | 1,229 | 120 | 81 | 360 |
| Alternation find | 226 | 529 | 4,437 | 656 | 19 | 1,699 |
| Capture groups | 87 | 90 | 572 | 327 | 73 | 234 |
| Email find | 206 | 392 | 1,923 | 244 | 83 | 548 |
| Find in prose | 2,587 | 2,965 | 19,921 | 4,174 | 18 | 12,621 |

| Application case (ns/op) | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go |
|---|---:|---:|---:|---:|---:|---:|
| UUID validation | 456 | 1,302 | 2,537 | 683 | 352 | 934 |
| API route | 530 | 645 | 6,562 | 1,114 | 315 | 1,003 |
| Case-insensitive keywords | 399 | 1,322 | 7,312 | 1,265 | 530 | 4,440 |
| URL extraction | 638 | 1,267 | 7,508 | 1,434 | 624 | 1,591 |
| Secret redaction | 1,192 | 784 | 7,173 | 1,001 | 638 | 2,513 |
| CSV field scan | 2,347 | 748 | 10,518 | 6,441 | 1,304 | 3,502 |
| Log parse | 2,963 | 1,096 | 16,090 | 2,956 | 2,064 | 2,347 |
| Stack-trace extraction | 4,398 | 2,438 | 28,123 | 4,805 | 3,952 | 4,388 |

The standard log-parse result had a wide interval (2,963 ± 719 ns/op). The
long confirmation measured 2,479 ± 49 ns/op, so the standard point estimate is
conservative for SafeRE. Summary geomeans nevertheless use the standard result.

## Real-world benchmark matrix

These 25 data-driven patterns use both matching and non-matching inputs at 1K,
10K, and 100K sizes. All values are mean ns/op; lower is better. SafeRE is
competitive on linear scans and benefits strongly from required-content fast
rejection. JDK can be dramatically faster when a match or rejection is found
near the start, but its two adversarial 100K no-match cases took about 42
seconds per operation. RE2/J is usually slower on scans while remaining
linear-time bounded.

| Pattern | Result | Input | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| `mapFieldPath` | match | 1,000 | 5,034 | 731 | 41,133 | 10,349 | 79 | 6,687 |
| `mapFieldPath` | match | 10,000 | 48,626 | 5,048 | 408,373 | 101,004 | 79 | 61,617 |
| `mapFieldPath` | match | 100,000 | 613,038 | 124,361 | 4,224,878 | 2,769,222 | 83 | 2,526,907 |
| `markupImageLink` | match | 1,000 | 4,312 | 2,335 | 52,396 | 5,578 | 5,374 | 10,343 |
| `markupImageLink` | match | 10,000 | 42,915 | 19,130 | 490,184 | 54,276 | 51,293 | 361,249 |
| `markupImageLink` | match | 100,000 | 424,536 | 179,838 | 4,924,270 | 583,542 | 492,847 | 3,500,387 |
| `versionList` | match | 1,000 | 8,178 | 10,160 | 77,978 | 7,174 | 6,337 | 17,061 |
| `versionList` | match | 10,000 | 81,840 | 100,172 | 676,581 | 71,647 | 62,852 | 210,738 |
| `versionList` | match | 100,000 | 780,132 | 966,012 | 7,023,498 | 704,576 | 634,316 | 5,571,893 |
| `malformedEntity` | match | 1,000 | 4,769 | 5,637 | 62,240 | 7,665 | 6,304 | 22,003 |
| `malformedEntity` | match | 10,000 | 46,314 | 55,991 | 615,894 | 71,064 | 59,416 | 250,269 |
| `malformedEntity` | match | 100,000 | 479,741 | 577,353 | 6,874,353 | 696,888 | 616,167 | 5,869,799 |
| `markupEntity` | match | 1,000 | 89 | 76 | 848 | 412 | 79 | 254 |
| `markupEntity` | match | 10,000 | 90 | 75 | 858 | 2,318 | 79 | 315 |
| `markupEntity` | match | 100,000 | 87 | 70 | 841 | 24,850 | 75 | 724 |
| `customProtocolLink` | match | 1,000 | 602 | 169 | 2,776 | 793 | 108 | 447 |
| `customProtocolLink` | match | 10,000 | 4,021 | 165 | 2,784 | 2,806 | 103 | 2,161 |
| `customProtocolLink` | match | 100,000 | 3,991 | 169 | 2,807 | 25,000 | 103 | 2,185 |
| `wildcardSearch` | match | 1,000 | 5,247 | 268 | 68,616 | 7,563 | 66 | 7,138 |
| `wildcardSearch` | match | 10,000 | 51,641 | 100 | 669,954 | 70,929 | 66 | 65,215 |
| `wildcardSearch` | match | 100,000 | 662,015 | 194 | 7,029,718 | 3,461,379 | 69 | 5,409,619 |
| `metadataBlock` | match | 1,000 | 3,399 | 3,555 | 43,785 | 4,489 | 4,141 | 10,393 |
| `metadataBlock` | match | 10,000 | 34,535 | 35,404 | 434,786 | 44,059 | 42,665 | 116,073 |
| `metadataBlock` | match | 100,000 | 352,601 | 363,228 | 4,321,594 | 447,979 | 407,811 | 3,266,842 |
| `blockedTags1` | match | 1,000 | 3,682 | 2,572 | 36,623 | 5,106 | 4,678 | 8,981 |
| `blockedTags1` | match | 10,000 | 37,189 | 22,189 | 364,523 | 48,608 | 45,670 | 271,123 |
| `blockedTags1` | match | 100,000 | 375,745 | 238,390 | 3,547,785 | 491,314 | 458,130 | 2,719,329 |
| `blockedTags2` | match | 1,000 | 3,711 | 1,923 | 34,522 | 4,901 | 4,355 | 8,371 |
| `blockedTags2` | match | 10,000 | 36,245 | 18,262 | 340,687 | 46,018 | 41,861 | 255,315 |
| `blockedTags2` | match | 100,000 | 356,102 | 155,873 | 3,358,769 | 464,462 | 417,258 | 2,528,881 |
| `overlappingUrl` | match | 1,000 | 5,931 | 8,319 | 49,616 | 4,404 | 3,804 | 12,907 |
| `overlappingUrl` | match | 10,000 | 65,785 | 85,235 | 483,642 | 41,323 | 37,827 | 418,979 |
| `overlappingUrl` | match | 100,000 | 689,259 | 797,366 | 4,907,390 | 421,477 | 375,470 | 4,181,978 |
| `caseInsensitiveKeyword` | match | 1,000 | 4,565 | 1,547 | 30,263 | 7,162 | 8,090 | 9,990 |
| `caseInsensitiveKeyword` | match | 10,000 | 46,379 | 15,249 | 305,812 | 68,853 | 75,828 | 131,538 |
| `caseInsensitiveKeyword` | match | 100,000 | 457,228 | 144,753 | 3,126,495 | 711,785 | 759,417 | 3,687,651 |
| `boundedNameMatch` | match | 1,000 | 8,040 | 3,547 | 35,029 | 7,441 | 7,488 | 11,058 |
| `boundedNameMatch` | match | 10,000 | 79,131 | 32,528 | 353,148 | 68,119 | 71,363 | 297,534 |
| `boundedNameMatch` | match | 100,000 | 815,851 | 317,797 | 3,430,851 | 686,680 | 698,426 | 2,936,683 |
| `templateTagMatch` | match | 1,000 | 2,498 | 4,446 | 28,364 | 3,683 | 3,135 | 6,447 |
| `templateTagMatch` | match | 10,000 | 25,896 | 46,532 | 284,046 | 35,090 | 30,153 | 206,107 |
| `templateTagMatch` | match | 100,000 | 244,104 | 483,783 | 2,815,159 | 365,544 | 355,556 | 2,056,744 |
| `sparseUrl` | match | 1,000 | 1,679 | 8,119 | 23,157 | 2,076 | 1,572 | 18,614 |
| `sparseUrl` | match | 10,000 | 19,298 | 82,581 | 251,174 | 21,306 | 16,511 | 228,120 |
| `sparseUrl` | match | 100,000 | 201,896 | 744,357 | 2,530,256 | 228,395 | 210,795 | 2,281,433 |
| `unprefixedWordBoundary` | match | 1,000 | 108 | 79 | 312 | 364 | 54 | 228 |
| `unprefixedWordBoundary` | match | 10,000 | 115 | 74 | 302 | 2,230 | 54 | 273 |
| `unprefixedWordBoundary` | match | 100,000 | 112 | 79 | 314 | 24,222 | 54 | 351 |
| `fruitSearchQuery` | match | 1,000 | 16,052 | 623 | 244,839 | 8,383 | 7,788 | 8,688 |
| `fruitSearchQuery` | match | 10,000 | 1,649,681 | 1,403 | 2,395,619 | 483,834 | 471,525 | 1,026,129 |
| `fruitSearchQuery` | match | 100,000 | 17,087,065 | 492 | 24,294,373 | 4,952,406 | 4,921,290 | 10,247,457 |
| `fruitMarkupTag` | match | 1,000 | 5,919 | 722 | 74,716 | 3,323 | 2,796 | 12,431 |
| `fruitMarkupTag` | match | 10,000 | 57,420 | 6,944 | 760,688 | 30,807 | 28,873 | 121,772 |
| `fruitMarkupTag` | match | 100,000 | 602,412 | 71,372 | 7,461,543 | 447,681 | 403,458 | 1,213,024 |
| `jsonBlock` | match | 1,000 | 2,214 | 6,057 | 19,790 | 2,347 | 1,962 | 7,000 |
| `jsonBlock` | match | 10,000 | 20,590 | 64,098 | 195,190 | 20,231 | 16,064 | 65,195 |
| `jsonBlock` | match | 100,000 | 204,763 | 508,204 | 1,999,569 | 210,772 | 186,891 | 1,299,071 |
| `charReplace` | match | 1,000 | 8,497 | 8,735 | 67,634 | 39,585 | 34,327 | 31,063 |
| `charReplace` | match | 10,000 | 94,342 | 93,216 | 662,319 | 401,208 | 341,106 | 395,811 |
| `charReplace` | match | 100,000 | 957,893 | 922,000 | 6,498,876 | 4,040,785 | 3,498,893 | 6,126,490 |
| `greedyOnePass` | match | 1,000 | 2,392 | 509 | 36,504 | 4,253 | 1,082 | 8,182 |
| `greedyOnePass` | match | 10,000 | 23,295 | 4,771 | 371,420 | 41,299 | 10,987 | 80,569 |
| `greedyOnePass` | match | 100,000 | 239,407 | 58,457 | 3,614,066 | 530,221 | 110,239 | 804,581 |
| `layoutBlock` | match | 1,000 | 2,984 | 1,751 | 13,912 | 2,393 | 1,893 | 4,206 |
| `layoutBlock` | match | 10,000 | 31,369 | 18,287 | 149,590 | 25,137 | 21,492 | 124,533 |
| `layoutBlock` | match | 100,000 | 330,722 | 176,567 | 1,452,189 | 268,285 | 207,098 | 1,248,434 |
| `turnTitleWhitespaceCjk` | match | 1,000 | 7,003 | 34,223 | 44,697 | 22,719 | 4,634 | 10,508 |
| `turnTitleWhitespaceCjk` | match | 10,000 | 70,289 | 342,512 | 460,664 | 237,151 | 45,183 | 109,254 |
| `turnTitleWhitespaceCjk` | match | 100,000 | 656,147 | 3,501,615 | 4,551,701 | 2,386,998 | 464,307 | 1,349,416 |
| `cjkSearch` | match | 1,000 | 171 | 16 | 460 | 4,176 | 52 | 188 |
| `cjkSearch` | match | 10,000 | 173 | 16 | 460 | 41,302 | 52 | 214 |
| `cjkSearch` | match | 100,000 | 172 | 16 | 585 | 416,579 | 55 | 429 |
| `emojiSearch` | match | 1,000 | 60 | 50 | 576 | 2,213 | 80 | 365 |
| `emojiSearch` | match | 10,000 | 59 | 51 | 559 | 21,337 | 84 | 384 |
| `emojiSearch` | match | 100,000 | 60 | 50 | 579 | 201,817 | 80 | 529 |
| `mapFieldPath` | no match | 1,000 | 1,311 | 65,669 | 44,833 | 1,581 | 1,081 | 30,548 |
| `mapFieldPath` | no match | 10,000 | 12,488 | 692,540 | 430,369 | 15,047 | 10,406 | 305,614 |
| `mapFieldPath` | no match | 100,000 | 126,210 | 7,278,956 | 4,316,476 | 156,064 | 103,301 | 3,362,141 |
| `markupImageLink` | no match | 1,000 | 1,359 | 6,762 | 35,708 | 1,874 | 1,366 | 11,141 |
| `markupImageLink` | no match | 10,000 | 12,666 | 70,032 | 361,602 | 18,082 | 12,922 | 277,980 |
| `markupImageLink` | no match | 100,000 | 123,490 | 687,692 | 3,569,493 | 195,370 | 136,389 | 2,771,017 |
| `versionList` | no match | 1,000 | 2,040 | 28,715 | 58,913 | 1,831 | 1,377 | 34,413 |
| `versionList` | no match | 10,000 | 28,294 | 279,488 | 612,047 | 17,638 | 13,194 | 339,636 |
| `versionList` | no match | 100,000 | 179,714 | 2,808,327 | 6,069,417 | 182,146 | 126,750 | 5,122,514 |
| `malformedEntity` | no match | 1,000 | 1,293 | 3,547 | 41,661 | 1,773 | 1,232 | 13,735 |
| `malformedEntity` | no match | 10,000 | 12,726 | 35,564 | 404,142 | 16,857 | 11,689 | 137,340 |
| `malformedEntity` | no match | 100,000 | 126,198 | 342,498 | 4,128,904 | 175,291 | 121,088 | 3,331,623 |
| `markupEntity` | no match | 1,000 | 102 | 681 | 181 | 858 | 480 | 517 |
| `markupEntity` | no match | 10,000 | 848 | 6,467 | 952 | 7,483 | 4,302 | 4,230 |
| `markupEntity` | no match | 100,000 | 8,404 | 67,778 | 8,429 | 71,992 | 49,566 | 44,894 |
| `customProtocolLink` | no match | 1,000 | 1,306 | 74,329 | 48,199 | 1,590 | 1,296 | 14,477 |
| `customProtocolLink` | no match | 10,000 | 12,626 | 6,176,486 | 483,097 | 15,435 | 13,081 | 362,662 |
| `customProtocolLink` | no match | 100,000 | 124,513 | 591,249,437 | 4,712,296 | 156,902 | 125,013 | 3,603,098 |
| `wildcardSearch` | no match | 1,000 | 476 | 4,353,393 | 36,122 | 1,685 | 1,135 | 23,934 |
| `wildcardSearch` | no match | 10,000 | 4,618 | 313,589,410 | 366,024 | 16,133 | 10,450 | 238,721 |
| `wildcardSearch` | no match | 100,000 | 57,497 | 42,290,631,394 | 3,830,355 | 161,981 | 104,360 | 3,147,373 |
| `metadataBlock` | no match | 1,000 | 1,287 | 20,742 | 44,869 | 1,869 | 1,325 | 13,412 |
| `metadataBlock` | no match | 10,000 | 12,717 | 2,020,634 | 440,617 | 17,633 | 12,651 | 132,877 |
| `metadataBlock` | no match | 100,000 | 125,867 | 199,882,604 | 4,467,936 | 192,619 | 133,375 | 3,412,183 |
| `blockedTags1` | no match | 1,000 | 1,305 | 1,566 | 18,374 | 1,593 | 1,481 | 4,867 |
| `blockedTags1` | no match | 10,000 | 12,346 | 13,880 | 172,446 | 20,237 | 15,056 | 150,762 |
| `blockedTags1` | no match | 100,000 | 126,346 | 138,003 | 1,752,655 | 215,257 | 158,317 | 1,497,926 |
| `blockedTags2` | no match | 1,000 | 1,305 | 1,635 | 14,485 | 1,250 | 1,144 | 4,216 |
| `blockedTags2` | no match | 10,000 | 12,676 | 12,887 | 139,346 | 15,425 | 9,868 | 118,310 |
| `blockedTags2` | no match | 100,000 | 124,308 | 133,107 | 1,369,200 | 164,197 | 104,427 | 1,189,957 |
| `overlappingUrl` | no match | 1,000 | 1,307 | 7,084 | 23,103 | 1,796 | 1,320 | 20,893 |
| `overlappingUrl` | no match | 10,000 | 12,629 | 70,068 | 243,436 | 17,633 | 12,623 | 225,163 |
| `overlappingUrl` | no match | 100,000 | 124,485 | 715,378 | 2,381,434 | 185,889 | 126,908 | 2,223,375 |
| `caseInsensitiveKeyword` | no match | 1,000 | 280 | 387 | 19,141 | 1,847 | 1,319 | 19,607 |
| `caseInsensitiveKeyword` | no match | 10,000 | 2,679 | 3,793 | 184,802 | 17,573 | 13,240 | 194,098 |
| `caseInsensitiveKeyword` | no match | 100,000 | 26,450 | 46,397 | 1,830,467 | 186,556 | 133,036 | 2,473,282 |
| `boundedNameMatch` | no match | 1,000 | 1,294 | 7,001 | 23,008 | 1,862 | 1,380 | 19,136 |
| `boundedNameMatch` | no match | 10,000 | 12,542 | 67,128 | 222,889 | 17,687 | 13,204 | 200,599 |
| `boundedNameMatch` | no match | 100,000 | 126,144 | 558,222 | 2,296,764 | 184,284 | 126,843 | 1,991,351 |
| `templateTagMatch` | no match | 1,000 | 105 | 496 | 222 | 570 | 79 | 607 |
| `templateTagMatch` | no match | 10,000 | 815 | 4,786 | 2,240 | 4,633 | 172 | 3,469 |
| `templateTagMatch` | no match | 100,000 | 7,921 | 43,642 | 23,672 | 51,199 | 2,418 | 38,288 |
| `sparseUrl` | no match | 1,000 | 1,308 | 7,043 | 23,116 | 1,856 | 1,321 | 20,602 |
| `sparseUrl` | no match | 10,000 | 12,604 | 69,952 | 251,839 | 17,642 | 12,617 | 226,328 |
| `sparseUrl` | no match | 100,000 | 123,777 | 717,168 | 2,520,099 | 187,407 | 132,905 | 2,220,455 |
| `unprefixedWordBoundary` | no match | 1,000 | 1,283 | 6,025 | 16,139 | 1,675 | 1,086 | 14,294 |
| `unprefixedWordBoundary` | no match | 10,000 | 12,712 | 61,020 | 166,948 | 15,703 | 10,933 | 142,644 |
| `unprefixedWordBoundary` | no match | 100,000 | 125,874 | 604,244 | 1,650,604 | 154,363 | 104,153 | 1,488,490 |
| `fruitSearchQuery` | no match | 1,000 | 1,295 | 3,273,411 | 62,692 | 1,907 | 1,383 | 46,349 |
| `fruitSearchQuery` | no match | 10,000 | 12,556 | 390,906,721 | 582,032 | 17,999 | 12,583 | 522,374 |
| `fruitSearchQuery` | no match | 100,000 | 126,088 | 42,059,953,813 | 6,070,061 | 186,985 | 126,906 | 5,243,080 |
| `fruitMarkupTag` | no match | 1,000 | 682 | 1,145 | 19,015 | 2,003 | 1,405 | 6,702 |
| `fruitMarkupTag` | no match | 10,000 | 6,297 | 11,532 | 199,277 | 18,886 | 13,680 | 64,976 |
| `fruitMarkupTag` | no match | 100,000 | 63,075 | 107,469 | 2,015,625 | 115,071 | 67,571 | 646,086 |
| `jsonBlock` | no match | 1,000 | 96 | 410 | 209 | 598 | 75 | 597 |
| `jsonBlock` | no match | 10,000 | 809 | 3,831 | 2,233 | 4,586 | 185 | 3,530 |
| `jsonBlock` | no match | 100,000 | 7,784 | 39,908 | 24,324 | 49,847 | 2,535 | 39,424 |
| `charReplace` | no match | 1,000 | 101 | 160 | 206 | 605 | 80 | 602 |
| `charReplace` | no match | 10,000 | 819 | 1,330 | 2,239 | 4,613 | 182 | 3,510 |
| `charReplace` | no match | 100,000 | 7,889 | 11,771 | 24,371 | 54,949 | 2,413 | 38,604 |
| `greedyOnePass` | no match | 1,000 | 24 | 16 | 45 | 340 | 43 | 56 |
| `greedyOnePass` | no match | 10,000 | 24 | 17 | 45 | 3,035 | 43 | 57 |
| `greedyOnePass` | no match | 100,000 | 24 | 16 | 46 | 23,979 | 43 | 56 |
| `layoutBlock` | no match | 1,000 | 1,302 | 1,224 | 7,744 | 926 | 435 | 2,690 |
| `layoutBlock` | no match | 10,000 | 12,393 | 11,073 | 73,441 | 9,898 | 3,640 | 64,893 |
| `layoutBlock` | no match | 100,000 | 126,503 | 120,689 | 742,735 | 96,984 | 38,435 | 655,004 |
| `turnTitleWhitespaceCjk` | no match | 1,000 | 5,726 | 32,946 | 33,774 | 13,915 | 2,140 | 8,203 |
| `turnTitleWhitespaceCjk` | no match | 10,000 | 58,753 | 307,105 | 335,346 | 139,750 | 20,379 | 82,031 |
| `turnTitleWhitespaceCjk` | no match | 100,000 | 527,283 | 3,168,363 | 3,275,174 | 1,381,385 | 193,987 | 970,056 |
| `cjkSearch` | no match | 1,000 | 487 | 147 | 18,753 | 1,595 | 1,136 | 9,997 |
| `cjkSearch` | no match | 10,000 | 4,657 | 1,230 | 183,480 | 15,634 | 10,471 | 98,992 |
| `cjkSearch` | no match | 100,000 | 45,872 | 12,127 | 1,813,944 | 153,499 | 104,257 | 1,518,958 |
| `emojiSearch` | no match | 1,000 | 471 | 260 | 18,729 | 1,592 | 1,081 | 10,176 |
| `emojiSearch` | no match | 10,000 | 4,777 | 2,393 | 179,055 | 16,106 | 10,477 | 100,688 |
| `emojiSearch` | no match | 100,000 | 45,564 | 24,131 | 1,812,063 | 153,853 | 108,808 | 1,531,521 |

## Compilation, replacement, and captures

| Compile (µs/op) | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go |
|---|---:|---:|---:|---:|---:|---:|
| Simple | 6.7 | 0.09 | 0.27 | 2.6 | 1.6 | 0.91 |
| Medium | 26 | 0.30 | 2.2 | 9.9 | 6.5 | 6.2 |
| Complex | 15 | 0.22 | 1.2 | 6.9 | 4.5 | 2.5 |
| Alternation | 27 | 0.42 | 3.1 | 11 | 7.9 | 6.0 |

SafeRE now performs more eager analysis at compile time. That improves match
execution but makes compilation slower than every comparator in this table.

| Replacement (ns/op) | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go |
|---|---:|---:|---:|---:|---:|---:|
| Digit replaceAll | 152 | 300 | 3,189 | 1,028 | 650 | 1,581 |
| Literal replaceFirst, no match | 86 | 270 | 173 | 479 | 267 | 887 |
| Literal replaceFirst | 55 | 41 | 149 | 217 | 98 | 583 |
| Pig Latin replaceAll | 2,338 | 955 | 8,307 | 2,455 | 1,816 | 2,828 |
| Empty-match replaceAll | 417 | 78 | 407 | 672 | 357 | 338 |

Capture extraction grows from 54 ns/op with no captures to 214 ns/op with ten.
At ten captures SafeRE is 1.15× faster than JDK, 6.9× faster than RE2/J, and
3.6× faster than RE2-FFM.

## Scaling and pathological behavior

| Failed search at 1 MiB (µs/op) | SafeRE | JDK | RE2/J | RE2-FFM | C++ RE2 | Go |
|---|---:|---:|---:|---:|---:|---:|
| Easy literal suffix | 84 | 147 | 84 | 356 | 0.05 | 24 |
| Medium class prefix | 396 | 271 | 25,058 | 351 | 0.04 | 18,218 |
| Hard nullable prefix | 0.04 | 44,845 | 38,996 | 350 | 0.04 | 25,453 |

The hard case, `[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$`, is rejected in roughly
constant time by SafeRE and native C++ RE2. JDK exhibits quadratic behavior;
RE2/J and Go remain linear but scan the input. Easy and medium cases show that
linear-time safety does not imply one universal constant factor.

For `a?{n}a{n}` on `a{n}`, SafeRE grows from 0.05 µs at n=10 to 0.31 µs at
n=100. RE2/J grows from 1.9 to 153 µs, Go from 0.87 to 64 µs, and native C++
RE2 from 0.05 to 0.17 µs. JDK reaches 17,670 µs at n=20 and larger configured
cases are intentionally not run.

For `(?:a?){20}a{20}` at 100 KiB, SafeRE takes 131 µs, versus 1,456 µs for
JDK, 38,732 µs for RE2/J, 159 µs for RE2-FFM, 107 µs for C++ RE2, and
21,547 µs for Go.

## Memory

Retained compiled-pattern size is larger for SafeRE because it stores compiled
program and analysis structures:

| Pattern | SafeRE | JDK | RE2/J |
|---|---:|---:|---:|
| Simple | 8,388 B | 756 B | 652 B |
| Medium | 16,916 B | 940 B | 1,692 B |
| Complex | 7,388 B | 1,204 B | 844 B |
| Alternation | 21,508 B | 964 B | 3,500 B |

SafeRE DFA cache growth ranged from 152 B for the simple pattern to 48,024 B
for the complex pattern. Allocation behavior depends on the execution path.
For easy search, SafeRE stayed near 152 B/op from 1 KiB through 1 MiB; JDK was
near 56 B/op and RE2/J near 48 B/op. Other result-materializing and state-heavy
paths allocate proportionally with input, so these figures are not a universal
per-match allocation claim.

## SafeRE-specific functionality

`PatternSet` matches many patterns simultaneously and has no direct comparator
in the other APIs. At 4, 16, and 64 patterns, anchored successful matches took
4.96, 8.93, and 25.7 µs; unanchored successful matches took 5.71, 36.6, and
125 µs. Anchoring increasingly reduces work as the set grows.

## Interpretation

The 150-measurement real-world matrix is the broadest evidence in this report.
SafeRE's geomean is 1.89× faster than JDK across that matrix, or 1.60× faster
when the two roughly 42-second JDK no-match results are excluded. The separate
eight-case application geomean is approximately even with JDK. Together these
results show competitive ordinary-workload performance without relying only on
the adversarial cases that motivate a linear-time engine.

Within the JVM comparisons, SafeRE is consistently faster than the other
linear-time options: 10.6× faster than RE2/J and 2.54× faster than RE2-FFM on
the real-world matrix, with similar direction on the core workloads. RE2/J's
Pike VM avoids backtracking blowups but performs more active-state work on many
scans. RE2-FFM benefits from C++ RE2's execution engine while paying conversion
and native-call costs at the Java boundary.

The native comparisons show the remaining runtime tradeoff. C++ RE2 is 2.32×
faster on core workloads, 1.32× faster on application workloads, and 1.44×
faster on the real-world matrix. SafeRE can still lead individual cases when
Java fast paths or required-content rejection avoid work. SafeRE is 6.17×
faster than Go `regexp` on the real-world matrix; Go remains linear-time but,
like RE2/J, does not use a general cached DFA engine.

The pathological/scaling geomean should not be treated as a general ranking.
It contains only three stress cases, and its largest differences come from an
end-anchored failure that SafeRE and native C++ RE2 reject without scanning the
full input. The other two comparisons place SafeRE much closer to native RE2
and RE2-FFM while still exposing the JDK backtracking cost and the per-character
state-management cost of NFA-only engines.

The costs are also concrete: SafeRE compilation is slower, compiled patterns
retain more memory, and some short anchored, replacement, and application cases
remain faster in JDK. The benchmark suite should therefore be read as a map of
engine tradeoffs, not as a single universal ranking.
