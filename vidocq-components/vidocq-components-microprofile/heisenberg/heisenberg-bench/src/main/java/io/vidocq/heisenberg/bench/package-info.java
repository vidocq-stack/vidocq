/**
 * JMH benchmarks for Heisenberg.
 *
 * <p>Planned contents (ROADMAP.md M3+) :</p>
 * <ul>
 *   <li>{@code InterceptorOverheadBenchmark} — interception overhead with no active policy.</li>
 *   <li>{@code RetryBenchmark} — throughput under retry vs SmallRye Fault Tolerance.</li>
 *   <li>{@code CircuitBreakerBenchmark} — p99 latency with CB in CLOSED vs OPEN state.</li>
 *   <li>{@code BulkheadBenchmark} — semaphore contention under concurrent load.</li>
 * </ul>
 */
package io.vidocq.heisenberg.bench;
