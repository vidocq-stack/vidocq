#!/usr/bin/env bash
# Runner for the official Jakarta Data 1.0 TCK against the Mansart provider.
# See README.md for the complete prerequisites (TCK install, M2 setup, …).

set -euo pipefail

cd "$(dirname "$0")"

# Activate the toolchain pinned by ../.sdkmanrc — Java 25 + Maven 4.0.0-rc-5
if [ -z "${SDKMAN_DIR:-}" ] && [ -d "$HOME/.sdkman" ]; then
    SDKMAN_DIR="$HOME/.sdkman"
fi
if [ -n "${SDKMAN_DIR:-}" ] && [ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]; then
    # shellcheck disable=SC1091
    source "$SDKMAN_DIR/bin/sdkman-init.sh"
    (cd .. && sdk env > /dev/null 2>&1) || true
fi

# jakarta.data:jakarta.data-tck:1.0.1 is on Maven Central since M6.3 — Maven will fetch it
# automatically when the `tck-run` profile is active. No local install needed.

# Verify the Mansart reactor is installed.
if [ ! -f "$HOME/.m2/repository/io/vidocq/mansart/mansart-data-core/1.0.0-SNAPSHOT/mansart-data-core-1.0.0-SNAPSHOT.jar" ]; then
    echo "[INFO] Mansart reactor not yet installed in local M2 — running 'mvn install -DskipTests' from ../"
    (cd .. && mvn -ntp install -DskipTests)
fi

case "${1:-}" in
    --smoke)
        # Mansart-only smoke harness (Vauban + standalone + Arquillian smoke, no TCK suite)
        mvn -ntp test
        ;;
    --pg|--postgres|--postgresql)
        # M6.5 — Run the TCK against PostgreSQL via Testcontainers. Requires Docker on the host.
        # Pulls postgres:17-alpine on first run.
        shift || true
        mvn -ntp -Ptck-run,tck-pg test -DfailIfNoTests=false "$@"
        ;;
    --sig|--signature)
        # M7-26 — Add the SignatureTests subset on top of the entity TCK (H2 by default).
        # Cumulative with --pg via direct mvn invocation: mvn -Ptck-run,tck-pg,tck-sig test
        shift || true
        mvn -ntp -Ptck-run,tck-sig test -DfailIfNoTests=false "$@"
        ;;
    --full|--all-suites)
        # M7-26 — Entity + Signature TCK. Choose H2 (default) or PG via env var:
        # PG=1 ./run-official-tck-data-1.0.sh --full
        shift || true
        if [ "${PG:-0}" = "1" ]; then
            mvn -ntp -Ptck-run,tck-pg,tck-sig test -DfailIfNoTests=false "$@"
        else
            mvn -ntp -Ptck-run,tck-sig test -DfailIfNoTests=false "$@"
        fi
        ;;
    --all|all|"")
        # Default: run the official Jakarta Data 1.0 TCK subset (EntityTests) on H2 in-memory.
        mvn -ntp -Ptck-run test -DfailIfNoTests=false
        ;;
    -Dtest=*)
        mvn -ntp -Ptck-run test "$@"
        ;;
    *)
        mvn -ntp -Ptck-run test "$@"
        ;;
esac
