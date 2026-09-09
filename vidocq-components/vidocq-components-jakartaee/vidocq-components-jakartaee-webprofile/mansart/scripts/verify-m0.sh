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
# Identify the module by its artifactId, not by its path: Maven logs
# "---< io.vidocq.mansart:mansart-jakarta-persistence-tck >---", never the
# directory. A first version grepped the path, found nothing, and reported FAIL
# on a run that had produced 991 tests — the checker lying in the safe direction
# is still the checker lying.
ARTIFACT="$(sed -n 's:.*<artifactId>\(.*\)</artifactId>.*:\1:p' "$H/$MOD/pom.xml" 2>/dev/null | head -1)"
[ -n "$ARTIFACT" ] || ARTIFACT="${MOD##*/}"
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
elif grep -q "<module>$MOD</module>" "$H/pom.xml" 2>/dev/null; then
    # A run registered BOTH the parent and the runner in the root pom. T001 only
    # looked for the parent, so it passed while the runner sat in the reactor —
    # the exact thing M0 forbids, invisible to the check meant to forbid it.
    d="the runner is listed in the ROOT pom <modules> — only the parent belongs there"
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
elif ls "$H/scripts"/run-official-tck-*.sh >/dev/null 2>&1; then
    # A run left a copy in scripts/. Two scripts with the same name, one of them
    # never executed, is how a fix gets applied to the wrong file for an hour.
    d="a stray copy sits in scripts/ ($(basename "$(ls "$H"/scripts/run-official-tck-*.sh | head -1)")) — the run script belongs in $MOD and nowhere else"
else r=0; fi
ck M0-T004 "run script + Arquillian config present" $r "$d"

