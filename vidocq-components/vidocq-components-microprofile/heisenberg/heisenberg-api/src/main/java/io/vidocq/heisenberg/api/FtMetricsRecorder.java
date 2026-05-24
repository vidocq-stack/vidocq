package io.vidocq.heisenberg.api;

import java.lang.reflect.Method;

/**
 * SPI d'instrumentation des métriques MicroProfile Fault Tolerance 4.1 §9.
 *
 * <p>Les implémentations sont découvertes par CDI ; si aucune n'est disponible,
 * {@link #NOOP} est utilisé. {@code DiracFtMetricsRecorder} (heisenberg-cdi-vauban) est
 * l'implémentation standard basée sur Dirac (MP Metrics).</p>
 *
 * <p>Toutes les méthodes sont idempotentes et thread-safe.</p>
 */
public interface FtMetricsRecorder {

    /**
     * Enregistre les métriques pour une méthode annotée FT.
     * Appelé une seule fois par méthode (au premier appel). Idempotent.
     *
     * @param asyncBulkhead {@code true} si @Bulkhead est en mode async (@Asynchronous présent)
     */
    void register(Class<?> beanClass, Method method,
                  boolean hasRetry, boolean hasTimeout, boolean hasCircuitBreaker,
                  boolean hasBulkhead, boolean hasFallback, boolean asyncBulkhead);

    /**
     * Enregistre le résultat global d'une invocation FT (après tous les politiques).
     *
     * @param succeeded      {@code true} si la méthode a retourné une valeur (éventuellement via fallback)
     * @param fallbackApplied {@code true} si le fallback a été invoqué et a retourné avec succès
     * @param fallbackDefined {@code true} si @Fallback est présent sur la méthode
     */
    void recordInvocation(Class<?> beanClass, Method method,
                          boolean succeeded, boolean fallbackApplied, boolean fallbackDefined);

    /**
     * Enregistre le résultat de la politique @Retry.
     *
     * @param retryCount nombre de tentatives de retry (0 = pas de retry)
     * @param result     comment la politique s'est terminée
     */
    void recordRetry(Class<?> beanClass, Method method, int retryCount, RetryResult result);

    /**
     * Enregistre le résultat de la politique @Timeout.
     *
     * @param timedOut      {@code true} si le timeout a été déclenché
     * @param durationNanos durée d'exécution en nanosecondes
     */
    void recordTimeout(Class<?> beanClass, Method method, boolean timedOut, long durationNanos);

    /**
     * Enregistre le résultat d'un appel au niveau du @CircuitBreaker.
     */
    void recordCircuitBreakerCall(Class<?> beanClass, Method method, CBCallResult result);

    /**
     * Notifie d'une transition d'état du circuit breaker.
     * Appelé chaque fois que l'état change (CLOSED→OPEN, OPEN→HALF_OPEN, HALF_OPEN→CLOSED).
     */
    void notifyCircuitBreakerStateChange(Class<?> beanClass, Method method, CBState from, CBState to);

    /**
     * Enregistre une invocation acceptée par le @Bulkhead.
     *
     * @param waitNanos temps passé en file d'attente (0 pour le mode sync ou permit immédiat)
     * @param runNanos  temps d'exécution
     */
    void recordBulkheadAccepted(Class<?> beanClass, Method method, long waitNanos, long runNanos);

    /**
     * Enregistre un rejet par le @Bulkhead.
     */
    void recordBulkheadRejected(Class<?> beanClass, Method method);

    /**
     * Met à jour la jauge "invocations en cours" du @Bulkhead.
     *
     * @param delta +1 quand une invocation démarre, -1 quand elle se termine
     */
    void bulkheadRunningDelta(Class<?> beanClass, Method method, int delta);

    /**
     * Met à jour la jauge "invocations en attente" du @Bulkhead (mode async uniquement).
     *
     * @param delta +1 quand une invocation entre en file, -1 quand elle en sort
     */
    void bulkheadWaitingDelta(Class<?> beanClass, Method method, int delta);

    // ------------------------------------------------------------------
    // Enums de résultats
    // ------------------------------------------------------------------

    enum RetryResult {
        VALUE_RETURNED,
        EXCEPTION_NOT_RETRYABLE,
        MAX_RETRIES_REACHED,
        MAX_DURATION_REACHED;

        public String tagValue() {
            return switch (this) {
                case VALUE_RETURNED -> "valueReturned";
                case EXCEPTION_NOT_RETRYABLE -> "exceptionNotRetryable";
                case MAX_RETRIES_REACHED -> "maxRetriesReached";
                case MAX_DURATION_REACHED -> "maxDurationReached";
            };
        }
    }

    enum CBCallResult {
        SUCCESS, FAILURE, CIRCUIT_BREAKER_OPEN;

        public String tagValue() {
            return switch (this) {
                case SUCCESS -> "success";
                case FAILURE -> "failure";
                case CIRCUIT_BREAKER_OPEN -> "circuitBreakerOpen";
            };
        }
    }

    enum CBState {
        CLOSED, OPEN, HALF_OPEN;

        public String tagValue() {
            return switch (this) {
                case CLOSED -> "closed";
                case OPEN -> "open";
                case HALF_OPEN -> "halfOpen";
            };
        }
    }

    // ------------------------------------------------------------------
    // Implémentation nulle (aucune instrumentation)
    // ------------------------------------------------------------------

    FtMetricsRecorder NOOP = new FtMetricsRecorder() {
        @Override
        public void register(Class<?> beanClass, Method method, boolean hasRetry, boolean hasTimeout,
                             boolean hasCircuitBreaker, boolean hasBulkhead, boolean hasFallback,
                             boolean asyncBulkhead) {}
        @Override
        public void recordInvocation(Class<?> beanClass, Method method, boolean succeeded,
                                     boolean fallbackApplied, boolean fallbackDefined) {}
        @Override
        public void recordRetry(Class<?> beanClass, Method method, int retryCount, RetryResult result) {}
        @Override
        public void recordTimeout(Class<?> beanClass, Method method, boolean timedOut, long durationNanos) {}
        @Override
        public void recordCircuitBreakerCall(Class<?> beanClass, Method method, CBCallResult result) {}
        @Override
        public void notifyCircuitBreakerStateChange(Class<?> beanClass, Method method, CBState from, CBState to) {}
        @Override
        public void recordBulkheadAccepted(Class<?> beanClass, Method method, long waitNanos, long runNanos) {}
        @Override
        public void recordBulkheadRejected(Class<?> beanClass, Method method) {}
        @Override
        public void bulkheadRunningDelta(Class<?> beanClass, Method method, int delta) {}
        @Override
        public void bulkheadWaitingDelta(Class<?> beanClass, Method method, int delta) {}
    };
}
