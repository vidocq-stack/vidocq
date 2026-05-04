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
    --all|all|"")
        # Default M6.3: run the official Jakarta Data 1.0 TCK subset (currently EntityTests).
        # M6.4 will widen this once TCKArchiveProcessor is wired into the deployment.
        mvn -ntp -Ptck-run test -DfailIfNoTests=false
        ;;
    -Dtest=*)
        mvn -ntp -Ptck-run test "$@"
        ;;
    *)
        mvn -ntp -Ptck-run test "$@"
        ;;
esac
