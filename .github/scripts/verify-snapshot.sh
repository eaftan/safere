#!/usr/bin/env bash
# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).
set -euo pipefail

VERSION=${1:?Usage: verify-snapshot.sh VERSION-SNAPSHOT}
if [[ ! "$VERSION" =~ ^[A-Za-z0-9_.-]+-SNAPSHOT$ ]]; then
  echo "Expected a -SNAPSHOT version, got: $VERSION" >&2
  exit 1
fi

# Keep the consumer and its Maven repository separate from the publishing build.
CONSUMER_DIR=$(mktemp -d)
trap 'rm -rf "$CONSUMER_DIR"' EXIT
cat > "$CONSUMER_DIR/pom.xml" <<EOF
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>snapshot.check</groupId>
  <artifactId>consumer</artifactId>
  <version>1.0</version>
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
      <version>$VERSION</version>
    </dependency>
  </dependencies>
</project>
EOF

mvn -f "$CONSUMER_DIR/pom.xml" \
  -Dmaven.repo.local="$CONSUMER_DIR/repository" \
  org.apache.maven.plugins:maven-dependency-plugin:3.10.0:build-classpath \
  -Dmdep.outputFile="$CONSUMER_DIR/classpath" \
  --batch-mode --no-transfer-progress

cat > "$CONSUMER_DIR/SnapshotCheck.java" <<'EOF'
// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
import org.safere.Pattern;

class SnapshotCheck {
  public static void main(String[] args) {
    if (!Pattern.compile("a+").matcher("aaa").matches()) {
      throw new AssertionError("Snapshot matching failed");
    }
  }
}
EOF
java --class-path "$(cat "$CONSUMER_DIR/classpath")" "$CONSUMER_DIR/SnapshotCheck.java"
