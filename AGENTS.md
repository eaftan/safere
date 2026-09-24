# SafeRE — Agent Guidelines

SafeRE is a BSD 3-Clause-licensed Java port of RE2 with a linear-time matching
contract and a `java.util.regex`-compatible API within that constraint. It is
not a clean-room port.

## Orientation

- `safere/src/main/java/org/safere/`: library implementation.
- `safere/src/test/java/org/safere/`: unit and regression tests.
- `safere-crosscheck/`: JDK differential facade and generated public API tests.
- `safere-fuzz/`: Jazzer differential fuzz targets and regression corpus.
- `safere-benchmarks/`: JMH suite, workload declarations, and collection tools.
- Java 21 is the source baseline; builds use OpenJDK 26 and CI tests JDK 21–26.
  Maven manages the build, JUnit, AssertJ, and JaCoCo versions.

Read references when the task needs them:

- [DESIGN.md](DESIGN.md): parser → AST simplification → compiler → execution
  architecture, engine selection, and implementation rationale. Check current
  code before relying on engine thresholds or fast-path ordering.
- [DEVELOPMENT.md](DEVELOPMENT.md): build setup, optional modules, versioned
  implementations, and external-project validation.
- [TESTING.md](TESTING.md): test organization and verification commands;
  [safere-fuzz/README.md](safere-fuzz/README.md) for fuzzing.
- [INTENTIONAL_DIVERGENCES.md](INTENTIONAL_DIVERGENCES.md): accepted JDK
  compatibility boundaries.
- [Performance guide](safere-benchmarks/PERFORMANCE_GUIDE.md): required practices
  for benchmarking, profiling, optimization, and publishing performance claims.

Do not read every reference for each task. Keep durable project rules here and
specialized procedures in their linked guides or skills. Update or replace
existing guidance when correcting a recurring problem; remove stale or
conflicting instructions instead of appending another exception.

## Correctness Constraints

- **Compatibility order:** preserve linear time first, then follow the official
  JDK 26 specification. For ambiguous or unspecified behavior, match observed
  JDK behavior when it preserves linear time. If observed behavior contradicts
  the specification, explain the contradiction and obtain the project owner's
  confirmation before adopting an intentional divergence. Existing approval for
  the same decision does not need to be requested again. Record the decision in
  `INTENTIONAL_DIVERGENCES.md`.
- **Bounded compatibility work:** use engine-native state, bounded per-input
  context, or a bounded number of linear passes. Do not repair results by
  repeatedly invoking matchers, replaying prefixes, or rescanning unbounded
  context inside position/retry loops. Find a linear formulation or document
  the compatibility boundary.
- **Supported dialect:** reject backreferences, lookahead/lookbehind, possessive
  quantifiers, and `\C` at parse time with clear errors.
- **Stack safety:** use explicit stacks/worklists or existing iterative Walker
  helpers for AST/program walks, parsing, compilation, matching, Unicode/class
  processing, and public API paths. Recursion is acceptable only in test helpers
  or implementation details with a statically small bound. Deep patterns and
  large inputs must not cause `StackOverflowError`.
- **Unicode:** operate on code points, using `Character.codePointAt()` and
  related APIs rather than treating UTF-16 code units as characters.

## Fixing Bugs

For SafeRE/JDK differences in parsing, matching, captures, matcher state, or
replacement, use the
[divergence-bug-fix skill](.agents/skills/divergence-bug-fix/SKILL.md). It owns
specification assessment and divergence-specific test and fuzz coverage.

For behavior-changing bug fixes:

1. Add systematic regression tests for the behavioral class before investigating
   engine internals. Run them to identify passing and failing cases.
2. Root-cause the failures and fix the underlying design. Avoid pattern-string,
   input-shape, or seed-specific guards unless they encode a documented rule.
3. Verify and explain the general invariant, not just the original reproducer.
   Prefer fewer moving parts and remove obsolete special cases.

Each fixed bug needs a regression that fails without the fix and passes with it.
For an approved intentional divergence with no implementation change, pin the
existing behavior and document it; a failing SafeRE test is not required.

