#!/usr/bin/env bash
# shellcheck shell=bash
#
# Runs the official MicroProfile Config 3.1 TCK
# (org.eclipse.microprofile.config:microprofile-config-tck:3.1.1)
# against the Ravel implementation.
#
# Modes:
#   ./run-official-tck-mp-config-3.1.sh                 # smoke test (default, no Arquillian)
#   ./run-official-tck-mp-config-3.1.sh all             # full suite (Arquillian + Weld)
#   ./run-official-tck-mp-config-3.1.sh -Dtest=Foo      # targeted test, tck-official profile
#
# Behaviour:
#   1. Installs ravel-api/ravel-core/ravel-cdi-vauban locally (mvn install -DskipTests)
#   2. Runs mvn -P"tck,<profile>" -pl ravel-tck test [args...]
#   3. Writes ravel-tck/target/tck-report.txt with the test counts and PASS/FAIL
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TCK_DIR="${ROOT_DIR}/ravel-tck"
REPORT_FILE="${TCK_DIR}/target/tck-report.txt"

mode="${1:-smoke}"
shift || true

case "${mode}" in
    smoke)
        profile="smoke"
        echo "==> Mode: SMOKE (RavelTckSmokeTest, no Arquillian)"
        ;;
    all)
        profile="tck-official"
        echo "==> Mode: ALL (official MicroProfile Config 3.1.1 suite — TestNG/Arquillian/Weld)"
        ;;
    -Dtest=*)
        profile="tck-official"
        # Put the shifted argument back so that mvn receives it.
        set -- "${mode}" "$@"
        echo "==> Mode: targeted (${mode}) with the tck-official profile"
        ;;
    *)
        echo "Usage: $0 [smoke|all|-Dtest=TestName]" >&2
        exit 64
        ;;
esac

echo "==> Step 1/2: local install of the Ravel artifacts (mvn install -DskipTests)"
( cd "${ROOT_DIR}" && mvn -ntp -pl ravel-api,ravel-core,ravel-cdi-vauban -am install -DskipTests )

echo "==> Step 2/2: Maven run on the in-reactor ravel-tck (profiles=tck,${profile})"
mkdir -p "${TCK_DIR}/target"

# ravel-tck is in the reactor, enabled by the `tck` Maven profile (TCK harmonisation,
# same pattern as the vidocq-runtime-tck-* runners, see CLAUDE.md).

set +e
( cd "${ROOT_DIR}" && mvn -ntp -P"tck,${profile}" -pl ravel-tck test "$@" ) \
    | tee "${REPORT_FILE}.raw"
status=$?
set -e

# Summary: extract the final "Tests run:" lines per module
echo "==> Writing the report: ${REPORT_FILE}"
{
    echo "# Ravel TCK report"
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


