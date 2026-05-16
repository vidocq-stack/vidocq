/**
 * SPI publique stable de Heisenberg — contrats entre {@code heisenberg-core}
 * et {@code heisenberg-cdi-vauban}, et entre Heisenberg et les adaptateurs externes.
 *
 * <p>Contenu prévu (ROADMAP.md M1+) :</p>
 * <ul>
 *   <li>{@code PolicyContext} — contexte d'invocation enrichi (méthode, bean, annotations FT).</li>
 *   <li>{@code RetryConfig}, {@code TimeoutConfig}, {@code CircuitBreakerConfig},
 *       {@code BulkheadConfig} — configurations immuables (records Java 25).</li>
 *   <li>{@code FaultToleranceException} — exception de base du projet.</li>
 * </ul>
 */
package io.vidocq.heisenberg.api;
