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

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SecurityContext backed by a JsonWebToken (roles = groups claim). */
class JwtSecurityContextTest {

    @Test
    void exposesPrincipalRolesAndScheme() {
        JsonWebToken jwt = CassiniTestDoubles.token("alice", Set.of("admin", "user"));
        JwtSecurityContext sc = new JwtSecurityContext(jwt, true);

        assertSame(jwt, sc.getUserPrincipal());
        assertTrue(sc.isUserInRole("admin"));
        assertTrue(sc.isUserInRole("user"));
        assertFalse(sc.isUserInRole("root"));
        assertTrue(sc.isSecure());
        assertEquals(JwtSecurityContext.MP_JWT, sc.getAuthenticationScheme());
    }

    @Test
    void insecureTransportReportedAsNotSecure() {
        JwtSecurityContext sc = new JwtSecurityContext(CassiniTestDoubles.token("bob", Set.of()), false);
        assertFalse(sc.isSecure());
        assertFalse(sc.isUserInRole("any"));
    }
}
