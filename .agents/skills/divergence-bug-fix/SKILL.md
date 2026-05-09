---
name: divergence-bug-fix
description: "Fix or classify SafeRE bugs where SafeRE behavior diverges from java.util.regex using the project bug-fixing discipline: JDK 26 Pattern/Matcher Javadoc assessment, test-driven diagnosis for behavior changes, principled class-level fixes instead of point patches, invariant-based verification, and regression/fuzz coverage where applicable. Use when a SafeRE bug involves any difference from the JDK in regex syntax parsing, parse-time acceptance or rejection, matching results, capture semantics, quantified captures, group boundaries, find() sequences, regions/bounds, hitEnd()/requireEnd(), matcher state transitions, replacement behavior, or crashes/errors from Parser, Pattern.compile, Pattern, or Matcher."
---

# SafeRE Divergence Bug Fix

## Goal

Fix or classify bugs where SafeRE diverges from `java.util.regex` in a way that follows the project
compatibility policy:

1. Preserve SafeRE's linear-time guarantee. Never choose JDK specification compliance or observed
   JDK behavior compatibility when doing so would violate SafeRE's linear-time guarantee.
2. Within the linear-time constraint, comply with the JDK specification.
3. If the JDK specification is ambiguous or unspecified, match the JDK's behavior when doing so
   preserves SafeRE's linear-time guarantee.
4. If the JDK's behavior does not match the specification, stop and explain the contradiction to
   the user. If the user confirms proceeding, note the divergence as intentional and match the JDK
   specification, subject to the linear-time guarantee.

When a SafeRE behavior change is needed, the fix must be test-driven, include regression coverage,
address the semantic class of the bug rather than the single reproducer, preserve SafeRE's
linear-time guarantee, and add fuzz seed or generator coverage for the bug's behavioral shape. When
the divergence is intentional and no code change is needed, document the compatibility decision and
add or preserve tests, docs, or fuzz coverage when useful.

## Workflow

1. File or preserve issue traceability when the bug was found during unrelated work.
   - If this divergence bug was discovered while doing another task and no issue exists, file a
     GitHub issue immediately before fixing or classifying it.
   - Do not use `Fixes #N` unless the change fully resolves every item in the issue; use
     `Refs #N` or `Part of #N` otherwise.

2. Read the bug report or reproducer and classify the behavior against the JDK 26 specification.
   - Assess SafeRE behavior and JDK behavior against the official JDK 26 Javadocs:
     - `Pattern`: https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/util/regex/Pattern.html
     - `Matcher`: https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/util/regex/Matcher.html
   - Determine whether the specification supports SafeRE's behavior, the JDK's behavior, both, or
     neither. Quote or cite the specific Javadoc rule when it controls the behavior.
   - Before reading or discussing SafeRE implementation internals, state the spec assessment to the
     user using only the behavior reported in the bug report or reproducer: what SafeRE did, what
     the JDK did, which Javadoc rule controls the behavior, and whether SafeRE's reported behavior
     is spec-compliant. Do not include root-cause or implementation analysis in this assessment.
   - If SafeRE's reported behavior is not spec-compliant and matching the specification preserves
     the linear-time guarantee, continue with the bug-fixing workflow. If SafeRE's reported
     behavior is spec-compliant, or if the assessment is ambiguous, stop and wait for the user
     before writing tests, inspecting internals, or changing code.
   - If the JDK behavior contradicts the specification, stop before writing tests or code and
     explain that to the user. If the user confirms proceeding, the intended SafeRE outcome is to
     follow the JDK specification, subject to the linear-time guarantee, and document the divergence
     from observed JDK behavior as intentional.
   - If the specification is ambiguous or silent, treat the observed JDK behavior as the
     compatibility target only when matching it preserves SafeRE's linear-time guarantee.
   - If the divergence is required by SafeRE's linear-time guarantee, document the unsupported
     behavior as an intentional divergence even if the JDK specification or observed JDK behavior
     supports it.
   - If the classification shows no SafeRE behavior change is needed because the divergence is
     intentional, document the decision and skip to the documentation, coverage, and verification
     steps that apply.

