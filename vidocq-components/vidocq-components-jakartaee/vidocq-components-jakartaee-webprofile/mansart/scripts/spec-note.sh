#!/usr/bin/env bash
# Write one note per spec chapter — SEQUENTIALLY, with a watchdog per call.
#
# Why this is a script and not an agent loop:
#   The lead used to dispatch all 39 noter calls at once against a server with
#   max_concurrent_requests=2. Result: 9 active + 7 queued, prompt processing
#   collapsed from 1730 to 170 tok/s, and one call hung forever. The run stayed
#   "alive" for 51 minutes without writing a single file — the worst kind of
#   failure, because it looks exactly like work in progress.
#
#   Dispatching N mechanical calls is not agent work. A shell does it better:
#   one at a time, a hard timeout each, and progress you can see.
#
# Usage: scripts/spec-note.sh <XXX> [--timeout SECONDS] [--force]
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
CODE="${1:-}"
TIMEOUT=600
FORCE=0
shift || true
while [ $# -gt 0 ]; do
    case "$1" in
        --timeout) TIMEOUT="$2"; shift 2 ;;
        --force) FORCE=1; shift ;;
        *) shift ;;
    esac
done

if ! printf '%s' "$CODE" | grep -qE '^[A-Z]{3}$'; then
    echo "usage: scripts/spec-note.sh <XXX> [--timeout S] [--force]"
    exit 2
fi

SRC="$ROOT/docs/spec-src/$CODE"
DST="$ROOT/docs/spec-notes/$CODE"
[ -d "$SRC" ] || { echo "no chapters at $SRC — run scripts/spec-fetch.py first"; exit 3; }
mkdir -p "$DST"

# Front matter carries no requirement. Deterministic skip, no model needed.
SKIP='preamble|license|foreword|colophon|revision-history|related-documents|bibliography|appendix'

total=0; done_=0; skipped=0; failed=0; empty=0
start=$(date +%s)

for chapter in "$SRC"/ch-*.md; do
    base="$(basename "$chapter")"
    case "$base" in ch-*) ;; *) continue ;; esac
    if printf '%s' "$base" | grep -qiE "$SKIP"; then
        skipped=$((skipped + 1)); continue
    fi
    total=$((total + 1))
    note="$DST/$base"
    if [ -s "$note" ] && [ "$FORCE" -eq 0 ]; then
        done_=$((done_ + 1))
        printf '  [skip] %s (note exists)\n' "$base"
        continue
    fi

    printf '  [%2d] %s ... ' "$total" "$base"
    # The task is repeated in the message on purpose: a 45 KB chapter pushes a
    # system prompt out of reach — a noter once answered "what would you like me
    # to do with this specification?".
    if timeout "$TIMEOUT" opencode run --agent noter \
        "Read $chapter.
         WRITE a note file at $note.
         Max 200 lines. ONLY normative requirements (must/shall/is required to),
         one per line, each prefixed with its spec section number.
         Then ls the note and report: note: <path> / requirements: <n>." \
        >"$DST/.last-noter.log" 2>&1
    then
        if [ -s "$note" ]; then
            done_=$((done_ + 1))
            printf 'ok (%s lines)\n' "$(wc -l < "$note" | tr -d ' ')"
        else
            empty=$((empty + 1))
            printf 'REPORTED OK BUT NO FILE\n'   # the silent failure, made loud
        fi
    else
        rc=$?
        failed=$((failed + 1))
        [ $rc -eq 124 ] && printf 'TIMEOUT after %ss\n' "$TIMEOUT" || printf 'FAILED (exit %s)\n' "$rc"
    fi
done

elapsed=$(( $(date +%s) - start ))
printf '\nnotes: %d/%d written, %d front-matter skipped, %d timed out, %d reported-but-missing (%ds)\n' \
    "$done_" "$total" "$skipped" "$failed" "$empty" "$elapsed"
[ "$done_" -eq "$total" ]
