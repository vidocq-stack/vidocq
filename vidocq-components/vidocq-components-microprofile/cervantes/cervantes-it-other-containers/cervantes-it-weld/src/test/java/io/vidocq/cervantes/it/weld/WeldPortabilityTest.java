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
package io.vidocq.cervantes.it.weld;

import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.JwtValidator;
import io.vidocq.cervantes.jaxrs.JwtAuthenticationFilter;
import io.vidocq.cervantes.jaxrs.RolesAllowedDynamicFeature;
import jakarta.enterprise.context.control.RequestContextController;
import java.security.KeyPair;
import java.util.Set;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Cervantes jars, unchanged, under Weld SE on a class path (vidocq-workspace#15, cervantes#24):
 * the build compatible extension registers the {@code @Claim} beans, the validator is built from
 * MicroProfile Config, the authentication filter bean validates a bearer token, and the application
 * reads the caller through {@code JsonWebToken} and {@code @Claim}. Nothing generated for Vauban is used.
 */
class WeldPortabilityTest {

    private static final KeyPair KEYS = Tokens.newKeyPair();

    private static WeldContainer container;

    @BeforeAll
    static void start() {
        // MP JWT 2.1 §9: read through MicroProfile Config, here from system properties (Ravel).
        System.setProperty("mp.jwt.verify.publickey", Tokens.publicKey(KEYS));
        System.setProperty("mp.jwt.verify.issuer", Tokens.ISSUER);
        container = new Weld().initialize();
    }

    @AfterAll
    static void stop() {
        if (container != null) {
            container.close();
        }
        System.clearProperty("mp.jwt.verify.publickey");
        System.clearProperty("mp.jwt.verify.issuer");
    }

    @Test
    void vaubanIsNotOnTheClassPath() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.vidocq.vauban.core.container.VaubanContainer"));
    }

    @Test
    void jaxrsProvidersAreBeans() {
        assertTrue(container.select(JwtAuthenticationFilter.class).isResolvable(), "authentication filter");
        assertTrue(container.select(RolesAllowedDynamicFeature.class).isResolvable(), "@RolesAllowed feature");
    }

    @Test
    void validTokenAuthenticatesAndClaimsAreInjected() {
        String token = Tokens.sign(KEYS.getPrivate(), "alice", "admin", "user");
        inRequest(() -> {
            FakeRequest request = new FakeRequest("Bearer " + token);
            container.select(JwtAuthenticationFilter.class).get().filter(request.context());

            assertFalse(request.aborted());
            assertEquals("alice", request.securityContext().getUserPrincipal().getName());
            assertTrue(request.securityContext().isUserInRole("admin"));
            assertFalse(request.securityContext().isUserInRole("auditor"));

            CallerClaims caller = container.select(CallerClaims.class).get();
            assertEquals("alice", caller.tokenName());
            assertEquals("alice", caller.upn());
            assertEquals(Set.of("admin", "user"), caller.groups());
            assertEquals("id-alice", caller.subject());
        });
    }

    @Test
    void requestWithoutTokenStaysAnonymous() {
        inRequest(() -> {
            FakeRequest request = new FakeRequest(null);
            container.select(JwtAuthenticationFilter.class).get().filter(request.context());
            assertFalse(request.aborted());
            assertNull(request.securityContext());
        });
    }

    @Test
    void tokenSignedByAnotherKeyIsRejected() {
        String forged = Tokens.sign(Tokens.newKeyPair().getPrivate(), "mallory", "admin");
        JwtValidator validator = container.select(JwtValidator.class).get();
        assertThrows(JwtValidationException.class, () -> validator.validate(forged));
    }

    private static void inRequest(Runnable body) {
        RequestContextController controller = container.select(RequestContextController.class).get();
        controller.activate();
        try {
            body.run();
        } finally {
            controller.deactivate();
        }
    }
}
