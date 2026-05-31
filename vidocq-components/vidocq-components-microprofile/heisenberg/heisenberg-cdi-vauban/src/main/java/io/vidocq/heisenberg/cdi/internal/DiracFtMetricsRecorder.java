package io.vidocq.heisenberg.cdi.internal;

import io.vidocq.heisenberg.api.FtMetricsRecorder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.annotation.RegistryType;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MicroProfile Fault Tolerance 4.1 §9 metrics recorder based on Dirac (MP Metrics).
 *
 * <p>Publishes all FT metrics in the Dirac APPLICATION registry:
 * {@code ft.invocations.total}, {@code ft.retry.calls.total}, {@code ft.retry.retries.total},
 * {@code ft.timeout.calls.total}, {@code ft.timeout.executionDuration},
 * {@code ft.circuitbreaker.calls.total}, {@code ft.circuitbreaker.state.total},
 * {@code ft.circuitbreaker.opened.total}, {@code ft.bulkhead.calls.total},
 * {@code ft.bulkhead.executionsRunning}, {@code ft.bulkhead.executionsWaiting},
 * {@code ft.bulkhead.runningDuration}, {@code ft.bulkhead.waitingDuration}.</p>
 *
 * <p>Registration is idempotent: a first call creates the metrics with value 0,
 * subsequent calls reuse the same instances.</p>
 */
@ApplicationScoped
public class DiracFtMetricsRecorder implements FtMetricsRecorder {

    @SuppressWarnings("deprecation")
    @Inject
    @RegistryType
    private MetricRegistry registry;

    // Idempotency guard: method tag → registered
    private final ConcurrentHashMap<String, Boolean> registered = new ConcurrentHashMap<>();

    // CB state time trackers: method tag → tracker
    private final ConcurrentHashMap<String, CBStateTracker> cbStateTrackers = new ConcurrentHashMap<>();

    // Bulkhead running/waiting gauge counters: method tag → counter
    private final ConcurrentHashMap<String, AtomicLong> bulkheadRunning = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> bulkheadWaiting = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // register()
    // ------------------------------------------------------------------

