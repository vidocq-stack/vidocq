package io.vidocq.heisenberg.cdi.internal;

import io.vidocq.heisenberg.api.FtMetricsRecorder;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Recorder composite : fan-out vers une liste de recorders dlguent.
 *
 * <p>Permet de publier simultanment dans les deux registres dfinis par la spec :
 * §9 (MP Metrics via {@link DiracFtMetricsRecorder}) et §10 (OpenTelemetry via
 * {@link OtelFtMetricsRecorder}).</p>
 *
 * <p>Chaque dlgu est invoqu dans l'ordre de la liste. Une exception dans l'un
 * d'eux est attrape et ignore — l'instrumentation ne doit jamais propager un
 * chec dans le chemin d'excution mtier.</p>
 */
final class CompositeFtMetricsRecorder implements FtMetricsRecorder {

    private final List<FtMetricsRecorder> delegates;

    CompositeFtMetricsRecorder(List<FtMetricsRecorder> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    private interface Action {
        void run(FtMetricsRecorder r);
    }

    private void fanOut(Action action) {
        for (FtMetricsRecorder r : delegates) {
            try {
                action.run(r);
            } catch (Throwable ignored) {
                // l'instrumentation ne doit jamais casser le chemin mtier
            }
        }
    }

    @Override
    public void register(Class<?> beanClass, Method method,
                         boolean hasRetry, boolean hasTimeout, boolean hasCircuitBreaker,
                         boolean hasBulkhead, boolean hasFallback, boolean asyncBulkhead) {
        fanOut(r -> r.register(beanClass, method, hasRetry, hasTimeout, hasCircuitBreaker,
                hasBulkhead, hasFallback, asyncBulkhead));
    }

    @Override
    public void recordInvocation(Class<?> beanClass, Method method,
                                 boolean succeeded, boolean fallbackApplied, boolean fallbackDefined) {
        fanOut(r -> r.recordInvocation(beanClass, method, succeeded, fallbackApplied, fallbackDefined));
    }

    @Override
    public void recordRetry(Class<?> beanClass, Method method, int retryCount, RetryResult result) {
        fanOut(r -> r.recordRetry(beanClass, method, retryCount, result));
    }

    @Override
    public void recordTimeout(Class<?> beanClass, Method method, boolean timedOut, long durationNanos) {
        fanOut(r -> r.recordTimeout(beanClass, method, timedOut, durationNanos));
    }

    @Override
    public void recordCircuitBreakerCall(Class<?> beanClass, Method method, CBCallResult result) {
        fanOut(r -> r.recordCircuitBreakerCall(beanClass, method, result));
    }

    @Override
    public void notifyCircuitBreakerStateChange(Class<?> beanClass, Method method, CBState from, CBState to) {
        fanOut(r -> r.notifyCircuitBreakerStateChange(beanClass, method, from, to));
    }

    @Override
    public void recordBulkheadAccepted(Class<?> beanClass, Method method, long waitNanos, long runNanos) {
        fanOut(r -> r.recordBulkheadAccepted(beanClass, method, waitNanos, runNanos));
    }

    @Override
    public void recordBulkheadRejected(Class<?> beanClass, Method method) {
        fanOut(r -> r.recordBulkheadRejected(beanClass, method));
    }

    @Override
    public void bulkheadRunningDelta(Class<?> beanClass, Method method, int delta) {
        fanOut(r -> r.bulkheadRunningDelta(beanClass, method, delta));
    }

    @Override
    public void bulkheadWaitingDelta(Class<?> beanClass, Method method, int delta) {
        fanOut(r -> r.bulkheadWaitingDelta(beanClass, method, delta));
    }
}

