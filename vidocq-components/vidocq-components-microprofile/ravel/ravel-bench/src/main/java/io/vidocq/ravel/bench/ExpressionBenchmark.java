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

import java.util.concurrent.TimeUnit;

/**
 * JMH M5 benchmark for property expression resolution cost (§7.2)
 * across three nesting levels.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms256m", "-Xmx256m"})
@State(Scope.Benchmark)
public class ExpressionBenchmark {

    @Param({"RAVEL", "SMALLRYE"})
    public ConfigImpl impl;

    private Config config;

    @Setup
    public void setup() {
        this.config = impl.build(BenchSources.EXPRESSIONS);
    }

    /** Baseline: value without expression, pure cascade lookup. */
    @Benchmark
    public String literal() {
        return config.getValue("bench.literal", String.class);
    }

    /** 1 level: ${bench.env}. */
    @Benchmark
    public String oneLevel() {
        return config.getValue("bench.url", String.class);
    }

    /** 2-3 levels: ${bench.host.${bench.env}} -> ${bench.url}. */
    @Benchmark
    public String deep() {
        return config.getValue("bench.deep", String.class);
    }
}

