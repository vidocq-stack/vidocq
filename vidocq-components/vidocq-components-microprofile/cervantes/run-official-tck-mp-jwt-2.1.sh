#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# run-official-tck-mp-jwt-2.1.sh
#
# Runs the official MicroProfile JWT 2.1 TCK for Cervantes.
#
# cervantes-tck is in-reactor behind the `tck` Maven profile (TCK harmonisation):
# a plain `mvn install` never builds it; this script activates it with
# `-P"tck,<smoke|tck-official>" -pl cervantes-tck test` from the repo root.
#
# Usage:
#   ./run-official-tck-mp-jwt-2.1.sh             # smoke test (default)
#   ./run-official-tck-mp-jwt-2.1.sh all          # full TCK suite
#   ./run-official-tck-mp-jwt-2.1.sh -Dtest=Foo   # targeted test
#
# Prerequisites:
#   - Java 25 + Maven 3.9.16 (sdk env in the cervantes/ directory)
#   - The cervantes reactor installed locally:
#       PATH="$HOME/.sdkman/candidates/maven/3.9.16/bin:$PATH" ./mvnw -ntp install -DskipTests
#   - The TCK artifacts on Maven Central (downloaded automatically):
#       org.eclipse.microprofile.jwt:microprofile-jwt-auth-tck:2.1
#       org.eclipse.microprofile.jwt:microprofile-jwt-auth-tck:2.1:tests
# ---------------------------------------------------------------------------
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$SCRIPT_DIR"
TCK_DIR="$ROOT_DIR/cervantes-tck"

# Force Maven 3.9.16
export PATH="$HOME/.sdkman/candidates/maven/3.9.16/bin:$PATH"

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

case "${1:-}" in
    all)
        echo "==> Running FULL MP JWT 2.1 TCK (tck-official profile)..."
        ( cd "${ROOT_DIR}" && mvn -ntp -P"tck,tck-official" -pl cervantes-tck test )
        ;;
    -Dtest=*)
        TEST_ARG="$1"
        echo "==> Running targeted test: $TEST_ARG"
        ( cd "${ROOT_DIR}" && mvn -ntp -P"tck,tck-official" -pl cervantes-tck test "$TEST_ARG" )
        ;;
    "")
        echo "==> Running SMOKE test (smoke profile)..."
        ( cd "${ROOT_DIR}" && mvn -ntp -P"tck,smoke" -pl cervantes-tck test )
        ;;
    *)
        echo "==> Running with extra args: $*"
        ( cd "${ROOT_DIR}" && mvn -ntp -P"tck,tck-official" -pl cervantes-tck test "$@" )
        ;;
esac
