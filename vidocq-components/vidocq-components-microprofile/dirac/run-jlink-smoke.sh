#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")" && pwd)"
cd "$repo_root"

echo "==> M9 smoke: install minimal modules (dirac-mp-metrics-api, dirac-api, dirac-core)"
./mvnw -ntp -pl dirac-mp-metrics-api,dirac-api,dirac-core -am install -DskipTests >/dev/null

mp_api_jar="$repo_root/dirac-mp-metrics-api/target/dirac-mp-metrics-api-0.1.0-SNAPSHOT.jar"
api_jar="$repo_root/dirac-api/target/dirac-api-0.1.0-SNAPSHOT.jar"
core_jar="$repo_root/dirac-core/target/dirac-core-0.1.0-SNAPSHOT.jar"

if [[ ! -f "$mp_api_jar" || ! -f "$api_jar" || ! -f "$core_jar" ]]; then
  echo "[ERROR] Missing required jars for smoke check."
  exit 1
fi

module_path="$mp_api_jar:$api_jar:$core_jar"

echo "==> M9 smoke: JPMS resolution check"
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
