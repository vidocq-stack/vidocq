/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
/**
 * Sécurité JAX-RS de Cervantes (MicroProfile JWT 2.1) : authentification par bearer token et
 * autorisation par rôles.
 *
 * <ul>
 *   <li>{@link io.vidocq.cervantes.jaxrs.JwtAuthenticationFilter} — valide le token et pose le
 *       {@link io.vidocq.cervantes.jaxrs.JwtSecurityContext}.</li>
 *   <li>{@link io.vidocq.cervantes.jaxrs.RolesAllowedDynamicFeature} +
 *       {@link io.vidocq.cervantes.jaxrs.RolesAllowedRequestFilter} — appliquent
 *       {@code @RolesAllowed}/{@code @PermitAll}/{@code @DenyAll}.</li>
 * </ul>
 *
 * <p>N'utilise que l'API JAX-RS standard (impl-agnostique) ; les filtres sont des beans CDI
 * {@code @Provider} découverts par le {@code BeanProvider} de Cassini.</p>
 */
package io.vidocq.cervantes.jaxrs;
