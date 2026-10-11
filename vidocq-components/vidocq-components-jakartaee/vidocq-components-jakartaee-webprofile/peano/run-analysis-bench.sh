#!/usr/bin/env bash
set -euo pipefail

analysis_root="$(cd "$(dirname "$0")" && pwd)"
analysis_modules="$analysis_root/peano-bench/target/classes:$analysis_root/peano-bench/target/module-path"
analysis_sources="$analysis_root/peano-bench/src/analysis/java"
analysis_m2="${PEANO_ANALYSIS_M2:-$HOME/.m2/repository}"
analysis_processor="$analysis_m2/org/openjdk/jmh/jmh-generator-annprocess/1.37/jmh-generator-annprocess-1.37.jar:$analysis_m2/org/openjdk/jmh/jmh-core/1.37/jmh-core-1.37.jar"

if [[ ! -d "$analysis_root/peano-bench/target/module-path" ]]; then
    echo 'Build the bench profile before running component diagnostics.' >&2
    exit 1
fi
analysis_output="$(mktemp -d "$analysis_root/peano-bench/target/analysis.XXXXXX")"
trap 'rm -rf "$analysis_output"' EXIT

# Build-only module patch: no shipped module descriptor or application export changes.
javac --module-path "$analysis_modules" --add-modules jmh.core \
    --patch-module "io.vidocq.peano.core=$analysis_sources" \
    --add-reads io.vidocq.peano.core=jmh.core \
    -processorpath "$analysis_processor" \
    -processor org.openjdk.jmh.generators.BenchmarkProcessor \
    -d "$analysis_output" -s "$analysis_output/generated" \
    "$analysis_sources/io/vidocq/peano/core/internal/CreationAnalysisBenchmark.java"

analysis_args="--module-path \"$analysis_modules\" --add-modules ALL-MODULE-PATH --patch-module io.vidocq.peano.core=\"$analysis_output\" --add-reads io.vidocq.peano.core=jmh.core --add-exports io.vidocq.peano.core/io.vidocq.peano.core.internal=jmh.core --add-exports io.vidocq.peano.core/io.vidocq.peano.core.internal.jmh_generated=jmh.core -Dpeano.reflection=allow"
java --module-path "$analysis_modules" --add-modules ALL-MODULE-PATH \
    --patch-module "io.vidocq.peano.core=$analysis_output" \
    --add-reads io.vidocq.peano.core=jmh.core \
    --add-exports io.vidocq.peano.core/io.vidocq.peano.core.internal=jmh.core \
    --add-exports io.vidocq.peano.core/io.vidocq.peano.core.internal.jmh_generated=jmh.core \
    org.openjdk.jmh.Main 'io.vidocq.peano.core.internal.CreationAnalysisBenchmark.*' \
    -foe true -jvmArgs "$analysis_args" "$@"