    @Override
    public void register(Class<?> beanClass, Method method,
                         boolean hasRetry, boolean hasTimeout, boolean hasCircuitBreaker,
                         boolean hasBulkhead, boolean hasFallback, boolean asyncBulkhead) {
        if (!isMetricsEnabled()) return;
        String key = methodKey(beanClass, method);
        if (registered.putIfAbsent(key, Boolean.TRUE) != null) return;

        Tag methodTag = methodTag(beanClass, method);

        // ft.invocations.total
        String[] results = {"valueReturned", "exceptionThrown"};
        String[] fallbacks = hasFallback
                ? new String[]{"applied", "notApplied"}
                : new String[]{"notDefined"};
        for (String result : results) {
            for (String fb : fallbacks) {
                registry.counter("ft.invocations.total", methodTag,
                        new Tag("result", result), new Tag("fallback", fb));
            }
        }

        if (hasRetry) {
            // ft.retry.calls.total — all (retried, retryResult) combinations
            for (boolean retried : new boolean[]{false, true}) {
                String[] retryResults = retried
                        ? new String[]{"valueReturned", "exceptionNotRetryable", "maxRetriesReached", "maxDurationReached"}
                        : new String[]{"valueReturned", "exceptionNotRetryable"};
                for (String rr : retryResults) {
                    registry.counter("ft.retry.calls.total", methodTag,
                            new Tag("retried", String.valueOf(retried)),
                            new Tag("retryResult", rr));
                }
            }
            // ft.retry.retries.total
            registry.counter("ft.retry.retries.total", methodTag);
        }

        if (hasTimeout) {
            // ft.timeout.calls.total
            registry.counter("ft.timeout.calls.total", methodTag, new Tag("timedOut", "true"));
            registry.counter("ft.timeout.calls.total", methodTag, new Tag("timedOut", "false"));
            // ft.timeout.executionDuration
            Metadata durationMeta = Metadata.builder()
                    .withName("ft.timeout.executionDuration")
                    .withUnit(MetricUnits.NANOSECONDS)
                    .build();
            registry.histogram(durationMeta, methodTag);
        }

        if (hasCircuitBreaker) {
            // ft.circuitbreaker.calls.total
            registry.counter("ft.circuitbreaker.calls.total", methodTag, new Tag("circuitBreakerResult", "success"));
            registry.counter("ft.circuitbreaker.calls.total", methodTag, new Tag("circuitBreakerResult", "failure"));
            registry.counter("ft.circuitbreaker.calls.total", methodTag, new Tag("circuitBreakerResult", "circuitBreakerOpen"));
            // ft.circuitbreaker.opened.total
            registry.counter("ft.circuitbreaker.opened.total", methodTag);
            // ft.circuitbreaker.state.total — 3 gauges (one per state)
            CBStateTracker tracker = cbStateTrackers.computeIfAbsent(key, k -> new CBStateTracker());
            Metadata stateMeta = Metadata.builder()
                    .withName("ft.circuitbreaker.state.total")
                    .withUnit(MetricUnits.NANOSECONDS)
                    .build();
            registry.gauge(stateMeta, tracker, t -> t.nanos(FtMetricsRecorder.CBState.CLOSED), methodTag, new Tag("state", "closed"));
            registry.gauge(stateMeta, tracker, t -> t.nanos(FtMetricsRecorder.CBState.OPEN), methodTag, new Tag("state", "open"));
            registry.gauge(stateMeta, tracker, t -> t.nanos(FtMetricsRecorder.CBState.HALF_OPEN), methodTag, new Tag("state", "halfOpen"));
        }

        if (hasBulkhead) {
            // ft.bulkhead.calls.total
            registry.counter("ft.bulkhead.calls.total", methodTag, new Tag("bulkheadResult", "accepted"));
            registry.counter("ft.bulkhead.calls.total", methodTag, new Tag("bulkheadResult", "rejected"));
            // ft.bulkhead.executionsRunning gauge
            AtomicLong running = bulkheadRunning.computeIfAbsent(key, k -> new AtomicLong(0));
            registry.gauge("ft.bulkhead.executionsRunning", running, AtomicLong::get, methodTag);
            // ft.bulkhead.runningDuration histogram
            Metadata runMeta = Metadata.builder()
                    .withName("ft.bulkhead.runningDuration")
                    .withUnit(MetricUnits.NANOSECONDS)
                    .build();
            registry.histogram(runMeta, methodTag);

            if (asyncBulkhead) {
                // ft.bulkhead.executionsWaiting gauge
                AtomicLong waiting = bulkheadWaiting.computeIfAbsent(key, k -> new AtomicLong(0));
                registry.gauge("ft.bulkhead.executionsWaiting", waiting, AtomicLong::get, methodTag);
                // ft.bulkhead.waitingDuration histogram
                Metadata waitMeta = Metadata.builder()
                        .withName("ft.bulkhead.waitingDuration")
                        .withUnit(MetricUnits.NANOSECONDS)
                        .build();
                registry.histogram(waitMeta, methodTag);
            }
        }
    }

    // ------------------------------------------------------------------
    // Invocation
    // ------------------------------------------------------------------

    @Override
    public void recordInvocation(Class<?> beanClass, Method method,
                                 boolean succeeded, boolean fallbackApplied, boolean fallbackDefined) {
        if (!isMetricsEnabled()) return;
        Tag methodTag = methodTag(beanClass, method);
        String result = succeeded ? "valueReturned" : "exceptionThrown";
        String fb = fallbackDefined
                ? (fallbackApplied ? "applied" : "notApplied")
                : "notDefined";
        Counter c = registry.counter("ft.invocations.total", methodTag,
                new Tag("result", result), new Tag("fallback", fb));
        c.inc();
    }

    // ------------------------------------------------------------------
    // Retry
    // ------------------------------------------------------------------

    @Override
    public void recordRetry(Class<?> beanClass, Method method, int retryCount, RetryResult result) {
        if (!isMetricsEnabled()) return;
        Tag methodTag = methodTag(beanClass, method);
        boolean retried = retryCount > 0;
        registry.counter("ft.retry.calls.total", methodTag,
                new Tag("retried", String.valueOf(retried)),
                new Tag("retryResult", result.tagValue())).inc();
        if (retryCount > 0) {
            registry.counter("ft.retry.retries.total", methodTag).inc(retryCount);
        }
    }

    // ------------------------------------------------------------------
    // Timeout
    // ------------------------------------------------------------------

    @Override
    public void recordTimeout(Class<?> beanClass, Method method, boolean timedOut, long durationNanos) {
        if (!isMetricsEnabled()) return;
        Tag methodTag = methodTag(beanClass, method);
        registry.counter("ft.timeout.calls.total", methodTag,
                new Tag("timedOut", String.valueOf(timedOut))).inc();
        Metadata durationMeta = Metadata.builder()
                .withName("ft.timeout.executionDuration")
                .withUnit(MetricUnits.NANOSECONDS)
                .build();
        registry.histogram(durationMeta, methodTag).update(durationNanos);
    }

