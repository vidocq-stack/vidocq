#!/usr/bin/env bash
set -euo pipefail

retention_root="$(cd "$(dirname "$0")" && pwd)"
retention_modules="$retention_root/peano-bench/target/classes:$retention_root/peano-bench/target/module-path"
retention_sources="$retention_root/peano-bench/src/analysis/java"
retention_output="$(mktemp -d "$retention_root/peano-bench/target/retention.XXXXXX")"
trap 'rm -rf "$retention_output"' EXIT

# Diagnostic read edge only; the published core descriptor does not require management.
javac --module-path "$retention_modules" --add-modules java.management \
    --patch-module "io.vidocq.peano.core=$retention_sources" \
    --add-reads io.vidocq.peano.core=java.management -d "$retention_output" \
    "$retention_sources/io/vidocq/peano/core/internal/CacheRetentionProbe.java"
java -Xms128m -Xmx128m -XX:+UseSerialGC --module-path "$retention_modules" \
    --add-modules java.management --patch-module "io.vidocq.peano.core=$retention_output" \
    --add-reads io.vidocq.peano.core=java.management \
    --module io.vidocq.peano.core/io.vidocq.peano.core.internal.CacheRetentionProbe "${1:-128}"
