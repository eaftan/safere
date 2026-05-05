---
name: fuzzer-run-triage
description: "Triage SafeRE Jazzer fuzzer runs performed manually by the user: locate Surefire/Jazzer logs, distinguish latest run artifacts from persistent corpus and older crashes, extract bugs/findings, identify crash inputs, summarize repro details, and prepare GitHub issue material without rerunning fuzzing."
---

# Fuzzer Run Triage

## Goal

When the user has run a SafeRE Jazzer fuzzer by hand, find what that run discovered and present
the actionable bug findings. Do not assume `target/fuzz-reproducers` exists: Jazzer JUnit usually
writes libFuzzer `crash-*` inputs to the fuzzer's input resource directory. Maven/Surefire writes
XML summaries under `target/surefire-reports`, and SafeRE's helper script records raw
stdout/stderr under `target/fuzz-logs`.

## First Checks

1. Identify the fuzzer class from the user's command or question, for example
   `CharacterClassExpressionFuzzer`.
2. Inspect the latest matching Surefire files:
   - `safere-fuzz/target/surefire-reports/TEST-org.safere.fuzz.<Fuzzer>.xml`
   - `safere-fuzz/target/surefire-reports/org.safere.fuzz.<Fuzzer>.txt`
3. Inspect helper-script console logs if present. These are often the best source for
   `artifact_prefix`, `Test unit written`, `Base64`, timeout, and crash path lines that Surefire
   XML may omit:

   ```bash
   find safere-fuzz/target/fuzz-logs -path '*/<Fuzzer>.log' \
     -printf '%TY-%Tm-%Td %TH:%TM:%TS %p\n' | sort
   ```

4. Check file mtimes before trusting artifacts:

   ```bash
   stat safere-fuzz/target/surefire-reports/TEST-org.safere.fuzz.<Fuzzer>.xml
   stat safere-fuzz/target/surefire-reports/org.safere.fuzz.<Fuzzer>.txt
   stat safere-fuzz/target/fuzz-logs/<run-id>/<Fuzzer>.log
   ```

5. Extract findings from the XML and raw console log:

   ```bash
   rg -n '== Java Exception|AssertionError|CrosscheckException|PatternSyntaxException|divergence|DEDUP_TOKEN|libFuzzer crashing input|Test unit written|artifact_prefix|reproducer_path|Java reproducer written' \
     safere-fuzz/target/surefire-reports/TEST-org.safere.fuzz.<Fuzzer>.xml
   rg -n '== Java Exception|AssertionError|CrosscheckException|PatternSyntaxException|divergence|DEDUP_TOKEN|libFuzzer: timeout|Test unit written|artifact_prefix|Base64:|reproducer_path|Java reproducer written' \
     safere-fuzz/target/fuzz-logs/<run-id>/<Fuzzer>.log
   ```

## Where Artifacts Live

- **Surefire logs**: `safere-fuzz/target/surefire-reports/`
  - Mixed by fuzzer class in one directory.
  - The same fuzzer's `.xml` and `.txt` files are overwritten by newer runs.
  - Other fuzzer reports may be older and unrelated.
  - XML may omit raw libFuzzer lines such as `artifact_prefix`, `Test unit written`, and `Base64`.

- **Raw helper-script console logs**: `safere-fuzz/target/fuzz-logs/<run-id>/<Fuzzer>.log`
  - Created by `safere-fuzz/scripts/run-fuzz-test.sh`.
  - Preserve the combined stdout/stderr stream while still showing output live in the terminal.
  - Prefer these logs for mapping `== Java Exception` blocks to `crash-*`, `slow-unit-*`, and
    `timeout-*` files.
  - If a user provides a manually saved console log, inspect it the same way as these files.

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

## Replaying Candidates on the Current Branch

When the user wants bugs verified or filed, replay candidates against the current working tree and
current branch. Do not treat old console output as proof that a bug still exists.

1. Install the current SafeRE module and compile the current fuzz test classes first:

   ```bash
   mvn -pl safere -DskipTests install -q
   mvn -pl safere-fuzz -DskipTests test-compile -q
   ```

   Do not skip the `safere` install step before standalone `safere-fuzz` replays. A command such
   as `mvn -pl safere-fuzz ... surefire:test` does not rebuild reactor dependencies, so it can
   resolve an older `org.safere:safere` artifact from the local Maven repository and report bugs
   that are already fixed on the current branch.

2. Replay saved inputs one at a time by isolating the copied test resource directory under
   `target/test-classes`. This avoids stale build-output inputs and gives an exact
   `crash-*`-to-finding mapping:

   ```bash
   TARGET=safere-fuzz/target/test-classes/org/safere/fuzz/<Fuzzer>Inputs/<method>
   SOURCE=safere-fuzz/src/test/resources/org/safere/fuzz/<Fuzzer>Inputs/<method>
   rm -f "$TARGET"/*
   cp "$SOURCE/<crash-or-timeout-file>" "$TARGET/"
   mvn -pl safere-fuzz -Dtest=<Fuzzer> -Dsurefire.failIfNoSpecifiedTests=false surefire:test
   ```

3. Restore the copied test resources from source after isolated replay:

   ```bash
   rm -f "$TARGET"/*
   cp "$SOURCE"/* "$TARGET"/
   ```

4. Record whether the candidate still reproduces, the current assertion output, and the exact
   source input file. If it no longer reproduces on the current branch, say so and do not file a
   bug for it.

5. Deduplicate reproducible findings by semantic bug class before filing. Multiple `crash-*` files
   may represent one parser rule, matcher invariant, or Unicode-boundary bug.

## Interpreting Results

Use both logs and crash files:

- The XML usually contains the readable exception text: regex, flags, input, SafeRE result, JDK
  result, stack trace, and dedup tokens.
- `crash-*` files are the inputs Jazzer can replay through the fuzz target, but they may not be
  human-readable.
- Raw console logs can contain crash paths and Base64 units even when Surefire XML has only
  exception text, or when the fork exits on a libFuzzer timeout before a useful XML report is
  written.
- A `.txt` report with `Failures: 0, Errors: 0` does not prove no bugs were found when
  `jazzer.keep_going` is in use. Always inspect the XML and raw console log for
  `== Java Exception`, `DEDUP_TOKEN`, `Test unit written`, and `libFuzzer: timeout`.

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
- Whether a raw `target/fuzz-logs/<run-id>/<Fuzzer>.log` file, or a manually saved console log,
  was inspected.
- Each finding's regex/flags/input/operation and SafeRE vs JDK behavior.
- The corresponding `crash-*` files, if identifiable.
- Any `slow-unit-*`, `timeout-*`, or Base64 units for hangs/timeouts.
- Whether `target/fuzz-reproducers` exists, and why absence may be normal.

When asked to file issues:

1. Search existing issues for exact repro substrings and the semantic class.
2. Write the issue body to a temp file and use `gh issue create --body-file`.
3. Include the command, Surefire XML path, raw console log path, crash input path, and concise
   observed behavior.
4. Do not close existing issues unless all items are resolved.
