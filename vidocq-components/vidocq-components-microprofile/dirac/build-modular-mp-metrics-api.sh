#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")" && pwd)"
mp_api_src="$HOME/.m2/repository/org/eclipse/microprofile/metrics/microprofile-metrics-api/5.1.1/microprofile-metrics-api-5.1.1.jar"

if [[ ! -f "$mp_api_src" ]]; then
  echo "[ERROR] Missing source artifact: $mp_api_src" >&2
  exit 1
fi

work_dir="$repo_root/target/modular-mp-metrics-api"
rm -rf "$work_dir"
mkdir -p "$work_dir/gen" "$work_dir/out"

mod_mp_api_jar="$work_dir/microprofile-metrics-api-5.1.1-mod.jar"
cp "$mp_api_src" "$mod_mp_api_jar"

jdeps --ignore-missing-deps --generate-module-info "$work_dir/gen" "$mod_mp_api_jar" >/dev/null

module_info_src="$work_dir/gen/microprofile.metrics.api/module-info.java"
if [[ ! -f "$module_info_src" ]]; then
  echo "[ERROR] jdeps did not generate module-info.java" >&2
  exit 1
fi

javac \
  --patch-module microprofile.metrics.api="$mod_mp_api_jar" \
  -d "$work_dir/out" \
  "$module_info_src"

jar --update --file "$mod_mp_api_jar" -C "$work_dir/out" module-info.class

# Print only the artifact path on stdout for script composition.
echo "$mod_mp_api_jar"

