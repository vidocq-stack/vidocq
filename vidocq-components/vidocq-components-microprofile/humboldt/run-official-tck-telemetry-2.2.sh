#!/bin/bash
set -e

# ==============================================================================
# Runs the official MicroProfile Telemetry 2.2 TCK against Humboldt
# ==============================================================================
#
# Stack: TestNG + Arquillian + ShrinkWrap (see humboldt-tck/README.md)
#
# Official TCK coordinates (public on Maven Central):
#   org.eclipse.microprofile.telemetry:microprofile-telemetry-tracing-tck:2.2-RC3
#   org.eclipse.microprofile.telemetry:microprofile-telemetry-metrics-tck:2.2-RC3
#   org.eclipse.microprofile.telemetry:microprofile-telemetry-logs-tck:2.2-RC3
#
# Usage:
#   ./run-official-tck-telemetry-2.2.sh                       # smoke
#   ./run-official-tck-telemetry-2.2.sh all                   # full suite (M7c+)
#   ./run-official-tck-telemetry-2.2.sh -Dtest=BasicAppTest   # targeted test
#
# humboldt-tck is in-reactor, enabled by the `tck` Maven profile (TCK
# harmonisation, same pattern as the vidocq-runtime-tck-* runners): this script
# is a thin wrapper over `./mvnw -Ptck,<profile> -pl humboldt-tck clean test`.
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Use the Maven wrapper (pinned Maven version) when present, otherwise the system mvn.
if [[ -x "$SCRIPT_DIR/mvnw" ]]; then
    MVN_CMD=("$SCRIPT_DIR/mvnw" "-ntp")
else
    MVN_CMD=("mvn")
fi

# Pick out the "all" / "--all" argument
MVN_ARGS=()
USE_ALL=false
for arg in "$@"; do
    if [[ "$arg" == "all" || "$arg" == "--all" ]]; then
        USE_ALL=true
    else
        MVN_ARGS+=("$arg")
    fi
done

echo "============================================"
echo " Step 1 — Install the Humboldt reactor      "
echo "============================================"
# maven.test.skip=true (instead of -DskipTests): needed since M7c.12 because the
# humboldt-rest test compilation fails (microprofile.rest.client.api is off the module
# path by default in strict Java Modules mode — Maven still runs default-testCompile
# with -DskipTests). The humboldt-rest tests can still be run by hand with
# `mvn test -pl humboldt-rest` once the Java Modules main compilation is disabled.
"${MVN_CMD[@]}" install -Dmaven.test.skip=true

echo ""
echo "============================================"
echo " Step 2 — Run the MP Telemetry 2.2 TCK      "
echo "============================================"

if $USE_ALL; then
    profile="tck-official"
else
    # Smoke: only our own scaffold test (HumboldtTckSmokeTest)
    profile="tck-smoke"
fi

# `clean` so that the TCK harness is always compiled from scratch (a stale target/ gives false results).
( cd "${SCRIPT_DIR}" && "${MVN_CMD[@]}" -P"tck,${profile}" -pl humboldt-tck clean test "${MVN_ARGS[@]}" )
