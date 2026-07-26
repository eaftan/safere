#!/bin/bash
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.
#
# Run the separately licensed OpenJDK-derived regex benchmark suite against the
# current SafeRE checkout. The external suite is intentionally not vendored or
# included as a module of this repository.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEFAULT_EXTERNAL_REPO="$SCRIPT_DIR/../safere-openjdk-regex-benchmarks"
EXTERNAL_REPO="${SAFERE_OPENJDK_REGEX_BENCHMARKS_REPO:-$DEFAULT_EXTERNAL_REPO}"
MODE="standard"
BENCHMARKS=()
JMH_ARGS=()

usage() {
  cat <<EOF
Usage:
  ./run-openjdk-regex-benchmarks.sh [--repo PATH] [--smoke] [JmhBenchmarkRegex ...] [-- JmhArg ...]

Runs the GPL-2.0-only OpenJDK-derived benchmark suite from a separate checkout
against the SafeRE version in this checkout.

Options:
  --repo PATH  External benchmark checkout. Defaults to:
               $DEFAULT_EXTERNAL_REPO
  --smoke      Override the upstream JMH schedules with a minimal validation run.

The SAFERE_OPENJDK_REGEX_BENCHMARKS_REPO environment variable provides another
way to set the external checkout path.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)
      if [[ $# -lt 2 ]]; then
        echo "ERROR: --repo requires a path" >&2
        exit 2
      fi
      EXTERNAL_REPO="$2"
      shift 2
      ;;
    --smoke)
      MODE="smoke"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      JMH_ARGS=("$@")
      set --
      ;;
    --*)
      echo "ERROR: unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
    *)
      BENCHMARKS+=("$1")
      shift
      ;;
  esac
done

if [[ ! -d "$EXTERNAL_REPO" ]]; then
  cat >&2 <<EOF
ERROR: external benchmark checkout not found: $EXTERNAL_REPO

Clone https://github.com/eaftan/safere-openjdk-regex-benchmarks outside this
repository, or select an existing checkout with --repo PATH.
EOF
  exit 1
fi

EXTERNAL_REPO="$(cd "$EXTERNAL_REPO" && pwd)"
EXTERNAL_POM="$EXTERNAL_REPO/pom.xml"
EXTERNAL_JAR="$EXTERNAL_REPO/target/benchmarks.jar"

if [[ ! -f "$EXTERNAL_POM" || ! -f "$EXTERNAL_REPO/LICENSE" ]]; then
  echo "ERROR: not a safere-openjdk-regex-benchmarks checkout: $EXTERNAL_REPO" >&2
  exit 1
fi

if [[ ${#BENCHMARKS[@]} -eq 0 ]]; then
  BENCHMARKS=('org.safere.bench.openjdk.*')
fi

SAFERE_VERSION="$(
  mvn help:evaluate \
    -Dexpression=project.version \
    -q \
    -DforceStdout \
    -f "$SCRIPT_DIR/pom.xml"
)"
SAFERE_COMMIT="$(git -C "$SCRIPT_DIR" rev-parse HEAD)"
EXTERNAL_COMMIT="$(git -C "$EXTERNAL_REPO" rev-parse HEAD)"

echo "=== SafeRE OpenJDK regex benchmarks ==="
echo "SafeRE checkout: $SCRIPT_DIR"
echo "SafeRE commit: $SAFERE_COMMIT"
echo "SafeRE version: $SAFERE_VERSION"
echo "External checkout: $EXTERNAL_REPO"
echo "External commit: $EXTERNAL_COMMIT"
echo "Mode: $MODE"

if [[ -n "$(git -C "$SCRIPT_DIR" status --short)" ]]; then
  echo "WARNING: SafeRE checkout has uncommitted changes" >&2
fi
if [[ -n "$(git -C "$EXTERNAL_REPO" status --short)" ]]; then
  echo "WARNING: external benchmark checkout has uncommitted changes" >&2
fi

echo "=== Installing current SafeRE artifact ==="
mvn install \
  -pl safere \
  -am \
  -DskipTests \
  -Dpmd.skip=true \
  -Dcheckstyle.skip=true \
  -Dspotless.check.skip=true \
  -Dmaven.javadoc.skip=true \
  -Dexec.skip=true \
  -q \
  -f "$SCRIPT_DIR/pom.xml"

echo "=== Building external benchmark JAR ==="
mvn package \
  --quiet \
  --batch-mode \
  --no-transfer-progress \
  -Dsafere.version="$SAFERE_VERSION" \
  -f "$EXTERNAL_POM"

JMH_OPTS=()
if [[ "$MODE" = "smoke" ]]; then
  JMH_OPTS=(-f 1 -wi 1 -i 1 -w 100ms -r 100ms)
fi

echo "=== Running external benchmark suite ==="
java \
  -jar "$EXTERNAL_JAR" \
  "${JMH_OPTS[@]}" \
  "${JMH_ARGS[@]}" \
  "${BENCHMARKS[@]}"
