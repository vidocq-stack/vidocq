package io.vidocq.dirac.bench;

import io.vidocq.dirac.cdi.internal.CountedInterceptor;
import io.vidocq.dirac.cdi.internal.MetricRegistryProducerBean;
import io.vidocq.dirac.cdi.internal.TimedInterceptor;
import io.vidocq.dirac.internal.MetricRegistryImpl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Timed;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks M4: overhead d'appel CDI baseline vs @Counted vs @Timed,
 * plus enregistrement Counter/Timer sous 1000 virtual threads.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class M4TimerBenchmarks {

    @Benchmark
    public void plainCdiCall(InterceptorState state, Blackhole blackhole) {
        blackhole.consume(state.plainService.ping());
    }

    @Benchmark
    public void countedCdiCall(InterceptorState state, Blackhole blackhole) {
        blackhole.consume(state.countedService.ping());
    }

    @Benchmark
    public void timedCdiCall(InterceptorState state, Blackhole blackhole) {
        blackhole.consume(state.timedService.ping());
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void counterIncrementUnder1000VirtualThreads(VirtualThreadState state) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 1_000)
                    .mapToObj(ignored -> executor.submit(() -> state.counter.inc()))
                    .toList();
            for (var future : futures) {
                future.get();
            }
        }
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void timerUpdateUnder1000VirtualThreads(VirtualThreadState state) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 1_000)
                    .mapToObj(ignored -> executor.submit(() -> state.timer.update(Duration.ofNanos(1))))
                    .toList();
            for (var future : futures) {
                future.get();
            }
        }
    }

    @State(Scope.Benchmark)
    public static class InterceptorState {
        SeContainer container;
        PlainService plainService;
        CountedService countedService;
        TimedService timedService;

        @Setup(Level.Trial)
        public void setup() {
            container = SeContainerInitializer.newInstance()
                    .disableDiscovery()
                    .addBeanClasses(
                            MetricRegistryProducerBean.class,
                            CountedInterceptor.class,
                            TimedInterceptor.class,
                            PlainService.class,
                            CountedService.class,
                            TimedService.class
                    )
                    .initialize();
            plainService = container.select(PlainService.class).get();
            countedService = container.select(CountedService.class).get();
            timedService = container.select(TimedService.class).get();
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (container != null) {
                container.close();
            }
        }
    }

    @State(Scope.Benchmark)
    public static class VirtualThreadState {
        MetricRegistry registry;
        org.eclipse.microprofile.metrics.Counter counter;
        org.eclipse.microprofile.metrics.Timer timer;

        @Setup(Level.Trial)
        public void setup() {
            registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);
            counter = registry.counter("bench.counter.vthreads");
            timer = registry.timer("bench.timer.vthreads");
        }
    }

    @ApplicationScoped
    public static class PlainService {
        public int ping() {
            return 1;
        }
    }

    @ApplicationScoped
    public static class CountedService {
        @Counted(name = "bench.counted.calls", absolute = true)
        public int ping() {
            return 1;
        }
    }

    @ApplicationScoped
    public static class TimedService {
        @Timed(name = "bench.timed.calls", absolute = true)
        public int ping() {
            return 1;
        }
    }
}

