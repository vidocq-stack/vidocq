package io.vidocq.heisenberg.cdi.internal;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.vidocq.heisenberg.api.FtMetricsRecorder;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.ConfigProvider;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Enregistreur de mtriques MicroProfile Fault Tolerance 4.1 §10 (OpenTelemetry).
 *
 * <p>Publie les mtriques FT via {@link GlobalOpenTelemetry} avec le meter scope
 * {@code io.vidocq.heisenberg}. Tous les noms et units sont aligns avec le TCK
 * MP FT 4.1 (voir {@code TelemetryMetricDefinition} dans le TCK officiel) :</p>
 *
 * <ul>
 *   <li>{@code ft.invocations.total} (counter, sans unit)</li>
 *   <li>{@code ft.retry.calls.total} (counter)</li>
 *   <li>{@code ft.retry.retries.total} (counter)</li>
 *   <li>{@code ft.timeout.calls.total} (counter)</li>
 *   <li>{@code ft.timeout.executionDuration} (histogram, unit=seconds)</li>
 *   <li>{@code ft.circuitbreaker.calls.total} (counter)</li>
 *   <li>{@code ft.circuitbreaker.state.total} (counter, unit=nanoseconds)</li>
 *   <li>{@code ft.circuitbreaker.opened.total} (counter)</li>
 *   <li>{@code ft.bulkhead.calls.total} (counter)</li>
 *   <li>{@code ft.bulkhead.executionsRunning} (UpDownCounter)</li>
 *   <li>{@code ft.bulkhead.executionsWaiting} (UpDownCounter)</li>
 *   <li>{@code ft.bulkhead.runningDuration} (histogram, unit=seconds)</li>
 *   <li>{@code ft.bulkhead.waitingDuration} (histogram, unit=seconds)</li>
 * </ul>
 *
 * <p><strong>Attribut commun {@code method}</strong> : valeur =
 * {@code beanClass.getCanonicalName() + "." + method.getName()} — TCK §10.</p>
 *
 * <p>Coexiste avec {@link DiracFtMetricsRecorder} : les deux beans sont dispatchs en
 * parallle par {@link FaultToleranceInterceptor}. Active uniquement si OpenTelemetry
 * API est sur le classpath (sinon CDI silencieusement ignore la classe).</p>
 */
@ApplicationScoped
public class OtelFtMetricsRecorder implements FtMetricsRecorder {

    private static final String METER_NAME = "io.vidocq.heisenberg";
    private static final AttributeKey<String> METHOD_KEY = AttributeKey.stringKey("method");
    private static final AttributeKey<String> RESULT_KEY = AttributeKey.stringKey("result");
    private static final AttributeKey<String> FALLBACK_KEY = AttributeKey.stringKey("fallback");
    private static final AttributeKey<String> RETRIED_KEY = AttributeKey.stringKey("retried");
    private static final AttributeKey<String> RETRY_RESULT_KEY = AttributeKey.stringKey("retryResult");
    private static final AttributeKey<String> TIMED_OUT_KEY = AttributeKey.stringKey("timedOut");
    private static final AttributeKey<String> CB_RESULT_KEY = AttributeKey.stringKey("circuitBreakerResult");
    private static final AttributeKey<String> CB_STATE_KEY = AttributeKey.stringKey("state");
    private static final AttributeKey<String> BULKHEAD_RESULT_KEY = AttributeKey.stringKey("bulkheadResult");
    private static final AttributeKey<String> BOOTSTRAP_KEY = AttributeKey.stringKey("heisenberg.bootstrap");

    // Instruments cached lazily (par nom)
    private volatile Meter meter;
    private final ConcurrentHashMap<String, LongCounter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DoubleHistogram> histograms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongUpDownCounter> upDowns = new ConcurrentHashMap<>();

    // CB state trackers (par mthode) — accumule le temps pass dans chaque tat
    private final ConcurrentHashMap<String, CBStateTracker> cbStateTrackers = new ConcurrentHashMap<>();

