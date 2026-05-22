#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")" && pwd)"
cd "$repo_root"

echo "==> M9.2 smoke: build minimal modules"
./mvnw -ntp -pl dirac-api,dirac-core -am install -DskipTests >/dev/null

api_jar="$repo_root/dirac-api/target/dirac-api-0.1.0-SNAPSHOT.jar"
core_jar="$repo_root/dirac-core/target/dirac-core-0.1.0-SNAPSHOT.jar"
mp_api_src="$HOME/.m2/repository/org/eclipse/microprofile/metrics/microprofile-metrics-api/5.1.1/microprofile-metrics-api-5.1.1.jar"

if [[ ! -f "$api_jar" || ! -f "$core_jar" || ! -f "$mp_api_src" ]]; then
  echo "[ERROR] Missing required jars for M9.2 smoke check."
  exit 1
fi

work_dir="$repo_root/target/m92-jlink"
rm -rf "$work_dir"
mkdir -p "$work_dir/gen" "$work_dir/out"

mod_mp_api_jar="$work_dir/microprofile-metrics-api-5.1.1-mod.jar"
cp "$mp_api_src" "$mod_mp_api_jar"

echo "==> M9.2 smoke: generate explicit module-info for MP Metrics API"
jdeps --ignore-missing-deps --generate-module-info "$work_dir/gen" "$mod_mp_api_jar" >/dev/null

module_info_src="$work_dir/gen/microprofile.metrics.api/module-info.java"
if [[ ! -f "$module_info_src" ]]; then
  echo "[ERROR] jdeps did not generate module-info.java"
  exit 1
fi

javac \
  --patch-module microprofile.metrics.api="$mod_mp_api_jar" \
  -d "$work_dir/out" \
  "$module_info_src"

jar --update --file "$mod_mp_api_jar" -C "$work_dir/out" module-info.class

module_path="$api_jar:$core_jar:$mod_mp_api_jar"

echo "==> M9.2 smoke: validate modules"
java --module-path "$module_path" --validate-modules

echo "==> M9.2 smoke: build jlink image"
jlink \
  --module-path "$JAVA_HOME/jmods:$module_path" \
  --add-modules io.vidocq.dirac.core \
  --output "$work_dir/image"

echo "==> M9.2 smoke: image modules"
"$work_dir/image/bin/java" --list-modules | head -20

echo "[OK] M9.2 jlink smoke passed with local modularized MP Metrics API jar"

