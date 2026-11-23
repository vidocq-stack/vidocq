#!/usr/bin/env bash
# Precompile official Pages WARs before deploying them on modular Foy + Ibarra.
set -euo pipefail
cd "$(dirname "$0")"

prepare=true
install=true
smoke_selection='-Dtest=ee.jakarta.tck.pages.api.jakarta_servlet.jsp.jspfactory.URLClientIT,ee.jakarta.tck.pages.api.jakarta_servlet.jsp.jspengineinfo.URLClientIT,ee.jakarta.tck.pages.spec.configuration.charsequence.URLClientIT,ee.jakarta.tck.pages.spec.configuration.elevaluation.URLClientIT,ee.jakarta.tck.pages.spec.configuration.encoding.URLClientIT'
selection="$smoke_selection"
extra=()
for arg in "$@"; do
    case "$arg" in
        smoke) selection="$smoke_selection" ;;
        all|--all) selection='-Dtest=ee.jakarta.tck.pages.**.*IT' ;;
        --no-prepare) prepare=false ;;
        --no-install) install=false ;;
        -Dtest=*) selection="$arg" ;;
        *) extra+=("$arg") ;;
    esac
done

# Every Maven call, prepare-tck.py's too, reads and writes a local repository private to this
# checkout, above the shared ~/.m2/repository it only reads: an install from a branch never shadows
# the published snapshot every other build of the machine resolves (BUG-20261123-57).
# TCK_LOCAL_REPOSITORY moves it.
export TCK_LOCAL_REPOSITORY="${TCK_LOCAL_REPOSITORY:-$PWD/.tck-repository}"
maven=(./mvnw -ntp "-Dmaven.repo.local=$TCK_LOCAL_REPOSITORY" "-Dmaven.repo.local.tail=$HOME/.m2/repository")

if "$prepare"; then python3 ibarra-tck/prepare-tck.py; fi
if "$install"; then "${maven[@]}" clean install; fi
"${maven[@]}" -Ptck -pl ibarra-tck clean test
if ((${#extra[@]})); then
    "${maven[@]}" -Ptck,tck-official -pl ibarra-tck clean test "$selection" "${extra[@]}"
else
    "${maven[@]}" -Ptck,tck-official -pl ibarra-tck clean test "$selection"
fi
