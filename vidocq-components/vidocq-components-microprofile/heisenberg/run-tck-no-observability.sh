#!/usr/bin/env bash
# shellcheck shell=bash
#
# Wrapper du runner TCK officiel avec exclusions observabilité préconfigurées.
#
# Exclusions appliquées :
#   - metrics / metric
#   - telemetry
#   - opentelemetry
#
# Exemples :
#   ./run-tck-no-observability.sh
#   ./run-tck-no-observability.sh all
#   ./run-tck-no-observability.sh -Dtest=RetryTest
#
# Si -Dsurefire.excludes=... est déjà fourni, les patterns ci-dessous sont fusionnés.
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_SCRIPT="${ROOT_DIR}/run-official-tck-mp-fault-tolerance-4.1.sh"

DEFAULT_EXCLUDES='**/metric/**,**/metrics/**,**/*Metric*Test.class,**/telemetry/**,**/*Telemetry*Test.class,**/opentelemetry/**,**/*OpenTelemetry*Test.class'

if [[ ! -x "${BASE_SCRIPT}" ]]; then
    echo "Script de base introuvable ou non exécutable : ${BASE_SCRIPT}" >&2
    exit 66
fi

args=()
merged_excludes="${DEFAULT_EXCLUDES}"
user_defined_excludes=false

for arg in "$@"; do
    if [[ "${arg}" == -Dsurefire.excludes=* ]]; then
        user_defined_excludes=true
        user_value="${arg#-Dsurefire.excludes=}"
        if [[ -n "${user_value}" ]]; then
            merged_excludes="${DEFAULT_EXCLUDES},${user_value}"
        fi
    else
        args+=("${arg}")
    fi
done

if [[ "${user_defined_excludes}" == true ]]; then
    echo "==> Fusion des exclusions observabilité avec les exclusions Surefire fournies par l'utilisateur"
else
    echo "==> Exclusions observabilité activées (metrics / telemetry / opentelemetry)"
fi

if [[ ${#args[@]} -eq 0 ]]; then
    exec "${BASE_SCRIPT}" smoke "-Dsurefire.excludes=${merged_excludes}"
fi

exec "${BASE_SCRIPT}" "${args[@]}" "-Dsurefire.excludes=${merged_excludes}"

