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
package io.vidocq.heisenberg.bench;

import io.vidocq.heisenberg.internal.PolicyComposer;
import java.lang.reflect.Method;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * JMH benchmarks — Heisenberg interception overhead (baseline M3).
 *
 * <p>Measures latency (p99) and throughput for:</p>
 * <ol>
 *   <li>Direct call without FT (baseline).</li>
 *   <li>{@code PolicyComposer.invoke} with no FT annotation active.</li>
 *   <li>{@code PolicyComposer.invoke} with {@code @Timeout} active (method faster than the timeout).</li>
 * </ol>
 *
 * <p>Run: {@code java -jar heisenberg-bench/target/benchmarks.jar
 * InterceptorOverheadBenchmark}</p>
 *
 * <p>Results recorded in {@code BENCH.md}.</p>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
public class InterceptorOverheadBenchmark {

    private static final Object[] NO_ARGS = new Object[0];

    private Method noFtMethod;
    private Method timeoutMethod;
    private NoFtService noFtTarget;
    private TimeoutService timeoutTarget;

    @Setup
    public void setup() throws Exception {
        noFtTarget = new NoFtService();
        timeoutTarget = new TimeoutService();
        noFtMethod = NoFtService.class.getDeclaredMethod("call");
        timeoutMethod = TimeoutService.class.getDeclaredMethod("call");
    }

    /** Baseline: direct call without any FT framework. */
    @Benchmark
    public Object directCall() {
        return "ok";
    }

    /**
     * Pure overhead of {@code PolicyComposer} without any FT annotation.
     * Measures the fixed cost of annotation reading + dispatch.
     */
    @Benchmark
    public Object noFtInterceptor() throws Exception {
        return PolicyComposer.invoke(() -> "ok", noFtTarget, noFtMethod, NO_ARGS);
    }

    /**
     * {@code PolicyComposer} with {@code @Timeout} active — method within the time limit.
     * Measures the overhead of {@code StructuredTaskScope} + virtual-thread fork.
     */
    @Benchmark
    public Object withTimeoutActive() throws Exception {
        return PolicyComposer.invoke(() -> "ok", timeoutTarget, timeoutMethod, NO_ARGS);
    }

    // ---- Embedded test services ----

    static class NoFtService {
        String call() {
            return "ok";
        }
    }

    static class TimeoutService {
        @Timeout(value = 5_000, unit = ChronoUnit.MILLIS)
        String call() {
            return "ok";
        }
    }
}

