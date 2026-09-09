#!/usr/bin/env bash
# Module-scoped SonarQube scan. Same contract as build.sh:
#   - full log to a file, never to stdout
#   - short summary an agent can put in a 3-line verdict
#   - real exit code
#
# Skips cleanly (exit 0) when Sonar is not configured, so /next keeps working
# instead of dying on a missing token. A skip is reported, never hidden.
#
# Usage: scripts/sonar.sh <module-path>
#   SONAR_HOST   default http://localhost:9001   (the vidocq-sonar container)
#   SONAR_TOKEN  required to actually scan; unset => skip
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
MODULE="${1:-}"
HOST="${SONAR_HOST:-http://localhost:9001}"
CONTAINER="${SONAR_CONTAINER:-vidocq-sonar}"
LOGDIR="${MANSART_BUILD_LOGDIR:-$ROOT/target/build-logs}"
mkdir -p "$LOGDIR"
LOG="$LOGDIR/sonar-$(date +%Y%m%d-%H%M%S)-$$.log"

if [ -z "${SONAR_TOKEN:-}" ]; then
    printf 'sonar: skipped — SONAR_TOKEN not set\n'
    printf 'to enable: create a project token in %s, then export SONAR_TOKEN=...\n' "$HOST"
    exit 0
fi

# --- make sure the server is up: it stops with OrbStack -----------------------
up() { curl -fsS --max-time 5 "$HOST/api/system/status" 2>/dev/null | grep -q '"status":"UP"'; }
if ! up; then
    docker start "$CONTAINER" >/dev/null 2>&1 || true
    for _ in $(seq 1 60); do up && break; sleep 2; done
fi
if ! up; then
    printf 'sonar: FAILED — %s is not UP (container %s)\n' "$HOST" "$CONTAINER"
    exit 69 # EX_UNAVAILABLE: machine problem, not a code problem
fi

# The plugin is not declared in any POM on purpose — invoked by coordinates so
# the build stays clean for people who do not run Sonar.
# NOTE: no -pl. The scanner refuses it — "Maven session does not declare a top
# level project". Scope by inclusions instead: same speed, and it actually runs.
PLUGIN="org.sonarsource.scanner.maven:sonar-maven-plugin:sonar"
ARGS=("$PLUGIN" "-Dsonar.host.url=$HOST" "-Dsonar.token=$SONAR_TOKEN")
[ -n "$MODULE" ] && ARGS+=("-Dsonar.inclusions=$MODULE/**")

out=$("$HERE/build.sh" "${ARGS[@]}" 2>&1)
rc=$?
# build.sh already wrote the real log; point at it instead of nesting a second one.
inner=$(printf '%s' "$out" | grep -oE '/[^ ]+\.log' | tail -1)
[ -n "$inner" ] && LOG="$inner" || printf '%s\n' "$out" >"$LOG"

if [ $rc -eq 0 ]; then
    url=$(grep -aoE 'https?://[^ ]+/dashboard[^ ]*' "$LOG" | tail -1)
    printf 'sonar: OK (exit 0)\n'
    [ -n "$url" ] && printf 'dashboard: %s\n' "$url"
else
    printf 'sonar: FAILED (exit %d)\n' "$rc"
    grep -aE '^\[ERROR\]' "$LOG" \
        | grep -avE '\[Help [0-9]\]|cwiki\.apache\.org|To see the full stack trace' \
        | head -5
fi
printf 'log: %s\n' "$LOG"
exit $rc
