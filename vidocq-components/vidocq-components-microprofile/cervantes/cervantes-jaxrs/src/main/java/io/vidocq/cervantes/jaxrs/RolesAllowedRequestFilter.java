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
package io.vidocq.cervantes.jaxrs;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.util.Set;

/**
 * Enforces authorization for a resource method (registered by
 * {@link RolesAllowedDynamicFeature}). Priority {@link Priorities#AUTHORIZATION}: runs after
 * the {@link JwtAuthenticationFilter}, so {@code getSecurityContext()} already reflects the validated token.
 *
 * <ul>
 *   <li>{@code @DenyAll} → always {@code 403}.</li>
 *   <li>{@code @RolesAllowed} → {@code 401} if not authenticated, {@code 403} if no role matches.</li>
 * </ul>
 * ({@code @PermitAll} and unannotated methods register no filter.)
 */
@Priority(Priorities.AUTHORIZATION)
public final class RolesAllowedRequestFilter implements ContainerRequestFilter {

    private final Set<String> rolesAllowed;
    private final boolean denyAll;

    private RolesAllowedRequestFilter(Set<String> rolesAllowed, boolean denyAll) {
        this.rolesAllowed = rolesAllowed;
        this.denyAll = denyAll;
    }

    static RolesAllowedRequestFilter denyAll() {
        return new RolesAllowedRequestFilter(Set.of(), true);
    }

    static RolesAllowedRequestFilter rolesAllowed(Set<String> roles) {
        return new RolesAllowedRequestFilter(Set.copyOf(roles), false);
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (denyAll) {
            abort(requestContext, Response.Status.FORBIDDEN);
            return;
        }
        SecurityContext security = requestContext.getSecurityContext();
        Principal user = security == null ? null : security.getUserPrincipal();
        if (user == null) {
            abort(requestContext, Response.Status.UNAUTHORIZED); // not authenticated
            return;
        }
        boolean granted = rolesAllowed.stream().anyMatch(security::isUserInRole);
        if (!granted) {
            abort(requestContext, Response.Status.FORBIDDEN); // authenticated but role missing
        }
    }

    private static void abort(ContainerRequestContext requestContext, Response.Status status) {
        requestContext.abortWith(Response.status(status).build());
    }
}
