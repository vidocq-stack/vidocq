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

import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.Provider;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Applies JSR-250 authorization annotations on JAX-RS resource methods
 * (MicroProfile JWT 2.2 §1.1). For each method, registers a {@link RolesAllowedRequestFilter}
 * according to the effective annotation.
 *
 * <p>Precedence: annotations at <em>method</em> level override those at <em>class</em> level.
 * On a given target, order is {@code @DenyAll} &gt; {@code @RolesAllowed} &gt;
 * {@code @PermitAll}. A method with no effective annotation remains open (no filter).</p>
 */
@Provider
@ApplicationScoped
public class RolesAllowedDynamicFeature implements DynamicFeature {

    @Override
    public void configure(ResourceInfo resourceInfo, FeatureContext context) {
        Method method = resourceInfo.getResourceMethod();

        // Method level (takes precedence).
        if (method.isAnnotationPresent(DenyAll.class)) {
            context.register(RolesAllowedRequestFilter.denyAll());
            return;
        }
        RolesAllowed methodRoles = method.getAnnotation(RolesAllowed.class);
        if (methodRoles != null) {
            context.register(RolesAllowedRequestFilter.rolesAllowed(Set.of(methodRoles.value())));
            return;
        }
        if (method.isAnnotationPresent(PermitAll.class)) {
            return; // explicit @PermitAll: no constraint
        }

        // Fall back to class level.
        Class<?> resourceClass = resourceInfo.getResourceClass();
        if (resourceClass.isAnnotationPresent(DenyAll.class)) {
            context.register(RolesAllowedRequestFilter.denyAll());
            return;
        }
        RolesAllowed classRoles = resourceClass.getAnnotation(RolesAllowed.class);
        if (classRoles != null) {
            context.register(RolesAllowedRequestFilter.rolesAllowed(Set.of(classRoles.value())));
        }
        // Otherwise: no security annotation → open endpoint.
    }
}
