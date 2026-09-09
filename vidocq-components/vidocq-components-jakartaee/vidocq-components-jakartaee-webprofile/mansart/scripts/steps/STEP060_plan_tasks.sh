#!/usr/bin/env bash
# STEP 060 — turn the notes into TASKS-<XXX>.md and STATUS-<XXX>.md.
# in:  <XXX>           out: TASKS-<XXX>.md, STATUS-<XXX>.md
# One @planner per milestone group; the SCRIPT owns milestone order, card ids,
# M0 and STATUS. Needs docs/spec-src/<XXX>/milestones.tsv — the one judgement a
# script cannot make. Safe to re-run: fragments are cached, progress preserved.
set -uo pipefail
H="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
[ $# -ge 1 ] || { echo "usage: $(basename "$0") <XXX> [--force]"; exit 2; }
echo "== STEP060 plan: $1"
"$H/scripts/spec-tasks.sh" "$@"; rc=$?
[ $rc -eq 0 ] && echo "next: /tck $1 in OpenCode, then /next $1"
exit $rc
