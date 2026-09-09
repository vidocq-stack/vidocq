#!/usr/bin/env bash
# Print (and create) the path of a card's work-order file. Never let an agent
# compose it: told "tasks/<XXX>/<CARD>.md", one wrote tasks/001/CARD.md.
# Usage: scripts/task-file.sh <XXX> <CARD>     e.g. JKP M0-T001
set -uo pipefail
H="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
C="${1:-}"; K="${2:-}"
printf '%s' "$C" | grep -qE '^[A-Z]{3}$' || { echo "usage: scripts/task-file.sh <XXX> <CARD>"; exit 2; }
printf '%s' "$K" | grep -qE '^M[0-9]+-T[0-9]{3}$' || { echo "card must look like M0-T001, got '$K'"; exit 2; }
mkdir -p "$H/tasks/$C"
printf '%s\n' "tasks/$C/$K.md"
