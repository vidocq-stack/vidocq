/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
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
 * JMH M5 benchmark for {@code Config.getValue(String, String.class)} lookup cost
 * on hot cache (key always present) and cold path (missing key -> Optional.empty).
 *
 * <p>Compares Ravel and SmallRye with identical properties.</p>
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
        // Round-robin on 64 keys to avoid JIT constant folding.
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

