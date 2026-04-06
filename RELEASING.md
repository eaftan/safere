# Releasing SafeRE

This document describes how to publish a new release of SafeRE to Maven Central.

## Prerequisites

The following GitHub secrets must be configured in the repository:

| Secret | Description |
|---|---|
| `GPG_PRIVATE_KEY` | Armored GPG private key for signing artifacts |
| `GPG_PASSPHRASE` | Passphrase for the GPG key |
| `MAVEN_CENTRAL_USERNAME` | Maven Central Portal token username |
| `MAVEN_CENTRAL_PASSWORD` | Maven Central Portal token password |

## Release Process

### 1. Update the version

Remove the `-SNAPSHOT` suffix from the version in all POM files:

```bash
mvn versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false
```

### 2. Commit the version change

```bash
git add -A
git commit -m "Release vX.Y.Z"
```

### 3. Tag the release

```bash
git tag vX.Y.Z
```

### 4. Push the commit and tag

```bash
git push origin main
git push origin vX.Y.Z
```

Pushing the tag triggers the
[release workflow](.github/workflows/release.yml), which:

1. Sets the POM version from the tag
2. Builds and runs all tests
3. Signs all artifacts with GPG
4. Publishes to Maven Central via the Central Portal

### 5. Verify the release

- Check the [GitHub Actions run](https://github.com/eaftan/safere/actions/workflows/release.yml)
  for success.
- After a few minutes, verify the artifact appears on
  [Maven Central](https://central.sonatype.com/artifact/org.safere/safere).

### 6. Bump to next SNAPSHOT

```bash
mvn versions:set -DnewVersion=X.Y+1.0-SNAPSHOT -DgenerateBackupPoms=false
git add -A
git commit -m "Bump version to X.Y+1.0-SNAPSHOT"
git push origin main
```

## Troubleshooting

- **GPG signing fails**: Ensure the GPG private key secret is the full armored
  output of `gpg --armor --export-secret-keys`, including the `BEGIN` and `END`
  lines.
- **Central Portal rejects the upload**: Verify that the `org.safere` namespace
  is verified at [central.sonatype.com](https://central.sonatype.com) and that
  all POM metadata (`<url>`, `<licenses>`, `<scm>`, `<developers>`) is present.
- **Token expired**: Generate a new token at
  [central.sonatype.com/usertoken](https://central.sonatype.com/usertoken) and
  update the GitHub secrets.
