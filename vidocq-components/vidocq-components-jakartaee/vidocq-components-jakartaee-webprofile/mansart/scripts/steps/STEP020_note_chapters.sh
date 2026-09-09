#!/usr/bin/env bash
# STEP 020 — one note per chapter, SEQUENTIALLY, watchdog per call.
# in:  <XXX>           out: docs/spec-notes/<XXX>/*.md
# Never parallel: the server allows 2 concurrent requests, and a 39-way fan-out
# once left a run "alive" for 51 minutes without writing a file. Resumable.
set -uo pipefail
H="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
[ $# -ge 1 ] || { echo "usage: $(basename "$0") <XXX> [--force]"; exit 2; }
echo "== STEP020 notes: $1  (~47 s per chapter, resumable)"
"$H/scripts/spec-note.sh" "$@"; rc=$?
[ $rc -eq 0 ] && echo "next: STEP030_detect_tck.sh $1"
exit $rc
