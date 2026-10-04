#!/usr/bin/env zsh
set -euo pipefail

MODE="${1:-smoke}"

run_tck() {
  local label="$1"
  shift
  echo "[grimm] Running TCK variant: ${label}"
  # clean: the harness is always compiled from scratch (a stale target/ gives false results)
  mvn -f grimm-tck/pom.xml -Ptck-official clean test "$@"
}

if [[ "$MODE" == "all" ]]; then
  echo "[grimm] Running full TCK suite"
  ./mvnw -ntp clean install -DskipTests
  run_tck "full"
elif [[ "$MODE" == "smoke" ]]; then
  echo "[grimm] Running smoke TCK suite"
  ./mvnw -ntp clean install -DskipTests
  run_tck "smoke" -Dtest=GrimmTckSmokeTest
elif [[ "$MODE" == "matrix" ]]; then
  TARGET_TEST="${2:-PetStoreAppTest}"
  echo "[grimm] Running matrix TCK suite on ${TARGET_TEST}"
  ./mvnw -ntp clean install -DskipTests
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
  ./mvnw -ntp clean install -DskipTests
  run_tck "targeted" "$@"
fi

