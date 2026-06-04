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
 * Cervantes JAX-RS security (MicroProfile JWT 2.1): bearer token authentication and role-based
 * authorization.
 *
 * <ul>
 *   <li>{@link io.vidocq.cervantes.jaxrs.JwtAuthenticationFilter} — validates the token and sets
 *       the {@link io.vidocq.cervantes.jaxrs.JwtSecurityContext}.</li>
 *   <li>{@link io.vidocq.cervantes.jaxrs.RolesAllowedDynamicFeature} +
 *       {@link io.vidocq.cervantes.jaxrs.RolesAllowedRequestFilter} — enforce
 *       {@code @RolesAllowed}/{@code @PermitAll}/{@code @DenyAll}.</li>
 * </ul>
 *
 * <p>Uses only the standard JAX-RS API (impl-agnostic); filters are CDI {@code @Provider} beans
 * discovered by the Cassini {@code BeanProvider}.</p>
 */
package io.vidocq.cervantes.jaxrs;
