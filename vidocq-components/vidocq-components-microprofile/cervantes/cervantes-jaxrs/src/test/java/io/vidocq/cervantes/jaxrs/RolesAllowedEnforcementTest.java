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

import io.vidocq.cervantes.jaxrs.CassiniTestDoubles.CapturingFeatureContext;
import io.vidocq.cervantes.jaxrs.CassiniTestDoubles.FakeRequestContext;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Application de {@code @RolesAllowed}/{@code @PermitAll}/{@code @DenyAll} (MicroProfile JWT 2.1 §1.1) :
 * précédence méthode > classe et codes 200/401/403.
 */
class RolesAllowedEnforcementTest {

    // --- ressources de test ---

    static class OpenResource {
        @RolesAllowed("admin") public void adminOnly() {}
        @PermitAll public void open() {}
        @DenyAll public void forbidden() {}
        public void unannotated() {}
    }

    @RolesAllowed("user")
    static class SecuredResource {
        public void inherited() {}
        @PermitAll public void openMethod() {}
        @RolesAllowed("admin") public void elevated() {}
    }

    // --- helpers ---

    private static ContainerRequestFilter filterFor(Class<?> clazz, String method) throws Exception {
        CapturingFeatureContext ctx = new CapturingFeatureContext();
        new RolesAllowedDynamicFeature()
                .configure(CassiniTestDoubles.resourceInfo(clazz, clazz.getMethod(method)), ctx);
        return ctx.registered.isEmpty() ? null : (ContainerRequestFilter) ctx.registered.get(0);
    }

    private static int statusFor(ContainerRequestFilter filter, SecurityContext sc) throws Exception {
        FakeRequestContext rc = new FakeRequestContext();
        if (sc != null) rc.securityContext(sc);
        filter.filter(rc); // ContainerRequestFilter#filter déclare throws IOException
        return rc.isAborted() ? rc.abortedStatus() : 200;
    }

    private static SecurityContext user(String name, String... roles) {
        return new JwtSecurityContext(CassiniTestDoubles.token(name, Set.of(roles)), false);
    }

    // --- méthode niveau ---

    @Test
    void rolesAllowed_grantsMatchingRole_rejectsOthers() throws Exception {
        ContainerRequestFilter filter = filterFor(OpenResource.class, "adminOnly");
        assertNotNull(filter);
        assertEquals(200, statusFor(filter, user("alice", "admin")));
        assertEquals(403, statusFor(filter, user("bob", "user")));   // authentifié, mauvais rôle
        assertEquals(401, statusFor(filter, null));                   // non authentifié
    }

    @Test
    void permitAll_registersNoFilter() throws Exception {
        assertNull(filterFor(OpenResource.class, "open"));
    }

    @Test
    void denyAll_alwaysForbidden() throws Exception {
        ContainerRequestFilter filter = filterFor(OpenResource.class, "forbidden");
        assertNotNull(filter);
        assertEquals(403, statusFor(filter, user("alice", "admin"))); // même un admin est refusé
    }

    @Test
    void unannotatedMethodOnOpenClass_isOpen() throws Exception {
        assertNull(filterFor(OpenResource.class, "unannotated"));
    }

    // --- précédence classe ---

    @Test
    void classLevelRolesAllowed_appliesToUnannotatedMethod() throws Exception {
        ContainerRequestFilter filter = filterFor(SecuredResource.class, "inherited");
        assertNotNull(filter);
        assertEquals(200, statusFor(filter, user("alice", "user")));
        assertEquals(403, statusFor(filter, user("bob", "guest")));
    }

    @Test
    void methodPermitAll_overridesClassRolesAllowed() throws Exception {
        assertNull(filterFor(SecuredResource.class, "openMethod"));
    }

    @Test
    void methodRolesAllowed_overridesClassRolesAllowed() throws Exception {
        ContainerRequestFilter filter = filterFor(SecuredResource.class, "elevated");
        assertNotNull(filter);
        assertEquals(200, statusFor(filter, user("alice", "admin")));
        assertEquals(403, statusFor(filter, user("carol", "user"))); // le rôle classe "user" ne suffit plus
    }
}
