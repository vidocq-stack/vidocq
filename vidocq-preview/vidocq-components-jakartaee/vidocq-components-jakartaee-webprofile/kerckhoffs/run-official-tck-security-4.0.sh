#!/usr/bin/env bash
# Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
# SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
#
# Official Jakarta Security 4.0 TCK on Kerckhoffs (PLAN.md section 7, milestone K0).
#
# The TCK is published as SOURCES only. This script downloads the distribution (SHA-256 pinned),
# builds the `common` module and the selected `app-*` modules from those sources with the TCK's own
# Maven build, and runs their Arquillian tests with the Kerckhoffs container of `kerckhoffs-tck`
# instead of the GlassFish container the TCK selects by default. The TCK build is third-party code:
# it runs with this repository's Maven wrapper and is never published or installed over ours.
#
# Two disclosed deviations from an unmodified run, both on the command line, none in the sources:
#   - the TCK's default `glassfish-ci-managed` profile is deactivated (-P'!glassfish-ci-managed');
#   - the class path of `kerckhoffs-tck` (container, Foy, Chappe, Vauban) is added to failsafe
#     (-Dmaven.test.additionalClasspath) and Arquillian is pointed at our container
#     (-Darquillian.launch, -Darquillian.xml through argLine);
#   - the `sigtest.api.*` properties, which that profile defines and the (unbuilt) signature test
#     module needs to be read, are set to the Security API coordinates.
#
# Usage: run-official-tck-security-4.0.sh [app-mem ...] [-- extra Maven args]
#   with no application, runs the smoke set: the applications that are green (kerckhoffs-tck/BASELINE.md).
# Environment: MVN_EXTRA  extra Maven arguments (for example -s /path/settings.xml)
#              TCK_LOCAL_REPOSITORY  the local repository every Maven call reads and writes, above the shared
#                                    ~/.m2/repository it only reads (default: .tck-repository here)
set -euo pipefail

cd "$(dirname "$0")"
root="$PWD"

# Every Maven call reads and writes a local repository private to this checkout, above the shared
# ~/.m2/repository it only reads: an install from a branch never shadows the published snapshot every
# other build of the machine resolves (BUG-20261123-57). TCK_LOCAL_REPOSITORY moves it.
LOCAL_REPOSITORY="${TCK_LOCAL_REPOSITORY:-$root/.tck-repository}"
REPOSITORY_ARGS=("-Dmaven.repo.local=$LOCAL_REPOSITORY" "-Dmaven.repo.local.tail=$HOME/.m2/repository")

zip_url='https://download.eclipse.org/jakartaee/security/4.0/jakarta-security-tck-4.0.0.zip'
zip_sha256='e21a3d8251bf954c99a3f9a5f71911696509a1ed40c23673760785a3b88e4172'
work="$root/kerckhoffs-tck/target/security-tck"
zip="$work/jakarta-security-tck-4.0.0.zip"
dist="$work/distribution"

apps=()
extra=()
while [ $# -gt 0 ]; do
    case "$1" in
        --) shift; extra=("$@"); break ;;
        app-*) apps+=("$1") ;;
        *) echo "error: unexpected argument '$1'" >&2; exit 2 ;;
    esac
    shift
done
[ ${#apps[@]} -gt 0 ] || apps=(app-mem app-mem-basic app-custom app-custom-session app-multiple-store app-multiple-store-backup app-securitycontext-auth app-custom-authentication-mechanism-handler app-custom-authentication-mechanism-handler2 app-custom-rememberme app-mem-policy app-mem-policy2)

# shellcheck disable=SC2206
mvn_extra=(${MVN_EXTRA:-})
# Bash 3.2 (macOS) treats an empty "${a[@]}" as unbound under set -u.
mvn=(./mvnw -ntp "${REPOSITORY_ARGS[@]}" ${mvn_extra[@]+"${mvn_extra[@]}"})

echo "[1/4] Kerckhoffs: install into $LOCAL_REPOSITORY, then the class path of the container"
"${mvn[@]}" install -DskipTests
mkdir -p "$work"
cp_file="$work/container.classpath"
"${mvn[@]}" -Ptck -pl kerckhoffs-tck test-compile dependency:build-classpath \
    -Dmdep.includeScope=test -Dmdep.outputFile="$cp_file"

# JUnit 5 must stay off the TCK's class path: the TCK is JUnit 4 and failsafe would pick the platform provider.
additional="$root/kerckhoffs-tck/target/test-classes"
IFS=':' read -r -a entries < "$cp_file" || true   # the file has no final newline: read returns 1
for entry in "${entries[@]}"; do
    case "$entry" in
        *junit-jupiter*|*junit-platform*|*opentest4j*|*apiguardian*) ;;
        *) additional="$additional,$entry" ;;
    esac
done

echo "[2/4] The TCK distribution, SHA-256 pinned"
if [ ! -f "$zip" ]; then
    curl --fail --silent --show-error --location --output "$zip" "$zip_url"
fi
actual="$(shasum -a 256 "$zip" | cut -d' ' -f1)"
if [ "$actual" != "$zip_sha256" ]; then
    echo "error: $zip has SHA-256 $actual, expected $zip_sha256" >&2
    rm -f "$zip"
    exit 1
fi
rm -rf "$dist"
mkdir -p "$dist"
unzip -q "$zip" -d "$dist"
tck_dir="$(find "$dist" -maxdepth 2 -type d -name tck | head -1)"
[ -n "$tck_dir" ] || { echo "error: no tck/ directory in the distribution" >&2; exit 1; }

echo "[3/4] The TCK build of: common ${apps[*]}"
modules="common"
for app in "${apps[@]}"; do modules="$modules,$app"; done
arq_xml="$root/kerckhoffs-tck/src/test/resources/arquillian.xml"

echo "[4/4] Running the Arquillian tests on the Kerckhoffs container"
(
    cd "$tck_dir"
    "$root/mvnw" -ntp "${REPOSITORY_ARGS[@]}" ${mvn_extra[@]+"${mvn_extra[@]}"} -P'!glassfish-ci-managed' -pl "$modules" verify \
        -Dsigtest.api.groupId=jakarta.security \
        -Dsigtest.api.artifactId=jakarta.security.enterprise-api \
        -Dsigtest.api.version=4.0.0 \
        -Dmaven.test.additionalClasspath="$additional" \
        -DargLine="-Darquillian.launch=kerckhoffs -Darquillian.xml=$arq_xml" \
        --fail-at-end ${extra[@]+"${extra[@]}"}
)

# Reached only when every selected application passed: a failing run exits non-zero above.
echo "Reports: $tck_dir/<app>/target/failsafe-reports. Record the result in kerckhoffs-tck/BASELINE.md."
echo "A local run of selected applications is a measurement, not a certification."
