/**
 * Benchmarks JMH pour Heisenberg.
 *
 * <p>Contenu prévu (ROADMAP.md M3+) :</p>
 * <ul>
 *   <li>{@code InterceptorOverheadBenchmark} — overhead d'interception sans politique active.</li>
 *   <li>{@code RetryBenchmark} — throughput sous retry vs SmallRye Fault Tolerance.</li>
 *   <li>{@code CircuitBreakerBenchmark} — latence p99 avec CB en état CLOSED vs OPEN.</li>
 *   <li>{@code BulkheadBenchmark} — contention sémaphore sous charge concurrente.</li>
 * </ul>
 */
package io.vidocq.heisenberg.bench;
