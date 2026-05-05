---
name: parser-bug-fix
description: "Fix SafeRE parser bugs using the project bug-fixing discipline: test-driven diagnosis, mandatory regression tests, principled class-level fixes instead of point patches, invariant-based verification, and required grammar-biased fuzz generator updates. Use when a SafeRE bug involves regex syntax parsing, parse-time acceptance or rejection, JDK syntax compatibility, parser stack safety, character-class parsing, escape parsing, unsupported regex feature rejection, or crashes/errors from Parser or Pattern.compile."
---

# Parser Bug Fix

## Goal

Fix parser bugs in a way that preserves SafeRE's semantics and linear-time guarantee. The fix must
be test-driven, include regression coverage, address the semantic class of the bug rather than the
single reproducer, and add grammar-biased fuzz coverage for the bug's syntax shape.

## Workflow

1. File or preserve issue traceability when the bug was found during unrelated work.
   - If this parser bug was discovered while doing another task and no issue exists, file a
     GitHub issue immediately before fixing it.
   - Do not use `Fixes #N` unless the change fully resolves every item in the issue; use
     `Refs #N` or `Part of #N` otherwise.

2. Start with tests, before reading parser internals.
   - Ask why the existing tests missed this class of syntax.
   - Add systematic JUnit coverage in `safere/src/test/java/org/safere`, usually
     `ParserTest.java` or `JdkSyntaxCompatibilityTest.java`.
   - Cover representative variants: accepted/rejected forms, inside and outside character
     classes, relevant flags, nesting, separators, Unicode/code point boundaries, and JDK
     compatibility behavior when applicable.
   - Name tests for behavior, not issue numbers. Keep issue IDs only in comments or display-name
     suffixes if useful.

3. Run the new focused tests and confirm they expose the bug.
   - Prefer a narrow Maven run first, such as:
     `mvn -pl safere -Dtest=ParserTest test`
   - For public API compatibility behavior, include the matching crosscheck or compatibility test
     path when practical.

4. Root-cause the parser behavior and make a principled fix.
   - Read parser internals only after the failing coverage exists.
   - Fix the semantic class of the bug, not a pattern string or fuzzer seed.
   - Do not add point guards unless they directly encode a documented regex/API rule.
   - Prefer fewer moving parts. Removing a special case is better than adding one when it
     clarifies the parser invariant.
   - Preserve linear-time constraints: keep rejecting backreferences, lookaround, possessive
     quantifiers, and other unsupported non-regular features at parse time with clear errors.
   - Preserve stack safety; use iterative parsing/tree-walking patterns for deeply nested syntax.

5. Verify the invariant, not just the reproducer.
   - State why the fix is correct for the whole syntax class.
   - Look for another input in the same class that would still break the fix; if one exists, the
     fix is still too narrow.
   - For JDK compatibility fixes, use official JDK Javadoc as the specification when documenting
     or deciding behavior. Do not rely on memory or implementation guesses.
   - When SafeRE intentionally diverges from JDK behavior for linear-time guarantees, document the
     reason in tests or code comments where relevant.

6. Add the syntax shape to the relevant fuzz generator axis.
   - Character-class expressions: `safere-fuzz/src/test/java/org/safere/fuzz/CharacterClassExpressionFuzzer.java`
   - Escapes and escaped literals: `safere-fuzz/src/test/java/org/safere/fuzz/EscapeSyntaxFuzzer.java`
   - Dialect boundaries, unsupported constructs, property spelling variants:
     `safere-fuzz/src/test/java/org/safere/fuzz/DialectSyntaxFuzzer.java`
   - General parser compatibility grammar combinations:
     `safere-fuzz/src/test/java/org/safere/fuzz/ParserCompatibilityFuzzer.java`
   - Deep nesting or parser stack safety:
     `safere-fuzz/src/test/java/org/safere/fuzz/ParserStackSafetyFuzzer.java`
   - If none fits, extend the closest axis or add a new focused parser fuzzer. Do not rely only on
     a one-off regression string.

7. Verify the fix.
   - Run the focused JUnit tests again.
   - Run the relevant fuzz target in regression mode, for example:
     `mvn -pl safere-fuzz -am -Dtest=EscapeSyntaxFuzzer -Dsurefire.failIfNoSpecifiedTests=false test`
   - Run `mvn -pl safere test -q` before finishing a parser bug fix.
   - If the change affects public API compatibility, run:
     `mvn verify -pl safere,safere-crosscheck -am -Pcrosscheck-public-api-tests`

## Regression Test Expectations

- Every parser bug fix must add a regression test that fails without the fix and passes with it.
- Test the behavior class, not only the reproducer.
- Compare against `java.util.regex` when SafeRE intends to match JDK behavior.
- When SafeRE intentionally diverges for linear-time guarantees, assert the rejection and document
  the reason in the test.
- Include parser acceptance/rejection tests and, when acceptance changed, at least one membership
  or compile-through-public-API assertion.
- For performance or scaling parser regressions, do not hardcode elapsed durations. Test scaling
  behavior or relative work in a machine-stable way.

## Fix Quality Bar

- No pattern-string checks, input-shape checks, or fuzzer-seed checks unless they directly encode a
  documented regex/API rule.
- No new `@SuppressWarnings` annotations without explicit project-owner approval.
- No backreferences, lookahead/lookbehind, possessive quantifiers, or unsupported non-regular
  constructs may be accepted as a side effect of a parser fix.
- No recursive parser/tree traversal that can overflow on deeply nested regexes.
- No undocumented compatibility divergence from `java.util.regex` unless the divergence is
  fundamental to SafeRE's linear-time design or the JDK behavior is internally inconsistent.

## Fuzz Coverage Rule

Every parser bug fix must update a grammar-biased fuzz generator so future fuzzing can rediscover
nearby syntax shapes. Add explicit regression seeds/constants when helpful, but also expand the
generator pieces, prefixes, suffixes, operators, or contexts that describe the broader shape.
