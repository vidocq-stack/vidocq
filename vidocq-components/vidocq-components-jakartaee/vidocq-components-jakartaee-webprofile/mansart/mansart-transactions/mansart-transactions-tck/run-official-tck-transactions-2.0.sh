#!/usr/bin/env bash
# Runner for the official Jakarta Transactions 2.0 TCK against the Mansart provider.
# See README.md for the complete prerequisites (TCK install, M2 setup, …).
#
# Usage :
#   ./run-official-tck-transactions-2.0.sh                # smoke (default profile)
#   ./run-official-tck-transactions-2.0.sh all            # full suite
#   ./run-official-tck-transactions-2.0.sh -Dtest=NomDuTest

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

# The TCK artefact (jakarta.transaction:jakarta.transaction-tck:2.0.x) is non-public — install
# it locally first via:
#   mvn install:install-file -Dfile=jakarta.transaction-tck-2.0.1.jar \
#        -DgroupId=jakarta.transaction -DartifactId=jakarta.transaction-tck \
#        -Dversion=2.0.1 -Dpackaging=jar
# Maven will fetch transitives from Central when the `tck-run` profile is active.

if [[ "${1:-}" == "all" ]]; then
    shift
    exec mvn -B -ntp -Ptck-run test "$@"
fi

exec mvn -B -ntp -Ptck-run test "$@"
