#!/usr/bin/env bash
# STEP 050 — where does the TCK runner module go? Read the repo, never invent.
# in:  <XXX>           out: docs/spec-src/<XXX>/module.conf (a PROPOSAL to edit)
# An agent left to itself created ee/jakarta/tck/persistence/mansart-jkp-tck —
# the TCK's Java package path, with the harness's 3-letter code as a module name.
set -uo pipefail
H="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
[ $# -ge 1 ] || { echo "usage: $(basename "$0") <XXX> [--force]"; exit 2; }
echo "== STEP050 module path: $1"
python3 "$H/scripts/tck-module.py" "$@"; rc=$?
[ $rc -eq 0 ] && echo "   READ docs/spec-src/$1/module.conf AND FIX THE NAME IF IT IS WRONG" \
              && echo "next: STEP060_plan_tasks.sh $1"
exit $rc
