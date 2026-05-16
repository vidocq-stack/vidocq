/**
 * Intégration CDI Vauban de Heisenberg — intercepteur et BCE.
 *
 * <p>Contenu prévu (ROADMAP.md M1+) :</p>
 * <ul>
 *   <li>{@code FaultToleranceInterceptor} — {@code @Interceptor} CDI orchestrant le
 *       {@code PolicyComposer} du core pour chaque invocation de méthode annotée.</li>
 *   <li>{@code HeisenbergExtension} — BCE validant au démarrage la cohérence des
 *       annotations FT (fallbackMethod existe, type de retour @Asynchronous compatible).</li>
 *   <li>{@code StateRegistryBean} — {@code @ApplicationScoped} stockant l'état des
 *       {@code CircuitBreaker} et {@code Bulkhead} dans un {@code ConcurrentHashMap}.</li>
 * </ul>
 */
package io.vidocq.heisenberg.cdi.internal;
