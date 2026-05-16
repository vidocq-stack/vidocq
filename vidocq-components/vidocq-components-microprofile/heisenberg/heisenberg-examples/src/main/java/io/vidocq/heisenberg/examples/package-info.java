/**
 * Exemples d'utilisation de MicroProfile Fault Tolerance 4.1 via Heisenberg.
 *
 * <p>Contenu prévu :</p>
 * <ul>
 *   <li>{@code RetryExample} — service avec @Retry sur une opération I/O instable.</li>
 *   <li>{@code CircuitBreakerExample} — service avec @CircuitBreaker et @Fallback.</li>
 *   <li>{@code BulkheadAsyncExample} — service @Bulkhead + @Asynchronous.</li>
 * </ul>
 */
package io.vidocq.heisenberg.examples;