3. If a SafeRE behavior change is needed, start with tests before reading parser, capture, matcher,
   or engine internals.
   - Ask why the existing tests missed this class of syntax or behavior.
   - Add systematic JUnit coverage in `safere/src/test/java/org/safere`, usually
     `ParserTest.java`, `JdkSyntaxCompatibilityTest.java`, `MatcherTest.java`,
     `MatcherStateMachineTraceTest.java`, `QuantifiedCaptureSemanticsTest.java`, replacement
     tests, or another focused public API compatibility test.
   - Cover representative variants for the bug class: accepted/rejected forms, inside and outside
     character classes, flags, nesting, separators, Unicode/code point boundaries, capture groups,
     nullable/quantified captures, repeated `find()` sequences, regions/bounds, replacement
     templates, end-state flags, and JDK compatibility behavior when applicable.
   - Name tests for behavior, not issue numbers. Keep issue IDs only in comments or display-name
     suffixes if useful.
   - Encode the compatibility decision in the tests: intentional SafeRE divergence for linear-time
     constraints, spec-required behavior within those constraints, or JDK behavior for ambiguous or
     unspecified areas when matching it preserves linear time.

4. If a SafeRE behavior change is needed, run the new focused tests and confirm they expose the
   SafeRE behavior that needs to change.
   - Prefer a narrow Maven run first, such as:
     `mvn -pl safere -Dtest=JdkSyntaxCompatibilityTest test`
   - For public API compatibility behavior, include the matching crosscheck or compatibility test
     path when practical.

5. If a SafeRE behavior change is needed, root-cause the SafeRE behavior and make a principled fix.
   - Read internals only after the failing coverage exists.
   - Fix the semantic class of the bug, not a pattern string or fuzzer seed.
   - Do not add point guards unless they directly encode a documented regex/API rule.
   - Prefer fewer moving parts. Removing a special case is better than adding one when it
     clarifies the relevant invariant.
   - Preserve linear-time constraints: keep rejecting backreferences, lookaround, possessive
     quantifiers, and other unsupported features that would violate SafeRE's guarantees or
     supported dialect at parse time with clear errors.
   - Preserve stack safety; use iterative parsing/tree-walking patterns for deeply nested syntax.

6. Verify the invariant or compatibility decision, not just the reproducer.
   - If fixing SafeRE behavior, state why the fix is correct for the whole behavior class.
   - If fixing SafeRE behavior, look for another input in the same class that would still break the
     fix; if one exists, the fix is still too narrow.
   - If no behavior change is needed because the divergence is intentional, state why the existing
     behavior is correct under the compatibility policy.
   - Use the JDK 26 `Pattern` and `Matcher` Javadocs as the specification when documenting or
     deciding behavior. Do not rely on memory or implementation guesses.
   - When SafeRE intentionally diverges from observed JDK behavior because linear-time guarantees
     or SafeRE's supported dialect require rejection of an unsupported feature, or because the JDK
     contradicts the spec and the user has confirmed proceeding, document the reason in tests or
     code comments where relevant.