When validating SafeRE in external projects, follow the
[external-validation workflow](DEVELOPMENT.md#external-project-validation): fix
found bugs and recheck the external failure before proceeding.

## Build and Validation

```bash
# Focused library tests (replace MatcherTest with the affected class)
mvn -pl safere -Dtest=MatcherTest test -q

# Full library suite, when warranted
mvn -pl safere test -q

# Build/install this project for dependent modules or external validation
mvn install -DskipTests -q
```

- Run best-effort focused validation before creating or updating a PR. Select
  checks for the changed behavior; use CI for standard broad coverage. A full
  library suite plus generated public API crosschecks is not a routine local
  prerequisite, including for divergence work. Broaden local validation for
  changes spanning engines/shared invariants, failures, unresolved risks, or an
  explicit request. For prose-only changes, check accuracy and links rather
  than running Java suites unless requested.
- For changed tests, run the relevant tests. For changed fuzz coverage, run the
  affected targets in regression mode. For public API compatibility changes,
  select affected generated crosschecks when practical; see `TESTING.md`.
- Run Maven builds/tests sequentially within one checkout: compilation can
  replace class files in use by another run. Use separate worktrees/build
  outputs when concurrent independent builds are needed.
- Once relevant checks pass, rerun or expand them only for new changes,
  failures, or unresolved concerns. Distinguish an environment failure from a
  code failure and do not describe an incomplete check as passed.
- Work is complete when the requested scope is covered, relevant checks have
  been assessed, and remaining limitations are communicated. Respect a request
  to stop at local review rather than publishing.

## Source and Test Conventions

- Follow [Google Java Style](https://google.github.io/styleguide/javaguide.html):
  2-space indentation, 100-character lines, same-line braces, sorted imports
  with static imports separate, and no wildcard imports.
  Match nearby code and use the configured formatter.
- One class per file except private inner classes. Use `@Override` for overrides
  and Javadoc for public/protected members, with `{@code ...}` for code fragments.
- Use `Objects.requireNonNull` where appropriate. Prefer typed collections over
  `Object[]`; primitive arrays are fine for performance.
- Retain BSD license headers and add the established header to new source files.
  Copy the appropriate existing variant: code derived from RE2/J must retain
  its additional attribution, not just the RE2 attribution.
- Do not add `@SuppressWarnings` without project-owner approval, except
  `@SuppressWarnings("ArrayRecordComponent")` on non-public array-carrier
  records when array value semantics are not used and an adjacent comment
  explains ownership or performance. Public records still require approval.
- Use JUnit Jupiter and AssertJ assertions. Name tests for behavior rather than
  issue numbers; issue references belong in comments or display-name suffixes
  when useful. Use `FooTest.java` for tests of `Foo` and appropriate parameterized
  coverage. Port relevant RE2 test cases when useful.
- Use `@DisabledForCrosscheck("reason")` on original tests for assertions that
  should appear as disabled only in generated crosscheck coverage.
- Test performance behavior through stable scaling or relative comparisons,
  not fixed elapsed-time thresholds.
- Do not include test counts or personal names in prose documentation. Refer
  to projects such as RE2 and RE2/J; preserve required license attribution.
- Do not install packages, libraries, or tools on the machine. Ask the project
  owner to install missing prerequisites.

## Performance Work

For optimization work, profile the workload before implementation. Before
committing an optimization, demonstrate an improvement with before/after
benchmarks. Use `./run-java-benchmarks.sh` for Java benchmarks, never
`mvn exec:java`, and run benchmarks sequentially. The scripts own measurement
settings; use standard mode for routine evidence and `--long` to confirm close,
surprising, or important comparisons.

Read the [performance guide](safere-benchmarks/PERFORMANCE_GUIDE.md) for collection
scope, workload syntax, profiling commands, statistics, and artifact requirements.
Use the [write-benchmark-report skill](.agents/skills/write-benchmark-report/SKILL.md)
for collections and published reports. Performance PRs must include measured
before/after results and their improvement or regression.

## Pull Requests

- **Keep PR descriptions focused on the change.** Explain the problem, the
  resulting behavior, and any design decision or compatibility tradeoff
  reviewers need to assess it.
- **Do not include a routine Validation section.** Rely on GitHub CI for
  standard checks. Mention validation only when it provides evidence CI does
  not cover, such as required before/after benchmarks, or when a material
  limitation affects review.
- **Omit work history.** Do not recount investigation steps, tool commands,
  intermediate failures, retries, or local environment problems unless they
  reveal an unresolved risk relevant to the change.
- **Describe the final scope.** Summarize meaningful test coverage when it
  helps explain the behavior being protected. Include issue linkage and
  identify any issue requirements left unresolved.
- **Scale detail to the change.** Prefer one or two short paragraphs for
  simple changes. Use bullets or headings only when they improve readability.
- When creating PRs, use a normal descriptive title. Do not prefix titles with
  `[codex]` unless explicitly requested.
- Create ready-for-review PRs by default. Use draft PRs only when explicitly
  requested or when the work is known to be incomplete.
- **Update existing PRs — do not close and reopen.** Push commits (or
  force-push if rebasing) to the existing branch. Closing and reopening PRs
  loses review context and clutters the issue tracker.
- Do not push directly to `main`. Publish changes on a branch through a PR.

## Issue Tracking and GitHub Text

- Use `Fixes #N` in PR descriptions and commit messages only when all issue
  requirements are resolved; otherwise use `Refs #N` or `Part of #N`. Include
  the linkage in the PR description so GitHub associates the PR with the issue.
- Do not close an issue until all items are resolved. For partial work, post a
  progress comment instead.
- Fix bugs caused by or within the goal of the current task in that task; do
  not split them into separate issues. File an issue immediately for a genuinely
  unrelated bug discovered during other work; do not silently work around it.
- For PR/issue descriptions and comments sent with `gh`, write the body to a
  temporary file and pass `--body-file`. Avoid inline multiline Markdown.
