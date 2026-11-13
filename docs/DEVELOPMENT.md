# Developer guide

Read [CONTRIBUTING.md](../CONTRIBUTING.md) for contribution requirements and
[AGENTS.md](../AGENTS.md) for project conventions. The
[architecture guide](ARCHITECTURE.md) explains the library; the
[testing guide](TESTING.md) describes validation. Run commands from the repository root.

## Building and testing

Source builds require JDK 26 and Maven 3.9 or newer. The production JDK is
recorded in [.sdkmanrc](../.sdkmanrc). The library's baseline is Java 21;
CI tests compiled artifacts on JDK 21 through 26.

On a shared development machine, prefix Maven commands with
`./tools/machine-run --` as below, including Maven commands in later examples.

```bash
# Build and install the core library and its reactor dependencies.
./tools/machine-run -- mvn -pl safere -am install -DskipTests

# Run focused library tests.
./tools/machine-run -- mvn -pl safere -Dtest=MatcherTest test

# Build and install the full reactor.
./tools/machine-run -- mvn install

# Generate library Javadoc.
./tools/machine-run -- mvn -pl safere javadoc:javadoc
```

Optional native benchmark engines have additional prerequisites; see
[benchmark runners](../safere-benchmarks/README.md).

## Exclusive machine access

Tests, builds, profiling, fuzzing, and benchmarks share one exclusive reservation
on a development machine. Agents can investigate, edit, and review concurrently,
then reserve the machine only while executing heavy commands. This avoids both
overlapping measurements and fixed delays between agents.

The [machine runner](../tools/machine-run) requires Python 3.9 or newer and POSIX
file locks and process groups (Linux or macOS). It uses Python's standard-library
`fcntl.flock` and waits in the OS until the running command finishes. Lock
acquisition does not have a time limit or promise FIFO ordering. It preserves
the command's arguments, working directory, standard
streams, and exit status. Reservation messages go to stderr.

```bash
./tools/machine-run --label "field-path tests" -- \
  mvn -pl safere -Dtest=MatcherTest test -q

./tools/machine-run --status

# Keep a prepared comparison together in a single reservation.
./tools/machine-run --label "baseline/candidate comparison" -- bash -c '
  (cd /path/to/baseline && ./run-java-benchmarks.sh "Benchmark.method") &&
  (cd /path/to/candidate && ./run-java-benchmarks.sh "Benchmark.method")
'
```

All root `run-*-benchmarks.sh` scripts, `collect-benchmark-results.sh`,
`materialize-benchmark-inputs.sh`, the branch comparison and benchmark configuration
evaluation tools, and `safere-fuzz/scripts/run-fuzz-test.sh` reserve the machine
automatically, including their build phases. Invoke other commands, including
direct Maven, Java profiling, native builds, and external-project tests, through
the runner explicitly. This is cooperative coordination: commands that bypass
the runner can still interfere with measurements.

The default lock is `/tmp/safere-machine-heavy.lock`, independent of the checkout
and `TMPDIR`. It is created with access restricted to its user. Separate
worktrees and clones using this runner coordinate through that same file. If
`SAFERE_MACHINE_LOCK_FILE` is configured, every participating session must use the
same absolute path on a shared local filesystem. A different path creates an
independent reservation; use that only for isolated tooling tests. Never remove
or replace the lock file to release it: the kernel lock belongs to the open
file, and a replacement would allow overlapping workloads. Old owner metadata
can remain in an idle file; `--status` checks the kernel lock before reporting it.

Nested runners reuse the reservation within the workload's process group,
including calls through Python subprocesses that close inherited descriptors.
`SAFERE_MACHINE_RUN_CONTEXT` is internal state; do not set or copy it manually.
Run foreground commands: do not detach workers, start new sessions, or launch
parallel heavy workloads within a reservation. The runner terminates remaining
workers in its workload's process group when the foreground command exits.
On SIGINT, SIGTERM, or SIGHUP, it forwards the signal to that group, escalating
to SIGKILL after a grace period if needed, before releasing its reservation.
Cancel a waiting runner to withdraw that request without affecting the owner.

