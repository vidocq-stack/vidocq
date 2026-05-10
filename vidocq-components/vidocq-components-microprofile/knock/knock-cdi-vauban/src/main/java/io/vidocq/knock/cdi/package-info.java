/**
 * Intégration CDI Vauban de Knock — package public (vide en M2).
 *
 * <p>L'implémentation est dans {@code io.vidocq.knock.cdi.internal} (non exporté) :</p>
 * <ul>
 *   <li>{@code HealthCheckCdiExtension} — BCE CDI 4.1 (validation probe qualifier)</li>
 *   <li>{@code KnockCdiHealthCheckRegistry} — bean {@code @ApplicationScoped} injectable</li>
 *   <li>{@code HealthCheckRegistrar} — enregistrement automatique au démarrage CDI</li>
 * </ul>
 */
package io.vidocq.knock.cdi;
