#!/usr/bin/env bash
#
# CRaC end to end: start an application, change its state, take a checkpoint, restore it in a new process, and
# check that the HTTP listener serves again with the state kept.
#
#   JAVA_HOME=<a CRaC JDK, e.g. Azul Zulu CRaC 25> crac-e2e.sh <dist-dir> <vidocq-runtime-crac-extension.jar>
#
# <dist-dir> is the output of `vidocq:package` of vidocq-runtime-cassini-rest-example (bin/, lib/): an application
# with an in-memory Todo list on /api/todos. The extension jar goes into its lib/, which the launcher puts on the
# module path in full. Linux only: a checkpoint is not taken elsewhere.
set -euo pipefail

DIST=${1:?usage: crac-e2e.sh <dist-dir> <vidocq-runtime-crac-extension.jar>}
CRAC_JAR=${2:?usage: crac-e2e.sh <dist-dir> <vidocq-runtime-crac-extension.jar>}
: "${JAVA_HOME:?JAVA_HOME must point to a CRaC JDK}"
PATH="$JAVA_HOME/bin:$PATH"
PORT=${PORT:-8080}
WORK=$(mktemp -d)
IMG="$WORK/img"
mkdir -p "$IMG"
URL="http://127.0.0.1:$PORT/api/todos"
PID=""

cleanup() {
    # Only the process this script started, never a pattern.
    if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
        kill "$PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; for f in "$WORK"/*.log; do echo "--- $f" >&2; tail -40 "$f" >&2; done; exit 1; }

now_ms() { date +%s%3N; }

wait_http() { # wait_http <seconds>
    for _ in $(seq 1 $(($1 * 20))); do
        if curl -fs -o /dev/null "$URL"; then return 0; fi
        sleep 0.05
    done
    return 1
}

java -XX:+PrintFlagsFinal -version 2>/dev/null | grep -q CRaCCheckpointTo || fail "this JDK has no CRaC support"
cp "$CRAC_JAR" "$DIST/lib/"
# Not bin/*.sh: its -Dvidocq.app.path puts the application in its own layer, whose class loader (Vauban) keeps the
# application jar open, and a checkpoint refuses an open file. Without the property the jar is on the module path,
# where the JDK itself closes and reopens it.
# The example reads its classes by reflection, which a layer would have opened for it (the Cassini example needs
# nothing else: its module is not open).
M=io.vidocq.runtime.examples.rest
OPENS=""
for target in io.vidocq.cassini.core io.vidocq.champollion.jsonb io.vidocq.vauban.core io.vidocq.cassini.cdi.vauban \
              io.vidocq.cassini.cdi; do
    OPENS="$OPENS --add-opens $M/$M=$target --add-opens $M/$M.model=$target"
done
run_java() { # run_java <jvm options>...
    # shellcheck disable=SC2086
    exec java $OPENS "$@" --module-path "$DIST/lib:$DIST/app" --add-modules ALL-MODULE-PATH \
        --module io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq
}

echo "== start"
run_java -XX:CRaCCheckpointTo="$IMG" -XX:+StartAttachListener ${EXTRA_OPTS:-} \
    >"$WORK/first.log" 2>&1 &
PID=$!
wait_http 60 || fail "the application did not answer on $URL"
# The listener answers before the crac extension, which starts last, has registered: wait for it.
for _ in $(seq 1 100); do
    grep -q "CRaC: .* suspendable(s) registered" "$WORK/first.log" && break
    sleep 0.1
done
grep -q "CRaC: .* suspendable(s) registered" "$WORK/first.log" || fail "the crac extension did not register"

curl -fs -X POST -H 'Content-Type: application/json' -d '{"title":"kept across the checkpoint"}' "$URL" >/dev/null \
    || fail "cannot create a todo"
curl -fs "$URL" | grep -q "kept across the checkpoint" || fail "the todo is not listed before the checkpoint"

echo "== checkpoint"
jcmd "$PID" JDK.checkpoint >"$WORK/jcmd.log" 2>&1 || true
for _ in $(seq 1 200); do
    kill -0 "$PID" 2>/dev/null || break
    sleep 0.1
done
kill -0 "$PID" 2>/dev/null && fail "the process is still alive after the checkpoint"
PID=""
[ -n "$(ls -A "$IMG")" ] || fail "the checkpoint wrote nothing in $IMG"
grep -q "Suspended" "$WORK/first.log" || fail "the listeners were not suspended before the checkpoint"

echo "== restore"
T0=$(now_ms)
java -XX:CRaCRestoreFrom="$IMG" >"$WORK/restored.log" 2>&1 &
PID=$!
wait_http 30 || fail "the restored application did not answer on $URL"
T1=$(now_ms)
echo "restored and answering after $((T1 - T0)) ms"

curl -fs "$URL" | grep -q "kept across the checkpoint" || fail "the todo did not survive the restore"
curl -fs -X POST -H 'Content-Type: application/json' -d '{"title":"after the restore"}' "$URL" >/dev/null \
    || fail "cannot create a todo after the restore"
curl -fs "$URL" | grep -q "after the restore" || fail "the todo created after the restore is not listed"

echo "OK: checkpoint -> restore -> HTTP, state kept"
