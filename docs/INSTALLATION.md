# Installation

SafeRE requires Java 21 or later. The [README](../README.md#installation)
shows the Maven and Gradle coordinates for a numbered release. See
[GitHub releases](https://github.com/eaftan/safere/releases) for release notes.

## Development snapshots

Successful pushes to `main` publish the current development version to the
[Central Portal snapshot repository](https://central.sonatype.com/repository/maven-snapshots/)
after CI passes. Pull requests do not publish snapshots.
Snapshots are for testing: their contents may change and repository retention policies
may remove them. Use a numbered release for stable dependencies.

Read the development version from the root [pom.xml](../pom.xml). Replace
`X.Y.Z-SNAPSHOT` below with that version, then add the repository and dependency
to your Maven POM:

```xml
<repositories>
  <repository>
    <id>central-portal-snapshots</id>
    <url>https://central.sonatype.com/repository/maven-snapshots/</url>
    <releases><enabled>false</enabled></releases>
    <snapshots><enabled>true</enabled></snapshots>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>org.safere</groupId>
    <artifactId>safere</artifactId>
    <version>X.Y.Z-SNAPSHOT</version>
  </dependency>
</dependencies>
```

Run Maven with `-U` to refresh a cached snapshot. The development version follows
the version in [pom.xml](../pom.xml) and changes as new release cycles begin.

## Building from source

Building requires JDK 26 and Maven 3.9 or newer. See the
[developer guide](DEVELOPMENT.md) for commands and optional modules.
