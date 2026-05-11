#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

physical_core_count() {
  local cores
  if command -v lscpu >/dev/null 2>&1; then
    cores="$(
      lscpu -p=Core,Socket 2>/dev/null \
        | awk -F, '!/^#/ && NF >= 2 { print $1 "," $2 }' \
        | sort -u \
        | wc -l \
        | tr -d '[:space:]'
    )"
    if [[ "$cores" =~ ^[0-9]+$ ]] && [[ "$cores" -gt 0 ]]; then
      echo "$cores"
      return
    fi
  fi

  if [[ "$(uname -s)" == "Darwin" ]] && command -v sysctl >/dev/null 2>&1; then
    cores="$(sysctl -n hw.physicalcpu 2>/dev/null || true)"
    if [[ "$cores" =~ ^[0-9]+$ ]] && [[ "$cores" -gt 0 ]]; then
      echo "$cores"
      return
    fi
  fi

  echo 1
}

threads="$(physical_core_count)"
output_dir="target/exhaustive-reports/control-escape-sweep"
java_args=()
for arg in "$@"; do
  case "$arg" in
    --threads=*)
      threads="${arg#--threads=}"
      ;;
    --output-dir=*)
      output_dir="${arg#--output-dir=}"
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
main_class="org.safere.exhaustive.ControlEscapeDivergenceSweep"

java -cp "$classpath" "$main_class" \
  "${java_args[@]}" \
  "--threads=$threads" \
  "--output-dir=$output_dir"
