#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# run-official-tck-mp-jwt-2.2.sh
#
# Runs the official MicroProfile JWT 2.2 TCK for Cervantes.
#
# cervantes-tck is in-reactor behind the `tck` Maven profile (TCK harmonisation):
# a plain `mvn install` never builds it; this script activates it with
# `-P"tck,<smoke|tck-official>" -pl cervantes-tck clean test` from the repo root.
#
# Usage:
#   ./run-official-tck-mp-jwt-2.2.sh             # smoke test (default)
#   ./run-official-tck-mp-jwt-2.2.sh all          # full TCK suite
#   ./run-official-tck-mp-jwt-2.2.sh -Dtest=Foo   # targeted test
#
# Prerequisites:
#   - Java 25 (sdk env in the cervantes/ directory); Maven comes from ./mvnw
#   - The TCK artifacts on Maven Central (downloaded automatically):
#       org.eclipse.microprofile.jwt:microprofile-jwt-auth-tck:2.2
#       org.eclipse.microprofile.jwt:microprofile-jwt-auth-tck:2.2:tests
# ---------------------------------------------------------------------------
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$SCRIPT_DIR"
TCK_DIR="$ROOT_DIR/cervantes-tck"

# Use the Maven wrapper (pinned Maven version).
MVN=("$ROOT_DIR/mvnw" "-ntp")

if [ ! -f "$TCK_DIR/pom.xml" ]; then
    echo "ERROR: $TCK_DIR/pom.xml not found. Run from the cervantes/ directory." >&2
    exit 1
fi

CASSINI_TCK_DIR="$SCRIPT_DIR/../cassini/cassini-tck"

# Install cassini-tck jar into local M2 (contains CassiniTestHarness)
if [ -d "$CASSINI_TCK_DIR" ]; then
    echo "==> Installing cassini-tck into local M2..."
    ( cd "$CASSINI_TCK_DIR/.." && ./mvnw -ntp -q -pl cassini-tck install -DskipTests )
fi

echo "==> Clean install of the cervantes reactor (tests skipped)..."
( cd "$ROOT_DIR" && "${MVN[@]}" clean install -DskipTests )

# Every mode below runs `clean test` on the TCK module: a stale target/ gives false results.

case "${1:-}" in
    all)
        echo "==> Running FULL MP JWT 2.2 TCK (tck-official profile)..."
        ( cd "${ROOT_DIR}" && "${MVN[@]}" -P"tck,tck-official" -pl cervantes-tck clean test )
        ;;
    -Dtest=*)
        TEST_ARG="$1"
        echo "==> Running targeted test: $TEST_ARG"
        ( cd "${ROOT_DIR}" && "${MVN[@]}" -P"tck,tck-official" -pl cervantes-tck clean test "$TEST_ARG" )
        ;;
    "")
        echo "==> Running SMOKE test (smoke profile)..."
        ( cd "${ROOT_DIR}" && "${MVN[@]}" -P"tck,smoke" -pl cervantes-tck clean test )
        ;;
    *)
        echo "==> Running with extra args: $*"
        ( cd "${ROOT_DIR}" && "${MVN[@]}" -P"tck,tck-official" -pl cervantes-tck clean test "$@" )
        ;;
esac