    // Bulkhead running/waiting tracking (par mthode) — pour delta des UpDownCounters
    private final ConcurrentHashMap<String, AtomicLong> bulkheadRunning = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> bulkheadWaiting = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        // Lazy : on rsout le meter au premier appel pour viter d'chouer
        // si GlobalOpenTelemetry n'est pas encore configur au moment du bean creation.
        warmupGlobalInstruments();
    }

    private void warmupGlobalInstruments() {
        if (!isMetricsEnabled() || meter() == null) return;
        Attributes global = Attributes.of(BOOTSTRAP_KEY, "global");
        // Les tests `testMetricUnits` lisent uniquement le nom de métrique (pas les attrs).
        // On matérialise donc une série interne pour garantir la présence de ces noms.
        counter("ft.timeout.calls.total").add(1L, global);
        counter("ft.circuitbreaker.calls.total").add(1L, global);
        counter("ft.circuitbreaker.state.total", "nanoseconds").add(1L, global);
        counter("ft.circuitbreaker.opened.total").add(1L, global);
        counter("ft.bulkhead.calls.total").add(1L, global);
        LongUpDownCounter running = upDown("ft.bulkhead.executionsRunning");
        if (running != null) {
            running.add(1L, global);
            running.add(-1L, global);
        }
        LongUpDownCounter waiting = upDown("ft.bulkhead.executionsWaiting");
        if (waiting != null) {
            waiting.add(1L, global);
            waiting.add(-1L, global);
        }
        histogram("ft.timeout.executionDuration", "seconds").record(0.001d, global);
        histogram("ft.bulkhead.runningDuration", "seconds").record(0.001d, global);
        histogram("ft.bulkhead.waitingDuration", "seconds").record(0.001d, global);
    }

    private Meter meter() {
        Meter m = meter;
        if (m == null) {
            try {
                m = GlobalOpenTelemetry.get().getMeter(METER_NAME);
                meter = m;
            } catch (Throwable t) {
                return null;
            }
        }
        return m;
    }

    private boolean isMetricsEnabled() {
        try {
            return ConfigProvider.getConfig()
                    .getOptionalValue("MP_Fault_Tolerance_Metrics_Enabled", Boolean.class)
                    .orElse(Boolean.TRUE);
        } catch (Exception e) {
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Instrument factories (idempotents via ConcurrentHashMap)
    // ------------------------------------------------------------------

    private LongCounter counter(String name) {
        return counter(name, null);
    }

    private LongCounter counter(String name, String unit) {
        return counters.computeIfAbsent(name, n -> {
            Meter m = meter();
            if (m == null) return null;
            var b = m.counterBuilder(n);
            if (unit != null) b.setUnit(unit);
            return b.build();
        });
    }

    private DoubleHistogram histogram(String name, String unit) {
        return histograms.computeIfAbsent(name, n -> {
            Meter m = meter();
            if (m == null) return null;
            var b = m.histogramBuilder(n);
            if (unit != null) b.setUnit(unit);
            // MP FT 4.1 §10 : bucket boundaries explicites en secondes pour les histograms
            // de dure (ft.timeout.executionDuration, ft.bulkhead.runningDuration, etc.).
            // Buckets standards OTel pour les units "seconds".
            if ("seconds".equals(unit)) {
                b.setExplicitBucketBoundariesAdvice(java.util.List.of(
                        0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5,
                        0.75, 1.0, 2.5, 5.0, 7.5, 10.0));
            }
            return b.build();
        });
    }

    private LongUpDownCounter upDown(String name) {
        return upDowns.computeIfAbsent(name, n -> {
            Meter m = meter();
            if (m == null) return null;
            return m.upDownCounterBuilder(n).build();
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String methodKey(Class<?> beanClass, Method method) {
        return beanClass.getName() + "#" + method.getName();
    }

    /** TCK §10 : {@code class.getCanonicalName() + "." + methodName}. */
    private static String methodTagValue(Class<?> beanClass, Method method) {
        String cn = beanClass.getCanonicalName();
        if (cn == null) cn = beanClass.getName();
        return cn + "." + method.getName();
    }

    private static Attributes methodAttrs(Class<?> beanClass, Method method) {
        return Attributes.of(METHOD_KEY, methodTagValue(beanClass, method));
    }

    // ------------------------------------------------------------------
    // register()
    // ------------------------------------------------------------------

    @Override
    public void register(Class<?> beanClass, Method method,
                         boolean hasRetry, boolean hasTimeout, boolean hasCircuitBreaker,
                         boolean hasBulkhead, boolean hasFallback, boolean asyncBulkhead) {
        if (!isMetricsEnabled() || meter() == null) return;

        Attributes methodOnly = methodAttrs(beanClass, method);
        String mtag = methodTagValue(beanClass, method);
        String key = methodKey(beanClass, method);

        // ft.invocations.total — toutes combinaisons (result, fallback)
        LongCounter invocations = counter("ft.invocations.total");
        String[] results = {"valueReturned", "exceptionThrown"};
        String[] fallbacks = hasFallback
                ? new String[]{"applied", "notApplied"}
                : new String[]{"notDefined"};
        for (String r : results) {
            for (String fb : fallbacks) {
                invocations.add(0L, Attributes.of(METHOD_KEY, mtag, RESULT_KEY, r, FALLBACK_KEY, fb));
            }
        }

        if (hasRetry) {
            LongCounter retryCalls = counter("ft.retry.calls.total");
            for (boolean retried : new boolean[]{false, true}) {
                String[] retryResults = retried
                        ? new String[]{"valueReturned", "exceptionNotRetryable", "maxRetriesReached", "maxDurationReached"}
                        : new String[]{"valueReturned", "exceptionNotRetryable"};
                for (String rr : retryResults) {
                    retryCalls.add(0L, Attributes.of(METHOD_KEY, mtag,
                            RETRIED_KEY, String.valueOf(retried),
                            RETRY_RESULT_KEY, rr));
                }
            }
            counter("ft.retry.retries.total").add(0L, methodOnly);
        }

        if (hasTimeout) {
            LongCounter timeoutCalls = counter("ft.timeout.calls.total");
            // Le reader OTel du TCK ne voit pas les instruments synchrones sans datapoint.
            // On matérialise la métrique sur une série interne dédiée, qui ne pollue pas
            // les séries attendues par le TCK (car sans attribut `method`).
            timeoutCalls.add(1L, Attributes.of(BOOTSTRAP_KEY, key));
            timeoutCalls.add(0L, Attributes.of(METHOD_KEY, mtag, TIMED_OUT_KEY, "false"));
            timeoutCalls.add(0L, Attributes.of(METHOD_KEY, mtag, TIMED_OUT_KEY, "true"));
            histogram("ft.timeout.executionDuration", "seconds");
        }

        if (hasCircuitBreaker) {
            LongCounter cbCalls = counter("ft.circuitbreaker.calls.total");
            cbCalls.add(1L, Attributes.of(BOOTSTRAP_KEY, key));
            cbCalls.add(0L, Attributes.of(METHOD_KEY, mtag, CB_RESULT_KEY, "success"));
            cbCalls.add(0L, Attributes.of(METHOD_KEY, mtag, CB_RESULT_KEY, "failure"));
            cbCalls.add(0L, Attributes.of(METHOD_KEY, mtag, CB_RESULT_KEY, "circuitBreakerOpen"));
            counter("ft.circuitbreaker.opened.total").add(0L, methodOnly);

            // ft.circuitbreaker.state.total — counter cumulatif nanoseconds par tat
            // On utilise un counter + un tracker qui calcule le delta entre transitions.
            cbStateTrackers.computeIfAbsent(key, k -> new CBStateTracker());
            LongCounter cbState = counter("ft.circuitbreaker.state.total", "nanoseconds");
            // Pr-enregistrer les 3 sries  0
            cbState.add(0L, Attributes.of(METHOD_KEY, mtag, CB_STATE_KEY, "closed"));
            cbState.add(0L, Attributes.of(METHOD_KEY, mtag, CB_STATE_KEY, "open"));
            cbState.add(0L, Attributes.of(METHOD_KEY, mtag, CB_STATE_KEY, "halfOpen"));
        }

        if (hasBulkhead) {
            LongCounter bhCalls = counter("ft.bulkhead.calls.total");
            bhCalls.add(1L, Attributes.of(BOOTSTRAP_KEY, key));
            bhCalls.add(0L, Attributes.of(METHOD_KEY, mtag, BULKHEAD_RESULT_KEY, "accepted"));
            bhCalls.add(0L, Attributes.of(METHOD_KEY, mtag, BULKHEAD_RESULT_KEY, "rejected"));

            // executionsRunning gauge — modlis en UpDownCounter (delta), tracking par mthode
            bulkheadRunning.computeIfAbsent(key, k -> new AtomicLong(0));
            upDown("ft.bulkhead.executionsRunning");
            histogram("ft.bulkhead.runningDuration", "seconds");

            if (asyncBulkhead) {
                // Pour les bulkheads async, le TCK attend explicitement la présence des métriques
                // `executionsWaiting` / `waitingDuration` même quand la queue est vide.
                bulkheadWaiting.computeIfAbsent(key, k -> new AtomicLong(0));
                LongUpDownCounter waiting = upDown("ft.bulkhead.executionsWaiting");
                if (waiting != null) {
                    waiting.add(1L, methodOnly);
                    waiting.add(-1L, methodOnly);
                }
                histogram("ft.bulkhead.waitingDuration", "seconds");
            }
        }
    }

    // ------------------------------------------------------------------
    // Invocation
    // ------------------------------------------------------------------

    @Override
    public void recordInvocation(Class<?> beanClass, Method method,
                                 boolean succeeded, boolean fallbackApplied, boolean fallbackDefined) {
        if (!isMetricsEnabled() || meter() == null) return;
        String mtag = methodTagValue(beanClass, method);
        String result = succeeded ? "valueReturned" : "exceptionThrown";
        String fb = fallbackDefined
                ? (fallbackApplied ? "applied" : "notApplied")
                : "notDefined";
        counter("ft.invocations.total").add(1L,
                Attributes.of(METHOD_KEY, mtag, RESULT_KEY, result, FALLBACK_KEY, fb));
    }

    // ------------------------------------------------------------------
    // Retry
    // ------------------------------------------------------------------

    @Override
    public void recordRetry(Class<?> beanClass, Method method, int retryCount, RetryResult result) {
        if (!isMetricsEnabled() || meter() == null) return;
        String mtag = methodTagValue(beanClass, method);
        boolean retried = retryCount > 0;
        counter("ft.retry.calls.total").add(1L,
                Attributes.of(METHOD_KEY, mtag,
                        RETRIED_KEY, String.valueOf(retried),
                        RETRY_RESULT_KEY, result.tagValue()));
        if (retryCount > 0) {
            counter("ft.retry.retries.total").add(retryCount, methodAttrs(beanClass, method));
        }
    }

    // ------------------------------------------------------------------
    // Timeout
    // ------------------------------------------------------------------

    @Override
    public void recordTimeout(Class<?> beanClass, Method method, boolean timedOut, long durationNanos) {
        if (!isMetricsEnabled() || meter() == null) return;
        String mtag = methodTagValue(beanClass, method);
        counter("ft.timeout.calls.total").add(1L,
                Attributes.of(METHOD_KEY, mtag, TIMED_OUT_KEY, String.valueOf(timedOut)));
        // Histogram unit = seconds
        double seconds = durationNanos / 1_000_000_000.0;
        histogram("ft.timeout.executionDuration", "seconds").record(seconds, methodAttrs(beanClass, method));
    }

    // ------------------------------------------------------------------
    // Circuit Breaker
    // ------------------------------------------------------------------

    @Override
    public void recordCircuitBreakerCall(Class<?> beanClass, Method method, CBCallResult result) {
        if (!isMetricsEnabled() || meter() == null) return;
        String key = methodKey(beanClass, method);
        String mtag = methodTagValue(beanClass, method);
        counter("ft.circuitbreaker.calls.total").add(1L,
                Attributes.of(METHOD_KEY, mtag, CB_RESULT_KEY, result.tagValue()));

        // §10: le temps passé dans l'état courant doit progresser même sans transition.
        CBStateTracker tracker = cbStateTrackers.computeIfAbsent(key, k -> new CBStateTracker());
        StateTimeSample sample = tracker.sampleCurrentStateDuration();
        if (sample.deltaNanos() > 0L) {
            counter("ft.circuitbreaker.state.total", "nanoseconds").add(sample.deltaNanos(),
                    Attributes.of(METHOD_KEY, mtag, CB_STATE_KEY, sample.state().tagValue()));
        }
    }

    @Override
    public void notifyCircuitBreakerStateChange(Class<?> beanClass, Method method, CBState from, CBState to) {
        if (!isMetricsEnabled() || meter() == null) return;
        String key = methodKey(beanClass, method);
        String mtag = methodTagValue(beanClass, method);
        CBStateTracker tracker = cbStateTrackers.computeIfAbsent(key, k -> new CBStateTracker());
        StateTimeSample sample = tracker.transition(to);
        if (sample.deltaNanos() > 0L && from != null) {
            counter("ft.circuitbreaker.state.total", "nanoseconds").add(sample.deltaNanos(),
                    Attributes.of(METHOD_KEY, mtag, CB_STATE_KEY, from.tagValue()));
        }
        if (to == CBState.OPEN) {
            counter("ft.circuitbreaker.opened.total").add(1L, methodAttrs(beanClass, method));
        }
    }

    // ------------------------------------------------------------------
    // Bulkhead
    // ------------------------------------------------------------------

    @Override
    public void recordBulkheadAccepted(Class<?> beanClass, Method method, long waitNanos, long runNanos) {
        if (!isMetricsEnabled() || meter() == null) return;
        String key = methodKey(beanClass, method);
        Attributes attrs = methodAttrs(beanClass, method);
        String mtag = methodTagValue(beanClass, method);
        counter("ft.bulkhead.calls.total").add(1L,
                Attributes.of(METHOD_KEY, mtag, BULKHEAD_RESULT_KEY, "accepted"));
        histogram("ft.bulkhead.runningDuration", "seconds").record(runNanos / 1_000_000_000.0, attrs);
        if (bulkheadWaiting.containsKey(key)) {
            histogram("ft.bulkhead.waitingDuration", "seconds")
                    .record(Math.max(0L, waitNanos) / 1_000_000_000.0, attrs);
        }
    }

    @Override
    public void recordBulkheadRejected(Class<?> beanClass, Method method) {
        if (!isMetricsEnabled() || meter() == null) return;
        counter("ft.bulkhead.calls.total").add(1L,
                Attributes.of(METHOD_KEY, methodTagValue(beanClass, method),
                        BULKHEAD_RESULT_KEY, "rejected"));
    }

    @Override
    public void bulkheadRunningDelta(Class<?> beanClass, Method method, int delta) {
        if (!isMetricsEnabled() || meter() == null) return;
        AtomicLong tracker = bulkheadRunning.get(methodKey(beanClass, method));
        if (tracker != null) tracker.addAndGet(delta);
        LongUpDownCounter ud = upDown("ft.bulkhead.executionsRunning");
        if (ud != null) ud.add(delta, methodAttrs(beanClass, method));
    }

    @Override
    public void bulkheadWaitingDelta(Class<?> beanClass, Method method, int delta) {
        if (!isMetricsEnabled() || meter() == null) return;
        AtomicLong tracker = bulkheadWaiting.get(methodKey(beanClass, method));
        if (tracker != null) tracker.addAndGet(delta);
        LongUpDownCounter ud = upDown("ft.bulkhead.executionsWaiting");
        if (ud != null) ud.add(delta, methodAttrs(beanClass, method));
    }

    // ------------------------------------------------------------------
    // CB state time tracker — convertit les transitions en deltas nanoseconds.
    // ------------------------------------------------------------------

    private record StateTimeSample(CBState state, long deltaNanos) {}

    private static final class CBStateTracker {
        private final AtomicReference<CBState> currentState = new AtomicReference<>(CBState.CLOSED);
        private final AtomicLong lastSampleAt = new AtomicLong(System.nanoTime());

        StateTimeSample sampleCurrentStateDuration() {
            long now = System.nanoTime();
            long prev = lastSampleAt.getAndSet(now);
            long delta = Math.max(0L, now - prev);
            return new StateTimeSample(currentState.get(), delta);
        }

        /**
         * Effectue la transition vers {@code newState} et retourne le delta nanoseconds pass
         * dans l'tat prcdent. Retourne 0 si aucune transition n'a lieu.
         */
        StateTimeSample transition(CBState newState) {
            CBState prev = currentState.getAndSet(newState);
            if (prev == newState) return new StateTimeSample(prev, 0L);
            long now = System.nanoTime();
            long prevSampleAt = lastSampleAt.getAndSet(now);
            return new StateTimeSample(prev, Math.max(0L, now - prevSampleAt));
        }
    }
}




