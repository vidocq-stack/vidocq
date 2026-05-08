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

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Bench JMH §M5 — coût des converters built-in (Integer, Long, Boolean, Duration)
 * et tableau (§5.4).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms256m", "-Xmx256m"})
@State(Scope.Benchmark)
public class ConversionBenchmark {

    @Param({"RAVEL", "SMALLRYE"})
    public ConfigImpl impl;

    private Config config;

    @Setup
    public void setup() {
        this.config = impl.build(BenchSources.TYPED);
    }

    @Benchmark
    public int integer() {
        return config.getValue("bench.int", Integer.class);
    }

    @Benchmark
    public long lng() {
        return config.getValue("bench.long", Long.class);
    }

    @Benchmark
    public boolean bool() {
        return config.getValue("bench.boolean", Boolean.class);
    }

    @Benchmark
    public Duration duration() {
        return config.getValue("bench.duration", Duration.class);
    }

    @Benchmark
    public String[] stringArray() {
        return config.getValue("bench.list", String[].class);
    }
}

