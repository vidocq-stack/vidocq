#!/usr/bin/env bash
# STEP 010 — ingest a spec: download, split into chapters, record TCK coordinates.
# in:  <url> <XXX>     out: docs/spec-src/<XXX>/ + spec-meta.json
# HTML is preferred over PDF (no converter on this machine, and headings survive
# the split). Any chapter over 45 KB is split again: an 80 KB chapter once pushed
# a worker's system prompt out of reach and it asked what it was supposed to do.
set -uo pipefail
H="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
[ $# -ge 2 ] || { echo "usage: $(basename "$0") <url> <XXX>"; exit 2; }
echo "== STEP010 fetch+split: $2"
python3 "$H/scripts/spec-fetch.py" "$@"; rc=$?
[ $rc -eq 0 ] && echo "next: STEP020_note_chapters.sh $2"
exit $rc