Existing worktrees need these changes to reserve automatically. For an older
checkout, invoke the new runner by absolute path with that checkout as the
working directory. Already running sessions also need the updated workflow
instruction; previously launched heavy commands are outside this mechanism.

The runner provides coordination rather than a guarantee of an otherwise idle
machine. Unrelated applications and system activity can still affect measurements.

Verify the coordinator without running Java workloads:

```bash
./tools/machine-run -- python3 -m unittest discover -s tools
```

## GitHub Actions dependencies

All workflows use the generated
[dependency lockfile](../.github/workflows/actions.lock). After adding a workflow
or changing an action reference, regenerate it from the repository root:

```bash
gh extension install github/gh-actions-lock --pin v0.4.1
gh actions-lock
gh actions-lock --verify
```

Commit the workflow changes and lockfile together; do not edit the lockfile by
hand. CI verifies dependency coverage and upstream provenance on every pull
request, including documentation changes. Dependabot updates the lockfile with
its GitHub Actions version updates using the existing configuration.

Review changes to locked commits as dependency updates, and investigate changed
repository or owner IDs. See GitHub's
[lockfile guide](https://docs.github.com/en/actions/how-tos/secure-your-work/lock-workflow-dependencies)
for handling moved references.

## Formatting

SafeRE uses google-java-format through Spotless:

```bash
mvn spotless:apply
mvn spotless:check
```

Enable the formatting hook once per checkout with
`git config core.hooksPath .githooks`. Retain the established source license
headers, including RE2/J attribution on derived code. The hook formats and
stages all modified Java files, so it does not preserve partial Java staging.

## Optional modules and packaging

Normal library use requires no extra JVM flags. The optional Vector scanner
uses the incubating Vector API; its activation and stability contract are in
[Direct UTF-8 matching](UTF8.md#experimental-vector-scanner). Build and test
configuration can add module readability flags internally; these are not
additional application requirements.

Keep the baseline public API expressible in Java 21 types. JDK-specific
implementations must remain internal, with a baseline path available. If a
versioned implementation is added, use the standard multi-release JAR layout:
the baseline classes at the root and replacements under
`META-INF/versions/<version>/`, with `Multi-Release: true` in the manifest.
Classes that link to optional modules must not load eagerly during ordinary use.
See the [library POM](../safere/pom.xml) for the actual packaging configuration.

Verify packaged artifacts as well as classes in the build directory when
changing packaging or JDK-specific implementations:

```bash
mvn -pl safere package -Prelease -DskipTests
jar --list --file safere/target/safere-<version>.jar
jar --describe-module --file safere/target/safere-<version>.jar
jar --validate --file safere/target/safere-<version>.jar
```

Replace `<version>` with the POM version. Check that only intended public
packages are exported, binary JARs do not contain sources, and source JARs
include any versioned sources. Run runtime compatibility checks against the
packaged archive to detect misplaced entries and module-loading problems.

The [Vector benchmark module](../safere-vector-benchmarks/README.md) is outside
the normal reactor. Unicode data regeneration is also an explicit maintenance
operation; see the [generator guide](../safere-unicode/README.md).

## External project validation

When swapping SafeRE into another project, fix each discovered SafeRE bug
before continuing validation. Follow [AGENTS.md](../AGENTS.md): add regression
coverage before investigating engine internals, fix the behavioral class,
and run relevant checks. Reinstall with `mvn install -DskipTests`, rerun the
host project's failing tests, and commit the fix before resuming validation.

Resolve specification contradictions or linear-time compatibility boundaries
under the compatibility policy before proceeding. Record the host revision,
adapter changes, exercised operations, and unresolved differences with the
validation evidence. A local integration does not imply upstream adoption.
