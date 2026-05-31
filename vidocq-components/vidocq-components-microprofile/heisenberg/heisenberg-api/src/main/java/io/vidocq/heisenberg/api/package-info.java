/**
 * Stable public Heisenberg SPI — contracts between {@code heisenberg-core}
 * and {@code heisenberg-cdi-vauban}, and between Heisenberg and external adapters.
 *
 * <p>Planned contents (ROADMAP.md M1+) :</p>
 * <ul>
 *   <li>{@code PolicyContext} — enriched invocation context (method, bean, FT annotations).</li>
 *   <li>{@code RetryConfig}, {@code TimeoutConfig}, {@code CircuitBreakerConfig},
 *       {@code BulkheadConfig} — immutable configurations (Java 25 records).</li>
 *   <li>{@code FaultToleranceException} — the project's base exception.</li>
 * </ul>
 */
package io.vidocq.heisenberg.api;
