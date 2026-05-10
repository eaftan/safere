#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

threads=1
output_dir="target/exhaustive-reports/character-class-sweep"
heap_size="512m"
java_args=()
for arg in "$@"; do
  case "$arg" in
    --threads=*)
      threads="${arg#--threads=}"
      ;;
    --output-dir=*)
      output_dir="${arg#--output-dir=}"
      ;;
    --heap=*)
      heap_size="${arg#--heap=}"
      ;;
    *)
      java_args+=("$arg")
      ;;
  esac
done

if ! [[ "$threads" =~ ^[0-9]+$ ]] || [[ "$threads" -lt 1 ]]; then
  echo "--threads must be a positive integer" >&2
  exit 2
fi

mvn -pl safere-exhaustive -am -DskipTests package -q
classpath="safere-exhaustive/target/classes:safere/target/classes"
main_class="org.safere.exhaustive.CharacterClassDivergenceSweep"

java "-Xmx$heap_size" \
  -cp "$classpath" "$main_class" \
  "${java_args[@]}" \
  "--threads=$threads" \
  "--output-dir=$output_dir"
