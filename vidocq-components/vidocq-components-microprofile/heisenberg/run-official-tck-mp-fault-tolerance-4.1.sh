#!/usr/bin/env bash
# shellcheck shell=bash
#
# Lance la suite TCK officielle MicroProfile Fault Tolerance 4.1
# (org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-tck:4.1)
# contre l'implémentation Heisenberg.
#
# Modes :
#   ./run-official-tck-mp-fault-tolerance-4.1.sh                 # smoke test (sans Arquillian)
#   ./run-official-tck-mp-fault-tolerance-4.1.sh all             # suite complète (Arquillian + container Heisenberg)
#   ./run-official-tck-mp-fault-tolerance-4.1.sh -Dtest=Foo      # test ciblé via le profil tck-official
#
# Comportement :
#   1. Installe en local (./mvnw install -DskipTests) heisenberg-api/core/cdi-vauban
#   2. Invoque mvn -f heisenberg-tck/pom.xml -P<profile> test [args...]
#   3. Génère heisenberg-tck/target/tck-report.txt avec le résumé PASS/FAIL/SKIP
#
# Vérification de la disponibilité du TCK sur Maven Central :
#   mvn dependency:get -Dartifact=org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-tck:4.1
#
# Multiplicateur de timeout pour environnements CI lents :
#   Passer la propriété système -Dorg.eclipse.microprofile.fault.tolerance.tck.timeout.multiplier=2.0
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TCK_DIR="${ROOT_DIR}/heisenberg-tck"
REPORT_FILE="${TCK_DIR}/target/tck-report.txt"

mode="${1:-smoke}"
shift || true

case "${mode}" in
    smoke)
        profile="smoke"
        echo "==> Mode : SMOKE (HeisenbergTckSmokeTest, hors Arquillian)"
        ;;
    all)
        profile="tck-official"
        echo "==> Mode : ALL (suite officielle MicroProfile Fault Tolerance 4.1 — TestNG/Arquillian)"
        ;;
    -Dtest=*)
        profile="tck-official"
        set -- "${mode}" "$@"
        echo "==> Mode : ciblé (${mode}) avec profil tck-official"
        ;;
    *)
        echo "Usage : $0 [smoke|all|-Dtest=NomDuTest]" >&2
        exit 64
        ;;
esac

echo "==> Étape 1/2 : install local des artefacts Heisenberg (./mvnw install -DskipTests)"
( cd "${ROOT_DIR}" && ./mvnw -ntp -pl heisenberg-api,heisenberg-core,heisenberg-cdi-vauban -am install -DskipTests )

echo "==> Étape 2/2 : exécution Maven sur heisenberg-tck (profil=${profile})"
mkdir -p "${TCK_DIR}/target"

MVN="${ROOT_DIR}/mvnw"

set +e
"${MVN}" -ntp -f "${TCK_DIR}/pom.xml" -P"${profile}" test "$@" \
    | tee "${REPORT_FILE}.raw"
status=$?
set -e

echo "==> Génération du rapport : ${REPORT_FILE}"
summary_line="$(grep 'Tests run:' "${REPORT_FILE}.raw" | tail -n 1 | perl -pe 's/\e\[[0-9;]*[A-Za-z]//g' || true)"
tests_run=""
tests_failures=""
tests_errors=""
tests_skipped=""
tests_passed=""
if [[ "${summary_line}" =~ ^(\[[A-Z]+\]\ )?Tests\ run:\ ([0-9]+),\ Failures:\ ([0-9]+),\ Errors:\ ([0-9]+),\ Skipped:\ ([0-9]+) ]]; then
    tests_run="${BASH_REMATCH[2]}"
    tests_failures="${BASH_REMATCH[3]}"
    tests_errors="${BASH_REMATCH[4]}"
    tests_skipped="${BASH_REMATCH[5]}"
    tests_passed=$((tests_run - tests_failures - tests_errors - tests_skipped))
fi
{
    echo "# Heisenberg TCK report"
    echo "# Généré le $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "# Profile : ${profile}"
    echo "# Args    : $*"
    echo
    if [ -n "${summary_line}" ]; then
        echo "# Résumé : ${summary_line}"
    fi
    if [ -n "${tests_passed}" ]; then
        echo "# Tests réussis : ${tests_passed}/${tests_run}"
        echo "# Détails       : failures=${tests_failures}, errors=${tests_errors}, skipped=${tests_skipped}"
    fi
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
