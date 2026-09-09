#!/usr/bin/env bash
# Check M0 card by card, mechanically. PASS is a fact here, not a claim.
#
# WHY: M0-T002 said "./scripts/build.sh builds it, exit 0" without saying WHERE.
# An agent ran it at the repository root, the 26-module reactor built fine, and
# it recorded `install -> OK (exit 0), 10 tests, 0 failures` as evidence. Every
# word of that was true. None of it was about the card: the TCK runner is out of
# the reactor, so the root build never touched it — and the module did not even
# compile (missing groupId).
#
# A done-when that another command can satisfy is not a done-when. Each check
# below names the exact artifact of the card it verifies.
#
# Usage: scripts/verify-m0.sh <XXX>        exit 0 = every card passes
set -uo pipefail
H="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CODE="${1:-}"
printf '%s' "$CODE" | grep -qE '^[A-Z]{3}$' || { echo "usage: scripts/verify-m0.sh <XXX>"; exit 2; }

CONF="$H/docs/spec-src/$CODE/module.conf"
[ -f "$CONF" ] || { echo "no $CONF — run scripts/steps/STEP050_module_path.sh $CODE"; exit 3; }
MOD="$(sed -n 's/^ *tck_module *= *//p' "$CONF" | tr -d ' ')"
PARENT="${MOD%%/*}"
[ -n "$MOD" ] || { echo "module.conf has no tck_module"; exit 3; }

pass=0; fail=0
ck() { # ck <card> <label> <0|1> [detail]
    if [ "$3" -eq 0 ]; then pass=$((pass+1)); printf '  %-8s PASS  %s\n' "$1" "$2"
    else fail=$((fail+1)); printf '  %-8s FAIL  %s%s\n' "$1" "$2" "${4:+ — $4}"; fi
}

echo "== verify M0 for $CODE (module: $MOD)"

# T001 — the parent module exists AND the root reactor knows about it
r=1; d=""
if [ ! -f "$H/$PARENT/pom.xml" ]; then d="$PARENT/pom.xml missing"
elif ! grep -q "<module>$PARENT</module>" "$H/pom.xml"; then d="root pom.xml does not list <module>$PARENT</module>"
else r=0; fi
ck M0-T001 "parent module registered in the root reactor" $r "$d"

# T002 — the runner exists, is OUT of the reactor, and COMPILES WHERE IT LIVES
r=1; d=""
if [ ! -f "$H/$MOD/pom.xml" ]; then d="$MOD/pom.xml missing"
elif grep -q "<module>${MOD##*/}</module>" "$H/$PARENT/pom.xml" 2>/dev/null; then
    d="the runner is listed in $PARENT/pom.xml <modules> — it must stay OUT of the reactor"
else
    ( cd "$H/$MOD" && "$H/scripts/build.sh" test-compile ) >/dev/null 2>&1 && r=0 || d="test-compile fails IN $MOD"
fi
ck M0-T002 "runner compiles in its own directory" $r "$d"

# T003 — the TCK jar actually resolves for THIS module
r=1; d=""
COORD="$(python3 -c "
import json;t=json.load(open('$H/docs/spec-src/$CODE/spec-meta.json')).get('tck') or {}
r=t.get('recommended') or {};print('%s:%s:%s'%(r.get('groupId'),r.get('artifactId'),r.get('version')) if r else '')" 2>/dev/null)"
if [ -z "$COORD" ] || [ "$COORD" = "None:None:None" ]; then d="no TCK coordinate in spec-meta.json"
elif [ ! -f "$H/$MOD/pom.xml" ]; then d="no runner yet"
elif ! grep -q "${COORD#*:}" "$H/$MOD/pom.xml" 2>/dev/null && ! grep -q "$(echo "$COORD" | cut -d: -f2)" "$H/$MOD/pom.xml"; then
    d="$COORD is not declared in the runner pom"
else
    ( cd "$H/$MOD" && "$H/scripts/build.sh" dependency:resolve ) >/dev/null 2>&1 && r=0 || d="dependency:resolve fails"
fi
ck M0-T003 "TCK jar declared and resolving ($COORD)" $r "$d"

# T004 — the wiring exists where a runner of this repo puts it
r=1; d=""
RUN="$(ls "$H/$MOD"/run-official-tck-*.sh 2>/dev/null | head -1)"
if [ -z "$RUN" ]; then d="no run-official-tck-*.sh in $MOD"
elif [ ! -x "$RUN" ]; then d="$(basename "$RUN") is not executable"
elif [ ! -f "$H/$MOD/src/test/resources/arquillian.xml" ]; then d="src/test/resources/arquillian.xml missing"
else r=0; fi
ck M0-T004 "run script + Arquillian config present" $r "$d"

# T005 — the suite produced a counter. ANY counter. Zero is a pass.
r=1; d=""
LOG="$(ls -t "$H"/target/build-logs/*.log 2>/dev/null | head -1)"
if [ -z "$RUN" ]; then d="nothing to run yet"
elif [ -z "$LOG" ]; then d="no build log"
elif grep -qE 'Tests run: [0-9]+' "$LOG" && grep -q "$MOD" "$LOG"; then r=0
    d="$(grep -oE 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+' "$LOG" | tail -1)"
else d="the latest build log shows no counter FOR $MOD (a counter from another module is not this card)"
fi
ck M0-T005 "the TCK produced a counter, any counter" $r "$d"

echo
printf 'M0: %d pass, %d fail\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
