# Contributing to SafeRE

Bug reports, documentation improvements, and code contributions are welcome.
Use [GitHub issues](https://github.com/eaftan/safere/issues) to report a problem
or discuss substantial changes before starting work. Include a minimal
reproducer and the SafeRE and JDK versions for a bug report.

## Development setup

Building requires **JDK 26 and Maven 3.9 or newer**. The library targets Java 21;
CI checks the built artifacts on JDK 21 through 26. Run commands from the
repository root:

```bash
mvn -pl safere -am install -DskipTests
mvn -pl safere -Dtest=MatcherTest test
mvn spotless:apply
```

Choose the test class for your change. See the [developer guide](docs/DEVELOPMENT.md)
for the complete build workflow and [testing guide](docs/TESTING.md) for other
test suites. To enable the repository's formatting hook:

```bash
git config core.hooksPath .githooks
```

## Changes and pull requests

Preserve linear-time matching first, then JDK compatibility within that
constraint. Add regression coverage for behavior changes and fix the underlying
behavioral class. Follow [AGENTS.md](AGENTS.md) for project conventions and
[semantic invariants](docs/INVARIANTS.md) for the matching contracts.

Run focused checks for the changed behavior. Broaden validation when a change
spans engines or shared invariants, or leaves an unresolved risk. Documentation
changes need accuracy and link checks. Performance changes require profiling
and measured before/after evidence under the
[performance guide](safere-benchmarks/PERFORMANCE_GUIDE.md).

Submit a pull request from a branch. Describe the problem, final behavior,
and any compatibility tradeoff. Link the related issue; use `Fixes #N` only
when the change resolves it completely. Keep routine validation and work
history out of the description; include benchmark evidence or material
limitations when relevant to review.
