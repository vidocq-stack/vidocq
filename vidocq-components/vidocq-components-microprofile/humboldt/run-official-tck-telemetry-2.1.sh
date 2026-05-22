#!/bin/bash
set -e

# ==============================================================================
# Script de lancement du TCK officiel MicroProfile Telemetry 2.1 — Humboldt
# ==============================================================================
#
# Stack : TestNG + Arquillian + ShrinkWrap (cf. humboldt-tck/README.md)
#
# Coordonnées TCK confirmées (publiques Maven Central, M7.1 audit 2026-05-21) :
#   org.eclipse.microprofile.telemetry:microprofile-telemetry-tracing-tck:2.1
#   org.eclipse.microprofile.telemetry:microprofile-telemetry-metrics-tck:2.1
#   org.eclipse.microprofile.telemetry:microprofile-telemetry-logs-tck:2.1
#
# Utilisation :
#   ./run-official-tck-telemetry-2.1.sh                       # smoke
#   ./run-official-tck-telemetry-2.1.sh all                   # suite complète (M7c+)
#   ./run-official-tck-telemetry-2.1.sh -Dtest=BasicAppTest   # test ciblé
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Utilise Maven Wrapper (Maven 4) si présent, sinon fallback sur mvn système.
if [[ -x "$SCRIPT_DIR/mvnw" ]]; then
    MVN_CMD=("$SCRIPT_DIR/mvnw" "-ntp")
else
    MVN_CMD=("mvn")
fi

# Filtrage de l'argument "all" ou "--all"
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
echo " Étape 1 — Install reactor Humboldt en M2   "
echo "============================================"
"${MVN_CMD[@]}" install -DskipTests

echo ""
echo "============================================"
echo " Étape 2 — Lancement TCK MP Telemetry 2.1   "
echo "============================================"

cd humboldt-tck

if $USE_ALL; then
    "${MVN_CMD[@]}" -f pom.xml -Ptck-official verify "${MVN_ARGS[@]}"
else
    # Smoke : juste le test scaffold maison (HumboldtTckSmokeTest)
    "${MVN_CMD[@]}" -f pom.xml test "${MVN_ARGS[@]}"
fi
