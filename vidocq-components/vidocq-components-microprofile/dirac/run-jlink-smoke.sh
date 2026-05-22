#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")" && pwd)"
cd "$repo_root"

echo "==> M9 smoke: install minimal modules (dirac-api, dirac-core)"
./mvnw -ntp -pl dirac-api,dirac-core -am install -DskipTests >/dev/null

echo "==> M9 smoke: build modular MP Metrics API artifact"
mod_mp_api_jar="$(./build-modular-mp-metrics-api.sh)"

api_jar="$repo_root/dirac-api/target/dirac-api-0.1.0-SNAPSHOT.jar"
core_jar="$repo_root/dirac-core/target/dirac-core-0.1.0-SNAPSHOT.jar"

if [[ ! -f "$api_jar" || ! -f "$core_jar" || ! -f "$mod_mp_api_jar" ]]; then
  echo "[ERROR] Missing required jars for smoke check."
  exit 1
fi

module_path="$api_jar:$core_jar:$mod_mp_api_jar"

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

if ! "$output_dir/bin/java" --list-modules | grep -q '^microprofile.metrics.api'; then
  echo "[ERROR] Expected microprofile.metrics.api in image module list"
  exit 1
fi

echo "[OK] M9 jlink smoke passed with modular MP Metrics API artifact: $mod_mp_api_jar"

