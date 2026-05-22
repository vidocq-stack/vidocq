#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")" && pwd)"
cd "$repo_root"

echo "==> M9 smoke: install minimal modules (dirac-api, dirac-core)"
./mvnw -ntp -pl dirac-api,dirac-core -am install -DskipTests >/dev/null

api_jar="$repo_root/dirac-api/target/dirac-api-0.1.0-SNAPSHOT.jar"
core_jar="$repo_root/dirac-core/target/dirac-core-0.1.0-SNAPSHOT.jar"
mp_api_jar="$HOME/.m2/repository/org/eclipse/microprofile/metrics/microprofile-metrics-api/5.1.1/microprofile-metrics-api-5.1.1.jar"

if [[ ! -f "$api_jar" || ! -f "$core_jar" || ! -f "$mp_api_jar" ]]; then
  echo "[ERROR] Missing required jars for smoke check."
  exit 1
fi

module_path="$api_jar:$core_jar:$mp_api_jar"

echo "==> M9 smoke: JPMS resolution check"
java --module-path "$module_path" --validate-modules

output_dir="$repo_root/target/dirac-image-smoke"
rm -rf "$output_dir"

echo "==> M9 smoke: jlink attempt (expected to fail until MP Metrics is explicit module)"
set +e
jlink \
  --module-path "$JAVA_HOME/jmods:$module_path" \
  --add-modules io.vidocq.dirac.core \
  --output "$output_dir" \
  >"$repo_root/target/jlink-smoke.out" 2>&1
rc=$?
set -e

if [[ $rc -eq 0 ]]; then
  echo "[OK] jlink image generated at $output_dir"
  "$output_dir/bin/java" --list-modules | head -20
  exit 0
fi

if grep -q "automatic module cannot be used with jlink" "$repo_root/target/jlink-smoke.out"; then
  echo "[KNOWN BLOCKER] jlink failed due to automatic module: microprofile.metrics.api"
  echo "See $repo_root/target/jlink-smoke.out and JLINK.md"
  exit 2
fi

echo "[ERROR] jlink failed unexpectedly. See $repo_root/target/jlink-smoke.out"
exit $rc

