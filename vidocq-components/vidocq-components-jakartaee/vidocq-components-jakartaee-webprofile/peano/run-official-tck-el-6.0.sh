#!/usr/bin/env bash
set -euo pipefail

# Official Jakarta EL 6.0.1 TCK. Run from this checkout after selecting .sdkmanrc.
if [[ ! -f peano-tck/pom.xml || ! -x ./mvnw ]]; then
    echo "Run this script from the Peano checkout root." >&2
    exit 2
fi

mode="${1:-all}"
case "$mode" in
    all) selector=() ;;
    smoke)
        selector=("-Dtest=com.sun.ts.tests.el.spec.literal.ELClientIT,com.sun.ts.tests.el.spec.collectionoperators.ELClientIT,com.sun.ts.tests.signaturetest.el.ELSigTestIT")
        ;;
    -Dtest=*) selector=("$mode") ;;
    *)
        echo "Usage: ./run-official-tck-el-6.0.sh [all|smoke|-Dtest=fully.qualified.Test#method]" >&2
        exit 2
        ;;
esac
if [[ $# -gt 1 ]]; then
    echo "Only one mode or test selector is accepted." >&2
    exit 2
fi

java_bin="java"
if [[ -n "${JAVA_HOME:-}" ]]; then
    java_bin="$JAVA_HOME/bin/java"
fi
java_version="$("$java_bin" -version 2>&1)"
if [[ ! "$java_version" =~ version\ \"25[.\"] ]]; then
    echo "Select Java 25 with sdk env before running the official TCK." >&2
    exit 2
fi

distribution_dir="peano-tck/target/official-distribution"
mkdir -p "$distribution_dir"
distribution_zip="${EL_TCK_ZIP:-$distribution_dir/jakarta-expression-language-tck-6.0.1.zip}"
if [[ ! -f "$distribution_zip" ]]; then
    if [[ -n "${EL_TCK_ZIP:-}" ]]; then
        echo "EL_TCK_ZIP does not identify an existing distribution archive." >&2
        exit 2
    fi
    curl --fail --location --silent --show-error \
        --output "$distribution_zip.part" \
        https://download.eclipse.org/jakartaee/expression-language/6.0/jakarta-expression-language-tck-6.0.1.zip
    mv "$distribution_zip.part" "$distribution_zip"
fi
unzip -q -o "$distribution_zip" \
    "el-tck/artifacts/jakarta-expression-language-tck-6.0.1.jar" \
    -d "$distribution_dir"
./mvnw -N -ntp install:install-file \
    "-Dfile=$distribution_dir/el-tck/artifacts/jakarta-expression-language-tck-6.0.1.jar" \
    -DpomFile=peano-tck/official-artifact.pom
./mvnw -ntp install -DskipTests
# Clear old reports so partial selections cannot be mistaken for a full run.
rm -rf peano-tck/target/surefire-reports
./mvnw -ntp -Ptck,tck-official -pl peano-tck test "${selector[@]}"
