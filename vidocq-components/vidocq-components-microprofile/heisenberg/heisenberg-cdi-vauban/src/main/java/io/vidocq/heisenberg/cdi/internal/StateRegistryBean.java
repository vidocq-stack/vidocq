package io.vidocq.heisenberg.cdi.internal;

import io.vidocq.heisenberg.internal.CircuitBreakerState;
import io.vidocq.heisenberg.internal.CircuitBreakerStateRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Global Circuit Breaker state registry — CDI {@code @ApplicationScoped} bean.
 *
 * <p>Stores the CLOSED/OPEN/HALF_OPEN states + success counters for all circuit breakers
 * using a stable key: {@code ClassName#methodName}.</p>
 */
@ApplicationScoped
public class StateRegistryBean implements CircuitBreakerStateRegistry {

    private static final class CircuitBreakerSnapshot {
        volatile CircuitBreakerState state = CircuitBreakerState.CLOSED;
        volatile long openedAtNanos = 0;
        volatile int successesInHalfOpen = 0;
        volatile int failureCount = 0;
    }

    private final ConcurrentHashMap<String, CircuitBreakerSnapshot> states = new ConcurrentHashMap<>();

    /**
     * Stable key for a circuit breaker.
     */
    private String key(String beanClass, String methodName) {
        return beanClass + "#" + methodName;
    }

    private CircuitBreakerSnapshot getOrCreate(String key) {
        return states.computeIfAbsent(key, k -> new CircuitBreakerSnapshot());
    }

    @Override
    public CircuitBreakerState getState(String beanClass, String methodName) {
        return getOrCreate(key(beanClass, methodName)).state;
    }

    @Override
    public void recordSuccess(String beanClass, String methodName) {
        CircuitBreakerSnapshot snap = getOrCreate(key(beanClass, methodName));
        snap.successesInHalfOpen++;
    }

    @Override
    public void recordFailure(String beanClass, String methodName) {
        CircuitBreakerSnapshot snap = getOrCreate(key(beanClass, methodName));
        snap.failureCount++;
    }

    @Override
    public void setOpen(String beanClass, String methodName) {
        CircuitBreakerSnapshot snap = getOrCreate(key(beanClass, methodName));
        snap.state = CircuitBreakerState.OPEN;
        snap.openedAtNanos = System.nanoTime();
    }

    @Override
    public void setClosed(String beanClass, String methodName) {
        CircuitBreakerSnapshot snap = getOrCreate(key(beanClass, methodName));
        snap.state = CircuitBreakerState.CLOSED;
        snap.successesInHalfOpen = 0;
        snap.failureCount = 0;
    }

    @Override
    public void setHalfOpen(String beanClass, String methodName) {
        CircuitBreakerSnapshot snap = getOrCreate(key(beanClass, methodName));
        snap.state = CircuitBreakerState.HALF_OPEN;
        snap.successesInHalfOpen = 0;
    }

    @Override
    public long getMillisSinceOpen(String beanClass, String methodName) {
        CircuitBreakerSnapshot snap = states.get(key(beanClass, methodName));
        if (snap == null) return 0;
        return (System.nanoTime() - snap.openedAtNanos) / 1_000_000;
    }

    @Override
    public int getSuccessesInHalfOpen(String beanClass, String methodName) {
        CircuitBreakerSnapshot snap = states.get(key(beanClass, methodName));
        return snap != null ? snap.successesInHalfOpen : 0;
    }
}

