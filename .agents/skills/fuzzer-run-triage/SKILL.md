---
name: fuzzer-run-triage
description: "Triage SafeRE Jazzer fuzzer runs performed manually by the user: locate Surefire/Jazzer logs, distinguish latest run artifacts from persistent corpus and older crashes, extract bugs/findings, identify crash inputs, summarize repro details, and prepare GitHub issue material without rerunning fuzzing."
---

# Fuzzer Run Triage

## Goal

When the user has run a SafeRE Jazzer fuzzer by hand, find what that run discovered and present
the actionable bug findings. Do not assume `target/fuzz-reproducers` exists: Jazzer JUnit usually
writes libFuzzer `crash-*` inputs to the fuzzer's input resource directory, while Maven/Surefire
writes human-readable logs under `target/surefire-reports`.

## First Checks

1. Identify the fuzzer class from the user's command or question, for example
   `CharacterClassExpressionFuzzer`.
2. Inspect the latest matching Surefire files:
   - `safere-fuzz/target/surefire-reports/TEST-org.safere.fuzz.<Fuzzer>.xml`
   - `safere-fuzz/target/surefire-reports/org.safere.fuzz.<Fuzzer>.txt`
3. Check file mtimes before trusting artifacts:

   ```bash
   stat safere-fuzz/target/surefire-reports/TEST-org.safere.fuzz.<Fuzzer>.xml
   stat safere-fuzz/target/surefire-reports/org.safere.fuzz.<Fuzzer>.txt
   ```

4. Extract findings from the XML:

   ```bash
   rg -n '== Java Exception|AssertionError|CrosscheckException|PatternSyntaxException|divergence|DEDUP_TOKEN|libFuzzer crashing input|Test unit written|artifact_prefix|reproducer_path|Java reproducer written' \
     safere-fuzz/target/surefire-reports/TEST-org.safere.fuzz.<Fuzzer>.xml
   ```

## Where Artifacts Live

- **Surefire logs**: `safere-fuzz/target/surefire-reports/`
  - Mixed by fuzzer class in one directory.
  - The same fuzzer's `.xml` and `.txt` files are overwritten by newer runs.
  - Other fuzzer reports may be older and unrelated.

- **Generated corpus**: `safere-fuzz/.cifuzz-corpus/org.safere.fuzz.<Fuzzer>/<method>/`
  - Persistent coverage corpus, intentionally accumulated across runs.
  - Not a clean list of current-run bugs.

- **Jazzer JUnit crash inputs**:
  `safere-fuzz/src/test/resources/org/safere/fuzz/<Fuzzer>Inputs/<method>/crash-*`
  - Usually the important reproducer files for JUnit fuzz findings.
  - These are persistent checked-in seed locations, so use mtimes and the Surefire log to tell
    new crashes from old ones.

- **Compiled test resource copies**:
  `safere-fuzz/target/test-classes/org/safere/fuzz/<Fuzzer>Inputs/<method>/`
  - Build output copies. Do not treat these as source-of-truth new findings.

- **Standalone Java reproducers**: path from `-Djazzer.reproducer_path=...`
  - In Jazzer JUnit mode these often do not exist, even when bugs were found.
  - If present, inspect them, but absence is not evidence that no bugs were found.

## Finding New Crash Inputs

Find the fuzzer method and input resource directory. For most SafeRE fuzzers the method name is
visible in the Surefire testcase name and in the resource path:

```bash
rg -n '<testcase name=' safere-fuzz/target/surefire-reports/TEST-org.safere.fuzz.<Fuzzer>.xml
find safere-fuzz/src/test/resources/org/safere/fuzz/<Fuzzer>Inputs -type f -name 'crash-*' \
  -printf '%TY-%Tm-%Td %TH:%TM:%TS %p\n' | sort
```

If the user gives the approximate run time, compare crash mtimes to that time. If they do not,
compare crash mtimes to the Surefire XML mtime and the `sun.java.command` or testcase elapsed time
inside the XML.

## Interpreting Results

Use both logs and crash files:

- The XML usually contains the readable exception text: regex, flags, input, SafeRE result, JDK
  result, stack trace, and dedup tokens.
- `crash-*` files are the inputs Jazzer can replay through the fuzz target, but they may not be
  human-readable.
- A `.txt` report with `Failures: 0, Errors: 0` does not prove no bugs were found when
  `jazzer.keep_going` is in use. Always inspect the XML for `== Java Exception` and `DEDUP_TOKEN`.

Classify findings before filing:

- **compile divergence**: SafeRE accepts/rejects a pattern differently from JDK.
- **match divergence**: SafeRE and JDK compile but produce different match results.
- **API sequence divergence**: matcher/split/replace/stateful operation differs.
- **crash/hang/stack overflow**: SafeRE throws unexpectedly, times out, or exceeds stack limits.

Deduplicate by semantic class, not only exact string. If two findings are variants of the same
parser rule or matcher invariant, discuss whether to file one broader issue or multiple focused
issues.

## Reporting to the User

Summarize:

- Which run files were inspected, with mtimes if recency matters.
- Whether the Surefire report is a latest per-fuzzer report or mixed with older reports.
- Each finding's regex/flags/input/operation and SafeRE vs JDK behavior.
- The corresponding `crash-*` files, if identifiable.
- Whether `target/fuzz-reproducers` exists, and why absence may be normal.

When asked to file issues:

1. Search existing issues for exact repro substrings and the semantic class.
2. Write the issue body to a temp file and use `gh issue create --body-file`.
3. Include the command, Surefire XML path, crash input path, and concise observed behavior.
4. Do not close existing issues unless all items are resolved.
