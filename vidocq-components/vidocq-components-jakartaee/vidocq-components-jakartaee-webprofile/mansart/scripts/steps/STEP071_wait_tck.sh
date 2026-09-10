#!/usr/bin/env bash
# STEP 071 — wait (briefly) for a detached STEP070 and report where it is.
# exit 0 = M0 complete · 1 = finished, not complete · 3 = still RUNNING (call again)
# Bounded to ~90 s per call so it fits under any tool timeout; the lead loops.
set -uo pipefail
H="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CODE="${1:-}"; printf '%s' "$CODE" | grep -qE '^[A-Z]{3}$' || { echo "usage: STEP071_wait_tck.sh <XXX>"; exit 2; }
LOG="$H/target/wire-logs/STEP070.log"; LOCK="$H/target/wire-logs/STEP070.lock"
[ -f "$LOG" ] || { echo "no STEP070 run found — start it: ./scripts/steps/STEP070_wire_tck.sh $CODE"; exit 2; }
alive() { [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; }
for _ in $(seq 1 18); do alive || break; sleep 5; done
echo "== STEP070 progress ($(date +%H:%M:%S))"
grep -E '^\s+\[[0-9:]+\]|M0-T|SCOPE|M0:|== (verify|M0)' "$LOG" | tail -14 | sed 's/^/   /'
if alive; then echo; echo "RUNNING — call ./scripts/steps/STEP071_wait_tck.sh $CODE again. Do not verify or build meanwhile."; exit 3; fi
if grep -q '== M0 complete' "$LOG"; then echo; echo "M0 COMPLETE"; exit 0; fi
echo; echo "FINISHED, NOT COMPLETE — last verify output above; logs in target/wire-logs/"; exit 1
