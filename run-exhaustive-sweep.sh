#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

if [[ "$#" -lt 1 ]]; then
  echo "usage: $0 SweepClassName [sweep args...]" >&2
  echo "example: $0 CharacterClassDivergenceSweep --range=:1000000" >&2
  exit 2
fi

sweep_class="$1"
shift
main_class="org.safere.exhaustive.$sweep_class"

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
mvn -pl safere-exhaustive exec:java -Dexec.mainClass="$main_class" -Dexec.args="$exec_args" -q
