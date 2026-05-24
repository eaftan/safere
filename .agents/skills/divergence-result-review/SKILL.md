---
name: divergence-result-review
description: Review SafeRE/java.util.regex divergence sweep outputs, JSONL reports, fuzz result sets, or differential matrices by bucketing related failures, classifying whether each bucket is specification-required, reasonable compatibility behavior, intentional JDK implementation detail, or unsupported under SafeRE's linear-time guarantee, and assessing whether each bucket can be fixed with a principled linear-time implementation. Use when asked to analyze, inventory, categorize, triage, summarize, or decide next steps for divergence results rather than immediately fixing a single bug.
---

# Divergence Result Review

## Goal

Turn raw SafeRE/JDK divergence artifacts into a small set of semantic buckets with a decision for
each bucket:

1. Does the JDK behavior follow the official specification, is it otherwise reasonable behavior
   SafeRE should target, or is it an observed JDK implementation detail?
2. Can SafeRE support that behavior with a principled implementation that preserves the linear-time
   guarantee?

This skill is for review and triage. Do not fix code unless the user explicitly asks.

## Sources Of Truth

Use the SafeRE compatibility policy:

1. Preserve SafeRE's linear-time guarantee.
2. Within that constraint, follow the official JDK specification.
3. If the specification is ambiguous or silent, match observed JDK behavior only when it can be
   expressed as principled engine semantics and remains linear.
4. If observed JDK behavior appears to be an implementation detail or requires retry loops,
   verifier matchers, pattern-specific exceptions, or repeated rescans, classify it as an
   intentional divergence candidate rather than a bug to emulate.

When spec interpretation matters, check the official JDK Javadocs:

- `Pattern`: `https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/util/regex/Pattern.html`
- `Matcher`: `https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/util/regex/Matcher.html`

For Unicode grapheme behavior, use Unicode UAX #29 and the project design:

- UAX #29: `https://www.unicode.org/reports/tr29/`
- `design/GRAPHEME_REGION_MATCHING.md`

## Artifact Triage

1. Inventory the run directory before reading large files:
   - `rg --files <report-dir>`
   - `du -h <report-dir>/*`
   - inspect summary TSV/JSON files first if they exist.
2. If only a huge JSONL exists, treat it as a stream:
   - sample `head`, `tail`, and evenly spaced byte offsets;
   - use bounded streaming scripts, `jq`, `rg -m`, or `python3` generators;
   - never load the full file or build an exact unbounded hash map over all buckets.
3. State whether the run is complete:
   - complete if final summary files were written or the run reported completion;
   - incomplete if the process was stopped before summary output.
4. Do not claim exact counts from samples. Report exact counts only from completed summary files or
   bounded explicit passes whose scope you state.

## Bucketing Workflow

1. Parse each divergence into stable dimensions:
   - regex label and regex;
   - operation (`matches`, `lookingAt`, `find`, repeated find, reset/reuse, replacement, compile);
   - region shape, transparent bounds, anchoring bounds;
   - input family or Unicode class shape;
   - mismatch direction: accept/reject, trace bounds, captures, errors, stateful behavior.
2. Bucket by semantic shape, not by raw input string. Prefer labels such as:
   - `matches()` end condition mismatch;
   - non-anchoring anchor context;
   - transparent boundary visibility;
   - repeated-find cursor after empty boundary;
   - grapheme cluster segmentation context;
   - capture trace mismatch;
   - parser acceptance/rejection mismatch.
3. For each bucket, keep representative examples:
   - smallest readable reproducer if available;
   - one high-risk Unicode/region example if it reveals the real boundary condition;
   - one example showing captures or statefulness if relevant.
4. Separate already-known intentional classes from unknown classes. Use existing project tests and
   design docs to avoid reopening decisions already made.

## Classification

Classify each bucket with both a behavior status and a feasibility status.

Behavior statuses:

- `SPEC_REQUIRED`: JDK behavior is required by Pattern/Matcher Javadocs and can be stated as a
  normal regex/API rule.
- `REASONABLE_COMPAT`: the spec is ambiguous or silent, but the observed JDK behavior is coherent,
  useful, and fits SafeRE's model.
- `JDK_DETAIL`: observed behavior appears to come from java.util.regex implementation mechanics
  rather than from the spec or a coherent compositional model.
- `LINEAR_TIME_UNSUPPORTED`: the behavior may be specified or useful, but supporting it would break
  SafeRE's linear-time guarantee or require unsupported regex semantics.
- `NEEDS_MORE_DATA`: the artifact is too incomplete or ambiguous to classify responsibly.

Feasibility statuses:

- `ENGINE_NATIVE_LINEAR`: can be implemented as a bounded engine transition, predicate, cache key,
  or context parameter.
- `LINEAR_WITH_CACHING`: can be implemented linearly if per-input or per-context state is cached
  instead of rescanned.
- `LINEAR_BY_GUARDING_PATHS`: can be supported by routing affected programs away from optimized
  paths until those paths share the canonical semantics.
- `NOT_PRINCIPLED`: exact emulation would require verifier matchers, repeated re-search, post-hoc
  retries, pattern/input special cases, or behavior that does not compose.
- `UNKNOWN_FEASIBILITY`: more code review or targeted testing is needed.

## Linear-Time Assessment

For each bucket that might be fixable, identify the intended implementation shape:

- explicit engine context dimension;
- cache key dimension;
- single-pass or amortized-O(1) boundary table;
- monotonic search cursor rule;
- parser acceptance/rejection rule;
- optimized-engine guard or shared semantic primitive.

Reject or flag designs that require:

- launching a new matcher for many nearby candidate positions;
- scanning backward through an unbounded prefix per boundary query;
- retrying NFA/DFA searches after a candidate was selected;
- pattern-string or input-shape exceptions;
- recovering captures by repeatedly rerunning searches.

## Report Format

Lead with a concise inventory:

```
Artifacts reviewed:
- <path>: <size/status>
- Run status: complete/incomplete
- Counting method: exact summary / bounded sample / targeted stream scan
```

Then report buckets in a table or compact sections:

```
Bucket: <semantic name>
Representative: /regex/ on <input/region summary>
Observed: JDK <trace>; SafeRE <trace>
Behavior classification: SPEC_REQUIRED | REASONABLE_COMPAT | JDK_DETAIL | ...
Linear-time feasibility: ENGINE_NATIVE_LINEAR | NOT_PRINCIPLED | ...
Decision: fix / document intentional divergence / investigate
Reasoning: <spec/design/linear-time explanation>
```

End with recommended next steps:

- buckets to fix now;
- buckets to document as intentional divergences;
- buckets needing targeted tests or a narrower sweep;
- sweep harness improvements only if the artifact itself prevents useful review.

## Discipline

- Do not fix code unless asked.
- Do not overfit to one fuzzer seed or one JSON row.
- Do not treat observed JDK behavior as automatically correct.
- Do not dismiss JDK behavior as a quirk without explaining why it is unspecified, incoherent, or
  not expressible in SafeRE's linear-time engine model.
- Cite official docs or project design files when classification depends on them.
- If a new actionable bug is discovered outside the current task, preserve issue traceability under
  the repository's AGENTS.md rules.
