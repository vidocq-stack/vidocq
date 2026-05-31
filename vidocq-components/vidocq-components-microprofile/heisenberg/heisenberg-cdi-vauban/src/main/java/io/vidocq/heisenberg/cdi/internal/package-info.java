/**
 * Vauban CDI integration for Heisenberg — interceptor and BCE.
 *
 * <p>Planned contents (ROADMAP.md M1+) :</p>
 * <ul>
 *   <li>{@code FaultToleranceInterceptor} — CDI {@code @Interceptor} orchestrating the
 *       core {@code PolicyComposer} for each annotated method invocation.</li>
 *   <li>{@code HeisenbergExtension} — BCE validating at startup the consistency of
 *       FT annotations (fallbackMethod exists, compatible @Asynchronous return type).</li>
 *   <li>{@code StateRegistryBean} — {@code @ApplicationScoped} storing the state of
 *       {@code CircuitBreaker} and {@code Bulkhead} in a {@code ConcurrentHashMap}.</li>
 * </ul>
 */
package io.vidocq.heisenberg.cdi.internal;
