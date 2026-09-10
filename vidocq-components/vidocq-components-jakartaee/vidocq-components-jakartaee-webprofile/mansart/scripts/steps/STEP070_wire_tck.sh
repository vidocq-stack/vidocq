#!/usr/bin/env bash
# STEP 070 — wire the TCK runner and drive it to a calibrated counter.
# in:  <XXX>     out: <parent>/ + <parent>/<runner>/, STATUS refreshed, exit = verify-m0
#
# Four @impl calls, one artifact each, sequential, a watchdog on each; then up to
# N verify-and-fix rounds where every verify-m0 FAIL line becomes the next @impl
# instruction. The lead is NOT in this loop: asked to "delegate four artifacts",
# a lead read the references itself for twelve tool calls, then wrote the four
# delegations AS TEXT and ended its turn. Dispatching mechanical calls is a
# shell's job — same lesson as spec-note.sh and spec-tasks.sh, one level up.
#
# Usage: scripts/steps/STEP070_wire_tck.sh <XXX> [--timeout S] [--rounds N]
set -uo pipefail
H="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CODE="${1:-}"; TIMEOUT=900; ROUNDS=6
shift || true
while [ $# -gt 0 ]; do case "$1" in
    --timeout) TIMEOUT="$2"; shift 2 ;; --rounds) ROUNDS="$2"; shift 2 ;; *) shift ;; esac; done
printf '%s' "$CODE" | grep -qE '^[A-Z]{3}$' || { echo "usage: STEP070_wire_tck.sh <XXX> [--timeout S] [--rounds N]"; exit 2; }
cd "$H" || exit 3

META="docs/spec-src/$CODE/spec-meta.json"
[ -f "$META" ] || { echo "no $META — run STEP010 first"; exit 3; }
MOD="$(python3 scripts/tck-module.py "$CODE" 2>/dev/null)"; PARENT="${MOD%%/*}"; RUNNER="${MOD##*/}"
[ -n "$MOD" ] || { echo "no module path — STEP050 first"; exit 3; }
read -r COORD KW VER REF < <(python3 - "$META" <<'PY'
import json,sys
m=json.load(open(sys.argv[1])); t=m.get("tck") or {}; r=t.get("recommended") or {}
runners=[x["path"] for x in t.get("repo_runners",[])]
print("{}:{}:{}".format(r.get("groupId","?"),r.get("artifactId","?"),r.get("version","?")),
      t.get("keyword","spec"), t.get("spec_version","?"), runners[0] if runners else "")
PY
)
[ "$COORD" != "?:?:?" ] || { echo "no runnable TCK in $META — STEP030/STEP040 first"; exit 3; }
REFPARENT="${REF%%/*}"
REFSCRIPT="$(ls "$REF"/run-official-tck-*.sh 2>/dev/null | head -1)"
LOGD="$H/target/wire-logs"; mkdir -p "$LOGD"

COMMON="RULES (AGENTS.md applies): write ONLY the file(s) named here, nothing else. Never touch $REFPARENT/, mansart-transactions/ or mansart-pool/ — they are frozen. Never write Java. Do not run mvn. When done, ls -la what you wrote and report 3 lines."

impl() { # impl <label> <prompt>
    printf '  [%s] %-38s ' "$(date +%H:%M:%S)" "$1"
    if timeout "$TIMEOUT" opencode run --agent impl "$2" </dev/null >"$LOGD/$1.log" 2>&1; then echo "returned"
    else rc=$?; [ $rc -eq 124 ] && echo "TIMEOUT ${TIMEOUT}s" || echo "exit $rc"; fi
}
have() { [ -e "$1" ] && printf '        ok   %s\n' "$1" || printf '        MISSING %s\n' "$1"; }

echo "== STEP070 wire TCK runner for $CODE"
echo "   module   $MOD"
echo "   tck      $COORD"
echo "   reference $REF"
echo

if [ ! -f "$PARENT/pom.xml" ]; then
impl "1-parent-and-root-registration" "Create the Maven parent module for the $KW $VER spec, and register it.
READ FIRST: pom.xml (root reactor) and $REFPARENT/pom.xml — copy the shape of the latter.
WRITE 1: $PARENT/pom.xml — packaging pom, <parent> io.vidocq.mansart:mansart-root (relativePath ../pom.xml), artifactId $PARENT, name 'Mansart :: Jakarta $KW $VER', NO <modules> section at all, NO <dependencies>.
EDIT 2: root pom.xml — add exactly one line <module>$PARENT</module> inside <modules>, after the existing ones. Change nothing else in it.
$COMMON"
have "$PARENT/pom.xml"; grep -q "<module>$PARENT</module>" pom.xml && echo "        ok   root pom lists $PARENT" || echo "        MISSING <module>$PARENT</module> in root pom"
fi

