#!/usr/bin/env bash
# shellcheck shell=bash
#
# Lance la suite TCK officielle MicroProfile Health 4.0
# (org.eclipse.microprofile.health:microprofile-health-tck:4.0.1)
# contre l'implémentation Knock.
#
# Modes :
#   ./run-official-tck-mp-health-4.0.sh                 # smoke test (sans Arquillian)
#   ./run-official-tck-mp-health-4.0.sh all             # suite complète (Arquillian + Weld)
#   ./run-official-tck-mp-health-4.0.sh -Dtest=Foo      # test ciblé via le profil tck-official
#
# Comportement :
#   1. Installe en local (./mvnw install -DskipTests) knock-api/knock-core/knock-cdi-vauban/knock-cassini
#   2. Invoque mvn -f knock-tck/pom.xml -P<profile> test [args...]
#   3. Génère target/tck-report.txt avec le résumé PASS/FAIL/SKIP
#
# Prérequis avant d'utiliser le mode 'all' :
#   Vérifier la disponibilité du TCK sur Maven Central :
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
        echo "==> Mode : SMOKE (KnockTckSmokeTest, hors Arquillian)"
        ;;
    all)
        profile="tck-official"
        echo "==> Mode : ALL (suite officielle MicroProfile Health 4.0 — TestNG/Arquillian/Weld)"
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

echo "==> Étape 1/2 : install local des artefacts Knock (./mvnw install -DskipTests)"
( cd "${ROOT_DIR}" && ./mvnw -ntp -pl knock-api,knock-core,knock-cdi-vauban,knock-cassini -am install -DskipTests )

echo "==> Étape 2/2 : exécution Maven sur knock-tck (profil=${profile})"
mkdir -p "${TCK_DIR}/target"

MVN="${ROOT_DIR}/mvnw"

set +e
"${MVN}" -ntp -f "${TCK_DIR}/pom.xml" -P"${profile}" test "$@" \
    | tee "${REPORT_FILE}.raw"
status=$?
set -e

echo "==> Génération du rapport : ${REPORT_FILE}"
{
    echo "# Knock TCK report"
    echo "# Généré le $(date -u +%Y-%m-%dT%H:%M:%SZ)"
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
