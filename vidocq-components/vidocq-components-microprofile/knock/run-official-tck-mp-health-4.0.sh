#!/usr/bin/env bash
# shellcheck shell=bash
#
# Runs the official MicroProfile Health 4.0 TCK
# (org.eclipse.microprofile.health:microprofile-health-tck:4.0.1)
# against the Knock implementation.
#
# Modes:
#   ./run-official-tck-mp-health-4.0.sh                 # smoke test (default, no Arquillian)
#   ./run-official-tck-mp-health-4.0.sh all             # full suite (Arquillian + Weld)
#   ./run-official-tck-mp-health-4.0.sh -Dtest=Foo      # targeted test, tck-official profile
#
# Behaviour:
#   1. Installs knock-api/knock-core/knock-cdi-vauban/knock-jaxrs locally (./mvnw install -DskipTests)
#   2. Runs ./mvnw -P"tck,<profile>" -pl knock-tck test [args...]
#      (knock-tck is in the reactor, enabled by the `tck` Maven profile — TCK harmonisation)
#   3. Writes knock-tck/target/tck-report.txt with the test counts and PASS/FAIL
#
# Before using the 'all' mode:
#   check that the TCK is available on Maven Central:
#   mvn dependency:get -Dartifact=org.eclipse.microprofile.health:microprofile-health-tck:4.0.1
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TCK_DIR="${ROOT_DIR}/knock-tck"
REPORT_FILE="${TCK_DIR}/target/tck-report.txt"

mode="${1:-smoke}"
shift || true

case "${mode}" in
    smoke)
        profile="smoke"
        echo "==> Mode: SMOKE (KnockTckSmokeTest, no Arquillian)"
        ;;
    all)
        profile="tck-official"
        echo "==> Mode: ALL (official MicroProfile Health 4.0 suite — TestNG/Arquillian/Weld)"
        ;;
    -Dtest=*)
        profile="tck-official"
        set -- "${mode}" "$@"
        echo "==> Mode: targeted (${mode}) with the tck-official profile"
        ;;
    *)
        echo "Usage: $0 [smoke|all|-Dtest=TestName]" >&2
        exit 64
        ;;
esac

echo "==> Step 1/2: local install of the Knock artifacts (./mvnw install -DskipTests)"
( cd "${ROOT_DIR}" && ./mvnw -ntp -pl knock-api,knock-core,knock-cdi-vauban,knock-jaxrs -am install -DskipTests )

echo "==> Step 2/2: Maven run on the in-reactor knock-tck (profiles=tck,${profile})"
mkdir -p "${TCK_DIR}/target"

MVN="${ROOT_DIR}/mvnw"

set +e
( cd "${ROOT_DIR}" && "${MVN}" -ntp -P"tck,${profile}" -pl knock-tck test "$@" ) \
    | tee "${REPORT_FILE}.raw"
status=$?
set -e

echo "==> Writing the report: ${REPORT_FILE}"
{
    echo "# Knock TCK report"
    echo "# Generated $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "# Profile : ${profile}"
    echo "# Args    : $*"
    echo
    grep -E "^\[INFO\] Tests run:|^Tests run:" "${REPORT_FILE}.raw" || true
    echo
    if [ ${status} -eq 0 ]; then
        echo "RESULT : PASS"
    else
        echo "RESULT : FAIL (exit ${status})"
    fi
} > "${REPORT_FILE}"

cat "${REPORT_FILE}"
exit ${status}
