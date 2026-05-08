/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.bench;

import org.eclipse.microprofile.config.Config;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Bench JMH §M5 — coût d'une lookup {@code Config.getValue(String, String.class)}
 * sur un cache hot (clé toujours présente) et froid (clé absente → Optional.empty).
 *
 * <p>Comparaison Ravel ↔ Smallrye avec exactement les mêmes propriétés.</p>
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms256m", "-Xmx256m"})
@State(Scope.Benchmark)
public class LookupBenchmark {

    @Param({"RAVEL", "SMALLRYE"})
    public ConfigImpl impl;

    private Config config;
    private String[] hotKeys;
    private int cursor;

    @Setup
    public void setup() {
        this.config = impl.build(BenchSources.SCALARS);
        this.hotKeys = BenchSources.SCALARS.keySet().toArray(String[]::new);
    }

    @Benchmark
    public String hit_String() {
        // Round-robin sur 64 clés pour éviter le constant-folding du JIT.
        String key = hotKeys[(cursor++ & 63)];
        return config.getValue(key, String.class);
    }

    @Benchmark
    public void hit_Optional(Blackhole bh) {
        String key = hotKeys[(cursor++ & 63)];
        bh.consume(config.getOptionalValue(key, String.class));
    }

    @Benchmark
    public Optional<String> miss_Optional() {
        return config.getOptionalValue("bench.missing.key", String.class);
    }
}

