#!/usr/bin/env bash
# Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
# SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
#
# Official Jakarta Tags tests (jakarta.tck:tags-tck from Maven Central) on Ibarra + Foy + Peano.
# The official distribution zip is read for one file only, the Derby DDL of the sql tests,
# extracted into bodoni-tck/target/ after a SHA-256 check. Run from this checkout after sdk env.
#
#   ./run-official-tck-tags-3.0.sh [smoke|all|-Dtest=...] [--no-prepare] [--no-install] [Maven arguments...]
#
# TAGS_TCK_ZIP          a local copy of the distribution zip, used instead of the download
# BODONI_M2_REPOSITORY  the local repository --no-install checks (default: $HOME/.m2/repository)
set -euo pipefail

usage="Usage: ./run-official-tck-tags-3.0.sh [smoke|all|-Dtest=...] [--no-prepare] [--no-install] [Maven arguments...]"
zip_url='https://download.eclipse.org/jakartaee/tags/3.0/jakarta-tags-tck-3.0.0.zip'
zip_sha256='996c9abc6561134cea986107da0648c2a33277d2f765475994ff9b16da70cb5a'
distribution_dir='bodoni-tck/target/tags-tck-distribution'
ddl_entry='tags-tck/sql/derby/derby.ddl.jstl.sql'
ddl="$distribution_dir/$ddl_entry"
smoke_selection='-Dtest=com.sun.ts.tests.jstl.spec.core.general.outtag.JSTLClientIT,com.sun.ts.tests.jstl.spec.etu.functions.JSTLClientIT,com.sun.ts.tests.jstl.spec.etu.uri.JSTLClientIT,com.sun.ts.tests.jstl.spec.fmt.i18n.bundle.JSTLClientIT,com.sun.ts.tests.jstl.spec.xml.xmlcore.xout.JSTLClientIT,com.sun.ts.tests.jstl.spec.sql.result.JSTLClientIT'

prepare=true
install=true
selection="$smoke_selection"
extra=()
for arg in "$@"; do
    case "$arg" in
        -h|--help) echo "$usage"; exit 0 ;;
        smoke) selection="$smoke_selection" ;;
        all|--all) selection='-Dtest=com.sun.ts.tests.jstl.**.*IT' ;;
        --no-prepare) prepare=false ;;
        --no-install) install=false ;;
        -Dtest=*) selection="$arg" ;;
        -*) extra+=("$arg") ;;
        *) echo "Unknown mode: $arg" >&2; echo "$usage" >&2; exit 2 ;;
    esac
done

cd "$(dirname "$0")"
if [[ ! -f bodoni-tck/pom.xml || ! -x ./mvnw ]]; then
    echo "Run this script from the Bodoni checkout root." >&2
    exit 2
fi

java_bin="java"
if [[ -n "${JAVA_HOME:-}" ]]; then
    java_bin="$JAVA_HOME/bin/java"
fi
java_version="$("$java_bin" -version 2>&1 || true)"
if [[ ! "$java_version" =~ version\ \"25[.\"] ]]; then
    echo "Select Java 25 with sdk env before running the official TCK." >&2
    exit 2
fi

# --no-install runs against the snapshots already in the local repository: refuse stale ones.
check_installed_snapshots() {
    local repository="${BODONI_M2_REPOSITORY:-$HOME/.m2/repository}"
    local version
    version="$(awk '/<artifactId>bodoni-parent<\/artifactId>/ { found = 1 }
        found && /<version>/ { gsub(/.*<version>|<\/version>.*/, ""); print; exit }' pom.xml)"
    if [[ -z "$version" ]]; then
        echo "Cannot read the bodoni-parent version from pom.xml." >&2
        exit 2
    fi
    local module jar newer
    for module in bodoni-core bodoni-peano; do
        jar="$repository/io/vidocq/bodoni/$module/$version/$module-$version.jar"
        if [[ ! -f "$jar" ]] \
            || ! newer="$(find "$module/src/main" "$module/pom.xml" pom.xml -newer "$jar" 2>/dev/null)" \
            || [[ -n "$newer" ]]; then
            echo "$module: installed snapshot is missing or older than its sources; run without --no-install" >&2
            exit 2
        fi
    done
}

sha256_of() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{ print $1 }'
    else
        sha256sum "$1" | awk '{ print $1 }'
    fi
}

# Fetches (or takes TAGS_TCK_ZIP), checks the pinned SHA-256, extracts the Derby DDL only.
prepare_distribution() {
    if ! command -v shasum >/dev/null 2>&1 && ! command -v sha256sum >/dev/null 2>&1; then
        echo "Neither shasum nor sha256sum is available to check the distribution." >&2
        exit 2
    fi
    mkdir -p "$distribution_dir"
    local zip="${TAGS_TCK_ZIP:-$distribution_dir/jakarta-tags-tck-3.0.0.zip}"
    local downloaded=false
    if [[ ! -f "$zip" ]]; then
        if [[ -n "${TAGS_TCK_ZIP:-}" ]]; then
            echo "TAGS_TCK_ZIP does not identify an existing distribution archive: $zip" >&2
            exit 2
        fi
        curl --fail --location --silent --show-error --output "$zip.part" "$zip_url"
        mv "$zip.part" "$zip"
        downloaded=true
    fi
    if [[ "$(sha256_of "$zip")" != "$zip_sha256" ]]; then
        echo "Distribution checksum mismatch: $zip" >&2
        if "$downloaded"; then rm -f "$zip"; fi
        exit 1
    fi
    unzip -q -o "$zip" "$ddl_entry" -d "$distribution_dir"
}

if "$install"; then
    ./mvnw -ntp clean install
else
    check_installed_snapshots
fi
if "$prepare"; then prepare_distribution; fi
if [[ ! -f "$ddl" ]]; then
    echo "The official Derby DDL is missing; run without --no-prepare." >&2
    exit 2
fi

# Clear old reports so a partial selection cannot be mistaken for a full run. No module clean:
# target/tags-tck-distribution must survive.
rm -rf bodoni-tck/target/surefire-reports bodoni-tck/target/pages-deployments
./mvnw -ntp -Ptck -pl bodoni-tck test
# Bash 3.2 (macOS) treats an empty "${extra[@]}" as unbound under set -u.
if ((${#extra[@]})); then
    ./mvnw -ntp -Ptck,tck-official -pl bodoni-tck test "$selection" "${extra[@]}"
else
    ./mvnw -ntp -Ptck,tck-official -pl bodoni-tck test "$selection"
fi
