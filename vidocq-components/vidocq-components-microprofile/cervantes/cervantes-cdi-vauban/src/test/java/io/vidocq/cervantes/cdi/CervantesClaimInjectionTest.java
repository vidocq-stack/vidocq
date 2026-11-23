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
package io.vidocq.cervantes.cdi;

import io.vidocq.cervantes.internal.DefaultJsonWebToken;
import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.eclipse.microprofile.jwt.Claim;
import org.eclipse.microprofile.jwt.ClaimValue;
import org.eclipse.microprofile.jwt.Claims;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Injection {@code @Claim} typed under Vauban container (MicroProfile JWT 2.2 §"Injection of JSON
 * Web Token claims"). {@link CervantesClaimExtension} synthesizes a bean by type encountered;
 * {@link io.vidocq.cervantes.cdi.internal.ClaimResolver} reads the {@link DefaultJsonWebToken} of the
 * current request on {@link JsonWebTokenContext}.
 */
class CervantesClaimInjectionTest {

    /** How the start gets what {@link CervantesClaimExtension} did. */
    enum ExtensionsAtStart {
        /** It runs the extension. */
        RUN,
        /** It reads the record a build froze, and runs no extension (Vidocq/vauban#288). */
        FROZEN
    }

    @TempDir
    Path output;

    private VaubanContainerBuilder deployment() {
        return VaubanContainer.builder()
                .classLoader(outputLoader())
                .addBeanClass(CervantesClaimExtension.class)
                .addBeanClass(JsonWebTokenContext.class)
                .addBeanClass(ClaimConsumer.class);
    }

    /** The loader of a start: the build's output first, where a record is, then the classes of this test. */
    private ClassLoader outputLoader() {
        try {
            return new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader());
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonObject claims() {
        return Json.createObjectBuilder()
                .add("iss", "https://issuer.vidocq.dev")
                .add("sub", "u-42")
                .add("upn", "alice")
                .add("groups", Json.createArrayBuilder().add("admin").add("user"))
                .add("roleCount", 3)
                .add("active", true)
                .add("address", Json.createObjectBuilder().add("city", "Paris"))
                .build();
    }

    /**
     * The claim types the extension collects at {@code @Registration} reach the {@code @Synthesis} of the same run,
     * and the synthetic beans that phase registers are what a build freezes: a start from the record runs no
     * extension and injects the same claims (BUG-20261123-48).
     */
    @ParameterizedTest
    @EnumSource(ExtensionsAtStart.class)
    void resolvesTypedClaimsFromTheRequestToken(ExtensionsAtStart extensions) {
        if (extensions == ExtensionsAtStart.FROZEN) {
            var outcome = deployment().freezeExtensions(output);
            assertTrue(outcome.frozen(), outcome.reason());
        }
        try (var container = deployment().build()) {
            assertEquals(extensions == ExtensionsAtStart.FROZEN, container.extensionsAtStart().frozen(), "frozen");
            assertEquals(extensions == ExtensionsAtStart.FROZEN ? List.of() : List.of(CervantesClaimExtension.class.getName()),
                    container.extensionsAtStart().ranAtStart(), "extensions run at start");

            container.requestContext().runInScope(() -> {
                JsonWebTokenContext context = container.select(JsonWebTokenContext.class);
                context.setToken(new DefaultJsonWebToken(claims(), "raw-token"));

                ClaimConsumer consumer = container.select(ClaimConsumer.class);

                assertEquals("alice", consumer.upn, "@Claim(standard=upn) String");
                assertEquals(Set.of("admin", "user"), consumer.groups, "@Claim(\"groups\") Set<String>");
                assertEquals(3L, consumer.roleCount, "@Claim(\"roleCount\") Long");
                assertTrue(consumer.active, "@Claim(\"active\") boolean");

                assertNotNull(consumer.upnValue, "@Claim ClaimValue<String> injected");
                assertEquals("upn", consumer.upnValue.getName());
                assertEquals("alice", consumer.upnValue.getValue());

                assertTrue(consumer.missing.isEmpty(), "@Claim(\"missing\") Optional<String> → empty");

                assertNotNull(consumer.address, "@Claim(\"address\") JsonObject injected");
                assertEquals("Paris", consumer.address.getString("city"));

                assertEquals("alice", consumer.upnProvider.get(), "@Claim Provider<String> resolves lazily");
            });
        }
    }

    @Test
    void claimValueReflectsTheCurrentRequestTokenLazily() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(CervantesClaimExtension.class)
                .addBeanClass(JsonWebTokenContext.class)
                .addBeanClass(ClaimConsumer.class)
                .build()) {

            // Scope 1 : alice
            container.requestContext().runInScope(() -> {
                container.select(JsonWebTokenContext.class).setToken(new DefaultJsonWebToken(claims(), "raw"));
                ClaimValue<String> upn = container.select(ClaimConsumer.class).upnValue;
                assertEquals("alice", upn.getValue());
            });

            //Scope 2: bob — a new token, a new value resolved lazyly
            container.requestContext().runInScope(() -> {
                JsonObject bob = Json.createObjectBuilder().add("sub", "u-7").add("upn", "bob").build();
                container.select(JsonWebTokenContext.class).setToken(new DefaultJsonWebToken(bob, "raw"));
                ClaimValue<String> upn = container.select(ClaimConsumer.class).upnValue;
                assertEquals("bob", upn.getValue());
            });
        }
    }

    @Dependent
    public static class ClaimConsumer {
        @Inject @Claim(standard = Claims.upn)
        public String upn;

        @Inject @Claim("groups")
        public Set<String> groups;

        @Inject @Claim("roleCount")
        public Long roleCount;

        @Inject @Claim("active")
        public boolean active;

        @Inject @Claim("upn")
        public ClaimValue<String> upnValue;

        @Inject @Claim("missing")
        public Optional<String> missing;

        @Inject @Claim("address")
        public JsonObject address;

        @Inject @Claim("upn")
        public Provider<String> upnProvider;
    }
}
