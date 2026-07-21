# Releasing SafeRE

This document describes how to publish a new release of SafeRE to Maven Central
and create its GitHub release notes.

## Prerequisites

The following GitHub secrets must be configured in the repository:

| Secret | Description |
|---|---|
| `GPG_PRIVATE_KEY` | Armored GPG private key for signing artifacts |
| `GPG_PASSPHRASE` | Passphrase for the GPG key |
| `MAVEN_CENTRAL_USERNAME` | Maven Central Portal token username |
| `MAVEN_CENTRAL_PASSWORD` | Maven Central Portal token password |

## Release Process

### 1. Prepare `main`

Start from a clean, up-to-date `main` branch. The committed POM versions should
remain development versions, such as `X.Y.Z-SNAPSHOT`; the release workflow sets
the exact release version from the tag.

```bash
git switch main
git pull --ff-only
```

Update release-facing documentation if needed, such as the version shown in the
README installation snippets.

### 2. Prepare the release notes

Collect the complete commit messages since the previous release:

```bash
PREVIOUS_RELEASE_TAG=$(git describe --tags --abbrev=0)
git log --reverse --format='commit %H%n%n%B%n---' \
  "${PREVIOUS_RELEASE_TAG}..HEAD" > /tmp/safere-release-commits.txt
```

Review every commit in `/tmp/safere-release-commits.txt` and summarize the
user-facing changes in `/tmp/safere-vX.Y.Z-release-notes.md`. Use the following
structure, omitting empty sections:

```markdown
## Highlights

A short description of the most important release themes.

## New features

- User-facing APIs and newly supported behavior.

## Compatibility and correctness

- JDK compatibility improvements and important bug fixes.

## Performance

- Meaningful optimizations, with measured results when available.

## Other changes

- Other changes users should know about.

**Full changelog:** https://github.com/eaftan/safere/compare/PREVIOUS_TAG...vX.Y.Z
```

The commit messages are the complete input inventory, but the release notes
should be a concise, curated summary rather than a copy of the commit log. Put
breaking changes and migration requirements first. Follow links to pull
requests or issues when a commit message does not provide enough context to
describe its user-visible effect accurately.

Local verification is not repeated here because every change merged into
`main` has already passed CI. The release workflow verifies the tagged release
build before publishing it.

### 3. Tag the release

```bash
git tag -a vX.Y.Z -m "Release vX.Y.Z"
```

### 4. Push the tag

```bash
git push origin vX.Y.Z
```

Pushing the tag triggers the
[release workflow](.github/workflows/release.yml), which:

1. Sets the POM version from the tag
2. Builds and verifies the `safere` module with `mvn -pl safere verify`
3. Signs the `safere` release artifacts with GPG
4. Publishes `org.safere:safere` to Maven Central via the Central Portal

### 5. Verify the release

- Check the [GitHub Actions run](https://github.com/eaftan/safere/actions/workflows/release.yml)
  for success.
- After a few minutes, verify the artifact appears on
  [Maven Central](https://central.sonatype.com/artifact/org.safere/safere).

### 6. Publish the GitHub release notes

After the release workflow succeeds and the artifact is available on Maven
Central, create a GitHub Release from the tag using the prepared notes:

```bash
gh release create vX.Y.Z \
  --verify-tag \
  --title "SafeRE X.Y.Z" \
  --notes-file /tmp/safere-vX.Y.Z-release-notes.md
```

### 7. Bump to next SNAPSHOT

After a successful release, bump `main` to the next development version:

```bash
mvn versions:set -DnewVersion=NEXT-SNAPSHOT -DgenerateBackupPoms=false
git add -A
git commit -m "Bump version to NEXT-SNAPSHOT"
git push origin main
```

For example, after releasing `v0.3.0`, bump to `0.4.0-SNAPSHOT`.

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
