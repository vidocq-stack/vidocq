#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")" && pwd)"
cd "$repo_root"

./run-jlink-smoke.sh
echo "[OK] jlink smoke passed"