7. Update or preserve fuzz coverage as applicable.
   - Character-class expressions:
     `safere-fuzz/src/test/java/org/safere/fuzz/CharacterClassExpressionFuzzer.java`
   - Escapes and escaped literals:
     `safere-fuzz/src/test/java/org/safere/fuzz/EscapeSyntaxFuzzer.java`
   - Dialect boundaries, unsupported constructs, property spelling variants:
     `safere-fuzz/src/test/java/org/safere/fuzz/DialectSyntaxFuzzer.java`
   - General parser compatibility grammar combinations:
     `safere-fuzz/src/test/java/org/safere/fuzz/ParserCompatibilityFuzzer.java`
   - Deep nesting or parser stack safety:
     `safere-fuzz/src/test/java/org/safere/fuzz/ParserStackSafetyFuzzer.java`
   - Captures, quantified captures, group boundaries:
     `safere-fuzz/src/test/java/org/safere/fuzz/MatchFuzzer.java` or a focused capture fuzzer.
   - Stateful matcher APIs, repeated `find()`, regions/bounds, `hitEnd()`/`requireEnd()`:
     `safere-fuzz/src/test/java/org/safere/fuzz/FindSequenceFuzzer.java` or
     `safere-fuzz/src/test/java/org/safere/fuzz/RegionBoundsFuzzer.java`.
   - Replacement templates and replacement state:
     an existing replacement fuzzer if present, or the closest public API fuzzer.
   - For behavior changes, add the behavioral shape to the relevant fuzz generator axis or seed
     corpus. If none fits, extend the closest axis or add a new focused fuzzer. Do not rely only on
     a one-off regression string.
   - If no code change is made because the divergence is intentional, add or preserve fuzz coverage
     when useful.

8. Verify the fix or intentional-divergence decision.
   - If tests changed, run the focused JUnit tests again.
   - If fuzz coverage changed, run the relevant fuzz target in regression mode, for example:
     `mvn -pl safere-fuzz -am -Dtest=EscapeSyntaxFuzzer -Dsurefire.failIfNoSpecifiedTests=false test`
   - If SafeRE behavior changed, run `mvn -pl safere test -q` before finishing a
     behavior-changing divergence fix.
   - If the change affects public API compatibility, run:
     `mvn verify -pl safere,safere-crosscheck -am -Pcrosscheck-public-api-tests`

## Regression Test Expectations

- Every SafeRE behavior change for a divergence bug must add a regression test that fails without
  the fix and passes with it.
- If no code change is made because the divergence is intentional, add or preserve documentation or
  test coverage when useful.
- Test the behavior class, not only the reproducer.
- Compare against `java.util.regex` when the JDK specification is ambiguous or unspecified, matching
  observed JDK behavior preserves SafeRE's linear-time guarantee, and SafeRE intends to match it.
- Assert specification-required behavior directly when the JDK 26 Javadocs control the outcome and
  that behavior preserves SafeRE's linear-time guarantee.
- When SafeRE intentionally diverges from observed JDK behavior, document whether the reason is
  SafeRE's linear-time guarantee or JDK-spec compliance after user-confirmed JDK/spec contradiction.
- Include parser acceptance/rejection tests and, when acceptance changed, at least one membership
  or compile-through-public-API assertion.
- For performance or scaling regressions, do not hardcode elapsed durations. Test scaling behavior
  or relative work in a machine-stable way.

## Fix Quality Bar

- No pattern-string checks, input-shape checks, or fuzzer-seed checks unless they directly encode a
  documented regex/API rule.
- No new `@SuppressWarnings` annotations without explicit project-owner approval.
- No backreferences, lookahead/lookbehind, possessive quantifiers, or unsupported features that
  would violate SafeRE's guarantees or supported dialect may be accepted as a side effect of a
  divergence fix.
- No recursive parser/tree traversal that can overflow on deeply nested regexes.
- No undocumented compatibility divergence from `java.util.regex`: either match the JDK behavior
  for ambiguous or unspecified behavior when doing so preserves linear time, or document why SafeRE
  must reject unsupported behavior to preserve linear time, or why the JDK behavior contradicts the
  JDK 26 specification after the user confirms proceeding.

## Fuzz Coverage Rule

Every SafeRE behavior change for a divergence bug must update a fuzz generator so future fuzzing can
rediscover nearby behavioral shapes. Add explicit regression seeds/constants when helpful, but also
expand the generator pieces, prefixes, suffixes, operators, API call sequences, or contexts that
describe the broader shape. If no code change is made because the divergence is intentional, add or
preserve fuzz coverage when useful.