    // ------------------------------------------------------------------
    // Circuit Breaker
    // ------------------------------------------------------------------

    @Override
    public void recordCircuitBreakerCall(Class<?> beanClass, Method method, CBCallResult result) {
        if (!isMetricsEnabled()) return;
        Tag methodTag = methodTag(beanClass, method);
        registry.counter("ft.circuitbreaker.calls.total", methodTag,
                new Tag("circuitBreakerResult", result.tagValue())).inc();
    }

    @Override
    public void notifyCircuitBreakerStateChange(Class<?> beanClass, Method method, CBState from, CBState to) {
        if (!isMetricsEnabled()) return;
        String key = methodKey(beanClass, method);
        CBStateTracker tracker = cbStateTrackers.get(key);
        if (tracker != null) {
            tracker.transition(to);
        }
        if (to == CBState.OPEN) {
            registry.counter("ft.circuitbreaker.opened.total", methodTag(beanClass, method)).inc();
        }
    }

    // ------------------------------------------------------------------
    // Bulkhead
    // ------------------------------------------------------------------

    @Override
    public void recordBulkheadAccepted(Class<?> beanClass, Method method, long waitNanos, long runNanos) {
        if (!isMetricsEnabled()) return;
        String key = methodKey(beanClass, method);
        Tag methodTag = methodTag(beanClass, method);
        registry.counter("ft.bulkhead.calls.total", methodTag,
                new Tag("bulkheadResult", "accepted")).inc();
        Metadata runMeta = Metadata.builder()
                .withName("ft.bulkhead.runningDuration")
                .withUnit(MetricUnits.NANOSECONDS)
                .build();
        registry.histogram(runMeta, methodTag).update(runNanos);
        // In async bulkhead mode, waitingDuration is reported for every accepted execution,
        // including immediate acquisitions (0ns), so histogram counts stay consistent with
        // the calls.total counter. In sync mode, only emit if we actually tracked waiting.
        if (bulkheadWaiting.containsKey(key)) {
            Metadata waitMeta = Metadata.builder()
                    .withName("ft.bulkhead.waitingDuration")
                    .withUnit(MetricUnits.NANOSECONDS)
                    .build();
            registry.histogram(waitMeta, methodTag).update(Math.max(0L, waitNanos));
        }
    }

    @Override
    public void recordBulkheadRejected(Class<?> beanClass, Method method) {
        if (!isMetricsEnabled()) return;
        registry.counter("ft.bulkhead.calls.total", methodTag(beanClass, method),
                new Tag("bulkheadResult", "rejected")).inc();
    }

    @Override
    public void bulkheadRunningDelta(Class<?> beanClass, Method method, int delta) {
        if (!isMetricsEnabled()) return;
        AtomicLong counter = bulkheadRunning.get(methodKey(beanClass, method));
        if (counter != null) counter.addAndGet(delta);
    }

    @Override
    public void bulkheadWaitingDelta(Class<?> beanClass, Method method, int delta) {
        if (!isMetricsEnabled()) return;
        AtomicLong counter = bulkheadWaiting.get(methodKey(beanClass, method));
        if (counter != null) counter.addAndGet(delta);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String methodKey(Class<?> beanClass, Method method) {
        return beanClass.getName() + "." + method.getName();
    }

    private static Tag methodTag(Class<?> beanClass, Method method) {
        return new Tag("method", beanClass.getName() + "." + method.getName());
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
    // CB state time tracker
    // ------------------------------------------------------------------

    private static final class CBStateTracker {
        private volatile FtMetricsRecorder.CBState currentState = FtMetricsRecorder.CBState.CLOSED;
        private volatile long stateEnteredAt = System.nanoTime();
        private final long[] accNanos = new long[3];

        synchronized long nanos(FtMetricsRecorder.CBState queryState) {
            long acc = accNanos[queryState.ordinal()];
            if (currentState == queryState) {
                acc += System.nanoTime() - stateEnteredAt;
            }
            return acc;
        }

        synchronized void transition(FtMetricsRecorder.CBState newState) {
            if (newState == currentState) return;
            long now = System.nanoTime();
            accNanos[currentState.ordinal()] += now - stateEnteredAt;
            currentState = newState;
            stateEnteredAt = now;
        }
    }
}
