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
 * Benchmarks JMH — overhead d'interception Heisenberg (baseline M3).
 *
 * <p>Mesure la latence (p99) et le throughput pour :</p>
 * <ol>
 *   <li>Appel direct sans FT (référence).</li>
 *   <li>{@code PolicyComposer.invoke} sans aucune annotation FT active.</li>
 *   <li>{@code PolicyComposer.invoke} avec {@code @Timeout} actif (méthode plus rapide que le timeout).</li>
 * </ol>
 *
 * <p>Lancement : {@code java -jar heisenberg-bench/target/benchmarks.jar
 * InterceptorOverheadBenchmark}</p>
 *
 * <p>Résultats consignés dans {@code BENCH.md}.</p>
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

    /** Référence : appel direct sans aucun framework FT. */
    @Benchmark
    public Object directCall() {
        return "ok";
    }

    /**
     * Overhead pur du {@code PolicyComposer} sans annotation FT.
     * Mesure le coût fixe de lecture des annotations + dispatch.
     */
    @Benchmark
    public Object noFtInterceptor() throws Exception {
        return PolicyComposer.invoke(() -> "ok", noFtTarget, noFtMethod, NO_ARGS);
    }

    /**
     * {@code PolicyComposer} avec {@code @Timeout} actif — méthode dans les temps.
     * Mesure l'overhead de {@code StructuredTaskScope} + fork virtual thread.
     */
    @Benchmark
    public Object withTimeoutActive() throws Exception {
        return PolicyComposer.invoke(() -> "ok", timeoutTarget, timeoutMethod, NO_ARGS);
    }

    // ---- Services de test intégrés ----

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

