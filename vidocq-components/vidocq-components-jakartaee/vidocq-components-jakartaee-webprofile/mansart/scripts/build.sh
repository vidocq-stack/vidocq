#!/usr/bin/env bash
# The ONLY way to run Maven in this repo.
#
# Why it exists: `mvn … | tail -5` returns tail's exit code, always 0. That is how
# 262 Maven runs got logged green while 38 of them had failed (BUILD FAILURE, 3
# compile errors, 17 test failures). Agents must never call mvn/mvnw directly —
# the guard plugin denies it.
#
# Contract:
#   - full log goes to a file, never to stdout
#   - stdout is a short summary an agent can put in a 3-line verdict
#   - exit code is Maven's real one
#
# Usage: scripts/build.sh [maven args...]
#        scripts/build.sh -pl mansart-jakarta-data/mansart-data-core test
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
LOGDIR="${MANSART_BUILD_LOGDIR:-$ROOT/target/build-logs}"
mkdir -p "$LOGDIR"
LOG="$LOGDIR/build-$(date +%Y%m%d-%H%M%S)-$$.log"

# --- pin the JDK from .sdkmanrc -----------------------------------------------
# An agent shell does not run `sdk env`: a harness run compiled with Java 26 while
# .sdkmanrc pins 25.0.3-tem. A silently wrong compiler is worse than a failed
# build, so resolve it here instead of trusting whatever JAVA_HOME we inherited.
JDK_LABEL="(caller)"
if [ -f "$ROOT/.sdkmanrc" ]; then
    want_java=$(grep -oE '^[[:space:]]*java=[^[:space:]]+' "$ROOT/.sdkmanrc" | cut -d= -f2 | tr -d '\r')
    if [ -n "${want_java:-}" ]; then
        cand="${SDKMAN_DIR:-$HOME/.sdkman}/candidates/java/$want_java"
        if [ -x "$cand/bin/javac" ]; then
            export JAVA_HOME="$cand"
            export PATH="$JAVA_HOME/bin:$PATH"
            JDK_LABEL="$want_java"
        else
            printf 'FAILED (setup)\n'
            printf 'jdk %s pinned by .sdkmanrc is not installed: %s\n' "$want_java" "$cand"
            printf 'install it with: sdk install java %s\n' "$want_java"
            exit 78 # EX_CONFIG — not a build failure, a machine failure
        fi
    fi
fi

MVN="$ROOT/mvnw"
[ -x "$MVN" ] || MVN="mvn"

# -B batch (no colour codes in the log), -ntp no transfer progress: both exist to
# keep the log greppable, not to look nice.
"$MVN" -B -ntp "$@" >"$LOG" 2>&1
rc=$?

# --- summary: counts first, then the first real error, then the log path -------
tests=$(grep -aoE 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+' "$LOG" \
        | tail -1)
modules_fail=$(grep -acE '^\[INFO\] .* FAILURE \[' "$LOG" || true)

if [ $rc -eq 0 ]; then
    printf 'OK (exit 0) jdk: %s\n' "$JDK_LABEL"
    [ -n "$tests" ] && printf '%s\n' "$tests"
else
    printf 'FAILED (exit %d) jdk: %s\n' "$rc" "$JDK_LABEL"
    [ -n "$tests" ] && printf '%s\n' "$tests"
    [ "${modules_fail:-0}" -gt 0 ] && printf 'modules failed: %s\n' "$modules_fail"
    # first compilation error / test failure / plugin error, whichever comes first
    grep -aE '^\[ERROR\]' "$LOG" \
        | grep -avE 'To see the full stack trace|Re-run Maven|For more information|^\[ERROR\]\s*$|\[Help [0-9]\]|cwiki\.apache\.org' \
        | head -8
fi
printf 'log: %s\n' "$LOG"
exit $rc
