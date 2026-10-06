#!/bin/bash
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
# Build adapter invoked by projects/safere/build.sh in OSS-Fuzz.
set -euo pipefail
: "${OUT:?OSS-Fuzz output directory is required}"
: "${JAVA_HOME:?JDK 26 installation is required}"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

# Validate enrollment before building or publishing launchers.
python3 safere-fuzz/scripts/targets.py --suite oss-fuzz --format tsv
mkdir -p "$OUT/openjdk"
rsync -aL --exclude='*.zip' --exclude=jmods --exclude=man "$JAVA_HOME/" "$OUT/openjdk/"
mvn --batch-mode install -DskipTests -pl safere,safere-fuzz -am
python3 - "$OUT" <<'PYJAR'
from pathlib import Path
import shutil, sys, xml.etree.ElementTree as ET
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
version = ET.parse('pom.xml').find('m:version', ns).text
shutil.copyfile(Path('safere/target') / ('safere-' + version + '.jar'),
                Path(sys.argv[1]) / 'safere.jar')
PYJAR
jar -cf "$OUT/safere-fuzz.jar" -C safere-fuzz/target/test-classes .
cp "${JAZZER_JUNIT_PATH:-/usr/local/bin/jazzer_junit.jar}" "$OUT/jazzer_junit.jar"
mvn --batch-mode -pl safere-fuzz dependency:copy-dependencies \
  -DoutputDirectory="$OUT" -DincludeScope=test \
  -DexcludeGroupIds=com.code-intelligence -DexcludeArtifactIds=safere
python3 safere-fuzz/scripts/export-oss-fuzz.py "$OUT"
