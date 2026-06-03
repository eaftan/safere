#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

quote_exec_arg() {
  local value="$1"
  value="${value//\'/\'\\\'\'}"
  printf "'%s'" "$value"
}

exec_args=""
for arg in "$@"; do
  if [[ -n "$exec_args" ]]; then
    exec_args+=" "
  fi
  exec_args+="$(quote_exec_arg "$arg")"
done

mvn -pl safere-exhaustive -am -DskipTests install -q
mvn -pl safere-exhaustive exec:java \
  -Dexec.mainClass=org.safere.exhaustive.CompactDivergenceAudit \
  -Dexec.args="$exec_args" \
  -q
