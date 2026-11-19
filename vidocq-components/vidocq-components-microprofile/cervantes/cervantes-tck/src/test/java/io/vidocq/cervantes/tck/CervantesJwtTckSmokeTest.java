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
package io.vidocq.cervantes.tck;

import io.vidocq.cervantes.api.JwtValidator;
import io.vidocq.cervantes.cdi.JsonWebTokenContext;
import io.vidocq.cervantes.cdi.internal.JwtAuthConfigProducer;
import io.vidocq.cervantes.jaxrs.JwtAuthenticationFilter;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.jwt.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Smoke test: checks that Cervantes modules and JWT 2.2 spec MP
 * are of course classpath and that the main classes are accessible.
 * Run by the "smoke" profile (default active) without Arquillian.
 */
class CervantesJwtTckSmokeTest {

    @Test
    void mpJwtApiOnClasspath() {
        // org.eclipse.microprofile.jwt.JsonWebToken must be accessible
        assertNotNull(JsonWebToken.class.getAnnotation(java.lang.annotation.Retention.class) != null
                        ? JsonWebToken.class
                        : JsonWebToken.class,
                "JsonWebToken interface must be on classpath");

        // Claims enum must be accessible
        assertNotNull(Claims.class, "@Claims enum must be accessible");
    }

    @Test
    void cervantesModulesOnClasspath() {
        assertNotNull(JwtValidator.class, "JwtValidator (cervantes-api) must be on classpath");
        assertNotNull(JsonWebTokenContext.class, "JsonWebTokenContext (cervantes-cdi-vauban) must be on classpath");
        assertNotNull(JwtAuthConfigProducer.class, "JwtAuthConfigProducer must be on classpath");
        assertNotNull(JwtAuthenticationFilter.class, "JwtAuthenticationFilter (cervantes-jaxrs) must be on classpath");
    }
}