if [ ! -f "$MOD/pom.xml" ]; then
impl "2-runner-pom" "Create the TCK runner POM for the $KW $VER spec.
READ FIRST: $REF/pom.xml — it is the working reference (its suite passes). Copy its shape, then ADAPT every trace of the other spec.
WRITE: $MOD/pom.xml. Requirements:
- modelVersion 4.0.0, NO <parent> element, standalone; groupId io.vidocq.mansart, artifactId $RUNNER, version 0.3.0-SNAPSHOT, maven.compiler.release 25, maven.deploy.skip true.
- dependencies: jakarta.persistence:jakarta.persistence-api:3.2.0 (scope provided), $COORD (scope test), jakarta.tck:persistence-tck-common:${COORD##*:} (scope test), the Arquillian/TestNG/H2 test dependencies the reference uses. NOTHING ELSE. Do NOT depend on any io.vidocq.mansart implementation module: none exists yet.
- surefire (a 'tck-run' profile like the reference): <includes><include>**/Client.class</include></includes>; <dependenciesToScan><dependency>${COORD%:*}</dependency></dependenciesToScan>; <systemPropertyVariables><platform.mode>standalone</platform.mode><persistence.unit.name>JPATCK</persistence.unit.name></systemPropertyVariables>.
- It must NOT be listed in any <modules>. Do not edit $PARENT/pom.xml or the root pom.
$COMMON"
have "$MOD/pom.xml"
fi

if [ ! -f "$MOD/src/test/resources/persistence.xml" ] || [ ! -f "$MOD/src/test/resources/arquillian.xml" ]; then
impl "3-resources" "Create the test resources of the TCK runner $MOD.
READ FIRST: $REF/src/test/resources/arquillian.xml (copy it, adapt names to $KW).
WRITE 1: $MOD/src/test/resources/arquillian.xml
WRITE 2: $MOD/src/test/resources/persistence.xml — persistence version=\"$VER\" schema persistence_${VER/./_}.xsd, two units: <persistence-unit name=\"JPATCK\" transaction-type=\"RESOURCE_LOCAL\"> and the same for \"JPATCK2\". NO <provider> element in either (no provider exists yet). NO <class> lists.
WRITE NO JAVA FILE. Not a smoke test, not an appender, nothing under src/test/java — the tests come from the TCK jar.
$COMMON"
have "$MOD/src/test/resources/arquillian.xml"; have "$MOD/src/test/resources/persistence.xml"
fi

if ! ls "$MOD"/run-official-tck-*.sh >/dev/null 2>&1; then
impl "4-run-script" "Create the run script of the TCK runner $MOD.
READ FIRST: $REFSCRIPT — copy it, adapt every name to $KW $VER.
WRITE: $MOD/run-official-tck-$KW-$VER.sh (inside the module, NOT in scripts/), executable (chmod +x). Default mode runs: mvn -ntp -Ptck-run test -DfailIfNoTests=false from the module directory. Keep it short.
$COMMON"
have "$(ls "$MOD"/run-official-tck-*.sh 2>/dev/null | head -1)"
fi

echo
for r in $(seq 1 "$ROUNDS"); do
    echo "== verify round $r/$ROUNDS"
    out="$(./scripts/verify-m0.sh "$CODE" 2>&1)"; rc=$?
    printf '%s\n' "$out" | grep -E 'M0-T|SCOPE|M0:' | sed 's/^/   /'
    [ $rc -eq 0 ] && { echo; echo "== M0 complete for $CODE"; exit 0; }
    fails="$(printf '%s\n' "$out" | grep -E 'FAIL|hint:|refusing|stray' | sed 's/^ *//')"
    impl "fix-round-$r" "verify-m0.sh reports these failures for the TCK runner $MOD. Fix EXACTLY what each line names, in the files it names, nothing else:
$fails
Context: reference runner $REF; TCK artifact $COORD; the suite is selected by <include>**/Client.class</include> plus <dependenciesToScan>; platform.mode=standalone and persistence.unit.name=JPATCK are surefire system properties; persistence.xml declares JPATCK and JPATCK2 with no <provider>. Never write Java. Never touch a delivered module.
$COMMON"
done
echo; echo "== M0 NOT complete after $ROUNDS rounds — last verify output above; logs in $LOGD/"
exit 1
