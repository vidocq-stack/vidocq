#!/usr/bin/env bash
# Runner for the official Jakarta Persistence 3.2 TCK against the Mansart provider.
# See README.md for the complete prerequisites (TCK install, M2 setup, …).

set -euo pipefail

cd "$(dirname "$0")"

# Activate the toolchain pinned by ../.sdkmanrc — Java 17 + Maven 4
if [ -z "${SDKMAN_DIR:-}" ] && [ -d "$HOME/.sdkman" ]; then
    SDKMAN_DIR="$HOME/.sdkman"
fi
if [ -n "${SDKMAN_DIR:-}" ] && [ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]; then
    # shellcheck disable=SC1091
    source "$SDKMAN_DIR/bin/sdkman-init.sh"
    (cd .. && sdk env > /dev/null 2>&1) || true
fi

# Verify the Mansart reactor is installed (standalone TCK needs it for Arquillian deps).
if [ ! -f "$HOME/.m2/repository/io/vidocq/mansart/mansart-root/$(sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' ../pom.xml 2>/dev/null || echo unknown)/mansart-root-$(sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' ../pom.xml 2>/dev/null || echo unknown).jar" ]; then
    echo "[INFO] Mansart reactor not yet installed in local M2 — running './scripts/build.sh install -DskipTests' from ../"
    (cd .. && ./scripts/build.sh install -DskipTests)
fi

case "${1:-}" in
    --smoke)
        # Mansart-only smoke harness (no TCK suite)
        ./scripts/build.sh test
        ;;
    --full|--all-suites)
        # Full TCK suite
        shift || true
        ./scripts/build.sh -Ptck-run test -DfailIfNoTests=false "$@"
        ;;
    --all|all|"")
        # Default: run the official Jakarta Persistence 3.2 TCK standalone suite.
        ./scripts/build.sh -Ptck-run test -DfailIfNoTests=false
        ;;
    -Dtest=*)
        ./scripts/build.sh -Ptck-run test "$@"
        ;;
    *)
        ./scripts/build.sh -Ptck-run test "$@"
        ;;
esac
