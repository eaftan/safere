#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

if [[ "$#" -lt 1 ]]; then
  echo "usage: $0 {character-class|grapheme-cluster|control-escape} [sweep args...]" >&2
  exit 2
fi

sweep="$1"
shift

case "$sweep" in
  character-class)
    main_class="org.safere.exhaustive.CharacterClassDivergenceSweep"
    ;;
  grapheme-cluster)
    main_class="org.safere.exhaustive.GraphemeClusterDivergenceSweep"
    ;;
  control-escape)
    main_class="org.safere.exhaustive.ControlEscapeDivergenceSweep"
    ;;
  *)
    echo "unknown exhaustive sweep: $sweep" >&2
    echo "usage: $0 {character-class|grapheme-cluster|control-escape} [sweep args...]" >&2
    exit 2
    ;;
esac

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

mvn -pl safere-exhaustive -am -DskipTests compile -q
mvn -pl safere-exhaustive exec:java -Dexec.mainClass="$main_class" -Dexec.args="$exec_args" -q
