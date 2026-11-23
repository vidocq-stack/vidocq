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
#   - Every Maven call uses .tck-repository/ here as its local repository, above the
#     shared ~/.m2/repository it only reads; TCK_LOCAL_REPOSITORY moves it
#   - The TCK artifacts on Maven Central (downloaded automatically):
#       org.eclipse.microprofile.jwt:microprofile-jwt-auth-tck:2.2
#       org.eclipse.microprofile.jwt:microprofile-jwt-auth-tck:2.2:tests
# ---------------------------------------------------------------------------
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$SCRIPT_DIR"
TCK_DIR="$ROOT_DIR/cervantes-tck"

# Every Maven call reads and writes a local repository private to this checkout, above the shared
# ~/.m2/repository it only reads: an install from a branch never shadows the published snapshot every
# other build of the machine resolves (BUG-20261123-57). TCK_LOCAL_REPOSITORY moves it.
LOCAL_REPOSITORY="${TCK_LOCAL_REPOSITORY:-$ROOT_DIR/.tck-repository}"
REPOSITORY_ARGS=("-Dmaven.repo.local=$LOCAL_REPOSITORY" "-Dmaven.repo.local.tail=$HOME/.m2/repository")

# Use the Maven wrapper (pinned Maven version).
MVN=("$ROOT_DIR/mvnw" "-ntp" "${REPOSITORY_ARGS[@]}")

if [ ! -f "$TCK_DIR/pom.xml" ]; then
    echo "ERROR: $TCK_DIR/pom.xml not found. Run from the cervantes/ directory." >&2
    exit 1
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