# T005 — the suite produced a counter. ANY counter. Zero is a pass.
r=1; d=""
# NOT the newest log: this script itself runs build.sh twice (T002, T003), so the
# newest log is always one of its own compile runs and holds no counter. A first
# version read it, found nothing, and reported FAIL on a tree that had just
# produced 991 tests. A checker lying in the safe direction is still lying.
# Scan back for the newest log that names THIS artifact and holds a counter.
LOG=""
for f in $(ls -t "$H"/target/build-logs/*.log 2>/dev/null | head -40); do
    # The counter must come from the OFFICIAL suite. A delivered runner shipped
    # src/test/java/.../Client.java containing assertTrue(true) — a class whose
    # name matches the **/Client.class include, so it runs and reports
    # "Tests run: 1, Failures: 0" even when no TCK test is selected at all.
    # A card satisfied by a tautology is worse than a card left undone.
    # ...and NEWER THAN THE POM. Delete the module, rebuild it with the same
    # artifactId, and a log from the previous incarnation would satisfy this card
    # without a single test having run. A counter must be about the runner that
    # exists now.
    if [ "$f" -nt "$H/$MOD/pom.xml" ] \
       && grep -q "$ARTIFACT" "$f" && grep -qE 'Tests run: [0-9]+' "$f" \
       && grep -q 'ee\.jakarta\.tck\.' "$f"; then LOG="$f"; break; fi
done
# No counter on disk? RUN THE SUITE. A run wired all six cards correctly and
# never executed the TCK, so the card sat at FAIL until a human ran it by hand —
# and a checker that needs a human to produce the evidence it checks is a checker
# that cannot finish anything. The measurement IS the verification here.
if [ -z "$LOG" ] && [ -n "$RUN" ] && [ "${VERIFY_M0_NO_RUN:-}" != "1" ]; then
    printf '  M0-T005  .... no counter on disk; running the suite once (up to 30 min)\n'
    ( cd "$H/$MOD" && timeout 1800 "$H/scripts/build.sh" -Ptck-run test ) >/dev/null 2>&1
    for f in $(ls -t "$H"/target/build-logs/*.log 2>/dev/null | head -5); do
        if grep -q "$ARTIFACT" "$f" && grep -qE 'Tests run: [0-9]+' "$f" \
           && grep -q 'ee\.jakarta\.tck\.' "$f"; then LOG="$f"; break; fi
    done
fi
if [ -z "$RUN" ]; then d="nothing to run yet"
elif [ -n "$LOG" ]; then r=0
    d="$(grep -oE 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+' "$LOG" | tail -1) ($(basename "$LOG"))"
else
    d="no build log shows a counter from the OFFICIAL suite (ee.jakarta.tck.*) for $ARTIFACT"
    if find "$H/$MOD/src/test/java" -name 'Client.java' 2>/dev/null | grep -q .; then
        d="$d
             refusing: this module declares its own Client.java. That name matches
             the **/Client.class include, so it counts as a test result while no
             TCK test runs. Delete it — a smoke test must not be named Client."
    fi
    # The most likely cause, and it is never obvious: Jakarta TCK test classes
    # are named Client — 160 of them in the persistence jar — which matches NONE
    # of surefire's default include patterns (*Test, Test*, *Tests, *TestCase).
    # A runner copied from another spec inherits that spec's <includes> and
    # silently selects nothing: BUILD SUCCESS, zero tests, zero errors.
    if [ -f "$H/$MOD/pom.xml" ] && ! grep -q 'dependenciesToScan' "$H/$MOD/pom.xml"; then
        d="$d
             hint: no <dependenciesToScan> in the runner pom. The TCK tests live
             inside a jar; surefire only scans this module's own classes unless
             told otherwise, so the run exits 0 with Tests run: 0."
    fi
    if [ -f "$H/$MOD/pom.xml" ] && ! grep -q 'Client\.class' "$H/$MOD/pom.xml"; then
        d="$d
             hint: no <include>**/Client.class</include> in the runner pom. Jakarta
             TCK tests are named Client and match no default surefire pattern, so
             the suite selects nothing and still exits 0."
    fi
fi
ck M0-T005 "the TCK produced a counter, any counter" $r "$d"

# T006 — a counter that measures the implementation, not the harness.
# By default the TCK thinks it runs inside a JakartaEE container: every test
# fails at setup demanding an injected EntityManager, and the score stays put
# whatever gets implemented. platform.mode=standalone moves the failure to the
# real cause — no persistence provider — which is the number M1 will move.
r=1; d=""
if [ ! -f "$H/$MOD/pom.xml" ]; then d="no runner yet"
elif ! grep -q 'platform\.mode' "$H/$MOD/pom.xml"; then
    d="platform.mode is not set in the runner pom — the TCK defaults to jakartaEE and every test fails asking for a container-injected EntityManager"
elif ! grep -qE 'platform\.mode>[[:space:]]*standalone' "$H/$MOD/pom.xml"; then
    d="platform.mode is set but not to 'standalone'"
elif ! grep -q 'persistence\.unit\.name' "$H/$MOD/pom.xml"; then
    d="persistence.unit.name is not set — the TCK needs to be told which unit to use"
else
    PXML="$(find "$H/$MOD/src/test/resources" -name 'persistence.xml' 2>/dev/null | head -1)"
    if [ -z "$PXML" ]; then
        d="no persistence.xml under src/test/resources (the TCK ships a template: unzip -p .../persistence-tck-common-*.jar ee/jakarta/tck/persistence/common/template/standalone/persistence.xml)"
    # The unit NAMES are not a detail: the TCK asks for JPATCK by name. A file
    # declaring "default" is a persistence.xml that no test will ever load.
    elif ! grep -q 'persistence-unit name="JPATCK"' "$PXML"; then
        d="$(basename "$PXML") declares no persistence-unit named JPATCK — the TCK looks that name up, so a unit called anything else is never loaded"
    # And the provider decides WHOSE conformance is being measured. Declaring
    # another implementation turns the whole suite into a test of that project:
    # a run was delivered naming org.eclipse.persistence.jpa.PersistenceProvider.
    elif grep -qE '<provider>(?!.*(io\.vidocq|mansart))' "$PXML" 2>/dev/null ||
         grep -E '<provider>' "$PXML" | grep -qvE 'io\.vidocq|mansart'; then
        d="$(basename "$PXML") names a foreign <provider> ($(grep -oE '<provider>[^<]*' "$PXML" | head -1 | cut -c11-)) — that measures THAT project's conformance, not ours. Leave it out until our provider exists."
    else r=0; d="platform.mode=standalone, persistence.unit.name set, JPATCK declared"; fi
fi
ck M0-T006 "the counter measures the implementation, not the harness" $r "$d"

echo
printf 'M0: %d pass, %d fail\n' "$pass" "$fail"

# Refresh STATUS from what was just measured. An agent committed a working
# runner and left STATUS reading "0 done": the counts are computed at generation
# time, so they are stale from the moment anything changes. Making every
# verification refresh them removes the window entirely — nobody has to remember.
# VERIFY_M0_NO_SYNC breaks the cycle when spec-tasks.sh is the one calling us.
if [ -z "${VERIFY_M0_NO_SYNC:-}" ] && [ -x "$H/scripts/spec-tasks.sh" ]; then
    VERIFY_M0_NO_SYNC=1 "$H/scripts/spec-tasks.sh" "$CODE" >/dev/null 2>&1 \
        && echo "STATUS-$CODE.md refreshed"
fi
[ "$fail" -eq 0 ]
