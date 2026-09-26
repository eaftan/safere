# Developer guide

Read [CONTRIBUTING.md](../CONTRIBUTING.md) for contribution requirements and
[AGENTS.md](../AGENTS.md) for project conventions. The
[architecture guide](ARCHITECTURE.md) explains the library; the
[testing guide](TESTING.md) describes validation. Run commands from the repository root.

## Building and testing

Source builds require JDK 26 and Maven 3.9 or newer. The production JDK is
recorded in [.sdkmanrc](../.sdkmanrc). The library's baseline is Java 21;
CI tests compiled artifacts on JDK 21 through 26.

```bash
# Build and install the core library and its reactor dependencies.
mvn -pl safere -am install -DskipTests

# Run focused library tests.
mvn -pl safere -Dtest=MatcherTest test

# Build and install the full reactor.
mvn install

# Generate library Javadoc.
mvn -pl safere javadoc:javadoc
```

Run Maven builds sequentially in one checkout so compilation does not replace
class files in use by another run. Optional native benchmark engines have
additional prerequisites; see [benchmark runners](../safere-benchmarks/README.md).

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
