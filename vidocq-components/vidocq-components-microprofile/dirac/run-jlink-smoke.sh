#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")" && pwd)"
cd "$repo_root"

# Every Maven call reads and writes a local repository private to this checkout, above the shared
# ~/.m2/repository it only reads: an install from a branch never shadows the published snapshot every
# other build of the machine resolves (BUG-20261123-57). TCK_LOCAL_REPOSITORY moves it.
local_repository="${TCK_LOCAL_REPOSITORY:-$repo_root/.tck-repository}"
repository_args=("-Dmaven.repo.local=$local_repository" "-Dmaven.repo.local.tail=$HOME/.m2/repository")

echo "==> M9 smoke: install minimal modules (dirac-mp-metrics-api, dirac-api, dirac-core)"
./mvnw -ntp "${repository_args[@]}" -pl dirac-mp-metrics-api,dirac-api,dirac-core -am clean install -DskipTests >/dev/null

# Globs — never hardcode the reactor version (issue #3 follow-up).
mp_api_jar="$(ls "$repo_root"/dirac-mp-metrics-api/target/dirac-mp-metrics-api-*.jar 2>/dev/null | grep -vE '(sources|javadoc)' | head -n 1)"
api_jar="$(ls "$repo_root"/dirac-api/target/dirac-api-*.jar 2>/dev/null | grep -vE '(sources|javadoc)' | head -n 1)"
core_jar="$(ls "$repo_root"/dirac-core/target/dirac-core-*.jar 2>/dev/null | grep -vE '(sources|javadoc)' | head -n 1)"

if [[ ! -f "$mp_api_jar" || ! -f "$api_jar" || ! -f "$core_jar" ]]; then
  echo "[ERROR] Missing required jars for smoke check."
  exit 1
fi

module_path="$mp_api_jar:$api_jar:$core_jar"

echo "==> M9 smoke: Java Modules resolution check"
java --module-path "$module_path" --validate-modules

output_dir="$repo_root/target/dirac-image-smoke"
rm -rf "$output_dir"

echo "==> M9 smoke: jlink attempt"
jlink \
  --module-path "$JAVA_HOME/jmods:$module_path" \
  --add-modules io.vidocq.dirac.core \
  --output "$output_dir" \
  >"$repo_root/target/jlink-smoke.out" 2>&1

echo "[OK] jlink image generated at $output_dir"
"$output_dir/bin/java" --list-modules | head -20

echo "[OK] M9 jlink smoke passed with artifact: $mp_api_jar"
