#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# run-official-tck-mp-jwt-2.1.sh
#
# Lance le TCK officiel MicroProfile JWT 2.1 pour Cervantes.
#
# Usage:
#   ./run-official-tck-mp-jwt-2.1.sh             # smoke test (défaut)
#   ./run-official-tck-mp-jwt-2.1.sh all          # suite TCK complète
#   ./run-official-tck-mp-jwt-2.1.sh -Dtest=Foo   # test ciblé
#
# Prérequis:
#   - Java 25 + Maven 4.0.0-rc-5 (sdk env dans le répertoire cervantes/)
#   - Le reactor cervantes installé en local :
#       PATH="$HOME/.sdkman/candidates/maven/4.0.0-rc-5/bin:$PATH" ./mvnw -ntp install -DskipTests
#   - Les TCK artifacts sur Maven Central (téléchargés automatiquement) :
#       org.eclipse.microprofile.jwt:microprofile-jwt-auth-tck:2.1
#       org.eclipse.microprofile.jwt:microprofile-jwt-auth-tck:2.1:tests
# ---------------------------------------------------------------------------
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TCK_DIR="$SCRIPT_DIR/cervantes-tck"

# Force Maven 4
export PATH="$HOME/.sdkman/candidates/maven/4.0.0-rc-5/bin:$PATH"

if [ ! -f "$TCK_DIR/pom.xml" ]; then
    echo "ERROR: $TCK_DIR/pom.xml not found. Run from the cervantes/ directory." >&2
    exit 1
fi

CASSINI_TCK_DIR="$SCRIPT_DIR/../cassini/cassini-tck"

# Install cassini-tck jar into local M2 (contains CassiniTestHarness)
if [ -d "$CASSINI_TCK_DIR" ]; then
    echo "==> Installing cassini-tck into local M2..."
    cd "$CASSINI_TCK_DIR"
    mvn -ntp install -DskipTests -q
fi

cd "$TCK_DIR"

case "${1:-}" in
    all)
        echo "==> Running FULL MP JWT 2.1 TCK (tck-official profile)..."
        mvn -ntp test -Ptck-official
        ;;
    -Dtest=*)
        TEST_ARG="$1"
        echo "==> Running targeted test: $TEST_ARG"
        mvn -ntp test -Ptck-official "$TEST_ARG"
        ;;
    "")
        echo "==> Running SMOKE test (default profile)..."
        mvn -ntp test
        ;;
    *)
        echo "==> Running with extra args: $*"
        mvn -ntp test -Ptck-official "$@"
        ;;
esac
