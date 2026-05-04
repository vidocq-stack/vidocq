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

# Verify the TCK is installed in the local M2.
TCK_JAR="$HOME/.m2/repository/jakarta/data/jakarta-data-tck/1.0.1/jakarta-data-tck-1.0.1.jar"
if [ ! -f "$TCK_JAR" ]; then
    cat <<EOF >&2
[ERROR] jakarta.data:jakarta-data-tck:1.0.1 is not installed in your local Maven repo.

Expected at: $TCK_JAR

Install procedure:
  1. Download jakarta-data-tck-1.0.1.zip from
     https://download.eclipse.org/jakartaee/data/1.0/
  2. Unzip and run:
       mvn install:install-file \\
           -Dfile=artifacts/jakarta-data-tck-1.0.1.jar \\
           -DgroupId=jakarta.data \\
           -DartifactId=jakarta-data-tck \\
           -Dversion=1.0.1 \\
           -Dpackaging=jar

See README.md > "Install the TCK artefacts" for full details.
EOF
    exit 2
fi

# Verify the Mansart reactor is installed.
if [ ! -f "$HOME/.m2/repository/io/vidocq/mansart/mansart-data-core/1.0.0-SNAPSHOT/mansart-data-core-1.0.0-SNAPSHOT.jar" ]; then
    echo "[INFO] Mansart reactor not yet installed in local M2 — running 'mvn install -DskipTests' from ../"
    (cd .. && mvn -ntp install -DskipTests)
fi

case "${1:-}" in
    --all|all)
        # Full suite — every TCK class
        shift || true
        mvn -ntp test -DfailIfNoTests=false "$@"
        ;;
    -Dtest=*)
        # Single test or pattern
        mvn -ntp test "$@"
        ;;
    "")
        # Default: smoke subset (when one is documented). For now runs the suite
        # configured in src/test/resources/tck-suite.xml.
        mvn -ntp test
        ;;
    *)
        # Forward any other args (e.g. -Pprofile=…) to Maven directly
        mvn -ntp test "$@"
        ;;
esac
