#!/usr/bin/env bash
set -euo pipefail

bench_root="$(cd "$(dirname "$0")" && pwd)"
bench_modules="$bench_root/peano-bench/target/classes:$bench_root/peano-bench/target/module-path"
if [[ ! -d "$bench_root/peano-bench/target/module-path" ]]; then
    echo 'Build first: ./mvnw -ntp -Pbench -pl peano-bench -am package' >&2
    exit 1
fi

# JMH forks launch a class name; resolving the same module graph keeps those forks modular.
exec java --module-path "$bench_modules" --add-modules ALL-MODULE-PATH \
    org.openjdk.jmh.Main \
    -foe true \
    -jvmArgsAppend "--module-path \"$bench_modules\" --add-modules ALL-MODULE-PATH -Dpeano.reflection=allow" \
    "$@"
