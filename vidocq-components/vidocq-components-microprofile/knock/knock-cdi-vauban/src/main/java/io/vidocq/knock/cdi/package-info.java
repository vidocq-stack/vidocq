/**
 * Knock Vauban CDI integration — public package (empty in M2).
 *
 * <p>The implementation lives in {@code io.vidocq.knock.cdi.internal} (not exported):</p>
 * <ul>
 *   <li>{@code HealthCheckCdiExtension} — CDI 4.1 BCE (probe qualifier validation)</li>
 *   <li>{@code KnockCdiHealthCheckRegistry} — injectable {@code @ApplicationScoped} bean</li>
 *   <li>{@code HealthCheckRegistrar} — automatic registration at CDI startup</li>
 * </ul>
 */
package io.vidocq.knock.cdi;
