#!/bin/bash
set -e

# ==============================================================================
# Script de lancement du TCK officiel Jakarta RESTful Web Services 4.0 — Cassini
# ==============================================================================
#
# Prérequis :
# 1. Avoir installé localement les artifacts du TCK officiel (non-publics) :
#    - jakarta.tck:jakarta-restful-ws-tck:4.0.1
#    Cf. cassini-tck/README.md
#
# Utilisation :
#   ./run-official-tck-restful-4.0.sh                          # smoke test
#   ./run-official-tck-restful-4.0.sh all                      # suite complète
#   ./run-official-tck-restful-4.0.sh -Dtest=ResourceTests     # classe ciblée
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Every Maven call reads and writes a local repository private to this checkout, above the shared
# ~/.m2/repository it only reads: an install from a branch never shadows the published snapshot every
# other build of the machine resolves (BUG-20261123-57). TCK_LOCAL_REPOSITORY moves it.
LOCAL_REPOSITORY="${TCK_LOCAL_REPOSITORY:-$SCRIPT_DIR/.tck-repository}"
REPOSITORY_ARGS=("-Dmaven.repo.local=$LOCAL_REPOSITORY" "-Dmaven.repo.local.tail=$HOME/.m2/repository")

# Filtrage de l'argument "all"
MVN_ARGS=()
USE_ALL=false
for arg in "$@"; do
    if [[ "$arg" == "all" || "$arg" == "--all" ]]; then
        USE_ALL=true
    else
        MVN_ARGS+=("$arg")
    fi
done

# Step 0 — EFTL TCK bundle. The Maven Central artifact lacks the signature-test resources
# (sig-test.map, sig-test-pkg-list.txt, jakarta.ws.rs.sig_4.0.0); the tck-official profile
# copies them from the EFTL jar so JAXRSSigTestIT runs. Downloaded once into target/eftl,
# SHA-256 checked against the value published in vidocq/CERTIFICATION.md. Never committed.
EFTL_ZIP_URL="https://download.eclipse.org/jakartaee/restful-ws/4.0/jakarta-restful-ws-tck-4.0.1.zip"
EFTL_ZIP_SHA256="b6290c1b5b3d2fdd9cc700a999243492a7e27b94a9b6af1974ff4dc5bfbf98f2"
EFTL_DIR="cassini-tck/target/eftl"
EFTL_JAR="$EFTL_DIR/restful-ws-tck/artifacts/jakarta-restful-ws-tck-4.0.1.jar"
if [[ ! -f "$EFTL_JAR" ]]; then
    echo "======================================="
    echo " Step 0 — Fetch the EFTL TCK bundle    "
    echo "======================================="
    mkdir -p "$EFTL_DIR"
    curl -fsSL -o "$EFTL_DIR/tck.zip" "$EFTL_ZIP_URL"
    actual=$(shasum -a 256 "$EFTL_DIR/tck.zip" | cut -d' ' -f1)
    if [[ "$actual" != "$EFTL_ZIP_SHA256" ]]; then
        echo "SHA-256 mismatch for $EFTL_ZIP_URL: $actual" >&2
        exit 1
    fi
    unzip -q -o -d "$EFTL_DIR" "$EFTL_DIR/tck.zip" 'restful-ws-tck/artifacts/jakarta-restful-ws-tck-4.0.1.jar'
    echo "EFTL jar ready: $EFTL_JAR"
fi

echo "======================================="
echo " Step 1 — Install the reactor         "
echo "======================================="
echo "into $LOCAL_REPOSITORY"
mvn -q "${REPOSITORY_ARGS[@]}" install -DskipTests

echo ""
echo "======================================="
echo " Étape 2 — Lancement du TCK REST 4.0   "
echo "======================================="

# cassini-tck est in-reactor, activé par le profil Maven `tck`
# (harmonisation TCK, même pattern que les runners vidocq-runtime-tck-*).
if $USE_ALL; then
    mvn "${REPOSITORY_ARGS[@]}" -P"tck,tck-official" -pl cassini-tck verify "${MVN_ARGS[@]}"
else
    # Smoke : juste le test harness maison
    mvn "${REPOSITORY_ARGS[@]}" -P"tck,tck-official" -pl cassini-tck test -Dtest=CassiniHarnessSmokeTest "${MVN_ARGS[@]}"
fi
