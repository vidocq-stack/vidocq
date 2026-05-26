/**
 * Sécurité JAX-RS de Cervantes (MicroProfile JWT 2.1) : authentification par bearer token et
 * autorisation par rôles.
 *
 * <ul>
 *   <li>{@link io.vidocq.cervantes.cassini.JwtAuthenticationFilter} — valide le token et pose le
 *       {@link io.vidocq.cervantes.cassini.JwtSecurityContext}.</li>
 *   <li>{@link io.vidocq.cervantes.cassini.RolesAllowedDynamicFeature} +
 *       {@link io.vidocq.cervantes.cassini.RolesAllowedRequestFilter} — appliquent
 *       {@code @RolesAllowed}/{@code @PermitAll}/{@code @DenyAll}.</li>
 * </ul>
 *
 * <p>N'utilise que l'API JAX-RS standard (impl-agnostique) ; les filtres sont des beans CDI
 * {@code @Provider} découverts par le {@code BeanProvider} de Cassini.</p>
 */
package io.vidocq.cervantes.cassini;
