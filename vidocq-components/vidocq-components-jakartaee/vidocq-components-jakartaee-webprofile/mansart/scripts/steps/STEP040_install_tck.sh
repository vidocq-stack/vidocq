#!/usr/bin/env bash
# STEP 040 — obtain the official TCK and install it into the local M2.
# in:  <XXX>           out: jars in ~/.m2, spec-meta.json refreshed
# Nothing is hardcoded: the spec page links its own archive, the published
# .sha256 verifies it, each jar declares its own Maven coordinates.
# exit 5 = the page links no archive (an agent with WebFetch takes over)
# exit 7 = a JavaTest/TSharness TCK, run rather than depended upon
set -uo pipefail
H="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
[ $# -ge 1 ] || { echo "usage: $(basename "$0") <XXX> [--dry-run]"; exit 2; }
echo "== STEP040 install TCK: $1"
# Idempotent: a lead ran this step although STEP030 had just exited 0, and a
# failed re-install then read as "no Maven TCK". Already runnable = nothing to do.
if "$H/scripts/steps/STEP030_detect_tck.sh" "$1" >/dev/null 2>&1; then
    echo "   already installed and runnable — nothing to do (STEP030 exits 0)"
    echo "next: STEP050_module_path.sh $1"
    exit 0
fi
python3 "$H/scripts/tck-install.py" "$@"; rc=$?
if [ $rc -eq 0 ]; then
    python3 "$H/scripts/spec-fetch.py" "$1" --refresh-tck
    echo "next: STEP050_module_path.sh $1"
fi
exit $rc
