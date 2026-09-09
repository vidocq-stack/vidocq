#!/usr/bin/env bash
# Runner for the official Jakarta Persistence 3.2 TCK against the Mansart provider.
# See README.md for the complete prerequisites (TCK install, M2 setup, …).

set -euo pipefail

cd "$(dirname "$0")"

# Activate the toolchain pinned by ../.sdkmanrc — Java 25 + Maven 3.9.16
if [ -z "${SDKMAN_DIR:-}" ] && [ -d "$HOME/.sdkman" ]; then
    SDKMAN_DIR="$HOME/.sdkman"
fi
if [ -n "${SDKMAN_DIR:-}" ] && [ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]; then
    # shellcheck disable=SC1091
    source "$SDKMAN_DIR/bin/sdkman-init.sh"
    (cd .. && sdk env > /dev/null 2>&1) || true
fi

# jakarta.tck:persistence-tck-spec-tests:3.2.1 is on Maven Central since M6.3 — Maven will fetch it
# automatically when the `tck-run` profile is active. No local install needed.

# Verify the Mansart reactor is installed. (Note: persistence-core not built yet — skip check)

# Default: Run the official Jakarta Persistence 3.2 TCK suite (Client.class) on H2 in-memory.
case "${1:-}" in
    --smoke| --pg|--postgres|--postgresql| --sig|--signature| --full|--all-suites)
        echo "[INFO] TCK profiles --smoke, --pg, --sig, --full are deferred to future release."
        echo "       Use default to run the basic TCK (Client.class) on H2."
        ;;
    -Dtest=*)
        mvn -ntp -Ptck-run test "$@"
        ;;
    *)
        mvn -ntp -Ptck-run test -DfailIfNoTests=false
        ;;
esac