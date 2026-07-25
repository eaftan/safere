# SafeRE CI Test Recording

This module measures execution overlap among SafeRE tests that exercise the public regex API.
It generates copies of eligible tests, rewrites them to import a recording facade, and delegates
every facade operation once to SafeRE. The original tests are not modified.

The recording facade captures only calls made by test code. Unlike the crosscheck facade, it does
not invoke the JDK or inspect additional match state.

## Focused Run

Run a focused overlap analysis with the same JUnit test selector syntax accepted by Surefire:

```bash
mvn -pl safere-recording -am \
  -Precord-public-api-tests \
  -Dtest=MatcherTest,QuantifiedCaptureSemanticsTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  verify
```

The generated files are:

- `target/recording/events.tsv`: raw facade events, with text fields Base64-encoded so each event
  occupies one line.
- `target/recording/overlap-report.md`: test-method summaries, containment candidates, and
  representative exact overlaps.

The current reporter keeps distinct fingerprints in memory. Use focused groups of test classes
rather than the entire `CrossEngineExhaustiveTest` until the external-sort aggregation path is
implemented.

## Recorded Identity

Events are grouped into facade-object lifecycles. Each lifecycle produces:

- an interaction fingerprint over the facade type, ordered method names, and typed arguments;
- an execution fingerprint that additionally includes results and exceptions.

The report uses execution fingerprints for exact overlap and containment. Parameterized JUnit
invocations are aggregated under their test-template method while preserving every individual
execution in the counts.

The encoding preserves nulls, empty strings, UTF-16 contents, flags, byte arrays, matcher state
operations, replacements, results, and exception types. It does not normalize semantically
equivalent regular expressions.

## Coverage

The generated source set starts with the public-API-compatible selection maintained by
`safere-crosscheck`. Tests tied to package-private parser, compiler, program, or execution-engine
classes are excluded. UTF-8 public API tests are supported by recording versions of `Utf8Input`,
`Utf8Matcher`, and `Utf8Sink`.

Overlap is evidence for review, not an automatic deletion decision. A focused regression can
remain valuable even when a broad matrix contains the same execution.
