#!/usr/bin/env bash
# shellcheck shell=bash
#
# Lance la suite TCK officielle MicroProfile Config 3.1
# (org.eclipse.microprofile.config:microprofile-config-tck:3.1.1)
# contre l'implémentation Ravel.
#
# Modes :
#   ./run-official-tck-mp-config-3.1.sh                 # smoke test (sans Arquillian)
#   ./run-official-tck-mp-config-3.1.sh all             # suite complète (Arquillian + Weld)
#   ./run-official-tck-mp-config-3.1.sh -Dtest=Foo      # test ciblé via le profil tck-official
#
# Comportement :
#   1. Installe en local (mvn install -DskipTests) ravel-api/ravel-core/ravel-cdi-vauban
#   2. Invoque mvn -f ravel-tck/pom.xml -P<profile> test [args...]
#   3. Génère target/tck-report.txt avec le résumé PASS/FAIL/SKIP
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
        echo "==> Mode : SMOKE (RavelTckSmokeTest, hors Arquillian)"
        ;;
    all)
        profile="tck-official"
        echo "==> Mode : ALL (suite officielle MicroProfile Config 3.1.1 — TestNG/Arquillian/Weld)"
        ;;
    -Dtest=*)
        profile="tck-official"
        # On replace l'argument shifté pour que mvn le reçoive.
        set -- "${mode}" "$@"
        echo "==> Mode : ciblé (${mode}) avec profil tck-official"
        ;;
    *)
        echo "Usage : $0 [smoke|all|-Dtest=NomDuTest]" >&2
        exit 64
        ;;
esac

echo "==> Étape 1/2 : install local des artefacts Ravel (mvn install -DskipTests)"
( cd "${ROOT_DIR}" && mvn -ntp -pl ravel-api,ravel-core,ravel-cdi-vauban -am install -DskipTests )

echo "==> Étape 2/2 : exécution Maven sur ravel-tck in-reactor (profils=tck,${profile})"
mkdir -p "${TCK_DIR}/target"

# ravel-tck est in-reactor, activé par le profil Maven `tck` (harmonisation TCK,
# même pattern que les runners vidocq-runtime-tck-*, cf. CLAUDE.md).

set +e
( cd "${ROOT_DIR}" && mvn -ntp -P"tck,${profile}" -pl ravel-tck test "$@" ) \
    | tee "${REPORT_FILE}.raw"
status=$?
set -e

# Résumé : extraire les lignes "Tests run:" finales par module
echo "==> Génération du rapport : ${REPORT_FILE}"
{
    echo "# Ravel TCK report"
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


