# Collection notes

The selected SafeRE matrix is complete. This collection covers Java JDK, RE2/J,
RE2-FFM, SafeRE String, SafeRE UTF-8, native C++ RE2, PCRE2 JIT, and Rust
regex. It does not include Go, .NET, or the external OpenJDK-derived suite.

The measured SafeRE library revision is based on commit
`5bfcec92acda26fcde50fc8bc510f3a4afe855ea` (2026-09-25 17:51:20 -0700).
There were no production-library changes during collection. The benchmark
worktree contains uncommitted benchmark-plan changes required to express the
selected engines' syntax accurately. The report plan and generated manifest
were refreshed from that final worktree state; source and manifest hashes are
in `source-checksums.txt`.

Benchmark-plan adjustments:

- C++ RE2 now uses a pattern profile that inherits the shared RE2 alternates
  and permits C++-specific overrides. This preserves RE2's declared syntax
  exclusions while allowing the `(?i)` spelling for the Unicode case-folding
  workload.
- C++ RE2 uses literal U+3001 and bracket characters in its alternate for the
  citation-scrubbing pattern because its parser rejected the Java `\\u3001`
  escape.
- PCRE2 JIT uses `(?i)` for the Unicode case-folding workload; the runner
  compiles in UTF mode and PCRE2 rejected Java's combined `(?iu)` flags.

The native RE2 and PCRE2 collection logs retain failed partial attempts under
`*-failed-*.txt`; only `re2-results.jsonl` and `pcre2-jit-results.jsonl` contain
the complete final attempts. The C++ build reused the already-fetched pinned
sources with CMake's fully disconnected FetchContent mode after network access
was unavailable.

Complete normalized row counts are: SafeRE String 759, SafeRE UTF-8 581, JDK
695, RE2/J 589, RE2-FFM 539, C++ RE2 618, PCRE2 JIT 441, and Rust regex 637.
The normalizer completed with 4,859 unique `(engine, benchmark)` identities.
