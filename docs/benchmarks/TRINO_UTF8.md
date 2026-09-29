# Trino UTF-8 integration measurements

This historical report preserves the final local integration measurements from
the UTF-8 implementation work. It does not establish support in upstream Trino.
The original report did not retain raw benchmark output in this repository;
the aggregate figures below cannot be independently reconstructed here.

## Revisions, coverage, and results

The final local integration uses SafeRE revision
`8e2394facf9c50ac3d51a15b6485b496cb591d2c` and Trino revision
`4e070738fd759ef7cb909177bda24884b7d00dc1`. This local Trino integration exposes `JONI`, `RE2J`, and
`SAFERE` as independent engine selectors. It retains `io.trino:trino-re2j` and
its DFA properties for the RE2/J path while using separately named types and
functions for SafeRE.

The complete SafeRE and RE2/J regular-expression function classes passed, including like,
ordinary and lambda replacement, extraction, extract-all, split, count, and
position behavior. The adjacent feature-configuration, type-coercion, and
connector-expression translation tests also passed. A full `trino-main` run
was attempted; it could not complete because Docker-dependent OAuth tests fail
without a Docker environment, after which an unrelated node-state poller kept
the failed run alive. No regex-related failure occurred before termination.

The final deterministic JMH matrix runs SafeRE, RE2/J, and Joni from the same
Trino checkout using identical inputs. It covers matching and replacement over
five pattern families and two input sizes. All three engines ran in the same
invocation with two forks, three 1-second warmups, and five 1-second
measurements. The geometric mean of SafeRE time divided by Trino RE2/J time is
0.47 overall, 0.40 for capture-free matching, and 0.57 for
replacement. Every individual ratio is at most 1.05, satisfying the
precommitted 1.10 aggregate and 1.20 individual gates. Values below 1.0 mean
SafeRE is faster.

## Earlier implementation measurements

Completed locally at SafeRE revision
`3d0b19358d0fe7a0c2ccb3a1cabd3f3684c94d26`. CPU profiling identified the
capture-free Pike NFA path as the short-search bottleneck. Unanchored supported
programs now use the cached DFA for boolean search, while end-anchored programs
retain the bounded-allocation NFA path. Fully literal patterns use a precomputed
UTF-8 KMP search, preserving linear time even for overlapping prefixes.

The shared `Utf8MatchingBenchmark` covers the frozen capture-free,
decode-inclusive, predecoded String, repeated-find, capture-bound, empty-match,
window, construction, and hard-failure workloads; byte-native replacement is
covered by `ByteReplacementBenchmark`. Across the six capture-free cases,
trusted byte search has a time geomean of approximately 0.61 relative to
predecoded String and allocates effectively zero bytes per operation. The
legacy email workload improved from approximately 5.18 microseconds to 115
nanoseconds before the literal specialization; the final frozen literal cases
range from approximately 6 to 18 nanoseconds.

The SafeRE String regression set remained within its gates. At Trino revision
`7ec953a0619`, the existing function benchmark, run with the SafeRE-backed
legacy `benchmarkLikeRe2J` method, measured literal-like, phone-like, and
replacement paths against Joni.
The three time ratios have a geometric mean of approximately 0.87. The
replacement no-match case is slower but allocates approximately 152 bytes per
operation rather than 2,384 bytes. Thus adapter and output costs do not erase
the aggregate benefit. The final integration experiment is recorded above.
