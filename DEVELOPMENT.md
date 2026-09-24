# Developer Guide

This guide covers contributor workflows and implementation conventions that are too detailed for
the user-facing README. For matching-engine architecture, see [DESIGN.md](DESIGN.md). For testing
strategy and test-suite organization, see [TESTING.md](TESTING.md).

## Building and Testing

SafeRE requires Java 21 or later. Production artifacts are built with JDK 26 while the baseline
sources are compiled with `--release 21`.

Run the main library tests:

```bash
mvn -pl safere test -q
```

Run formatting checks:

```bash
mvn spotless:check
```

Build the release artifacts without publishing them:

```bash
mvn -pl safere package -Prelease -DskipTests -q
```

## JDK-Specific Implementations

SafeRE is designed to be distributed as a multi-release JAR (MR-JAR) when necessary for post-JDK-21 APIs, while compiling baseline sources with `--release 21`.

The source and binary layouts follow standard MR-JAR conventions:

```text
safere/src/main/java/                         safere-<version>.jar
  module-info.java                              module-info.class
  org/safere/...                                org/safere/...

safere/src/main/java-jdk<version>/            META-INF/versions/<version>/
  org/safere/...                                org/safere/...
```

The JDK 21 sources compile into the root of the JAR. When versioned directories are introduced (e.g., when the Vector API graduates from `jdk.incubator.vector` to a preview feature in `java.base`), the versioned profile compiles `src/main/java-jdk<version>` with `multiReleaseOutput`, which writes its classes beneath `META-INF/versions/<version>`. The manifest marks the artifact with `Multi-Release: true`.

At runtime, baseline JDKs ignore the versioned directory, while newer JDKs use eligible entries from `META-INF/versions/<version>` automatically.

### Public API boundary

Version-specific classes are implementation details. Keep them package-private unless a separate
public API has been intentionally designed. In particular:

- do not expose post-JDK-21 types from the baseline API;
- do not export `org.safere.internal` from `module-info.java`;
- do not make an internal kernel public merely to call it from a separate module or test;
- keep interfaces shared between baseline and versioned code expressible using JDK 21 types.

### Connecting a versioned implementation

Prefer MR-JAR class selection over reflection. Define a baseline interface using only JDK 21
types, then provide a factory class in both the baseline and versioned source trees. Both factory
classes must have the same fully qualified name and binary-compatible methods.

For example, a baseline interface might be:

```java
package org.safere;

interface StringScanner {
  int indexOfCharClass(String input, int[] ranges, int start);
}
```

The JDK 21 source tree would contain the baseline factory:

```java
package org.safere;

final class StringScannerFactory {
  static StringScanner create() {
    return new BaselineStringScanner();
  }

  private StringScannerFactory() {}
}
```

The JDK 22 source tree would contain a replacement with the same class name and method descriptor:

```java
package org.safere;

final class StringScannerFactory {
  static StringScanner create() {
    return new ForeignMemoryStringScanner();
  }

  private StringScannerFactory() {}
}
```

After packaging, these implementations occupy:

```text
org/safere/StringScannerFactory.class
META-INF/versions/22/org/safere/StringScannerFactory.class
```

Code in `Matcher` calls `StringScannerFactory.create()` normally. JDK 21 loads the root factory;
JDK 22 and later load the versioned factory. The compiler checks both implementations, and the JVM
performs selection without `Class.forName`, reflective invocation, or an explicit version branch.

This example describes the intended integration pattern; these factory classes do not exist yet.

### Optional modules and the Vector API

MR-JAR selection answers which JDK implementation is eligible. It does not resolve optional
modules or grant module readability.

Do not eagerly initialize or load a versioned class that links to `jdk.incubator.vector`. Keep a
non-Vector path available and load the Vector implementation only after the existing experimental
Vector provider has been enabled. The intended selection shape is:

```text
versioned StringScannerFactory
  ├── default: MemorySegment SWAR implementation
  └── Vector enabled: MemorySegment Vector implementation
```

Ordinary SafeRE use requires no additional flags. The experimental Vector provider continues to
use the flags documented in README.md:

```text
--add-modules=jdk.incubator.vector
-Dorg.safere.experimental.vectorScanProvider=vector
```

Build and test executions may additionally use `--add-reads org.safere=jdk.incubator.vector` as
internal module-path plumbing. Do not introduce it as a user-facing requirement without first
designing and documenting the complete activation path.

### Testing the packaged artifact

Tests for version-specific code belong in the corresponding test source tree:

```text
safere/src/test/java-jdk22/
```

The `compile-jdk22-foreign-memory-tests` execution compiles those tests separately with
`--release 22`. This separation is required: placing their bytecode in the ordinary test output
would prevent the prebuilt test artifact from running on JDK 21.

Version-specific tests must run against a packaged MR-JAR, not only `target/classes`. Testing the
archive catches missing manifests, misplaced versioned entries, and incorrect runtime selection.
CI runs the baseline packaged artifact on JDK 21 through 26 and the JDK 22 tests on JDK 22 through
26.

Inspect a local artifact with:

```bash
jar --list --file safere/target/safere-<version>.jar \
  | grep '^META-INF/versions/'
jar --describe-module --file safere/target/safere-<version>.jar
jar --validate --file safere/target/safere-<version>.jar
```

Confirm that the module descriptor exports only intended public packages and that the binary JAR
does not contain `.java` files. With `-Prelease`, also confirm that the source JAR contains the
versioned sources under `META-INF/versions/22`.

## External Project Validation

When swapping SafeRE into external projects for validation (#26), fix each bug
before continuing with the rest of the validation. Follow the bug-fixing and
compatibility policy in [AGENTS.md](AGENTS.md): add regression coverage before
investigating engine internals, root-cause and fix the bug, and run the SafeRE
suite. Reinstall with `mvn install -DskipTests -q`, rerun the external project's
failing tests to confirm the fix, and commit it before resuming validation.
For a JDK/specification contradiction or a linear-time compatibility boundary,
resolve the compatibility decision under that policy before proceeding.
