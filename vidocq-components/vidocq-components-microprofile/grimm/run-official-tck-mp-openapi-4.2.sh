#!/usr/bin/env zsh
set -euo pipefail

MODE="${1:-smoke}"

# Every Maven call reads and writes a local repository private to this checkout, above the shared
# ~/.m2/repository it only reads: an install from a branch never shadows the published snapshot every
# other build of the machine resolves (BUG-20261123-57). TCK_LOCAL_REPOSITORY moves it.
# The script runs from the grimm/ folder (./mvnw, grimm-tck/pom.xml): the repository sits there.
LOCAL_REPOSITORY="${TCK_LOCAL_REPOSITORY:-$PWD/.tck-repository}"
REPOSITORY_ARGS=("-Dmaven.repo.local=$LOCAL_REPOSITORY" "-Dmaven.repo.local.tail=$HOME/.m2/repository")

run_tck() {
  local label="$1"
  shift
  echo "[grimm] Running TCK variant: ${label}"
  # clean: the harness is always compiled from scratch (a stale target/ gives false results)
  ./mvnw "${REPOSITORY_ARGS[@]}" -f grimm-tck/pom.xml -Ptck-official clean test "$@"
}

if [[ "$MODE" == "all" ]]; then
  echo "[grimm] Running full TCK suite"
  ./mvnw -ntp "${REPOSITORY_ARGS[@]}" clean install -DskipTests
  run_tck "full"
elif [[ "$MODE" == "smoke" ]]; then
  echo "[grimm] Running smoke TCK suite"
  ./mvnw -ntp "${REPOSITORY_ARGS[@]}" clean install -DskipTests
  run_tck "smoke" -Dtest=GrimmTckSmokeTest
elif [[ "$MODE" == "matrix" ]]; then
  TARGET_TEST="${2:-PetStoreAppTest}"
  echo "[grimm] Running matrix TCK suite on ${TARGET_TEST}"
  ./mvnw -ntp "${REPOSITORY_ARGS[@]}" clean install -DskipTests
  run_tck "default-readiness" -Dtest="${TARGET_TEST}" \
    -Dgrimm.tck.port=0 \
    -Dgrimm.tck.waitForReadiness=true \
    -Dgrimm.tck.readinessTimeoutMillis=10000
  run_tck "extended-readiness" -Dtest="${TARGET_TEST}" \
    -Dgrimm.tck.port=0 \
    -Dgrimm.tck.waitForReadiness=true \
    -Dgrimm.tck.readinessTimeoutMillis=30000
  run_tck "no-readiness-probe" -Dtest="${TARGET_TEST}" \
    -Dgrimm.tck.port=0 \
    -Dgrimm.tck.waitForReadiness=false
else
  echo "[grimm] Running targeted TCK: $*"
  ./mvnw -ntp "${REPOSITORY_ARGS[@]}" clean install -DskipTests
  run_tck "targeted" "$@"
fi

